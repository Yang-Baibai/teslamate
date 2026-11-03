package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"math"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// ══════════════════════════════════════════════════════════════════════
// 保养 / 轮胎提醒 —— **基准与判定都在服务端**（v0.123.0）
// ══════════════════════════════════════════════════════════════════════
//
// ## 为什么搬
//
// 之前保养基准只存在**手机本地**（SharedPreferences 的 perCar）⇒
// 飞书 / Hermes **完全问不出**「我轮胎还能跑多少」——
// 这是 ADR #52（能力对账）最后一条没堵上的缺口：
// App 能看到的东西，Hermes 也要能取。
//
// ⇒ 现在：**基准存在服务端**（按车隔离），App 与 Hermes 读**同一份**。
//
// ## 三条不可破的规矩（与项目其它部分一致）
//
// ① **判定只在服务端**（ADR #38）：剩余公里数、超期与否、到期里程表，
//    全部由这里算。App 与 MCP 只读结果。
// ② **文案只有一份**（ADR #41）：这里的 renderMaintenanceText 出中文原文，
//    App 印它、Hermes 念它 ⇒ 两边不会各说各话。
// ③ **车端里程表读不到就不报**（v0.75.0 的教训）：
//    TeslaMate 的 positions.odometer 是按 GPS 点累计的，
//    实测能偏 38,422 公里 ⇒ 拿它当保养基准会报出
//    「保养超期 38,422 公里」这种**假告警**。
//    ⇒ 只用 Fleet API 的 vehicle_state.odometer（车端自己记的那个）。
//
// ## 基准是**车主的线下行为**（不是遥测）
//
// 换了机油、开了保养单，只有车主自己知道 ⇒ 它是**用户填的输入**，
// 不是车上报的数据 ⇒ 放在服务端自己的表里（不动 TeslaMate 的 schema），
// 由 App 的「设置 → 保养与轮胎」页写入。

// ── 建表 ──────────────────────────────────────────────────────────────

// sqlEnsureMaintenanceTable 建我们自己的表。
//
// ⚠️ 这是**本项目**的表，不动 TeslaMate 的 schema
// （TeslaMate 升级可能重写它的表结构，我们的表要独立）。
const sqlEnsureMaintenanceTable = `
CREATE TABLE IF NOT EXISTS tm_maintenance_baselines (
    car_id              smallint    PRIMARY KEY,
    service_odo_km      double precision NOT NULL DEFAULT 0,
    service_interval_km integer        NOT NULL DEFAULT 20000,
    tire_odo_km         double precision NOT NULL DEFAULT 0,
    tire_interval_km    integer        NOT NULL DEFAULT 40000,
    updated_at          timestamptz NOT NULL DEFAULT now()
)`

// sqlGetMaintenance 读某台车的基准（没有行 = 还没设置过）。
const sqlGetMaintenance = `
SELECT service_odo_km, service_interval_km, tire_odo_km, tire_interval_km
  FROM tm_maintenance_baselines WHERE car_id = $1`

// sqlUpsertMaintenance 写基准（UPSERT ⇒ 同一台车只有一行）。
const sqlUpsertMaintenance = `
INSERT INTO tm_maintenance_baselines
       (car_id, service_odo_km, service_interval_km, tire_odo_km, tire_interval_km, updated_at)
VALUES ($1, $2, $3, $4, $5, now())
ON CONFLICT (car_id) DO UPDATE
   SET service_odo_km      = EXCLUDED.service_odo_km,
       service_interval_km = EXCLUDED.service_interval_km,
       tire_odo_km         = EXCLUDED.tire_odo_km,
       tire_interval_km    = EXCLUDED.tire_interval_km,
       updated_at          = now()`

// ensureMaintenanceTable 建表（启动时调用一次）。
//
// ⚠️ 建表失败**不致命** —— 那样只是「保养提醒不可用」，
// 不该让整个 API 起不来。
func ensureMaintenanceTable(ctx context.Context) {
	if db == nil {
		return
	}
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	if _, err := db.Exec(ctx, sqlEnsureMaintenanceTable); err != nil {
		log.Printf("⚠️  建 tm_maintenance_baselines 失败：%v（保养提醒将不可用）", err)
		return
	}
	log.Printf("✅ tm_maintenance_baselines 就绪（保养 / 轮胎基准存在服务端）")
}

// ── 数据结构 ──────────────────────────────────────────────────────────

// MaintenanceBaseline 一台车的保养 / 轮胎基准（**用户填的输入**）。
type MaintenanceBaseline struct {
	// ServiceOdoKm 上次保养时的**车端里程表**读数（km）；0 = 没设置。
	ServiceOdoKm float64 `json:"service_odo_km"`
	// ServiceIntervalKm 保养间隔（km）。
	ServiceIntervalKm int `json:"service_interval_km"`
	// TireOdoKm 上次换胎时的车端里程表读数（km）；0 = 没设置。
	TireOdoKm float64 `json:"tire_odo_km"`
	// TireIntervalKm 换胎间隔（km）。
	TireIntervalKm int `json:"tire_interval_km"`
}

// MaintenanceItem 一项提醒（保养或轮胎）的**服务端结论**。
type MaintenanceItem struct {
	// Label 「保养」/「轮胎」。
	Label string `json:"label"`
	// DueOdoKm 该在哪个**车端里程表**读数时做。
	DueOdoKm float64 `json:"due_odo_km"`
	// RemainingKm 还剩多少公里（**负数 = 已超期**）。
	RemainingKm float64 `json:"remaining_km"`
	// Overdue 已超期。
	Overdue bool `json:"overdue"`
	// DueSoon 快到了（2000 公里内）—— 该提醒，但还没超期。
	DueSoon bool `json:"due_soon"`
	// Text 这一项的**中文原文**（服务端唯一一份文案，ADR #41）。
	Text string `json:"text"`
	// BannerText 首页横幅用的那一句（服务端唯一一份文案）。
	BannerText string `json:"banner_text"`
}

// MaintenanceReport 保养 / 轮胎提醒的完整报告（App 与 Hermes 共用）。
type MaintenanceReport struct {
	// CarID 车号。
	CarID int `json:"car_id"`
	// OdometerKm 当前**车端里程表**（km）；nil = 读不到（车睡着 / 没配 Fleet）。
	//
	// ⚠️ nil 时**不给任何判定**（宁可不说，也不误报）—— v0.75.0 的教训。
	OdometerKm *float64 `json:"odometer_km"`
	// OdometerSource 里程表的来源（「车端」/「未知」）。
	OdometerSource string `json:"odometer_source"`
	// Configured 基准是否已设置过（false = 用户还没填过）。
	Configured bool `json:"configured"`
	// Service / Tire 两项提醒（没设置基准的那项为 nil）。
	Service *MaintenanceItem `json:"service"`
	Tire    *MaintenanceItem `json:"tire"`
	// Summary 一句话总结（服务端唯一一份文案）。
	Summary string `json:"summary"`
	// Note 需要说明时的一句话（空 = 不需要）。
	Note string `json:"note"`
	// Baseline 原始基准（App 的设置页要显示它）。
	Baseline MaintenanceBaseline `json:"baseline"`
}

// maintenanceDueSoonKm 「快到了」的阈值（公里）。
//
// ⚠️ 这是**判定阈值**，只在服务端有一份（ADR #38）。
// 客户端闸门（Hermes 闸门 13）会核对两侧一致。
const maintenanceDueSoonKm = 2000

// ── 判定与文案 ────────────────────────────────────────────────────────

// maintenanceItem 算一项提醒的结论。
//
// ⚠️ odometerKm 为 nil ⇒ 返回 nil（**不报**）—— 车端读不到时宁可不说，
// 也不拿 GPS 累计值去套（v0.75.0：那样会报「超期 38,422 公里」的假告警）。
func maintenanceItem(label string, baseOdoKm float64, intervalKm int, odometerKm *float64) *MaintenanceItem {
	if baseOdoKm <= 0 || odometerKm == nil || *odometerKm <= 0 {
		return nil
	}
	due := baseOdoKm + float64(intervalKm)
	remaining := due - *odometerKm
	overdue := remaining < 0
	dueSoon := !overdue && remaining <= maintenanceDueSoonKm
	it := &MaintenanceItem{
		Label:       label,
		DueOdoKm:    due,
		RemainingKm: remaining,
		Overdue:     overdue,
		DueSoon:     dueSoon,
	}
	it.Text = renderMaintenanceItemText(it)
	it.BannerText = renderMaintenanceBannerText(it)
	return it
}

// renderMaintenanceItemText 出「保养卡里那一行」的文案（服务端唯一一份）。
//
// ⚠️ **不许暗示方向**（趋势 / ↑ ↓ →）—— 这里只报事实与差值。
func renderMaintenanceItemText(it *MaintenanceItem) string {
	if it.Overdue {
		return fmt.Sprintf("已超期 %d 公里", int(math.Abs(it.RemainingKm)))
	}
	return fmt.Sprintf("还有 %d 公里到期", int(it.RemainingKm))
}

// renderMaintenanceBannerText 出「首页横幅」那句文案（服务端唯一一份）。
//
// ⚠️ v0.20.0（车主 P1-1）：横幅里**不重复当前里程表**
// （下方「里程表 101,455 公里」已经有同一个数，一字不差地出现两次
// 会被以为是两个不同的数）⇒ 只写「超了多少 / 该在多少时做」。
func renderMaintenanceBannerText(it *MaintenanceItem) string {
	due := formatThousands(int64(math.Round(it.DueOdoKm)))
	if it.Overdue {
		return fmt.Sprintf("%s 超期 %d 公里 · 该在里程表 %s 公里时做",
			it.Label, int(math.Abs(it.RemainingKm)), due)
	}
	return fmt.Sprintf("%s 还有 %d 公里到期（该在里程表 %s 公里时做）",
		it.Label, int(it.RemainingKm), due)
}

// renderMaintenanceSummary 出「一句话总结」（给 Hermes / 飞书）。
//
// ⚠️ 两项都没设置 ⇒ 不编一句话（Summary 为空）⇒ 宁可说不知道。
func renderMaintenanceSummary(svc, tire *MaintenanceItem) string {
	var parts []string
	if svc != nil {
		parts = append(parts, svc.Text)
	}
	if tire != nil {
		parts = append(parts, tire.Text)
	}
	return strings.Join(parts, " · ")
}

// ── 取数 ──────────────────────────────────────────────────────────────

// fetchMaintenanceBaseline 读某台车的基准（没有行 ⇒ 零值 + configured=false）。
func fetchMaintenanceBaseline(ctx context.Context, carID int) (MaintenanceBaseline, bool, error) {
	var b MaintenanceBaseline
	err := db.QueryRow(ctx, sqlGetMaintenance, carID).Scan(
		&b.ServiceOdoKm, &b.ServiceIntervalKm, &b.TireOdoKm, &b.TireIntervalKm)
	if err != nil {
		// ⚠️ 没有行 = 用户还没设置过 ⇒ **不是错误**。
		if strings.Contains(err.Error(), "no rows") {
			return MaintenanceBaseline{
				ServiceIntervalKm: 20000,
				TireIntervalKm:    40000,
			}, false, nil
		}
		return b, false, err
	}
	return b, true, nil
}

// computeMaintenance 算保养 / 轮胎提醒的完整报告。
func computeMaintenance(ctx context.Context, carID int) (*MaintenanceReport, error) {
	b, configured, err := fetchMaintenanceBaseline(ctx, carID)
	if err != nil {
		return nil, err
	}
	rep := &MaintenanceReport{
		CarID:          carID,
		Configured:     configured,
		Baseline:       b,
		OdometerSource: "未知",
	}
	// ⚠️ **车端里程表**（Fleet API 的 vehicle_state.odometer）——
	// 车主填的基准是他**仪表盘上看到的数**（车端），拿 GPS 累计值去套会偏几万公里。
	odo := teslaOdometer()
	if odo != nil && *odo > 0 {
		rep.OdometerKm = odo
		rep.OdometerSource = "车端"
	}
	rep.Service = maintenanceItem("保养", b.ServiceOdoKm, b.ServiceIntervalKm, rep.OdometerKm)
	rep.Tire = maintenanceItem("轮胎", b.TireOdoKm, b.TireIntervalKm, rep.OdometerKm)
	rep.Summary = renderMaintenanceSummary(rep.Service, rep.Tire)

	switch {
	case rep.OdometerKm == nil:
		// ⚠️ 车端读不到 ⇒ **不报**（v0.75.0 的教训）。
		rep.Note = "车端里程表暂时读不到（车可能睡着了）⇒ 暂不判定保养到期"
	case !configured || (rep.Service == nil && rep.Tire == nil):
		rep.Note = "还没设置保养与轮胎基准"
	}
	return rep, nil
}

// ── HTTP ──────────────────────────────────────────────────────────────

// handleMaintenance 返回保养 / 轮胎提醒的**服务端结论**。
//
// ⚠️ 这是**飞书 / Hermes 能问到的第一个「保养类」问题** ——
// 之前这条判定只在客户端算 ⇒ 飞书问「我轮胎还能跑多少」只能答「不知道」。
func handleMaintenance(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "car id 无效")
		return
	}
	rep, err := computeMaintenance(r.Context(), id)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, rep)
}

// maintenanceBaselineInput 「写基准」的请求体。
type maintenanceBaselineInput struct {
	ServiceOdoKm      float64 `json:"service_odo_km"`
	ServiceIntervalKm int     `json:"service_interval_km"`
	TireOdoKm         float64 `json:"tire_odo_km"`
	TireIntervalKm    int     `json:"tire_interval_km"`
}

// handleMaintenanceBaseline 写某台车的保养 / 轮胎基准。
//
// ⚠️ **由 App 的「设置 → 保养与轮胎」页调用** —— 基准是车主的线下行为，
// 只有车主自己知道（换了机油、开了保养单）⇒ 它是**用户填的输入**。
//
// ⚠️ 校验：**负数一律拒绝**（里程表读数不可能是负的）
// ⇒ 宁可 400，也不写进去一个会让提醒乱掉的数。
func handleMaintenanceBaseline(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "car id 无效")
		return
	}
	var in maintenanceBaselineInput
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		writeErr(w, http.StatusBadRequest, "请求体不是合法的 JSON")
		return
	}
	if in.ServiceOdoKm < 0 || in.TireOdoKm < 0 {
		writeErr(w, http.StatusBadRequest, "里程表读数不能是负数")
		return
	}
	if in.ServiceOdoKm > 0 && in.ServiceIntervalKm <= 0 {
		writeErr(w, http.StatusBadRequest, "保养间隔必须大于 0 公里")
		return
	}
	if in.TireOdoKm > 0 && in.TireIntervalKm <= 0 {
		writeErr(w, http.StatusBadRequest, "换胎间隔必须大于 0 公里")
		return
	}
	if in.ServiceIntervalKm <= 0 {
		in.ServiceIntervalKm = 20000
	}
	if in.TireIntervalKm <= 0 {
		in.TireIntervalKm = 40000
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	if _, err := db.Exec(ctx, sqlUpsertMaintenance, id,
		in.ServiceOdoKm, in.ServiceIntervalKm, in.TireOdoKm, in.TireIntervalKm); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	// 回读一份**算好的结论** ⇒ App 不用再自己算一遍。
	rep, err := computeMaintenance(ctx, id)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, rep)
}

// formatThousands 千分位（与 App 的 fmtThousands 同一口径）。
//
// ⚠️ 这里是**服务端唯一**的一份：App 侧那个 Kotlin 函数只用于**本地兜底**，
// 正常路径的数字由服务端给。
func formatThousands(v int64) string {
	s := strconv.FormatInt(v, 10)
	neg := false
	if strings.HasPrefix(s, "-") {
		neg = true
		s = s[1:]
	}
	var parts []string
	for len(s) > 3 {
		parts = append([]string{s[len(s)-3:]}, parts...)
		s = s[:len(s)-3]
	}
	parts = append([]string{s}, parts...)
	out := strings.Join(parts, ",")
	if neg {
		return "-" + out
	}
	return out
}
