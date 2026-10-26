package main

// ---------- Tesla Fleet API 只读客户端（锁车状态 + 实时胎压） ----------
//
// 为什么需要它：
//   TeslaMate 的库里没有「车门锁没锁」，也没有实时胎压——它只记行驶、充电、位置。
//   锁车状态与实时胎压只有特斯拉云端知道，只能走 Tesla Fleet API 读 vehicle_data。
//
// 三条硬约束（决定了下面所有设计）：
//  1. 凭据可能没有。没有凭据必须照常启动，locked 返回 null（未知），绝不 panic。
//  2. refresh_token 是一次性的：刷新后旧的立即失效，有效期 3 个月。
//     每次刷新拿到新 token 都必须落盘，否则三个月后要重新走浏览器授权。
//  3. 任何失败都不能影响主服务：锁车/胎压拿不到就是「未知」。
//
// 只读：本文件只发 GET 和一次换 token 的 POST，绝不调用任何车控接口。

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

const (
	// teslaPollInterval 轮询间隔。锁车状态要新鲜——车停着不动时锁车状态也可能变。
	teslaPollInterval = 2 * time.Minute

	// teslaTokenRefreshMargin access_token 有效期 8 小时（28800s），
	// 剩余不足 1 小时就提前刷新，避免请求正好撞上过期。
	teslaTokenRefreshMargin = 1 * time.Hour

	// teslaDefaultTokenTTL 响应里没有 expires_in 时的兜底有效期。
	teslaDefaultTokenTTL = 8 * time.Hour

	// teslaBackoffInit / teslaBackoffMax 轮询失败时的指数退避边界（别把 API 打爆）。
	teslaBackoffInit = 30 * time.Second
	teslaBackoffMax  = 15 * time.Minute

	// teslaEnvKey refresh_token 在 .env 里的键名。
	teslaEnvKey = "TESLA_REFRESH_TOKEN"
)

// teslaEndpoints 一个区域的端点。
type teslaEndpoints struct {
	auth  string
	fleet string
}

// 各区域端点。中国区账号用 cn（auth.tesla.cn）。
var teslaRegions = map[string]teslaEndpoints{
	"cn": {auth: "https://auth.tesla.cn", fleet: "https://fleet-api.prd.cn.vn.cloud.tesla.cn"},
	"na": {auth: "https://auth.tesla.com", fleet: "https://fleet-api.prd.na.vn.cloud.tesla.com"},
	"eu": {auth: "https://auth.tesla.eu", fleet: "https://fleet-api.prd.eu.vn.cloud.tesla.com"},
}

// errVehicleUnavailable 车睡着/离线时 Fleet API 返回 408。
//
// 这不是故障：车锁得好好的，只是没醒着上报数据。
// 所以这种情况保留上次已知的锁车状态（locked_at 让 App 自己判断新鲜度），
// 不把它降级成「未知」，否则车一停就是一片 null，功能等于没有。
var errVehicleUnavailable = errors.New("车辆当前不可用（多半在休眠）")

// errTeslaAuth Fleet API 凭据失效（401 / 403）。
//
// 凭据坏了不代表车解锁了 —— 它只代表我们读不到。所以它**同样不清空**已知的锁车状态，
// 只是把「读不到」这件事记下来告诉 App。
var errTeslaAuth = errors.New("Tesla 凭据已失效")

// teslaConfig 全部来自环境变量，一个都没配也能启动（降级成「未知」）。
type teslaConfig struct {
	clientID     string
	clientSecret string
	refreshToken string
	region       string
	vehicleID    string
	envFile      string // refresh_token 轮换后回写的 .env 路径
	stateFile    string // .env 写不进去时的兜底位置
	pollInterval time.Duration
}

// teslaClient Fleet API 只读客户端。
//
// 并发模型：令牌状态与车辆状态各一把读写锁。
// 只有一个后台 goroutine 会写，HTTP handler 只读，所以读侧永远不加锁排队。
type teslaClient struct {
	cfg      teslaConfig
	http     *http.Client
	authURL  string
	fleetURL string

	mu           sync.RWMutex
	accessToken  string
	refreshToken string
	tokenExpiry  time.Time
	vehicleID    string
	// ⚠️ v0.66.0：车辆 **VIN**（17 位，如 <VIN>）。
	//
	// 为什么单独存：Fleet API 的 id 是**数字**（中国区实测 <fleet车辆ID>），
	// 而 command-proxy 的命令端点要的是 **VIN**
	// （实测传 id 会被回 `expected 17-character VIN in path`）。
	// 两者不能互换。
	vehicleVIN string

	stateMu  sync.RWMutex
	locked   *bool
	lockedAt *time.Time
	liveTPMS TeslaTPMS // 实时胎压快照（来源 Fleet API 的 vehicle_state）
	// ⚠️ v0.21.0：车端里程表（km），同样来自 Fleet API 的 vehicle_state.odometer。
	//
	// 为什么需要它：TeslaMate 的 `positions.odometer` 是**按 GPS 点累计**的里程表，
	// 与车端真实里程表有偏差（实测差几百公里）。
	// 保养超期是拿里程表算的 —— 基准是车主的知识，
	// 拿一个偏了的数去套，会让告警**偏**同样多公里。
	odoVehicle *float64

	// ⚠️ v0.9.0：轮询健康状态。
	//   fleetErr    非空 = 轮询失败时的原因（空 = 正常）
	//   fleetLastOK 最近一次**成功**拉取的时刻
	// 有了这两个，App 才能区分「车真没锁」和「这会儿没取到数据」——
	// 而「锁没锁」是车主第一诉求，把它降级成「未知」比显示一个旧值伤害大得多。
	fleetErr    string
	fleetLastOK *time.Time
	// ⚠️ v0.22.0：Fleet 实时状态（四态 + 正常），见 FleetState* 常量。
	fleetState string
}

// TeslaTPMSWheel 一只轮胎的实时读数与 Tesla 服务端的告警位。
//
// Pressure 为 nil = 没读到（字段缺失 / 车休眠），**不是 0** ——
// 0 bar 是不可能值，压成 0 会让 App 显示「0 bar」这种一眼假的读数。
type TeslaTPMSWheel struct {
	Pressure *float64 // bar
	Hard     bool     // tpms_hard_warning_*：Tesla 自己判定的硬告警
	Soft     bool     // tpms_soft_warning_*：Tesla 自己判定的软告警（接近阈值）
}

// TeslaTPMS 一次拉回来的四轮实时胎压快照。
//
// 轮次固定为：左前 FL / 右前 FR / 左后 RL / 右后 RR。
type TeslaTPMS struct {
	FL TeslaTPMSWheel
	FR TeslaTPMSWheel
	RL TeslaTPMSWheel
	RR TeslaTPMSWheel

	At *time.Time // 这批数据的采集时刻（车端上报的 timestamp）
}

// 全局实例。nil 表示没初始化（此时锁车状态恒为未知）。
var tesla *teslaClient

// teslaLockState 返回缓存的锁车状态与采集时刻。
//
// nil / nil = 未知（没配凭据、还没拉到、或拉取失败降级）。
func teslaLockState() (*bool, *time.Time) {
	if tesla == nil {
		return nil, nil
	}
	return tesla.LockState()
}

// teslaLiveTPMS 返回缓存的实时胎压快照。
//
// 没初始化（没配凭据）时返回零值：四轮 Pressure 全是 nil、At 是 nil = 未知。
func teslaLiveTPMS() TeslaTPMS {
	if tesla == nil {
		return TeslaTPMS{}
	}
	return tesla.TPMS()
}

// teslaFleetStatus 返回后台轮询的健康状态：失败原因 + 最近一次成功的时刻。
//
// 没配凭据时两者都是空 —— 那不是「故障」，是「没装这个功能」。
func teslaFleetStatus() (string, *time.Time) {
	if tesla == nil {
		return "", nil
	}
	return tesla.FleetStatus()
}

// ⚠️ v0.21.0：是否**配置了** Fleet API 凭据。
//
// 为什么需要它：没配凭据时，锁车状态与实时胎压**永远**是「未知」——
// 它不是「这会儿没取到」，是「这台服务器压根没有实时状态源」。
// App 原来一律显示「没读到车机的锁车状态 —— 这不代表车没锁」，
// 车主会以为是车机或网络出了故障，刷新两次、怀疑车没锁。
//
// 有了这个标志，App 才能把「**没装这个功能**」与「**暂时读不到**」分开说。
func teslaFleetEnabled() bool {
	if tesla == nil {
		return false
	}
	return tesla.configured()
}

// ⚠️ v0.22.0：Fleet 实时状态（四态 + 正常）。
//
// 没配凭据 → disabled；有凭据但还没跑过 → ok（初始值）。
func teslaFleetState() string {
	if tesla == nil || !tesla.configured() {
		return FleetStateDisabled
	}
	return tesla.FleetState()
}

// ⚠️ v0.22.0：给「服务器状态」页用的三个原始值。
func teslaFleetLastOK() *time.Time {
	if tesla == nil {
		return nil
	}
	_, ok := tesla.FleetStatus()
	return ok
}

func teslaFleetError() string {
	if tesla == nil {
		return ""
	}
	err, _ := tesla.FleetStatus()
	return err
}

// ⚠️ v0.21.0：车端里程表（km）。
//
// 与 `positions.odometer`（TeslaMate 按 GPS 点累计）的区别：
// 车端里程表是**车辆自己**记的，是保养基准应该对齐的那个数。
func teslaOdometer() *float64 {
	if tesla == nil {
		return nil
	}
	return tesla.Odometer()
}

// FleetStatus 返回轮询健康状态（读侧不加锁排队）。
func (c *teslaClient) FleetStatus() (string, *time.Time) {
	c.stateMu.RLock()
	defer c.stateMu.RUnlock()
	return c.fleetErr, c.fleetLastOK
}

// setFleetError 记下本轮失败的原因。
func (c *teslaClient) setFleetError(msg string) {
	c.stateMu.Lock()
	defer c.stateMu.Unlock()
	c.fleetErr = msg
}

// markFleetOK 轮询成功：清掉错误、记下成功时刻。
func (c *teslaClient) markFleetOK() {
	c.stateMu.Lock()
	defer c.stateMu.Unlock()
	c.fleetErr = ""
	now := time.Now().UTC()
	c.fleetLastOK = &now
	c.fleetState = FleetStateOK
}

// setFleetState 记下 Fleet 实时状态（四态 + 正常）。
func (c *teslaClient) setFleetState(s string) {
	c.stateMu.Lock()
	defer c.stateMu.Unlock()
	c.fleetState = s
}

// FleetState 返回当前的 Fleet 状态。
func (c *teslaClient) FleetState() string {
	c.stateMu.RLock()
	defer c.stateMu.RUnlock()
	if c.fleetState == "" {
		return FleetStateOK
	}
	return c.fleetState
}

// loadTeslaConfig 从环境变量读配置。缺什么都不报错——缺了就是「未知」。
func loadTeslaConfig() teslaConfig {
	region := strings.ToLower(strings.TrimSpace(getenv("TESLA_REGION", "cn")))
	if _, ok := teslaRegions[region]; !ok {
		log.Printf("⚠️  TESLA_REGION=%q 不是已知区域（cn/na/eu），按 cn 处理", region)
		region = "cn"
	}
	cfg := teslaConfig{
		clientID:     strings.TrimSpace(getenv("TESLA_CLIENT_ID", "")),
		clientSecret: strings.TrimSpace(getenv("TESLA_CLIENT_SECRET", "")),
		refreshToken: "",
		region:       region,
		vehicleID:    strings.TrimSpace(getenv("TESLA_VEHICLE_ID", "")),
		envFile:      resolveTeslaEnvFile(),
		stateFile:    strings.TrimSpace(getenv("TESLA_REFRESH_TOKEN_FILE", "/tmp/tesla_refresh_token")),
		pollInterval: teslaPollInterval,
	}
	// ⚠️ v0.22.0（自托管运维评审 P2-1）：**优先读 .env 文件**，环境变量只作兜底。
	//
	// 原来优先读环境变量 —— 但中国区的 refresh_token 是**一次性**的：
	// 每次刷新都会拿到新 token，旧的**立刻作废**，而服务端会把新值**回写 .env**。
	// 于是运行一段时间后：
	//   .env 里的值      = 轮换后的**新**值（有效）
	//   环境变量里的值    = 容器启动时注入的**旧**值（**已作废**）
	//
	// 平时没事（内存里用的是轮换后的新 token），
	// 但 `docker compose restart api` **不会重新注入环境变量** ——
	// 一重启就会用已作废的旧 token，Fleet **永久失效**（一次性 token 重试也没用）。
	//
	// 现在反过来：文件优先（它才是会被更新的那个），环境变量兜底。
	//
	// ⚠️ 优先级颠倒的风险：宿主机 .env 若被手工改成旧值，
	// 会盖掉容器里的新值 —— 但那本来就是人的操作，责任明确。
	if cfg.envFile != "" {
		if v := readEnvValue(cfg.envFile, teslaEnvKey); v != "" {
			cfg.refreshToken = v
		}
	}
	if cfg.refreshToken == "" {
		cfg.refreshToken = strings.TrimSpace(getenv("TESLA_REFRESH_TOKEN", ""))
	}
	if cfg.refreshToken == "" {
		// 最后兜底：兜底状态文件（容器内 /tmp，重建即失效）
		cfg.refreshToken = readTrimmedFile(cfg.stateFile)
	}
	return cfg
}

// resolveTeslaEnvFile 找 .env 的位置：
//  1. TESLA_ENV_FILE 显式指定
//  2. 容器内常见路径 /app/.env
//  3. 当前目录 / 上一级的 .env（本地直接跑二进制时）
//
// 找不到就返回空字符串——此时 refresh_token 轮换只能写兜底文件并打警告。
func resolveTeslaEnvFile() string {
	if p := strings.TrimSpace(getenv("TESLA_ENV_FILE", "")); p != "" {
		return p
	}
	for _, cand := range []string{"/app/.env", "./.env", "../.env"} {
		if st, err := os.Stat(cand); err == nil && !st.IsDir() {
			return cand
		}
	}
	return ""
}

// newTeslaClient 建客户端。
func newTeslaClient(cfg teslaConfig) *teslaClient {
	ep := teslaRegions[cfg.region]
	if ep.fleet == "" { // 防御：region 不在表里
		ep = teslaRegions["cn"]
	}
	return &teslaClient{
		cfg:          cfg,
		http:         &http.Client{Timeout: 15 * time.Second},
		authURL:      ep.auth,
		fleetURL:     ep.fleet,
		refreshToken: cfg.refreshToken,
	}
}

// configured 凭据够不够。不够就不启动轮询，锁车状态恒为未知。
func (c *teslaClient) configured() bool {
	return c.cfg.clientID != "" && c.cfg.refreshToken != ""
}

// LockState 返回缓存的锁车状态（读侧不加锁排队）。
func (c *teslaClient) LockState() (*bool, *time.Time) {
	c.stateMu.RLock()
	defer c.stateMu.RUnlock()
	return c.locked, c.lockedAt
}

// TPMS 返回缓存的实时胎压快照（读侧不加锁排队）。
//
// 返回的是快照副本：里面的指针指向的数值**再也不会被改写**
// （每轮轮询都是新分配），所以调用方可以安全地持有它。
func (c *teslaClient) TPMS() TeslaTPMS {
	c.stateMu.RLock()
	defer c.stateMu.RUnlock()
	return c.liveTPMS
}

// setLockState 记下本次采集结果。
func (c *teslaClient) setLockState(locked bool, at time.Time) {
	c.stateMu.Lock()
	defer c.stateMu.Unlock()
	v := locked
	t := at
	c.locked = &v
	c.lockedAt = &t
}

// setLiveTPMS 记下本次采集到的实时胎压。
func (c *teslaClient) setLiveTPMS(t TeslaTPMS) {
	c.stateMu.Lock()
	defer c.stateMu.Unlock()
	c.liveTPMS = t
}

// setOdometer 记下本次采集到的车端里程表。
//
// ⚠️ v0.21.0：nil = 没读到（车休眠 / 字段缺失），**不是 0**。
func (c *teslaClient) setOdometer(km *float64) {
	c.stateMu.Lock()
	defer c.stateMu.Unlock()
	c.odoVehicle = km
}

// Odometer 返回缓存的车端里程表（读侧不加锁排队）。
//
// ⚠️ v0.21.0：与 [LockState] 同一套「**失败也保留上次已知**」策略 ——
// 车端里程表是**单调递增**的量，保留旧值比清空更安全。
func (c *teslaClient) Odometer() *float64 {
	c.stateMu.RLock()
	defer c.stateMu.RUnlock()
	return c.odoVehicle
}

// clearVehicleState 降级成「未知」。
//
// ⚠️ v0.9.0：**这个函数不再被调用**。
//
// 原来只要轮询失败（哪怕只是 Tesla 侧一次 429 限流）就调它，把锁车与实时胎压
// 一起清成 null。后果是：
//   - Tesla 侧抖动 30 秒 → App 的锁车变「未知」、胎压卡整块消失
//   - 用户无法区分「车真没锁」和「这会儿没取到数据」
//   - 而「锁没锁」是车主第一诉求 —— 把它降级成「未知」，
//     比显示一个**带时间戳的旧值**伤害大得多（后者至少是诚实的事实）。
//
// 现在的策略：**任何失败都保留上次已知结果**，只把失败原因记进 fleetErr，
// 由 App 显示「暂时没取到，原因是 X」。真正从没拿到过数据时状态本来就是 null。
func (c *teslaClient) clearVehicleState() {
	c.stateMu.Lock()
	defer c.stateMu.Unlock()
	c.locked = nil
	c.lockedAt = nil
	c.liveTPMS = TeslaTPMS{}
}

// Fleet 实时状态的**四态**（+ 正常）。
//
// ⚠️ v0.22.0（自托管运维评审 P1-4 + P0-3）：
// 原来 App 只能区分「有值」与「没值」（未知），
// 而「没值」其实是**四种完全不同的情况**：
//
//	disabled     没配凭据 —— 这台服务器**没有实时状态源**（永远不会变）
//	asleep       车在休眠 —— **正常状态**，不是故障（等它醒来就更新）
//	unauthorized 授权失效 —— 一次性 token 过期，**不会自愈**，要人工重新授权
//	unreachable  云端故障 —— 会自愈（退避重试中）
//
// 不分开的后果很具体：
//   - 「车休眠」被说成「没读到」→ 车主以为车机或网络坏了；
//   - 「授权失效」被说成「稍后自动重试」→ **永远重试也不会成功**，
//     而锁车与实时胎压会**静默停更**，唯一的告警通道（Hermes 推送）
//     又不读这个状态 —— 于是「锁没锁」这个第一诉求可能一周没人发现。
const (
	FleetStateDisabled    = "disabled"
	FleetStateAsleep      = "asleep"
	FleetStateUnauthorized = "unauthorized"
	FleetStateUnreachable = "unreachable"
	FleetStateOK          = "ok"
)

// shortFleetError 把内部错误压成一句**能直接给用户看**的话。
//
// 原则：这句话会原样出现在 App 的横幅上，所以
//   - 不能带内部细节（URL、令牌片段、状态码堆栈）
//   - 必须说清「显示的是最后一次成功获取的数据」——
//     否则用户会以为那是实时值。
func shortFleetError(err error) string {
	const tail = "锁车与胎压显示的是最后一次成功获取的数据"
	switch {
	case errors.Is(err, errTeslaAuth):
		return "Tesla 授权已失效，需要重新授权；" + tail
	case strings.Contains(err.Error(), "429"):
		return "Tesla 云端限流中，稍后自动重试；" + tail
	case strings.Contains(err.Error(), "vehicle_data 返回 5"):
		return "Tesla 云端暂时不可用，稍后自动重试；" + tail
	case strings.Contains(err.Error(), "刷新 access_token 失败"):
		// ⚠️ v0.22.0：**删掉「稍后自动重试」** ——
		// 中国区的 refresh_token 是**一次性**的，刷新失败意味着它已过期，
		// 重试一万次也不会成功。文案必须指向真正的出路：
		// 在服务器上重新跑一次授权脚本。
		return "Tesla 授权已过期，需要在服务器上重新授权" +
			"（scripts/tesla-authorize.ps1）；锁车与实时胎压已停更"
	case strings.Contains(err.Error(), "获取车辆列表失败"):
		return "读不到 Tesla 车辆列表，稍后自动重试；" + tail
	default:
		return "暂时连不上 Tesla 云端，稍后自动重试；" + tail
	}
}

// fleetStateFor 从错误推出状态。
//
// ⚠️ 「刷新 access_token 失败」单独成一类 —— 它是**授权失效**，
// 与「云端故障」的处理方式完全不同：前者要人工介入，后者会自愈。
func fleetStateFor(err error) string {
	switch {
	case errors.Is(err, errTeslaAuth):
		return FleetStateUnauthorized
	case strings.Contains(err.Error(), "刷新 access_token 失败"):
		return FleetStateUnauthorized
	default:
		return FleetStateUnreachable
	}
}

// Run 后台轮询循环。ctx 取消即退出。
//
// 节奏：正常每 teslaPollInterval 拉一次 vehicle_data；
// 失败按指数退避（30s → 15min 封顶），别把 API 打爆。
func (c *teslaClient) Run(ctx context.Context) {
	backoff := teslaBackoffInit
	var wait time.Duration
	for {
		if ctx.Err() != nil {
			return
		}
		err := c.pollOnce(ctx)
		wait, backoff = c.handlePollResult(err, backoff)
		select {
		case <-ctx.Done():
			return
		case <-time.After(wait):
		}
	}
}

// handlePollResult 处理一轮轮询的结果：更新状态、记错误、决定等多久再试。
//
// ⚠️ v0.9.0 抽出来是为了**可测**：
// 原来这些分支散在 Run 的循环体里，只能靠跑整个循环来验证 ——
// 而「失败时到底会不会清掉锁车状态」恰恰是最该测的一条（见 tesla_test.go）。
//
// 返回（本次等多久，下次失败时的退避基数）。
func (c *teslaClient) handlePollResult(err error, backoff time.Duration) (time.Duration, time.Duration) {
	switch {
	case err == nil:
		c.markFleetOK()
		return c.cfg.pollInterval, teslaBackoffInit

	case errors.Is(err, errVehicleUnavailable):
		// 车睡着了：这不是故障。保留上次已知结果（锁车 + 实时胎压），
		// 只提示一次别刷屏，也不要在 App 上挂错误横幅。
		//
		// ⚠️ v0.22.0：状态记成 **asleep**（而不是「正常」）——
		// 「车在休眠」与「一切正常」是**两件事**：
		// 前者意味着锁车状态要等它醒来才更新，App 该说清楚这一点，
		// 而不是让用户对着一个不动的锁车图标猜。
		log.Printf("ℹ️  Tesla 车辆当前不可用（多半休眠）：锁车与实时胎压保留上次结果")
		c.setFleetError("")
		c.setFleetState(FleetStateAsleep)
		return c.cfg.pollInterval, teslaBackoffInit

	default:
		// ⚠️ v0.9.0：**不再清空**锁车与实时胎压（见 clearVehicleState 的注释）。
		// 失败只做两件事：记下原因（App 会显示）+ 退避重试。
		// 「锁没锁」是车主第一诉求 —— 显示一个带时间戳的旧值
		// 比显示「未知」有用得多，也不误导（App 会写明这是什么时候的数据）。
		c.setFleetError(shortFleetError(err))
		c.setFleetState(fleetStateFor(err))
		wait := backoff
		next := backoff * 2
		if next > teslaBackoffMax {
			next = teslaBackoffMax
		}
		log.Printf("⚠️  Tesla Fleet API 轮询失败：%v；%s 后重试（保留上次已知结果）", err, wait)
		return wait, next
	}
}

// pollOnce 跑一轮：刷新 token → 找车 → 拉车辆实时状态（锁车 + 胎压 + 里程）。
//
// ⚠️ 一次请求拿全部：中国区的 vehicle_data 不支持 endpoints 参数，
// 只能整包拉（约 6.5 KB），所以锁车与胎压本来就该在同一个响应里解析，
// 没必要为了多拿几个字段再发一次请求（那只会平白多吃一倍限流额度）。
func (c *teslaClient) pollOnce(ctx context.Context) error {
	if err := c.ensureToken(ctx); err != nil {
		return fmt.Errorf("刷新 access_token 失败: %w", err)
	}
	vid, err := c.resolveVehicleID(ctx)
	if err != nil {
		return fmt.Errorf("获取车辆列表失败: %w", err)
	}
	st, err := c.fetchVehicleState(ctx, vid)
	if err != nil {
		return fmt.Errorf("获取车辆状态失败: %w", err)
	}
	// 采集时刻优先用车端上报的 timestamp（车端时钟，更接近数据真实时刻），
	// 没有就用本地时间 —— 它只用于让 App 显示新鲜度。
	if st.LockedAt == nil {
		now := time.Now().UTC()
		st.LockedAt = &now
		st.TPMS.At = &now
	}
	if st.Locked != nil {
		c.setLockState(*st.Locked, *st.LockedAt)
	}
	c.setLiveTPMS(st.TPMS)
	// ⚠️ v0.21.0：车端里程表与胎压来自**同一个** vehicle_data 响应，
	// 一并缓存（里程表是单调递增的量，保留旧值比清空安全）。
	if st.Odometer != nil {
		c.setOdometer(st.Odometer)
	}
	return nil
}

// ensureToken 保证手里有可用的 access_token。
//
// access_token 有效期 8 小时；剩余不足 1 小时就提前刷新。
func (c *teslaClient) ensureToken(ctx context.Context) error {
	c.mu.RLock()
	token := c.accessToken
	expiry := c.tokenExpiry
	c.mu.RUnlock()
	if token != "" && time.Now().Before(expiry.Add(-teslaTokenRefreshMargin)) {
		return nil
	}
	return c.refreshAccessToken(ctx)
}

// teslaTokenResp 换 token 的响应。
type teslaTokenResp struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	TokenType    string `json:"token_type"`
	ExpiresIn    int64  `json:"expires_in"`
	CreatedAt    int64  `json:"created_at"`
}

// refreshAccessToken 用 refresh_token 换 access_token。
//
// ⚠️ refresh_token 是一次性的：每次刷新都会返回新的，旧的立刻失效。
// 所以拿到新的必须立刻落盘（见 persistRefreshToken），否则三个月后要重走浏览器授权。
func (c *teslaClient) refreshAccessToken(ctx context.Context) error {
	c.mu.RLock()
	rt := c.refreshToken
	c.mu.RUnlock()
	if rt == "" {
		return errors.New("未配置 " + teslaEnvKey)
	}

	form := url.Values{}
	form.Set("grant_type", "refresh_token")
	form.Set("client_id", c.cfg.clientID)
	form.Set("refresh_token", rt)
	// client_secret 在刷新时不是必填，带上无害
	if c.cfg.clientSecret != "" {
		form.Set("client_secret", c.cfg.clientSecret)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		c.authURL+"/oauth2/v3/token", strings.NewReader(form.Encode()))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return err
	}
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("token 接口返回 %d: %s", resp.StatusCode, maskSecretText(truncate(string(body), 300)))
	}
	var tr teslaTokenResp
	if err := json.Unmarshal(body, &tr); err != nil {
		return fmt.Errorf("token 响应解析失败: %w", err)
	}
	if tr.AccessToken == "" {
		return errors.New("token 响应里没有 access_token")
	}

	ttl := time.Duration(tr.ExpiresIn) * time.Second
	if ttl <= 0 {
		ttl = teslaDefaultTokenTTL
	}
	expiry := time.Now().Add(ttl)

	c.mu.Lock()
	c.accessToken = tr.AccessToken
	c.tokenExpiry = expiry
	rotated := tr.RefreshToken != "" && tr.RefreshToken != rt
	if rotated {
		c.refreshToken = tr.RefreshToken
	}
	c.mu.Unlock()

	if rotated {
		c.persistRefreshToken(tr.RefreshToken)
	}
	return nil
}

// persistRefreshToken 把轮换后的 refresh_token 写回 .env。
//
// 要求（都在下面的实现里兑现了）：
//   - 原子写：同目录临时文件 + os.Rename，写一半崩溃不会毁掉 .env
//   - 保留原文件格式与行尾：只替换 TESLA_REFRESH_TOKEN= 那一行的值，其余字节原样保留
//   - 写失败不能让服务崩溃：记日志、兜底写状态文件、内存里的新 token 继续用
func (c *teslaClient) persistRefreshToken(token string) {
	token = strings.TrimSpace(token)
	if token == "" {
		return
	}
	if c.cfg.envFile == "" {
		log.Printf("⚠️  未找到 .env（TESLA_ENV_FILE 未设且常见路径都不存在）：refresh_token 只留在内存，重启后需重新授权")
		c.writeStateFile(token)
		return
	}
	if err := writeEnvValue(c.cfg.envFile, teslaEnvKey, token); err != nil {
		log.Printf("⚠️  refresh_token 回写 %s 失败：%v；改写兜底文件 %s", c.cfg.envFile, err, c.cfg.stateFile)
		c.writeStateFile(token)
		return
	}
	log.Printf("✅ refresh_token 已轮换并回写 %s（值不打印）", c.cfg.envFile)
}

// writeStateFile 兜底：把 refresh_token 写进状态文件。
//
// 位置默认在容器内 /tmp（重建容器即失效），不会落到仓库里，避免密钥被误提交。
func (c *teslaClient) writeStateFile(token string) {
	path := c.cfg.stateFile
	if path == "" {
		log.Printf("⚠️  refresh_token 无处落盘（.env 与兜底文件都不可用）：重启后需重新授权")
		return
	}
	if dir := filepath.Dir(path); dir != "" && dir != "." {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			log.Printf("⚠️  兜底目录 %s 不可创建：%v；refresh_token 只留在内存", dir, err)
			return
		}
	}
	if err := os.WriteFile(path, []byte(token), 0o600); err != nil {
		log.Printf("⚠️  兜底文件 %s 写入失败：%v；refresh_token 只留在内存", path, err)
		return
	}
	log.Printf("⚠️  refresh_token 已写入兜底文件 %s：.env 没更新，容器重建后需手动把它写回 .env 的 %s", path, teslaEnvKey)
}

// resolveVehicleID 找到要查的车。
//
// 没配 TESLA_VEHICLE_ID 就自动取车辆列表的第一台。
func (c *teslaClient) resolveVehicleID(ctx context.Context) (string, error) {
	c.mu.RLock()
	vid := c.vehicleID
	c.mu.RUnlock()
	if vid != "" {
		return vid, nil
	}
	body, code, err := c.get(ctx, c.fleetURL+"/api/1/vehicles")
	if err != nil {
		return "", err
	}
	if code != http.StatusOK {
		return "", fmt.Errorf("车辆列表返回 %d: %s", code, maskSecretText(truncate(string(body), 300)))
	}
	var out struct {
		Response []struct {
			// ⚠️ 中国区的 id 是**数字**（实测 <fleet车辆ID>），
			// 全球区是字符串（"12345678901234567"）。用 flexString 两种都收。
			ID flexString `json:"id"`
		} `json:"response"`
	}
	if err := json.Unmarshal(body, &out); err != nil {
		return "", fmt.Errorf("车辆列表解析失败: %w", err)
	}
	if len(out.Response) == 0 {
		return "", errors.New("车辆列表为空")
	}
	vid = string(out.Response[0].ID)
	c.mu.Lock()
	c.vehicleID = vid
	c.mu.Unlock()
	log.Printf("ℹ️  Tesla Fleet API 自动选中车辆 %s", vid)
	return vid, nil
}

// flexString 收「字符串或数字」的 JSON 值，统一的字符串形式。
//
// 中国区 Fleet API 的 id / vehicle_id 是裸数字（如 <fleet车辆ID>），
// 全球区是带引号的字符串。用 string 直接 Unmarshal 会在中国区报
// "json: cannot unmarshal number into Go struct field .response.id of type string"。
type flexString string

func (f *flexString) UnmarshalJSON(b []byte) error {
	s := strings.TrimSpace(string(b))
	if s == "null" || s == "" {
		return nil
	}
	if strings.HasPrefix(s, `"`) {
		var str string
		if err := json.Unmarshal(b, &str); err != nil {
			return err
		}
		*f = flexString(str)
		return nil
	}
	*f = flexString(s)
	return nil
}

// teslaVehicleState 一次 vehicle_data 拉回来的车辆状态快照。
type teslaVehicleState struct {
	Locked   *bool      // 车门锁状态
	LockedAt *time.Time // 车端上报的采集时刻
	Odometer *float64   // 里程表 km
	TPMS     TeslaTPMS  // 四轮实时胎压
}

// vehicleStateJSON 中国区 vehicle_data 里的 vehicle_state 段。
//
// 全部用指针：缺字段 = 没读到（nil），而不是 0。
// 胎压 0 bar 是不可能值，缺字段压成 0 会让 App 显示一眼假的读数。
type vehicleStateJSON struct {
	Locked    *bool    `json:"locked"`
	Timestamp *int64   `json:"timestamp"` // 毫秒
	Odometer  *float64 `json:"odometer"`  // km

	// 实时胎压（bar）
	PressureFL *float64 `json:"tpms_pressure_fl"`
	PressureFR *float64 `json:"tpms_pressure_fr"`
	PressureRL *float64 `json:"tpms_pressure_rl"`
	PressureRR *float64 `json:"tpms_pressure_rr"`

	// Tesla 服务端自己的告警判定（优先于我们自己的阈值计算）
	HardFL *bool `json:"tpms_hard_warning_fl"`
	HardFR *bool `json:"tpms_hard_warning_fr"`
	HardRL *bool `json:"tpms_hard_warning_rl"`
	HardRR *bool `json:"tpms_hard_warning_rr"`

	SoftFL *bool `json:"tpms_soft_warning_fl"`
	SoftFR *bool `json:"tpms_soft_warning_fr"`
	SoftRL *bool `json:"tpms_soft_warning_rl"`
	SoftRR *bool `json:"tpms_soft_warning_rr"`
}

// toState 把 vehicle_state 段转成内部快照。
func (v *vehicleStateJSON) toState() teslaVehicleState {
	st := teslaVehicleState{
		Locked:   v.Locked,
		Odometer: v.Odometer,
	}
	if v.Timestamp != nil && *v.Timestamp > 0 {
		t := time.UnixMilli(*v.Timestamp).UTC()
		st.LockedAt = &t
	}
	st.TPMS = TeslaTPMS{
		FL: TeslaTPMSWheel{Pressure: v.PressureFL, Hard: boolOf(v.HardFL), Soft: boolOf(v.SoftFL)},
		FR: TeslaTPMSWheel{Pressure: v.PressureFR, Hard: boolOf(v.HardFR), Soft: boolOf(v.SoftFR)},
		RL: TeslaTPMSWheel{Pressure: v.PressureRL, Hard: boolOf(v.HardRL), Soft: boolOf(v.SoftRL)},
		RR: TeslaTPMSWheel{Pressure: v.PressureRR, Hard: boolOf(v.HardRR), Soft: boolOf(v.SoftRR)},
		At: st.LockedAt,
	}
	return st
}

// boolOf 把「可空布尔」变成普通布尔：nil = false（没告警）。
func boolOf(b *bool) bool { return b != nil && *b }

// fetchVehicleState 拉某辆车的实时状态（锁车 + 四轮胎压 + 里程表）。
//
// GET /api/1/vehicles/{id}/vehicle_data —— **不带 endpoints 参数**。
//
// ⚠️ 中国区（auth.tesla.cn / fleet-api.prd.cn.vn.cloud.tesla.cn）的实测行为
// 与全球区文档不一致，三处都踩过：
//
//  1. **不支持 endpoints**：带上 `?endpoints=lock_state` 只会返回 462 字节的
//     车辆元数据（id/vin/state…），**完全没有 data 字段**，拿不到锁车状态。
//     不带参数才会返回约 6.5 KB 的完整状态。
//  2. **状态是平铺的**，不是 `response.data.lock_state`：
//     中国区把各状态组直接挂在 response 下，锁车在
//     `response.vehicle_state.locked`（同组还有 timestamp、odometer、
//     四轮胎压等）。全球区才是 `response.data.lock_state`。
//  3. **实时胎压也在同一个 vehicle_state 里**（中国区实测）：
//     `tpms_pressure_fl/fr/rl/rr`（bar）+ `tpms_hard_warning_*` +
//     `tpms_soft_warning_*`。所以锁车与胎压一次请求就拿全了。
//
// 两种包封都认：先找 vehicle_state（同时带出胎压），再回退到 data.lock_state。
//
// 需要 scope vehicle_device_data。
func (c *teslaClient) fetchVehicleState(ctx context.Context, vehicleID string) (teslaVehicleState, error) {
	u := fmt.Sprintf("%s/api/1/vehicles/%s/vehicle_data",
		c.fleetURL, url.PathEscape(vehicleID))
	body, code, err := c.get(ctx, u)
	if err != nil {
		return teslaVehicleState{}, err
	}
	if code == http.StatusRequestTimeout { // 408：车睡着了
		return teslaVehicleState{}, errVehicleUnavailable
	}
	// ⚠️ v0.9.0：401/403 单独识别。
	// 凭据坏了 ≠ 车解锁了，所以它同样**不清空**已知状态，只把原因记给 App。
	if code == http.StatusUnauthorized || code == http.StatusForbidden {
		return teslaVehicleState{}, fmt.Errorf("%w: vehicle_data 返回 %d", errTeslaAuth, code)
	}
	if code != http.StatusOK {
		return teslaVehicleState{}, fmt.Errorf("vehicle_data 返回 %d: %s", code, maskSecretText(truncate(string(body), 300)))
	}

	// 包封见过 {"response":{...}} 与 {"data":{...}}，两种都认。
	var wrapper map[string]json.RawMessage
	if err := json.Unmarshal(body, &wrapper); err != nil {
		return teslaVehicleState{}, fmt.Errorf("vehicle_data 解析失败: %w", err)
	}
	raw, ok := wrapper["response"]
	if !ok {
		raw, ok = wrapper["data"]
	}
	if !ok {
		return teslaVehicleState{}, errors.New("vehicle_data 响应里没有 response/data 字段")
	}

	// 中国区形态：状态平铺在 response 下，锁车与胎压都在 vehicle_state 里。
	var flat struct {
		VehicleState *vehicleStateJSON `json:"vehicle_state"`
	}
	if err := json.Unmarshal(raw, &flat); err == nil && flat.VehicleState != nil {
		st := flat.VehicleState.toState()
		if st.Locked == nil {
			return teslaVehicleState{}, errors.New("vehicle_data 响应里没有 lock_state")
		}
		return st, nil
	}

	// 全球区形态：response.data.lock_state（实测不含胎压字段）
	var data struct {
		LockState *bool `json:"lock_state"`
	}
	if err := json.Unmarshal(raw, &data); err != nil {
		return teslaVehicleState{}, fmt.Errorf("lock_state 解析失败: %w", err)
	}
	if data.LockState == nil {
		return teslaVehicleState{}, errors.New("vehicle_data 响应里没有 lock_state")
	}
	return teslaVehicleState{Locked: data.LockState}, nil
}

// LiveTpmsResult 实时胎压的判定结果。
type LiveTpmsResult struct {
	Alerting   bool     // 是否有异常
	Wheels     []string // 异常轮次（中文名）
	Values     []string // 异常轮次的读数描述
	WarnWheels []string // 接近阈值的轮次（Tesla 的软告警）
}

// EvaluateLiveTpms 判定实时胎压（服务端唯一事实源）。
//
// 判据优先级：
//  1. **Tesla 服务端自己的硬告警位**（tpms_hard_warning_*）——它说了算，优先于阈值
//  2. **Tesla 服务端自己的软告警位**（tpms_soft_warning_*）——「接近阈值」的提示
//  3. **阈值兜底**（复用 alerts.go 的 EvaluateTpms，判据与既有 tpms_* 完全一致）：
//     Tesla 只在告警时才给 warning 位，不告警时不会告诉你「快到了」，
//     所以越界（<2.5 / >3.3 bar）与单胎离群仍要自己算一遍。
//
// 硬告警 → 异常；软告警 → 接近阈值（提示，不算异常）。
func EvaluateLiveTpms(tpms TeslaTPMS) LiveTpmsResult {
	// 阈值兜底：判据与既有的 tpms_* 完全一致（见 alerts.go）
	base := EvaluateTpms(tpms.FL.Pressure, tpms.FR.Pressure, tpms.RL.Pressure, tpms.RR.Pressure)
	thresholdHit := map[string]bool{}
	for _, w := range base.Wheels {
		thresholdHit[w] = true
	}

	wheels := []struct {
		name string
		w    TeslaTPMSWheel
	}{
		{"左前", tpms.FL}, {"右前", tpms.FR}, {"左后", tpms.RL}, {"右后", tpms.RR},
	}

	res := LiveTpmsResult{}
	for _, w := range wheels {
		if w.w.Hard || thresholdHit[w.name] {
			res.Alerting = true
			res.Wheels = append(res.Wheels, w.name)
			if w.w.Pressure != nil {
				res.Values = append(res.Values, fmtBar(w.name, *w.w.Pressure))
			}
			continue
		}
		if w.w.Soft {
			res.WarnWheels = append(res.WarnWheels, w.name)
		}
	}
	return res
}

// get 发一个带 Bearer 头的 GET。
func (c *teslaClient) get(ctx context.Context, urlStr string) ([]byte, int, error) {
	c.mu.RLock()
	token := c.accessToken
	c.mu.RUnlock()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, urlStr, nil)
	if err != nil {
		return nil, 0, err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return nil, resp.StatusCode, err
	}
	return body, resp.StatusCode, nil
}

// ---------- .env 读写（refresh_token 轮换落盘） ----------

// writeEnvValue 把 .env 里 key=... 的值改成 value，其余内容原样保留。
//
// 做法：
//  1. 同目录写临时文件 + os.Rename 覆盖（原子；崩溃不会毁掉 .env）
//  2. rename 不可用时（例如 .env 是 bind mount 挂载点，Linux 上 rename 会 EBUSY）
//     → 退化成原地覆写，并告诉调用方这次不是原子的
//
// 返回 nil 表示 .env 已经写成功（哪怕走的是非原子的原地覆写）。
func writeEnvValue(path, key, value string) error {
	content, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	updated := replaceEnvLine(content, key, value)

	dir := filepath.Dir(path)
	tmp, err := os.CreateTemp(dir, "."+filepath.Base(path)+".tmp*")
	if err != nil {
		// 目录不可写 → 退化成原地覆写
		return fallbackInPlace(path, updated, "临时文件不可创建")
	}
	defer os.Remove(tmp.Name()) // rename 成功后这行是 no-op，失败时清理残留文件
	if _, err := tmp.Write(updated); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmp.Name(), path); err != nil {
		// .env 是 bind mount 挂载点时 rename 会 EBUSY → 原地覆写
		return fallbackInPlace(path, updated, "rename 不可用")
	}
	return nil
}

// fallbackInPlace 退化为原地覆写（.env 是挂载点时 rename 用不了）。
//
// 原地覆写不是原子的：极端情况下进程被 kill 可能写坏 .env。
// 但它只动 TESLA_REFRESH_TOKEN 那一行，行数与其它行内容都不变，
// 最坏情况是那一行的值不完整——下次刷新会再写一次。
func fallbackInPlace(path string, updated []byte, why string) error {
	if err := writeEnvInPlace(path, updated); err != nil {
		return fmt.Errorf("原地覆写失败（%s）: %w", why, err)
	}
	log.Printf("ℹ️  %s 是挂载点或目录不可写（%s），已退化为原地覆写 .env（本次非原子）", path, why)
	return nil
}

// writeEnvInPlace 原地覆写文件（非原子，仅在 rename 不可用时兜底）。
func writeEnvInPlace(path string, content []byte) error {
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_TRUNC, 0)
	if err != nil {
		return err
	}
	defer f.Close()
	if _, err := f.Write(content); err != nil {
		return err
	}
	return f.Sync()
}

// replaceEnvLine 返回把 key=... 那行的值换成 value 之后的完整内容。
//
// 原样保留：BOM、其它行的内容与行尾、目标行自己的行尾（CRLF/LF 都行）。
// 目标行不存在时追加到文件末尾，用文件里已有的行尾风格。
func replaceEnvLine(content []byte, key, value string) []byte {
	prefix := []byte(key + "=")

	// BOM 单独摘出来：它贴在第一行行首，不剥掉就匹配不到那行的键。
	// 最后原样放回。
	bom := []byte{}
	body := content
	if bytes.HasPrefix(body, []byte{0xEF, 0xBB, 0xBF}) {
		bom = body[:3]
		body = body[3:]
	}

	lines := splitLinesKeepEndings(body)
	for i, ln := range lines {
		trimmed, ending := splitLineEnding(ln)
		lead := trimmed[:len(trimmed)-len(bytes.TrimLeft(trimmed, " \t"))]
		if bytes.HasPrefix(bytes.TrimLeft(trimmed, " \t"), prefix) {
			rebuilt := make([]byte, 0, len(lead)+len(prefix)+len(value)+len(ending))
			rebuilt = append(rebuilt, lead...)
			rebuilt = append(rebuilt, prefix...)
			rebuilt = append(rebuilt, []byte(value)...)
			rebuilt = append(rebuilt, ending...)
			lines[i] = rebuilt
			return joinBytes(bom, bytes.Join(lines, nil))
		}
	}
	// 没有这一行 → 追加
	ending := []byte("\n")
	if bytes.Contains(body, []byte("\r\n")) {
		ending = []byte("\r\n")
	}
	out := make([]byte, 0, len(body)+len(prefix)+len(value)+len(ending)+1)
	out = append(out, body...)
	if len(body) > 0 && body[len(body)-1] != '\n' {
		out = append(out, ending...)
	}
	out = append(out, prefix...)
	out = append(out, []byte(value)...)
	out = append(out, ending...)
	return joinBytes(bom, out)
}

// joinBytes 把两段字节拼起来（不用 append 拼到源切片的底层数组上）。
func joinBytes(head, tail []byte) []byte {
	out := make([]byte, 0, len(head)+len(tail))
	out = append(out, head...)
	out = append(out, tail...)
	return out
}

// splitLineEnding 把一行的行尾（\r\n 或 \n）摘出来，行体单独返回。
func splitLineEnding(ln []byte) (body, ending []byte) {
	if idx := bytes.IndexByte(ln, '\n'); idx >= 0 {
		if idx > 0 && ln[idx-1] == '\r' {
			return ln[:idx-1], ln[idx-1:]
		}
		return ln[:idx], ln[idx:]
	}
	if idx := bytes.IndexByte(ln, '\r'); idx >= 0 {
		return ln[:idx], ln[idx:]
	}
	return ln, nil
}

// splitLinesKeepEndings 按行切开，行尾（\r\n 或 \n）留在行内。
func splitLinesKeepEndings(content []byte) [][]byte {
	var out [][]byte
	start := 0
	for i := 0; i < len(content); i++ {
		if content[i] == '\n' {
			out = append(out, content[start:i+1])
			start = i + 1
		}
	}
	if start < len(content) {
		out = append(out, content[start:])
	}
	return out
}

// readEnvValue 从 .env 里读一个键的值（只读，不写）。
func readEnvValue(path, key string) string {
	if path == "" {
		return ""
	}
	content, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	prefix := []byte(key + "=")
	for _, ln := range splitLinesKeepEndings(content) {
		body := bytes.TrimRight(ln, "\r\n")
		body = bytes.TrimLeft(body, " \t")
		if bytes.HasPrefix(body, prefix) {
			v := strings.TrimSpace(string(body[len(prefix):]))
			v = strings.Trim(v, `"'`)
			return v
		}
	}
	return ""
}

// readTrimmedFile 读一个只有一行的文件（兜底状态文件）。
func readTrimmedFile(path string) string {
	if path == "" {
		return ""
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

// ---------- 日志脱敏 ----------

// secretPattern 连续 20 个以上的 token/密钥字符。
var secretPattern = regexp.MustCompile(`[A-Za-z0-9_\-]{20,}`)

// maskSecretText 把日志里可能出现的令牌打码。
func maskSecretText(s string) string {
	return secretPattern.ReplaceAllString(s, "***")
}

// truncate 截断长文本，避免日志刷屏。
func truncate(s string, n int) string {
	s = strings.TrimSpace(s)
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}
