package com.teslamate.app.ui

/**
 * ⚠️ **v0.122.0（本轮手术点）：充电详情「实测充到 80% 用了多久」—— 自由句改 KV 行。**
 *
 * ## 原来长什么样
 *
 * 「电量曲线」卡里，曲线下面**平铺**着一句：
 *
 *   本次实测：从 68% 充到 80% 用了 36 分钟（读自充电曲线）
 *
 * 三个毛病：
 *
 * ① **它是那一屏唯一的自由句** —— 同屏「充电路况」卡里的邻居全是 KV 行
 *    （「该站同起点实测中位」「从 65% 充到 80%（折算）」…）
 *    ⇒ 同一个屏幕两套行样式，扫不下去。
 * ② **同一个事实有两套说法** —— 服务端给了数时是「本次实测：…（读自充电曲线）」，
 *    老服务端没这个字段时走客户端兜底、写成「实测从 …（从曲线直接读）」
 *    ⇒ 两条**互斥**分支各说各话 ⇒ 用户看到的是「今天这句、明天那句」。
 * ③ **它太长** —— 正常字号（bodySmall = 13sp）就要 **346.06dp**，
 *    200% 字号（26sp）要 **692.12dp**；而卡片内容区只有 **328~361dp**
 *    ⇒ 正常字号在 360dp 窄屏上就已经放不下，大字号更是折成两三行。
 *    （宽度一律用 `estimateTextWidthDpWithNbsp` 算，别手算。）
 *
 * ## 现在长什么样
 *
 *   本次实测：68% 充到 80%          36 分钟
 *
 * 一行 KV：标签说**口径**（从哪个起点、充到哪个），值只放**那个数**。
 * 「读自充电曲线」这类**来源披露**折进 ⓘ（[noteMeasuredTo80]）——
 * 数据一点没丢，只是从平铺改成点开才看（v0.65.0 起的既有做法）。
 *
 * ## 为什么两条分支必须共用一个纯函数
 *
 * 服务端口径（`measured_minutes_to_80` + `measured_from_soc`）与客户端兜底
 * （自己从曲线算）**产出的是同一个事实**：从曲线第一个采样点充到 80% 用了多久。
 * ⇒ 既然是同一个事实，就**只能有一句话**
 * （本项目的老规矩：同一个数只能有一个说法，ADR #38 / #47）。
 * ⇒ 客户端闸门 29 钉住「两条分支都走这个纯函数」。
 */

/** 那一行的**标签**：说清口径（从哪个起点、充到哪个），不含那个数。 */
fun measuredTo80Label(fromSoc: Int): String = "本次实测：$fromSoc% 充到 80%"

/** 那一行的**值**：只有那个数（数与单位之间用不换行空格 —— ADR #42）。 */
fun measuredTo80Value(minutes: Int): String = "$minutes${NBSP}分钟"

/**
 * 那一行的 ⓘ 口径说明（**折起来**才看）。
 *
 * ⚠️ 为什么折：这一行下面就是曲线 ⇒「读自充电曲线」平铺着说是**重复**；
 * 而「起点是曲线的第一个采样点」这句**不重复**（它是口径，别的行没有）
 * ⇒ 折进 ⓘ，一个字不删（v0.65.0 起：数据一点没丢，只是从平铺改成点开才看）。
 */
fun noteMeasuredTo80(): String =
    "从这次充电的电量曲线直接读出来的：曲线第一次到 80% 的时刻减去曲线起点。" +
        "起点是曲线的第一个采样点，不一定是你插枪时的电量。"

/** 那一行**标签**的字号：bodySmall = **13sp**（见 ui/Theme.kt；⚠️ 别拿 M3 默认值算）。 */
const val MEASURED_LABEL_SP = 13f

/** 那一行**值**的字号：bodyMedium = **15sp**。 */
const val MEASURED_VALUE_SP = 15f

/**
 * 那一行的**最窄**内容宽度（dp）：360dp 窄屏 − 卡片左右内边距 32dp = **328dp**。
 *
 * ⚠️ 用**最窄**那一档，不是 `FULL_WIDTH_DP`（361dp，393dp 屏）——
 * 宽度预算要按**放得下的那一台**算，不是按最宽的那台（ADR #43 / #45 同一纪律）。
 */
const val MEASURED_ROW_CONTENT_DP = 328f

/** 那一行的标签在某字号倍率下需要的宽度（dp）—— 一律用**估算器**算，别手算。 */
fun measuredTo80LabelNeededDp(label: String, fontScale: Float): Float =
    estimateTextWidthDpWithNbsp(label, MEASURED_LABEL_SP * fontScale)

/** 那一行的值在某字号倍率下需要的宽度（dp）—— 一律用**估算器**算，别手算。 */
fun measuredTo80ValueNeededDp(value: String, fontScale: Float): Float =
    estimateTextWidthDpWithNbsp(value, MEASURED_VALUE_SP * fontScale)
