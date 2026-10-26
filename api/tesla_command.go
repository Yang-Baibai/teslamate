package main

// ⚠️ **v0.66.0（第 33 轮「车辆控制下沉」）**：Tesla 车控命令通道。
//
// ## 为什么这个文件存在
//
// 车主诉求：「我希望接下来给这个 app 加入车辆控制的功能，
// 并且在 PC 上提供标准的 MCP 或 CLI 服务，让我电脑上的 Hermes
// 也可以完整的查看车辆数据和操控车辆。」
//
// 并且明确：「**Housekeeper 中的车辆控制解耦掉，正确的应该是车辆控制在此项目中。
// Housekeeper 调用这个项目。**」
//
// ⇒ 车控能力归 **teslamate**，Housekeeper 将来改成调 teslamate 的 API。
//
// ## 通道是怎么走的
//
// Tesla 的「签名命令」（2021 年后的 Model 3/Y 必须）不能由业务代码直接发：
// 它要用**注册在 Tesla partner account 上的那把私钥**做 ECDSA 签名，
// 再用 Tesla 下发的 **domain session key** 加密 payload。
// Tesla 官方把这套协议实现成了独立的 sidecar：**`tesla/vehicle-command`**。
//
// 完整链路：
//
//	teslamate(Go API)  ──①拿 access_token──▶  Tesla token 端点
//	        │
//	        └──②带 Bearer token + JSON body──▶  command-proxy(:4443)
//	                    │                              │
//	                    │                        ③用 /data 里的车辆私钥签名
//	                    ▼                              │
//	              signed_command ─────────────────────▶  Tesla Fleet API
//
// ① 复用 `tesla.go` 里已有的 `ensureToken`（access_token 8 小时有效，剩 1 小时自动刷新）。
// ② proxy 的 HTTPS 用**自签证书**（公钥就是注册到 partner account 的那把），
//    所以客户端要信自己的 CA（`TESLA_COMMAND_PROXY_CA`）。
// ③ 车辆私钥 + TLS 证书放在 `teslamate_command_keys` 卷里（**不入库**）。
//
// ## 令牌的口径（⚠️ 一次性 refresh_token）
//
// 中国区的 `refresh_token` 是**一次性**的：每次刷新返回新的，旧的立刻失效。
// teslamate 的 `tesla.go` 已经处理好了轮换与落盘（回写 `.env`）。
//
// ⚠️ **但 scope 是授权时定的**：只读授权（`openid offline_access vehicle_device_data`）
// 换来的令牌**没有 `vehicle_cmds`** ⇒ 发车控命令会 403。
// 要用车控，令牌必须是**扩了 scope 的那次授权**拿到的。
//
// ## 风险分级
//
// 本项目是**自用单用户**（API token 就是边界），所以**不做 TOTP** ——
// 那是 Housekeeper 面向多用户的设计。
// 但**高危动作仍然分级**，App 与 MCP 都要据此决定要不要二次确认。
//
//	high   ：解锁 / 开合尾门 / 远程启动 —— 会动车、会开孔、会让人上车
//	medium ：空调温度、充电上限、充电电流、开始/停止充电、代客模式、唤醒
//	low    ：锁车、鸣笛、闪灯、关空调、哨兵模式
//
// ⚠️ **唤醒（wake_up）单独计价**（Tesla 把它当 10 个单位），且会把车叫醒 ——
// 所以它虽然是 medium，但 UI 上要提示「会消耗电量、可能计费」。

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
)

// --------------------------------------------------------------------------
// 风险级别
// --------------------------------------------------------------------------

const (
	teslaCmdRiskLow    = "low"
	teslaCmdRiskMedium = "medium"
	teslaCmdRiskHigh   = "high"
)

// 参数取值范围（与 Tesla App 的舒适区一致；车端会拒越界值）。
const (
	teslaChargeLimitMin = 50
	teslaChargeLimitMax = 100
	teslaTempMin        = 15
	teslaTempMax        = 28
	teslaAmpsMin        = 5
	teslaAmpsMax        = 48
	teslaCredMinLen     = 4
	teslaCredMaxLen     = 20
)

// teslaCommandSpec 一个可下发的动作的静态描述。
type teslaCommandSpec struct {
	Action       string   `json:"action"`
	Endpoint     string   `json:"-"`
	Description  string   `json:"description"`
	Risk         string   `json:"risk"`
	Params       []string `json:"params"`
	WakesVehicle bool     `json:"wakes_vehicle"`
}

// teslaCommandCatalog 全部 16 个动作。
//
// ⚠️ 顺序 = App 与 MCP 展示的顺序（按风险从高到低排）。
var teslaCommandCatalog = []teslaCommandSpec{
	{Action: "door_unlock", Endpoint: "door_unlock", Description: "解锁车门", Risk: teslaCmdRiskHigh},
	{Action: "actuate_trunk", Endpoint: "actuate_trunk", Description: "开合尾门", Risk: teslaCmdRiskHigh},
	{Action: "remote_start_drive", Endpoint: "remote_start_drive", Description: "远程启动（无钥匙驾驶 2 分钟）", Risk: teslaCmdRiskHigh,
		Params: []string{"password"}},
	{Action: "set_valet_mode", Endpoint: "set_valet_mode", Description: "代客模式开关", Risk: teslaCmdRiskMedium,
		Params: []string{"on", "password"}},
	{Action: "set_temperature", Endpoint: "set_temps", Description: "设定座舱温度（℃）", Risk: teslaCmdRiskMedium,
		Params: []string{"driver_temp", "passenger_temp"}},
	{Action: "set_climate_on", Endpoint: "set_climate_on", Description: "开启空调", Risk: teslaCmdRiskMedium},
	{Action: "set_climate_off", Endpoint: "set_climate_off", Description: "关闭空调", Risk: teslaCmdRiskLow},
	{Action: "set_charge_limit", Endpoint: "set_charge_limit", Description: "设置充电上限（%）", Risk: teslaCmdRiskMedium,
		Params: []string{"percent"}},
	{Action: "set_charging_amps", Endpoint: "set_charging_amps", Description: "设置充电电流（A）", Risk: teslaCmdRiskMedium,
		Params: []string{"charging_amps"}},
	{Action: "charge_start", Endpoint: "charge_start", Description: "开始充电", Risk: teslaCmdRiskMedium},
	{Action: "charge_stop", Endpoint: "charge_stop", Description: "停止充电", Risk: teslaCmdRiskMedium},
	{Action: "wake_up", Endpoint: "wake_up", Description: "唤醒车辆（会耗电、可能计费）", Risk: teslaCmdRiskMedium,
		WakesVehicle: true},
	{Action: "door_lock", Endpoint: "door_lock", Description: "锁车", Risk: teslaCmdRiskLow},
	{Action: "honk_horn", Endpoint: "honk_horn", Description: "鸣笛", Risk: teslaCmdRiskLow},
	{Action: "flash_lights", Endpoint: "flash_lights", Description: "闪灯", Risk: teslaCmdRiskLow},
	{Action: "set_sentry_mode", Endpoint: "set_sentry_mode", Description: "哨兵模式开关", Risk: teslaCmdRiskLow,
		Params: []string{"on"}},
}

// teslaCommandByAction 动作名 → 规格。
var teslaCommandByAction = func() map[string]teslaCommandSpec {
	m := make(map[string]teslaCommandSpec, len(teslaCommandCatalog))
	for _, s := range teslaCommandCatalog {
		m[s.Action] = s
	}
	return m
}()

// --------------------------------------------------------------------------
// 参数校验
// --------------------------------------------------------------------------

// teslaCmdBool 取布尔参数。
func teslaCmdBool(p map[string]any, key string, def bool) (bool, error) {
	v, ok := p[key]
	if !ok || v == nil {
		return def, nil
	}
	b, ok := v.(bool)
	if !ok {
		return false, fmt.Errorf("参数 %s 必须是布尔值", key)
	}
	return b, nil
}

// teslaCmdInt 取整数参数（带范围校验）。
func teslaCmdInt(p map[string]any, key string, lo, hi int, def *int) (int, error) {
	v, ok := p[key]
	if !ok || v == nil {
		if def == nil {
			return 0, fmt.Errorf("缺少参数 %s", key)
		}
		return *def, nil
	}
	f, ok := v.(float64)
	if !ok {
		return 0, fmt.Errorf("参数 %s 必须是数字", key)
	}
	if f != float64(int(f)) {
		return 0, fmt.Errorf("参数 %s 必须是整数", key)
	}
	n := int(f)
	if n < lo || n > hi {
		return 0, fmt.Errorf("参数 %s 必须在 %d~%d 之间", key, lo, hi)
	}
	return n, nil
}

// teslaCmdCredential 取口令类参数（代客模式 PIN / 远程启动 PIN）。
func teslaCmdCredential(p map[string]any, key string) (string, error) {
	v, ok := p[key]
	if !ok || v == nil {
		return "", fmt.Errorf("缺少参数 %s", key)
	}
	s, ok := v.(string)
	if !ok {
		return "", fmt.Errorf("参数 %s 必须是字符串", key)
	}
	if len(s) < teslaCredMinLen || len(s) > teslaCredMaxLen {
		return "", fmt.Errorf("参数 %s 长度必须在 %d~%d 之间", key, teslaCredMinLen, teslaCredMaxLen)
	}
	return s, nil
}

// buildTeslaCommandBody 校验参数并构造下发给 proxy 的 body。
//
// ⚠️ 校验失败**不发任何网络请求**（参数错就退回去，别让车收到半截命令）。
func buildTeslaCommandBody(action string, p map[string]any) (map[string]any, error) {
	if p == nil {
		p = map[string]any{}
	}
	switch action {
	case "door_lock":
		return map[string]any{"on": true}, nil
	case "door_unlock":
		return map[string]any{"on": true}, nil
	case "honk_horn", "flash_lights":
		return map[string]any{}, nil
	case "set_climate_on":
		return map[string]any{"on": true}, nil
	case "set_climate_off":
		return map[string]any{"on": false}, nil
	case "actuate_trunk", "charge_start", "charge_stop", "wake_up":
		return map[string]any{}, nil
	case "set_temperature":
		driver, err := teslaCmdInt(p, "driver_temp", teslaTempMin, teslaTempMax, nil)
		if err != nil {
			return nil, err
		}
		passenger := driver
		if _, ok := p["passenger_temp"]; ok {
			passenger, err = teslaCmdInt(p, "passenger_temp", teslaTempMin, teslaTempMax, nil)
			if err != nil {
				return nil, err
			}
		}
		return map[string]any{"driver_temp": driver, "passenger_temp": passenger}, nil
	case "set_charge_limit":
		percent, err := teslaCmdInt(p, "percent", teslaChargeLimitMin, teslaChargeLimitMax, nil)
		if err != nil {
			return nil, err
		}
		return map[string]any{"percent": percent}, nil
	case "set_charging_amps":
		amps, err := teslaCmdInt(p, "charging_amps", teslaAmpsMin, teslaAmpsMax, nil)
		if err != nil {
			return nil, err
		}
		return map[string]any{"charging_amps": amps}, nil
	case "set_sentry_mode":
		on, err := teslaCmdBool(p, "on", true)
		if err != nil {
			return nil, err
		}
		return map[string]any{"on": on}, nil
	case "set_valet_mode":
		on, err := teslaCmdBool(p, "on", true)
		if err != nil {
			return nil, err
		}
		pw, err := teslaCmdCredential(p, "password")
		if err != nil {
			return nil, err
		}
		return map[string]any{"on": on, "password": pw}, nil
	case "remote_start_drive":
		pw, err := teslaCmdCredential(p, "password")
		if err != nil {
			return nil, err
		}
		return map[string]any{"password": pw}, nil
	}
	return nil, fmt.Errorf("不支持的命令 %q", action)
}

// --------------------------------------------------------------------------
// command-proxy 客户端
// --------------------------------------------------------------------------

var (
	teslaProxyClientOnce sync.Once
	teslaProxyClient     *http.Client
	teslaProxyClientErr  error
)

// teslaCommandProxyURL 代理地址。
func teslaCommandProxyURL() string {
	return strings.TrimRight(strings.TrimSpace(getenv("TESLA_COMMAND_PROXY_URL", "")), "/")
}

// teslaCommandProxyClient 造一个信自己 CA 的 HTTP 客户端。
//
// ⚠️ proxy 用的是**自签证书**（它的公钥就是注册到 partner account 的那把 EC 密钥），
// 所以必须显式信任 `TESLA_COMMAND_PROXY_CA` 指向的 CA ——
// 否则 TLS 校验直接失败（`x509: certificate signed by unknown authority`）。
func teslaCommandProxyClient() (*http.Client, error) {
	teslaProxyClientOnce.Do(func() {
		caPath := strings.TrimSpace(getenv("TESLA_COMMAND_PROXY_CA", ""))
		if caPath == "" {
			teslaProxyClientErr = errors.New("未配置 TESLA_COMMAND_PROXY_CA")
			return
		}
		pem, err := os.ReadFile(caPath)
		if err != nil {
			teslaProxyClientErr = fmt.Errorf("读 CA 失败: %w", err)
			return
		}
		pool := x509.NewCertPool()
		if !pool.AppendCertsFromPEM(pem) {
			teslaProxyClientErr = errors.New("CA 文件里没有可用证书")
			return
		}
		teslaProxyClient = &http.Client{
			Timeout: 30 * time.Second,
			Transport: &http.Transport{
				TLSClientConfig: &tls.Config{RootCAs: pool, MinVersion: tls.VersionTLS12},
			},
		}
	})
	return teslaProxyClient, teslaProxyClientErr
}

// teslaCommandResult 一次命令下发的结果。
type teslaCommandResult struct {
	Action      string         `json:"action"`
	Risk        string         `json:"risk"`
	Description string         `json:"description"`
	VIN         string         `json:"vin"`
	Sent        bool           `json:"sent"`
	Response    map[string]any `json:"response,omitempty"`
	Error       string         `json:"error,omitempty"`
	At          time.Time      `json:"at"`
}

// resolveVehicleVIN 取这台车的 VIN（17 位）。
//
// ⚠️ **command-proxy 的命令端点要的是 VIN，不是 Fleet API 的 id** ——
// 实测传数字 id（<fleet车辆ID>）会被 proxy 回
// `expected 17-character VIN in path (do not user Fleet API ID)`。
// 两者不能互换。
func (c *teslaClient) resolveVehicleVIN(ctx context.Context) (string, error) {
	c.mu.RLock()
	vin := c.vehicleVIN
	c.mu.RUnlock()
	if vin != "" {
		return vin, nil
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
			ID  flexString `json:"id"`
			VIN string     `json:"vin"`
		} `json:"response"`
	}
	if err := json.Unmarshal(body, &out); err != nil {
		return "", fmt.Errorf("车辆列表解析失败: %w", err)
	}
	if len(out.Response) == 0 {
		return "", errors.New("车辆列表为空")
	}
	vin = strings.TrimSpace(out.Response[0].VIN)
	if vin == "" {
		return "", errors.New("车辆列表里没有 vin 字段")
	}
	c.mu.Lock()
	c.vehicleVIN = vin
	c.mu.Unlock()
	log.Printf("ℹ️  车控用 VIN %s", vin)
	return vin, nil
}

// sendTeslaCommand 校验并下发一条命令。
//
// ⚠️ 车休眠时 Tesla 会回 408（`vehicle is offline or asleep`）——
// 那是**车没醒**，不是命令错；调用方要区分这两种（见 `teslaCommandOfflineHint`）。
func (c *teslaClient) sendTeslaCommand(ctx context.Context, action string, params map[string]any) (*teslaCommandResult, error) {
	spec, ok := teslaCommandByAction[action]
	if !ok {
		return nil, fmt.Errorf("不支持的命令 %q", action)
	}
	body, err := buildTeslaCommandBody(action, params)
	if err != nil {
		return nil, err
	}
	proxy := teslaCommandProxyURL()
	if proxy == "" {
		return nil, errors.New("未配置 TESLA_COMMAND_PROXY_URL —— 车控代理没起来")
	}
	if err := c.ensureToken(ctx); err != nil {
		return nil, fmt.Errorf("拿不到 Tesla 令牌: %w", err)
	}
	c.mu.RLock()
	token := c.accessToken
	c.mu.RUnlock()
	// ⚠️ 命令端点要 **VIN**，不是 Fleet API 的数字 id。
	vin, err := c.resolveVehicleVIN(ctx)
	if err != nil {
		return nil, fmt.Errorf("拿不到车辆 VIN: %w", err)
	}

	raw, _ := json.Marshal(body)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		fmt.Sprintf("%s/api/1/vehicles/%s/command/%s", proxy, vin, spec.Endpoint),
		bytes.NewReader(raw))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+token)

	client, err := teslaCommandProxyClient()
	if err != nil {
		return nil, err
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("调 command-proxy 失败: %w", err)
	}
	defer resp.Body.Close()
	respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))

	res := &teslaCommandResult{
		Action:      action,
		Risk:        spec.Risk,
		Description: spec.Description,
		VIN:         vin,
		At:          time.Now(),
	}
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		res.Sent = true
		var parsed map[string]any
		if json.Unmarshal(respBody, &parsed) == nil {
			res.Response = parsed
		}
		// ⚠️ 审计：谁、什么时候、对哪台车做了什么 —— 车控是**不可逆**的。
		log.Printf("🚗 车控下发：action=%s risk=%s vin=%s params=%s", action, spec.Risk, vin, redactCommandParams(action, params))
		return res, nil
	}
	res.Error = strings.TrimSpace(string(respBody))
	log.Printf("⚠️ 车控失败：action=%s risk=%s vin=%s http=%d err=%s", action, spec.Risk, vin, resp.StatusCode, res.Error)
	return res, nil
}

// redactCommandParams 审计日志里**不落明文口令**。
func redactCommandParams(action string, params map[string]any) string {
	if params == nil {
		return "{}"
	}
	safe := make(map[string]any, len(params))
	for k, v := range params {
		if k == "password" {
			safe[k] = "***"
			continue
		}
		safe[k] = v
	}
	b, _ := json.Marshal(safe)
	return string(b)
}

// --------------------------------------------------------------------------
// HTTP 端点
// --------------------------------------------------------------------------

// handleTeslaCommandCatalog 列出支持的动作（App 与 MCP 都用它渲染）。
func handleTeslaCommandCatalog(w http.ResponseWriter, r *http.Request) {
	if _, err := pathID(r); err != nil {
		writeErr(w, http.StatusBadRequest, "车 id 不合法")
		return
	}
	writeJSON(w, map[string]any{
		"actions": teslaCommandCatalog,
		"proxy":   teslaCommandProxyConfigured(),
	})
}

// teslaCommandProxyConfigured 代理与令牌是否就绪（给 App 显示「为什么按钮是灰的」）。
func teslaCommandProxyConfigured() bool {
	if teslaCommandProxyURL() == "" {
		return false
	}
	if _, err := teslaCommandProxyClient(); err != nil {
		return false
	}
	return tesla != nil && tesla.configured()
}

// handleTeslaCommand 下发一条命令。
//
// POST /api/v1/cars/{id}/commands/{action}
// body: {"params": {...}} 或直接 {...}
func handleTeslaCommand(w http.ResponseWriter, r *http.Request) {
	if _, err := pathID(r); err != nil {
		writeErr(w, http.StatusBadRequest, "车 id 不合法")
		return
	}
	action := strings.TrimSpace(r.PathValue("action"))
	if action == "" {
		writeErr(w, http.StatusBadRequest, "缺少动作名")
		return
	}
	if _, ok := teslaCommandByAction[action]; !ok {
		writeErr(w, http.StatusBadRequest, "不支持的命令 "+action)
		return
	}

	// body 两种写法都收：{"params":{...}} 与直接 {...}
	var raw map[string]any
	dec := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	if err := dec.Decode(&raw); err != nil && !errors.Is(err, io.EOF) {
		writeErr(w, http.StatusBadRequest, "请求体不是 JSON")
		return
	}
	params, _ := raw["params"].(map[string]any)
	if params == nil {
		// 没套 {"params":…} ⇒ 整个 body 当参数
		params = raw
	}

	if tesla == nil || !tesla.configured() {
		writeErr(w, http.StatusServiceUnavailable, "Tesla 令牌未配置 —— 车控不可用")
		return
	}

	res, err := tesla.sendTeslaCommand(r.Context(), action, params)
	if err != nil {
		// 参数错 = 400；其余（代理没起、令牌不行）= 502
		code := http.StatusBadGateway
		if strings.Contains(err.Error(), "参数") || strings.Contains(err.Error(), "不支持") ||
			strings.Contains(err.Error(), "缺少") {
			code = http.StatusBadRequest
		}
		writeErr(w, code, err.Error())
		return
	}

	// ⚠️ 车在休眠时 proxy 回 408 —— 那不是命令错，是「车没醒」。
	// 把它翻译成 409 + 人话，让 App 能显示「先唤醒它」。
	if !res.Sent && strings.Contains(res.Error, "408") {
		writeJSON(w, map[string]any{
			"action":      res.Action,
			"risk":        res.Risk,
			"description": res.Description,
			"sent":        false,
			"error":       "车在休眠或离线 —— 先唤醒再试",
			"hint":        "车醒着的时候命令才发得出去；唤醒本身会耗电、可能计费",
			"at":          res.At,
		})
		return
	}
	writeJSON(w, res)
}
