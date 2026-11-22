package main

import (
	"testing"
	"time"
)

func cmF(v float64) *float64 { return &v }

func cmDate(s string) time.Time {
	t, err := time.Parse(time.RFC3339, s)
	if err != nil {
		panic(err)
	}
	return t
}

func cmRow(km float64, speed int, wh float64, temp *float64, date string) consumptionRow {
	return consumptionRow{
		km:          km,
		avgSpeedKph: speed,
		whPerKm:     wh,
		outsideTemp: temp,
		startDate:   cmDate(date),
	}
}

// TestConsumptionModel_照抄客户端口径
//
// ⚠️ 断言的是**具体数值**：
//
//	· 可用样本 = 4 条（km<20 与 wh>400 的两条被滤掉）；
//	· 只有 [0,60) 桶够 2 条样本 ⇒ 中位 = 175；
//	· 参考车速 = 最近 4 趟均速中位 = 60；
//	· 残差样本只有 4 条（<10）⇒ P90 必须是 null。
func TestConsumptionModel_照抄客户端口径(t *testing.T) {
	rows := []consumptionRow{
		cmRow(100, 50, 180, cmF(25), "2026-10-01T08:00:00Z"),
		cmRow(100, 70, 190, cmF(25), "2026-10-02T08:00:00Z"),
		cmRow(100, 100, 200, cmF(25), "2026-10-03T08:00:00Z"),
		cmRow(100, 50, 170, cmF(25), "2026-10-04T08:00:00Z"),
		// 下面两条**必须被滤掉**：km < 20 与 wh > 400。
		cmRow(10, 30, 180, cmF(25), "2026-10-05T08:00:00Z"),
		cmRow(100, 50, 450, cmF(25), "2026-10-06T08:00:00Z"),
	}
	loc := time.UTC
	m := buildConsumptionModel(rows, loc)
	if m.Caveat != "" {
		t.Fatalf("样本够时不该有 caveat：%s", m.Caveat)
	}
	if len(m.Buckets) != 1 {
		t.Fatalf("应当只有 1 个桶（其余桶样本不足），实际 %d 个", len(m.Buckets))
	}
	b := m.Buckets[0]
	if b.MinKph != 0 || b.MaxKph != 60 {
		t.Fatalf("桶边界应为 [0,60)，实际 [%d,%d)", b.MinKph, b.MaxKph)
	}
	if b.WhPerKm < 174.999 || b.WhPerKm > 175.001 {
		t.Fatalf("桶中位应为 175，实际 %v", b.WhPerKm)
	}
	if b.Samples != 2 {
		t.Fatalf("桶样本应为 2，实际 %d", b.Samples)
	}
	if m.CruiseKph < 59.999 || m.CruiseKph > 60.001 {
		t.Fatalf("参考车速应为 60，实际 %v", m.CruiseKph)
	}
	if m.TempMinC == nil || *m.TempMinC != 25 || m.TempMaxC == nil || *m.TempMaxC != 25 {
		t.Fatalf("气温范围应为 25~25，实际 %v / %v", m.TempMinC, m.TempMaxC)
	}
	if m.LowTempSamples != 0 {
		t.Fatalf("低温样本应为 0，实际 %d", m.LowTempSamples)
	}
	if m.FirstDrivenAt != "2026-10-01" || m.LastDrivenAt != "2026-10-04" {
		t.Fatalf("覆盖范围应为 2026-10-01 ~ 2026-10-04，实际 %s ~ %s", m.FirstDrivenAt, m.LastDrivenAt)
	}
	if m.ResidualP90WhPerKm != nil {
		t.Fatalf("残差样本不足 10 条时 P90 必须是 null，实际 %v", *m.ResidualP90WhPerKm)
	}
	if m.ResidualSamples != 0 {
		t.Fatalf("残差样本不足时 residualSamples 应为 0，实际 %d", m.ResidualSamples)
	}
	if m.SampleCount != 2 {
		t.Fatalf("分桶样本总数应为 2，实际 %d", m.SampleCount)
	}
}

// TestConsumptionModel_样本不足就不给模型
func TestConsumptionModel_样本不足就不给模型(t *testing.T) {
	rows := []consumptionRow{
		cmRow(100, 50, 180, cmF(25), "2026-10-01T08:00:00Z"),
		cmRow(100, 60, 185, cmF(25), "2026-10-02T08:00:00Z"),
		cmRow(100, 70, 190, cmF(25), "2026-10-03T08:00:00Z"),
	}
	m := buildConsumptionModel(rows, time.UTC)
	if m.Caveat == "" {
		t.Fatal("样本不足时必须给 caveat（不知道 ≠ 正常）")
	}
	if len(m.Buckets) != 0 {
		t.Fatalf("样本不足时不该有桶，实际 %d 个", len(m.Buckets))
	}
}

// TestConsumptionModel_阈值钉死具体数值
func TestConsumptionModel_阈值钉死具体数值(t *testing.T) {
	if consumptionMinUsableSamples != 4 {
		t.Errorf("可用样本门槛应为 4，实际 %d", consumptionMinUsableSamples)
	}
	if consumptionMinKm != 20.0 || consumptionMinWh != 50.0 || consumptionMaxWh != 400.0 {
		t.Error("样本筛选阈值被改了（km 20 / wh 50~400）")
	}
	if consumptionMinBucketSamples != 2 || consumptionMinResidualSamples != 10 {
		t.Error("桶样本门槛(2) 或残差样本门槛(10) 被改了")
	}
	if consumptionMinCruiseKm != 30.0 || consumptionCruiseTrips != 5 {
		t.Error("参考车速口径被改了（≥30 公里、最近 5 趟）")
	}
	if consumptionMinResidualKm != 100.0 || consumptionLowTempC != 10.0 {
		t.Error("残差 km≥100 或低温 ≤10°C 的门槛被改了")
	}
	if consumptionMaxKph != 2147483647 {
		t.Errorf("最后一档上界必须是 2147483647（客户端 Int.MAX_VALUE），实际 %d", consumptionMaxKph)
	}
	if len(consumptionBucketEdges) != 6 || consumptionBucketEdges[0] != 0 ||
		consumptionBucketEdges[1] != 60 || consumptionBucketEdges[2] != 90 ||
		consumptionBucketEdges[3] != 110 || consumptionBucketEdges[4] != 130 {
		t.Error("分桶边界被改了（0/60/90/110/130）")
	}
}
