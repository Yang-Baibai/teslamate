package main

import (
	"strings"
	"testing"
)

// ⚠️ **v0.119.0 补记**：简报里的**续航段**（当前值）的渲染规则。
//
// ⚠️ 这一段是「**现在**能开多远」⇒ 与简报的窗口**无关**
// ⇒ 文案里**必须**写「当前」，否则会被读成「近 30 天能开 171 公里」
// —— 那是一个我们**没算过**的数（反幻觉，同 ADR #46 的规矩）。
func TestRenderRangeBriefLine(t *testing.T) {
	if got := renderRangeBriefLine(nil); got != "" {
		t.Fatalf("nil ⇒ 应为空行，实际 %q", got)
	}
	// 没有真实可达（样本不足）⇒ 不给数 ⇒ 空行（不许拿标称冒充）。
	tr := 170.56772567584196
	fl := 113.71181711722797
	lo, hi := 151, 197
	flo, fhi := 100, 131
	empty := &ChargeBriefRange{}
	if got := renderRangeBriefLine(empty); got != "" {
		t.Fatalf("没有真实可达 ⇒ 应为空行，实际 %q", got)
	}

	r := &ChargeBriefRange{
		TrueRangeKm:    &tr,
		TrueRangeLoKm:  &lo,
		TrueRangeHiKm:  &hi,
		FloorRangeKm:   &fl,
		FloorRangeLoKm: &flo,
		FloorRangeHiKm: &fhi,
		BucketLabel:    "0–60",
		SampleCount:    92,
	}
	got := renderRangeBriefLine(r)
	want := "续航：当前真实可达 171 公里 · 约 151 ~ 197 公里 · 留 20% 保底能开 114 公里（约 100 ~ 131 公里）"
	if got != want {
		t.Fatalf("文案不对：\n got  %q\n want %q", got, want)
	}
	// ⚠️ 「当前」两个字必须有。
	if !strings.Contains(got, "当前") {
		t.Fatalf("续航段没有「当前」二字 ⇒ 窗口无关的当前值会被读成窗口内的量：%q", got)
	}
	// ⚠️ 不许出现箭头（趋势符号）—— 我们**没下**任何方向结论。
	if strings.Contains(got, "→") {
		t.Fatalf("续航段出现了箭头「→」⇒ 那是趋势符号，而我们没下这个结论：%q", got)
	}
}
