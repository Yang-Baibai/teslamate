package main

import (
	"context"
	"fmt"
	"math"
	"sort"
)

// ⚠️ **v0.34.0（第 21 轮口径审计 P0-2 + 独立复算）：标定法口径的爬升系数。**
//
// ## 为什么要有第二套系数
//
// v0.33.0 的 c_up=6.43 / c_regen=3.22 是在**车轮功率毛耗电**口径上拟合的
// （positions.power × Δt 积分），而客户端的能耗底档
// `whSample(...).bucket.whPerKm` 来自**标定法净值**口径
// （(Δrated_range) × efficiency × 1000 / km，回充**已抵扣**）。
//
// **两个口径混用会三重重复计量**：
//
// ① 回充被扣两次 —— 底档已是净值（回充已抵扣），公式又减一次 c_regen × 下降坡度；
// ② 该档的**典型爬升**被算两次 —— 底档本身建自那些趟次，公式再叠**绝对**坡度；
// ③ 同段既上又下时给 (c_up − c_regen) × grade，而正确增量应基于
//
//	**相对该档典型坡度**的增量。
//
// 独立复算实测（客户端真实可用样本 88 趟，真值 = 同口径标定法 Wh/km）：
//
//	不加修正          偏差  −3.62%   RMSE 55.35   结论翻转 1
//	旧口径公式        偏差 +12.08%   RMSE 37.13   结论翻转 4   ← 高估
//	新口径 delta      偏差  −0.84%   RMSE 24.12   结论翻转 0
//
// ## 公式
//
//	whEff(段) = whBase(该段速度档)
//	          + c_up_cal   × (g_up(段) − g_up_typ(该档))
//	          − c_regen_cal × (g_dn(段) − g_dn_typ(该档))
//
// ## ⚠️ 系数**没有物理解释**
//
// 独立复算发现：`drives.ascent` **不是物理高差** ——
// 被审计段（206 km / 毛爬升 4186 m / 净爬升 1506 m）的能量自洽性算出 **142%**
// （超出 100% 的势能上限），物理上不可能。
// 所以 `c_up ÷ (m·g)` 得到的「隐含传动效率 78%」**不成立**，
// 系数只能当**经验标定值**，必须以「口径 + 置信区间 + 样本量」呈现，
// 不得宣称物理效率。

// 客户端 `ConsumptionModel.EDGES` —— 必须与客户端**逐条一致**。
var climbSpeedEdges = []int{0, 60, 90, 110, 130, 0}

// climbCalibRow 一趟行程的拟合输入。
type climbCalibRow struct {
	driveID                     int
	km, minutes                 float64
	avgSpeedKph                 float64
	ascentM, descentM           float64
	whPerKm                     float64
}

// computeClimbCalib 拟合**标定法口径**的系数与每档基准坡度。
func computeClimbCalib(carID int) (ClimbModel, error) {
	rows, err := db.Query(context.Background(), sqlClimbCalib, carID)
	if err != nil {
		return ClimbModel{}, err
	}
	defer rows.Close()

	var list []climbCalibRow
	for rows.Next() {
		var r climbCalibRow
		var ascent, descent int
		if err := rows.Scan(&r.driveID, &r.km, &r.minutes, &ascent, &descent, &r.avgSpeedKph, &r.whPerKm); err != nil {
			return ClimbModel{}, err
		}
		r.ascentM = float64(ascent)
		r.descentM = float64(descent)
		list = append(list, r)
	}
	if err := rows.Err(); err != nil {
		return ClimbModel{}, err
	}
	if len(list) < 20 {
		return ClimbModel{}, fmt.Errorf("%w：标定法口径只有 %d 趟可用行程", errClimbNotEnough, len(list))
	}

	// ── 回归：wh_per_km ~ 1 + kph + g_up + g_dn + h ──────────
	var xs [][]float64
	var ys []float64
	for _, d := range list {
		if d.km <= 0.1 || d.minutes <= 0 || d.avgSpeedKph <= 0 {
			continue
		}
		xs = append(xs, []float64{
			1,
			d.avgSpeedKph,
			d.ascentM / d.km,
			d.descentM / d.km,
			d.minutes / 60.0,
		})
		ys = append(ys, d.whPerKm)
	}
	beta, se, r2, ok := olsFitFull(xs, ys)
	if !ok {
		return ClimbModel{}, fmt.Errorf("%w：标定法回归失败（共线或样本不足）", errClimbNotEnough)
	}
	df := len(ys) - len(beta)
	if df < 1 {
		return ClimbModel{}, fmt.Errorf("%w：标定法回归自由度不足", errClimbNotEnough)
	}
	tc := tCrit975(df)
	cUp := beta[2]
	cRegen := beta[3]
	ciUp := []float64{round3(cUp - tc*se[2]), round3(cUp + tc*se[2])}
	ciRegen := []float64{round3(cRegen - tc*se[3]), round3(cRegen + tc*se[3])}

	// ── 每档基准坡度（delta 的基准） ────────────────────────
	// 口径：该档**所有**可用趟次的 ascent/km、descent/km 的**中位数**。
	typ := climbTypicalGrades(list)

	return ClimbModel{
		Basis:             "calib_net",
		CUpCalibNet:       round3(cUp),
		CRegenCalibNet:    round3(cRegen),
		CUpCalibNetSE:     round3(se[2]),
		CRegenCalibNetSE:  round3(se[3]),
		CUpCalibNetCI95:   ciUp,
		CRegenCalibNetCI95: ciRegen,
		NCalib:            len(ys),
		R2Calib:           round3(r2),
		TypicalGrade:      typ,
	}, nil
}

// climbTypicalGrades 每车速档的典型坡度（delta 基准）。
//
// 边界与客户端 `ConsumptionModel.EDGES` 完全一致（0/60/90/110/130/开区间）。
func climbTypicalGrades(list []climbCalibRow) []TypicalGrade {
	var out []TypicalGrade
	for i := 0; i < len(climbSpeedEdges)-1; i++ {
		lo := climbSpeedEdges[i]
		hi := climbSpeedEdges[i+1]
		var ups, downs []float64
		for _, d := range list {
			if d.km <= 0.1 {
				continue
			}
			inBucket := false
			if hi == 0 { // 末档：开区间
				inBucket = d.avgSpeedKph >= float64(lo)
			} else {
				inBucket = d.avgSpeedKph >= float64(lo) && d.avgSpeedKph < float64(hi)
			}
			if !inBucket {
				continue
			}
			ups = append(ups, d.ascentM/d.km)
			downs = append(downs, d.descentM/d.km)
		}
		if len(ups) < 2 {
			continue
		}
		maxKph := hi
		if hi == 0 {
			maxKph = 0 // 开区间
		}
		out = append(out, TypicalGrade{
			MinKph:        lo,
			MaxKph:        maxKph,
			GradeUpTyp:    round3(medianOf(ups)),
			GradeDownTyp:  round3(medianOf(downs)),
			N:             len(ups),
		})
	}
	return out
}

// medianOf 中位数（偶数样本取两个中间值的平均，与客户端 `medianOf` 同口径）。
func medianOf(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	s := append([]float64(nil), values...)
	sort.Float64s(s)
	n := len(s)
	if n%2 == 1 {
		return s[n/2]
	}
	return (s[n/2-1] + s[n/2]) / 2
}

// tCrit975 自由度 df 下双侧 95% 的 t 临界值。
//
// 用 Cornish-Fisher 展开（正态分位数 + 高阶修正）。
// 实测精度：df=10 → 2.2281（真值 2.228）、df=15 → 2.1314（2.131）、
// df=30 → 2.0423（2.042）、df=100 → 1.9840（1.984）。df ≥ 5 时足够准。
func tCrit975(df int) float64 {
	if df < 1 {
		return 0
	}
	z := 1.959963984540054
	d := float64(df)
	g1 := (z*z*z + z) / (4 * d)
	g2 := (5*math.Pow(z, 5) + 16*math.Pow(z, 3) + 3*z) / (96 * d * d)
	g3 := (3*math.Pow(z, 7) + 19*math.Pow(z, 5) + 17*math.Pow(z, 3) - 15*z) / (384 * d * d * d)
	g4 := (79*math.Pow(z, 9) + 776*math.Pow(z, 7) + 1482*math.Pow(z, 5) - 1920*math.Pow(z, 3) - 945*z) / (92160 * d * d * d * d)
	return z + g1 + g2 + g3 + g4
}

// olsFitFull 解 OLS 并返回**标准误**。
//
// SE_i = σ · sqrt((X'X)⁻¹_ii)，σ² = SSE / (n − k)。
func olsFitFull(xs [][]float64, ys []float64) ([]float64, []float64, float64, bool) {
	b, r2, ok := olsFit(xs, ys)
	if !ok {
		return nil, nil, 0, false
	}
	n := len(ys)
	k := len(xs[0])
	ssRes := 0.0
	for i := 0; i < n; i++ {
		p := 0.0
		for j := 0; j < k; j++ {
			p += xs[i][j] * b[j]
		}
		d := ys[i] - p
		ssRes += d * d
	}
	sigma2 := 0.0
	if n > k {
		sigma2 = ssRes / float64(n-k)
	}
	xtx := make([][]float64, k)
	for a := 0; a < k; a++ {
		xtx[a] = make([]float64, k)
		for c := 0; c < k; c++ {
			s := 0.0
			for i := 0; i < n; i++ {
				s += xs[i][a] * xs[i][c]
			}
			xtx[a][c] = s
		}
	}
	inv, ok2 := invertMatrix(xtx)
	if !ok2 {
		return b, nil, r2, false
	}
	se := make([]float64, k)
	for i := 0; i < k; i++ {
		if inv[i][i] > 0 {
			se[i] = math.Sqrt(sigma2 * inv[i][i])
		}
	}
	return b, se, r2, true
}

// invertMatrix 高斯-约当法求逆（部分主元）。
func invertMatrix(m [][]float64) ([][]float64, bool) {
	k := len(m)
	if k == 0 {
		return nil, false
	}
	a := make([][]float64, k)
	for i := 0; i < k; i++ {
		a[i] = make([]float64, 2*k)
		copy(a[i], m[i])
		a[i][k+i] = 1
	}
	for c := 0; c < k; c++ {
		piv := c
		for r := c + 1; r < k; r++ {
			if math.Abs(a[r][c]) > math.Abs(a[piv][c]) {
				piv = r
			}
		}
		if math.Abs(a[piv][c]) < 1e-12 {
			return nil, false
		}
		a[c], a[piv] = a[piv], a[c]
		d := a[c][c]
		for j := 0; j < 2*k; j++ {
			a[c][j] /= d
		}
		for r := 0; r < k; r++ {
			if r == c {
				continue
			}
			f := a[r][c]
			if f == 0 {
				continue
			}
			for j := 0; j < 2*k; j++ {
				a[r][j] -= f * a[c][j]
			}
		}
	}
	inv := make([][]float64, k)
	for i := 0; i < k; i++ {
		inv[i] = make([]float64, k)
		copy(inv[i], a[i][k:])
	}
	return inv, true
}
