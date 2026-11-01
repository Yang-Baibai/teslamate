package main

import (
	"strings"
	"testing"
	"unicode/utf8"
)

// ⚠️ **v0.107.0（用户反馈 #25）**：车控**短标签**与**后果说明**的护栏。
//
// 用户原话（真机截图 `media/feedback/25.jpg`）：
//
//	「车辆控制这一块的各个按钮**可以不需要括号内的那些东西**。
//	 把文字的布局尽量统一。」
//
// 截图里「车窗通风（开一条缝）」与「关闭全部车窗」并排 ——
// 一个**两行**、一个**一行** ⇒ 按钮被文字撑得参差不齐。
//
// 三条规矩：
//
//  ① `description` 是**唯一**手写处 —— `label` / `note` 全从它**派生**。
//     另写一份必然漂移（v0.102.0 的教训：「60 分」那句话抄了第二份，
//     错了 57 个版本都没人发现）。
//  ② `label` 里**不许有括号** —— 括号里的内容都搬进 `note` 了。
//  ③ `label` 要**短** —— 见 `teslaCommandLabelMaxChars` 的推导。
//
// ⚠️ **`description` 本身一个字都不许改** —— 它是给 LLM / Hermes 看的那一份，
// 改了就是 Hermes 侧退化（这是本项目的红线）。

// 车控按钮里放得下的汉字数上限。
//
// ⚠️ **怎么算出来的**（别拍脑袋）：
//   · 卡片内容区 ≈ 361dp（393dp 屏 − 卡片左右内边距 32dp，见 RangeLayout.kt）；
//   · 2 列网格，中间 8dp 间距 ⇒ 每列 ≈ 176dp；
//   · 按钮自身左右内边距 10dp × 2 ⇒ 可放文字 ≈ **156dp**；
//   · 一个汉字 = bodyMedium（14sp）× 1.0em ⇒ **7 个字 ≈ 98dp**，余量充足。
//
// ⇒ 7 个字以内，正常字号下按钮**必是一行** ⇒ 同排按钮的文字布局统一。
//
// ⚠️ **200% 字号**下 7 个字 ≈ 196dp > 156dp ⇒ 会折成两行 ——
//    那是**字号**的事（按钮仍然等高，`IntrinsicSize.Min` + `fillMaxHeight()`），
//    不在这一条的管辖范围内。
const teslaCommandLabelMaxChars = 7

// 标签里不许出现任何括号（全角半角都不行）。
func TestTeslaCommandCatalog_LabelHasNoParenthesis(t *testing.T) {
	for _, s := range teslaCommandCatalog {
		if strings.TrimSpace(s.Label) == "" {
			t.Errorf("%s 的短标签是空的", s.Action)
			continue
		}
		if strings.ContainsAny(s.Label, "（）()[]【】") {
			t.Errorf("%s 的短标签 %q 里还有括号 ⇒ 按钮文字又会两行（用户反馈 #25）",
				s.Action, s.Label)
		}
	}
}

// 短标签要够短 —— 短到按钮在正常字号下**只占一行**。
func TestTeslaCommandCatalog_LabelIsShort(t *testing.T) {
	for _, s := range teslaCommandCatalog {
		n := utf8.RuneCountInString(s.Label)
		if n > teslaCommandLabelMaxChars {
			t.Errorf("%s 的短标签 %q 有 %d 个字，超过上限 %d ⇒ 正常字号下按钮会折成两行",
				s.Action, s.Label, n, teslaCommandLabelMaxChars)
		}
	}
}

// `label` / `note` 必须**从 `description` 派生得出**（关联检查，不是「短语在不在」）。
//
// ⚠️ 关键：`note` 为空时，`description` 里那个括号**只能是单位**
// —— 否则说明「后果被悄悄弄丢了」（界面上的说明只剩一半）。
func TestTeslaCommandCatalog_LabelAndNoteComeFromDescription(t *testing.T) {
	for _, s := range teslaCommandCatalog {
		if s.Note != "" {
			want := s.Label + "（" + s.Note + "）"
			if s.Description != want {
				t.Errorf("%s 的 description = %q，但 label+note 推出的是 %q ⇒ 两处真相，必有一处是错的",
					s.Action, s.Description, want)
			}
			continue
		}
		if !strings.HasSuffix(s.Description, "）") {
			continue
		}
		// ⚠️ **按 rune 切** —— 字符串切片是按字节的，
		// 「（」「）」是 3 字节字符 ⇒ 按字节切会切出半个括号。
		rs := []rune(s.Description)
		open := -1
		for i := len(rs) - 1; i >= 0; i-- {
			if rs[i] == '（' {
				open = i
				break
			}
		}
		if open < 0 {
			t.Errorf("%s 的 description %q 有右括号却没有左括号", s.Action, s.Description)
			continue
		}
		inner := strings.TrimSpace(string(rs[open+1 : len(rs)-1]))
		if !teslaCommandUnitHints[inner] {
			t.Errorf("%s 的 description %q 里的「%s」没有派生进 note ⇒ 后果说明被弄丢了",
				s.Action, s.Description, inner)
		}
		if want := s.Label + "（" + inner + "）"; s.Description != want {
			t.Errorf("%s 的 description = %q，但 label 推出的是 %q", s.Action, s.Description, want)
		}
	}
}

// 钉住具体值 —— 光有结构性检查不够：**值本身**也不能被悄悄改掉。
//
// ⚠️ 这一张表同时钉住了三样东西：
//   · `description`（**一个字都不许改** —— 它是 Hermes / LLM 看的那一份）；
//   · `label`（按钮上印的短标签）；
//   · `note`（后果说明；单位类括号 ⇒ 空）。
func TestTeslaCommandCatalog_LabelAndNoteConcrete(t *testing.T) {
	want := map[string]struct {
		desc  string
		label string
		note  string
	}{
		// 带**后果说明**的四个 —— 括号里的内容要搬进 note。
		"window_vent":        {"车窗通风（开一条缝）", "车窗通风", "开一条缝"},
		"open_frunk":         {"打开前备箱（远程关不了）", "打开前备箱", "远程关不了"},
		"remote_start_drive": {"远程启动（无钥匙驾驶 2 分钟）", "远程启动", "无钥匙驾驶 2 分钟"},
		"wake_up":            {"唤醒车辆（会耗电、可能计费）", "唤醒车辆", "会耗电、可能计费"},
		// 括号里**只是单位**的三个 —— 按钮上也不要它，但它不是后果。
		"set_temperature":   {"设定座舱温度（℃）", "设定座舱温度", ""},
		"set_charge_limit":  {"设置充电上限（%）", "设置充电上限", ""},
		"set_charging_amps": {"设置充电电流（A）", "设置充电电流", ""},
	}
	byAction := make(map[string]teslaCommandSpec, len(teslaCommandCatalog))
	for _, s := range teslaCommandCatalog {
		byAction[s.Action] = s
	}
	for action, w := range want {
		s, ok := byAction[action]
		if !ok {
			t.Errorf("目录里没有动作 %s", action)
			continue
		}
		if s.Description != w.desc {
			t.Errorf("%s 的 description = %q，期望 %q（Hermes 侧靠它，别乱改）",
				action, s.Description, w.desc)
		}
		if s.Label != w.label {
			t.Errorf("%s 的 label = %q，期望 %q", action, s.Label, w.label)
		}
		if s.Note != w.note {
			t.Errorf("%s 的 note = %q，期望 %q", action, s.Note, w.note)
		}
	}
}

// 派生函数本身的用例（边界也要钉住）。
func TestSplitCommandLabel(t *testing.T) {
	cases := []struct {
		in    string
		label string
		note  string
	}{
		{"车窗通风（开一条缝）", "车窗通风", "开一条缝"},
		{"设置充电上限（%）", "设置充电上限", ""},
		{"唤醒车辆（会耗电、可能计费）", "唤醒车辆", "会耗电、可能计费"},
		{"鸣笛", "鸣笛", ""},
		{"", "", ""},
		// 头部为空 ⇒ 不拆（免得 label 变成空串）。
		{"（只有括号）", "（只有括号）", ""},
		// 前后空格要吃掉。
		{"  锁车  ", "锁车", ""},
	}
	for _, c := range cases {
		label, note := splitCommandLabel(c.in)
		if label != c.label || note != c.note {
			t.Errorf("splitCommandLabel(%q) = (%q, %q)，期望 (%q, %q)",
				c.in, label, note, c.label, c.note)
		}
	}
}
