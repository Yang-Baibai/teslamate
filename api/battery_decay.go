package main

import (
	"fmt"
	"math"
	"strconv"
	"time"
)

// ⚠️ **v0.35.0（第 21 轮数据口径审计 P1-D + 独立复算）：电池容量衰减的判定。**
//
// ## 为什么不能用一条直线
//
// v0.29.0 的做法是「按里程做最小二乘，然后看效应/噪声比」——
// 但那个**效应/噪声**指标**不是显著性检验**：
// 它拿「整段端到端变化」除以「单点残差 σ」，
// 而斜率的显著性取决于 **SE(斜率) = σ / √Sxx**，与端到端变化不是一回事。
//
// 更要命的是：把「样本 < 50 个就不给趋势」当闸门，
// 等于用一个**样本数启发式**代替统计判据 ——
// 数据明明支持结论（t = −5.18，95% CI 不含 0），却被闸门卡掉。
//
// ## 真正的问题：这条序列不是一条直线
//
// 独立复算本机 17 个严格档满充点（end_soc = 100）发现：
//
//	朴素线性：斜率 −0.1375 度/1000km，R² = 0.641
//	线性 + 一次阶跃：R² = **0.829**，F = **15.41**（F(1,14) 临界 4.60，显著）
//	阶跃本身：−0.5624 度，95% CI [−0.870, −0.255]，t = −3.92
//	扣掉阶跃后的连续速率：−0.0595 度/1000km
//
// 而**阶跃前后两段单独看都没有显著趋势**
// （前段 −0.0733，t = −1.72；后段 −0.0507，t = −1.36）——
// 这正是「缓慢连续衰减 + 一次不连续」的指纹。
//
// 换句话说：现在界面报的「每 1000 公里 −0.138 度」**高估了 2.3 倍**，
// 因为它把一次性的阶跃**抹成了连续趋势**。
// 真实连续速率只有 −0.060 度/1000km。
//
// ## 口径
//
// - **严格档**：只用 `end_soc == 100` 的点（真正充满）。
//   90~98% 的点从 94% 线性外推到 100%，**系统性偏高 0.56%**
//   （服务端自己在 `api/models.go` 的 `max_capacity_kwh_strict` 注释里写明）。
// - 自变量用**里程**（衰减按公里发生，按日期会把「这几个月开得多」误读成「衰减快」）。
// - 显著性用 **t 检验**（95%），而不是样本数闸门。
//
// ⚠️ **这个端点是「经验标定」，不是物理测量**：
// 容量 = 续航估值 × 效率常数，而续航估值受**充电时气温**影响
// （残差 σ 里大部分是气温，不是测量噪声）。所以结论只在这段里程内有效。

// decayPoint 一个满充测量点。
type decayPoint struct {
	date string
	km   float64
	kwh  float64
}

// BatteryDecay 电池容量衰减的判定结果。
type BatteryDecay struct {
	// 参与回归的点数（严格档）
	N int `json:"n"`
	// 严格档（100% 满充）点数
	StrictN int `json:"strict_n"`
	// 未计入的非满充点数
	ExcludedN int `json:"excluded_n"`
	// 覆盖的里程跨度（公里）与天数
	SpanKm   float64 `json:"span_km"`
	SpanDays int    `json:"span_days"`

	// ── 朴素线性（整段一条直线）──────────────────────────
	NaivePer1000Km  float64 `json:"naive_per_1000km"`
	NaiveCiLo       float64 `json:"naive_ci_lo"`
	NaiveCiHi       float64 `json:"naive_ci_hi"`
	NaiveSignificant bool   `json:"naive_significant"`
	R2Linear        float64 `json:"r2_linear"`

	// ── 一次阶跃 ──────────────────────────────────────────
	HasStep         bool    `json:"has_step"`
	StepKm          float64 `json:"step_km"`
	StepDate        string  `json:"step_date"`
	StepKwh         float64 `json:"step_kwh"`
	StepCiLo        float64 `json:"step_ci_lo"`
	StepCiHi        float64 `json:"step_ci_hi"`
	StepSignificant bool    `json:"step_significant"`
	R2Step          float64 `json:"r2_step"`
	// 阶跃把斜率「抹高」了多少倍（1 = 没有抹）
	NaiveOverstate  float64 `json:"naive_overstate"`

	// ── 扣掉阶跃后的连续速率 ──────────────────────────────
	UnderlyingPer1000Km  float64 `json:"underlying_per_1000km"`
	UnderlyingCiLo       float64 `json:"underlying_ci_lo"`
	UnderlyingCiHi       float64 `json:"underlying_ci_hi"`
	UnderlyingSignificant bool  `json:"underlying_significant"`

	// ── 阶跃前后两段各自的速率 ────────────────────────────
	PrePer1000Km   float64 `json:"pre_per_1000km"`
	PreSignificant bool    `json:"pre_significant"`
	PreN           int     `json:"pre_n"`
	PostPer1000Km  float64 `json:"post_per_1000km"`
	PostSignificant bool   `json:"post_significant"`
	PostN          int     `json:"post_n"`

	// 方法与警告
	Method string `json:"method"`
	Caveat string `json:"caveat"`
}

// decayMinPoints 做阶跃检测至少需要的点数。
const decayMinPoints = 8

// strictDateRange 严格档序列的首末日期。
func strictDateRange(pts []decayPoint) (time.Time, time.Time, bool) {
	if len(pts) < 2 {
		return time.Time{}, time.Time{}, false
	}
	first, err1 := time.Parse("2006-01-02", pts[0].date)
	last, err2 := time.Parse("2006-01-02", pts[len(pts)-1].date)
	if err1 != nil || err2 != nil {
		return time.Time{}, time.Time{}, false
	}
	return first, last, true
}

// tSignificant 判断 |β| 是否达到 t 临界值。
//
// ⚠️ **σ = 0 的退化情形必须单独处理**：
// 完全平的序列里 β 和 SE 都是 0，直接写 `|β| >= t·SE` 会**恒真**
// —— 于是「一条水平线」被判成「显著趋势」。
// （这是本轮回归测试 `TestBatteryDecayFlatSeriesHasNoStep` 当场抓出来的。）
//
// 规则：
//   - SE 有效 → 按 `|β| >= t·SE` 判；
//   - SE ≈ 0（完美拟合）→ 只有 β 也 ≈ 0 时才算「不显著」；
//     β ≠ 0 意味着完美线性，判为显著。
func tSignificant(beta, se, tc float64) bool {
	if se <= 1e-12 {
		return math.Abs(beta) > 1e-12
	}
	return math.Abs(beta) >= tc*se
}

// analyzeBatteryDecay 判定电池容量是不是「已经能看出趋势」。
//
// @param strict  严格档（end_soc == 100）且里程/容量齐全的点（按里程升序）
// @param excluded 未计入的非满充点数
// @param spanDays 数据覆盖的天数
func analyzeBatteryDecay(strict []decayPoint, excluded int, spanDays int) *BatteryDecay {
	out := &BatteryDecay{
		N:         len(strict),
		StrictN:   len(strict),
		ExcludedN: excluded,
		SpanDays:  spanDays,
		Method:    "严格档（充到 100%）按里程做最小二乘；先检验是否存在一次性阶跃（y = a + b·km + c·D），" +
			"再给出扣掉阶跃后的连续速率；显著性用 t 检验（95% 置信区间）。",
	}
	if len(strict) >= 2 {
		out.SpanKm = strict[len(strict)-1].km - strict[0].km
	}
	if len(strict) < decayMinPoints {
		out.Caveat = "只有 " + strconv.Itoa(len(strict)) + " 个 100% 满充点，不足以判定趋势。" +
			"（判定趋势至少要 " + strconv.Itoa(decayMinPoints) + " 个点）"
		return out
	}

	xs := make([][]float64, len(strict))
	ys := make([]float64, len(strict))
	for i, p := range strict {
		xs[i] = []float64{1, p.km}
		ys[i] = p.kwh
	}
	beta, se, r2, ok := olsFitFull(xs, ys)
	if !ok {
		out.Caveat = "线性拟合失败（样本共线或不足）。"
		return out
	}
	df := len(ys) - len(beta)
	tc := tCrit975(df)
	out.NaivePer1000Km = round3(beta[1] * 1000)
	out.NaiveCiLo = round3((beta[1] - tc*se[1]) * 1000)
	out.NaiveCiHi = round3((beta[1] + tc*se[1]) * 1000)
	out.NaiveSignificant = tSignificant(beta[1], se[1], tc)
	out.R2Linear = round3(r2)

	// ── 阶跃检测：y = a + b·km + c·D_k ────────────────────
	// 枚举所有切点，取 R² 最大者。
	bestR2 := r2
	bestK := -1
	var bestBeta, bestSe []float64
	for k := 3; k <= len(strict)-3; k++ {
		xs3 := make([][]float64, len(strict))
		for i, p := range strict {
			d := 0.0
			if i >= k {
				d = 1.0
			}
			xs3[i] = []float64{1, p.km, d}
		}
		b3, s3, r3, ok3 := olsFitFull(xs3, ys)
		if !ok3 {
			continue
		}
		if r3 > bestR2 {
			bestR2 = r3
			bestK = k
			bestBeta = b3
			bestSe = s3
		}
	}

	if bestK >= 0 && len(bestBeta) == 3 {
		df3 := len(ys) - 3
		tc3 := tCrit975(df3)
		stepT := 0.0
		if bestSe[2] > 0 {
			stepT = bestBeta[2] / bestSe[2]
		}
		out.HasStep = true
		out.StepKwh = round3(bestBeta[2])
		out.StepCiLo = round3(bestBeta[2] - tc3*bestSe[2])
		out.StepCiHi = round3(bestBeta[2] + tc3*bestSe[2])
		out.StepSignificant = tSignificant(stepT, 1, tc3)
		out.StepKm = strict[bestK].km
		out.StepDate = strict[bestK].date
		out.R2Step = round3(bestR2)
		out.UnderlyingPer1000Km = round3(bestBeta[1] * 1000)
		out.UnderlyingCiLo = round3((bestBeta[1] - tc3*bestSe[1]) * 1000)
		out.UnderlyingCiHi = round3((bestBeta[1] + tc3*bestSe[1]) * 1000)
		out.UnderlyingSignificant = tSignificant(bestBeta[1], bestSe[1], tc3)
		if bestBeta[1] != 0 {
			out.NaiveOverstate = round3(math.Abs(beta[1] / bestBeta[1]))
		}
		// 阶跃前后两段各自的速率
		out.PreN = bestK
		out.PostN = len(strict) - bestK
		out.PrePer1000Km, out.PreSignificant = segmentSlope(strict[:bestK])
		out.PostPer1000Km, out.PostSignificant = segmentSlope(strict[bestK:])
	}

	out.Caveat = decayCaveat(out)
	return out
}

// segmentSlope 一段序列的斜率（每 1000 公里）与是否显著。
func segmentSlope(pts []decayPoint) (float64, bool) {
	if len(pts) < 4 {
		return 0, false
	}
	xs := make([][]float64, len(pts))
	ys := make([]float64, len(pts))
	for i, p := range pts {
		xs[i] = []float64{1, p.km}
		ys[i] = p.kwh
	}
	beta, se, _, ok := olsFitFull(xs, ys)
	if !ok {
		return 0, false
	}
	df := len(ys) - len(beta)
	if df < 1 {
		return 0, false
	}
	tc := tCrit975(df)
	return round3(beta[1] * 1000), tSignificant(beta[1], se[1], tc)
}

func decayCaveat(d *BatteryDecay) string {
	var parts []string
	if d.HasStep {
		if d.StepSignificant {
			parts = append(parts, fmt.Sprintf(
				"检测到一次性阶跃：%s（%.0f 公里处）掉了 %.3f 度（95%% 区间 %.3f ~ %.3f，t 检验显著）",
				d.StepDate, d.StepKm, d.StepKwh, d.StepCiLo, d.StepCiHi))
		} else {
			parts = append(parts, fmt.Sprintf(
				"序列里有一个疑似阶跃（%s，%.0f 公里处，%.3f 度），但**统计上不显著**，仅供参考",
				d.StepDate, d.StepKm, d.StepKwh))
		}
		if d.NaiveOverstate > 1.15 {
			parts = append(parts, fmt.Sprintf(
				"⚠️ 不扣这次阶跃的话，表面速率会被**高估 %.1f 倍**（%.3f → %.3f 度/1000 公里）",
				d.NaiveOverstate, d.NaivePer1000Km, d.UnderlyingPer1000Km))
		}
	} else {
		parts = append(parts, "没有检测到一次性阶跃，整段按一条直线拟合")
	}
	if !d.PreSignificant && !d.PostSignificant {
		parts = append(parts, "阶跃前后两段**单独看都没有显著趋势** —— 这正是一次性阶跃的指纹")
	}
	parts = append(parts, fmt.Sprintf(
		"容量口径是「续航估值 × 效率常数」，残差里大部分是**充电时气温**而不是测量噪声；"+
			"结论只在这 %.0f 公里 / %d 天内有效，不能外推",
		d.SpanKm, d.SpanDays))
	return joinCN(parts, "；")
}
