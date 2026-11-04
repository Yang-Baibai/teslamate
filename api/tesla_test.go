package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// ---------- .env 回写 ----------

// TestReplaceEnvLinePreservesFormat 只替换目标那一行，其余字节原样保留。
func TestReplaceEnvLinePreservesFormat(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want string
	}{
		{
			name: "CRLF 行尾与注释原样保留",
			in:   "# 注释\r\nAPI_TOKEN=abc\r\nTESLA_REFRESH_TOKEN=old\r\nOTHER=1\r\n",
			want: "# 注释\r\nAPI_TOKEN=abc\r\nTESLA_REFRESH_TOKEN=new\r\nOTHER=1\r\n",
		},
		{
			name: "LF 行尾",
			in:   "# 注释\nTESLA_REFRESH_TOKEN=old\nOTHER=1\n",
			want: "# 注释\nTESLA_REFRESH_TOKEN=new\nOTHER=1\n",
		},
		{
			name: "没有 BOM 也没有其它行",
			in:   "TESLA_REFRESH_TOKEN=old\n",
			want: "TESLA_REFRESH_TOKEN=new\n",
		},
		{
			name: "BOM 保留",
			in:   "\xEF\xBB\xBFTESLA_REFRESH_TOKEN=old\n",
			want: "\xEF\xBB\xBFTESLA_REFRESH_TOKEN=new\n",
		},
		{
			name: "键不存在则追加（沿用文件行尾）",
			in:   "API_TOKEN=abc\r\n",
			want: "API_TOKEN=abc\r\nTESLA_REFRESH_TOKEN=new\r\n",
		},
		{
			name: "不能误伤同后缀的其它键",
			in:   "MY_TESLA_REFRESH_TOKEN=keep\nTESLA_REFRESH_TOKEN=old\n",
			want: "MY_TESLA_REFRESH_TOKEN=keep\nTESLA_REFRESH_TOKEN=new\n",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := string(replaceEnvLine([]byte(tc.in), teslaEnvKey, "new"))
			if got != tc.want {
				t.Fatalf("replaceEnvLine:\n got %q\nwant %q", got, tc.want)
			}
		})
	}
}

// TestWriteEnvValueRoundTrip 写回后能读回来，且其它行没被动过。
func TestWriteEnvValueRoundTrip(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, ".env")
	orig := "# 注释\r\nAPI_TOKEN=abc\r\nTESLA_REFRESH_TOKEN=old\r\nOTHER=1\r\n"
	if err := os.WriteFile(path, []byte(orig), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := writeEnvValue(path, teslaEnvKey, "new"); err != nil {
		t.Fatalf("writeEnvValue: %v", err)
	}
	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	want := "# 注释\r\nAPI_TOKEN=abc\r\nTESLA_REFRESH_TOKEN=new\r\nOTHER=1\r\n"
	if string(got) != want {
		t.Fatalf("writeEnvValue:\n got %q\nwant %q", got, want)
	}
	if v := readEnvValue(path, teslaEnvKey); v != "new" {
		t.Fatalf("readEnvValue = %q, want %q", v, "new")
	}
	// 临时文件不能留在目录里
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 {
		t.Fatalf("目录里应只剩 .env，实际 %d 个文件", len(entries))
	}
}

// ---------- Fleet API 响应解析 ----------

// TestFetchVehicleStateParsesWrappers response / data 两种包封都要认。
func TestFetchVehicleStateParsesWrappers(t *testing.T) {
	cases := []struct {
		name string
		body string
		want bool
	}{
		{"response 包封", `{"response":{"lock_state":true}}`, true},
		{"data 包封", `{"data":{"lock_state":false}}`, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if !strings.HasSuffix(r.URL.Path, "/api/1/vehicles/123/vehicle_data") {
					t.Errorf("path = %q", r.URL.Path)
				}
				// ⚠️ 绝不能带 endpoints 参数：实测中国区带上它只返回
				// 462 字节的车辆元数据，连 lock_state / 胎压都没有。
				if got := r.URL.Query().Get("endpoints"); got != "" {
					t.Errorf("endpoints = %q, 中国区不支持该参数", got)
				}
				if got := r.Header.Get("Authorization"); got != "Bearer tok" {
					t.Errorf("Authorization = %q", got)
				}
				io.WriteString(w, tc.body)
			}))
			defer srv.Close()

			c := newTeslaClient(teslaConfig{})
			c.fleetURL = srv.URL
			c.mu.Lock()
			c.accessToken = "tok"
			c.mu.Unlock()

			st, err := c.fetchVehicleState(context.Background(), "123")
			if err != nil {
				t.Fatalf("fetchVehicleState: %v", err)
			}
			if st.Locked == nil || *st.Locked != tc.want {
				t.Fatalf("lock_state = %v, want %v", st.Locked, tc.want)
			}
		})
	}
}

// TestFetchVehicleStateVehicleUnavailable 车睡着（408）要能被上层识别出来。
func TestFetchVehicleStateVehicleUnavailable(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusRequestTimeout)
		io.WriteString(w, `{"error":"vehicle unavailable"}`)
	}))
	defer srv.Close()

	c := newTeslaClient(teslaConfig{})
	c.fleetURL = srv.URL
	c.mu.Lock()
	c.accessToken = "tok"
	c.mu.Unlock()

	if _, err := c.fetchVehicleState(context.Background(), "123"); err != errVehicleUnavailable {
		t.Fatalf("err = %v, want errVehicleUnavailable", err)
	}
}

// TestFetchVehicleStateParsesTPMS 中国区的 vehicle_state 里同时带着实时胎压与告警位。
//
// 形状来自真机实测（中国区 fleet-api.prd.cn.vn.cloud.tesla.cn）：
// 胎压在 vehicle_state 里，字段名是 tpms_pressure_fl/fr/rl/rr（bar）。
func TestFetchVehicleStateParsesTPMS(t *testing.T) {
	body := `{"response":{"vehicle_state":{
		"locked":true,
		"timestamp":1792851782915,
		"odometer":63041.137767,
		"tpms_pressure_fl":2.95,"tpms_pressure_fr":2.875,
		"tpms_pressure_rl":2.875,"tpms_pressure_rr":2.85,
		"tpms_hard_warning_fl":false,"tpms_hard_warning_fr":false,
		"tpms_hard_warning_rl":false,"tpms_hard_warning_rr":false,
		"tpms_soft_warning_fl":false,"tpms_soft_warning_fr":false,
		"tpms_soft_warning_rl":false,"tpms_soft_warning_rr":false
	}}}`
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		io.WriteString(w, body)
	}))
	defer srv.Close()

	c := newTeslaClient(teslaConfig{})
	c.fleetURL = srv.URL
	c.mu.Lock()
	c.accessToken = "tok"
	c.mu.Unlock()

	st, err := c.fetchVehicleState(context.Background(), "123")
	if err != nil {
		t.Fatalf("fetchVehicleState: %v", err)
	}
	if st.Locked == nil || !*st.Locked {
		t.Fatalf("locked = %v, want true", st.Locked)
	}
	// ⚠️ **v0.127.0**：官方那个字段的原始单位是**英里**
	//   （上面的载荷就是真机实测值 63041.137767 英里）
	//   ⇒ 解析处已换算成公里：63041.137767 英里 = 101,454.8768 公里。
	//
	//   ⚠️ v0.127.0 之前这里断言的是**英里原值** ⇒ 等于把
	//   「把英里当公里」这个 bug 写进了测试（它自己就是帮凶）。
	if st.Odometer == nil || *st.Odometer < 101454.8 || *st.Odometer > 101454.9 {
		t.Fatalf("odometer = %v, want ≈101454.88 公里（63041.137767 英里换算而来）", st.Odometer)
	}
	if st.LockedAt == nil || st.LockedAt.UnixMilli() != 1792851782915 {
		t.Fatalf("采集时刻 = %v, want 1792851782915", st.LockedAt)
	}
	want := map[string]float64{"FL": 2.95, "FR": 2.875, "RL": 2.875, "RR": 2.85}
	got := map[string]*float64{
		"FL": st.TPMS.FL.Pressure, "FR": st.TPMS.FR.Pressure,
		"RL": st.TPMS.RL.Pressure, "RR": st.TPMS.RR.Pressure,
	}
	for k, w := range want {
		p := got[k]
		if p == nil || *p != w {
			t.Fatalf("%s 胎压 = %v, want %v", k, p, w)
		}
	}
	if st.TPMS.At == nil {
		t.Fatal("实时胎压的采集时刻不该是 nil")
	}
}

// TestFetchVehicleStateMissingPressure 缺字段 = 不知道（nil），不能压成 0。
func TestFetchVehicleStateMissingPressure(t *testing.T) {
	body := `{"response":{"vehicle_state":{"locked":false,"timestamp":1792851782915,
		"tpms_pressure_fr":2.875}}}`
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		io.WriteString(w, body)
	}))
	defer srv.Close()

	c := newTeslaClient(teslaConfig{})
	c.fleetURL = srv.URL
	c.mu.Lock()
	c.accessToken = "tok"
	c.mu.Unlock()

	st, err := c.fetchVehicleState(context.Background(), "123")
	if err != nil {
		t.Fatalf("fetchVehicleState: %v", err)
	}
	if st.TPMS.FL.Pressure != nil {
		t.Fatalf("缺失的胎压应是 nil，实际 %v", *st.TPMS.FL.Pressure)
	}
	if st.TPMS.FR.Pressure == nil || *st.TPMS.FR.Pressure != 2.875 {
		t.Fatalf("右前胎压 = %v, want 2.875", st.TPMS.FR.Pressure)
	}
}

// TestEvaluateLiveTpms 硬告警算异常，软告警算「接近阈值」，没有告警位时靠阈值兜底。
func TestEvaluateLiveTpms(t *testing.T) {
	f := func(v float64) *float64 { return &v }

	// 1) Tesla 服务端的硬告警位 → 异常（哪怕读数还在正常范围内）
	res := EvaluateLiveTpms(TeslaTPMS{RL: TeslaTPMSWheel{Pressure: f(2.9), Hard: true}})
	if !res.Alerting || len(res.Wheels) != 1 || res.Wheels[0] != "左后" {
		t.Fatalf("硬告警应判为异常，实际 %+v", res)
	}
	if len(res.Values) != 1 || !strings.Contains(res.Values[0], "左后") {
		t.Fatalf("硬告警应带读数描述，实际 %+v", res.Values)
	}

	// 2) 软告警位 → 接近阈值（提示，不算异常）
	res = EvaluateLiveTpms(TeslaTPMS{FL: TeslaTPMSWheel{Pressure: f(2.6), Soft: true}})
	if res.Alerting {
		t.Fatalf("软告警不该算异常，实际 %+v", res)
	}
	if len(res.WarnWheels) != 1 || res.WarnWheels[0] != "左前" {
		t.Fatalf("软告警应进 WarnWheels，实际 %+v", res)
	}

	// 3) Tesla 不告警时不会告诉你「快到了」，越界仍要靠阈值兜底
	res = EvaluateLiveTpms(TeslaTPMS{FL: TeslaTPMSWheel{Pressure: f(2.4)}})
	if !res.Alerting || len(res.Wheels) != 1 || res.Wheels[0] != "左前" {
		t.Fatalf("越界应判为异常，实际 %+v", res)
	}

	// 4) 一切正常 → 不异常
	res = EvaluateLiveTpms(TeslaTPMS{
		FL: TeslaTPMSWheel{Pressure: f(2.95)},
		FR: TeslaTPMSWheel{Pressure: f(2.9)},
		RL: TeslaTPMSWheel{Pressure: f(2.95)},
		RR: TeslaTPMSWheel{Pressure: f(2.95)},
	})
	if res.Alerting || len(res.Wheels) != 0 || len(res.WarnWheels) != 0 {
		t.Fatalf("正常值不该告警，实际 %+v", res)
	}

	// 5) 什么都没读到 → 不告警（由上层判成「未知」）
	res = EvaluateLiveTpms(TeslaTPMS{})
	if res.Alerting {
		t.Fatalf("没数据时不该告警，实际 %+v", res)
	}
}

// TestVehicleStateCacheKeepsLastValueWhenVehicleAsleep 车休眠（408）必须保留上次已知值。
//
// 为什么要测这个：车一停就是一片 null 的话，胎压功能等于没有。
func TestVehicleStateCacheKeepsLastValueWhenVehicleAsleep(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusRequestTimeout)
		io.WriteString(w, `{"error":"vehicle unavailable"}`)
	}))
	defer srv.Close()

	c := newTeslaClient(teslaConfig{})
	c.fleetURL = srv.URL
	c.mu.Lock()
	c.accessToken = "tok"
	c.tokenExpiry = time.Now().Add(8 * time.Hour)
	c.vehicleID = "123"
	c.mu.Unlock()

	// 模拟上一轮成功采集到的结果
	fl := 2.95
	at := time.Now().UTC()
	c.setLiveTPMS(TeslaTPMS{FL: TeslaTPMSWheel{Pressure: &fl}, At: &at})
	c.setLockState(true, at)

	err := c.pollOnce(context.Background())
	if !errors.Is(err, errVehicleUnavailable) {
		t.Fatalf("err = %v, want errVehicleUnavailable", err)
	}
	got := c.TPMS()
	if got.FL.Pressure == nil || *got.FL.Pressure != 2.95 {
		t.Fatalf("车休眠后实时胎压应保留上次结果，实际 %v", got.FL.Pressure)
	}
	if got.At == nil {
		t.Fatal("车休眠后实时胎压的采集时刻应保留")
	}
	if locked, _ := c.LockState(); locked == nil || !*locked {
		t.Fatalf("车休眠后锁车状态应保留上次结果，实际 %v", locked)
	}
}

// TestVehicleStateCacheKeepsLastValueOnRealFailure 真失败（429/5xx）也**保留**上次已知值。
//
// ⚠️ v0.9.0 改的语义。原来这里失败会调 clearVehicleState() 把锁车与胎压清成 null，
// 于是「Tesla 侧抖一下」= 「锁车状态未知」+ 「胎压卡整块消失」，
// 用户无法区分「车真没锁」和「这会儿没取到数据」。
//
// 「锁没锁」是车主第一诉求 —— 显示一个**带时间戳的旧值**远比「未知」有用，
// 也不误导（App 会写明这是最后一次成功获取的数据）。
func TestVehicleStateCacheKeepsLastValueOnRealFailure(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusTooManyRequests)
		io.WriteString(w, `{"error":"too many requests"}`)
	}))
	defer srv.Close()

	c := newTeslaClient(teslaConfig{})
	c.fleetURL = srv.URL
	c.mu.Lock()
	c.accessToken = "tok"
	c.tokenExpiry = time.Now().Add(8 * time.Hour)
	c.vehicleID = "123"
	c.mu.Unlock()

	// 模拟上一轮成功采集到的结果
	fl := 2.95
	at := time.Now().UTC()
	c.setLiveTPMS(TeslaTPMS{FL: TeslaTPMSWheel{Pressure: &fl}, At: &at})
	c.setLockState(true, at)
	c.markFleetOK()

	err := c.pollOnce(context.Background())
	if err == nil {
		t.Fatal("429 应当算失败")
	}
	if errors.Is(err, errVehicleUnavailable) {
		t.Fatalf("429 不应被当成车休眠：%v", err)
	}

	// 走一遍「处理结果」这一步（Run 循环里的判定逻辑）
	c.handlePollResult(err, teslaBackoffInit)

	// 关键断言：状态**没有被清空**
	if got := c.TPMS(); got.FL.Pressure == nil || *got.FL.Pressure != 2.95 {
		t.Fatalf("真失败后实时胎压应保留上次结果，实际 %v", got.FL.Pressure)
	}
	if locked, _ := c.LockState(); locked == nil || !*locked {
		t.Fatalf("真失败后锁车状态应保留上次结果，实际 %v", locked)
	}

	// 但**必须**记下失败原因，让 App 能说清「为什么现在读不到」
	fleetErr, lastOK := c.FleetStatus()
	if fleetErr == "" {
		t.Fatal("失败后应记下 fleetErr，App 要靠它说明原因")
	}
	if !strings.Contains(fleetErr, "最后一次成功获取") {
		t.Fatalf("fleetErr 必须说明显示的是旧数据，实际 %q", fleetErr)
	}
	if lastOK == nil {
		t.Fatal("失败前的成功时刻应保留（App 用它显示数据有多旧）")
	}
}

// TestShortFleetError 不泄露内部细节，且必须说清「显示的是旧数据」。
func TestShortFleetError(t *testing.T) {
	cases := []struct {
		name   string
		err    error
		expect string
	}{
		{"授权失效", fmt.Errorf("%w: vehicle_data 返回 401", errTeslaAuth), "授权已失效"},
		{"限流", errors.New("vehicle_data 返回 429: rate limited"), "限流"},
		{"服务端错误", errors.New("vehicle_data 返回 503: oops"), "云端暂时不可用"},
		{"网络错误", errors.New("Get \"https://x\": dial tcp: timeout"), "连不上"},
	}
	for _, c := range cases {
		got := shortFleetError(c.err)
		if !strings.Contains(got, c.expect) {
			t.Errorf("%s: shortFleetError = %q, 期望包含 %q", c.name, got, c.expect)
		}
		if !strings.Contains(got, "最后一次成功获取") {
			t.Errorf("%s: 必须说明显示的是旧数据，实际 %q", c.name, got)
		}
		// 不能泄露内部细节
		for _, leak := range []string{"vehicle_data 返回", "dial tcp", "https://", "Authorization"} {
			if strings.Contains(got, leak) {
				t.Errorf("%s: 泄露了内部细节 %q：%q", c.name, leak, got)
			}
		}
	}
}

// TestCarStatusAlwaysEmitsLiveTpmsKeys 实时胎压字段必须原样输出（没数据时是 null，不能缺键）。
func TestCarStatusAlwaysEmitsLiveTpmsKeys(t *testing.T) {
	b, err := json.Marshal(CarStatus{CarID: 1})
	if err != nil {
		t.Fatal(err)
	}
	s := string(b)
	for _, k := range []string{
		"tpms_live_fl", "tpms_live_fr", "tpms_live_rl", "tpms_live_rr",
		"tpms_live_at", "tpms_live_alert", "tpms_live_alert_wheels",
		"tpms_live_alert_detail", "tpms_live_warn_wheels",
	} {
		if !strings.Contains(s, `"`+k+`":`) {
			t.Fatalf("status 里缺 %s 键：%s", k, s)
		}
	}
	// 没数据时必须是 null（不是 0、也不能缺键）
	if !strings.Contains(s, `"tpms_live_fl":null`) {
		t.Fatalf("没数据时 tpms_live_fl 应输出 null：%s", s)
	}
	if !strings.Contains(s, `"tpms_live_alert":null`) {
		t.Fatalf("没数据时 tpms_live_alert 应输出 null：%s", s)
	}
}

// ---------- refresh_token 轮换 ----------

// TestRefreshAccessTokenRotatesAndPersists 换到新 refresh_token 后必须写回 .env。
func TestRefreshAccessTokenRotatesAndPersists(t *testing.T) {
	dir := t.TempDir()
	envPath := filepath.Join(dir, ".env")
	orig := "# 注释\r\nAPI_TOKEN=abc\r\nTESLA_REFRESH_TOKEN=old-token\r\nOTHER=1\r\n"
	if err := os.WriteFile(envPath, []byte(orig), 0o600); err != nil {
		t.Fatal(err)
	}

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.URL.Path, "/oauth2/v3/token") {
			http.NotFound(w, r)
			return
		}
		if err := r.ParseForm(); err != nil {
			t.Errorf("ParseForm: %v", err)
		}
		if got := r.Form.Get("grant_type"); got != "refresh_token" {
			t.Errorf("grant_type = %q", got)
		}
		if got := r.Form.Get("refresh_token"); got != "old-token" {
			t.Errorf("refresh_token = %q", got)
		}
		json.NewEncoder(w).Encode(map[string]any{
			"access_token":  "new-access",
			"refresh_token": "new-refresh",
			"expires_in":    28800,
		})
	}))
	defer srv.Close()

	c := newTeslaClient(teslaConfig{
		clientID:     "client-id",
		refreshToken: "old-token",
		envFile:      envPath,
		stateFile:    filepath.Join(dir, "fallback"),
	})
	c.authURL = srv.URL

	if err := c.refreshAccessToken(context.Background()); err != nil {
		t.Fatalf("refreshAccessToken: %v", err)
	}

	got, err := os.ReadFile(envPath)
	if err != nil {
		t.Fatal(err)
	}
	want := "# 注释\r\nAPI_TOKEN=abc\r\nTESLA_REFRESH_TOKEN=new-refresh\r\nOTHER=1\r\n"
	if string(got) != want {
		t.Fatalf("轮换后 .env:\n got %q\nwant %q", got, want)
	}

	c.mu.RLock()
	newRT := c.refreshToken
	c.mu.RUnlock()
	if newRT != "new-refresh" {
		t.Fatalf("内存里的 refresh_token = %q, want new-refresh", newRT)
	}
	// .env 写成功就不该有兜底文件
	if _, err := os.Stat(filepath.Join(dir, "fallback")); !os.IsNotExist(err) {
		t.Fatalf("不该写兜底文件（stat err = %v）", err)
	}
}

// TestRefreshAccessTokenNoRotation 响应里没有新 refresh_token 时不能动 .env。
func TestRefreshAccessTokenNoRotation(t *testing.T) {
	dir := t.TempDir()
	envPath := filepath.Join(dir, ".env")
	orig := "TESLA_REFRESH_TOKEN=old-token\r\n"
	if err := os.WriteFile(envPath, []byte(orig), 0o600); err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{
			"access_token": "new-access",
			"expires_in":   28800,
		})
	}))
	defer srv.Close()

	c := newTeslaClient(teslaConfig{clientID: "client-id", refreshToken: "old-token", envFile: envPath})
	c.authURL = srv.URL
	if err := c.refreshAccessToken(context.Background()); err != nil {
		t.Fatalf("refreshAccessToken: %v", err)
	}
	got, _ := os.ReadFile(envPath)
	if string(got) != orig {
		t.Fatalf(".env 被改了：%q", got)
	}
}

// TestMaskSecretText 日志脱敏不能漏掉令牌。
func TestMaskSecretText(t *testing.T) {
	in := `{"error":"invalid_grant","client_id":"client-abc","refresh_token":"abcdefghijklmnopqrstuvwxyz012345"}`
	got := maskSecretText(in)
	if strings.Contains(got, "abcdefghijklmnopqrstuvwxyz012345") {
		t.Fatalf("令牌没被脱敏: %q", got)
	}
}

// TestWriteEnvValueOnBindMount 在「宿主机挂进来的 .env」上真写一次。
//
// 为什么要有这个测试：
//
//	.env 挂进容器有两种挂法，行为完全不同——
//	  - 挂目录（.env 是目录里的普通文件）：rename 覆盖可用，是原子的
//	  - 挂单个文件（.env 本身是挂载点）：Linux 上 rename 覆盖会 EBUSY，
//	    必须退化成原地覆写
//	两种挂法都必须保证「写对了、且没把 .env 写坏」。
//
// 路径由 TESLA_TEST_ENV_PATH 指定（不设就跳过），容器里跑：
//
//	go test -run TestWriteEnvValueOnBindMount ./...
func TestWriteEnvValueOnBindMount(t *testing.T) {
	path := os.Getenv("TESLA_TEST_ENV_PATH")
	if path == "" {
		t.Skip("未指定 TESLA_TEST_ENV_PATH，跳过挂载点写入测试")
	}
	before, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("读 %s: %v", path, err)
	}
	if !bytes.Contains(before, []byte(teslaEnvKey+"=")) {
		t.Fatalf("%s 里没有 %s 这一行", path, teslaEnvKey)
	}

	if err := writeEnvValue(path, teslaEnvKey, "rotated-token-value"); err != nil {
		t.Fatalf("writeEnvValue: %v", err)
	}

	after, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("读 %s: %v", path, err)
	}
	if !bytes.Contains(after, []byte(teslaEnvKey+"=rotated-token-value")) {
		t.Fatalf("回写后没有新值：\n%s", after)
	}
	// 其它行必须一个字节都没动
	if bytes.Count(before, []byte("\n")) != bytes.Count(after, []byte("\n")) {
		t.Fatalf("行数变了：%d → %d", bytes.Count(before, []byte("\n")), bytes.Count(after, []byte("\n")))
	}
	// 临时文件不能残留（残留就是潜在的密钥泄漏）
	dir := filepath.Dir(path)
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("读目录 %s: %v", dir, err)
	}
	for _, e := range entries {
		if strings.HasPrefix(e.Name(), "."+filepath.Base(path)+".tmp") {
			t.Fatalf("残留临时文件：%s", e.Name())
		}
	}
}
