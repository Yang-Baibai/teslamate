package com.teslamate.app.ui

import com.teslamate.app.fmt

/**
 * 「上次读到多少、什么时候」那一行（**车外温度 / 车内温度共用** ⇒ 两处不许漂）。
 *
 * ⚠️ **v0.149.0（本轮手术点）**：这行原本是 v0.146.0 为**车外温度**内联写在界面里的；
 * 本轮给**车内温度**补同一行 ⇒ 同一件事**两处**实现 ⇒ 迟早漂成两个说法
 * （与「温度口径文案两端逐字一致」那条纪律同型）⇒ 收进**一个纯函数**。
 *
 * ## 什么时候显示
 *
 * - 当前读数**还在** ⇒ 不显示（没有「上次」可言）；
 * - **没有**上次读数 ⇒ 不显示（说「上次读到 —」是废话）；
 * - 有上次读数 ⇒ 「<标签>上次读到 <值> · <日期>」（没有时刻就不带日期）。
 *
 * ⚠️ 日期由调用方**先格式化好**再传进来（`fmtDate()` 用的是服务端时区）
 * ⇒ 这个函数**纯到可单测**（不碰时区 ⇒ 断言不会随机挂）。
 */
internal fun lastReadNote(
    label: String,
    current: Double?,
    last: Double?,
    atText: String?,
): String? {
    if (current != null || last == null) return null
    return label + "上次读到 " + last.fmt(1, "°C") + (atText?.let { " · $it" } ?: "")
}
