package main

import (
	"strings"
	"testing"
	"time"
)

// ⚠️ **v0.179.0：休眠唤醒队列的护栏。**
//
// 这些断言钉住三件事：
// ① 「车在休眠」的判据**只有一处**（isTeslaAsleepError）——
//    handler 与 wakeAndRetry 都用它 ⇒ 两处不许各写一份（会漂）。
// ② 唤醒后的**披露文案**（done 追加「（已唤醒）」）且**幂等**。
// ③ 披露文案**不许超宽**（常驻文案上限 44，api/app_copy.go 的 appCopyWidthLimit）。

func TestIsTeslaAsleepError(t *testing.T) {
	cases := []struct {
		name string
		res  *teslaCommandResult
		want bool
	}{
		{"命令成功了", &teslaCommandResult{Sent: true}, false},
		{"车睡着（408）", &teslaCommandResult{Error: "upstream said 408 vehicle is offline or asleep"}, true},
		{"别的失败", &teslaCommandResult{Error: "500 internal"}, false},
		{"没失败", &teslaCommandResult{}, false},
		{"nil", nil, false},
	}
	for _, c := range cases {
		if got := isTeslaAsleepError(c.res); got != c.want {
			t.Errorf("%s: isTeslaAsleepError = %v，期望 %v", c.name, got, c.want)
		}
	}
}

// TestWokeDoneDiscloses 唤醒后必须在完成态文案里**披露**。
//
// ⚠️ 为什么必须披露：唤醒会耗电、可能计费 ⇒ 用户有权知道车被叫醒了。
func TestWokeDoneDiscloses(t *testing.T) {
	if got := wokeDone("已锁车"); got != "已锁车（已唤醒）" {
		t.Errorf("唤醒后应披露，得到 %q", got)
	}
	// 幂等：重复调用不许叠成「（已唤醒）（已唤醒）」。
	once := wokeDone("已锁车")
	if twice := wokeDone(once); twice != once {
		t.Errorf("披露应幂等，%q => %q", once, twice)
	}
	// 没有完成态文案时也要给出披露。
	if got := wokeDone(""); got != "（已唤醒）" {
		t.Errorf("空文案时也应披露，得到 %q", got)
	}
}

// TestWokeDoneWidth 披露后的文案不许超宽（上限 44，同 appCopyWidthLimit）。
func TestWokeDoneWidth(t *testing.T) {
	for _, done := range []string{"已锁车", "空调已开启", "已解锁", "充电上限已设定", "车窗已关好"} {
		full := wokeDone(done)
		if w := copyVisualWidth(full); w > appCopyWidthLimit {
			t.Errorf("唤醒披露后宽度 %d > %d：%q", w, appCopyWidthLimit, full)
		}
	}
}

// TestTeslaWakeNotReadyTextHonest 唤醒了还没醒 ⇒ **不许**报成功。
func TestTeslaWakeNotReadyTextHonest(t *testing.T) {
	if !strings.Contains(teslaWakeNotReadyText, "还没醒") {
		t.Errorf("没醒的提示要说清「还没醒」：%q", teslaWakeNotReadyText)
	}
	for _, bad := range []string{"已完成", "已锁车", "成功"} {
		if strings.Contains(teslaWakeNotReadyText, bad) {
			t.Errorf("没醒的提示里不该出现成功字样 %q：%q", bad, teslaWakeNotReadyText)
		}
	}
}

// TestTeslaWakeWaitWithinAppTimeout 唤醒等待必须**短于** App 的读超时。
//
// ⚠️ App 的 OkHttp readTimeout = 20 秒（app-android/.../Api.kt 的 sharedClient）
// ⇒ 服务端阻塞超过它 ⇒ App 先超时，用户看到「服务器响应太慢」（方向错的提示）。
func TestTeslaWakeWaitWithinAppTimeout(t *testing.T) {
	const appReadTimeout = 20 * time.Second
	if teslaWakeWait >= appReadTimeout {
		t.Fatalf("唤醒等待 %v 必须短于 App 的读超时 %v", teslaWakeWait, appReadTimeout)
	}
	// 还要给「唤醒命令 + 重试命令」的往返留出余量。
	if teslaWakeWait > appReadTimeout-8*time.Second {
		t.Fatalf("唤醒等待 %v 离 App 的读超时 %v 太近，来回命令就没余量了", teslaWakeWait, appReadTimeout)
	}
}
