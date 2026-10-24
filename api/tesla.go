package main

// ---------- Tesla Fleet API 只读客户端（锁车状态） ----------
//
// 为什么需要它：
//   TeslaMate 的库里没有「车门锁没锁」——它只记行驶、充电、位置。
//   锁车状态只有特斯拉云端知道，只能走 Tesla Fleet API 读 vehicle_data。
//
// 三条硬约束（决定了下面所有设计）：
//  1. 凭据可能没有。没有凭据必须照常启动，locked 返回 null（未知），绝不 panic。
//  2. refresh_token 是一次性的：刷新后旧的立即失效，有效期 3 个月。
//     每次刷新拿到新 token 都必须落盘，否则三个月后要重新走浏览器授权。
//  3. 任何失败都不能影响主服务：锁车状态拿不到就是「未知」。
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
// 并发模型：令牌状态与锁车状态各一把读写锁。
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

	stateMu  sync.RWMutex
	locked   *bool
	lockedAt *time.Time
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
		refreshToken: strings.TrimSpace(getenv("TESLA_REFRESH_TOKEN", "")),
		region:       region,
		vehicleID:    strings.TrimSpace(getenv("TESLA_VEHICLE_ID", "")),
		envFile:      resolveTeslaEnvFile(),
		stateFile:    strings.TrimSpace(getenv("TESLA_REFRESH_TOKEN_FILE", "/tmp/tesla_refresh_token")),
		pollInterval: teslaPollInterval,
	}
	if cfg.refreshToken == "" {
		// 容器里可能只挂了 .env 文件而没注入环境变量，退而从文件里读
		if cfg.envFile != "" {
			cfg.refreshToken = readEnvValue(cfg.envFile, teslaEnvKey)
		}
		if cfg.refreshToken == "" {
			cfg.refreshToken = readTrimmedFile(cfg.stateFile)
		}
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

// setLockState 记下本次采集结果。
func (c *teslaClient) setLockState(locked bool, at time.Time) {
	c.stateMu.Lock()
	defer c.stateMu.Unlock()
	v := locked
	t := at
	c.locked = &v
	c.lockedAt = &t
}

// clearLockState 降级成「未知」。
func (c *teslaClient) clearLockState() {
	c.stateMu.Lock()
	defer c.stateMu.Unlock()
	c.locked = nil
	c.lockedAt = nil
}

// Run 后台轮询循环。ctx 取消即退出。
//
// 节奏：正常每 teslaPollInterval 拉一次 vehicle_data；
// 失败按指数退避（30s → 15min 封顶），别把 API 打爆。
func (c *teslaClient) Run(ctx context.Context) {
	backoff := teslaBackoffInit
	for {
		if ctx.Err() != nil {
			return
		}
		err := c.pollOnce(ctx)
		wait := c.cfg.pollInterval
		switch {
		case err == nil:
			backoff = teslaBackoffInit
		case errors.Is(err, errVehicleUnavailable):
			// 车睡着了：保留上次已知结果，只提示一次，别刷屏
			log.Printf("ℹ️  Tesla 车辆当前不可用（多半休眠）：锁车状态保留上次结果")
			backoff = teslaBackoffInit
		default:
			// 真失败：锁车状态降级成「未知」，退避重试
			c.clearLockState()
			wait = backoff
			backoff *= 2
			if backoff > teslaBackoffMax {
				backoff = teslaBackoffMax
			}
			log.Printf("⚠️  Tesla Fleet API 轮询失败：%v；%s 后重试", err, wait)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(wait):
		}
	}
}

// pollOnce 跑一轮：刷新 token → 找车 → 拉锁车状态。
func (c *teslaClient) pollOnce(ctx context.Context) error {
	if err := c.ensureToken(ctx); err != nil {
		return fmt.Errorf("刷新 access_token 失败: %w", err)
	}
	vid, err := c.resolveVehicleID(ctx)
	if err != nil {
		return fmt.Errorf("获取车辆列表失败: %w", err)
	}
	locked, lockedAt, err := c.fetchLockState(ctx, vid)
	if err != nil {
		return fmt.Errorf("获取锁车状态失败: %w", err)
	}
	// 采集时刻优先用车端上报的 timestamp（车端时钟，更接近数据真实时刻），
	// 没有就用本地时间 —— 它只用于让 App 显示新鲜度。
	if lockedAt == nil {
		now := time.Now().UTC()
		lockedAt = &now
	}
	c.setLockState(*locked, *lockedAt)
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

// fetchLockState 拉某辆车的锁车状态。
//
// GET /api/1/vehicles/{id}/vehicle_data —— **不带 endpoints 参数**。
//
// ⚠️ 中国区（auth.tesla.cn / fleet-api.prd.cn.vn.cloud.tesla.cn）的实测行为
// 与全球区文档不一致，两处都踩过：
//
//  1. **不支持 endpoints**：带上 `?endpoints=lock_state` 只会返回 462 字节的
//     车辆元数据（id/vin/state…），**完全没有 data 字段**，拿不到锁车状态。
//     不带参数才会返回约 6.5 KB 的完整状态。
//  2. **状态是平铺的**，不是 `response.data.lock_state`：
//     中国区把各状态组直接挂在 response 下，锁车在
//     `response.vehicle_state.locked`（同组还有 timestamp、odometer、
//     四轮胎压等）。全球区才是 `response.data.lock_state`。
//
// 两种都认：先找 vehicle_state.locked，再回退到 data.lock_state。
//
// 需要 scope vehicle_device_data。
func (c *teslaClient) fetchLockState(ctx context.Context, vehicleID string) (*bool, *time.Time, error) {
	u := fmt.Sprintf("%s/api/1/vehicles/%s/vehicle_data",
		c.fleetURL, url.PathEscape(vehicleID))
	body, code, err := c.get(ctx, u)
	if err != nil {
		return nil, nil, err
	}
	if code == http.StatusRequestTimeout { // 408：车睡着了
		return nil, nil, errVehicleUnavailable
	}
	if code != http.StatusOK {
		return nil, nil, fmt.Errorf("vehicle_data 返回 %d: %s", code, maskSecretText(truncate(string(body), 300)))
	}

	// 包封见过 {"response":{...}} 与 {"data":{...}}，两种都认。
	var wrapper map[string]json.RawMessage
	if err := json.Unmarshal(body, &wrapper); err != nil {
		return nil, nil, fmt.Errorf("vehicle_data 解析失败: %w", err)
	}
	raw, ok := wrapper["response"]
	if !ok {
		raw, ok = wrapper["data"]
	}
	if !ok {
		return nil, nil, errors.New("vehicle_data 响应里没有 response/data 字段")
	}

	// 中国区形态：状态平铺在 response 下，锁车在 vehicle_state 里。
	var flat struct {
		VehicleState *struct {
			Locked    *bool  `json:"locked"`
			Timestamp *int64 `json:"timestamp"` // 毫秒
		} `json:"vehicle_state"`
	}
	if err := json.Unmarshal(raw, &flat); err == nil && flat.VehicleState != nil && flat.VehicleState.Locked != nil {
		var at *time.Time
		if flat.VehicleState.Timestamp != nil && *flat.VehicleState.Timestamp > 0 {
			t := time.UnixMilli(*flat.VehicleState.Timestamp).UTC()
			at = &t
		}
		return flat.VehicleState.Locked, at, nil
	}

	// 全球区形态：response.data.lock_state
	var data struct {
		LockState *bool `json:"lock_state"`
	}
	if err := json.Unmarshal(raw, &data); err != nil {
		return nil, nil, fmt.Errorf("lock_state 解析失败: %w", err)
	}
	if data.LockState == nil {
		return nil, nil, errors.New("vehicle_data 响应里没有 lock_state")
	}
	return data.LockState, nil, nil
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
