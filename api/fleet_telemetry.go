package main

// ---------- Fleet Telemetry 配置下发（v0.79.0 3/3） ----------
//
// 为什么走 command-proxy 而不是直接打 Fleet API：
//
//	官方明确写了「This endpoint **should be called through the Vehicle Command Proxy**.
//	The configured private key will be used to sign the configuration.」
//	（developer.tesla.cn → Fleet API → Vehicle Endpoints → fleet_telemetry_config create）
//
//	直接打 Fleet API 需要自己造 JWS（Schorr/P-256 签名），而且官方不推荐。
//	而 command-proxy 里已经有那把私钥 —— 它会替我们签，然后转成
//	`fleet_telemetry_config_jws` 发给 Tesla。
//
//	⇒ 我们**复用**已有的 command-proxy 客户端与 `ensureToken()`，
//	  一行令牌逻辑都不另写（那是最容易出错的地方）。

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"sort"
	"strings"
	"time"
)

const (
	// telemetryDefaultCAFile 服务器证书链的挂载路径（compose.yaml 里挂进来的）。
	//
	// ⚠️ 这是 **Tailscale 签的 Let's Encrypt 证书**（公开可信）——
	//    raw TCP 模式下 Tesla 直接看到这张证书，自签会被拒。
	telemetryDefaultCAFile = "/telemetry_certs/server.crt"

	// telemetryDefaultHost / telemetryDefaultPort 车端要连的地址。
	//
	// ⚠️ 443 与 8443 是**同一个域名的两个入口**：
	//    443 给 Tesla 云拉应用公钥（普通 HTTPS），
	//    8443 给车端推遥测（raw TCP 透传，端到端 mTLS）。
	telemetryDefaultHost = "<主机名>.<tailnet域>.ts.net"
	telemetryDefaultPort = 8443

	// telemetryConfigTTL 配置 JWT 的有效期。
	//
	// ⚠️ **Tesla 给了硬边界**（2026-10-29 实调拿到的原文报错）：
	//
	//	"expiration should be greater than 1793204345 and less than 1824653945"
	//
	//	即 **exp ∈ (服务端当下, 服务端当下 + 365 天)**。
	//	- 下界：exp 必须大于服务端当下（否则是过期的配置）
	//	- 上界：**最多 365 天**，而且是「服务端当下 + 365 天」——
	//	  自己按本地时钟算「现在 + 365 天」会**比上界晚几秒** ⇒ 被拒。
	//
	// ⇒ 这里取 360 天：稳稳落在区间内，也留出续签余量。
	//    到期前要重下一次（续期脚本里加一步）。
	telemetryConfigTTL = 360 * 24 * time.Hour
)

// telemetryFields 要让车端推送的**车辆数据字段**。
//
// ⚠️ 字段名必须与官方 vehicle_data.proto 里的键**完全一致** ——
//
//	写错不报错，只是那个字段永远不来。
//	这里全部取自官方文档给的示例配置（developer.tesla.cn → Fleet Telemetry
//	→ Overview → Example Configuration），不自己发明。
//
// ⚠️ `interval_seconds` 是**最小间隔**，不是发送频率：
//
//	官方口径是「值变了才发，且不比间隔更频繁」。
var telemetryFields = map[string]any{
	"Soc":                 map[string]any{"interval_seconds": 60},
	"Locked":              map[string]any{"interval_seconds": 60},
	"EstBatteryRange":     map[string]any{"interval_seconds": 60},
	"Odometer":            map[string]any{"interval_seconds": 300},
	"ChargeAmps":          map[string]any{"interval_seconds": 5},
	"DetailedChargeState": map[string]any{"interval_seconds": 5},

	// ⚠️ **v0.81.0 新增**：车停着不动时最该知道的那批。
	//
	//	这批字段的共同点是「我们**主动去拉**很难拿到」：
	//	- 胎压 / 车窗 / 门 / 哨兵 / 温度：都在 `vehicle_state` 里，
	//	  但要车**醒着**才拉得到；车睡了就只能守着旧缓存
	//	- 充电上限：轮询里也有，但那是 2 分钟前的
	//
	//	⇒ 走遥测（车主动推）之后，**车睡着也有最后已知值**，
	//	而且不用我们为了这几个字段去高频轮询（轮询会撞 Tesla 的 429 限流）。
	//
	// ⚠️ 字段名必须与官方 `vehicle_data.proto` 逐字一致 ——
	//	写错**不报错**，只是那个字段永远不来（静默失效）。
	//	`TestTelemetryConfiguredFieldsAreOfficial` 会当场变红。
	//
	// ⚠️ `interval_seconds` 是**最小间隔**（官方口径：值变了才发，且不比间隔更频繁）。
	//	这批都是变化慢的量 ⇒ 300s。**不要**为了「更实时」调小 ——
	//	车端有速率限制，调小只会把别的字段挤掉。
	"TpmsPressureFl":      map[string]any{"interval_seconds": 300},
	"TpmsPressureFr":      map[string]any{"interval_seconds": 300},
	"TpmsPressureRl":      map[string]any{"interval_seconds": 300},
	"TpmsPressureRr":      map[string]any{"interval_seconds": 300},
	"TpmsHardWarnings":    map[string]any{"interval_seconds": 300},
	"TpmsSoftWarnings":    map[string]any{"interval_seconds": 300},
	"FdWindow":            map[string]any{"interval_seconds": 300},
	"FpWindow":            map[string]any{"interval_seconds": 300},
	"RdWindow":            map[string]any{"interval_seconds": 300},
	"RpWindow":            map[string]any{"interval_seconds": 300},
	"DoorState":           map[string]any{"interval_seconds": 300},
	"SentryMode":          map[string]any{"interval_seconds": 300},
	"InsideTemp":          map[string]any{"interval_seconds": 300},
	"OutsideTemp":         map[string]any{"interval_seconds": 300},
	"ChargeLimitSoc":      map[string]any{"interval_seconds": 300},
	"IsolationResistance": map[string]any{"interval_seconds": 300},

	// ⚠️ **v0.84.0 新增**：固件版本 + **可安装**的固件版本。
	//
	//	`Version`（官方编号 **68**）= 当前固件版本
	//	`SoftwareUpdateVersion`（官方编号 **220**）= **待安装**的固件版本
	//
	// ⇒ 有了它们，**车睡着也能知道**「有没有新固件可装」——
	//   轮询侧的 `vehicle_state.car_version` 只有车**醒着**时才拉得到。
	//
	// ⚠️ **单独成组**（上面留空行）是有意的：gofmt 按「连续行」对齐，
	//    混进上面那组会把整张表的排版都重排 —— 那是与本次改动无关的 diff 污染。
	"Version":               map[string]any{"interval_seconds": 300},
	"SoftwareUpdateVersion": map[string]any{"interval_seconds": 300},

	// ⚠️ **v0.88.0 新增**：**座舱状态**（空调 / 座椅加热 / 方向盘加热）。
	//
	//	为什么现在才加：v0.87.0 让人能**设**座椅加热 / 方向盘加热 / 空调，
	//	但**读不回来** ⇒ Hermes 问「座椅加热开着吗」只能答「不知道」。
	//	这一批补上「设 → 读回」的闭环。
	//
	//	⚠️ 字段名与编号来自官方 proto（protos/vehicle_data.proto → enum Field）：
	//	  SeatHeaterLeft=87  SeatHeaterRight=88
	//	  HvacACEnabled=196  HvacAutoMode=197  HvacFanSpeed=198  HvacFanStatus=199
	//	  HvacLeftTemperatureRequest=200  HvacPower=201
	//	  HvacRightTemperatureRequest=202
	//	  HvacSteeringWheelHeatAuto=203  HvacSteeringWheelHeatLevel=204
	//
	//	⚠️ **固件门槛（官方 proto 注释原文）**：
	//	  "fields 180-183, 185-228 are first available in firmware version
	//	   2024.44.25 and 2024.45.25"
	//	⇒ 196~204 要 **2024.44.25+**；本车实测固件 2026.26.200 ✓
	//	（87/88 在更早的段 ⇒ 无门槛）。
	//
	//	⚠️ `interval_seconds` 是**最小间隔**（官方口径：值变了才发）。
	//	这批都是**变化慢**的量 ⇒ 300s，与上面那批一致。
	"SeatHeaterLeft":              map[string]any{"interval_seconds": 300},
	"SeatHeaterRight":             map[string]any{"interval_seconds": 300},
	"HvacACEnabled":               map[string]any{"interval_seconds": 300},
	"HvacAutoMode":                map[string]any{"interval_seconds": 300},
	"HvacFanSpeed":                map[string]any{"interval_seconds": 300},
	"HvacFanStatus":               map[string]any{"interval_seconds": 300},
	"HvacLeftTemperatureRequest":  map[string]any{"interval_seconds": 300},
	"HvacPower":                   map[string]any{"interval_seconds": 300},
	"HvacRightTemperatureRequest": map[string]any{"interval_seconds": 300},
	"HvacSteeringWheelHeatAuto":   map[string]any{"interval_seconds": 300},
	"HvacSteeringWheelHeatLevel":  map[string]any{"interval_seconds": 300},
}

// --------------------------------------------------------------------------
// 配置构造
// --------------------------------------------------------------------------

// telemetryCABundle 取 CA 证书串（车端用它验证我们的服务器证书）。
//
// ⚠️ **去掉叶子证书**，只留中间证书 + 根证书 ——
//
//	这才是「CA」的语义（叶子是服务器自己给的，不该塞进信任库）。
//	server.crt 里是「叶子 + YE2 + Root YE + ISRG X2」四段。
func telemetryCABundle() (string, error) {
	path := strings.TrimSpace(getenv("TELEMETRY_CA_FILE", telemetryDefaultCAFile))
	raw, err := os.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("读服务器证书失败（%s）：%w", path, err)
	}
	var out []string
	rest := raw
	skippedLeaf := false
	for {
		var block *pem.Block
		block, rest = pem.Decode(rest)
		if block == nil {
			break
		}
		if block.Type != "CERTIFICATE" {
			continue
		}
		if !skippedLeaf {
			skippedLeaf = true
			continue
		}
		out = append(out, string(pem.EncodeToMemory(block)))
	}
	if len(out) == 0 {
		return "", fmt.Errorf("证书文件 %s 里没有可用的 CA 段（至少要有叶子 + 一段 CA）", path)
	}
	return strings.Join(out, ""), nil
}

// telemetryClaims 组装车端配置（JWT claims）。
//
// ⚠️ claim 名来自官方工具 fleet-telemetry/tools/check_server_cert.sh：
//
//	它读的就是 `.hostname` / `.ca` / `.port`（端口缺省 443）。
//	字段选择用 `.fields`，格式来自官方示例配置。
//	`aud` / `iss` 由 command-proxy 覆写（proxy 源码里明确警告会覆盖），
//	所以这里**不要**自己填。
func telemetryClaims() (map[string]any, error) {
	ca, err := telemetryCABundle()
	if err != nil {
		return nil, err
	}
	port := telemetryDefaultPort
	if v := strings.TrimSpace(getenv("TELEMETRY_PORT", "")); v != "" {
		if _, err := fmt.Sscanf(v, "%d", &port); err != nil {
			return nil, fmt.Errorf("TELEMETRY_PORT 不是数字：%q", v)
		}
	}
	return map[string]any{
		"hostname": strings.TrimSpace(getenv("TELEMETRY_HOST", telemetryDefaultHost)),
		"port":     port,
		"ca":       ca,
		"fields":   telemetryFields,
		"exp":      time.Now().Add(telemetryConfigTTL).Unix(),
	}, nil
}

// --------------------------------------------------------------------------
// 与 Tesla 交互
// --------------------------------------------------------------------------

// fleetStatus 车端对本应用的支持情况。
//
// 来源：`POST /api/1/vehicles/fleet_status`（官方 Vehicle Endpoints）。
// 关键字段 `fleet_telemetry_version` —— 车上的遥测客户端版本；
// 为空 ⇒ 这台车**没有** Fleet Telemetry 客户端，下发配置没有意义。
// fleetVehicleInfo 一台车在 fleet_status 里的信息。
type fleetVehicleInfo struct {
	FirmwareVersion                string `json:"firmware_version"`
	VehicleCommandProtocolRequired bool   `json:"vehicle_command_protocol_required"`
	DiscountedDeviceData           bool   `json:"discounted_device_data"`
	FleetTelemetryVersion          string `json:"fleet_telemetry_version"`
	TotalNumberOfKeys              int    `json:"total_number_of_keys"`
}

type fleetStatus struct {
	Response struct {
		// ⚠️ 中国区实测（2026-10-29）：这些字段**不是平铺**的，
		//    而是包在 `vehicle_info` 里按 VIN 索引：
		//
		//	{"response":{"key_paired_vins":["VIN"],"unpaired_vins":[],
		//	  "vehicle_info":{"VIN":{"firmware_version":"2026.26.200",
		//	    "fleet_telemetry_version":"1.3.0", ...}}}}
		//
		//    按官方文档里的平铺结构去解析 ⇒ 全部读成空值，
		//    而接口是 200 —— 又一次「静默失效」。
		KeyPairedVINs []string                    `json:"key_paired_vins"`
		UnpairedVINs  []string                    `json:"unpaired_vins"`
		VehicleInfo   map[string]fleetVehicleInfo `json:"vehicle_info"`
	} `json:"response"`
	// ⚠️ Raw —— 保留原文才能判断「到底有没有遥测客户端版本」。
	Raw string `json:"-"`
}

// ForVIN 取某台车的信息。
func (f *fleetStatus) ForVIN(vin string) (fleetVehicleInfo, bool) {
	if f == nil {
		return fleetVehicleInfo{}, false
	}
	v, ok := f.Response.VehicleInfo[vin]
	return v, ok
}

// KeyPaired 这台车的虚拟钥匙有没有配到车上。
func (f *fleetStatus) KeyPaired(vin string) bool {
	if f == nil {
		return false
	}
	for _, v := range f.Response.KeyPairedVINs {
		if v == vin {
			return true
		}
	}
	return false
}

// fetchFleetStatus 查 fleet_status。
//
// ⚠️ **必须带 vins** —— 中国区实测：body 为 `{}` 会回
//
//	`param is missing or the value is empty or invalid: vins`。
//	官方也写了「discounted_device_data … only available when the endpoint
//	is called with one VIN」。
func fetchFleetStatus(ctx context.Context, vin string) (*fleetStatus, error) {
	if tesla == nil || !tesla.configured() {
		return nil, fmt.Errorf("未配置 Tesla Fleet API 凭据")
	}
	if err := tesla.ensureToken(ctx); err != nil {
		return nil, fmt.Errorf("拿不到 Tesla 令牌：%w", err)
	}
	tesla.mu.RLock()
	token := tesla.accessToken
	tesla.mu.RUnlock()

	payload, err := json.Marshal(map[string]any{"vins": []string{vin}})
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		tesla.fleetURL+"/api/1/vehicles/fleet_status", bytes.NewReader(payload))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := tesla.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("fleet_status 返回 %d：%s", resp.StatusCode, strings.TrimSpace(string(body)))
	}
	var out fleetStatus
	if err := json.Unmarshal(body, &out); err != nil {
		return nil, fmt.Errorf("解析 fleet_status 失败：%w", err)
	}
	out.Raw = strings.TrimSpace(string(body))
	return &out, nil
}

// fleetTelemetryConfigState 车上的遥测配置现状。
//
// 来源：`GET /api/1/vehicles/{vin}/fleet_telemetry_config`。
// `synced=true` ⇒ 车已经采用了目标配置；false ⇒ 车会在下次连后端时采用。
type fleetTelemetryConfigState struct {
	Response struct {
		// ⚠️ Synced 在**没有配置时也是 true**（实测：
		//    `{"synced":true,"config":null,"limit_reached":false,"key_paired":true}`）
		//    ⇒ 判断「车端到底配没配」只能看 Config 是不是 null，
		//    **不能**拿 synced 当「已生效」的证据。
		Synced       bool            `json:"synced"`
		LimitReached bool            `json:"limit_reached"`
		KeyPaired    bool            `json:"key_paired"`
		Config       json.RawMessage `json:"config"`
	} `json:"response"`
	// ⚠️ Raw 留着 —— 「synced=true」到底是「车已采用我们的配置」
	//    还是「这辆车压根没有配置、接口只是回了个默认值」，
	//    只能看原始响应才分得清。**不能靠 synced 一个布尔值下结论。**
	Raw string `json:"-"`
}

// HasConfig 车端**真的**有一份配置（不是接口的默认值）。
func (s *fleetTelemetryConfigState) HasConfig() bool {
	raw := s.Response.Config
	return len(raw) > 0 && string(raw) != "null"
}

// fetchFleetTelemetryConfig 查车上的遥测配置现状。
func fetchFleetTelemetryConfig(ctx context.Context, vin string) (*fleetTelemetryConfigState, error) {
	if tesla == nil || !tesla.configured() {
		return nil, fmt.Errorf("未配置 Tesla Fleet API 凭据")
	}
	if err := tesla.ensureToken(ctx); err != nil {
		return nil, fmt.Errorf("拿不到 Tesla 令牌：%w", err)
	}
	tesla.mu.RLock()
	token := tesla.accessToken
	tesla.mu.RUnlock()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet,
		tesla.fleetURL+"/api/1/vehicles/"+vin+"/fleet_telemetry_config", nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := tesla.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("查遥测配置返回 %d：%s", resp.StatusCode, strings.TrimSpace(string(body)))
	}
	var out fleetTelemetryConfigState
	if err := json.Unmarshal(body, &out); err != nil {
		return nil, fmt.Errorf("解析遥测配置失败：%w", err)
	}
	out.Raw = strings.TrimSpace(string(body))
	return &out, nil
}

// skippedVehicle 官方在响应里回传的「这辆车没配上，原因如下」。
type skippedVehicle struct {
	VIN    string `json:"vin"`
	Reason string `json:"reason"`
}

// deployFleetTelemetryResult 一次下发的结果。
type deployFleetTelemetryResult struct {
	Sent     bool             `json:"sent"`
	Accepted bool             `json:"accepted"`
	Synced   bool             `json:"synced"`
	Skipped  []skippedVehicle `json:"skipped"`
	Message  string           `json:"message"`
	Raw      string           `json:"raw,omitempty"`
	// ConfigSummary 下发后回读到的车端配置现状（诊断用）。
	//
	// ⚠️ v0.79.0：原来是 config_raw（实测 4KB，其中 3.5KB 是 CA 串）——
	//    现在压成摘要，跟 GET /fleet_telemetry 用**同一个形状**。
	ConfigSummary *telemetryConfigSummary `json:"config_summary,omitempty"`
}

// deployFleetTelemetry 下发遥测配置。
//
// ⚠️ **三态，绝不合并成一个「成功」**（v0.76.0 的教训）：
//
//	Sent     —— 请求发到了 proxy（HTTP 2xx）
//	Accepted —— Tesla 没把这台车列进 skipped_vehicles（车**接下了**配置）
//	Synced   —— 车已经采用（GET 回来 synced=true）
//
// 「发到了」≠「车接下了」≠「车已经在用了」。
// 三者分别报，App 和飞书才不用替用户瞎操心。
func deployFleetTelemetry(ctx context.Context, vin string) (*deployFleetTelemetryResult, error) {
	proxy := teslaCommandProxyURL()
	if proxy == "" {
		return nil, fmt.Errorf("未配置 TESLA_COMMAND_PROXY_URL —— 车控代理没起来")
	}
	if tesla == nil || !tesla.configured() {
		return nil, fmt.Errorf("未配置 Tesla Fleet API 凭据")
	}
	if err := tesla.ensureToken(ctx); err != nil {
		return nil, fmt.Errorf("拿不到 Tesla 令牌：%w", err)
	}
	claims, err := telemetryClaims()
	if err != nil {
		return nil, err
	}
	tesla.mu.RLock()
	token := tesla.accessToken
	tesla.mu.RUnlock()

	body, err := json.Marshal(map[string]any{
		"vins":   []string{vin},
		"config": claims,
	})
	if err != nil {
		return nil, err
	}

	// ⚠️ 路径是 `/api/1/vehicles/fleet_telemetry_config`（**没有 VIN**）——
	//    proxy 源码 pkg/proxy/proxy.go 里就是按这个路径长度匹配的，
	//    VIN 放在 body 的 `vins` 里。
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		proxy+"/api/1/vehicles/fleet_telemetry_config", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")

	client, err := teslaCommandProxyClient()
	if err != nil {
		return nil, err
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("调车控代理失败：%w", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)

	res := &deployFleetTelemetryResult{Raw: strings.TrimSpace(string(raw))}
	if resp.StatusCode != http.StatusOK {
		return res, fmt.Errorf("下发返回 %d：%s", resp.StatusCode, res.Raw)
	}
	res.Sent = true

	// 官方响应形如 {"response": {"vins": [...], "skipped_vehicles": [...]}}
	var parsed struct {
		Response struct {
			VINs    []string         `json:"vins"`
			Skipped []skippedVehicle `json:"skipped_vehicles"`
		} `json:"response"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil {
		// 解析不了就**不**宣布成功 —— 只说「发到了」。
		res.Message = "配置已发出，但响应格式读不懂，不能确认车端是否接下"
		return res, nil
	}
	res.Skipped = parsed.Response.Skipped
	for _, s := range res.Skipped {
		if s.VIN == vin {
			res.Message = "车端拒绝了这份配置：" + telemetrySkipReason(s.Reason)
			return res, nil
		}
	}
	res.Accepted = true
	res.Message = "配置已下发，车端已收下"

	// 立刻回读一次，看车有没有已经采用。
	//
	// ⚠️ 判据是「config 不再是 null」+「synced」——单看 synced 不算数
	//    （车端没有配置时 synced 也是 true）。
	if st, err := fetchFleetTelemetryConfig(ctx, vin); err == nil {
		res.Synced = st.HasConfig() && st.Response.Synced
		res.ConfigSummary = summarizeTelemetryConfig(st.Raw)
		if res.ConfigSummary != nil {
			res.ConfigSummary.CASourceCertificates = telemetryCASourceCount()
		}
		if st.Response.LimitReached {
			res.Message = "配置已下发，但这台车的遥测配置名额已满（最多 5 个应用）"
		} else if !res.Synced {
			res.Message = "配置已下发，车端会在下次连后端时采用（synced 还没变成 true）"
		}
	}
	return res, nil
}

// telemetrySkipReason 官方给出的「没配上」原因 → 人话。
//
// ⚠️ 只翻官方列出来的那几种（developer.tesla.cn → fleet_telemetry_config create
//
//	的原文：missing_key / unsupported_hardware / unsupported_firmware / max_configs）。
//	没收录的原样摊开 —— 前缀猜含义就是幻觉。
func telemetrySkipReason(reason string) string {
	switch strings.TrimSpace(reason) {
	case "missing_key":
		return "这把虚拟钥匙还没被加到车上（要在 Tesla App 里配对）"
	case "unsupported_hardware":
		return "这台车不支持遥测（2021 年前的 Model S/X 不支持）"
	case "unsupported_firmware":
		return "固件太老（走车控代理需要 2024.26 及以上）"
	case "max_configs":
		return "这台车已经配了 5 个遥测应用，名额满了"
	default:
		if strings.TrimSpace(reason) == "" {
			return "原因未说明"
		}
		return "车端拒绝（" + reason + "）"
	}
}

// --------------------------------------------------------------------------
// 对外接口
// --------------------------------------------------------------------------

// fleetTelemetryStatusResponse 状态接口的返回。
type fleetTelemetryStatusResponse struct {
	VIN                   string `json:"vin"`
	Configured            bool   `json:"configured"`
	Synced                bool   `json:"synced"`
	LimitReached          bool   `json:"limit_reached"`
	FleetTelemetryVersion string `json:"fleet_telemetry_version,omitempty"`
	FirmwareVersion       string `json:"firmware_version,omitempty"`
	ServerHost            string `json:"server_host"`
	ServerPort            int    `json:"server_port"`
	// ⚠️ CanDeploy / Reason：能不能下发、为什么不能。
	//    不给这两个字段，调用方只能靠猜。
	CanDeploy bool   `json:"can_deploy"`
	Reason    string `json:"reason,omitempty"`
	// Deployable 是**能不能下发**的硬条件齐不齐（供 UI 直接禁用按钮）。
	Deployable bool `json:"deployable"`
	// KeyPaired 虚拟钥匙有没有配到车上 —— 下发配置的前置条件。
	KeyPaired bool `json:"key_paired"`
	// TotalNumberOfKeys 车上的钥匙总数（车最多支持 20 把）。
	TotalNumberOfKeys int `json:"total_number_of_keys,omitempty"`
	// ConfigSummary 车端遥测配置的**结构化摘要**。
	//
	// ⚠️ v0.79.0：这里原来直接回吐原始响应（实测 4115 字节，其中 3523 字节
	//    是 CA 证书串）—— 诊断价值低、响应臃肿。改成摘要：主机/端口/到期/字段/证书段数。
	//
	// ⚠️ 「synced=true」单独看**说明不了**车用的是我们的配置 ——
	//    要看 Hostname 是不是我们的域名。
	ConfigSummary *telemetryConfigSummary `json:"config_summary,omitempty"`
	// FleetStatusRaw fleet_status 的原始响应（诊断用）。
	FleetStatusRaw string `json:"fleet_status_raw,omitempty"`
}

// telemetryConfigSummary 车端遥测配置的结构化摘要。
//
// ⚠️ 只放**看得懂、能判断对错**的字段：
// 证书原文、iss 之类对运维没用的长串一律不进摘要。
type telemetryConfigSummary struct {
	// Hostname 车端被指向的遥测服务器主机名。
	Hostname string `json:"hostname,omitempty"`
	// Port 车端要连的端口。
	Port int `json:"port,omitempty"`
	// ExpiresAt 配置的到期时间（Unix 秒）。
	ExpiresAt int64 `json:"expires_at,omitempty"`
	// DaysLeft 距离过期还有多少天。
	//
	// ⚠️ 运维要盯这个数：官方对 exp 有硬上限（服务端当下 + 365 天），
	//    到期前**必须重新下发**，否则车端遥测直接断。
	DaysLeft int `json:"days_left,omitempty"`
	// Fields 车端会推送哪些字段（排好序，便于对比）。
	Fields []string `json:"fields,omitempty"`
	// FieldIntervals 每个字段的推送间隔（秒）。
	FieldIntervals map[string]int `json:"field_intervals,omitempty"`
	// CACertificates 下发的 CA 串里有几段证书。
	//
	// ⚠️ 我们下发时**剥掉了叶子证书**（车端信任库里不能有它），
	//    所以这个数应当比 ca_source_certificates **少 1**。
	//    本机实测：server.crt 4 段（叶子 + YE2 + Root YE + ISRG X2）⇒ 下发 3 段。
	//
	// ⚠️ 两个数**相等** ⇒ 叶子没剥干净 ⇒ 车端会验不过我们的服务器证书。
	CACertificates int `json:"ca_certificates,omitempty"`
	// CASourceCertificates 证书文件里的总段数（含叶子）。
	CASourceCertificates int `json:"ca_source_certificates,omitempty"`
	// Audience 这份配置是发给谁的（官方固定值）。
	Audience string `json:"audience,omitempty"`
}

// telemetryCASourceCount 证书文件里的**总**段数（含叶子）。
//
// 运维拿它对照 ca_certificates：下发段数比它少 1 才说明叶子已剥掉。
func telemetryCASourceCount() int {
	path := strings.TrimSpace(getenv("TELEMETRY_CA_FILE", telemetryDefaultCAFile))
	raw, err := os.ReadFile(path)
	if err != nil {
		return 0
	}
	return strings.Count(string(raw), "-----BEGIN CERTIFICATE-----")
}

// telemetryConfigClaims 车端配置里的那几项（我们自己下发的形状）。
type telemetryConfigClaims struct {
	Hostname string `json:"hostname"`
	Port     int    `json:"port"`
	Exp      int64  `json:"exp"`
	CA       string `json:"ca"`
	Fields   map[string]struct {
		IntervalSeconds int `json:"interval_seconds"`
	} `json:"fields"`
	Audience json.RawMessage `json:"aud"`
}

// summarizeTelemetryConfig 把车端配置接口的原始响应压成结构化摘要。
//
// ⚠️ 解析不出来就返回 **nil** —— 宁可没有摘要，也不给一个半对的。
func summarizeTelemetryConfig(raw string) *telemetryConfigSummary {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil
	}
	var outer struct {
		Response struct {
			Config json.RawMessage `json:"config"`
		} `json:"response"`
	}
	if err := json.Unmarshal([]byte(raw), &outer); err != nil {
		return nil
	}
	cfgRaw := bytes.TrimSpace(outer.Response.Config)
	if len(cfgRaw) == 0 || string(cfgRaw) == "null" {
		return nil
	}
	// config 可能是对象，也可能是 JWS 字符串 —— 两种都试。
	var claims telemetryConfigClaims
	if err := json.Unmarshal(cfgRaw, &claims); err != nil {
		payload, ok := decodeJWTPayload(string(cfgRaw))
		if !ok {
			return nil
		}
		if err := json.Unmarshal(payload, &claims); err != nil {
			return nil
		}
	}
	if claims.Hostname == "" && claims.Port == 0 && len(claims.Fields) == 0 {
		return nil
	}
	out := &telemetryConfigSummary{
		Hostname:       claims.Hostname,
		Port:           claims.Port,
		ExpiresAt:      claims.Exp,
		Audience:       telemetryAudience(claims.Audience),
		CACertificates: strings.Count(claims.CA, "-----BEGIN CERTIFICATE-----"),
		FieldIntervals: map[string]int{},
	}
	for name, f := range claims.Fields {
		out.Fields = append(out.Fields, name)
		out.FieldIntervals[name] = f.IntervalSeconds
	}
	sort.Strings(out.Fields)
	if claims.Exp > 0 {
		out.DaysLeft = int((claims.Exp - time.Now().Unix()) / 86400)
	}
	return out
}

// decodeJWTPayload 取 JWS 的载荷段。
//
// ⚠️ **不验签** —— 这里只是把**我们自己下发的东西**读回来给人看，
// 不是安全边界（安全边界在车端验我们的服务器证书那一步）。
func decodeJWTPayload(jws string) ([]byte, bool) {
	parts := strings.Split(strings.TrimSpace(jws), ".")
	if len(parts) < 2 {
		return nil, false
	}
	b, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, false
	}
	return b, true
}

// telemetryAudience aud 可能是字符串，也可能是数组。
func telemetryAudience(raw json.RawMessage) string {
	if len(raw) == 0 {
		return ""
	}
	var s string
	if err := json.Unmarshal(raw, &s); err == nil {
		return s
	}
	var arr []string
	if err := json.Unmarshal(raw, &arr); err == nil && len(arr) > 0 {
		return arr[0]
	}
	return ""
}

// handleFleetTelemetryStatus GET /api/v1/cars/{id}/fleet_telemetry
//
// 看这台车能不能接遥测、现在接上了没。
func handleFleetTelemetryStatus(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "车 id 不合法")
		return
	}
	ctx := r.Context()
	vin, err := carVIN(ctx, id)
	if err != nil {
		writeErr(w, http.StatusNotFound, "查不到这台车")
		return
	}

	out := fleetTelemetryStatusResponse{
		VIN:        vin,
		ServerHost: strings.TrimSpace(getenv("TELEMETRY_HOST", telemetryDefaultHost)),
		ServerPort: telemetryDefaultPort,
		Deployable: tesla != nil && tesla.configured() && teslaCommandProxyURL() != "",
	}
	if v := strings.TrimSpace(getenv("TELEMETRY_PORT", "")); v != "" {
		if _, err := fmt.Sscanf(v, "%d", &out.ServerPort); err != nil {
			out.ServerPort = telemetryDefaultPort
		}
	}
	if !out.Deployable {
		out.Reason = "车控代理或 Tesla 凭据没就绪，下发不了"
		writeJSON(w, out)
		return
	}

	if st, err := fetchFleetStatus(ctx, vin); err == nil {
		out.FleetStatusRaw = st.Raw
		if info, ok := st.ForVIN(vin); ok {
			out.FleetTelemetryVersion = info.FleetTelemetryVersion
			out.FirmwareVersion = info.FirmwareVersion
			out.TotalNumberOfKeys = info.TotalNumberOfKeys
		}
		out.KeyPaired = st.KeyPaired(vin)
		if out.FleetTelemetryVersion == "" {
			out.Reason = "车端没报遥测客户端版本（固件 " + out.FirmwareVersion + "）"
		}
	} else {
		out.Reason = "查不到车端状态：" + err.Error()
	}
	if cfg, err := fetchFleetTelemetryConfig(ctx, vin); err == nil {
		// ⚠️ 「配没配」看 config 是不是 null —— **不看 synced**
		//    （没有配置时 synced 也是 true，那是默认值，不是「已生效」）。
		out.Configured = cfg.HasConfig()
		out.Synced = cfg.Response.Synced
		out.LimitReached = cfg.Response.LimitReached
		out.KeyPaired = out.KeyPaired || cfg.Response.KeyPaired
		out.ConfigSummary = summarizeTelemetryConfig(cfg.Raw)
		if out.ConfigSummary != nil {
			out.ConfigSummary.CASourceCertificates = telemetryCASourceCount()
		}
	} else {
		// 查不到 ≠ 没配 —— 官方对「没配过」的返回可能不是 200。
		// 这里明确区分，不能说成「没配」。
		log.Printf("ℹ️  查遥测配置现状失败：%v", err)
	}
	if out.Reason == "" && !out.KeyPaired {
		// 官方原文：没配钥匙的车会被列进 skipped_vehicles，原因是 missing_key。
		out.Reason = "虚拟钥匙还没配到车上（官方会回 missing_key）"
	}
	if out.Reason == "" {
		out.CanDeploy = true
	}
	writeJSON(w, out)
}

// handleFleetTelemetryDeploy POST /api/v1/cars/{id}/fleet_telemetry
//
// 下发遥测配置（走 command-proxy 签名）。
func handleFleetTelemetryDeploy(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "车 id 不合法")
		return
	}
	ctx := r.Context()
	vin, err := carVIN(ctx, id)
	if err != nil {
		writeErr(w, http.StatusNotFound, "查不到这台车")
		return
	}
	res, err := deployFleetTelemetry(ctx, vin)
	if err != nil {
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}
	writeJSON(w, res)
}
