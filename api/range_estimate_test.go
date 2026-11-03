package main

import (
	"math"
	"strings"
	"testing"
	"time"
)

func fp(v float64) *float64 { return &v }
func ip(v int) *int         { return &v }

func mkDrive(start time.Time, km, wh float64, kph int, temp *float64) *Drive {
	return &Drive{
		StartDate:   start,
		Distance:    fp(km),
		WhPerKm:     fp(wh),
		AvgSpeedKph: ip(kph),
		OutsideTemp: temp,
	}
}

var t0 = time.Date(2026, 10, 1, 8, 0, 0, 0, time.UTC)

// ⚠️ **样本不足 ⇒ 不建模型**（客户端：usable.size < 4 ⇒ null）。
func TestRangeModelNeedsFourUsable(t *testing.T) {
	drives := []*Drive{
		mkDrive(t0, 50, 180, 60, fp(25)),
		mkDrive(t0.AddDate(0, 0, 1), 50, 180, 60, fp(25)),
		mkDrive(t0.AddDate(0, 0, 2), 50, 180, 60, fp(25)),
	}
	if m := buildRangeModel(drives); m != nil {
		t.Fatalf("只有 3 条可用行程（< 4）⇒ 不该建出模型，却建了 %d 个档", len(m.Buckets))
	}
}

// ⚠️ **样本过滤**：km < 20 / wh ∉ [50,400] / 均速 ≤ 0 的行程不进模型。
func TestRangeModelFiltersSamples(t *testing.T) {
	drives := []*Drive{
		mkDrive(t0, 50, 180, 60, fp(25)),
		mkDrive(t0.AddDate(0, 0, 1), 50, 180, 60, fp(25)),
		mkDrive(t0.AddDate(0, 0, 2), 50, 180, 60, fp(25)),
		mkDrive(t0.AddDate(0, 0, 3), 50, 180, 60, fp(25)),
		mkDrive(t0.AddDate(0, 0, 4), 19.9, 180, 60, fp(25)), // km < 20 ⇒ 排除
		mkDrive(t0.AddDate(0, 0, 5), 50, 49.9, 60, fp(25)),  // wh < 50 ⇒ 排除
		mkDrive(t0.AddDate(0, 0, 6), 50, 400.1, 60, fp(25)), // wh > 400 ⇒ 排除
		mkDrive(t0.AddDate(0, 0, 7), 50, 180, 0, fp(25)),    // 均速 ≤ 0 ⇒ 排除
	}
	m := buildRangeModel(drives)
	if m == nil {
		t.Fatal("4 条可用行程 ⇒ 应该建出模型")
	}
	if m.SampleCount != 4 {
		t.Fatalf("可用样本应是 4 条，实际 %d 条", m.SampleCount)
	}
}

// ⚠️ **分桶的中位数**：偶数样本取**两个中间值的平均**（不是上中位数）。
func TestRangeBucketMedianAveragesTwoMiddles(t *testing.T) {
	drives := []*Drive{
		mkDrive(t0, 100, 180, 50, fp(25)),
		mkDrive(t0.AddDate(0, 0, 1), 100, 190, 50, fp(25)),
		mkDrive(t0.AddDate(0, 0, 2), 100, 170, 70, fp(25)),
		mkDrive(t0.AddDate(0, 0, 3), 100, 170, 70, fp(25)),
	}
	m := buildRangeModel(drives)
	if m == nil {
		t.Fatal("应该建出模型")
	}
	if len(m.Buckets) != 2 {
		t.Fatalf("应有 2 个档（0–60 / 60–90），实际 %d 个", len(m.Buckets))
	}
	if math.Abs(m.Buckets[0].WhPerKm-185) > 1e-9 {
		t.Fatalf("0–60 档中位数应是 185（180/190 的平均），实际 %v", m.Buckets[0].WhPerKm)
	}
	if m.Buckets[0].Samples != 2 || m.Buckets[1].Samples != 2 {
		t.Fatalf("两档各 2 条样本，实际 %d / %d", m.Buckets[0].Samples, m.Buckets[1].Samples)
	}
}

// ⚠️ **温度修正**：只做热的（>22°C），上限 +10%；22°C 及以下不修正。
func TestRangeTempFactor(t *testing.T) {
	m := &rangeModel{Buckets: []rangeBucket{{MinKph: 0, MaxKph: 60, WhPerKm: 180, Samples: 5}}}
	cases := []struct {
		temp float64
		want float64
	}{
		{22, 1.0},
		{30, 1.048},
		{50, 1.10}, // 上限（0.006×28 = 0.168 ⇒ 截到 0.10）
	}
	for _, c := range cases {
		if got := m.tempFactor(fp(c.temp)); math.Abs(got-c.want) > 1e-9 {
			t.Fatalf("%v°C 的温度修正应是 %v，实际 %v", c.temp, c.want, got)
		}
	}
	if got := m.tempFactor(nil); got != 1.0 {
		t.Fatalf("没有车外温度 ⇒ 修正应为 1.0，实际 %v", got)
	}
}

// ⚠️ **低温不可信 ⇒ 不给数**（≤10°C 且低温样本 < 5 ⇒ null）。
func TestRangeLowTempBlocksNumber(t *testing.T) {
	m := &rangeModel{
		Buckets:        []rangeBucket{{MinKph: 0, MaxKph: 60, WhPerKm: 180, Samples: 5}},
		LowTempSamples: 0,
	}
	if v := m.TrueRangeKm(fp(448.3), 50, fp(5), 0.90); v != nil {
		t.Fatalf("低温样本 0 条 + 5°C ⇒ 不该给数，实际 %v", *v)
	}
	m.LowTempSamples = 5
	if v := m.TrueRangeKm(fp(448.3), 50, fp(5), 0.90); v == nil {
		t.Fatal("低温样本 5 条 ⇒ 应该给数")
	}
}

// ⚠️ **真实可达的算式**：标定续航 × 146 ÷ 实测 Wh/km × 温度修正 × 保底。
func TestRangeTrueRangeFormula(t *testing.T) {
	m := &rangeModel{
		Buckets:        []rangeBucket{{MinKph: 0, MaxKph: 60, WhPerKm: 180, Samples: 5}},
		LowTempSamples: 5,
	}
	got := m.TrueRangeKm(fp(448.3), 50, fp(22), 0.90)
	if got == nil {
		t.Fatal("应该给数")
	}
	want := 448.3 * 146.0 / 180.0 * 0.90
	if math.Abs(*got-want) > 1e-6 {
		t.Fatalf("真实可达应是 %v，实际 %v", want, *got)
	}
	// 保底更小 ⇒ 公里数更小
	floor := m.TrueRangeKm(fp(448.3), 50, fp(22), 0.80)
	if floor == nil || *floor >= *got {
		t.Fatalf("保底 0.80 的公里数应小于保底 0.90 的 %v", *got)
	}
	// 没有标定续航 ⇒ 不给数
	if v := m.TrueRangeKm(nil, 50, fp(22), 0.90); v != nil {
		t.Fatalf("没有标定续航 ⇒ 不该给数，实际 %v", *v)
	}
}

// ⚠️ **区间**：没有残差 P90 ⇒ 不给区间；有了 ⇒ 悲观 ≤ 乐观。
func TestRangeBand(t *testing.T) {
	m := &rangeModel{
		Buckets:        []rangeBucket{{MinKph: 0, MaxKph: 60, WhPerKm: 180, Samples: 5}},
		LowTempSamples: 5,
	}
	if b := m.TrueRangeBandKm(fp(448.3), 50, fp(22), 0.90); b != nil {
		t.Fatalf("没有残差 P90 ⇒ 不该给区间，实际 %+v", *b)
	}
	m.ResidualP90 = fp(20)
	b := m.TrueRangeBandKm(fp(448.3), 50, fp(22), 0.90)
	if b == nil {
		t.Fatal("有残差 P90 ⇒ 应该给区间")
	}
	if b.Lo > b.Hi {
		t.Fatalf("区间应升序，实际 %d ~ %d", b.Lo, b.Hi)
	}
	base := m.TrueRangeKm(fp(448.3), 50, fp(22), 0.90)
	if b.Lo > int(math.Round(*base))+1 || b.Hi < int(math.Round(*base))-1 {
		t.Fatalf("区间 %d ~ %d 应包住基数 %v", b.Lo, b.Hi, *base)
	}
}

// ⚠️ **参考车速**：最近 5 趟 **≥30 km** 行程的均速中位数（km<30 的不算）。
func TestRangeCruiseSpeed(t *testing.T) {
	drives := []*Drive{
		mkDrive(t0, 25, 180, 200, fp(25)), // km < 30 ⇒ 不进「最近 5 趟」
		mkDrive(t0.AddDate(0, 0, 1), 50, 180, 40, fp(25)),
		mkDrive(t0.AddDate(0, 0, 2), 50, 180, 50, fp(25)),
		mkDrive(t0.AddDate(0, 0, 3), 50, 180, 60, fp(25)),
		mkDrive(t0.AddDate(0, 0, 4), 50, 180, 70, fp(25)),
		mkDrive(t0.AddDate(0, 0, 5), 50, 180, 80, fp(25)),
		mkDrive(t0.AddDate(0, 0, 6), 50, 180, 90, fp(25)),
	}
	m := buildRangeModel(drives)
	if m == nil {
		t.Fatal("应该建出模型")
	}
	// 最近 5 趟 ≥30 km 的均速 = 50,60,70,80,90 ⇒ 中位数 70
	if math.Abs(m.CruiseKph-70) > 1e-9 {
		t.Fatalf("参考车速应是 70，实际 %v", m.CruiseKph)
	}
}

// ⚠️ **残差 P90**：只看 km ≥ 100 的长途样本；样本 < 10 ⇒ 不给数。
func TestRangeResidualNeedsTenLongSamples(t *testing.T) {
	drives := []*Drive{}
	for i := 0; i < 9; i++ {
		drives = append(drives, mkDrive(t0.AddDate(0, 0, i), 150, 180+float64(i), 50, fp(25)))
	}
	m := buildRangeModel(drives)
	if m == nil {
		t.Fatal("应该建出模型")
	}
	if m.ResidualP90 != nil {
		t.Fatalf("长途样本只有 9 条（< 10）⇒ 不该有 P90，实际 %v", *m.ResidualP90)
	}
	drives = append(drives, mkDrive(t0.AddDate(0, 0, 9), 150, 200, 50, fp(25)))
	m = buildRangeModel(drives)
	if m.ResidualP90 == nil {
		t.Fatal("长途样本 10 条 ⇒ 应该有 P90")
	}
	if m.ResidualSamples != 10 {
		t.Fatalf("P90 的样本数应是 10，实际 %d", m.ResidualSamples)
	}
}

// ⚠️ **借档要标明**：车速落在所有档外时借用最近的一档，且 exact=false。
//
// ⚠️ 借档只在**最低档不是从 0 起**时才会发生（0–60 档样本 < 2 条时它不建）
//
//	⇒ 车速 10 km/h 在有 0–60 档时是**落在档内**的，不是借档。
func TestRangeBucketBorrowMarksExactFalse(t *testing.T) {
	drives := []*Drive{
		mkDrive(t0, 100, 180, 50, fp(25)),
		mkDrive(t0.AddDate(0, 0, 1), 100, 190, 50, fp(25)),
		mkDrive(t0.AddDate(0, 0, 2), 100, 170, 70, fp(25)),
		mkDrive(t0.AddDate(0, 0, 3), 100, 170, 70, fp(25)),
	}
	m := buildRangeModel(drives)
	if m == nil {
		t.Fatal("应该建出模型")
	}
	if _, exact := bucketForSpeed(55, m.Buckets); !exact {
		t.Fatal("55 km/h 落在 0–60 档 ⇒ 应是精确档")
	}
	// 有 0–60 档时，10 km/h 在档内（最低档从 0 起）
	if _, exact := bucketForSpeed(10, m.Buckets); !exact {
		t.Fatal("10 km/h 落在 0–60 档内（最低档从 0 起）⇒ 应是精确档")
	}

	// 没有 0–60 档（样本不足 2 条 ⇒ 不建）⇒ 最低档是 60 ⇒ 10 km/h 只能借档
	drives2 := []*Drive{
		mkDrive(t0, 100, 175, 70, fp(25)),
		mkDrive(t0.AddDate(0, 0, 1), 100, 175, 70, fp(25)),
		mkDrive(t0.AddDate(0, 0, 2), 100, 185, 95, fp(25)),
		mkDrive(t0.AddDate(0, 0, 3), 100, 185, 95, fp(25)),
	}
	m2 := buildRangeModel(drives2)
	if m2 == nil {
		t.Fatal("应该建出模型")
	}
	if len(m2.Buckets) != 2 || m2.Buckets[0].MinKph != 60 {
		t.Fatalf("该模型应只有 60–90 / 90–110 两档，实际 %+v", m2.Buckets)
	}
	b, exact := bucketForSpeed(10, m2.Buckets)
	if exact {
		t.Fatal("10 km/h 落在所有档外 ⇒ 借档 ⇒ exact 必须是 false")
	}
	if b.MinKph != 60 {
		t.Fatalf("借最近的一档应是 60–90，实际 %d–%d", b.MinKph, b.MaxKph)
	}
}

// ⚠️ **v0.119.0**：模型缓存的失效判据（纯函数 ⇒ 单测直接钉）。
//
// ⚠️ **两个失效条件缺一不可**：
//
//	① 超时 ⇒ 重算；
//	② **行程条数变了** ⇒ 重算（否则「模型样本 N 条」停在旧数上）。
func TestRangeModelCacheValid(t *testing.T) {

	now := time.Date(2026, 11, 3, 12, 0, 0, 0, time.UTC)
	hit := rangeModelCacheEntry{at: now, model: &rangeModel{}, driveCount: 92}

	if !rangeModelCacheValid(hit, now.Add(time.Minute), 92) {
		t.Fatal("同样的行程条数 + 未超时 ⇒ 缓存应当有效")
	}
	if rangeModelCacheValid(hit, now.Add(time.Minute), 93) {
		t.Fatal("行程条数变了（多开了一趟）⇒ 缓存必须失效")
	}
	if rangeModelCacheValid(hit, now.Add(rangeModelCacheTTL+time.Second), 92) {
		t.Fatal("超过 TTL ⇒ 缓存必须失效")
	}
	// ⚠️ 「样本不足」（model == nil）也是一个**合法答案** ⇒ 命中时必须照样返回 nil，
	//    不能当成「没有缓存」（那会让每次请求都重算）。
	nilHit := rangeModelCacheEntry{at: now, model: nil, driveCount: 92}
	if !rangeModelCacheValid(nilHit, now.Add(time.Minute), 92) {
		t.Fatal("模型为 nil（样本不足）也是有效缓存")
	}
}

// ⚠️ **v0.121.0 补记**：「还有 X 公里，够吗」的判定（纯函数 ⇒ 直接钉）。
func TestApplyReachEstimate(t *testing.T) {
	// 容量常量在测试环境里没有 ⇒ 显式给一个（否则「到达时剩余电量」算不出来）
	t.Setenv("NOMINAL_BATTERY_KWH", "78.4")
	tr, fl := 170.0, 113.0
	wh, temp := 167.0, 1.045
	batt := 50
	mk := func() *RangeEstimate {
		return &RangeEstimate{
			TrueRangeKm:  &tr,
			FloorRangeKm: &fl,
			WhPerKm:      &wh,
			TempFactor:   temp,
			BatteryLevel: &batt,
			Summary:      "真实可达 170 公里",
		}
	}

	// 到得了（150 ≤ 170）⇒ 够；且给出到达时剩余电量。
	e := mk()
	applyReachEstimate(e, 150)
	if e.ReachOk == nil || !*e.ReachOk {
		t.Fatal("150 公里 ≤ 真实可达 170 ⇒ 应当够")
	}
	if e.ReachFloorOk == nil || *e.ReachFloorOk {
		t.Fatal("150 公里 > 保底 113 ⇒ 应当不够（保底口径独立判）")
	}
	if e.ArriveSocPct == nil {
		t.Fatal("到得了 ⇒ 应当给出到达时剩余电量")
	}
	if e.ReachFloorShortfallKm == nil {
		t.Fatal("保底不够 ⇒ 应当给出「保底还差多少公里」")
	}

	// 到不了（200 > 170）⇒ 不够 + 差额；且**不给**剩余电量。
	e2 := mk()
	applyReachEstimate(e2, 200)
	if e2.ReachOk == nil || *e2.ReachOk {
		t.Fatal("200 公里 > 真实可达 170 ⇒ 应当不够")
	}
	if e2.ReachShortfallKm == nil || *e2.ReachShortfallKm != 30 {
		t.Fatalf("差值应为 30 公里，实际 %v", e2.ReachShortfallKm)
	}
	if e2.ArriveSocPct != nil {
		t.Fatal("到不了 ⇒ 不应给「到达时剩余电量」（会被读成到了就没电）")
	}
	if !strings.Contains(e2.Summary, "差 30 公里") {
		t.Fatalf("摘要里应当写差 30 公里，实际 %q", e2.Summary)
	}
	// ⚠️ 不许暗示方向（趋势符号）。
	if strings.Contains(e2.Summary, "→") {
		t.Fatalf("摘要里出现了箭头 ⇒ 那是趋势符号：%q", e2.Summary)
	}
}
