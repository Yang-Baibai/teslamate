package main

import (
	"fmt"
	"strings"
	"testing"
)

// TestCopyVisualWidthSameAsClient 宽度口径与客户端棘轮一致（汉字/全角=2，半角=1，空格不计）。
func TestCopyVisualWidthSameAsClient(t *testing.T) {
	cases := []struct {
		s    string
		want int
	}{
		{"", 0},
		{"车内", 4},
		{"a b", 2}, // 空格不算
		{"50.0", 4},
		{"℃", 1}, // U+2103 不在汉字/全角区间 ⇒ 按半角算（客户端同）
		{"，", 2}, // 全角标点
		{"⇒", 1}, // U+21D2 同上
		{"·", 1}, // U+00B7 同上
	}
	for _, c := range cases {
		if got := copyVisualWidth(c.s); got != c.want {
			t.Fatalf("copyVisualWidth(%q) = %d，期望 %d", c.s, got, c.want)
		}
	}
}

// TestAppVisibleCopyWithinWidthLimit 服务端**常驻**印在 App 上的文案不许超宽。
//
// ⚠️ 这正是 v0.142.0 那条 78 宽度长句溜过去的地方：
// 客户端啰嗦棘轮只扫 .kt ⇒ 服务端拼出来的超长文案没人拦。
func TestAppVisibleCopyWithinWidthLimit(t *testing.T) {
	// ① 座舱闷热文案（App 座舱卡常驻渲染）
	inside := 50.0
	outside := 30.0
	ch := cabinHeat(&inside, &outside, nil, false)
	if ch == nil || ch.Text == "" {
		t.Fatalf("座舱闷热文案不该为空：%+v", ch)
	}
	if w := copyVisualWidth(ch.Text); w > appCopyWidthLimit {
		t.Fatalf("座舱闷热文案宽度 %d > %d：%q", w, appCopyWidthLimit, ch.Text)
	}

	// ② 车外温度读不到时的那句（App 续航卡常驻渲染，v0.144.0 收窄过）
	if w := copyVisualWidth(staleOutsideTempCaveat); w > appCopyWidthLimit {
		t.Fatalf("车外温度口径宽度 %d > %d：%q", w, appCopyWidthLimit, staleOutsideTempCaveat)
	}

	// ③ 低温分支的口径（同样会常驻印出来）
	m := &rangeModel{LowTempSamples: 3}
	low := 5.0
	cav := m.tempCaveat(&low)
	if w := copyVisualWidth(cav); w > appCopyWidthLimit {
		t.Fatalf("低温口径宽度 %d > %d：%q", w, appCopyWidthLimit, cav)
	}
}

// TestStaleCaveatKeepsKeyFacts 收窄**不许**丢事实（宁可短，不可没说清）。
func TestStaleCaveatKeepsKeyFacts(t *testing.T) {
	for _, must := range []string{"读不到", "气温修正", "偏乐观"} {
		if !strings.Contains(staleOutsideTempCaveat, must) {
			t.Fatalf("收窄后丢了关键事实 %q：%q", must, staleOutsideTempCaveat)
		}
	}
}

// TestTempCaveatTemplatesMatchThresholds 模板里写死的阈值不许与判据漂移。
//
// ⚠️ v0.146.0：模板搬进 app_copy.go 后，里面的「≤10°C」是**写死的**
// （要能跨文件逐字比对，就不能是 fmt 出来的）⇒ 得钉住它与 rangeTempLowC 一致。
func TestTempCaveatTemplatesMatchThresholds(t *testing.T) {
	want := fmt.Sprintf("≤%.0f°C", rangeTempLowC)
	for _, tmpl := range []string{tempCaveatLowUntrustedTmpl, tempCaveatNoLowCorrectionTmpl} {
		if !strings.Contains(tmpl, want) {
			t.Fatalf("温度口径模板里的阈值与判据不一致（模板写死 %q、判据 %v）：%q", want, rangeTempLowC, tmpl)
		}
		if w := copyVisualWidth(tmpl); w > appCopyWidthLimit {
			t.Fatalf("温度口径模板宽度 %d > %d：%q", w, appCopyWidthLimit, tmpl)
		}
	}
}

// TestTempCaveatRenderedWithinWidth 渲染出来（含样本条数）也不许超宽。
//
// ⚠️ 模板宽度是「样本条数按 %d 算」的 ⇒ 真填上数字可能更宽
// ⇒ 这里按真实的条数渲染一遍再量（低温条数实测只有个位数，128 是留给未来的余量）。
func TestTempCaveatRenderedWithinWidth(t *testing.T) {
	for _, n := range []int{1, 3, 128} {
		s := fmt.Sprintf(tempCaveatLowUntrustedTmpl, n)
		if w := copyVisualWidth(s); w > appCopyWidthLimit {
			t.Fatalf("低温口径（%d 条样本）宽度 %d > %d：%q", n, w, appCopyWidthLimit, s)
		}
		s2 := fmt.Sprintf(tempCaveatNoLowCorrectionTmpl, n)
		if w := copyVisualWidth(s2); w > appCopyWidthLimit {
			t.Fatalf("未做低温修正口径（%d 条样本）宽度 %d > %d：%q", n, w, appCopyWidthLimit, s2)
		}
	}
}

// assertNoteLinesWithinWidth 量一段服务端下发文案的**每一行**（按「；」拆行，客户端同规则）。
//
// ⚠️ 客户端拆行用的也是「；」（ui/BatteryDecay.kt 的 detailLines）⇒ 两端同一个拆法。
// ⚠️ 用 Errorf 不用 Fatalf ⇒ 一次把所有超限的行都报出来（不是一个一个试）。
func assertNoteLinesWithinWidth(t *testing.T, name, text string) {
	t.Helper()
	if strings.TrimSpace(text) == "" {
		return
	}
	for _, c := range strings.Split(text, "；") {
		c = strings.TrimSpace(c)
		if c == "" {
			continue
		}
		if w := copyVisualWidth(c); w > noteLineWidthLimit {
			t.Errorf("[%s] 分句宽度 %d > %d：%q", name, w, noteLineWidthLimit, c)
		}
	}
}

// TestDecayNoteLinesWithinWidth 服务端下发的 ⓘ 文案：每一行一个事实、每行不许超宽。
//
// ⚠️ v0.147.0 实测：caveat 整段 **750** 宽（12 个话题用「；」挤成一段）⇒ 展开是一堵字墙；
//
//	而客户端的 ⓘ 预算只扫客户端调用点的字面量 ⇒ 服务端这一半没人管 ⇒ 这道单测补上。
//
// ⚠️ **v0.169.0：这一版从「一条路径」扩到「每一条条件分支」。**
//
//	v0.147.0 那版只摆了一个样本（有落差 / 落差显著）⇒ decayCaveat 的**其余分支**
//	（没有落差 / 落差不显著 / 不触发高估 / 区间上界贴着 0 / 没有气温样本 /
//	  气温样本不足 / 没有留一法 / 没有空会话 / 字段最少）
//	**从没被任何判据跑过** ⇒ 「闸门跑绿 ≠ 它在检查东西」（与 ADR #69 的盲区同一类）。
//	⇒ 现在把每一条条件分支都摆出来逐条量。
//
// ⚠️ **顺带把 analyzeBatteryDecay 的两条提前返回也钉上** ——
//
//	那两句（点数不足 / 线性拟合失败）也是**服务端下发**的 caveat，
//	同样会进 App 的 ⓘ ⇒ 同样要守行宽。
func TestDecayNoteLinesWithinWidth(t *testing.T) {
	// 基准值取**线上真实形状**（2026-11-12 那份）：改动会让某个分支不触发。
	base := func() *BatteryDecay {
		return &BatteryDecay{
			N: 18, StrictN: 18, ExcludedN: 12, DroppedEmpty: 1,
			SpanKm: 10104.457, SpanDays: 90,
			NaivePer1000Km: -0.104, NaiveCiLo: -0.162, NaiveCiHi: -0.047,
			NaiveSignificant: true, R2Linear: 0.478,
			HasStep: true, StepKm: 96791.508598, StepDate: "2026-09-28",
			StepKwh: -0.7, StepCiLo: -1.078, StepCiHi: -0.323, StepSignificant: true,
			R2Step: 0.744, NaiveOverstate: 5.446,
			UnderlyingPer1000Km: -0.019, UnderlyingCiLo: -0.081, UnderlyingCiHi: 0.043,
			UnderlyingSignificant: false,
			TempPerC:              0.011, TempR2: 0.151, TempN: 18,
			AdjustedPer1000Km: -0.023, AdjustedCiLo: -0.083, AdjustedCiHi: 0.037,
			AdjustedSignificant: false,
			LeaveOneOutMin:      -0.037, LeaveOneOutMax: -0.004,
			LeaveOneOutSignificant: 0, LeaveOneOutTotal: 18,
		}
	}
	mut := func(f func(*BatteryDecay)) *BatteryDecay {
		d := base()
		f(d)
		return d
	}

	cases := []struct {
		name string
		d    *BatteryDecay
	}{
		{"线上真实形状", base()},
		{"没有落差", mut(func(d *BatteryDecay) { d.HasStep = false })},
		{"落差存在但不显著", mut(func(d *BatteryDecay) { d.StepSignificant = false })},
		{"落差没被高估（不触发那条）", mut(func(d *BatteryDecay) { d.NaiveOverstate = 1.05 })},
		{"区间上界贴着 0", mut(func(d *BatteryDecay) {
			d.UnderlyingCiHi = -0.0056
			d.UnderlyingCiLo = -0.08
		})},
		{"没有气温样本", mut(func(d *BatteryDecay) {
			d.TempN = 0
			d.TempPerC = 0
			d.TempR2 = 0
		})},
		{"气温样本不足 5 条", mut(func(d *BatteryDecay) { d.TempN = 3 })},
		{"没有留一法", mut(func(d *BatteryDecay) {
			d.LeaveOneOutTotal = 0
			d.LeaveOneOutSignificant = 0
		})},
		{"没有空会话", mut(func(d *BatteryDecay) { d.DroppedEmpty = 0 })},
		{"字段最少", &BatteryDecay{N: 8, StrictN: 8}},
	}
	for _, c := range cases {
		assertNoteLinesWithinWidth(t, c.name, decayCaveat(c.d))
	}

	// ⚠️ analyzeBatteryDecay 的两条提前返回（也是服务端下发的 caveat）
	assertNoteLinesWithinWidth(t, "点数不足", analyzeBatteryDecay(nil, 0, 0, 0, 0).Caveat)
	collinear := make([]decayPoint, 8)
	for i := range collinear {
		collinear[i] = decayPoint{date: "2026-01-01", km: 100, kwh: 60}
	}
	assertNoteLinesWithinWidth(t, "线性拟合失败", analyzeBatteryDecay(collinear, 0, 0, 10, 0).Caveat)

	// 方法说明（也是服务端下发、进 App ⓘ 的那一行）
	assertNoteLinesWithinWidth(t, "方法说明", decayMethodTmpl)
}
