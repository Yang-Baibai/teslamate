package main

import "testing"

// ⚠️ **v0.164.0**：天气判定的护栏 —— 「什么条件下才值得打扰」的答案钉在这儿。

// TestWeatherThresholdsArePinned 两个阈值**不许悄悄改**。
func TestWeatherThresholdsArePinned(t *testing.T) {
	if WeatherHeatC != 40 {
		t.Fatalf("极端高温阈值必须是 40℃，现在是 %d（35℃ 是常态 ⇒ 天天推就是噪音）", WeatherHeatC)
	}
	if WeatherIceC != 0 {
		t.Fatalf("结冰阈值必须是 0℃，现在是 %d", WeatherIceC)
	}
}

// TestEvaluateWeatherRiskNoRisk 常态天气**不推**（这是「不打扰」的那一半）。
func TestEvaluateWeatherRiskNoRisk(t *testing.T) {
	casts := []WeatherCast{
		{Date: "2026-11-16", DayWeather: "多云", DayTemp: 26, NightWeather: "多云", NightTemp: 19},
		{Date: "2026-11-17", DayWeather: "多云", DayTemp: 27, NightWeather: "小雨", NightTemp: 18},
	}
	r := EvaluateWeatherRisk(casts, "惠阳区")
	if r == nil {
		t.Fatal("有预报就该给结论（nil 只能表示「不知道」）")
	}
	if r.Alert {
		t.Fatalf("常态天气不该打扰用户：%+v", r)
	}
	if r.Level != WeatherRiskNone {
		t.Fatalf("常态天气的档位应是 none：%+v", r)
	}
	if r.City != "惠阳区" || r.TempMin != 18 || r.TempMax != 27 {
		t.Fatalf("城市/温度区间应原样带出：%+v", r)
	}
}

// TestEvaluateWeatherRiskSevere 极端天气（台风 / 冰雹）⇒ 最优先。
func TestEvaluateWeatherRiskSevere(t *testing.T) {
	casts := []WeatherCast{
		{Date: "2026-11-16", DayWeather: "多云", DayTemp: 26, NightWeather: "多云", NightTemp: 19},
		{Date: "2026-11-17", DayWeather: "台风", DayTemp: 24, NightWeather: "大雨", NightTemp: 21},
	}
	r := EvaluateWeatherRisk(casts, "惠阳区")
	if r == nil || !r.Alert || r.Level != WeatherRiskSevere {
		t.Fatalf("台风必须报「极端天气」：%+v", r)
	}
	if r.What == "" || r.Advice == "" {
		t.Fatalf("结论与建议都不许为空：%+v", r)
	}
}

// TestEvaluateWeatherRiskRain 暴雨量级（≥50mm 那一档）。
func TestEvaluateWeatherRiskRain(t *testing.T) {
	casts := []WeatherCast{
		{Date: "2026-11-16", DayWeather: "多云", DayTemp: 26, NightWeather: "多云", NightTemp: 19},
		{Date: "2026-11-17", DayWeather: "暴雨", DayTemp: 24, NightWeather: "大雨", NightTemp: 21},
	}
	r := EvaluateWeatherRisk(casts, "惠阳区")
	if r == nil || !r.Alert || r.Level != WeatherRiskRain {
		t.Fatalf("暴雨必须报「暴雨」：%+v", r)
	}
}

// TestEvaluateWeatherRiskIce 结冰（≤0℃）。
func TestEvaluateWeatherRiskIce(t *testing.T) {
	casts := []WeatherCast{
		{Date: "2026-11-16", DayWeather: "晴", DayTemp: 5, NightWeather: "晴", NightTemp: -2},
	}
	r := EvaluateWeatherRisk(casts, "某区")
	if r == nil || !r.Alert || r.Level != WeatherRiskIce {
		t.Fatalf("≤0℃ 必须报「结冰」：%+v", r)
	}
}

// TestEvaluateWeatherRiskHeat 极端高温（≥40℃）；35℃ **不算**。
func TestEvaluateWeatherRiskHeat(t *testing.T) {
	casts := []WeatherCast{
		{Date: "2026-07-20", DayWeather: "晴", DayTemp: 35, NightWeather: "晴", NightTemp: 28},
	}
	if r := EvaluateWeatherRisk(casts, "某区"); r == nil || r.Alert {
		t.Fatalf("35℃ 是常态 ⇒ 不该推：%+v", r)
	}
	casts = []WeatherCast{
		{Date: "2026-07-20", DayWeather: "晴", DayTemp: 41, NightWeather: "晴", NightTemp: 30},
	}
	if r := EvaluateWeatherRisk(casts, "某区"); r == nil || !r.Alert || r.Level != WeatherRiskHeat {
		t.Fatalf("41℃ 必须报「极端高温」：%+v", r)
	}
}

// TestEvaluateWeatherRiskPriority 极端天气 > 暴雨 > 结冰 > 高温。
func TestEvaluateWeatherRiskPriority(t *testing.T) {
	casts := []WeatherCast{
		{Date: "2026-11-16", DayWeather: "暴雨", DayTemp: 41, NightWeather: "台风", NightTemp: -3},
	}
	if r := EvaluateWeatherRisk(casts, "某区"); r == nil || r.Level != WeatherRiskSevere {
		t.Fatalf("四档同时命中时必须是最高那档（极端天气）：%+v", r)
	}
	casts = []WeatherCast{
		{Date: "2026-11-16", DayWeather: "暴雨", DayTemp: 41, NightWeather: "多云", NightTemp: 30},
	}
	if r := EvaluateWeatherRisk(casts, "某区"); r == nil || r.Level != WeatherRiskRain {
		t.Fatalf("暴雨 + 41℃ ⇒ 暴雨优先（高温排最后）：%+v", r)
	}
}

// TestEvaluateWeatherRiskEmpty 没预报 ⇒ nil（「不知道」，不是「没问题」）。
func TestEvaluateWeatherRiskEmpty(t *testing.T) {
	if r := EvaluateWeatherRisk(nil, "某区"); r != nil {
		t.Fatalf("没有预报必须给 nil（不知道 ≠ 没问题）：%+v", r)
	}
}

// TestEvaluateWeatherRiskOnlyTwoDays 只看**今天 + 明天**（更远的不算）。
func TestEvaluateWeatherRiskOnlyTwoDays(t *testing.T) {
	casts := []WeatherCast{
		{Date: "2026-11-16", DayWeather: "多云", DayTemp: 26, NightWeather: "多云", NightTemp: 19},
		{Date: "2026-11-17", DayWeather: "多云", DayTemp: 27, NightWeather: "多云", NightTemp: 18},
		{Date: "2026-11-18", DayWeather: "台风", DayTemp: 24, NightWeather: "台风", NightTemp: 21},
	}
	if r := EvaluateWeatherRisk(casts, "某区"); r == nil || r.Alert {
		t.Fatalf("第三天的台风不在判定窗口内 ⇒ 不该推：%+v", r)
	}
}
