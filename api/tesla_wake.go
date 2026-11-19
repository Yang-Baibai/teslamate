package main

import (
	"context"
	"log"
	"strings"
	"time"
)

// ⚠️ **v0.179.0：车在休眠时的「唤醒 → 等 → 重试」队列。**
//
// ## 为什么要有
//
// 原来车睡着（Tesla 回 **408** `vehicle is offline or asleep`）时，
// 命令**直接失败**，接口回 409 ＋「先唤醒再试」⇒
// 用户在飞书里说「开空调」会**断成两步**（先发「唤醒」，再发「开空调」），
// 而唤醒**本身会耗电、可能计费**（官方把 wake_up 单列为 10 个单位）。
//
// ⇒ 现在：**自动唤醒 → 等它醒 → 重试原命令一次** ⇒ 一次交互就完成。
//
// ## 为什么这样是安全的
//
// ⚠️ **408 ⇒ 车根本没醒 ⇒ 绝对没执行过** ⇒ 所以重试**不会**重复执行。
//
//	（这与「2xx 但响应丢了」的情形不同 —— 那种不能盲目重试。）
//
// ## ⚠️ 等待时长是**超时上限**，不是「唤醒要多久」的断言
//
// 我们**不**知道这台车唤醒要几秒（那取决于车况、网络、温度），
// ⇒ 不编一个「通常要 N 秒」⇒ 只给一个**等不到就如实说**的上限。
//
// ⚠️ **上限取 10 秒**的理由：App 的 OkHttp `readTimeout` 是 **20 秒**
//
//	（app-android/.../Api.kt 的 sharedClient）⇒ 服务端阻塞的时间
//	必须留出「唤醒命令 + 重试命令」的往返 ⇒ 10 秒是**贴着**上限选的，
//	再长 App 会先超时，用户看到的是「服务器响应太慢」（方向完全错的提示）。
//
// ⇒ 没醒就**如实说**「已唤醒，但车还没醒 —— 请稍后再试一次」
//
//	（用户稍后再点一次时，车通常已经醒了 ⇒ 那次就会直接成功）。
const teslaWakeWait = 10 * time.Second

// teslaWakeDoneSuffix 唤醒后追加在「完成态文案」后面的说明。
//
// ⚠️ **为什么不加新字段**：加字段会让**旧版 App 解析失败**
//
//	（kotlinx.serialization 遇到未知字段默认抛异常 ⇒ 整个车控结果读不出来）
//	⇒ 而 `done` 这个字段**本来就有**、App 已经在渲染它 ⇒ 追加一句最省事。
//
// ⚠️ **宽度**：`done` 是服务端下发的常驻文案 ⇒ 受 appCopyWidthLimit（44）约束
//
//	⇒ 「已锁车（已唤醒）」= 18 宽 ⇒ 最长的「空调已开启（已唤醒）」= 22 宽 ⇒ 都够。
const teslaWakeDoneSuffix = "（已唤醒）"

// teslaWakeNotReadyText 唤醒了但还没醒时的那句话。
const teslaWakeNotReadyText = "已唤醒，但车还没醒 —— 请稍后再试一次"

// isTeslaAsleepError 判断这次失败是不是「车在休眠或离线」。
//
// ⚠️ **沿用 handler 里那个判据**（`strings.Contains(res.Error, "408")`）
// ⇒ 两处**必须同源** ⇒ 抽成函数，别各写一份（会漂）。
func isTeslaAsleepError(res *teslaCommandResult) bool {
	if res == nil || res.Sent {
		return false
	}
	return strings.Contains(res.Error, "408")
}

// wokeDone 在完成态文案后面追加「（已唤醒）」。
//
// ⚠️ 已经追加过就**不再追加**（幂等）—— 否则重复调用会叠成「（已唤醒）（已唤醒）」。
func wokeDone(done string) string {
	if done == "" {
		return teslaWakeDoneSuffix
	}
	if strings.Contains(done, teslaWakeDoneSuffix) {
		return done
	}
	return done + teslaWakeDoneSuffix
}

// wakeAndRetry 车在休眠时：**唤醒 → 等 → 重试原命令一次**。
//
// @return 新的结果（已带上唤醒披露）；nil = 唤醒这条路走不通
//
//	（唤醒命令本身失败 ⇒ 车大概率离线 ⇒ 交回调用方走原来的 409 提示）。
//
// ⚠️ **幂等**：408 ⇒ 车没醒 ⇒ 原命令**确定没执行** ⇒ 重试安全。
func (c *teslaClient) wakeAndRetry(ctx context.Context, action string, params map[string]any) *teslaCommandResult {
	// ① 唤醒
	wakeRes, err := c.sendTeslaCommand(ctx, "wake_up", map[string]any{})
	if err != nil || wakeRes == nil || !wakeRes.Sent {
		log.Printf("ℹ️  车在休眠，唤醒未成功 ⇒ 交回调用方（可能离线）：%v", err)
		return nil
	}

	// ② 等它醒（**超时上限**，不是唤醒时长的断言）。
	select {
	case <-time.After(teslaWakeWait):
	case <-ctx.Done():
		return nil
	}

	// ③ 重试原命令一次。
	res, err := c.sendTeslaCommand(ctx, action, params)
	if err != nil || res == nil {
		return nil
	}
	if isTeslaAsleepError(res) {
		// 唤醒了但还没醒 ⇒ 如实说，不假装成功。
		res.State = teslaCmdStateFailed
		res.Error = teslaWakeNotReadyText
		res.Done = ""
		return res
	}
	// 成功 ⇒ 披露「顺带把车叫醒了」。
	res.Done = wokeDone(res.Done)
	log.Printf("🚗 车在休眠，已先唤醒再执行：action=%s state=%s", action, res.State)
	return res
}
