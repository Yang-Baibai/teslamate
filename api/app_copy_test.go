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
