package com.teslamate.app.ui

import com.teslamate.app.relTime
import java.time.Duration
import java.time.Instant

/**
 * 锁车状态**旧到什么程度**才算「旧值」（分钟）。
 *
 * ⚠️ **v0.75.0 定下的线**：5 分钟内 = 新鲜 ⇒ 不提「旧」（没脏就别提脏）。
 */
const val FLEET_LOCK_STALE_MINUTES = 5L

/**
 * 车况页那条**灰横幅**的文案（**纯函数** ⇒ 单测钉得住）。
 *
 * ## 它在说什么
 *
 * 横幅出现在**里程表下面**（v0.14.0：中性横幅下沉到主角数字之后），
 * 讲的是**顶部那行状态芯片**里的锁车状态。
 *
 * ## ⚠️ v0.171.0：修掉一句**自相矛盾**的话
 *
 * 屏幕上原来出现过：
 *
 * > 「—— 上面显示的是 **刚刚**读到的**旧值**。」
 *
 * ⇒ **既「刚刚」又「旧」** —— 这正是 v0.75.0 明说要消灭的那句，但**只修了一半**：
 *   - **判据**用的是 @BT@locked_at@BT@（**这个值**的采集时间）⇒ 5 分钟内就不说「旧值」；
 *   - **措辞**里印的却是 @BT@fleet_last_ok@BT@（**另一次**成功获取的时间）⇒ 两个时钟。
 *
 * ⇒ 于是「值很旧、但服务端刚成功拉过一次」时 ⇒ 判据说「旧」⇒ 措辞说「刚刚」⇒ **矛盾复现**。
 *   （2026-11-18 模拟器实拍：车况页真的印着这句。）
 *
 * ⇒ 现在：**判据与措辞都用 @BT@locked_at@BT@** ⇒ 结构上不可能再自相矛盾；
 *   而 @BT@locked_at@BT@ **读不到**时**整句不说**（宁可不说，也不说半句 —— v0.85.0 的老规矩）。
 *
 * ## 指代锚点
 *
 * 横幅在**下方**，而它说的是**顶部**那行 ⇒ 只说「上面显示的是」⇒ 读者会以为指里程表
 * ⇒ 现在明说「顶部那行的锁车状态」（与用户反馈 #26「决策句要加指代锚点」同型）。
 *
 * @param locked 当前锁车状态（null = 没读到）
 * @param lockedAt **这个值**的采集时间（判据与措辞的唯一时间源）
 * @param fleetError 服务端给的实时状态获取错误（可空）
 * @param fleetLastOk 最后一次成功获取实时状态的时间（**只在没读到锁车状态时**用）
 * @param fleetState 服务端的实时状态源状态（disabled / asleep / …）
 */
fun fleetNoticeText(
    locked: Boolean?,
    lockedAt: Instant?,
    fleetError: String,
    fleetLastOk: Instant?,
    fleetState: String?,
    now: Instant = Instant.now(),
): String? {
    val sb = StringBuilder()
    if (locked == null) {
        when (fleetState) {
            "disabled" ->
                sb.append("这台车没连实时状态源，锁车与实时胎压拿不到 —— 不是车的问题。")
            // ⚠️ **v0.75.0（模拟器真机复核）：不再重复「车在休眠」。**
            //
            // 页面顶部那行状态芯片已经写着「车在休眠 · 锁车状态等它醒来才更新」——
            // 横幅又说一遍 ⇒ 同一个事实说了两遍 ⇒ 两块灰横幅把电量环挤到第三屏。
            //
            // 上面那段注释写的分工是「**横幅 = 顶部那行没有的信息**」⇒ 那就照它做：
            // 顶部说了「车在休眠 + 锁车状态」，横幅只补**实时胎压**这一条。
            "asleep" ->
                sb.append("实时胎压要等它醒来才更新 —— 不是车的问题。")
            else ->
                sb.append("没读到车机的锁车状态 —— 这不代表车没锁。")
        }
        // 「最后一次成功获取」是**另一个事实**（服务端最近一次成功拉到实时状态）
        // ⇒ 只在「没读到锁车状态」这条路上有意义。
        fleetLastOk?.let { ok ->
            sb.append("（最后一次成功获取：${ok.relTime(now)}）")
        }
    } else {
        // ⚠️ **v0.171.0**：判据与措辞**同一个时间源**（lockedAt）⇒ 不可能自相矛盾。
        val ageMin = lockedAt?.let { Duration.between(it, now).toMinutes() }
        if (ageMin != null && ageMin >= FLEET_LOCK_STALE_MINUTES) {
            sb.append(" —— 顶部那行的锁车状态，是 ${lockedAt.relTime(now)}读到的旧值。")
        }
    }
    if (fleetError.isNotBlank()) {
        if (sb.isNotEmpty()) sb.append(" ")
        sb.append(fleetError)
    }
    return sb.toString().takeIf { it.isNotBlank() }
}
