package main

import (
	"context"
	"errors"
	"fmt"
	"math"
	"net/http"
	"strconv"
	"sync"
	"time"
)

// ⚠️ **v0.33.0（第 20 轮爬升能耗建模评审）：爬升能耗系数。**
//
// ## 为什么要有这个端点
//
// 「爬升修正」原来在客户端是 `if (ascentM >= 200) 1.05 else 1.0` ——
// **二值饱和**（200 m 与 2,571 m 同价）而且是**乘性**。
// 评审用本车 99 趟实测证明：
//
// - 必须是**加性**：重力位能 m·g·h 是**绝对量**，与车速、与基础 Wh/km 无关；
//   实测交互项 −0.007、R² 只涨 0.0002，分速带斜率跨 20~80 km/h 恒为 5.6~6.9。
// - 上坡系数 **c_up ≈ 6.4 Wh/m**（n=99、R²=0.67）。
// - 下坡回充系数 **c_regen ≈ 3.2 Wh/m**，纯下坡样本只有 5 趟 → **不能宣称「本车实测」**。
//
// ⚠️ **v0.34.0（第 21 轮口径审计 + 独立复算）：这套系数已**降级为交叉核对**。**
//
// 独立复算发现两个硬伤：
//
// ① **口径不同源** —— 这里的系数是**车轮功率毛耗电**口径，
//    而客户端的能耗底档是**标定法净值**口径。混用会三重重复计量
//    （回充扣两次、该档典型爬升算两次、既上又下时给净系数），
//    实测**高估 +12.08%**、造成 **4 次结论翻转**。
//    → 客户端现在只准用 `climb_calib.go` 里的**标定法口径**系数。
// ② **系数没有物理解释** —— `drives.ascent` **不是物理高差**
//    （能量自洽性实测算出 **142% > 100%**，物理不可能）。
//    → 「隐含传动效率 78%」这句话**不成立**，本文件头注释里的
//    「隐含传动效率 78% —— 落在物理区间内」已作废。
//
// ⚠️ 本文件头的「R² 仅 0.22、分裂半 2.72~4.37（±23%）」也是**过期数字**
// （当前数据是 R²=0.694、分裂半 2.94~3.36）—— 已一并订正。
//
// ## 为什么必须在服务端算
//
// ① `positions.power` 有 162 万行，客户端不该拉；
// ② **口径只能有一处** —— 第 20 轮充电评审 P0-5 的教训：
//    「服务端与客户端两套算法会漂移」曾让同一个数在列表页和详情页差一分钟。
//
// ## 口径
//
// 毛耗电 = Σ `max(P,0) × Δt`；回充 = Σ `max(−P,0) × Δt`。
// 两者**物理上互斥**（回充只可能来自下降），所以分开拟合**不需要**靠回归去分离信号。
// 段级对齐残差 se=42 Wh/段（远大于爬升信号），所以**不**用段级回归。
//
// 回归式：`Wh/km ~ 1 + kph + 爬升(m/km) + 时长(h)`
//
// ⚠️ **v0.36.0（第 21 轮口径审计 P1-7）：「时长项用来控制空调负载」这个解释是**错的**，订正。**
//
// 恒功率空调的每公里贡献 = `P_hvac × h / km = P_hvac / kph` ——
// 应该在 `1/kph` 上，**不是** `h`。
// 而且实测**不支持**那个改写：标定法口径下换 `1/kph` 后 R² 从 0.8198 掉到 0.8014，
// 系数 t = 0.83（不显著）。
//
// `h` 项真正在做的是**摊销每趟的固定开销**（空调、怠速、掉电…）：
// 它在 `kph` 之上只带来 ΔR² = +0.0086；去掉 `kph` 后只剩 +0.0016。
//
// ⚠️ **还有一个必须说清的限制**：模型里同时有 `kph` 与 `h`，而 `km = kph × h` ——
// **两者不独立**。这意味着模型在「固定里程、改变速度」的外推上没有物理意义：
// 实测固定 50 km 时 60 km/h → 162.4 Wh/km、120 km/h → 134.3 Wh/km（−17.4%），
// 与空气阻力方向相反。所以这套系数**只能当经验标定值**用，
// 且**不用于外推**（只用于「本车实测样本的范围内」的分档修正）。
//
// ⚠️ 另注：本文件头早期版本写「R² 仅 0.22、分裂半 2.72~4.37（±23%）」——
// 那是**过期数字**（当前是 R² = 0.694、分裂半 2.94~3.36）。

// MassKg 本车质量（kg）。只用于**物理自洽性校验**：
// m·g = 1850 × 9.81 / 3600 = 5.042 Wh/m。
// 隐含效率 = 5.042 / c_up，正常应在 70%~95%（电机+逆变+电池往返效率）。
const MassKg = 1850.0

// MgWhPerM = m·g（Wh/m）：把 1 米高度变化换算成电。
const MgWhPerM = MassKg * 9.81 / 3600.0

// 样本量门槛
const (
	climbMinDrives      = 30 // 上坡系数可宣称「本车实测」的最小行程数
	climbMinPureDown    = 8  // 回充系数可宣称「本车实测」的最小**纯下坡**行程数
	climbPureGradeMPerK = 5.0 // 「纯上坡 / 纯下坡」的坡度门槛（m/km）
	climbPureOtherMPerK = 1.5 // 「纯上坡 / 纯下坡」时另一侧的最大坡度（m/km）
)

// handleClimbModel 返回本车的爬升能耗系数。
//
// 端点：`GET /api/v1/cars/{car_id}/climb_model?min_km=20`
//
// ⚠️ **耗时**：实测首次 **约 5 秒**（要扫 162 万个 positions 做 LAG 窗口 + 分组）。
// 所以结果**按车缓存 30 分钟** —— 爬升系数只在新行程入库后才会变，
// 30 分钟的延迟完全可接受，而客户端不能等 5 秒。
func handleClimbModel(w http.ResponseWriter, r *http.Request) {
	carID, err := strconv.Atoi(r.PathValue("car_id"))
	if err != nil {
		writeErr(w, http.StatusBadRequest, "car id 无效")
		return
	}
	minKm := 20.0
	if v := r.URL.Query().Get("min_km"); v != "" {
		if f, err := strconv.ParseFloat(v, 64); err == nil && f >= 5 && f <= 500 {
			minKm = f
		}
	}

	// ── 缓存 ────────────────────────────────────────────
	key := fmt.Sprintf("%d:%.0f", carID, minKm)
	climbCacheMu.Lock()
	hit, ok := climbCache[key]
	climbCacheMu.Unlock()
	if ok && time.Since(hit.at) < climbCacheTTL {
		writeJSON(w, hit.model)
		return
	}

	model, err := computeClimbModel(carID, minKm)
	if err != nil {
		if err == errClimbNotEnough {
			writeErr(w, http.StatusBadRequest, err.Error())
			return
		}
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	climbCacheMu.Lock()
	climbCache[key] = climbCacheEntry{at: time.Now(), model: model}
	climbCacheMu.Unlock()
	writeJSON(w, model)
}

var errClimbNotEnough = errors.New("样本不足，算不出爬升系数")

// 缓存：按 (car_id, min_km) 缓存 30 分钟。
var (
	climbCacheMu   sync.Mutex
	climbCache     = map[string]climbCacheEntry{}
	climbCacheTTL  = 30 * time.Minute
)

type climbCacheEntry struct {
	at    time.Time
	model ClimbModel
}

// computeClimbModel 真正算系数（见 handleClimbModel 的说明）。
func computeClimbModel(carID int, minKm float64) (ClimbModel, error) {
	rows, err := db.Query(context.Background(), sqlClimbDrives, carID, minKm)
	if err != nil {
		return ClimbModel{}, err
	}
	defer rows.Close()

	type driveRow struct {
		km, minutes       float64
		ascentM, descentM float64
		grossKwh          float64
		regenKwh          float64
	}
	var list []driveRow
	for rows.Next() {
		var d driveRow
		var driveID, ascent, descent int
		if err := rows.Scan(&driveID, &d.km, &d.minutes, &ascent, &descent, &d.grossKwh, &d.regenKwh); err != nil {
			return ClimbModel{}, err
		}
		d.ascentM = float64(ascent)
		d.descentM = float64(descent)
		list = append(list, d)
	}
	if err := rows.Err(); err != nil {
		return ClimbModel{}, err
	}
	if len(list) < 3 {
		return ClimbModel{}, fmt.Errorf("%w：只有 %d 趟符合条件的行程", errClimbNotEnough, len(list))
	}

	// ── 拟合 ──────────────────────────────────────────────
	// 回归式：Wh/km ~ 1 + kph + 坡度(m/km) + 时长(h)
	//   系数[2] 就是「每米坡度多耗/多回多少 Wh/km」。
	//
	// ⚠️ **v0.36.0（第 21 轮口径审计 P1-6）：同时算**标准误与 95% 置信区间**。**
	//
	// 原来只上报**分裂半**（两半样本各拟合一次）——
	// 但分裂半只是**一次**实现（两半样本构成差），**不是估计量方差**：
	// c_up 的分裂半是 ±2.3%，而真实抽样不确定度是 **±14.5%**（SE 0.4762）。
	// 只报分裂半会让读者以为精度好 6 倍。
	fitGross := func(sub []driveRow) ([]float64, []float64, float64, bool) {
		var xs [][]float64
		var ys []float64
		for _, d := range sub {
			if d.grossKwh <= 0.01 || d.km <= 0.1 || d.minutes <= 0 {
				continue
			}
			kph := d.km / (d.minutes / 60.0)
			xs = append(xs, []float64{1, kph, d.ascentM / d.km, d.minutes / 60.0})
			ys = append(ys, d.grossKwh/d.km*1000.0)
		}
		return olsFitFull(xs, ys)
	}
	fitRegen := func(sub []driveRow) ([]float64, []float64, float64, bool) {
		var xs [][]float64
		var ys []float64
		for _, d := range sub {
			if d.regenKwh <= 0.01 || d.km <= 0.1 || d.minutes <= 0 {
				continue
			}
			kph := d.km / (d.minutes / 60.0)
			xs = append(xs, []float64{1, kph, d.descentM / d.km, d.minutes / 60.0})
			ys = append(ys, d.regenKwh/d.km*1000.0)
		}
		return olsFitFull(xs, ys)
	}

	bUp, seUp, r2Up, okUp := fitGross(list)
	bDn, seDn, r2Dn, okDn := fitRegen(list)
	if !okUp || !okDn {
		return ClimbModel{}, errors.New("爬升系数拟合失败（样本共线或不足）")
	}
	cUp := bUp[2]    // Wh/m（正数 = 上坡每米多耗）
	cRegen := bDn[2] // Wh/m（正数 = 下坡每米回充抵扣）
	// ⚠️ 符号说明：回充回归的目标变量是**回充能量**，
	// 而自变量是**下降坡度** —— 下降越多、回充越多，所以系数**本身是正的**。
	// 使用时是「**减去** c_regen × 下降坡度」。
	//
	// 隐含效率：
	//   上坡：消耗 > 势能（效率 < 1）→ η = m·g ÷ c_up
	//   下坡：回收 < 势能（效率 < 1）→ η = c_regen ÷ m·g
	// ⚠️ 两者方向相反，写反了就会得到 >100% 的荒谬效率（本次就踩过一次）。

	// ── 纯上坡 / 纯下坡样本 ──────────────────────────────
	pureUp, pureDown := 0, 0
	for _, d := range list {
		if d.km <= 0.1 {
			continue
		}
		ga, gd := d.ascentM/d.km, d.descentM/d.km
		if ga >= climbPureGradeMPerK && gd <= climbPureOtherMPerK {
			pureUp++
		}
		if gd >= climbPureGradeMPerK && ga <= climbPureOtherMPerK {
			pureDown++
		}
	}

	// ── 分裂半稳定性 ────────────────────────────────────
	// 按 id 奇偶分半重拟合：两个半样本给出的系数若差很多，说明模型不稳。
	var halfA, halfB []driveRow
	for i, d := range list {
		if i%2 == 0 {
			halfA = append(halfA, d)
		} else {
			halfB = append(halfB, d)
		}
	}
	var splitUp, splitRegen []float64
	for _, h := range [][]driveRow{halfA, halfB} {
		bu, _, _, ok := fitGross(h)
		bd, _, _, ok2 := fitRegen(h)
		if ok {
			splitUp = append(splitUp, bu[2])
		}
		if ok2 {
			splitRegen = append(splitRegen, bd[2])
		}
	}

	upTrustworthy := len(list) >= climbMinDrives
	regenTrustworthy := pureDown >= climbMinPureDown

	// ⚠️ **v0.34.0（第 21 轮口径审计 P0-2 + 独立复算）：并入标定法口径的系数。**
	//
	// 上面那套（车轮功率毛耗电口径）**不再发给客户端当修正用** ——
	// 客户端的能耗底档是标定法净值口径，混用会三重重复计量。
	// 保留它只作**物理交叉核对**。
	calib, err := computeClimbCalib(carID)
	if err != nil {
		// 标定法口径样本不足时**不阻断**（车轮口径那套仍然可用作交叉核对），
		// 但客户端会因为 basis 为空而**不给**修正 —— 宁可少算，不混口径。
		calib = ClimbModel{Basis: ""}
	}

	out := ClimbModel{
		CUpWhPerM:            round2(cUp),
		CRegenWhPerM:         round2(cRegen),
		ImpliedEtaUp:         round3(MgWhPerM / math.Abs(cUp)),
		ImpliedEtaRegen:      round3(math.Abs(cRegen) / MgWhPerM),
		NDrive:               len(list),
		NPureUp:              pureUp,
		NPureDown:            pureDown,
		R2Gross:              round3(r2Up),
		R2Regen:              round3(r2Dn),
		SplitHalfCUp:         roundAll(splitUp),
		SplitHalfCRegen:      roundAll(splitRegen),
		MinKm:                minKm,
		CUpTrustworthy:       upTrustworthy,
		CRegenTrustworthy:    regenTrustworthy,
		Method:               "口径 calib_net：y = (Δ标定续航) × cars.efficiency × 1000 / 里程（与客户端能耗底档同源）；回归式 wh_per_km ~ 1 + 车速 + 上坡坡度(m/km) + 下坡坡度(m/km) + 时长(h)；修正用 delta-grade（相对该车速档的典型坡度）。⚠️ 车速项与时长项不独立（km = kph × h），模型不可外推；时长项做的是摊销每趟固定开销，不是空调。另有车轮功率毛耗电口径（positions.power 积分）作物理交叉核对。",
		Caveat:               climbCaveat(upTrustworthy, regenTrustworthy, pureDown),
		ConservativeCRegen:   round2(conservativeRegen(splitRegen, cRegen, regenTrustworthy)),
		ImpliedEtaNote:       "⚠️ 隐含效率**不可信**：drives.ascent 不是物理高差（能量自洽性实测 142% > 100%），系数只能当经验标定值看，不要解读成传动效率。",
		// ⚠️ **v0.36.0（第 21 轮口径审计 P1-6）：补上标准误与 95% 置信区间。**
		// 原来只上报分裂半（±2.3%），而真实抽样不确定度是 ±14.5% —— 差 6 倍。
		CUpSE:       round3(seUp[2]),
		CRegenSE:    round3(seDn[2]),
		CUpCI95:     ci95Of(bUp[2], seUp[2], len(list)-len(bUp)),
		CRegenCI95:  ci95Of(bDn[2], seDn[2], len(list)-len(bDn)),
		// ── v0.34.0：标定法口径（客户端唯一该用的那套） ──
		Basis:              calib.Basis,
		CUpCalibNet:        calib.CUpCalibNet,
		CRegenCalibNet:     calib.CRegenCalibNet,
		CUpCalibNetSE:      calib.CUpCalibNetSE,
		CRegenCalibNetSE:   calib.CRegenCalibNetSE,
		CUpCalibNetCI95:    calib.CUpCalibNetCI95,
		CRegenCalibNetCI95: calib.CRegenCalibNetCI95,
		NCalib:             calib.NCalib,
		R2Calib:            calib.R2Calib,
		TypicalGrade:       calib.TypicalGrade,
	}
	return out, nil
}

// ci95Of 系数的 95% 置信区间（[下界, 上界]）。
func ci95Of(beta, se float64, df int) []float64 {
	if df < 1 {
		return nil
	}
	tc := tCrit975(df)
	return []float64{round3(beta - tc*se), round3(beta + tc*se)}
}

// conservativeRegen 回充系数的**保守取值**。
//
// 评审的判断：「宁可高估回充、别高估上坡」——
// 实际上「少抵扣回充 → 多报耗电 → 偏安全」，所以取**下界**。
// 回充系数不可信时（纯下坡样本不足），取分裂半的下界。
func conservativeRegen(split []float64, fitted float64, trustworthy bool) float64 {
	if trustworthy {
		return fitted
	}
	if len(split) == 0 {
		return fitted
	}
	lo := split[0]
	for _, v := range split {
		if v < lo {
			lo = v
		}
	}
	if lo <= 0 {
		return fitted
	}
	return lo
}

func climbCaveat(upOK, regenOK bool, pureDown int) string {
	var parts []string
	if upOK {
		// ⚠️ **v0.34.0（第 21 轮独立复算）：不能再宣称「隐含传动效率在物理区间内」。**
		// 独立复算的能量自洽性显示 `drives.ascent` **不是物理高差**（算出 142% > 100%，
		// 物理不可能）—— 所以「m·g ÷ c_up」得到的效率数字**没有物理意义**。
		// 系数只能当**经验标定值**看。
		parts = append(parts, "上坡系数为本车标定值（经验值，非物理效率）")
	} else {
		parts = append(parts, "上坡系数样本不足，按保守值处理")
	}
	if regenOK {
		parts = append(parts, "回充系数为本车标定值")
	} else {
		parts = append(parts, "回充系数纯下坡样本仅 "+strconv.Itoa(pureDown)+
			" 趟（<8），按分裂半下界的保守值处理，**不是纯实测**")
	}
	parts = append(parts, "坡度取 drives.ascent/descent 原样，**不是物理高差**；客户端修正用 delta 口径（相对该车速档的典型坡度）")
	return joinCN(parts, "；")
}

func joinCN(parts []string, sep string) string {
	out := ""
	for i, p := range parts {
		if i > 0 {
			out += sep
		}
		out += p
	}
	return out
}

// olsFit 解普通最小二乘：最小化 ‖Xb − y‖²。
//
// 用正规方程 (X'X) b = X'y + 高斯消元（部分主元）。
// 样本量只有 ~100、预测子 4 个，正规方程的数值稳定性足够
// （评审的独立复算用同样的方法，结果逐位吻合）。
func olsFit(xs [][]float64, ys []float64) ([]float64, float64, bool) {
	n := len(xs)
	if n < len(xs[0])+1 || n != len(ys) {
		return nil, 0, false
	}
	k := len(xs[0])
	// X'X
	xtx := make([][]float64, k)
	for a := 0; a < k; a++ {
		xtx[a] = make([]float64, k)
		for b := 0; b < k; b++ {
			s := 0.0
			for i := 0; i < n; i++ {
				s += xs[i][a] * xs[i][b]
			}
			xtx[a][b] = s
		}
	}
	// X'y
	xty := make([]float64, k)
	for a := 0; a < k; a++ {
		s := 0.0
		for i := 0; i < n; i++ {
			s += xs[i][a] * ys[i]
		}
		xty[a] = s
	}
	// 高斯消元
	m := make([][]float64, k)
	for i := 0; i < k; i++ {
		m[i] = make([]float64, k+1)
		copy(m[i], xtx[i])
		m[i][k] = xty[i]
	}
	for c := 0; c < k; c++ {
		piv := c
		for r := c + 1; r < k; r++ {
			if math.Abs(m[r][c]) > math.Abs(m[piv][c]) {
				piv = r
			}
		}
		if math.Abs(m[piv][c]) < 1e-9 {
			return nil, 0, false
		}
		m[c], m[piv] = m[piv], m[c]
		for r := 0; r < k; r++ {
			if r == c {
				continue
			}
			f := m[r][c] / m[c][c]
			for cc := c; cc <= k; cc++ {
				m[r][cc] -= f * m[c][cc]
			}
		}
	}
	b := make([]float64, k)
	for i := 0; i < k; i++ {
		b[i] = m[i][k] / m[i][i]
	}
	// R²
	ybar := 0.0
	for _, v := range ys {
		ybar += v
	}
	ybar /= float64(n)
	ssRes, ssTot := 0.0, 0.0
	for i := 0; i < n; i++ {
		p := 0.0
		for j := 0; j < k; j++ {
			p += xs[i][j] * b[j]
		}
		ssRes += (ys[i] - p) * (ys[i] - p)
		ssTot += (ys[i] - ybar) * (ys[i] - ybar)
	}
	r2 := 0.0
	if ssTot > 0 {
		r2 = 1 - ssRes/ssTot
	}
	return b, r2, true
}
