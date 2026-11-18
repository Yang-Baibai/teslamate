package main

import (
	"fmt"
	"math"
	"strings"
	"testing"
)

// iPtr 造一个 *int（测试夹具用）。
//
// ⚠️ **f64 不用在这里定义** —— telemetry_state_test.go 已有全包共用的那一个。
func iPtr(v int) *int { return &v }

// ⚠️ **v0.174.0**：实测满电续航的服务端折算护栏。
//
// ## 这些测试在钉什么
//
// ① **口径不许漂**：阈值（掉电 20 个百分点 / 物理可行性 1.5 倍）与
//    排序规则（同时长按时间）都在服务端一份 —— 客户端那份已删。
// ② **假恭维不许复活**（v0.40.0 的教训）：实测低于官方时必须报**低**。
// ③ **不许给半截数**：算不出来就是 caveat + 没有 median，不是 0。

// fullChargeFixture 复刻客户端旧算法里那几段真实数据的关键形状。
//
// ⚠️ 段 1：100% → 8%，跑了 300 公里 ⇒ 折算 326.09 公里/满电（合理）。
// ⚠️ 段 2：100% → 8%，跑了 **900 公里** ⇒ 折算 977 公里/满电
//
//	⇒ 官方标称 448 × 1.5 = 672 ⇒ **物理上不可能** ⇒ 必须被剔除。
//
// ⚠️ 段 3：100% → 95%，跑了 20 公里 ⇒ 掉电只有 5 个百分点 ⇒ 必须被剔除。
func fullChargeFixture() []fullChargeRow {
	return []fullChargeRow{
		{ // 第 1 场：充到 100%
			StartOdoKm: f64(1000.0),
			EndOdoKm:   f64(1000.0),
			StartSoc:   iPtr(50),
			EndSoc:     iPtr(100),
			EndRangeKm: f64(448.01),
		},
		{ // 第 2 场：下一次充电开始（8%）
			StartOdoKm: f64(1300.0),
			EndOdoKm:   f64(1300.0),
			StartSoc:   iPtr(8),
			EndSoc:     iPtr(100),
			EndRangeKm: f64(448.01),
		},
		{ // 第 3 场：物理上不可能的那段（掉电 92%，900 公里）
			StartOdoKm: f64(2200.0),
			EndOdoKm:   f64(2200.0),
			StartSoc:   iPtr(8),
			EndSoc:     iPtr(100),
			EndRangeKm: f64(448.01),
		},
		{ // 第 4 场：掉电只有 5 个百分点的短途
			StartOdoKm: f64(2220.0),
			EndOdoKm:   f64(2220.0),
			StartSoc:   iPtr(95),
			EndSoc:     iPtr(100),
			EndRangeKm: f64(448.01),
		},
	}
}

// TestFullChargeThresholdsArePinned 阈值**必须**被单测引用 ——
//
// ⚠️ **v0.174.0**：客户端闸门 45 要求这两个阈值出现在单测里。
// 理由：阈值写死成字面量（20 / 1.5）时，改了阈值单测**不会红**
// ⇒ 阈值就成了没人管的数字 ⇒ 而它直接决定「哪些段被剔除」。
// ⇒ 这里断言「夹具里的段在当前阈值下被剔除」⇒ 阈值一改，这里立刻红。
func TestFullChargeThresholdsArePinned(t *testing.T) {
	if fullChargeMinSocUsed != 20 {
		t.Fatalf("掉电不足的阈值应是 20 个百分点，实得 %d", fullChargeMinSocUsed)
	}
	if fullChargeFeasibleRatio != 1.5 {
		t.Fatalf("物理可行性的倍数应是 1.5，实得 %v", fullChargeFeasibleRatio)
	}
	// ⚠️ **公里数要反推**：公里数 = 官方标称 × 掉电% ÷ 100。
	//   （否则会自己撞上「物理可行性」那条判据 —— 那是另一个测试在管的事。）
	//   官方标称 448 × 掉电 19% ÷ 100 ≈ 85 公里。
	official := 448.0
	kmFor := func(socUsed int) float64 { return official * float64(socUsed) / 100.0 }

	// 掉电 19 个百分点（刚好不到阈值）⇒ 必须被剔除。
	rows := []fullChargeRow{
		{StartOdoKm: f64(1000), EndOdoKm: f64(1000), StartSoc: iPtr(50), EndSoc: iPtr(100), EndRangeKm: f64(official)},
		{StartOdoKm: f64(1000 + kmFor(19)), EndOdoKm: f64(1000 + kmFor(19)), StartSoc: iPtr(81), EndSoc: iPtr(100), EndRangeKm: f64(official)},
	}
	if segs := buildFullChargeSegments(rows, &official); len(segs) != 0 {
		t.Fatalf("掉电 %d 个百分点（< %d）不该算，实得 %d 段",
			100-81, fullChargeMinSocUsed, len(segs))
	}
	// 掉电 20 个百分点（刚好等于阈值）⇒ 算。
	rows[1].StartSoc = iPtr(80)
	rows[1].StartOdoKm = f64(1000 + kmFor(20))
	rows[1].EndOdoKm = f64(1000 + kmFor(20))
	if segs := buildFullChargeSegments(rows, &official); len(segs) != 1 {
		t.Fatalf("掉电刚好 %d 个百分点该算，实得 %d 段", fullChargeMinSocUsed, len(segs))
	}
	// 折算满电刚好等于官方标称 × 1.5 ⇒ 物理可行（判据是「超过」才剔除）。
	//
	// ⚠️ **公里数与掉电要同时对上**：掉电 100 个百分点时，
	//   公里数 = 官方标称 × 1.5 = 672 公里 ⇒ 折算正好是官方标称的 1.5 倍。
	//   （用 20 个百分点的话，公里数只有 90 ⇒ 折算远不到 1.5 倍，测不到这条判据。）
	rows = []fullChargeRow{
		{StartOdoKm: f64(1000), EndOdoKm: f64(1000), StartSoc: iPtr(0), EndSoc: iPtr(100), EndRangeKm: f64(official)},
		{StartOdoKm: f64(1000 + official*fullChargeFeasibleRatio), EndOdoKm: f64(1000 + official*fullChargeFeasibleRatio), StartSoc: iPtr(0), EndSoc: iPtr(100), EndRangeKm: f64(official)},
	}
	if segs := buildFullChargeSegments(rows, &official); len(segs) != 1 {
		t.Fatalf("折算刚好等于官方标称 × %v 该算，实得 %d 段", fullChargeFeasibleRatio, len(segs))
	}
	// 再多 1 公里 ⇒ 超过 1.5 倍 ⇒ 物理上不可能 ⇒ 必须被剔除。
	rows[1].StartOdoKm = f64(1000 + official*fullChargeFeasibleRatio + 1.0)
	rows[1].EndOdoKm = rows[1].StartOdoKm
	if segs := buildFullChargeSegments(rows, &official); len(segs) != 0 {
		t.Fatalf("折算超过官方标称 × %v 该剔除，实得 %d 段", fullChargeFeasibleRatio, len(segs))
	}
}

// TestFullChargeSegmentsKeepsRealAndDropsImpossible 两条剔除判据都要生效。
func TestFullChargeSegmentsKeepsRealAndDropsImpossible(t *testing.T) {
	segs := buildFullChargeSegments(fullChargeFixture(), f64(448.01))
	if len(segs) != 1 {
		t.Fatalf("应只剩 1 段（另两段分别被物理可行性/掉电不足剔除），实得 %d 段", len(segs))
	}
	s := segs[0]
	if s.Km < 299.9 || s.Km > 300.1 {
		t.Errorf("保留段的公里数应是 300，实得 %v", s.Km)
	}
	if s.FromSoc != 100 || s.ToSoc != 8 || s.SocUsed != 92 {
		t.Errorf("电量口径错了：from=%d to=%d used=%d", s.FromSoc, s.ToSoc, s.SocUsed)
	}
	// 折算：300 ÷ 92 × 100
	want := 300.0 / 92.0 * 100.0
	if s.FullCharge < want-0.01 || s.FullCharge > want+0.01 {
		t.Errorf("折算满电里程应约 %.4f，实得 %.4f", want, s.FullCharge)
	}
}

// TestFullChargeDropsShortTrip 掉电不足 20 个百分点的段必须被剔除。
func TestFullChargeDropsShortTrip(t *testing.T) {
	rows := []fullChargeRow{
		{StartOdoKm: f64(1000), EndOdoKm: f64(1000), StartSoc: iPtr(50), EndSoc: iPtr(100), EndRangeKm: f64(448)},
		{StartOdoKm: f64(1050), EndOdoKm: f64(1050), StartSoc: iPtr(95), EndSoc: iPtr(100), EndRangeKm: f64(448)},
	}
	segs := buildFullChargeSegments(rows, f64(448))
	if len(segs) != 0 {
		t.Fatalf("掉电只有 5 个百分点的段不该算，实得 %d 段", len(segs))
	}
}

// TestFullChargeDropsTinyKm 差几公里的「段」没有意义（挪车也会产生）。
func TestFullChargeDropsTinyKm(t *testing.T) {
	rows := []fullChargeRow{
		{StartOdoKm: f64(1000), EndOdoKm: f64(1000), StartSoc: iPtr(50), EndSoc: iPtr(100), EndRangeKm: f64(448)},
		{StartOdoKm: f64(1003), EndOdoKm: f64(1003), StartSoc: iPtr(20), EndSoc: iPtr(100), EndRangeKm: f64(448)},
	}
	segs := buildFullChargeSegments(rows, f64(448))
	if len(segs) != 0 {
		t.Fatalf("只跑了 3 公里的段不该算，实得 %d 段", len(segs))
	}
}

// TestFullChargeSameOdometerSortedByTime ⚠️ **v0.28.0 的教训不许复活**。
//
// 实测 ids 43/44 的里程**完全相同**（一次插枪被切成的两段）。
// 若排序不按时间 ⇒ 续航段会配错 ⇒ 折算出一个物理上不可能的数。
func TestFullChargeSameOdometerSortedByTime(t *testing.T) {
	// 两场充电里程相同、时间不同：先开始的那场先结束。
	rows := []fullChargeRow{
		{StartOdoKm: f64(1000), EndOdoKm: f64(1000), StartSoc: iPtr(50), EndSoc: iPtr(68), EndRangeKm: f64(448)},
		{StartOdoKm: f64(1000), EndOdoKm: f64(1000), StartSoc: iPtr(74), EndSoc: iPtr(100), EndRangeKm: f64(448)},
	}
	// 第二场里程相同 ⇒ 段里程为 0 ⇒ 必然被丢弃（km ≤ 5）。
	segs := buildFullChargeSegments(rows, f64(448))
	if len(segs) != 0 {
		t.Fatalf("里程相同的两次充电不该产生有效段，实得 %d 段", len(segs))
	}
}

// TestOfficialFullChargeRangeKm 官方标称取「充到 ≥99% 的场次」的车机额定续航中位数。
func TestOfficialFullChargeRangeKm(t *testing.T) {
	rows := []fullChargeRow{
		{EndSoc: iPtr(100), EndRangeKm: f64(448.01)},
		{EndSoc: iPtr(99), EndRangeKm: f64(400.00)},
		{EndSoc: iPtr(80), EndRangeKm: f64(999.00)}, // 没充满 ⇒ 不算
		{EndSoc: iPtr(100), EndRangeKm: nil},        // 车端没报 ⇒ 不算
	}
	official := officialFullChargeRangeKm(rows)
	if official == nil {
		t.Fatal("应能算出官方标称")
	}
	want := (448.01 + 400.00) / 2.0
	if *official < want-0.001 || *official > want+0.001 {
		t.Errorf("官方标称应是中位数 %.4f，实得 %.4f", want, *official)
	}
}

// TestOfficialFullChargeRangeKmNil 没有任何满充点 ⇒ nil（不是 0）。
func TestOfficialFullChargeRangeKmNil(t *testing.T) {
	rows := []fullChargeRow{
		{EndSoc: iPtr(80), EndRangeKm: f64(448.01)},
	}
	if officialFullChargeRangeKm(rows) != nil {
		t.Fatal("没有满充点时官方标称应是 nil")
	}
}

// TestFullChargeVsOfficialPercentDirection ⚠️ **v0.40.0 的教训（假恭维不许复活）**。
//
// 本车真实数据：中位 346.62 公里 / 官方标称 448.01 公里 ⇒ 实测**低 23%**。
// 旧算法（比值×100）会报成「实测高 77%」—— 一个让 App 看起来比官方
// **强 77%**、而实际**弱 23%** 的假恭维。
func TestFullChargeVsOfficialPercentDirection(t *testing.T) {
	pct := fullChargeVsOfficialPercent(346.6238, 448.01)
	if pct == nil {
		t.Fatal("应算出差百分比")
	}
	if *pct != -23 {
		t.Errorf("实测低于官方时应报 -23（实测低 23%%），实得 %d", *pct)
	}
}

// TestFullChargeVsOfficialPercentRegressionProof 旧算法的回归证明。
func TestFullChargeVsOfficialPercentRegressionProof(t *testing.T) {
	oldWrong := int(math.Round(346.6238 / 448.01 * 100.0))
	if oldWrong != 77 {
		t.Fatalf("旧算法应算出 77（这就是它错的地方），实得 %d", oldWrong)
	}
	new := fullChargeVsOfficialPercent(346.6238, 448.01)
	if new == nil || *new == oldWrong {
		t.Fatal("新算法必须与旧算法不同（方向反了）")
	}
}

// TestFullChargeVsOfficialPercentHigher 实测高于官方时如实报高。
func TestFullChargeVsOfficialPercentHigher(t *testing.T) {
	pct := fullChargeVsOfficialPercent(560.0, 448.0)
	if pct == nil || *pct != 25 {
		t.Fatalf("实测高于官方时应报 25，实得 %v", pct)
	}
}

// TestFullChargeVsOfficialPercentRoundsAwayFromZero ⚠️ 取整必须「远离零」。
//
// Kotlin 的 roundToInt 是「四舍五入远离零」（-22.5 → -23）；
// Go 的 math.Round 是「四舍五入到偶数」（-22.5 → -22）⇒ 差 1。
func TestFullChargeVsOfficialPercentRoundsAwayFromZero(t *testing.T) {
	// 中位 / 官方 = 0.775 ⇒ -22.5%
	official := 448.0
	median := official * 0.775
	pct := fullChargeVsOfficialPercent(median, official)
	if pct == nil || *pct != -23 {
		t.Fatalf("-22.5%% 应取成 -23（远离零），实得 %v", pct)
	}
}

// TestFullChargeVsOfficialPercentInvalid 非法输入返回 nil（不是 0）。
func TestFullChargeVsOfficialPercentInvalid(t *testing.T) {
	if fullChargeVsOfficialPercent(346.0, 0.0) != nil {
		t.Error("官方标称 0 应返回 nil")
	}
	if fullChargeVsOfficialPercent(346.0, -1.0) != nil {
		t.Error("官方标称为负应返回 nil")
	}
	if fullChargeVsOfficialPercent(0.0, 448.0) != nil {
		t.Error("实测为 0 应返回 nil")
	}
}

// TestFullChargeVsOfficialPercentEqual 正好等于官方时差是 0。
func TestFullChargeVsOfficialPercentEqual(t *testing.T) {
	pct := fullChargeVsOfficialPercent(448.0, 448.0)
	if pct == nil || *pct != 0 {
		t.Fatalf("正好等于官方时差应是 0，实得 %v", pct)
	}
}

// TestFullChargeNoteWidth 服务端下发的常驻说明**整句**不许超宽（上限 44）。
//
// ⚠️ **v0.174.0**：整句 = 「基于 N 段实测 · 官方标称 X 公里，实测低 Y%」——
// 客户端原来自己拼后半截，被啰嗦棘轮（客户端闸门 5）当场抓到。
// ⇒ 现在整句由服务端给 ⇒ **服务端**要守住 44 这个上限。
func TestFullChargeNoteWidth(t *testing.T) {
	// 最坏情况：三位数段数 + 三位数官方标称 + 两位数百分比。
	note := strings.Replace(fullChargeRangeNote, "%d", "143", 1)
	note += strings.Replace(fullChargeRangeNoteOfficial, "%d", "448", 1)
	note += strings.Replace(fullChargeRangeNoteDiffLow, "%d", "23", 1)
	if w := copyVisualWidth(note); w > appCopyWidthLimit {
		t.Errorf("常驻说明宽度 %d > %d：%q", w, appCopyWidthLimit, note)
	}
	// 「实测高」那一档也要量。
	note2 := strings.Replace(fullChargeRangeNote, "%d", "43", 1)
	note2 += strings.Replace(fullChargeRangeNoteOfficial, "%d", "448", 1)
	note2 += strings.Replace(fullChargeRangeNoteDiffHigh, "%d", "25", 1)
	if w := copyVisualWidth(note2); w > appCopyWidthLimit {
		t.Errorf("常驻说明（高）宽度 %d > %d：%q", w, appCopyWidthLimit, note2)
	}
}

// TestFullChargeCaveatWidth caveat 也会进界面 ⇒ 同样不许超宽。
func TestFullChargeCaveatWidth(t *testing.T) {
	if w := copyVisualWidth(fullChargeCaveat); w > appCopyWidthLimit {
		t.Errorf("caveat 宽度 %d > %d：%q", w, appCopyWidthLimit, fullChargeCaveat)
	}
}

// TestFullChargeSpreadFromRealData ⚠️ **「市区与高速差 1.7 倍」不许再硬编码**。
//
// ⚠️ **v0.175.0 实测**：那是个**具体的分布断言**（随数据变化）
// ⇒ 实测「最好/中位」已经是 **1.7562**（该写 1.8 倍）⇒ 那句话早就在说错，
// 而没有任何判据会红。
// ⇒ 现在这个倍数必须**由真实段数据算出来**。
func TestFullChargeSpreadFromRealData(t *testing.T) {
	segs := []FullChargeSegment{
		{FullCharge: 200.0},
		{FullCharge: 300.0},
		{FullCharge: 400.0},
	}
	// 最好 400 ÷ 中位 300 = 1.333… ⇒ 一位小数 = 1.3
	if got := fullChargeSpread(segs, 300.0); got != "1.3" {
		t.Fatalf("倍数应按真实数据算成 1.3，实得 %q", got)
	}
	// 中位 = 0 ⇒ 不给数（不编）
	if got := fullChargeSpread(segs, 0.0); got != "" {
		t.Fatalf("中位为 0 时不该给倍数，实得 %q", got)
	}
	// 样本不足 ⇒ 不给数
	if got := fullChargeSpread(segs[:1], 300.0); got != "" {
		t.Fatalf("样本不足时不该给倍数，实得 %q", got)
	}
}

// TestFullChargeScopeNoteHasNoHardcodedRatio ⓘ 口径里不许再写死倍数。
//
// ⚠️ **v0.175.0**：客户端原来那句「市区与高速差 **1.7 倍**」是**硬编码**的
// ⇒ 真实值已经变成 1.7562 ⇒ 那句话早就在说错。
// ⇒ 现在倍数是**填进去的** ⇒ 模板里不许出现任何写死的倍数。
func TestFullChargeScopeNoteHasNoHardcodedRatio(t *testing.T) {
	if strings.Contains(fullChargeScopeNoteTmpl, "1.7") {
		t.Errorf("口径模板里不许写死倍数：%q", fullChargeScopeNoteTmpl)
	}
	if !strings.Contains(fullChargeScopeNoteTmpl, "%s 倍") {
		t.Errorf("口径模板应留一个填倍数的槽位：%q", fullChargeScopeNoteTmpl)
	}
	// 填进去之后必须是一句能读的话
	note := strings.Replace(fullChargeScopeNoteTmpl, "%s", "1.8", 1)
	if !strings.Contains(note, "最好与中位差 1.8 倍") {
		t.Errorf("填好倍数的口径句不对：%q", note)
	}
}

// TestFullChargeNoteLinesWithinWidth 服务端下发的三段 ⓘ 文案：每行 ≤ 96。
//
// ⚠️ **v0.175.0**：这三段现在由服务端下发 ⇒ 进界面 ⇒ 受 noteLineWidthLimit 约束。
func TestFullChargeNoteLinesWithinWidth(t *testing.T) {
	notes := map[string]string{
		"method_note": fmt.Sprintf(fullChargeMethodNote, 449),
		"read_note":   fullChargeReadNote,
		"scope_note":  strings.Replace(fullChargeScopeNoteTmpl, "%s", "1.8", 1),
	}
	for name, text := range notes {
		for _, line := range strings.Split(text, "\n") {
			if w := copyVisualWidth(line); w > noteLineWidthLimit {
				t.Errorf("%s 有一行宽 %d > %d：%q", name, w, noteLineWidthLimit, line)
			}
		}
	}
}

// TestFullChargeMethodNoteKeepsKeyFacts 收窄也不许丢关键事实。
func TestFullChargeMethodNoteKeepsKeyFacts(t *testing.T) {
	note := fmt.Sprintf(fullChargeMethodNote, 449)
	for _, must := range []string{"折算", "下坡回充", "偏乐观", "规划"} {
		if !strings.Contains(note, must) {
			t.Errorf("折算口径丢了关键事实 %q：%q", must, note)
		}
	}
}

// TestFullChargeCaveatKeepsKeyFacts caveat 收窄也不许丢关键事实。
func TestFullChargeCaveatKeepsKeyFacts(t *testing.T) {
	for _, must := range []string{"官方标称", "满电续航"} {
		if !strings.Contains(fullChargeCaveat, must) {
			t.Errorf("caveat 丢了关键事实 %q：%q", must, fullChargeCaveat)
		}
	}
}
