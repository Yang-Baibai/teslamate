package main

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
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

// TestFetchLockStateParsesWrappers response / data 两种包封都要认。
func TestFetchLockStateParsesWrappers(t *testing.T) {
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
				if got := r.URL.Query().Get("endpoints"); got != "lock_state" {
					t.Errorf("endpoints = %q", got)
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

			got, err := c.fetchLockState(context.Background(), "123")
			if err != nil {
				t.Fatalf("fetchLockState: %v", err)
			}
			if got == nil || *got != tc.want {
				t.Fatalf("lock_state = %v, want %v", got, tc.want)
			}
		})
	}
}

// TestFetchLockStateVehicleUnavailable 车睡着（408）要能被上层识别出来。
func TestFetchLockStateVehicleUnavailable(t *testing.T) {
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

	if _, err := c.fetchLockState(context.Background(), "123"); err != errVehicleUnavailable {
		t.Fatalf("err = %v, want errVehicleUnavailable", err)
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
//   .env 挂进容器有两种挂法，行为完全不同——
//     - 挂目录（.env 是目录里的普通文件）：rename 覆盖可用，是原子的
//     - 挂单个文件（.env 本身是挂载点）：Linux 上 rename 覆盖会 EBUSY，
//       必须退化成原地覆写
//   两种挂法都必须保证「写对了、且没把 .env 写坏」。
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
