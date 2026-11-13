package main

import (
	"strings"
	"testing"
)

// ⚠️ **v0.159.0**：简报的能耗数字必须带口径说明。
//
// ① 不带 ⇒ 用户看到「用电 63.7 度」与同段的「充入 30.4 度」并排，会以为数据自相矛盾；
// ② 不带 ⇒ App 的用量页 ⓘ 讲得清楚、**飞书端一个字都没有**
//
//	⇒ 同一个数在两端的解释不一致（ADR #41：文案与数值只有服务端一份）。
func TestChargeBriefEnergyCarriesCaveat(t *testing.T) {
	loc := testLoc(t)
	now := fixedNow(t)
	ranges := buildChargeStats(loc, now, fixedRows(t))
	r := rangeByKey(ranges, "7d")
	energy := &ChargeBriefEnergy{
		Km: 365.8, Minutes: 412, KwhUsed: 63.72,
		WhPerKm: briefFloatp(174.19), KwhPer100Km: briefFloatp(17.42), DriveCount: 12,
	}
	_, body := renderChargeBrief(loc, "Asia/Shanghai", "爱车", r, &ChargeBriefSegments{Energy: energy})
	if !strings.Contains(body, EnergyCaveatPhrase) {
		t.Fatalf("简报的能耗段必须带口径说明（%q），实际正文 = %q", EnergyCaveatPhrase, body)
	}
	// 口径说明必须**紧跟能耗数字**（不许孤零零地挂在别的段后面 ⇒ 用户对不上是哪个数的）。
	if !strings.Contains(body, "kWh/100km（"+EnergyCaveatPhrase+"）") {
		t.Fatalf("口径说明必须紧跟能耗数字，实际正文 = %q", body)
	}
}

// 那句话本身不许是空壳，也不许丢掉「这是推算的」这层意思。
func TestEnergyCaveatPhraseIsNotEmpty(t *testing.T) {
	if strings.TrimSpace(EnergyCaveatPhrase) == "" {
		t.Fatal("EnergyCaveatPhrase 是空的 ⇒ 简报会挂一个空括号")
	}
	if !strings.Contains(EnergyCaveatPhrase, "反推") {
		t.Fatalf("口径说明必须说清这个数是「推算」的（实际 = %q）", EnergyCaveatPhrase)
	}
}
