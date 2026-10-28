package main

// ---------- Fleet Telemetry 数据落地（v0.79.0 3/3） ----------
//
// 为什么需要它：
//
//	`vehicle_data` 端点里**没有**故障码（BMS_* / DI_* / VCFRONT_* / CP_*）。
//	那套码只有一条路能拿到 —— Fleet Telemetry：车主动把数据推给我们。
//
// 数据通路：
//
//	Tesla 车 ──mTLS/WS──> fleet-telemetry ──MQTT──> mosquitto ──> 本文件 ──> Postgres
//
//	（fleet-telemetry 的 mqtt dispatcher 是官方支持的落地点；
//	  主题结构见 teslamotors/fleet-telemetry 的 datastore/mqtt/README.md）
//
// ⚠️ **反幻觉铁律**（本文件的第一原则）：
//
//	故障码的**含义**只来自已核验来源；查不到的一律
//	「按前缀解析子系统 + 提示进车机 Service Mode」，**绝不编原因**。
//	前缀本身就是模块名（结构事实），这是允许解析的；
//	「这个码意味着什么」是解释，解释必须能引用出处。

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"sort"
	"strings"
	"sync"
	"time"

	mqtt "github.com/eclipse/paho.mqtt.golang"
)

const (
	// telemetryDefaultTopicBase fleet-telemetry 侧配置的 mqtt.topic_base。
	//
	// ⚠️ 必须与 fleet-telemetry/config.json 里的 topic_base **完全一致** ——
	//    写错了就一条都收不到，而且不会报错（订阅成功、就是没消息）。
	telemetryDefaultTopicBase = "tesla_telemetry"

	// telemetryMQTTQoS 订阅 QoS。用 1（至少一次）——
	// 故障码这种「宁可多收一次也别漏」的数据不该用 0。
	telemetryMQTTQoS = 1
)

// --------------------------------------------------------------------------
// 故障码语义（唯一事实源）
// --------------------------------------------------------------------------

// telemetryAlertSubsystem 故障码前缀 → 所属硬件子系统。
//
// ⚠️ 只收录**前缀本身就是模块名**的那几类 —— 这是结构事实，不是猜测。
//
//	**不在这张表里的前缀一律显示「未收录的子系统」**，
//	绝不按字母硬凑一个中文名（那是幻觉，而且会误导车主）。
var telemetryAlertSubsystem = map[string]string{
	"BMS_":     "高压电池管理系统",
	"DI_":      "驱动系统（逆变器 / 电机）",
	"VCFRONT_": "前车身域控制器",
	"VCREAR_":  "后车身域控制器",
	"CP_":      "充电口模块",
}

// telemetryAlertMeaning 已核验的故障码 → 中文含义。
//
// ⚠️ **往这张表加条目的规矩**：必须能指出出处
//
//	（service.tesla.com 的对应条目，或项目章程 §3.2 明确写出的释义）。
//	查不到就别加 —— 未收录的码会走
//	「按前缀解析子系统 + 提示进车机 Service Mode」，
//	那比编一个像模像样的原因安全得多。
var telemetryAlertMeaning = map[string]string{
	"BMS_a035":     "高压电池绝缘故障",
	"BMS_u029":     "充电上限被锁定在 50%",
	"DI_a138":      "前电机被禁用",
	"VCFRONT_a182": "需要更换低压电池",
	"CP_a078":      "充电口锁扣结冰",
}

// telemetryServiceModeHint 未知故障码时给用户的**唯一**下一步指引。
//
// 这不是免责声明，是**正确做法**：Tesla 的维修手册只在车机 Service Mode 里
// （以及授权维修中心）能给到具体故障原因，App 和飞书都给不了。
const telemetryServiceModeHint = "具体原因请在车机 Service Mode 查看"

// alertCodePrefix 取故障码的前缀（到第一个下划线为止，含下划线）。
//
//	BMS_a035   → BMS_
//	VCFRONT_a182 → VCFRONT_
//	XYZ123     → XYZ123（没有下划线 ⇒ 整串就是前缀）
func alertCodePrefix(code string) string {
	code = strings.TrimSpace(code)
	if code == "" {
		return ""
	}
	if i := strings.Index(code, "_"); i >= 0 {
		return code[:i+1]
	}
	return code
}

// alertCodeSubsystem 按前缀解析子系统。查不到就返回空串（**不猜**）。
func alertCodeSubsystem(code string) string {
	return telemetryAlertSubsystem[alertCodePrefix(code)]
}

// alertCodeMeaning 已核验的含义。查不到就返回空串（**不猜**）。
func alertCodeMeaning(code string) string {
	return telemetryAlertMeaning[strings.TrimSpace(code)]
}

// alertCodeSummary 生成一句话摘要。
//
// ⚠️ 三档，缺的部分**明说缺**，不用模糊话术盖过去：
//
//	两个都有 → 「子系统 · 含义」
//	只有子系统 → 「子系统 · 含义未收录」
//	都没有 → 「未收录的子系统 · 含义未收录」
func alertCodeSummary(code string) string {
	sub := alertCodeSubsystem(code)
	mean := alertCodeMeaning(code)
	switch {
	case sub != "" && mean != "":
		return sub + " · " + mean
	case sub != "":
		return sub + " · 含义未收录"
	case mean != "":
		return mean
	default:
		return "未收录的子系统 · 含义未收录"
	}
}

// --------------------------------------------------------------------------
// 数据表
// --------------------------------------------------------------------------

// telemetryTables 建表语句。
//
// ⚠️ 幂等（IF NOT EXISTS），启动时执行；失败只告警不致命 ——
//
//	遥测落库失败不该拖垮主服务（车况、充电历史都跟它无关）。
var telemetryTables = []string{
	`CREATE TABLE IF NOT EXISTS tm_vehicle_alerts (
		id BIGSERIAL PRIMARY KEY,
		vin TEXT NOT NULL,
		alert_key TEXT NOT NULL,
		code TEXT NOT NULL,
		audiences TEXT NOT NULL DEFAULT '',
		started_at TIMESTAMPTZ,
		ended_at TIMESTAMPTZ,
		active BOOLEAN NOT NULL DEFAULT TRUE,
		first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
		last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
		UNIQUE (vin, alert_key)
	)`,
	`CREATE TABLE IF NOT EXISTS tm_telemetry_errors (
		id BIGSERIAL PRIMARY KEY,
		vin TEXT NOT NULL,
		error_key TEXT NOT NULL,
		name TEXT NOT NULL,
		body TEXT NOT NULL DEFAULT '',
		tags JSONB NOT NULL DEFAULT '{}'::jsonb,
		created_at TIMESTAMPTZ,
		first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
		last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
		UNIQUE (vin, error_key)
	)`,
	`CREATE TABLE IF NOT EXISTS tm_telemetry_connectivity (
		id BIGSERIAL PRIMARY KEY,
		vin TEXT NOT NULL,
		connection_id TEXT NOT NULL DEFAULT '',
		status TEXT NOT NULL,
		created_at TIMESTAMPTZ,
		first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
		UNIQUE (vin, connection_id, status, created_at)
	)`,
	`CREATE INDEX IF NOT EXISTS tm_vehicle_alerts_active_idx
		ON tm_vehicle_alerts (vin, active, started_at DESC)`,
}

// ensureTelemetryTables 建表。失败不致命（只告警）。
func ensureTelemetryTables(ctx context.Context) {
	for _, stmt := range telemetryTables {
		if _, err := db.Exec(ctx, stmt); err != nil {
			log.Printf("⚠️  建遥测表失败（不影响主服务）：%v", err)
			return
		}
	}
	log.Printf("✅ 遥测数据表就绪（tm_vehicle_alerts / tm_telemetry_errors / tm_telemetry_connectivity）")
}

// --------------------------------------------------------------------------
// MQTT 订阅
// --------------------------------------------------------------------------

// telemetryStreamState 遥测流的实时状态（内存）。
//
// 为什么要有它：App 需要区分
//
//	「遥测没接上」→ 不能说「没有故障码」
//	「遥测接上了但确实没有」→ 可以说「当前没有故障码」
//
// 这两件事差一个字就是**假安全**，必须分开。
type telemetryStreamState struct {
	mu            sync.RWMutex
	broker        bool // 与 mosquitto 是否连着
	lastMessageAt time.Time
	messages      int64
	alerts        int64
	errors        int64
	fields        int64  // V（车辆数据字段）条数 —— 只计数，本轮不落库
	lastSample    string // 最后一条 V 字段样例（证明管道通了）
}

var telemetryState telemetryStreamState

// telemetryStreamView 对外的遥测流状态。
type telemetryStreamView struct {
	Broker        bool      `json:"broker_connected"`
	LastMessageAt time.Time `json:"last_message_at,omitempty"`
	Messages      int64     `json:"messages"`
	Alerts        int64     `json:"alerts"`
	Errors        int64     `json:"errors"`
	Fields        int64     `json:"fields"`
	LastSample    string    `json:"last_sample,omitempty"`
}

func (s *telemetryStreamState) snapshot() telemetryStreamView {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return telemetryStreamView{
		Broker:        s.broker,
		LastMessageAt: s.lastMessageAt,
		Messages:      s.messages,
		Alerts:        s.alerts,
		Errors:        s.errors,
		Fields:        s.fields,
		LastSample:    s.lastSample,
	}
}

// telemetryTopics 要订阅的主题。
//
// ⚠️ 官方主题结构（datastore/mqtt/README.md）：
//
//	<base>/<VIN>/alerts/<name>/current   单个告警的最新状态
//	<base>/<VIN>/alerts/<name>/history   同一告警的全部历史
//	<base>/<VIN>/errors/<name>            遥测错误
//	<base>/<VIN>/connectivity             连接状态
//	<base>/<VIN>/v/<field>               车辆数据字段
//
// ⚠️ **告警码不在 payload 里，在 topic 里** —— payload 只有
//
//	StartedAt / EndedAt / Audiences。写错这一点 ⇒ 全部码都会变成空字符串。
func telemetryTopics(base string) map[string]string {
	return map[string]string{
		base + "/+/alerts/+/current": "alerts_current",
		base + "/+/alerts/+/history": "alerts_history",
		base + "/+/errors/+":         "errors",
		base + "/+/connectivity":     "connectivity",
		base + "/+/v/#":              "fields",
	}
}

// mqttAlert fleet-telemetry 的告警载荷。
//
// ⚠️ Audiences 可能是 null（车没给）—— 解析成 nil slice，不要报错。
type mqttAlert struct {
	StartedAt string   `json:"StartedAt"`
	EndedAt   string   `json:"EndedAt"`
	Audiences []string `json:"Audiences"`
}

// mqttError fleet-telemetry 的错误载荷。
type mqttError struct {
	Body      string            `json:"Body"`
	Tags      map[string]string `json:"Tags"`
	CreatedAt string            `json:"CreatedAt"`
}

// mqttConnectivity fleet-telemetry 的连接状态载荷。
type mqttConnectivity struct {
	ConnectionId string `json:"ConnectionId"`
	Status       string `json:"Status"`
	CreatedAt    string `json:"CreatedAt"`
}

// startTelemetryIngest 起一个后台 goroutine 订阅 MQTT。
//
// 没配 `TELEMETRY_MQTT_URL` 就**不开**（不是报错）——
// 遥测是可选增强，主服务不能因为它起不来。
func startTelemetryIngest(ctx context.Context) {
	broker := strings.TrimSpace(getenv("TELEMETRY_MQTT_URL", ""))
	if broker == "" {
		log.Printf("ℹ️  未配置 TELEMETRY_MQTT_URL：遥测数据落地未启用")
		return
	}
	base := strings.Trim(strings.TrimSpace(getenv("TELEMETRY_MQTT_TOPIC_BASE", telemetryDefaultTopicBase)), "/")
	topics := telemetryTopics(base)

	opts := mqtt.NewClientOptions().
		AddBroker(broker).
		SetClientID("teslamate-api-telemetry").
		SetCleanSession(true).
		SetAutoReconnect(true).
		SetConnectRetry(true).
		SetConnectRetryInterval(10 * time.Second).
		SetKeepAlive(30 * time.Second).
		SetConnectTimeout(15 * time.Second).
		SetOrderMatters(false).
		SetWriteTimeout(15 * time.Second)

	opts.SetOnConnectHandler(func(c mqtt.Client) {
		telemetryState.mu.Lock()
		telemetryState.broker = true
		telemetryState.mu.Unlock()
		log.Printf("✅ 已连上遥测 broker %s", broker)
		for topic := range topics {
			tok := c.Subscribe(topic, telemetryMQTTQoS, func(_ mqtt.Client, m mqtt.Message) {
				telemetryHandleMessage(base, m.Topic(), m.Payload())
			})
			if !tok.WaitTimeout(10 * time.Second) {
				log.Printf("⚠️  订阅超时：%s", topic)
				continue
			}
			if tok.Error() != nil {
				log.Printf("⚠️  订阅失败 %s：%v", topic, tok.Error())
			}
		}
		log.Printf("✅ 遥测订阅就绪（%d 个主题，前缀 %s）", len(topics), base)
	})

	opts.SetConnectionLostHandler(func(_ mqtt.Client, err error) {
		telemetryState.mu.Lock()
		telemetryState.broker = false
		telemetryState.mu.Unlock()
		log.Printf("⚠️  遥测 broker 断开：%v", err)
	})

	client := mqtt.NewClient(opts)
	go func() {
		<-ctx.Done()
		client.Disconnect(500)
	}()
	if tok := client.Connect(); tok.Wait() && tok.Error() != nil {
		log.Printf("⚠️  连遥测 broker 失败：%v（后台会重试）", tok.Error())
	}
}

// telemetryKind 遥测消息的种类。
type telemetryKind string

const (
	telemetryKindAlertsCurrent telemetryKind = "alerts_current"
	telemetryKindAlertsHistory telemetryKind = "alerts_history"
	telemetryKindErrors        telemetryKind = "errors"
	telemetryKindConnectivity  telemetryKind = "connectivity"
	telemetryKindFields        telemetryKind = "fields"
)

// telemetryClassify 把 MQTT 主题解析成 (种类, VIN, 名字)。
//
// ⚠️ **告警码在主题里，不在载荷里** —— fleet-telemetry 的 mqtt producer
//
//	是把 alert.Name 拼进 topic 的（datastore/mqtt/mqtt_payload.go）。
//	这里解析错 ⇒ 所有码都会变成空字符串，而界面上一片空白。
//
// ⚠️ 返回 ok=false 表示主题不认识 —— 宁可丢掉也不能乱猜。
func telemetryClassify(base, topic string) (telemetryKind, string, string, bool) {
	parts := strings.Split(topic, "/")
	if len(parts) > 0 && parts[0] == base {
		parts = parts[1:]
	}
	switch {
	case len(parts) == 4 && parts[1] == "alerts" && parts[3] == "current":
		return telemetryKindAlertsCurrent, parts[0], parts[2], true
	case len(parts) == 4 && parts[1] == "alerts" && parts[3] == "history":
		return telemetryKindAlertsHistory, parts[0], parts[2], true
	case len(parts) == 3 && parts[1] == "errors":
		return telemetryKindErrors, parts[0], parts[2], true
	case len(parts) == 2 && parts[1] == "connectivity":
		return telemetryKindConnectivity, parts[0], "", true
	case len(parts) >= 3 && parts[1] == "v":
		return telemetryKindFields, parts[0], strings.Join(parts[2:], "/"), true
	default:
		return "", "", "", false
	}
}

// telemetryHandleMessage 分发一条 MQTT 消息。
//
// ⚠️ 解析失败**只记日志不 panic** —— 一条脏消息不该把整条数据流带走。
func telemetryHandleMessage(base, topic string, payload []byte) {
	telemetryState.mu.Lock()
	telemetryState.messages++
	telemetryState.lastMessageAt = time.Now()
	telemetryState.mu.Unlock()

	kind, vin, name, ok := telemetryClassify(base, topic)
	if !ok {
		return
	}
	switch kind {
	case telemetryKindAlertsCurrent:
		var a mqttAlert
		if err := json.Unmarshal(payload, &a); err != nil {
			log.Printf("⚠️  告警载荷解析失败 %s：%v", topic, err)
			return
		}
		telemetryState.mu.Lock()
		telemetryState.alerts++
		telemetryState.mu.Unlock()
		telemetryStoreAlert(vin, name, a)
	case telemetryKindAlertsHistory:
		var arr []mqttAlert
		if err := json.Unmarshal(payload, &arr); err != nil {
			log.Printf("⚠️  告警历史载荷解析失败 %s：%v", topic, err)
			return
		}
		for _, a := range arr {
			telemetryStoreAlert(vin, name, a)
		}
	case telemetryKindErrors:
		var e mqttError
		if err := json.Unmarshal(payload, &e); err != nil {
			log.Printf("⚠️  错误载荷解析失败 %s：%v", topic, err)
			return
		}
		telemetryState.mu.Lock()
		telemetryState.errors++
		telemetryState.mu.Unlock()
		telemetryStoreError(vin, name, e)
	case telemetryKindConnectivity:
		var cv mqttConnectivity
		if err := json.Unmarshal(payload, &cv); err != nil {
			log.Printf("⚠️  连接状态载荷解析失败 %s：%v", topic, err)
			return
		}
		telemetryStoreConnectivity(vin, cv)
	case telemetryKindFields:
		telemetryState.mu.Lock()
		telemetryState.fields++
		if telemetryState.lastSample == "" {
			telemetryState.lastSample = fmt.Sprintf("%s/%s = %s", vin, name, string(payload))
		}
		telemetryState.mu.Unlock()
	}
}

// parseRFC3339 解析 RFC3339 时间。空串或格式不对 ⇒ 返回 nil（**不猜时间**）。
func parseRFC3339(s string) *time.Time {
	s = strings.TrimSpace(s)
	if s == "" {
		return nil
	}
	t, err := time.Parse(time.RFC3339, s)
	if err != nil {
		return nil
	}
	return &t
}

// telemetryStoreAlert 落一条告警。
//
// ⚠️ 用「码 + 起止时间」当唯一键（alert_key）：
//
//	同一条告警会被反复推送（current 每次都发）⇒ 必须幂等，
//	否则同一个故障会在库里堆出一串重复行。
func telemetryStoreAlert(vin, code string, a mqttAlert) {
	started := parseRFC3339(a.StartedAt)
	ended := parseRFC3339(a.EndedAt)
	key := code
	switch {
	case started != nil:
		key += "|" + started.UTC().Format(time.RFC3339)
	case ended != nil:
		key += "|" + ended.UTC().Format(time.RFC3339)
	default:
		key += "|unknown"
	}
	active := ended == nil
	_, err := db.Exec(context.Background(), `
INSERT INTO tm_vehicle_alerts (vin, alert_key, code, audiences, started_at, ended_at, active, last_seen_at)
VALUES ($1,$2,$3,$4,$5,$6,$7, now())
ON CONFLICT (vin, alert_key) DO UPDATE SET
	audiences = EXCLUDED.audiences,
	ended_at  = EXCLUDED.ended_at,
	active    = EXCLUDED.active,
	last_seen_at = now()`,
		vin, key, code, strings.Join(a.Audiences, ","), started, ended, active)
	if err != nil {
		log.Printf("⚠️  落告警失败 %s：%v", code, err)
	}
}

// telemetryStoreError 落一条遥测错误。
func telemetryStoreError(vin, name string, e mqttError) {
	created := parseRFC3339(e.CreatedAt)
	key := name
	if created != nil {
		key += "|" + created.UTC().Format(time.RFC3339)
	}
	tags, err := json.Marshal(e.Tags)
	if err != nil {
		tags = []byte("{}")
	}
	_, err = db.Exec(context.Background(), `
INSERT INTO tm_telemetry_errors (vin, error_key, name, body, tags, created_at, last_seen_at)
VALUES ($1,$2,$3,$4,$5,$6, now())
ON CONFLICT (vin, error_key) DO UPDATE SET
	body = EXCLUDED.body,
	tags = EXCLUDED.tags,
	last_seen_at = now()`,
		vin, key, name, e.Body, tags, created)
	if err != nil {
		log.Printf("⚠️  落遥测错误失败 %s：%v", name, err)
	}
}

// telemetryStoreConnectivity 落一条连接状态。
func telemetryStoreConnectivity(vin string, cv mqttConnectivity) {
	created := parseRFC3339(cv.CreatedAt)
	_, err := db.Exec(context.Background(), `
INSERT INTO tm_telemetry_connectivity (vin, connection_id, status, created_at)
VALUES ($1,$2,$3,$4)
ON CONFLICT (vin, connection_id, status, created_at) DO NOTHING`,
		vin, cv.ConnectionId, cv.Status, created)
	if err != nil {
		log.Printf("⚠️  落连接状态失败：%v", err)
	}
}

// --------------------------------------------------------------------------
// 对外接口
// --------------------------------------------------------------------------

// telemetryAlertView 一条告警（给 App / 飞书看）。
type telemetryAlertView struct {
	Code       string     `json:"code"`
	Subsystem  string     `json:"subsystem"`
	Meaning    string     `json:"meaning"`
	Summary    string     `json:"summary"`
	Audiences  []string   `json:"audiences"`
	StartedAt  *time.Time `json:"started_at"`
	EndedAt    *time.Time `json:"ended_at"`
	Active     bool       `json:"active"`
	LastSeenAt time.Time  `json:"last_seen_at"`
}

// telemetryErrorView 一条遥测错误。
type telemetryErrorView struct {
	Name      string            `json:"name"`
	Body      string            `json:"body"`
	Tags      map[string]string `json:"tags"`
	CreatedAt *time.Time        `json:"created_at"`
}

// telemetryAlertsResponse 故障码接口的返回。
type telemetryAlertsResponse struct {
	// ⚠️ Stream 一定要给 —— 它是「有没有接上遥测」的唯一凭据。
	//    没它，App 就无法区分「没有故障码」和「根本没在收数据」。
	Stream       telemetryStreamView  `json:"stream"`
	Alerts       []telemetryAlertView `json:"alerts"`
	Errors       []telemetryErrorView `json:"errors"`
	Connectivity string               `json:"connectivity,omitempty"`
	// ⚠️ EverConnected：遥测流**曾经**接上过吗。
	//
	// 没有它，App 就无法区分「这车没有故障码」与「我们压根没在收数据」——
	// 后者如果被渲染成「一切正常」，那是**假安全**。
	EverConnected bool `json:"ever_connected"`
	// Hint 未知码时给用户的下一步指引。
	Hint string `json:"hint,omitempty"`
	// UpdatedAt 库里最后一条遥测数据的时间。
	UpdatedAt *time.Time `json:"updated_at,omitempty"`
}

// carVIN 取这台车的 VIN（17 位）。
func carVIN(ctx context.Context, id int) (string, error) {
	var vin string
	err := db.QueryRow(ctx, `SELECT vin FROM cars WHERE id = $1`, id).Scan(&vin)
	if err != nil {
		return "", err
	}
	if strings.TrimSpace(vin) == "" {
		return "", fmt.Errorf("这台车没有记录 VIN")
	}
	return vin, nil
}

// handleTelemetryAlerts GET /api/v1/cars/{id}/alerts
//
// 给 App 车况页与 Hermes（飞书）看的**故障码**入口。
func handleTelemetryAlerts(w http.ResponseWriter, r *http.Request) {
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
	out, err := loadTelemetryAlerts(ctx, vin)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, out)
}

// loadTelemetryAlerts 查这台车**当前**的遥测现状（故障码 / 错误 / 连接状态）。
//
// ⚠️ 抽成函数是因为**两个地方**要用它：
//
//   - GET /api/v1/cars/{id}/alerts（MCP 与诊断用）
//   - GET /api/v1/cars/{id}/status 里的 telemetry 块（App 车况页用）
//
// 两边必须给**同一份**事实 —— 两处各查一次、各写一套口径，
// 就会出现「告警页说有 3 条、车况页说有 1 条」这种对不上的局面。
func loadTelemetryAlerts(ctx context.Context, vin string) (*telemetryAlertsResponse, error) {
	out := &telemetryAlertsResponse{
		Stream: telemetryState.snapshot(),
		Alerts: []telemetryAlertView{},
		Errors: []telemetryErrorView{},
	}

	rows, err := db.Query(ctx, `
SELECT code, audiences, started_at, ended_at, active, last_seen_at
FROM tm_vehicle_alerts
WHERE vin = $1
ORDER BY active DESC, started_at DESC NULLS LAST, last_seen_at DESC
LIMIT 100`, vin)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var v telemetryAlertView
		var audiences string
		if err := rows.Scan(&v.Code, &audiences, &v.StartedAt, &v.EndedAt, &v.Active, &v.LastSeenAt); err != nil {
			continue
		}
		if audiences != "" {
			v.Audiences = strings.Split(audiences, ",")
		} else {
			v.Audiences = []string{}
		}
		v.Subsystem = alertCodeSubsystem(v.Code)
		v.Meaning = alertCodeMeaning(v.Code)
		v.Summary = alertCodeSummary(v.Code)
		out.Alerts = append(out.Alerts, v)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	// ⚠️ 遇到**不认识**的码才给指引 —— 认识的码直接说含义，
	//    再补一句「去车机看」是废话。
	out.Hint = telemetryHintFor(out.Alerts)

	erows, err := db.Query(ctx, `
SELECT name, body, tags, created_at
FROM tm_telemetry_errors
WHERE vin = $1
ORDER BY created_at DESC NULLS LAST, last_seen_at DESC
LIMIT 50`, vin)
	if err != nil {
		return nil, err
	}
	for erows.Next() {
		var v telemetryErrorView
		var tagsRaw []byte
		if err := erows.Scan(&v.Name, &v.Body, &tagsRaw, &v.CreatedAt); err != nil {
			continue
		}
		v.Tags = map[string]string{}
		if len(tagsRaw) > 0 {
			_ = json.Unmarshal(tagsRaw, &v.Tags)
		}
		out.Errors = append(out.Errors, v)
	}
	erows.Close()
	if err := erows.Err(); err != nil {
		return nil, err
	}

	// 最近一次的连接状态（车端→我们的接收端）。
	var conn string
	var connAt *time.Time
	if err := db.QueryRow(ctx, `
SELECT status, created_at FROM tm_telemetry_connectivity
WHERE vin = $1 ORDER BY created_at DESC NULLS LAST LIMIT 1`, vin).Scan(&conn, &connAt); err == nil {
		out.Connectivity = conn
	}
	// ⚠️ **EverConnected**：我们**曾经**接到过这台车的遥测流吗？
	//
	// 这是「没有故障码」和「根本没在收数据」的分界线：
	//   EverConnected=false ⇒ 遥测**从未**接上 ⇒ 不能说「这车没故障码」
	//   EverConnected=true  ⇒ 遥测接上了，只是这一刻没有告警
	//
	// ⚠️ 用库里的历史记录判，**不用**进程内的计数器 ——
	//    进程重启后计数器归零，会把「接通过」误判成「没接过」。
	if err := db.QueryRow(ctx, `
SELECT EXISTS(
  SELECT 1 FROM tm_telemetry_connectivity WHERE vin = $1 AND status = 'CONNECTED'
)`, vin).Scan(&out.EverConnected); err != nil {
		return nil, err
	}

	var lastSeen *time.Time
	if err := db.QueryRow(ctx, `
SELECT GREATEST(
  COALESCE((SELECT max(last_seen_at) FROM tm_vehicle_alerts WHERE vin = $1), '-infinity'::timestamptz),
  COALESCE((SELECT max(last_seen_at) FROM tm_telemetry_errors WHERE vin = $1), '-infinity'::timestamptz)
)`, vin).Scan(&lastSeen); err == nil && lastSeen != nil {
		out.UpdatedAt = lastSeen
	}

	return out, nil
}

// --------------------------------------------------------------------------
// 内部工具
// --------------------------------------------------------------------------

// telemetryHintFor 有**不认识**的码才给下一步指引。
//
// ⚠️ 认识的码直接说含义就够了 —— 再补一句「去车机看」是废话。
//
//	全部认识 ⇒ 空串（App 不显示任何指引）。
func telemetryHintFor(alerts []telemetryAlertView) string {
	for _, a := range alerts {
		if a.Meaning == "" {
			return telemetryServiceModeHint
		}
	}
	return ""
}

// telemetryAlertsSummary 把故障码压成一句话（推送通道 / MCP 用）。
//
// ⚠️ 只报**事实**：有几个、还在持续吗。不解释原因（原因在详情里查）。
func telemetryAlertsSummary(alerts []telemetryAlertView) string {
	if len(alerts) == 0 {
		return ""
	}
	active := 0
	codes := make([]string, 0, len(alerts))
	for _, a := range alerts {
		if a.Active {
			active++
		}
		codes = append(codes, a.Code)
	}
	sort.Strings(codes)
	if active == 0 {
		return fmt.Sprintf("遥测故障码 %d 条（均已结束）：%s", len(alerts), strings.Join(codes, "、"))
	}
	return fmt.Sprintf("遥测故障码 %d 条（%d 条仍在持续）：%s", len(alerts), active, strings.Join(codes, "、"))
}

// telemetryMQTTConfigured 遥测落地是否配好了（给健康检查看）。
func telemetryMQTTConfigured() bool {
	return strings.TrimSpace(getenv("TELEMETRY_MQTT_URL", "")) != ""
}

// envDump 打印遥测相关配置（**不含任何密钥**）。
func telemetryEnvDump() string {
	return fmt.Sprintf("broker=%s topic_base=%s",
		os.Getenv("TELEMETRY_MQTT_URL"),
		getenv("TELEMETRY_MQTT_TOPIC_BASE", telemetryDefaultTopicBase))
}
