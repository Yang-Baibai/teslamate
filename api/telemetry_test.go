package main

// ---------- Fleet Telemetry 的单测 ----------
//
// ⚠️ 这些用例守的是三件最容易出错、又最容易被忽略的事：
//
//  1. **故障码在主题里，不在载荷里** —— 解析错 ⇒ 所有码变空字符串，界面一片空白
//  2. **反幻觉**：未知码只能按前缀报子系统，绝不能编一个含义
//  3. **三态不合并**：「发到了」≠「车接下了」≠「车已经在用」

import (
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// --------------------------------------------------------------------------
// 故障码前缀与子系统
// --------------------------------------------------------------------------

func TestAlertCodePrefix(t *testing.T) {
	cases := map[string]string{
		"BMS_a035":     "BMS_",
		"DI_a138":      "DI_",
		"VCFRONT_a182": "VCFRONT_",
		"CP_a078":      "CP_",
		"BMS_u029":     "BMS_",
		"NOUNDERSCORE": "NOUNDERSCORE",
		"":             "",
	}
	for code, want := range cases {
		if got := alertCodePrefix(code); got != want {
			t.Errorf("alertCodePrefix(%q) = %q，期望 %q", code, got, want)
		}
	}
}

// TestAlertCodeSubsystemOnlyKnownPrefixes 未知前缀必须返回空串。
//
// ⚠️ 这是反幻觉铁律的可执行版本：返回空 ⇒ 界面上显示「未收录的子系统」。
//
//	如果这里返回了猜测出来的中文名，就是幻觉。
func TestAlertCodeSubsystemOnlyKnownPrefixes(t *testing.T) {
	cases := map[string]string{
		"BMS_a035":     "高压电池管理系统",
		"DI_a138":      "驱动系统（逆变器 / 电机）",
		"VCFRONT_a182": "前车身域控制器",
		"VCREAR_x":     "后车身域控制器",
		"CP_a078":      "充电口模块",
		// 未收录的前缀 —— 必须空
		"XYZ_999":  "",
		"GADGET_1": "",
		"":         "",
	}
	for code, want := range cases {
		if got := alertCodeSubsystem(code); got != want {
			t.Errorf("alertCodeSubsystem(%q) = %q，期望 %q", code, got, want)
		}
	}
}

// TestAlertCodeMeaningOnlyVerified 未核验的码没有含义。
func TestAlertCodeMeaningOnlyVerified(t *testing.T) {
	if got := alertCodeMeaning("BMS_a035"); got == "" {
		t.Error("BMS_a035 应当有已核验的含义")
	}
	if got := alertCodeMeaning("XYZ_999"); got != "" {
		t.Errorf("未收录的码不该有含义，实际 %q", got)
	}
	if got := alertCodeMeaning("BMS_a999"); got != "" {
		t.Errorf("同前缀但未收录的码不该有含义，实际 %q", got)
	}
}

// TestAlertCodeSummary 三档摘要，缺的部分**明说缺**。
func TestAlertCodeSummary(t *testing.T) {
	cases := []struct {
		code string
		want string
	}{
		{"BMS_a035", "高压电池管理系统 · 高压电池绝缘故障"},
		{"BMS_u029", "高压电池管理系统 · 充电上限被锁定在 50%"},
		// 前缀收录但具体码没收录
		{"BMS_a999", "高压电池管理系统 · 含义未收录"},
		// 前缀也没收录
		{"XYZ_999", "未收录的子系统 · 含义未收录"},
	}
	for _, c := range cases {
		if got := alertCodeSummary(c.code); got != c.want {
			t.Errorf("alertCodeSummary(%q) = %q，期望 %q", c.code, got, c.want)
		}
	}
}

// TestAlertCodeSummaryNeverInventsMeaning 摘要里绝不能出现编出来的含义。
//
// ⚠️ 这条是**反幻觉的可执行断言**：未知码的摘要里
//
//	不得出现任何「故障」「失效」这类断言性词汇，除非它来自已核验表。
func TestAlertCodeSummaryNeverInventsMeaning(t *testing.T) {
	bad := []string{"故障", "失效", "损坏", "危险", "已坏"}
	for _, code := range []string{"XYZ_999", "GADGET_1", "BMS_a999"} {
		s := alertCodeSummary(code)
		for _, w := range bad {
			if strings.Contains(s, w) {
				t.Errorf("未知码 %q 的摘要 %q 里出现了断言性词汇 %q —— 那是幻觉", code, s, w)
			}
		}
	}
}

// --------------------------------------------------------------------------
// MQTT 主题解析
// --------------------------------------------------------------------------

// TestTelemetryClassify 主题 → (种类, VIN, 名字)。
//
// ⚠️ 特别测「告警码在主题里」这条：名字必须来自 topic 的第 4 段。
func TestTelemetryClassify(t *testing.T) {
	base := "tesla_telemetry"
	vin := "<VIN>"
	cases := []struct {
		topic string
		kind  telemetryKind
		name  string
	}{
		{base + "/" + vin + "/alerts/BMS_a035/current", telemetryKindAlertsCurrent, "BMS_a035"},
		{base + "/" + vin + "/alerts/BMS_a035/history", telemetryKindAlertsHistory, "BMS_a035"},
		{base + "/" + vin + "/errors/some_error", telemetryKindErrors, "some_error"},
		{base + "/" + vin + "/connectivity", telemetryKindConnectivity, ""},
		{base + "/" + vin + "/v/Soc", telemetryKindFields, "Soc"},
	}
	for _, c := range cases {
		kind, gotVIN, name, ok := telemetryClassify(base, c.topic)
		if !ok {
			t.Fatalf("主题 %q 认不出来", c.topic)
		}
		if kind != c.kind {
			t.Errorf("主题 %q 的种类 = %q，期望 %q", c.topic, kind, c.kind)
		}
		if gotVIN != vin {
			t.Errorf("主题 %q 的 VIN = %q，期望 %q", c.topic, gotVIN, vin)
		}
		if name != c.name {
			t.Errorf("主题 %q 的名字 = %q，期望 %q", c.topic, name, c.name)
		}
	}
}

// TestTelemetryClassifyRejectsUnknown 主题不认识就丢掉，不能乱猜。
func TestTelemetryClassifyRejectsUnknown(t *testing.T) {
	base := "tesla_telemetry"
	for _, topic := range []string{
		base + "/abc",
		base + "/abc/alerts",
		base + "/abc/alerts/X/Y/Z",
		"other_base/abc/alerts/X/current",
		"",
	} {
		if _, _, _, ok := telemetryClassify(base, topic); ok {
			t.Errorf("主题 %q 不该被认出来", topic)
		}
	}
}

// --------------------------------------------------------------------------
// MQTT 载荷
// --------------------------------------------------------------------------

// TestMqttAlertNullAudiences Audiences 可能是 null，不能因此解析失败。
func TestMqttAlertNullAudiences(t *testing.T) {
	var a mqttAlert
	if err := json.Unmarshal([]byte(`{"StartedAt":"2026-10-28T12:00:00Z","Audiences":null}`), &a); err != nil {
		t.Fatalf("解析失败：%v", err)
	}
	if a.StartedAt != "2026-10-28T12:00:00Z" {
		t.Errorf("StartedAt = %q", a.StartedAt)
	}
	if a.Audiences != nil {
		t.Errorf("null 的 Audiences 应解析成 nil，实际 %v", a.Audiences)
	}
}

// TestMqttAlertRealPayload 按官方 producer 的真实载荷形状测。
func TestMqttAlertRealPayload(t *testing.T) {
	var a mqttAlert
	body := `{"StartedAt":"2026-10-28T12:00:00Z","EndedAt":"2026-10-28T13:00:00Z","Audiences":["Customer","Service"]}`
	if err := json.Unmarshal([]byte(body), &a); err != nil {
		t.Fatalf("解析失败：%v", err)
	}
	if len(a.Audiences) != 2 || a.Audiences[0] != "Customer" {
		t.Errorf("Audiences = %v", a.Audiences)
	}
	if a.EndedAt == "" {
		t.Error("EndedAt 丢了")
	}
}

func TestMqttErrorPayload(t *testing.T) {
	var e mqttError
	body := `{"Body":"something went wrong","Tags":{"k":"v"},"CreatedAt":"2026-10-28T12:00:00Z"}`
	if err := json.Unmarshal([]byte(body), &e); err != nil {
		t.Fatalf("解析失败：%v", err)
	}
	if e.Body != "something went wrong" {
		t.Errorf("Body = %q", e.Body)
	}
	if e.Tags["k"] != "v" {
		t.Errorf("Tags = %v", e.Tags)
	}
}

func TestMqttConnectivityPayload(t *testing.T) {
	var cv mqttConnectivity
	body := `{"ConnectionId":"abc","Status":"CONNECTED","CreatedAt":"2026-10-28T12:00:00Z"}`
	if err := json.Unmarshal([]byte(body), &cv); err != nil {
		t.Fatalf("解析失败：%v", err)
	}
	if cv.Status != "CONNECTED" || cv.ConnectionId != "abc" {
		t.Errorf("解析出来 = %+v", cv)
	}
}

// TestParseRFC3339 不对的时间不许猜。
func TestParseRFC3339(t *testing.T) {
	if parseRFC3339("2026-10-28T12:00:00Z") == nil {
		t.Error("合法时间应解析成功")
	}
	for _, bad := range []string{"", "   ", "2026-10-28", "not a time"} {
		if parseRFC3339(bad) != nil {
			t.Errorf("%q 不该解析出时间", bad)
		}
	}
}

// --------------------------------------------------------------------------
// CA 证书串
// --------------------------------------------------------------------------

// TestTelemetryCABundleSkipsLeaf CA 串里**不能**有叶子证书。
//
// ⚠️ 叶子是服务器自己握手时给的；塞进信任库属于语义错误，
//
//	而且有些 TLS 实现会因此选错信任路径。
func TestTelemetryCABundleSkipsLeaf(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "server.crt")
	// 两个假 PEM 段（内容不是真证书也没关系 —— 这里只数段落）
	fake := "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n"
	if err := os.WriteFile(p, []byte(fake+fake), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("TELEMETRY_CA_FILE", p)
	bundle, err := telemetryCABundle()
	if err != nil {
		t.Fatalf("telemetryCABundle: %v", err)
	}
	if n := strings.Count(bundle, "BEGIN CERTIFICATE"); n != 1 {
		t.Errorf("CA 串里应有 1 段证书，实际 %d 段", n)
	}
}

// TestTelemetryCABundleNeedsAtLeastTwo 只有一个证书 ⇒ 没有 CA ⇒ 必须报错。
func TestTelemetryCABundleNeedsAtLeastTwo(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "server.crt")
	fake := "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n"
	if err := os.WriteFile(p, []byte(fake), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("TELEMETRY_CA_FILE", p)
	if _, err := telemetryCABundle(); err == nil {
		t.Error("只有一个证书时必须报错（没有 CA 段）")
	}
}

// --------------------------------------------------------------------------
// 下发配置
// --------------------------------------------------------------------------

// TestTelemetryClaimsShape 配置载荷的形状。
//
// ⚠️ `aud` / `iss` **不能**自己填 —— command-proxy 会覆写它们
//
//	（proxy 源码里明确 log 警告 "will be overwritten"）。
func TestTelemetryClaimsShape(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "server.crt")
	fake := "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n"
	if err := os.WriteFile(p, []byte(fake+fake), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("TELEMETRY_CA_FILE", p)
	t.Setenv("TELEMETRY_HOST", "example.ts.net")
	t.Setenv("TELEMETRY_PORT", "8443")

	claims, err := telemetryClaims()
	if err != nil {
		t.Fatalf("telemetryClaims: %v", err)
	}
	if claims["hostname"] != "example.ts.net" {
		t.Errorf("hostname = %v", claims["hostname"])
	}
	if claims["port"] != 8443 {
		t.Errorf("port = %v（类型/值不对）", claims["port"])
	}
	if _, ok := claims["ca"].(string); !ok {
		t.Error("ca 必须是字符串")
	}
	if _, ok := claims["fields"].(map[string]any); !ok {
		t.Error("fields 必须是对象")
	}
	if _, ok := claims["exp"]; !ok {
		t.Error("exp 必须有（配置有有效期，到期要重签）")
	}
	if _, ok := claims["aud"]; ok {
		t.Error("aud 不能自己填 —— proxy 会覆写")
	}
	if _, ok := claims["iss"]; ok {
		t.Error("iss 不能自己填 —— proxy 会覆写")
	}
}

// TestTelemetryClaimsRejectBadPort 端口写错必须报错，不能默默用错的端口。
func TestTelemetryClaimsRejectBadPort(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "server.crt")
	fake := "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n"
	if err := os.WriteFile(p, []byte(fake+fake), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("TELEMETRY_CA_FILE", p)
	t.Setenv("TELEMETRY_PORT", "not-a-number")
	if _, err := telemetryClaims(); err == nil {
		t.Error("端口不是数字时必须报错")
	}
}

// TestTelemetrySkipReason 官方列出的原因要翻，未收录的原样摊开。
func TestTelemetrySkipReason(t *testing.T) {
	if s := telemetrySkipReason("missing_key"); !strings.Contains(s, "钥匙") {
		t.Errorf("missing_key 的说明里该提到钥匙，实际 %q", s)
	}
	if s := telemetrySkipReason("max_configs"); !strings.Contains(s, "5") {
		t.Errorf("max_configs 的说明里该提到 5，实际 %q", s)
	}
	// 未收录的 —— 原样摊开，绝不编
	s := telemetrySkipReason("something_new")
	if !strings.Contains(s, "something_new") {
		t.Errorf("未收录的原因必须保留原码，实际 %q", s)
	}
	if s := telemetrySkipReason(""); s == "" {
		t.Error("空原因也要有话可说")
	}
}

// --------------------------------------------------------------------------
// 摘要
// --------------------------------------------------------------------------

// TestTelemetryAlertsSummary 只报事实，不解释原因。
func TestTelemetryAlertsSummary(t *testing.T) {
	if s := telemetryAlertsSummary(nil); s != "" {
		t.Errorf("没有故障码时应为空，实际 %q", s)
	}
	alerts := []telemetryAlertView{
		{Code: "BMS_a035", Active: true},
		{Code: "CP_a078", Active: false},
	}
	s := telemetryAlertsSummary(alerts)
	if !strings.Contains(s, "2") || !strings.Contains(s, "BMS_a035") || !strings.Contains(s, "CP_a078") {
		t.Errorf("摘要应含条数与两个码，实际 %q", s)
	}
	if !strings.Contains(s, "1") {
		t.Errorf("摘要应说明有 1 条仍在持续，实际 %q", s)
	}
}

// --------------------------------------------------------------------------
// v0.79.0 3/3：配置摘要 + 遥测块契约
// --------------------------------------------------------------------------

// TestSummarizeTelemetryConfig 真实车端配置能被压成摘要。
//
// ⚠️ 这份样本是**实测原文**的形状（config 是对象、ca 是多段 PEM 串）。
func TestSummarizeTelemetryConfig(t *testing.T) {
	raw := `{"response":{"synced":true,"config":{"hostname":"<主机名>.<tailnet域>.ts.net",` +
		`"ca":"-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n` +
		`-----BEGIN CERTIFICATE-----\nBBBB\n-----END CERTIFICATE-----\n",` +
		`"exp":1824308392,"port":8443,` +
		`"fields":{"Soc":{"interval_seconds":60},"ChargeAmps":{"interval_seconds":5}},` +
		`"iss":"abc","aud":"com.tesla.fleet.TelemetryClient"},"limit_reached":false,"key_paired":true}}`

	s := summarizeTelemetryConfig(raw)
	if s == nil {
		t.Fatal("实测形状的 config 必须能解析出摘要")
	}
	if s.Hostname != "<主机名>.<tailnet域>.ts.net" {
		t.Errorf("hostname 解析错了，实际 %q", s.Hostname)
	}
	if s.Port != 8443 {
		t.Errorf("port 解析错了，实际 %d", s.Port)
	}
	if s.ExpiresAt != 1824308392 {
		t.Errorf("exp 解析错了，实际 %d", s.ExpiresAt)
	}
	if s.Audience != "com.tesla.fleet.TelemetryClient" {
		t.Errorf("aud 解析错了，实际 %q", s.Audience)
	}
	// ⚠️ 证书段数是**运维要盯的数** —— 我们下发时剥掉叶子，
	//    所以车端存的应该是 2 段（中间 + 根）。
	if s.CACertificates != 2 {
		t.Errorf("CA 证书段数应为 2，实际 %d", s.CACertificates)
	}
	if len(s.Fields) != 2 || s.Fields[0] != "ChargeAmps" || s.Fields[1] != "Soc" {
		t.Errorf("字段名应排好序，实际 %v", s.Fields)
	}
	if s.FieldIntervals["ChargeAmps"] != 5 || s.FieldIntervals["Soc"] != 60 {
		t.Errorf("字段间隔解析错了，实际 %v", s.FieldIntervals)
	}
	if s.DaysLeft <= 0 {
		t.Errorf("days_left 应为正数（还没到期），实际 %d", s.DaysLeft)
	}
}

// TestSummarizeTelemetryConfigNull 没有配置就是没有摘要。
//
// ⚠️ 「synced=true 但 config 是 null」是**实测会发生的**（默认值），
//
//	不能把它当成「车端有配置」。
func TestSummarizeTelemetryConfigNull(t *testing.T) {
	raw := `{"response":{"synced":true,"config":null,"limit_reached":false,"key_paired":true}}`
	if s := summarizeTelemetryConfig(raw); s != nil {
		t.Errorf("config 为 null 时不应有摘要，实际 %+v", s)
	}
}

// TestSummarizeTelemetryConfigJWS config 是 JWS 字符串时也要能读出来。
func TestSummarizeTelemetryConfigJWS(t *testing.T) {
	// payload = {"hostname":"h.example","port":8443,"exp":1824308392,"fields":{}}
	payload := `{"hostname":"h.example","port":8443,"exp":1824308392,"fields":{"Soc":{"interval_seconds":60}}}`
	enc := base64.RawURLEncoding.EncodeToString([]byte(payload))
	jws := "eyJhbGciOiJFUzI1NiJ9." + enc + ".sig"
	raw := `{"response":{"synced":true,"config":"` + jws + `","limit_reached":false,"key_paired":true}}`

	s := summarizeTelemetryConfig(raw)
	if s == nil {
		t.Fatal("JWS 形状的 config 也必须能解析出摘要")
	}
	if s.Hostname != "h.example" || s.Port != 8443 {
		t.Errorf("JWS 载荷解析错了，实际 %+v", s)
	}
}

// TestSummarizeTelemetryConfigGarbage 解析不出来就返回 nil（宁缺毋滥）。
func TestSummarizeTelemetryConfigGarbage(t *testing.T) {
	for _, raw := range []string{"", "not json", `{"response":{}}`, `{"response":{"config":"not-a-jws"}}`} {
		if s := summarizeTelemetryConfig(raw); s != nil {
			t.Errorf("输入 %q 时不该有摘要，实际 %+v", raw, s)
		}
	}
}

// TestTelemetryAudience aud 可能是字符串，也可能是数组。
func TestTelemetryAudience(t *testing.T) {
	if s := telemetryAudience(json.RawMessage(`"a.b.c"`)); s != "a.b.c" {
		t.Errorf("字符串 aud 解析错了，实际 %q", s)
	}
	if s := telemetryAudience(json.RawMessage(`["x.y","z.w"]`)); s != "x.y" {
		t.Errorf("数组 aud 应取第一个，实际 %q", s)
	}
	if s := telemetryAudience(nil); s != "" {
		t.Errorf("空 aud 应为空串，实际 %q", s)
	}
}

// TestTelemetryHintOnlyForUnknownCodes 只有**不认识**的码才给指引。
func TestTelemetryHintOnlyForUnknownCodes(t *testing.T) {
	known := []telemetryAlertView{{Code: "BMS_a035", Meaning: "已收录的含义"}}
	if h := telemetryHintFor(known); h != "" {
		t.Errorf("认识的码不该给指引，实际 %q", h)
	}
	unknown := []telemetryAlertView{{Code: "ZZZ_x999"}}
	h := telemetryHintFor(unknown)
	if h != telemetryServiceModeHint {
		t.Errorf("不认识的码应给指引，实际 %q", h)
	}
	if h := telemetryHintFor(nil); h != "" {
		t.Errorf("没有码时不该有指引，实际 %q", h)
	}
}

// TestCarStatusAlwaysHasTelemetryKey ⚠️ 「遥测」这个键必须**永远**在。
//
// 少一次 ⇒ App 少一次区分「没有故障码」与「没在收数据」的机会
// ⇒ 可能把「根本没接上」渲染成「一切正常」= **假安全**。
func TestCarStatusAlwaysHasTelemetryKey(t *testing.T) {
	b, err := json.Marshal(CarStatus{})
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]any
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatal(err)
	}
	if _, ok := m["telemetry"]; !ok {
		t.Fatalf("/status 的响应里必须有 telemetry 键（哪怕是 null），实际 %s", string(b))
	}
}

// TestTelemetryStatusJSONShape 遥测块的字段名要对得上 App 的解析。
func TestTelemetryStatusJSONShape(t *testing.T) {
	block := &TelemetryStatus{
		Connected:     true,
		EverConnected: true,
		ActiveAlerts:  2,
		TotalAlerts:   5,
		Alerts:        []telemetryAlertView{},
		Stream:        telemetryStreamView{Broker: true, Messages: 42},
	}
	b, err := json.Marshal(block)
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]any
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatal(err)
	}
	for _, k := range []string{"connected", "ever_connected", "active_alerts",
		"total_alerts", "alerts", "stream", "updated_at"} {
		if _, ok := m[k]; !ok {
			t.Errorf("遥测块缺字段 %q，实际 %s", k, string(b))
		}
	}
	// ⚠️ 没有出错时不该出现 error 键（空串就不给，别让 App 以为出事了）。
	if _, ok := m["error"]; ok {
		t.Errorf("没出错时不该有 error 字段，实际 %s", string(b))
	}
	// ⚠️ updated_at 没有数据时必须是 null（不是缺键）——
	//    缺键会让 App 分不清「没数据」与「服务端没这个字段」。
	if m["updated_at"] != nil {
		t.Errorf("updated_at 无数据时应为 null，实际 %v", m["updated_at"])
	}
}
