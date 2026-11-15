package main

import (
	"encoding/json"
	"fmt"
	"log"
	"math"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

// --------------------------------------------------------------------------
// 天气（高德 Web 服务 API）
// --------------------------------------------------------------------------
//
// ⚠️ **这是「预报」，不是「实测」** —— 车上传感器给的「车外温度」是**实测**，
// 高德给的是「未来几天的预报」⇒ 两件不同的事，文案必须分清（ADR #38 / #41）。
//
// ⚠️ **天气接口只吃 city（adcode / 城市名），不吃经纬度**
// （2026-11-16 实测：location=… 返回 INVALID_PARAMS）
// ⇒ 先逆地理拿 adcode，再查天气（**两次**调用 ⇒ 都必须带缓存）。
//
// ⚠️ **拿不到就说「不知道」**：没配 AMAP_KEY / API 失败 / 车没有位置
// ⇒ 一律返回 nil（**不是**「天气没问题」）—— 宁可说不知道，不可编。

const (
	amapRegeoURL   = "https://restapi.amap.com/v3/geocode/regeo"
	amapWeatherURL = "https://restapi.amap.com/v3/weather/weatherInfo"

	// 天气预报的缓存：30 分钟。
	// ⚠️ 为什么要缓存：推送脚本每 5 分钟跑一次、App 每次刷新都拉 /status
	//   ⇒ 不缓存就是几十次/小时的高德调用（还容易撞 QPS 限制：实测连打 4 次就报
	//   CUQPS_HAS_EXCEEDED_THE_LIMIT）。
	weatherCacheTTL = 30 * time.Minute
	// 逆地理（坐标 → adcode）的缓存：12 小时。
	// 键是**取整到 0.1°** 的坐标（约 11 公里）⇒ 车开到别的城市 ⇒ 键变了 ⇒ 自然失效。
	weatherAdcodeTTL = 12 * time.Hour
	// **失败**只缓存 5 分钟：一次网络抖动不该把天气堵掉半天。
	weatherFailTTL = 5 * time.Minute
	// 判定只看**今天 + 明天**（更远的预报不值一提）。
	weatherForecastDays = 2
)

// 天气风险的档位（服务端唯一定义处）。
const (
	WeatherRiskNone   = "none"   // 取到了，但没有值得提醒的天气
	WeatherRiskSevere = "severe" // 极端天气（台风 / 飓风 / 龙卷风 / 冰雹）
	WeatherRiskRain   = "rain"   // 暴雨量级
	WeatherRiskIce    = "ice"    // 结冰
	WeatherRiskHeat   = "heat"   // 极端高温
)

// ⚠️ **v0.164.0：这就是「什么条件下才值得打扰」的答案（ADR #79）。**
const (
	// 极端高温（℃）：≥ 这个数才算值得提醒。
	// ⚠️ 为什么不是 35℃：35℃ 在夏天是**常态** ⇒ 天天推就是噪音。
	WeatherHeatC = 40
	// 结冰（℃）：预报最低温 ≤ 这个数。
	WeatherIceC = 0
)

// 值得推的天气词（高德官方 WeatherType 的取值）。
//
// ⚠️ **雷阵雨 / 大雨 / 小雨 / 多云 一律不在表里** —— 那是常态，推了就是噪音。
var (
	weatherSevereWords = []string{"台风", "飓风", "热带风暴", "龙卷风", "冰雹"}
	weatherRainWords   = []string{"暴雨", "大暴雨", "特大暴雨"}
)

// 各档的**建议**（服务端唯一一份：ADR #41）。
const (
	weatherSevereAdvice = "这种天气可能有大风或冰雹；车停在露天的话留意，出发前查路况。"
	weatherRainAdvice   = "暴雨容易积水，停在露天留意水位；雨天开山路当心湿滑。"
	weatherIceAdvice    = "玻璃和雨刷会结冰，电池性能也会下降；出发前先除霜、慢点开。"
	weatherHeatAdvice   = "车里会非常烫，上车前先开门窗通风，别一坐下就猛开冷气。"
)

// WeatherCast 一天的预报（高德 casts[] 的一条）。
type WeatherCast struct {
	Date         string `json:"date"`
	Weekday      string `json:"weekday"`
	DayWeather   string `json:"dayweather"`
	DayTemp      int    `json:"daytemp"`
	NightWeather string `json:"nightweather"`
	NightTemp    int    `json:"nighttemp"`
}

// WeatherRisk 天气的判定结论（**服务端唯一一份**：ADR #38 / #41）。
type WeatherRisk struct {
	// Alert = 值得打扰用户吗（**只有这一档才推飞书**）。
	Alert bool `json:"alert"`
	// Level = none / severe / rain / ice / heat
	Level string `json:"level"`
	// What = 一句话结论（服务端渲染的中文，唯一一份）。
	What string `json:"what"`
	// Advice = 该做什么（服务端渲染的中文，唯一一份）。
	Advice  string     `json:"advice"`
	City    string     `json:"city"`
	When    string     `json:"when"`
	TempMin int        `json:"temp_min_c"`
	TempMax int        `json:"temp_max_c"`
	At      *time.Time `json:"at"`
}

// EvaluateWeatherRisk 判未来几天有没有「值得打扰用户」的天气（**纯函数** ⇒ 单测钉得住）。
//
// ⚠️ **优先级**：极端天气 > 暴雨 > 结冰 > 极端高温
//
//	（极端高温在南方是常态级别 ⇒ 排最后：真到了 40℃ 才值得说一句）。
func EvaluateWeatherRisk(casts []WeatherCast, city string) *WeatherRisk {
	if len(casts) == 0 {
		return nil
	}
	type hit struct {
		level  string
		what   string
		advice string
	}
	var severe, rain, ice, heat *hit
	tmin, tmax := math.MaxInt, math.MinInt

	for i, c := range casts {
		if i >= weatherForecastDays {
			break
		}
		lo, hi := c.NightTemp, c.DayTemp
		if c.DayTemp < lo {
			lo = c.DayTemp
		}
		if c.NightTemp > hi {
			hi = c.NightTemp
		}
		if lo < tmin {
			tmin = lo
		}
		if hi > tmax {
			tmax = hi
		}
		if w := pickWeatherWord(c.DayWeather, c.NightWeather, weatherSevereWords); w != "" {
			severe = &hit{WeatherRiskSevere,
				fmt.Sprintf("%s 有%s", c.Date, w), weatherSevereAdvice}
		}
		if w := pickWeatherWord(c.DayWeather, c.NightWeather, weatherRainWords); w != "" {
			rain = &hit{WeatherRiskRain,
				fmt.Sprintf("%s 有%s", c.Date, w), weatherRainAdvice}
		}
		if lo <= WeatherIceC {
			ice = &hit{WeatherRiskIce,
				fmt.Sprintf("%s 最低 %d℃，会结冰", c.Date, lo), weatherIceAdvice}
		}
		if hi >= WeatherHeatC {
			heat = &hit{WeatherRiskHeat,
				fmt.Sprintf("%s 最高 %d℃，车里会暴晒", c.Date, hi), weatherHeatAdvice}
		}
	}

	out := &WeatherRisk{Level: WeatherRiskNone, City: city}
	if tmin <= tmax {
		out.TempMin, out.TempMax = tmin, tmax
	}
	for _, h := range []*hit{severe, rain, ice, heat} {
		if h != nil {
			out.Alert = true
			out.Level = h.level
			out.What = h.what
			out.Advice = h.advice
			break
		}
	}
	return out
}

// pickWeatherWord 从白天/夜里的天气词里挑第一个命中的。
func pickWeatherWord(day, night string, words []string) string {
	for _, w := range words {
		if strings.Contains(day, w) || strings.Contains(night, w) {
			return w
		}
	}
	return ""
}

// ---------------------------------------------------------------------------
// 取数（带缓存）
// ---------------------------------------------------------------------------

var (
	weatherMu      sync.Mutex
	weatherGeoKey  string
	weatherGeoAd   string
	weatherGeoCity string
	weatherGeoAt   time.Time
	weatherGeoOK   bool
	weatherCastKey string
	weatherCastAt  time.Time
	weatherCastOK  bool
	weatherCasts   []WeatherCast
)

// currentWeatherRisk 取当前天气并判定（**服务端唯一一份**）。
//
// ⚠️ nil = **不知道**（没位置 / 没配 key / API 失败）⇒ 消费方不许说成「天气没问题」。
func currentWeatherRisk(lat, lng *float64) *WeatherRisk {
	if lat == nil || lng == nil {
		return nil
	}
	weatherMu.Lock()
	defer weatherMu.Unlock()

	adcode, city, ok := amapAdcodeLocked(*lng, *lat)
	if !ok {
		return nil
	}
	casts, ok := amapForecastLocked(adcode)
	if !ok {
		return nil
	}
	r := EvaluateWeatherRisk(casts, city)
	if r == nil {
		return nil
	}
	now := time.Now()
	r.At = &now
	return r
}

// amapAdcodeLocked 坐标 → adcode（**调用方必须已持 weatherMu**）。
func amapAdcodeLocked(lng, lat float64) (string, string, bool) {
	key := fmt.Sprintf("%.1f,%.1f", math.Round(lng*10)/10, math.Round(lat*10)/10)
	ttl := weatherAdcodeTTL
	if !weatherGeoOK {
		ttl = weatherFailTTL
	}
	if key == weatherGeoKey && time.Since(weatherGeoAt) < ttl {
		return weatherGeoAd, weatherGeoCity, weatherGeoOK
	}

	v := url.Values{}
	v.Set("location", fmt.Sprintf("%.6f,%.6f", lng, lat))
	var resp struct {
		Status    string `json:"status"`
		Info      string `json:"info"`
		Regeocode struct {
			AddressComponent struct {
				City     string `json:"city"`
				District string `json:"district"`
				Adcode   string `json:"adcode"`
			} `json:"addressComponent"`
		} `json:"regeocode"`
	}
	if err := amapGetJSON(amapRegeoURL, v, &resp); err != nil || resp.Status != "1" {
		// ⚠️ 只记原因，**不打 URL**（key 在 query 里 ⇒ 会进日志）。
		log.Printf("⚠️ 天气：逆地理失败（err=%v status=%s info=%s）", err, resp.Status, resp.Info)
		weatherGeoKey, weatherGeoAt, weatherGeoOK = key, time.Now(), false
		weatherGeoAd, weatherGeoCity = "", ""
		return "", "", false
	}
	ac := resp.Regeocode.AddressComponent
	name := ac.District
	if name == "" {
		name = ac.City
	}
	weatherGeoKey, weatherGeoAt = key, time.Now()
	weatherGeoAd, weatherGeoCity, weatherGeoOK = ac.Adcode, name, ac.Adcode != ""
	return weatherGeoAd, weatherGeoCity, weatherGeoOK
}

// amapForecastLocked adcode → 未来几天预报（**调用方必须已持 weatherMu**）。
func amapForecastLocked(adcode string) ([]WeatherCast, bool) {
	if adcode == "" {
		return nil, false
	}
	ttl := weatherCacheTTL
	if !weatherCastOK {
		ttl = weatherFailTTL
	}
	if adcode == weatherCastKey && time.Since(weatherCastAt) < ttl {
		return weatherCasts, weatherCastOK
	}

	v := url.Values{}
	v.Set("city", adcode)
	v.Set("extensions", "all")
	var resp struct {
		Status    string `json:"status"`
		Info      string `json:"info"`
		Forecasts []struct {
			City  string `json:"city"`
			Casts []struct {
				Date         string `json:"date"`
				Weekday      string `json:"weekday"`
				DayWeather   string `json:"dayweather"`
				DayTemp      string `json:"daytemp"`
				NightWeather string `json:"nightweather"`
				NightTemp    string `json:"nighttemp"`
			} `json:"casts"`
		} `json:"forecasts"`
	}
	if err := amapGetJSON(amapWeatherURL, v, &resp); err != nil || resp.Status != "1" || len(resp.Forecasts) == 0 {
		// ⚠️ 同上：只记原因，不打 URL（key 在 query 里）。
		log.Printf("⚠️ 天气：取预报失败（err=%v status=%s info=%s）", err, resp.Status, resp.Info)
		weatherCastKey, weatherCastAt, weatherCastOK = adcode, time.Now(), false
		weatherCasts = nil
		return nil, false
	}

	casts := make([]WeatherCast, 0, len(resp.Forecasts[0].Casts))
	for _, c := range resp.Forecasts[0].Casts {
		casts = append(casts, WeatherCast{
			Date:         c.Date,
			Weekday:      c.Weekday,
			DayWeather:   c.DayWeather,
			DayTemp:      atoiSafe(c.DayTemp),
			NightWeather: c.NightWeather,
			NightTemp:    atoiSafe(c.NightTemp),
		})
	}
	weatherCastKey, weatherCastAt, weatherCastOK = adcode, time.Now(), true
	weatherCasts = casts
	return casts, true
}

// amapGetJSON 调一次高德 Web 服务 API。
//
// ⚠️ **日志里不许出现完整 URL**（key 在 query 里）⇒ 这里只把错误原样返回，不打 URL。
func amapGetJSON(base string, params url.Values, out any) error {
	key := getenv("AMAP_KEY", "")
	if key == "" {
		return fmt.Errorf("AMAP_KEY 未配置")
	}
	v := url.Values{}
	for k, vals := range params {
		for _, one := range vals {
			v.Add(k, one)
		}
	}
	v.Set("key", key)
	req, err := http.NewRequest(http.MethodGet, base+"?"+v.Encode(), nil)
	if err != nil {
		return err
	}
	client := &http.Client{Timeout: 8 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("高德返回 HTTP %d", resp.StatusCode)
	}
	return json.NewDecoder(resp.Body).Decode(out)
}

// atoiSafe 把高德的温度字符串安全地转成整数（转不了就 0 ⇒ 不会因为一个脏值把判定带歪）。
func atoiSafe(s string) int {
	n, err := strconv.Atoi(strings.TrimSpace(s))
	if err != nil {
		return 0
	}
	return n
}
