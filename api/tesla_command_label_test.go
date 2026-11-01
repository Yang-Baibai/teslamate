package main

import (
	"encoding/json"
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
//
//	· 卡片内容区 ≈ 361dp（393dp 屏 − 卡片左右内边距 32dp，见 RangeLayout.kt）；
//	· 2 列网格，中间 8dp 间距 ⇒ 每列 ≈ 176dp；
//	· 按钮自身左右内边距 10dp × 2 ⇒ 可放文字 ≈ **156dp**；
//	· 一个汉字 = bodyMedium（14sp）× 1.0em ⇒ **7 个字 ≈ 98dp**，余量充足。
//
// ⇒ 7 个字以内，正常字号下按钮**必是一行** ⇒ 同排按钮的文字布局统一。
//
// ⚠️ **200% 字号**下 7 个字 ≈ 196dp > 156dp ⇒ 会折成两行 ——
//
//	那是**字号**的事（按钮仍然等高，`IntrinsicSize.Min` + `fillMaxHeight()`），
//	不在这一条的管辖范围内。
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
		if !teslaCommandUnitHints[inner] && !teslaCommandSpecHints[inner] {
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
//
//	· `description`（**一个字都不许改** —— 它是 Hermes / LLM 看的那一份）；
//	· `label`（按钮上印的短标签）；
//	· `note`（后果说明；单位类括号 ⇒ 空）。
func TestTeslaCommandCatalog_LabelAndNoteConcrete(t *testing.T) {
	want := map[string]struct {
		desc  string
		label string
		note  string
	}{
		// 带**后果说明**的三个 —— 括号里的内容要搬进 note。
		"open_frunk":         {"打开前备箱（远程关不了）", "打开前备箱", "远程关不了"},
		"remote_start_drive": {"远程启动（无钥匙驾驶 2 分钟）", "远程启动", "无钥匙驾驶 2 分钟"},
		"wake_up":            {"唤醒车辆（会耗电、可能计费）", "唤醒车辆", "会耗电、可能计费"},
		// ⚠️ **v0.107.1**：「开一条缝」是**动作规格**、不是后果 ⇒ 不搬进 note
		// （否则车窗通风会平白多一次确认弹窗 —— 可逆动作不该被打断）。
		// ⚠️ `description` **一个字都不改** ⇒ LLM 侧照旧知道「只开一条缝」。
		"window_vent": {"车窗通风（开一条缝）", "车窗通风", ""},
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
		// ⚠️ **v0.107.1**：「开一条缝」是**动作规格**、不是后果 ⇒ note 为空。
		{"车窗通风（开一条缝）", "车窗通风", ""},
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

// ⚠️ **v0.107.1**：钉住「**哪些动作有后果说明**」—— 这直接决定 App 会不会弹窗。
//
// App 的规则是「**有 note ⇒ 必须弹窗**」（ADR #37）⇒
// 这张表**多一个名字，用户就多挨一次打断**；少一个 ⇒ **代价提示就丢了**。
//
// ⚠️ **只有这三个**（「规格」不算后果 ⇒ 见 `teslaCommandSpecHints`）：
//
//	· `open_frunk`「远程关不了」—— **限制**（开了远程关不掉）⇒ 必须先说清楚；
//	· `remote_start_drive`「无钥匙驾驶 2 分钟」—— **说明**（它到底做什么）；
//	· `wake_up`「会耗电、可能计费」—— **代价**（唤醒要花钱）。
//
// 而 `window_vent` 的「开一条缝」是**动作规格**（通风本来就是开条缝）
// ⇒ 它不该让用户为一个**可逆**动作多点一次（v0.107.1 收窄）。
func TestTeslaCommandCatalog_NoteSetPinned(t *testing.T) {
	want := map[string]bool{
		"open_frunk":         true,
		"remote_start_drive": true,
		"wake_up":            true,
	}
	got := map[string]bool{}
	for _, s := range teslaCommandCatalog {
		if strings.TrimSpace(s.Note) != "" {
			got[s.Action] = true
		}
	}
	for action := range got {
		if !want[action] {
			t.Errorf("%s 多出了后果说明 %q ⇒ App 会为它多弹一次确认窗（要改先改 ADR #37）",
				action, teslaCommandByAction[action].Note)
		}
	}
	for action := range want {
		if !got[action] {
			t.Errorf("%s 的后果说明不见了 ⇒ 用户点之前看不见「%s」",
				action, teslaCommandByAction[action].Description)
		}
	}
}

// 「只是单位」的表是**手工登记**的 ⇒ 把它的内容钉死：
// 谁想加一条，都得先想清楚（加错了 = 用户白挨一次弹窗）。
func TestTeslaCommandUnitHints_TablePinned(t *testing.T) {
	want := map[string]bool{
		"℃": true, // 单位：摄氏度
		"%": true, // 单位：百分比
		"A": true, // 单位：安培
	}
	if len(teslaCommandUnitHints) != len(want) {
		t.Errorf("「只是单位」的表有 %d 条，期望 %d 条：%v",
			len(teslaCommandUnitHints), len(want), teslaCommandUnitHints)
	}
	for k := range want {
		if !teslaCommandUnitHints[k] {
			t.Errorf("「%s」不在「只是单位」的表里了 ⇒ 它会被当成后果 ⇒ 多一次弹窗", k)
		}
	}
	for k := range teslaCommandUnitHints {
		if !want[k] {
			t.Errorf("「%s」被加进「只是单位」的表了 —— 要加的话，先想清楚它是不是单位", k)
		}
	}
}

// 「只是动作规格」的表也是**手工登记**的 ⇒ 同样钉死。
//
// ⚠️ 加错了的代价 = **多一次弹窗**（规格被当成后果）；
// 删错了的代价 = 用户点之前**看不见**这个规格。
func TestTeslaCommandSpecHints_TablePinned(t *testing.T) {
	want := map[string]bool{
		"开一条缝": true, // 规格：车窗通风只开一条缝（不是代价）
	}
	if len(teslaCommandSpecHints) != len(want) {
		t.Errorf("「只是动作规格」的表有 %d 条，期望 %d 条：%v",
			len(teslaCommandSpecHints), len(want), teslaCommandSpecHints)
	}
	for k := range want {
		if !teslaCommandSpecHints[k] {
			t.Errorf("「%s」不在「只是动作规格」的表里了 ⇒ 它会被当成后果 ⇒ 多一次弹窗", k)
		}
	}
	for k := range teslaCommandSpecHints {
		if !want[k] {
			t.Errorf("「%s」被加进「只是动作规格」的表了 —— 要加的话，先想清楚它是不是代价", k)
		}
	}
}

// ⚠️ **v0.107.1**：**线上契约** —— JSON 里必须真的带上 `label` / `note` 这两个键。
//
// ⚠️ **为什么这条最要紧**：App 的 `label` / `note` 有默认值（老服务端兼容）⇒
// **服务端一旦改字段名，App 不会报错**，只会**静默退回 `description`**
// ⇒ 用户的界面悄悄变回「标签里带括号」的老样子，**没有任何人会发现**。
// ⇒ 所以字段名必须被**钉住**（这里钉服务端这一侧，
// 客户端那一侧由 `scripts/client-gates.sh` 的闸门 19 钉）。
func TestTeslaCommandCatalog_JSONCarriesLabelAndNote(t *testing.T) {
	raw, err := json.Marshal(teslaCommandCatalog[0])
	if err != nil {
		t.Fatalf("序列化目录失败：%v", err)
	}
	var got map[string]any
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatalf("反序列化失败：%v", err)
	}
	for _, key := range []string{"label", "note"} {
		if _, ok := got[key]; !ok {
			t.Errorf("目录 JSON 里没有 %q 这个键 ⇒ App 读不到 ⇒ 界面悄悄退回老样子", key)
		}
	}
	// ⚠️ `note` 是 `json:"note"`（**没有** omitempty）⇒
	// 没后果的动作也必须带这个键（值是空串）⇒
	// App 端用「键缺失」和「空串」区分新旧服务端时不会踩坑。
	if _, ok := got["note"].(string); !ok {
		t.Errorf("目录 JSON 的 note 不是字符串：%v", got["note"])
	}
}
