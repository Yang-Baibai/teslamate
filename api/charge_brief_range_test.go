package main

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
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

// ── v0.121.0：续航段的**接线**（纯函数绿 ≠ 功能上线）──────────────────
//
// ⚠️ **上一版只测了 renderRangeBriefLine（一个纯函数）⇒ 全绿**，
// 但那一版把段**只接在「没有充电记录」那个分支**里，
// 且 **writeJSON 的字面量里根本没有 Range 字段**
// ⇒ 正常窗口（有充电记录）的正文里**没有续航段**、JSON 里 range = null
// ⇒ MCP 的契约自检当场报红（线上才发现）。
// ⇒ 这三条测的是**接线**：正文有没有、两个分支有没有、响应里有没有。

// 有充电记录的窗口（正常情况）⇒ 正文**必须**带续航段。
func TestRenderChargeBriefHasRangeLineWithCharges(t *testing.T) {
	tr := 170.56772567584196
	seg := &ChargeBriefSegments{Range: &ChargeBriefRange{TrueRangeKm: &tr}}
	stats := &ChargeStatsRange{Key: "7d", Count: 3, TotalKwh: 55.4}
	_, body := renderChargeBrief(time.Local, "Asia/Shanghai", "爱车", stats, seg)
	if !strings.Contains(body, "当前真实可达") {
		t.Fatalf("有充电记录的窗口正文里必须带续航段，实际正文 = %q", body)
	}
	if !strings.Contains(body, "当前") {
		t.Fatalf("续航段必须标明「当前」（窗口无关的数），实际正文 = %q", body)
	}
}

// 没有充电记录时 ⇒ 也要说清续航段为什么没数（而不是整段消失）。
func TestRenderChargeBriefHasRangeNoteWithoutCharges(t *testing.T) {
	seg := &ChargeBriefSegments{RangeNote: "续航：行程样本不足，暂不给数"}
	stats := &ChargeStatsRange{Key: "7d", Count: 0}
	_, body := renderChargeBrief(time.Local, "Asia/Shanghai", "爱车", stats, seg)
	if !strings.Contains(body, "行程样本不足") {
		t.Fatalf("没有充电记录时也要说清续航段为什么没数，实际正文 = %q", body)
	}
}

// 响应结构体必须**真的**带上 range / range_note ⇒ 否则 MCP 读到 null（静默退化）。
func TestChargeBriefJSONCarriesRange(t *testing.T) {
	tr := 170.56772567584196
	b := &ChargeBrief{Range: &ChargeBriefRange{TrueRangeKm: &tr}, RangeNote: "note"}
	buf, err := json.Marshal(b)
	if err != nil {
		t.Fatalf("序列化失败：%v", err)
	}
	s := string(buf)
	if !strings.Contains(s, `"range":{`) {
		t.Fatalf("响应 JSON 里没有 range 对象 ⇒ MCP 读 data[\"range\"] 只能拿到空：%s", s)
	}
	if !strings.Contains(s, "true_range_km") {
		t.Fatalf("响应 JSON 里没有 true_range_km：%s", s)
	}
	if !strings.Contains(s, `"range_note":"note"`) {
		t.Fatalf("响应 JSON 里没有 range_note：%s", s)
	}
}
