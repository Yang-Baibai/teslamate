package com.teslamate.app.ui

import com.teslamate.app.fmtKwh100

/**
 * ⚠️ **v0.126.0（第 23 轮 CDO Polish）：能耗-车速曲线卡 —— 主角那一组的两行对照数据。**
 *
 * ## 原来长什么样
 *
 * ```
 * 18.4                                                  ← 主角
 * 最省电速度档的能耗（kWh/100km，不含回充抵扣）              ← 标签
 * 100–110 km/h · 跑了 1234 公里                          ← 行 A
 * 跑得最多的档：130+ km/h · 1156 公里 · 18.7 kWh/100km    ← 行 B
 * ```
 *
 * 行 A 与行 B 是**同一类数据**（某个速度档 ＋ 你跑了多少公里［对照行还多一个能耗］），
 * 却用了**两种句式**：
 *
 * ① 行 A 带着一个**只有它有**的动词「**跑了**」⇒ 读者要在这两行之间换一次阅读方式；
 * ② 行 B 的标签与数据用**冒号**分隔，而全 App 的并列项一律用「·」
 *    （ADR #50：平行并列用「·」，不降级成括号 / 冒号）。
 *
 * ⇒ 现在两行**同形**：`档位 · 公里`（对照行多一个能耗）。
 *
 * ## 为什么抽成纯函数
 *
 * 文案要**被断言**（不能「看着对」就算）⇒ 纯函数 ＋ JVM 单测钉住；
 * ⇒ 客户端闸门 31 钉住「界面必须走这两个纯函数，纯函数里不许再出现冒号 / 「跑了」」。
 */
fun bestBandLine(label: String, km: Int): String =
    if (km > 0) "$label km/h · $km 公里" else "$label km/h"

/**
 * 「跑得最多的档 · 130+ km/h · 1156 公里 · 18.7 kWh/100km」。
 *
 * @param whPerKmGross **毛耗电**（Wh/km，内部口径；显示时 ÷10 ⇒ kWh/100km）。
 */
fun mostDrivenBandLine(label: String, km: Int, whPerKmGross: Double): String =
    "跑得最多的档 · $label km/h · $km 公里 · ${whPerKmGross.fmtKwh100()} kWh/100km"
