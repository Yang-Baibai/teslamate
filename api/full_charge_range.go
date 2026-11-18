package main

// ⚠️ **v0.174.0：实测满电续航 —— 判定与文案收归服务端。**
//
// ## 为什么搬
//
// 原来这段折算在**客户端**（app-android/.../ui/RangeSegments.kt 的 buildRangeSegments）。
// 后果有两个：
//
// ① **飞书里问不出来** —— mission §2.1「App 能看到的，Hermes 也要能取」；
//    这正是 docs/app-conclusions.json 里第 1 条 gap 的由来。
// ② **两端各算一套** —— 客户端那套的阈值（minSocUsed / 1.5 倍物理可行性）
//    只写在客户端，服务端没有 ⇒ 以后任一边改阈值，用户在 App 与飞书里
//    会看到**互相矛盾的两个数**（ADR #38 要治的正是这个病）。
//
// ## 口径（唯一定义处在这里）
//
// 一段续航 = 从**一次充电结束**到**下一次充电开始**。
// 里程 = 下一次充电的起始里程 − 上一次充电的结束里程
// （含开到充电桩的绕行 —— 那也是真跑的公里）。
// 折算成满电：公里数 ÷ 掉电百分比 × 100。
//
// ## 两条剔除判据（阈值都在这里，客户端不许再判）
//
// ① **掉电不足**（minSocUsed = 20 个百分点）：差几十公里的短途折算出来噪声极大。
// ② **物理上不可能**：折算满电里程 > 官方标称 × 1.5。
//
// ⚠️ **为什么是 1.5**：官方标称 448 公里 × 1.5 = 672 公里；
// 本机**实测**的高速满电纪录约 607 公里（1.36×）⇒ 1.5 留了一点余量。
//
// ## ⚠️ 官方标称拿不到时为什么不退回「行程覆盖率」
//
// 客户端原来那条退化路径要拉 drives（按里程重叠裁剪算覆盖率）。
// ⚠️ **服务端不查 drives** —— 因为本机**官方标称拿得到**（end_range_km 有值），
// 物理可行性判据**永远先生效** ⇒ 覆盖率那条路径在本机**根本走不到**。
// ⇒ 少一次重查询（drives 表很大）；且**不假装**那条路径存在。
// ⇒ 官方标称缺失时**不给数**（返回 caveat），而不是换一个口径给一个不一样的数。
// （详见 [fullChargeCaveat]。）

import (
	"context"
	"fmt"
	"net/http"
	"sort"
	"strconv"
	"time"
)

// ---------- 阈值（唯一定义处） ----------

const (
	// fullChargeMinSocUsed 掉电不足这个百分点数的段不算。
	//
	// ⚠️ 为什么要有它：差几十公里的短途（掉电 5%~15%）折算出来的
	// 「满电里程」噪声极大，混进来会把中位数拉偏。
	fullChargeMinSocUsed = 20

	// fullChargeFeasibleRatio 折算满电里程超过官方标称的这个倍数 ⇒ 物理上不可能。
	//
	// ⚠️ 本机实测：官方标称 448 公里 × 1.5 = 672 公里；
	// 实测高速满电纪录约 607 公里（1.36×）⇒ 1.5 留了一点余量。
	fullChargeFeasibleRatio = 1.5
)

// fullChargeRangeNote 常驻说明的**前半段**（App 与飞书共用这一句）。
//
// ⚠️ **v0.174.0**：这是**服务端唯一一份** —— 客户端不再自己拼这一句。
// （原来客户端拼的是「基于 N 段实测 · 官方标称 X 公里，实测低 Y%」，
// 而那个 Y% 是客户端自己算的 —— 两个地方各算一遍必然漂。）
//
// ⚠️ 宽度受 api/app_copy.go 的 appCopyWidthLimit（44）约束 —— 单测钉住。
const fullChargeRangeNote = "基于 %d 段实测"

// fullChargeRangeNoteOfficial 官方标称那一段（服务端唯一一份）。
//
// ⚠️ **v0.174.0**：客户端原来自己拼「官方标称 X 公里」⇒ 现在也归服务端。
const fullChargeRangeNoteOfficial = " · 官方标称 %d 公里"

// fullChargeRangeNoteDiffHigh / Low 实测**高于 / 低于**官方标称时那半句。
//
// ⚠️ **v0.40.0 的教训**：方向曾经反过（旧算法把「实测低 23%」报成「实测高 77%」
// —— 一个让 App 看起来比官方**强 77%**、而实际**弱 23%** 的假恭维）。
// ⇒ 方向与措辞都只在服务端一份。
const fullChargeRangeNoteDiffHigh = "，实测高 %d%%"
const fullChargeRangeNoteDiffLow = "，实测低 %d%%"

// fullChargeCaveat 官方标称缺失 / 样本不足时那句。
//
// ⚠️ **v0.174.0**：这是「不知道」而不是「正常」——
// 客户端据此**不显示这张卡**，飞书里则说清为什么没有数。
const fullChargeCaveat = "官方标称拿不到，无法换算成满电续航。"

// ---------- ⓘ 文案（服务端唯一一份，v0.175.0） ----------
//
// ⚠️ **v0.175.0**：这三段以前在**客户端**（ui/RangeSegments.kt 里的 InfoNote 拼接）。
// 两个问题：
//   ① **违反 ADR #41** —— 客户端在拼文案，而「车机满电时自己报的是 X 公里」
//      这个数服务端**已经给了**（note 里就有）⇒ 两处拼装 = 迟早漂。
//   ② **「市区与高速差 1.7 倍」是硬编码的具体分布断言** —— 实测「最好/中位」
//      已变成 **1.7562**（应写 1.8 倍）⇒ 那句话**早就在说错了**，
//      而没有任何判据会红。
// ⇒ 现在三段文案都在服务端；「差几倍」由**真实段数据**算（见 [fullChargeSpread]）。
//
// ⚠️ **每行 ≤ [noteLineWidthLimit]（96，在 api/app_copy.go）** —— 单测钉住。
// ⇒ 这里用 **\n** 分行（不是「；」）：客户端直接透传，Compose 的 Text 会照换行渲染。

// fullChargeMethodNote ⓘ「这个数怎么算的」（折算口径）。
//
// ⚠️ **%d = 官方标称（公里）** —— 服务端算好后填进去，客户端不许自己拼。
// ⚠️ **「X%」里的 % 在 Sprintf 里必须写成 %%** —— 否则 go vet 报「unknown verb」。
const fullChargeMethodNote = "这是折算值：把「从 X%% 开到 Y%% 跑了多少公里」按掉电比例折算到满电，含没充满就走的段\n" +
	"折算用净掉电，下坡回充被抵扣，所以偏乐观\n" +
	"车机满电时自己报的是 %d 公里\n" +
	"这两个数都是这车跑出来的，区别是一个折算、一个直读，规划请用上面的线路余量表"

// fullChargeReadNote ⓘ「最好/最差怎么读」（读法说明）。
const fullChargeReadNote = "「最好」多半含下坡回充，长下坡几乎不耗电，别拿它当平路规划依据\n" +
	"「最差」才是市区工况更接近的那个数"

// fullChargeScopeNoteTmpl ⓘ「口径」（段定义 + 分布）。
//
// ⚠️ **%s = 最好/中位的倍数**（由真实段数据算，见 [fullChargeSpread]）
// ⇒ 不再硬编码「1.7 倍」（实测已经是 1.7562 ⇒ 那是**过期的事实**）。
const fullChargeScopeNoteTmpl = "口径：一次充电结束到下一次充电开始，按掉电百分比折算到满电\n" +
	"这是折算值，不是某一次满电实跑\n" +
	"最好与中位差 %s 倍\n" +
	"中位数不代表任何一次具体出行"

// ---------- 端点返回结构 ----------

// FullChargeSegment 一段实测续航。
//
// ⚠️ 字段名与客户端旧模型一致（start_odometer_km / end_odometer_km …），
// 这样客户端只要把「本地算」换成「读服务端」，**渲染逻辑一字不改**。
type FullChargeSegment struct {
	StartOdoKm float64 `json:"start_odometer_km"`
	EndOdoKm   float64 `json:"end_odometer_km"`
	Km         float64 `json:"km"`
	FromSoc    int     `json:"from_soc"`
	ToSoc      int     `json:"to_soc"`
	SocUsed    int     `json:"soc_used"`
	StartDate  string  `json:"start_date"`
	FullCharge float64 `json:"full_charge_km"`
}

// FullChargeRange 实测满电续航的完整结论。
type FullChargeRange struct {
	// CarID 这台车（防止客户端串车）。
	CarID int `json:"car_id"`
	// MedianKm 折算满电里程的**中位数**（公里）—— 这张卡的主角。
	// nil = 算不出来（样本不足 / 官方标称缺失）⇒ 客户端**不显示这张卡**，
	// 而不是显示 0 或显示一半。
	MedianKm *float64 `json:"median_km"`
	// OfficialKm 官方标称的满电续航（公里）—— 车机在满电时自己报的那个数。
	// nil = 拿不到（老车 / 车端没上报）⇒ 见 [FullChargeRange.Caveat]。
	OfficialKm *float64 `json:"official_km"`
	// VsOfficialPercent 实测 vs 官方标称的差（%）。
	//
	// ⚠️ **v0.40.0 的教训**：旧算法 round(median/official*100) 恒 ≥ 0
	// ⇒ 「实测低 23%」被报成「实测高 77%」（假恭维）。
	// ⇒ 现在口径是 (median/official*100) - 100：**正数 = 高，负数 = 低**。
	VsOfficialPercent *int `json:"vs_official_percent"`
	// SegmentCount 参与统计的段数。
	SegmentCount int `json:"segment_count"`
	// Segments 段明细（App 的折叠区与飞书共用这一份）。
	Segments []FullChargeSegment `json:"segments"`
	// Note 常驻说明（服务端唯一一份）。
	Note string `json:"note"`
	// MethodNote ⓘ「这个数怎么算的」（服务端唯一一份，客户端只透传）。
	MethodNote string `json:"method_note"`
	// ReadNote ⓘ「最好/最差怎么读」（服务端唯一一份）。
	ReadNote string `json:"read_note"`
	// ScopeNote ⓘ「口径」（服务端唯一一份；里面的倍数按**真实段数据**算）。
	ScopeNote string `json:"scope_note"`
	// Caveat 算不出数时的原因（可空）。
	//
	// ⚠️ **v0.174.0**：这是「不知道」而不是「正常」——
	// 客户端据此**不显示这张卡**，并在飞书里说清为什么没有数。
	Caveat string `json:"caveat"`
}

// ---------- 内部折算（纯函数，可单测） ----------

// fullChargeRow 折算要用的**最小**充电字段集。
type fullChargeRow struct {
	StartOdoKm *float64
	EndOdoKm   *float64
	StartSoc   *int
	EndSoc     *int
	EndRangeKm *float64
	StartDate  time.Time
	EndDate    *time.Time
}

// buildFullChargeSegments 从充电记录重建续航段。
//
// ⚠️ 这段逻辑原来是客户端的 buildRangeSegments（v0.27.0 起）——
// **逐条照搬**，只把判定挪了个地方（ADR #38）。
//
// @param officialRangeKm 官方标称的满电续航（公里）；nil = 拿不到
//
// 排序说明：同里程的充电必须按**时间**升序排。
// 实测 ids 43/44 的里程完全相同（97726.898642）—— 它们是**一次插枪被切成的两段**
// （43: 68%→75%，44: 74%→100%）。稳定排序若保持 API 返回的日期倒序，
// 「下一场充电」会变成 44 ⇒ 续航段配成 (41, 44) ⇒ 掉电算成 26%
// ⇒ 折算满电 734 公里（物理上不可能）。真实应该是 (41, 43) ⇒ 596 公里。
func buildFullChargeSegments(rows []fullChargeRow, officialRangeKm *float64) []FullChargeSegment {
	// 过滤：里程与两端电量都得有，否则算不出这一段。
	filtered := make([]fullChargeRow, 0, len(rows))
	for _, r := range rows {
		if r.StartOdoKm == nil || r.StartSoc == nil || r.EndSoc == nil {
			continue
		}
		filtered = append(filtered, r)
	}
	// 按（起始里程，开始时间）升序 —— 同里程按时间，稳定。
	sort.SliceStable(filtered, func(i, j int) bool {
		if *filtered[i].StartOdoKm != *filtered[j].StartOdoKm {
			return *filtered[i].StartOdoKm < *filtered[j].StartOdoKm
		}
		return filtered[i].StartDate.Before(filtered[j].StartDate)
	})

	out := make([]FullChargeSegment, 0, len(filtered))
	for i := 0; i+1 < len(filtered); i++ {
		a := filtered[i]
		b := filtered[i+1]
		// 这一段的起点 = 上一场充电**结束**时的里程；
		// 没有结束里程就退回这一场充电的**起始**里程。
		odoA := a.EndOdoKm
		if odoA == nil {
			odoA = a.StartOdoKm
		}
		odoB := b.StartOdoKm
		if odoA == nil || odoB == nil {
			continue
		}
		km := *odoB - *odoA
		if km <= 5.0 {
			// 差几公里的「段」没有意义（挪车也会产生）。
			continue
		}
		fromSoc := *a.EndSoc
		toSoc := *b.StartSoc
		socUsed := fromSoc - toSoc
		if socUsed < fullChargeMinSocUsed {
			continue
		}
		fullChargeKm := km / float64(socUsed) * 100.0
		// ⚠️ 物理可行性判据：折算满电里程超过官方标称 1.5 倍 ⇒ 这台车做不到
		// ⇒ 剔除。官方标称缺失时**不给数**（见 fullChargeCaveat）。
		if officialRangeKm != nil {
			if fullChargeKm > *officialRangeKm*fullChargeFeasibleRatio {
				continue
			}
		}
		segDate := a.StartDate
		if a.EndDate != nil {
			segDate = *a.EndDate
		}
		out = append(out, FullChargeSegment{
			StartOdoKm: *odoA,
			EndOdoKm:   *odoB,
			Km:         km,
			FromSoc:    fromSoc,
			ToSoc:      toSoc,
			SocUsed:    socUsed,
			StartDate:  segDate.UTC().Format(time.RFC3339),
			FullCharge: fullChargeKm,
		})
	}
	return out
}

// officialFullChargeRangeKm 官方标称的**满电**续航（公里）。
//
// 取「充到 ≥99% 的那些场次」的车机额定续航中位数 ——
// 那就是车机在满电时显示的那个数，正是官方 App 给用户的那个数。
//
// ⚠️ **v0.174.0**：这个函数原来在客户端（officialFullChargeRangeKm）。
// 现在**只有服务端这一份**。
func officialFullChargeRangeKm(rows []fullChargeRow) *float64 {
	vals := make([]float64, 0, len(rows))
	for _, r := range rows {
		if r.EndSoc == nil || *r.EndSoc < 99 || r.EndRangeKm == nil {
			continue
		}
		vals = append(vals, *r.EndRangeKm)
	}
	if len(vals) == 0 {
		return nil
	}
	m := medianOfFloats(vals)
	return &m
}

// fullChargeVsOfficialPercent 实测 vs 官方标称的差（%）。
//
// ⚠️ **v0.40.0 的教训（这里保留痕迹）**：旧算法
//
//	diff = (median / official * 100).roundToInt()
//
// 是**比值×100**（恒 ≥ 0）⇒ 「实测低 23%」被报成「实测高 77%」
// ⇒ 一个让 App 看起来比官方**强 77%**、而实际**弱 23%** 的假恭维。
// ⇒ 现在口径是 (median/official*100) - 100：**正数 = 高，负数 = 低**。
//
// ⚠️ 这个函数原来在客户端（fullChargeVsOfficialPercent）——
// 现在**只有服务端这一份**（客户端的那份与它的单测一起删掉）。
func fullChargeVsOfficialPercent(medianKm float64, officialKm float64) *int {
	if officialKm <= 0.0 || medianKm <= 0.0 {
		return nil
	}
	raw := medianKm/officialKm*100.0 - 100.0
	pct := int(raw)
	if raw >= 0 {
		pct = int(raw + 0.5)
	} else {
		// ⚠️ 负数必须**远离零**地取整（-22.5 → -23），
		// 与客户端旧实现（Kotlin roundToInt）一致 ——
		// 用 math.Round 会变成「四舍五入到偶数」（-22.5 → -22）⇒ 差 1。
		pct = -int(-raw + 0.5)
	}
	return &pct
}

// fullChargeSpread 「最好与中位差几倍」。
//
// ⚠️ **v0.175.0**：这个倍数以前是**客户端硬编码的「1.7 倍」** ——
// 而它是个**具体的分布断言**（随数据变化）⇒ 实测已变成 1.7562（该写 1.8 倍）
// ⇒ 那句话**早就在说错**，而没有任何判据会红。
// ⇒ 现在由**真实段数据**算：最好段 ÷ 中位数，保留一位小数。
//
// ⚠️ **为什么是「最好/中位」而不是「市区/高速」**：
// 服务端**不分类**路段工况（那要引入「这段算市区还是高速」的另一个判定）
// ⇒ 只说**能查证的事实**（最好那趟与中位数差几倍）——
// 「最好多半含下坡回充」这句在 [fullChargeReadNote] 里，是**读法提示**不是分类。
//
// @return 倍数（一位小数的字符串）；样本不足时返回 ""（不给数，不编）。
func fullChargeSpread(segments []FullChargeSegment, medianKm float64) string {
	if len(segments) < 2 || medianKm <= 0 {
		return ""
	}
	best := segments[0].FullCharge
	for _, s := range segments {
		if s.FullCharge > best {
			best = s.FullCharge
		}
	}
	if best <= 0 {
		return ""
	}
	ratio := best / medianKm
	// 一位小数（四舍五入）。
	return strconv.FormatFloat(ratio, 'f', 1, 64)
}

// ---------- 取数 ----------

// sqlFullChargeRows 取折算要用的充电字段（最小集）。
//
// ⚠️ **口径与客户端那份严格一致**：
//
//	· 起始里程 = cp.position_id 指向的那条 positions.odometer；
//	· 结束里程 = 充电结束那一刻**最后一条** position 的 odometer；
//	· 官方标称 = cp.end_rated_range_km（车机在满电时报的数）。
//
// ⚠️ **过滤 charge_energy_added > 0.01**：与客户端旧算法**逐条同源**。
//
//	⚠️ **v0.174.0 实测查出来的坑**：本机有 **9 场「没实际充电」的会话**
//	（charge_energy_added = 0.00，start_soc == end_soc —— 插上桩没充上）。
//	客户端旧算法走的是 /charges 端点 ⇒ 那条 SQL **有**这个过滤
//	⇒ 客户端喂给折算的是 **83 场**，不是 98 场。
//	⇒ 服务端若不过滤，那 9 场会**插在段中间把一段拆成两段**
//	⇒ 段数变多、soc_used 变小 ⇒ **中位数会漂**。
//	⇒ 这里必须带上同一个过滤（与 api/queries.go 的 sqlCharges 一致）。
const sqlFullChargeRows = `
SELECT sp.odometer::float8,
       ep.odometer::float8,
       cp.start_battery_level,
       cp.end_battery_level,
       cp.end_rated_range_km::float8,
       cp.start_date,
       cp.end_date
FROM charging_processes cp
LEFT JOIN positions sp ON sp.id = cp.position_id
LEFT JOIN positions ep ON ep.id = (
  SELECT id FROM positions
  WHERE car_id = cp.car_id AND date <= cp.end_date
  ORDER BY date DESC LIMIT 1
)
WHERE cp.car_id = $1
  AND cp.charge_energy_added > 0.01
ORDER BY cp.start_date`

func queryFullChargeRows(ctx context.Context, carID int) ([]fullChargeRow, error) {
	rows, err := db.Query(ctx, sqlFullChargeRows, carID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []fullChargeRow{}
	for rows.Next() {
		var r fullChargeRow
		if err := rows.Scan(
			&r.StartOdoKm, &r.EndOdoKm,
			&r.StartSoc, &r.EndSoc,
			&r.EndRangeKm,
			&r.StartDate, &r.EndDate,
		); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// ---------- 端点 ----------

// handleFullChargeRange 实测满电续航（服务端算的结论）。
//
// GET /api/v1/cars/{id}/full_charge_range
//
// ⚠️ **v0.174.0 新增**：这个端点是 mission §2.1「App 能看到的，Hermes 也要能取」
// 的第一块砖 —— 原来 App 那个「实测满电续航」是**客户端自己算**的
// ⇒ 飞书里问不出来（docs/app-conclusions.json 第 1 条 gap）。
func handleFullChargeRange(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "car id 无效")
		return
	}
	ctx := context.Background()
	rows, err := queryFullChargeRows(ctx, id)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}

	out := FullChargeRange{
		CarID:    id,
		Segments: []FullChargeSegment{},
	}

	official := officialFullChargeRangeKm(rows)
	if official != nil {
		out.OfficialKm = official
	}

	// ⚠️ 官方标称拿不到 ⇒ **不给数**（不是「换一个口径给一个不一样的数」）。
	if official == nil {
		out.Caveat = fullChargeCaveat
		writeJSON(w, out)
		return
	}

	segments := buildFullChargeSegments(rows, official)
	out.Segments = segments
	out.SegmentCount = len(segments)
	if len(segments) == 0 {
		out.Caveat = fullChargeCaveat
		writeJSON(w, out)
		return
	}

	// 中位数：偶数个取两个中间值的平均（与 medianOfFloats 同一口径）。
	fulls := make([]float64, 0, len(segments))
	for _, s := range segments {
		fulls = append(fulls, s.FullCharge)
	}
	median := medianOfFloats(fulls)
	out.MedianKm = &median
	if pct := fullChargeVsOfficialPercent(median, *official); pct != nil {
		out.VsOfficialPercent = pct
	}
	// ⚠️ **v0.174.0**：整句在服务端拼好（官方标称 + 差百分比）——
	// 客户端一个字都不许自己拼（ADR #41：文案只有一份）。
	//
	// ⚠️ **宽度**：常驻文案上限 44（api/app_copy.go 的 appCopyWidthLimit）——
	// 单测钉住；超了就收窄措辞，不许调高上限。

	out.Note = fmt.Sprintf(fullChargeRangeNote, len(segments))
	if official != nil {
		out.Note += fmt.Sprintf(fullChargeRangeNoteOfficial, int(*official+0.5))
	}
	if pct := out.VsOfficialPercent; pct != nil {
		if *pct >= 0 {
			out.Note += fmt.Sprintf(fullChargeRangeNoteDiffHigh, *pct)
		} else {
			out.Note += fmt.Sprintf(fullChargeRangeNoteDiffLow, -*pct)
		}
	}
	// ⚠️ **v0.175.0**：三段 ⓘ 文案也在服务端拼好（客户端只透传）。
	out.MethodNote = fmt.Sprintf(fullChargeMethodNote, int(*official+0.5))
	out.ReadNote = fullChargeReadNote
	if spread := fullChargeSpread(segments, median); spread != "" {
		out.ScopeNote = fmt.Sprintf(fullChargeScopeNoteTmpl, spread)
	}
	writeJSON(w, out)
}
