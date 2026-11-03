package main

import (
	"math"
	"strings"
	"testing"
)

// ⚠️ **v0.123.0**：保养 / 轮胎提醒的**判定与文案**在服务端
// （api/maintenance.go）⇒ 这里钉住它的关键行为。
//
// 三条最要紧的：
// ① **车端里程表读不到就不报**（v0.75.0 的教训：拿 GPS 累计值套 ⇒
//    会报「保养超期 38,422 公里」的**假告警**，误报方向最危险）；
// ② **阈值只有一处**（2000 公里算「快到了」）；
// ③ **文案不许暗示方向**（趋势 / ↑ ↓ →）。

// maintF64 取 float64 的地址（**不叫 f64** —— telemetry_state_test.go 已占用那个名字）。
func maintF64(v float64) *float64 { return &v }

// ① 车端里程表读不到 ⇒ **不报**（宁可不说，也不误报）。
func TestMaintenanceOdometerUnknownReportsNothing(t *testing.T) {
	it := maintenanceItem("保养", 60000, 20000, nil)
	if it != nil {
		t.Fatalf("车端里程表读不到时不该给判定，却给了 %+v", it)
	}
}

// 车端里程表读到的数不合理（<= 0）⇒ 同样不报。
func TestMaintenanceOdometerNonPositiveReportsNothing(t *testing.T) {
	for _, v := range []float64{0, -1} {
		if it := maintenanceItem("保养", 60000, 20000, maintF64(v)); it != nil {
			t.Fatalf("车端里程表 = %v 时不该给判定，却给了 %+v", v, it)
		}
	}
}

// 没填基准（0）⇒ 不报（不制造噪音）。
func TestMaintenanceNoBaselineReportsNothing(t *testing.T) {
	if it := maintenanceItem("保养", 0, 20000, maintF64(101455)); it != nil {
		t.Fatalf("没填基准时不该给判定，却给了 %+v", it)
	}
}

// 正常情况：剩余公里数 = 到期里程表 − 当前车端里程表。
func TestMaintenanceRemainingKm(t *testing.T) {
	// 上次保养 60000，间隔 20000 ⇒ 该在 80000 时做；现在 101455 ⇒ 超期 21455。
	it := maintenanceItem("保养", 60000, 20000, maintF64(101455))
	if it == nil {
		t.Fatal("该给判定却没给")
	}
	if it.DueOdoKm != 80000 {
		t.Fatalf("到期里程表 = %v，应为 80000", it.DueOdoKm)
	}
	if math.Abs(it.RemainingKm-(-21455)) > 0.001 {
		t.Fatalf("剩余公里数 = %v，应为 -21455", it.RemainingKm)
	}
	if !it.Overdue {
		t.Fatal("剩余为负 ⇒ 应判超期")
	}
	if it.DueSoon {
		t.Fatal("已超期时不该同时判「快到了」")
	}
	if it.Text != "已超期 21455 公里" {
		t.Fatalf("文案 = %q，应为「已超期 21455 公里」", it.Text)
	}
}

// ② 阈值：**正好 2000 公里**算「快到了」；2001 公里不算。
//
// ⚠️ 这条阈值只在服务端有一份（ADR #38）⇒ Hermes 闸门 13 会核对客户端一致。
func TestMaintenanceDueSoonThreshold(t *testing.T) {
	// 上次保养 60000，间隔 20000 ⇒ 该在 80000 时做。
	// 现在 78000 ⇒ 还剩 2000 ⇒ 正好卡在阈值上 ⇒ 算「快到了」。
	it := maintenanceItem("保养", 60000, 20000, maintF64(78000))
	if it == nil || !it.DueSoon {
		t.Fatalf("还剩 2000 公里（正好阈值）⇒ 应判「快到了」，却得到 %+v", it)
	}
	// 现在 77999 ⇒ 还剩 2001 ⇒ 不算。
	it2 := maintenanceItem("保养", 60000, 20000, maintF64(77999))
	if it2 == nil || it2.DueSoon {
		t.Fatalf("还剩 2001 公里（刚过阈值）⇒ 不该判「快到了」，却得到 %+v", it2)
	}
	if it2.Overdue {
		t.Fatal("还没到期不该判超期")
	}
	if it2.Text != "还有 2001 公里到期" {
		t.Fatalf("文案 = %q，应为「还有 2001 公里到期」", it2.Text)
	}
}

// 阈值常量就是 2000（闸门要核对客户端那份）。
func TestMaintenanceDueSoonConstant(t *testing.T) {
	if maintenanceDueSoonKm != 2000 {
		t.Fatalf("maintenanceDueSoonKm = %d，应为 2000", maintenanceDueSoonKm)
	}
}

// ③ 文案：**不许暗示方向**（趋势 / ↑ ↓ →）。
func TestMaintenanceTextHasNoDirection(t *testing.T) {
	for _, odo := range []float64{101455, 78000, 79999, 80000} {
		it := maintenanceItem("保养", 60000, 20000, maintF64(odo))
		if it == nil {
			continue
		}
		for _, bad := range []string{"↑", "↓", "→", "↗", "↘"} {
			if strings.Contains(it.Text, bad) || strings.Contains(it.BannerText, bad) {
				t.Fatalf("文案里出现了方向符号「%s」⇒ %q / %q", bad, it.Text, it.BannerText)
			}
		}
	}
}

// 横幅文案：**不重复当前里程表**（v0.20.0 车主 P1-1 ——
// 同一屏里一字不差地出现两次同一个数，用户会以为是两个数）。
func TestMaintenanceBannerDoesNotRepeatCurrentOdometer(t *testing.T) {
	odo := 101455.0
	it := maintenanceItem("保养", 60000, 20000, &odo)
	if it == nil {
		t.Fatal("该给判定却没给")
	}
	// 横幅里出现的是**到期里程表**（80000），不是当前里程表（101455）。
	if strings.Contains(it.BannerText, "101,455") {
		t.Fatalf("横幅里重复了当前里程表：%q", it.BannerText)
	}
	if !strings.Contains(it.BannerText, "80,000") {
		t.Fatalf("横幅里应有到期里程表 80,000：%q", it.BannerText)
	}
	if !strings.Contains(it.BannerText, "该在里程表") {
		t.Fatalf("横幅里应说清「该在里程表多少时做」：%q", it.BannerText)
	}
}

// 千分位格式（与 App 的 fmtThousands 同一口径）。
func TestFormatThousands(t *testing.T) {
	cases := map[int64]string{
		0: "0", 123: "123", 1234: "1,234",
		80000: "80,000", 101455: "101,455", 1234567: "1,234,567",
	}
	for in, want := range cases {
		if got := formatThousands(in); got != want {
			t.Fatalf("formatThousands(%d) = %q，应为 %q", in, got, want)
		}
	}
}

// 两项都没设置 ⇒ **不编一句话**（宁可说不知道）。
func TestMaintenanceSummaryEmptyWhenNothingSet(t *testing.T) {
	if s := renderMaintenanceSummary(nil, nil); s != "" {
		t.Fatalf("两项都没设置时 summary 应为空，却得到 %q", s)
	}
}

// 一项设置 ⇒ summary 恰好是那一句。
func TestMaintenanceSummarySingleItem(t *testing.T) {
	svc := maintenanceItem("保养", 60000, 20000, maintF64(101455))
	s := renderMaintenanceSummary(svc, nil)
	if s != "已超期 21455 公里" {
		t.Fatalf("summary = %q", s)
	}
}
