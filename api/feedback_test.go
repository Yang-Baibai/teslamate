package main

import (
	"strings"
	"testing"
)

// ⚠️ **v0.100.0：用户反馈的入参校验**。
//
// 这组用例守的是「反馈通道」本身的可靠性：
// 空内容不能进来（那是误触）、超长不能静默截断（用户以为说清楚了，其实没有）、
// 上下文只能截断（它是线索，不是内容 —— 长一点不该把整条反馈挡下来）。
func TestNormalizeFeedbackMessage(t *testing.T) {
	cases := []struct {
		name    string
		in      string
		wantErr bool
		want    string
	}{
		{"正常内容", "用量页的每公里成本算错了", false, "用量页的每公里成本算错了"},
		{"首尾空白要去掉", "  车窗提示不对  ", false, "车窗提示不对"},
		{"空内容要拒绝", "", true, ""},
		{"只有空白也要拒绝", "   \n\t ", true, ""},
		{"正好到上限", strings.Repeat("字", feedbackMessageMax), false, strings.Repeat("字", feedbackMessageMax)},
		{"超一个字就拒绝", strings.Repeat("字", feedbackMessageMax+1), true, ""},
	}
	for _, c := range cases {
		got, err := normalizeFeedbackMessage(c.in)
		if c.wantErr {
			if err == nil {
				t.Errorf("%s：应当报错，实际通过了（返回 %q）", c.name, got)
			}
			continue
		}
		if err != nil {
			t.Errorf("%s：不应当报错，实际 %v", c.name, err)
			continue
		}
		if got != c.want {
			t.Errorf("%s：期望 %q，实际 %q", c.name, c.want, got)
		}
	}
}

// ⚠️ 中文按 **rune** 计长度 —— 用 len()（字节）会让上限变成 666 字。
func TestFeedbackMessageMaxCountsRunes(t *testing.T) {
	if _, err := normalizeFeedbackMessage(strings.Repeat("字", feedbackMessageMax)); err != nil {
		t.Fatalf("%d 个汉字应当通过，实际 %v", feedbackMessageMax, err)
	}
	if _, err := normalizeFeedbackMessage(strings.Repeat("字", feedbackMessageMax+1)); err == nil {
		t.Fatalf("%d 个汉字应当被拒绝，实际通过了", feedbackMessageMax+1)
	}
}

func TestNormalizeFeedbackContext(t *testing.T) {
	if got := normalizeFeedbackContext("  用量  "); got != "用量" {
		t.Errorf("上下文应去掉首尾空白，实际 %q", got)
	}
	long := strings.Repeat("页", feedbackContextMax+10)
	got := normalizeFeedbackContext(long)
	if n := len([]rune(got)); n != feedbackContextMax {
		t.Errorf("超长上下文应截到 %d 字，实际 %d", feedbackContextMax, n)
	}
	// ⚠️ 截断**不能**切坏 UTF-8（半个汉字会让 JSON 变成乱码）。
	if !strings.HasPrefix(long, got) {
		t.Errorf("截断后的内容应是原文的前缀，实际 %q", got)
	}
}

func TestValidFeedbackStatus(t *testing.T) {
	for _, s := range []string{"new", "doing", "skip", "done"} {
		if !validFeedbackStatus(s) {
			t.Errorf("状态 %q 应当合法", s)
		}
	}
	for _, s := range []string{"handled", "closed", "NEW", "处理中", ""} {
		if validFeedbackStatus(s) {
			t.Errorf("状态 %q 不该合法（只认 new / doing / skip / done）", s)
		}
	}
}

// ⚠️ **skip 必须写理由** —— 「评估后判断不处理」而没有理由，
// 等于把判断藏起来 ⇒ 下一轮还会有人重新纠结同一件事。
func TestNormalizeFeedbackDecision(t *testing.T) {
	// 正常路径
	got, note, err := normalizeFeedbackDecision("doing", "下一轮修：曲线画错了")
	if err != nil {
		t.Fatalf("doing 不该报错，实际 %v", err)
	}
	if got != "doing" || note != "下一轮修：曲线画错了" {
		t.Errorf("doing 的状态/理由应原样保留，实际 %q / %q", got, note)
	}
	// skip 必须有理由
	if _, _, err := normalizeFeedbackDecision("skip", "   "); err == nil {
		t.Error("skip 不写理由应当报错")
	}
	if _, _, err := normalizeFeedbackDecision("skip", "这是既有限制，不是缺陷"); err != nil {
		t.Errorf("skip 带理由不该报错，实际 %v", err)
	}
	// new（重新打开）要**清掉**理由：它表示「回到未评估」
	_, note2, err := normalizeFeedbackDecision("new", "以前写的理由")
	if err != nil {
		t.Fatalf("new 不该报错，实际 %v", err)
	}
	if note2 != "" {
		t.Errorf("重新打开应清空理由，实际 %q", note2)
	}
	// 非法状态
	if _, _, err := normalizeFeedbackDecision("handled", ""); err == nil {
		t.Error("handled 已不是合法状态，应当报错")
	}
}

// ⚠️ **v0.132.0**：评估结论**不许被静默截断**。
//
// 真因：原来结论被硬截到 `feedbackContextMax*4` = 256 字，且**不报错**
// ⇒ 一次真实回写就把三条结论截成半句话（「…真机复核通过（… · App 」），
// 而调用方拿到 200 OK ⇒ 写的人以为写进去了。
//
// 三条规矩：
//
//	① 正常长度（几百字）**原样保留** —— 一条真机复核结论本来就该写得下；
//	② 超过上限 ⇒ **截断但留痕**：末尾必须带「已截断」标记，
//	   让读的人知道这句话没写全（不许半句悄悄躺在库里）；
//	③ 截断后长度**仍不超过上限**（标记本身要算进去）。
func TestFeedbackDecisionIsNotSilentlyTruncated(t *testing.T) {
	// ① 一条真实的复核结论（几百字）必须原样保留
	real := strings.Repeat("真机复核通过。", 80) // 480 字
	_, note, err := normalizeFeedbackDecision("done", real)
	if err != nil {
		t.Fatalf("done 不该报错，实际 %v", err)
	}
	if note != real {
		t.Errorf("正常长度的结论应原样保留，实际被截成 %d 字（原文 %d 字）", len([]rune(note)), len([]rune(real)))
	}

	// ② 超长 ⇒ 必须留痕
	tooLong := strings.Repeat("字", feedbackDecisionMax+500)
	_, note2, err := normalizeFeedbackDecision("done", tooLong)
	if err != nil {
		t.Fatalf("done 不该报错，实际 %v", err)
	}
	if !strings.Contains(note2, "已截断") {
		t.Errorf("超长结论被截断后必须带「已截断」标记，实际末尾是 %q", tailRunes(note2, 30))
	}
	// ③ 截断后长度仍不超过上限（含标记）
	if n := len([]rune(note2)); n > feedbackDecisionMax {
		t.Errorf("截断后长度 %d 超过上限 %d", n, feedbackDecisionMax)
	}
}

// tailRunes 取字符串末尾 n 个字（用来打印，不影响断言）。
func tailRunes(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[len(r)-n:])
}

// ⚠️ 图片类型**只认魔数** —— 客户端给的文件名与 Content-Type 都可以随手伪造。
func TestDetectImageType(t *testing.T) {
	cases := []struct {
		name string
		data []byte
		want string
		ok   bool
	}{
		{"jpg", []byte{0xFF, 0xD8, 0xFF, 0xE0, 0x00}, "jpg", true},
		{"png", []byte{0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, "png", true},
		{"webp", append([]byte("RIFF"), append([]byte{0, 0, 0, 0}, []byte("WEBP")...)...), "webp", true},
		{"gif", []byte("GIF89a......"), "gif", true},
		{"纯文本", []byte("这是一段文字，不是图片"), "", false},
		{"空", []byte{}, "", false},
		{"exe 头", []byte{'M', 'Z', 0x90, 0x00}, "", false},
	}
	for _, c := range cases {
		got, ok := detectImageType(c.data)
		if ok != c.ok {
			t.Errorf("%s：判定应是 %v，实际 %v（%q）", c.name, c.ok, ok, got)
			continue
		}
		if ok && got != c.want {
			t.Errorf("%s：类型应是 %q，实际 %q", c.name, c.want, got)
		}
	}
}
