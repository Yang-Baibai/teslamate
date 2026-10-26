// TeslaMate 手机客户端 API 服务（只读）
// 从 TeslaMate PostgreSQL 读取数据，输出手机友好的 JSON。
// 鉴权：除 /healthz 外全部要求 Authorization: Bearer $API_TOKEN
package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"log"
	"mime"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

type config struct {
	port     string
	apiToken string
	timezone string // 用于按本地日/月聚合，默认 Asia/Shanghai
}

func loadConfig() config {
	return config{
		port:     getenv("PORT", "8090"),
		apiToken: getenv("API_TOKEN", ""),
		timezone: getenv("TZ_NAME", "Asia/Shanghai"),
	}
}

func getenv(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

var db *pgxpool.Pool

func main() {
	// APK 必须以 Android 包类型下发：alpine 容器没有 mime 数据库，
	// FileServer 会按内容嗅探把 APK（本质是 zip 容器）标成 application/zip，
	// 手机浏览器就会把它当压缩包下载而拒绝安装。
	_ = mime.AddExtensionType(".apk", "application/vnd.android.package-archive")

	cfg := loadConfig()
	if cfg.apiToken == "" {
		log.Fatal("必须设置 API_TOKEN 环境变量")
	}
	poolCfg, err := pgxpool.ParseConfig("postgres://" + getenv("DATABASE_USER", "teslamate") +
		":" + getenv("DATABASE_PASS", "") + "@" + getenv("DATABASE_HOST", "database") +
		":" + getenv("DATABASE_PORT", "5432") + "/" + getenv("DATABASE_NAME", "teslamate") +
		"?sslmode=" + getenv("DATABASE_SSL_MODE", "disable"))
	if err != nil {
		log.Fatalf("数据库连接串无效: %v", err)
	}
	poolCfg.MaxConns = 4
	poolCfg.MaxConnLifetime = time.Hour
	db, err = pgxpool.NewWithConfig(context.Background(), poolCfg)
	if err != nil {
		log.Fatalf("无法创建连接池: %v", err)
	}
	defer db.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := db.Ping(ctx); err != nil {
		log.Fatalf("数据库连接失败: %v", err)
	}
	// AMAP_KEY 启动时软校验：缺则只警告（不退出），staticmap 端点会返回 500
	amapKey := getenv("AMAP_KEY", "")
	if amapKey == "" {
		log.Printf("⚠️  AMAP_KEY 未配置：/api/v1/app/staticmap 端点将不可用")
	}
	// Tesla Fleet API（锁车状态）：没配凭据也照常启动，只是 locked 恒为 null（未知）
	tesla = newTeslaClient(loadTeslaConfig())
	if tesla.configured() {
		teslaCtx, cancelTesla := context.WithCancel(context.Background())
		defer cancelTesla()
		go tesla.Run(teslaCtx)
		log.Printf("Tesla Fleet API 已启用（区域 %s，轮询 %s）", tesla.cfg.region, tesla.cfg.pollInterval)
	} else {
		log.Printf("⚠️  未配置 Tesla Fleet API 凭据（TESLA_CLIENT_ID / TESLA_REFRESH_TOKEN）：锁车状态恒为未知（null）")
	}
	log.Printf("API 服务就绪，端口 %s，时区 %s", cfg.port, cfg.timezone)

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", handleHealth)
	// ⚠️ v0.22.0：给 App「服务器状态」页用的详细端点（**需鉴权** ——
	// /healthz 是免鉴权且对外映射的，不能往里加内部状态）。
	mux.HandleFunc("GET /api/v1/health", auth(handleHealthDetailed))
	mux.HandleFunc("GET /api/v1/cars", auth(handleCars))
	mux.HandleFunc("GET /api/v1/cars/{id}/status", auth(handleStatus))
	mux.HandleFunc("GET /api/v1/cars/{id}/drives", auth(handleDrives))
	mux.HandleFunc("GET /api/v1/drives/{id}/track", auth(handleTrack))
	// ⚠️ v0.24.0：真实逐公里 SOC 剖面（长途规划用）。
	mux.HandleFunc("GET /api/v1/drives/{id}/soc_profile", auth(handleSocProfile))
	// ⚠️ v0.26.0：整条路线（多趟行程拼成一条）的逐公里 SOC 剖面。
	// 一次长途旅程被充电切成很多段，App 要的是**一条连续曲线**而不是一堆碎片。
	mux.HandleFunc("GET /api/v1/cars/{car_id}/route_profile", auth(handleRouteProfile))
	// ⚠️ v0.27.0：驾驶风格画像（最近 N 趟行程的评分 + 汇总）。
	// /drives 只说「开了多远」，从不说「开得急不急、是不是夜里开、能耗高不高」。
	mux.HandleFunc("GET /api/v1/cars/{car_id}/driving_profile", auth(handleDrivingProfile))
	// ⚠️ v0.28.0：常驻点（车平时停在哪、停多久）。
	// 官方 App 只说「车现在在哪」—— 这里说「它**平时**停哪、停多久」。
	// ⚠️ 这是**推断停留**（由相邻行程的时间间隙推出来的），
	// 不是实时停车时长 —— 车睡着后 TeslaMate 就不再记位置了。
	mux.HandleFunc("GET /api/v1/cars/{car_id}/stay_points", auth(handleStayPoints))
	// ⚠️ v0.28.0：能耗-车速曲线（不同车速下真实耗多少电）。
	// 官方 App 只有**平均** Wh/km，这里给的是一条曲线。
	mux.HandleFunc("GET /api/v1/cars/{car_id}/energy_profile", auth(handleEnergyProfile))
	// ⚠️ v0.33.0：本车的**爬升能耗系数**（上坡 Wh/m、回充 Wh/m）。
	// 「爬升修正」原来在客户端是二值饱和的 ×1.05，实测必须是**加性** ——
	// 重力位能 m·g·h 是绝对量，与车速无关。口径只在服务端算一处。
	mux.HandleFunc("GET /api/v1/cars/{car_id}/climb_model", auth(handleClimbModel))
	// ⚠️ v0.25.0：充电成本构成（每公里成本用）。
	mux.HandleFunc("GET /api/v1/cars/{id}/cost_summary", auth(handleCostSummary))
	mux.HandleFunc("GET /api/v1/cars/{id}/charges", auth(handleCharges))
	mux.HandleFunc("GET /api/v1/charges/{id}/curve", auth(handleChargeCurve))
	mux.HandleFunc("GET /api/v1/cars/{id}/stats", auth(handleStats))
	mux.HandleFunc("GET /api/v1/cars/{id}/battery", auth(handleBattery))
	// ⚠️ v0.66.0：车辆控制（车控下沉到本项目；Housekeeper 将来调这里）。
	//   GET  → 支持哪些动作（App 与 MCP 用它渲染）
	//   POST → 下发一条命令（/api/v1/cars/{id}/commands/{action}）
	mux.HandleFunc("GET /api/v1/cars/{id}/commands", auth(handleTeslaCommandCatalog))
	mux.HandleFunc("POST /api/v1/cars/{id}/commands/{action}", auth(handleTeslaCommand))
	// ⚠️ **v0.67.0：给下游（Housekeeper）的车辆原始数据** ——
	// **原样转发** Tesla 的响应（连信封一样），下游的归一化逻辑一行都不用改。
	mux.HandleFunc("GET /api/v1/cars/{id}/vehicle_data", auth(handleTeslaVehicleData))
	mux.HandleFunc("GET /api/v1/cars/{id}/vehicles", auth(handleTeslaVehiclesMeta))
	mux.HandleFunc("GET /api/v1/cars/{id}/vehicle_snapshot", auth(handleTeslaVehicleSnapshot))
	mux.HandleFunc("GET /api/v1/app/latest", auth(handleAppLatest))
	mux.HandleFunc("GET /api/v1/app/staticmap", auth(handleStaticMap))
	// APK 静态服务（不加 auth：DownloadManager 无法塞自定义 header；Tailscale 隧道已加密）
	mux.Handle("/apk/", http.StripPrefix("/apk/", http.FileServer(http.Dir("/apk"))))

	srv := &http.Server{
		Addr:         ":" + cfg.port,
		Handler:      recoverLogger(mux),
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 30 * time.Second,
	}
	log.Fatal(srv.ListenAndServe())
}

func recoverLogger(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		defer func() {
			if rec := recover(); rec != nil {
				log.Printf("PANIC %s %s: %v", r.Method, r.URL.Path, rec)
				writeErr(w, http.StatusInternalServerError, "internal error")
			}
		}()
		next.ServeHTTP(w, r)
		log.Printf("%s %s -> %s", r.Method, r.URL.Path, time.Since(start).Round(time.Millisecond))
	})
}

func auth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		h := r.Header.Get("Authorization")
		const prefix = "Bearer "
		if !strings.HasPrefix(h, prefix) ||
			subtle.ConstantTimeCompare([]byte(strings.TrimPrefix(h, prefix)), []byte(getenv("API_TOKEN", ""))) != 1 {
			writeErr(w, http.StatusUnauthorized, "token 无效")
			return
		}
		next(w, r)
	}
}

func writeErr(w http.ResponseWriter, code int, msg string) {
	writeErrCode(w, code, msg, "")
}

// writeErrCode 带**机器可读错误码**的 JSON 错误响应。
//
// 为什么要错误码：App 需要区分
//   - no_data：这台车还没数据 —— **正常状态**，该走空状态引导，不该是红色错误条
//   - no_car ：根本没这台车 —— 配置错了，该提示去检查设置
//
// 光看 HTTP 状态码（都是 404）App 只能靠中文文案做字符串匹配 —— 那种做法
// 脆得很（改一个字就失效），而且对非中文文案完全失效。
func writeErrCode(w http.ResponseWriter, code int, msg, errCode string) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	body := map[string]string{"error": msg}
	if errCode != "" {
		body["error_code"] = errCode
	}
	json.NewEncoder(w).Encode(body)
}

func pathID(r *http.Request) (int, error) {
	return strconv.Atoi(r.PathValue("id"))
}
