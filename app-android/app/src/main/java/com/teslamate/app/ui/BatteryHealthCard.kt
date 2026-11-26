package com.teslamate.app.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.teslamate.app.BatteryHealth
import com.teslamate.app.fmt
import kotlin.math.roundToInt

/**
 * ⚠️ **v0.189.0：车况页的「电池健康」卡。**
 *
 * ## 为什么加这张卡
 *
 * 用户的原话是「接下来的重点方向是检测电池健康和电池状态，可以在车况页面加入相关的卡片」。
 * ⇒ 「电池健康」（容量还剩多少、有没有在持续衰减）**服务端早就算好了**
 * （`GET /api/v1/cars/{id}/battery` ⇒ 用量页那张卡），
 * 但**只在用量页** ⇒ 车主在车况页（每天看得最多的那一页）看不到。
 *
 * ## 这一轮的原则：**只透传，零新文案**
 *
 * ① 判定、阈值、口径说明**全在服务端**（ADR #38 / ADR #41）⇒ 客户端只做排版；
 * ② 两段口径说明**直接复用**既有的纯函数
 * （[noteBatteryHealth] 相对出厂、[noteRecentTrend] 相对首测）⇒ **不新增第二份**；
 * ③ 衰减结论行复用 [BatteryDecayText.headline]（照服务端的结论强度说话）。
 *
 * ## 「两把尺子」的取舍（ADR #100）
 *
 * 电池有两个健康数：**相对出厂**（SOH）与**相对首测**。
 * 车况页常驻给**相对出厂**（车主问「电池还好吗」时想要的那个数），
 * **相对首测**折进「更多明细」（它回答的是「有没有在持续掉」⇒ 属趋势，不属体检）。
 *
 * ⚠️ 「相对出厂」那个数（线上 83.1%）的分母是**铭牌容量**（78.4 度），
 * 而分子是「续航估值 × 效率常数」反推的 ⇒ **两把尺子不是同一把** ⇒ 它的 ⓘ 里必须说清
 * （[noteBatteryHealth] 里那句「分子里的效率常数服务端可改，改了就变」）。
 *
 * ## 降级
 *
 * - 服务端没给容量**也没有**健康度 ⇒ **整张卡不出现**（宁可不说，也不说半句）；
 * - 取不到 `/battery`（服务器挂掉 / 老服务端）⇒ **不显示这张卡**，且**不报错**
 *   （那是服务器的问题，不是车的问题 ⇒ 不该在车况页弹一条红条）。
 */
data class BatteryHealthCardData(
    /** 相对出厂的健康度（%）—— **服务端给的**，客户端不许自己拿两个容量相除。 */
    val healthPercent: Double?,
    /** 现在还能装多少度（服务端反推的可用容量）。 */
    val capacityKwh: Double?,
    /** 「哪一次充电测的」—— **服务端写的短文案**（时间诚实性，ADR #41）。 */
    val measurementNoteShort: String?,
    /** 「相对出厂」那段口径说明（复用 [noteBatteryHealth]）。 */
    val healthNote: String,
    /** 相对首测（%）—— 服务端给的（不受用户可改的效率常数影响）。 */
    val baselinePercent: Double?,
    /** 「相对首测」那段口径说明（复用 [noteRecentTrend]）。 */
    val baselineNote: String,
    /** 衰减结论（按服务端的结论强度排版，[BatteryDecayText.headline]）。 */
    val decayHeadline: String?,
    /** 衰减结论的依据 —— **服务端逐字透传**（老服务端没这个字段 ⇒ 不显示）。 */
    val decayNote: String?,
    /** 数据覆盖的天数（服务端给的）。 */
    val spanDays: Int?,
    /** 数据覆盖的里程（公里，服务端给的）。 */
    val spanKm: Double?,
    /** 参与判定的满充点个数（服务端给的）。 */
    val strictPoints: Int?,
    /** 累计循环那一句（**服务端渲染好的**，App 与飞书体检报告印同一句）。 */
    val usageLine: String?,
    /** 累计循环的口径说明（服务端唯一一份）。 */
    val usageNote: String?,
)

/**
 * ⚠️ **v0.189.0**：把服务端的 [/battery] 结论整理成车况页这张卡要显示的东西。
 *
 * ## 为什么是纯函数
 *
 * 这张卡的**取舍**（显示哪个数、缺什么就不显示）是**口径**，必须能被单测钉住
 * ⇒ 不能埋在 Composable 里。
 *
 * @return null ⇒ **整张卡不出现**（没有容量也没有健康度 ⇒ 没什么可说的）。
 */
fun batteryHealthCardData(b: BatteryHealth): BatteryHealthCardData? {
    val health = b.health_percent
    val capacity = b.current_capacity_kwh
    // ⚠️ 什么都没有 ⇒ 整张卡不出现（宁可不说，也不说半句 —— v0.85.0 的老规矩）。
    if (health == null && capacity == null) return null
    // ⚠️ **v0.22.0（P1-5）**：历史最好时用**严格档**（只取充到 100% 的点）——
    // 「≥90% 的点」系统性偏高 0.56%（车机的续航-电量曲线顶部是平的）。
    val strictMax = b.max_capacity_kwh_strict ?: b.max_capacity_kwh
    val nominal = b.nominal_capacity_kwh
    val decay = b.decay
    return BatteryHealthCardData(
        healthPercent = health,
        capacityKwh = capacity,
        measurementNoteShort = b.measurement?.note_short?.takeIf { it.isNotBlank() },
        healthNote = noteBatteryHealth(strictMax, nominal),
        baselinePercent = b.health_percent_of_baseline,
        baselineNote = noteRecentTrend(),
        decayHeadline = decay?.let { BatteryDecayText.headline(it) },
        decayNote = decay?.note?.takeIf { it.isNotBlank() },
        spanDays = b.series_span_days,
        spanKm = b.odometer_span_km,
        strictPoints = decay?.n,
        // ⚠️ **v0.192.0**：那一句**逐字**取服务端（客户端一个字都不拼 ⇒ ADR #101）。
        usageLine = b.usage?.line?.takeIf { it.isNotBlank() },
        usageNote = b.usage?.note?.takeIf { it.isNotBlank() },
    )
}

/**
 * ⚠️ **v0.189.0**：车况页的「电池健康」卡。
 *
 * ## 版面上的三条规矩
 *
 * ① **不抢主角**：车况页的主角是电量环（44sp）⇒ 这张卡用**次级档 26sp**
 * （层级 44 : 26 = 1.69×，v0.35.0 的规矩）；
 * ② **ⓘ 挂在它解释的那一行行尾**（v0.68.0 的规矩）——
 * 相对出厂的口径挂在副标题行（那儿摆着这条口径的两个操作数），
 * 衰减结论的依据挂在结论行 ⇒ 不在卡底堆三个一样的裸 ⓘ；
 * ③ **默认只留一个折叠入口**（v0.29.0 的规矩）⇒「相对首测」与覆盖范围折进「更多明细」。
 */
@Composable
fun BatteryHealthCard(b: BatteryHealth, modifier: Modifier = Modifier) {
    val d = batteryHealthCardData(b) ?: return
    SectionCard(title = "电池健康", modifier = modifier) {
        d.healthPercent?.let {
            BigStat(
                "${it.fmt(1)}%",
                "电池健康度（相对出厂）",
                mid = true,
            )
            Spacer(Modifier.height(2.dp))
        }
        // ⚠️ **副标题给的是「还能装多少度」** —— 百分比是**比值**，单看没有锚点；
        // 车主真正关心的是「还能装多少电」。「哪一次充电测的」由服务端给（时间诚实性）。
        TextWithNote(
            text = batteryCapacitySubtitle(d.capacityKwh, null, d.measurementNoteShort),
            note = d.healthNote,
            style = MaterialTheme.typography.labelMedium,
            a11yLabel = "电池健康度的口径",
        )
        // ⚠️ **衰减结论**（服务端判定 ⇒ 客户端只按它的**结论强度**说话，v0.41.0）。
        d.decayHeadline?.let { headline ->
            val note = d.decayNote
            if (note != null) {
                TextWithNote(
                    text = headline,
                    note = note,
                    style = MaterialTheme.typography.labelSmall,
                    a11yLabel = "电池衰减结论的依据",
                )
            } else {
                // ⚠️ 服务端没给依据（老服务端）⇒ **只印结论，不说半句**。
                Text(
                    headline,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // ⚠️ **v0.192.0：充电习惯与累计循环**（服务端算的**事实** ⇒ 客户端只印它那一句）。
        //
        // ⚠️ 印的是**服务端渲染好的那一句**（与飞书体检报告同一句）⇒ 两端不会各说各话。
        // ⚠️ 服务端没给（老服务端 / 没充电记录）⇒ **不显示**（宁可不说，也不说半句）。
        val usageNote = d.usageNote
        d.usageLine?.let { line ->
            if (usageNote != null) {
                TextWithNote(
                    text = line,
                    note = usageNote,
                    style = MaterialTheme.typography.labelSmall,
                    a11yLabel = "累计循环的口径",
                )
            } else {
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // ⚠️ **v0.29.0**：默认态只留**一个**折叠入口 ⇒ 其余都折进去。
        val cover = buildString {
            if (d.spanDays != null) {
                append(d.spanDays)
                append(" 天")
            }
            if (d.spanKm != null) {
                if (isNotEmpty()) append(" / ")
                append(d.spanKm.roundToInt())
                append(" 公里")
            }
            if (d.strictPoints != null) {
                if (isNotEmpty()) append(" · ")
                append(d.strictPoints)
                append(" 个满充点")
            }
        }
        if (d.baselinePercent != null || cover.isNotEmpty()) {
            CollapsibleSection(label = "更多明细") {
                d.baselinePercent?.let {
                    // ⚠️ 「相对首测」是**趋势**（有没有在持续掉）⇒ 折进明细。
                    TextWithNote(
                        text = "相对首测 ${it.fmt(1)}%",
                        note = d.baselineNote,
                        style = MaterialTheme.typography.labelSmall,
                        a11yLabel = "相对首测的口径",
                    )
                }
                if (cover.isNotEmpty()) {
                    Text(
                        cover,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
