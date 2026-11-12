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
// ⚠️ **v0.41.0（第 24 轮对抗性审计 ②③④）：上面那些数字**不能直接说成「检出了衰减」**。**
//
// 三处「说过头」：
//
// ① **显著性的刀刃在 0 上** —— 扣阶跃后的 95% 区间是 [−0.118, **−0.0006**]，
//    上界离 0 只有 0.0006（点估计的 1.0%）⇒ 说「显著」等于把结论架在刀尖上。
//
// ② **气温是混杂因素，而且被说反了** ——
//    旧文案写「残差里**大部分**是充电时气温」，
//    实测 Pearson **r = +0.424 ⇒ R² = 0.180**（气温只解释 **18%** 的残差方差）；
//    但气温系数 **+0.00916 度/°C**，27°C 与 11°C 相差 **0.147 度** ——
//    **与那个「阶跃」同量级**。
//    也就是说：气温**不是**残差的大头，却**足以左右结论**：
//    把气温放进模型后，连续速率变成 −0.0553，区间 [−0.1112, **+0.0007**] ⇒ **检不出**。
//
// ③ **阶跃是「挑」出来的** —— 12 个候选切点里取 R² 最大者，
//    而报出的 t = −3.92 **没有做多重比较校正**（胜者诅咒）。
//    次优切点 k=7 只差 0.4% 的 R²，就给出 −0.0550，区间 [−0.1168, **+0.0068**] ⇒ 不显著。
//    而且原始序列根本不是台阶 —— 满电续航估值按时间排是一条**单调下滑带噪声**的曲线，
//    「阶跃」是把这段斜坡人为劈成两半的产物。
//
// ## v0.41.0 的做法
//
// 不再「判定」出一个衰减结论，而是**给出结论强度**，并把稳健性检查做出来：
//
//   · **加气温模型**：y = a + b·km + c·T + d·D ⇒ 报告 b 的点估计与区间；
//   · **留一法**：去掉任意一个点重算 ⇒ 报告速率的取值范围与「显著」的次数；
//   · **空会话剔除**：没实际充电的场次（end − start < 1 个点）不是独立测量点。
//
// 三档结论（见 [DecayConclusion]）：
//
//	detectable    —— 加气温后仍显著 **且** 留一法每次都显著；
//	weak          —— 方向一致（点估计为负）但稳健性检查没过；
//	not_detectable—— 检不出。
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
// 容量 = 续航估值 × 效率常数，而续航估值受**充电时气温**影响。
// 所以结论只在这段里程内有效。

// decayPoint 一个满充测量点。
// decayMethodTmpl 电池衰减的「怎么算的」方法说明（App 的 ⓘ 与飞书共用这一句）。
//
// ⚠️ **v0.147.0**：原句 **232** 宽，里面还夹着**开发者话**
//
//	（拟合公式「y = a + b·km + c·D」、「结论强度见 conclusion 字段」这种字段名、
//	以及版本号备注「⚠ v0.41.0：…」—— 用户看到版本号只会困惑）。
//	⇒ 去掉它们（**事实一条没丢**：气温模型与留一法在 caveat 与客户端的「稳健性」两行里都有），
//	收窄成一句人话 ⇒ 78 宽 ≤ [noteLineWidthLimit]（上限在 api/app_copy.go）。
//
// ⚠️ **为什么它不在 api/app_copy.go**：那里放的是**常驻界面文案**与**宽度口径**；
//
//	这句是电池衰减模块自己的 ⓘ 文案 ⇒ 放模块里，但宽度上限仍取 app_copy.go 的那个数
//	（客户端闸门 36 跨文件比对两端的上限）。
const decayMethodTmpl = "严格档（充到 100%）按里程拟合；先剔除落差再给连续速率；显著性用 t 检验。"

type decayPoint struct {
	date  string
	km    float64
	kwh   float64
	tempC float64 // 充电时平均气温（°C）；缺失时为 0 且不参与气温模型
	hasT  bool
}

// ⚠️ **v0.41.0：结论强度**的三档。
const (
	// DecayDetectable —— 加气温后仍显著，且留一法每次都显著。
	DecayDetectable = "detectable"
	// DecayWeak —— 方向一致（点估计为负），但稳健性检查没过。
	DecayWeak = "weak"
	// DecayNotDetectable —— 检不出持续衰减趋势。
	DecayNotDetectable = "not_detectable"
	// DecayInsufficient —— 点数不够，任何结论都不给。
	DecayInsufficient = "insufficient"
)

// BatteryDecay 电池容量衰减的判定结果。
type BatteryDecay struct {
	// 参与回归的点数（严格档）
	N int `json:"n"`
	// 严格档（100% 满充）点数
	StrictN int `json:"strict_n"`
	// 未计入的非满充点数
	ExcludedN int `json:"excluded_n"`
	// ⚠️ v0.41.0：**没有实际充电**的场次数（同一场会话被 TeslaMate 拆成两段的尾巴）。
	// 那不是独立的测量点 —— 实测本机有 1 场（100%→100%、0.04 度、1 分钟）。
	DroppedEmpty int `json:"dropped_empty"`
	// 覆盖的里程跨度（公里）与天数
	SpanKm   float64 `json:"span_km"`
	SpanDays int     `json:"span_days"`

	// ── 朴素线性（整段一条直线）──────────────────────────
	NaivePer1000Km   float64 `json:"naive_per_1000km"`
	NaiveCiLo        float64 `json:"naive_ci_lo"`
	NaiveCiHi        float64 `json:"naive_ci_hi"`
	NaiveSignificant bool    `json:"naive_significant"`
	R2Linear         float64 `json:"r2_linear"`

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
	NaiveOverstate float64 `json:"naive_overstate"`

	// ── 扣掉阶跃后的连续速率 ──────────────────────────────
	UnderlyingPer1000Km   float64 `json:"underlying_per_1000km"`
	UnderlyingCiLo        float64 `json:"underlying_ci_lo"`
	UnderlyingCiHi        float64 `json:"underlying_ci_hi"`
	UnderlyingSignificant bool    `json:"underlying_significant"`

	// ── 阶跃前后两段各自的速率 ────────────────────────────
	PrePer1000Km    float64 `json:"pre_per_1000km"`
	PreSignificant  bool    `json:"pre_significant"`
	PreN            int     `json:"pre_n"`
	PostPer1000Km   float64 `json:"post_per_1000km"`
	PostSignificant bool    `json:"post_significant"`
	PostN           int     `json:"post_n"`

	// ── ⚠️ v0.41.0：气温混杂 ──────────────────────────────
	// 气温系数（度/°C，模型 y = a + b·km + c·T）
	TempPerC float64 `json:"temp_per_c"`
	// 气温能解释**朴素模型残差**的多少方差（0~1）
	TempR2 float64 `json:"temp_r2"`
	// 参与气温模型的有效点数
	TempN int `json:"temp_n"`

	// ── ⚠️ v0.41.0：加气温 + 阶跃后的连续速率 ────────────
	AdjustedPer1000Km   float64 `json:"adjusted_per_1000km"`
	AdjustedCiLo        float64 `json:"adjusted_ci_lo"`
	AdjustedCiHi        float64 `json:"adjusted_ci_hi"`
	AdjustedSignificant bool    `json:"adjusted_significant"`

	// ── ⚠️ v0.41.0：留一法稳健性 ─────────────────────────
	// 去掉任意一个点重算时，连续速率的取值范围
	LeaveOneOutMin float64 `json:"leave_one_out_min"`
	LeaveOneOutMax float64 `json:"leave_one_out_max"`
	// 留一法里「仍然显著」的次数 / 总次数
	LeaveOneOutSignificant int `json:"leave_one_out_significant"`
	LeaveOneOutTotal       int `json:"leave_one_out_total"`

	// ── ⚠️ v0.41.0：结论强度 ─────────────────────────────
	// detectable / weak / not_detectable / insufficient
	Conclusion string `json:"conclusion"`

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

// stepFit 一次「线性 + 一次阶跃」的拟合。
type stepFit struct {
	beta []float64
	se   []float64
	r2   float64
	k    int // 切点下标（-1 = 没找到）
	ok   bool
}

// fitStepModel 在序列里**枚举**所有切点，取 R² 最大者。
//
// ⚠️ **v0.41.0：这个「取最大值」本身就是一个多重比较问题 ——**
// 12 个候选切点里挑 R² 最大的，报出的 t 值**没有做多重比较校正**（胜者诅咒）。
// 所以调用方**不能**只凭这里的显著性就下结论，必须配合留一法与气温模型。
//
// ⚠️ **候选必须「真的更好」**：先算**不带阶跃**的同列模型作基线，
// 只有 R² **严格大于**基线的候选才算「检测到落差」。
// 否则一条完全平的序列会因为「取第一个成功的拟合」而被误判成有落差 ——
// v0.35.0 的回归测试 `TestBatteryDecayFlatSeriesHasNoStep` 守的就是这条。
func fitStepModel(pts []decayPoint, withTemp bool) stepFit {
	best := stepFit{k: -1}
	n := len(pts)
	if n < 6 {
		return best
	}
	mt := meanTempOf(pts)
	ys := make([]float64, n)
	for i, p := range pts {
		ys[i] = p.kwh
	}

	// 基线：同列但不含阶跃项的模型
	base := make([][]float64, n)
	for i, p := range pts {
		row := []float64{1, p.km}
		if withTemp {
			row = append(row, tempOf(p, mt))
		}
		base[i] = row
	}
	_, _, baseR2, okBase := olsFitFull(base, ys)
	if !okBase {
		return best
	}

	for k := 3; k <= n-3; k++ {
		xs := make([][]float64, n)
		for i, p := range pts {
			d := 0.0
			if i >= k {
				d = 1.0
			}
			row := []float64{1, p.km}
			if withTemp {
				row = append(row, tempOf(p, mt))
			}
			row = append(row, d)
			xs[i] = row
		}
		b, s, r2, ok := olsFitFull(xs, ys)
		if !ok {
			continue
		}
		// ⚠️ 必须**严格优于基线**才算「检测到落差」。
		if r2 <= baseR2 {
			continue
		}
		if !best.ok || r2 > best.r2 {
			best = stepFit{beta: b, se: s, r2: r2, k: k, ok: true}
		}
	}
	return best
}

// tempOf 取某点的气温；**缺失时用全体均值填补**。
//
// ⚠️ 不能当成 0°C —— 那是「0 度」的真实取值，会把缺失当成极寒天气，
// 直接把气温系数带偏。
func tempOf(p decayPoint, meanTemp float64) float64 {
	if p.hasT {
		return p.tempC
	}
	return meanTemp
}

// meanTempOf 有气温的点的均值。
func meanTempOf(pts []decayPoint) float64 {
	sum, n := 0.0, 0
	for _, p := range pts {
		if p.hasT {
			sum += p.tempC
			n++
		}
	}
	if n == 0 {
		return 0
	}
	return sum / float64(n)
}

// analyzeBatteryDecay 判定电池容量是不是「已经能看出趋势」。
//
// @param strict      严格档（end_soc == 100）且里程/容量齐全的点（按里程升序）
// @param excluded    未计入的非满充点数
// @param spanDays    数据覆盖的天数
// @param droppedEmpty 没实际充电的场次数
func analyzeBatteryDecay(strict []decayPoint, excluded int, spanDays int, droppedEmpty int) *BatteryDecay {
	out := &BatteryDecay{
		N:            len(strict),
		StrictN:      len(strict),
		ExcludedN:    excluded,
		DroppedEmpty: droppedEmpty,
		SpanDays:     spanDays,
		Method:       decayMethodTmpl,
	}
	if len(strict) >= 2 {
		out.SpanKm = strict[len(strict)-1].km - strict[0].km
	}
	if len(strict) < decayMinPoints {
		out.Conclusion = DecayInsufficient
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
		out.Conclusion = DecayInsufficient
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

	// ── 气温能解释多少残差方差（朴素模型）──────────────────
	out.TempR2 = round3(tempPartialR2(strict, beta))
	out.TempN = countWithTemp(strict)

	// ── 阶跃检测：y = a + b·km + c·D_k ────────────────────
	best := fitStepModel(strict, false)
	if best.ok && len(best.beta) == 3 {
		df3 := len(ys) - 3
		tc3 := tCrit975(df3)
		stepT := 0.0
		if best.se[2] > 0 {
			stepT = best.beta[2] / best.se[2]
		}
		out.HasStep = true
		out.StepKwh = round3(best.beta[2])
		out.StepCiLo = round3(best.beta[2] - tc3*best.se[2])
		out.StepCiHi = round3(best.beta[2] + tc3*best.se[2])
		out.StepSignificant = tSignificant(stepT, 1, tc3)
		out.StepKm = strict[best.k].km
		out.StepDate = strict[best.k].date
		out.R2Step = round3(best.r2)
		out.UnderlyingPer1000Km = round3(best.beta[1] * 1000)
		out.UnderlyingCiLo = round3((best.beta[1] - tc3*best.se[1]) * 1000)
		out.UnderlyingCiHi = round3((best.beta[1] + tc3*best.se[1]) * 1000)
		out.UnderlyingSignificant = tSignificant(best.beta[1], best.se[1], tc3)
		if best.beta[1] != 0 {
			out.NaiveOverstate = round3(math.Abs(beta[1] / best.beta[1]))
		}
		// 阶跃前后两段各自的速率
		out.PreN = best.k
		out.PostN = len(strict) - best.k
		out.PrePer1000Km, out.PreSignificant = segmentSlope(strict[:best.k])
		out.PostPer1000Km, out.PostSignificant = segmentSlope(strict[best.k:])
	}

	// ── ⚠️ v0.41.0：加气温 + 落差的模型 ────────────────────
	// y = a + b·km + c·T + d·D —— b 就是「扣掉气温影响后的连续速率」。
	if out.TempN >= 5 {
		adj := fitStepModel(strict, true)
		if adj.ok && len(adj.beta) == 4 {
			df4 := len(ys) - 4
			tc4 := tCrit975(df4)
			out.TempPerC = round3(adj.beta[2])
			out.AdjustedPer1000Km = round3(adj.beta[1] * 1000)
			out.AdjustedCiLo = round3((adj.beta[1] - tc4*adj.se[1]) * 1000)
			out.AdjustedCiHi = round3((adj.beta[1] + tc4*adj.se[1]) * 1000)
			out.AdjustedSignificant = tSignificant(adj.beta[1], adj.se[1], tc4)
		}
	}

	// ── ⚠️ v0.41.0：留一法稳健性 ─────────────────────────
	looMin, looMax, looSig, looTotal := leaveOneOut(strict)
	out.LeaveOneOutMin = round3(looMin)
	out.LeaveOneOutMax = round3(looMax)
	out.LeaveOneOutSignificant = looSig
	out.LeaveOneOutTotal = looTotal

	out.Conclusion = decayConclusion(out)
	out.Caveat = decayCaveat(out)
	return out
}

// tempPartialR2 气温能解释**朴素模型残差**的多少方差。
//
// ⚠️ 旧文案写「残差里**大部分**是充电时气温」——
// 实测 r = +0.424 ⇒ R² = 0.180（只有 18%）。这个函数把真值算出来。
func tempPartialR2(pts []decayPoint, beta []float64) float64 {
	var ts []float64
	var rs []float64
	for _, p := range pts {
		if !p.hasT {
			continue
		}
		ts = append(ts, p.tempC)
		rs = append(rs, p.kwh-(beta[0]+beta[1]*p.km))
	}
	if len(ts) < 3 {
		return 0
	}
	// 残差对气温做一元线性回归，取 R²
	meanT, meanR := 0.0, 0.0
	for i := range ts {
		meanT += ts[i]
		meanR += rs[i]
	}
	meanT /= float64(len(ts))
	meanR /= float64(len(rs))
	var sxy, sxx, syy float64
	for i := range ts {
		dt := ts[i] - meanT
		dr := rs[i] - meanR
		sxy += dt * dr
		sxx += dt * dt
		syy += dr * dr
	}
	if sxx <= 1e-12 {
		return 0
	}
	r2 := (sxy * sxy) / (sxx * syy)
	if r2 < 0 {
		return 0
	}
	return r2
}

// countWithTemp 有气温的点数。
func countWithTemp(pts []decayPoint) int {
	n := 0
	for _, p := range pts {
		if p.hasT {
			n++
		}
	}
	return n
}

// leaveOneOut 留一法：每次去掉一个点重算「扣落差后的连续速率」。
//
// 返回：速率的最小/最大值、「仍然显著」的次数、总次数。
//
// ⚠️ 这是**检验结论稳不稳**最便宜的办法 ——
// 一个「显著」的结论如果去掉任意一个点就不显著了，那它就架在刀尖上。
func leaveOneOut(pts []decayPoint) (float64, float64, int, int) {
	if len(pts) < decayMinPoints+1 {
		return 0, 0, 0, 0
	}
	mn, mx := 0.0, 0.0
	first := true
	sig, total := 0, 0
	for i := range pts {
		sub := make([]decayPoint, 0, len(pts)-1)
		sub = append(sub, pts[:i]...)
		sub = append(sub, pts[i+1:]...)
		f := fitStepModel(sub, false)
		if !f.ok || len(f.beta) != 3 {
			continue
		}
		df := len(sub) - 3
		if df < 1 {
			continue
		}
		tc := tCrit975(df)
		slope := f.beta[1] * 1000
		if first {
			mn, mx = slope, slope
			first = false
		} else {
			if slope < mn {
				mn = slope
			}
			if slope > mx {
				mx = slope
			}
		}
		total++
		lo := f.beta[1] - tc*f.se[1]
		hi := f.beta[1] + tc*f.se[1]
		if hi < 0 || lo > 0 {
			sig++
		}
	}
	return mn, mx, sig, total
}

// decayConclusion 给出**结论强度**。
//
// ⚠️ **v0.41.0：不再简单地「显著 / 不显著」两分。**
// 一个只差 0.001 就跨过 0 的区间，嘴上说「显著」是**过度承诺**。
//
// 判据（两项都过才算「区分得开」）：
// ① 加气温后区间仍然**不含 0**；
// ② 留一法**每一次**都仍然显著。
//
// 任一项没过、但点估计方向是负的（衰减方向）⇒ weak。
// 方向都不是负的 ⇒ not_detectable。
func decayConclusion(d *BatteryDecay) string {
	if d.N < decayMinPoints {
		return DecayInsufficient
	}
	adjOK := d.AdjustedSignificant && d.AdjustedCiHi < 0
	looOK := d.LeaveOneOutTotal > 0 && d.LeaveOneOutSignificant == d.LeaveOneOutTotal
	if adjOK && looOK {
		return DecayDetectable
	}
	if d.UnderlyingPer1000Km < 0 || d.AdjustedPer1000Km < 0 {
		return DecayWeak
	}
	return DecayNotDetectable
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

// decayCaveat 口径提示。
//
// ⚠️ **v0.41.0：全部改成实测值，不再有「大部分是气温」这种没有依据的说法。**
func decayCaveat(d *BatteryDecay) string {
	var parts []string
	if d.HasStep {
		if d.StepSignificant {
			parts = append(parts, fmt.Sprintf(
				"检测到一次落差：%s（%.0f 公里处）%.3f 度（95%% 区间 %.3f ~ %.3f）",
				d.StepDate, d.StepKm, d.StepKwh, d.StepCiLo, d.StepCiHi))
		} else {
			parts = append(parts, fmt.Sprintf(
				"序列里有一个疑似落差（%s，%.0f 公里处，%.3f 度），但统计上不显著，仅供参考",
				d.StepDate, d.StepKm, d.StepKwh))
		}
		parts = append(parts, "它可能不是电池变化 —— 也可能是车机重新标定了续航估值；"+
			"本 App 分不清这两者，别把它当成「电池掉了一次」")
		if d.NaiveOverstate > 1.15 {
			parts = append(parts, fmt.Sprintf(
				"⚠️ 不扣这次落差的话，表面速率会被高估 %.1f 倍（%.3f → %.3f 度/1000 公里）",
				d.NaiveOverstate, d.NaivePer1000Km, d.UnderlyingPer1000Km))
		}
	} else {
		parts = append(parts, "没有检测到一次性落差，整段按一条直线拟合")
	}

	// ⚠️ v0.41.0：把「区间离 0 有多远」和**稳健性检查**说清楚。
	if d.UnderlyingCiHi < 0 && d.UnderlyingCiHi > -0.01 {
		parts = append(parts, fmt.Sprintf(
			"扣掉落差后的连续速率区间上界是 %.4f —— 离 0 只有 %.4f，几乎区分不开",
			d.UnderlyingCiHi, -d.UnderlyingCiHi))
	}
	if d.TempN >= 5 {
		parts = append(parts, fmt.Sprintf(
			"把充电时气温放进模型后：连续速率 %.3f（区间 %.3f ~ %.3f）%s",
			d.AdjustedPer1000Km, d.AdjustedCiLo, d.AdjustedCiHi,
			ifStr(d.AdjustedSignificant && d.AdjustedCiHi < 0, "⇒ 仍然显著", "⇒ 检不出")))
		parts = append(parts, fmt.Sprintf(
			"气温系数 %+.5f 度/°C —— 20°C 的温差就值 %.3f 度，与那个落差同量级",
			d.TempPerC, math.Abs(d.TempPerC*20)))
	}
	if d.LeaveOneOutTotal > 0 {
		parts = append(parts, fmt.Sprintf(
			"留一法（去掉任意一个点重算）：速率落在 %.3f ~ %.3f，其中 %d/%d 次仍然显著",
			d.LeaveOneOutMin, d.LeaveOneOutMax, d.LeaveOneOutSignificant, d.LeaveOneOutTotal))
	}
	if d.TempN >= 3 {
		parts = append(parts, fmt.Sprintf(
			"气温只解释残差方差的 %.0f%%（不是「大部分」）；残差里还有别的东西",
			d.TempR2*100))
	}
	if d.DroppedEmpty > 0 {
		parts = append(parts, fmt.Sprintf(
			"剔除了 %d 场没有实际充电的场次（同一场会话被拆成两段的尾巴，不是独立测量点）",
			d.DroppedEmpty))
	}
	parts = append(parts, fmt.Sprintf(
		"容量口径是「续航估值 × 效率常数」（= 车机上报的续航估值 × 0.146，不是物理测量）；"+
			"结论只在这 %.0f 公里 / %d 天内有效，不能外推",
		d.SpanKm, d.SpanDays))
	return joinCN(parts, "；")
}

// ifStr 条件为真取 a，否则取 b。
func ifStr(cond bool, a, b string) string {
	if cond {
		return a
	}
	return b
}
