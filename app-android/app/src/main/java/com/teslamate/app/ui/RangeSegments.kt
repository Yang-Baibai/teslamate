package com.teslamate.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teslamate.app.Charge
import com.teslamate.app.Drive
import com.teslamate.app.Haptics
import com.teslamate.app.fmtDate
import java.time.Instant
import kotlin.math.roundToInt

/**
 * ⚠️ **v0.27.0（第 17 轮官方 App 对标评审 A-4）：实测满电续航。**
 *
 * ## 官方 App 给不了
 *
 * 官方 App 只显示**车机上报的标定续航**（EPA 值）。
 * 那个数是「按 146 Wh/km 还能跑多远」的**能量表**，
 * 跟真实路况（市区 / 高速 / 山路 / 气温）完全无关。
 *
 * 我们有 `charging_processes`：每一次充电都记了**充前电量和充后电量**，
 * 加上 `positions` 的里程表 —— 于是可以直接量出
 * **「从 100% 开到 8%，实跑了多少公里」**。
 *
 * 实测样本（本机 87 天，**剔除里程表跳段之后**）：
 * - 最好：10-02 那趟高速，**596 公里/满电**（⚠️ 见下面的「山地下坡」提醒）
 * - 市区工况多数落在 **340~360 公里/满电**
 * - **中位 351 公里**，官方标称满电 **448 公里**（17 场满充的中位数）
 *
 * ## ⚠️ 「最好」那个数要打折看
 *
 * 596 公里那趟是**山地下坡**的产物：海拔 1389~4327 m（均 3228 m），
 * 回充让等效能耗降到 110 Wh/km —— **平路跑不出这个数**。
 * 所以卡片上把「最好」标成「最好（含下坡）」，不拿它当规划依据。
 *
 * ## 口径
 *
 * 一段续航 = 从**一次充电结束**到**下一次充电开始**。
 * 里程 = 下一次充电的起始里程 − 上一次充电的结束里程
 * （含开到充电桩的绕行 —— 那也是真跑的公里）。
 *
 * 折算成满电：`公里数 ÷ 掉电百分比 × 100`。
 *
 * ⚠️ **必须自曝**：这是**折算值**，不是「某一次满电跑了 X 公里」。
 * 而且市区与高速差 1.7 倍，**中位数不代表任何一次具体出行**。
 */
data class RangeSegment(
    val startOdoKm: Double,
    val endOdoKm: Double,
    val km: Double,
    /** 离开上一个充电点时的电量 */
    val fromSoc: Int,
    /** 到达下一个充电点时的电量 */
    val toSoc: Int,
    /** 掉电百分比 */
    val socUsed: Int,
    val startDate: Instant,
    /** 折合满电里程（km） */
    val fullChargeKm: Double,
)

/**
 * 从充电记录里重建续航段。
 *
 * @param minSocUsed 掉电少于这个百分点的段**不算** ——
 *   差几十公里的短途（掉电 5%~15%）折算出来的「满电里程」噪声极大，
 *   混进来会把中位数拉偏。
 * @param drives **必须传**：用来剔除「里程表跳了但中间没有行程记录」的假段。
 *
 * ## ⚠️ 为什么要 drives
 *
 * 实测有一段**物理上不可能**的数据：
 * 2026-08-15 → 08-22 之间里程表走了 **366 公里**，而电量只掉了 **21%** ——
 * 折算出来是 **1744 公里/满电**（特斯拉做不到，实际是 37 Wh/km）。
 *
 * 原因：那 303 公里**根本没有行程记录**（里程表与行程表之间有空洞）——
 * 所以「掉电 21%」和「跑了 366 公里」根本不是同一段时间里的事。
 *
 * 现在要求：**这一段里的行程里程之和 ≥ 里程表跨度的 90%**，
 * 否则说明中间有没被记录的路段 → **这一段不算**（宁可少一段，也不给一个物理上不可能的数）。
 */
fun buildRangeSegments(
    charges: List<Charge>,
    drives: List<Drive> = emptyList(),
    minSocUsed: Int = 20,
    /**
     * ⚠️ **v0.29.0（复审 P0-2）：官方标称满电续航（公里）** ——
     * 用来做**物理可行性**判据：折算满电里程超过它 1.5 倍的段，物理上不可能，剔除。
     * 为 null 时退回「行程覆盖率」判据（老逻辑）。
     */
    officialRangeKm: Double? = null,
): List<RangeSegment> {
    val sorted = charges
        .filter { it.start_odometer_km != null && it.start_soc != null && it.end_soc != null }
        // ⚠️ **v0.28.0（复审 P0-⑤）：同里程的充电必须按**时间**升序排。**
        //
        // 实测 ids 43/44 的里程**完全相同**（97726.898642）——
        // 它们是**一次插枪被切成的两段**（43: 68%→75%，44: 74%→100%）。
        // 而稳定排序会保持 API 返回的**日期倒序**，于是「下一场充电」变成了 44，
        // 续航段配成 (41, 44) → 掉电算成 26% → 折算满电 **734 公里**（物理上不可能）。
        // 真实应该是 (41, 43) → 掉电 32% → **596 公里**。
        .sortedWith(compareBy({ it.start_odometer_km }, { it.start_date }))
    // 行程按里程排序，用来核对「这一段到底有没有真的开过」
    val sortedDrives = drives
        .filter { it.odometer_start != null && it.odometer_end != null && (it.distance_km ?: 0.0) > 0 }
        .sortedBy { it.odometer_start!! }
    val out = ArrayList<RangeSegment>()
    for (i in 0 until sorted.size - 1) {
        val a = sorted[i]
        val b = sorted[i + 1]
        val odoA = a.end_odometer_km ?: a.start_odometer_km ?: continue
        val odoB = b.start_odometer_km ?: continue
        val km = odoB - odoA
        if (km <= 5.0) continue
        // ⚠️ v0.27.0：**核对这段是不是真的全程有记录。**
        //
        // ⚠️ **v0.28.0（复审 P2-12）：要按里程**裁剪**后累加，不能整条计入。**
        // 原来只要「行程的终点 > 段起点 且 行程的起点 < 段终点」就把**整条**行程的里程算进来 ——
        // 复审实测：裁剪前有 **24 段**的比值 > 1（最极端 **1.952**），
        // 判据形同虚设。裁剪后最大的比值降到 **≤1.001** ✓，
        // 保留/剔除的段数不变（37 / 5）—— 说明裁剪没有误伤真段。
        val recordedKm = sortedDrives.sumOf { d ->
            val s = d.odometer_start!!
            val e = d.odometer_end!!
            val overlap = minOf(e, odoB) - maxOf(s, odoA)
            if (overlap <= 0.0) {
                0.0
            } else {
                val len = e - s
                val dist = d.distance_km ?: 0.0
                if (len <= 0.0) 0.0 else dist * (overlap / len)
            }
        }
        // 没有行程数据时（drives 为空）**不做校验** —— 不能因为拿不到行程就把全丢掉。
        //
        // ⚠️ **v0.29.0（复审 P0-2）：判据从「行程覆盖率」改成「物理可行性」。**
        //
        // 原来：`recordedKm < km * 0.9` 就杀。
        // 两位评审独立复算发现**误杀真段**：
        //   被剔除的 5 段里，rn9（546.5 公里、掉电 90%）是**真段** ——
        //   09-12 里程 93350.2，09-14/15/16 三个采样点都在**家**（22.57548/113.86248）、
        //   电量 16%、里程 93852.8 → **里程 +502.6 公里且电量 −80%**，
        //   两条独立信号同时证明车真跑了约 500 公里往返
        //   （只是 TeslaMate 睡着没记录行程）。
        //   折算 607 公里/满电，**物理可行**。
        //   另两段（rn5 1744 公里、rn8 1147 公里/满电）才该杀。
        //
        // 现在改成**物理可行性**判据：折算满电里程 > 官方标称 × 1.5 → 物理上不可能 → 剔除。
        // 实测：保留 40 → **43** 段，剔除 5 → **2** 段（正是该剔的那两段）。
        //
        // 为什么 1.5：官方标称 448 公里 × 1.5 = 672 公里；
        // 而本机**实测**的高速满电纪录是 607 公里（1.36×）—— 1.5 留了一点余量。
        val fromSoc = a.end_soc ?: continue
        val toSoc = b.start_soc ?: continue
        val socUsed = fromSoc - toSoc
        if (socUsed < minSocUsed) continue
        val fullChargeKm = km / socUsed * 100.0
        val maxFeasible = officialRangeKm?.let { it * 1.5 }
        if (maxFeasible != null) {
            // 折算满电里程超过官方标称 1.5 倍 → 物理上不可能（这台车做不到）→ 剔除
            if (fullChargeKm > maxFeasible) continue
        } else if (sortedDrives.isNotEmpty() && recordedKm < km * 0.9) {
            // 没有官方标称可比时，退回「行程覆盖率」判据
            continue
        }
        out += RangeSegment(
            startOdoKm = odoA,
            endOdoKm = odoB,
            km = km,
            fromSoc = fromSoc,
            toSoc = toSoc,
            socUsed = socUsed,
            startDate = a.end_date ?: a.start_date,
            fullChargeKm = fullChargeKm,
        )
    }
    return out
}

/**
 * 官方标称的**满电**续航（公里）。
 *
 * 取「充到 ≥99% 的那些场次」的车机额定续航中位数 ——
 * 那就是车机在满电时显示的那个数，正是官方 App 给用户的那个数。
 */
fun officialFullChargeRangeKm(charges: List<Charge>): Double? {
    val full = charges.filter { (it.end_soc ?: 0) >= 99 && it.end_range_km != null }
    if (full.isEmpty()) return null
    return com.teslamate.app.medianOf(full.map { it.end_range_km!! })
}

/**
 * ⚠️ **v0.40.0（第 24 轮对抗性审计 ①）：「实测满电续航 vs 官方标称」的百分比差。**
 *
 * ## 这个函数为什么存在
 *
 * 原来的百分比**方向反了**，而且错得极其隐蔽：
 * ```kotlin
 * val diff = (median / it * 100).roundToInt()          // 比值×100，恒 ≥ 0
 * append(if (diff >= 0) "，实测高 $diff%" else "，实测低 ${-diff}%")
 * ```
 * ① `median / it * 100` 是**比值×100**（恒 ≥ 0）⇒ `else` 分支是**永远走不到的死代码**；
 * ② 它被当成「超出 100 的百分比」用 ⇒ 语义整体错位。
 *
 * 实测本车：中位 **346.62** 公里 / 官方标称 **448.01** 公里
 *   ⇒ 比值 77.4% ⇒ 旧代码打印「官方标称 448 公里，实测**高 77%**」
 *   ⇒ 真相是「实测**低 23%**」（100 − 77.4 = 22.6）。
 *
 * 一个让 App 看起来比官方**强 77%**、而实际**弱 23%** 的假恭维。
 * 这个 bug 从 v0.27.0 活到 v0.39.0（12 个版本、23 轮评审全漏）——
 * 因为历次审计核的是**手算值**，不是**屏幕输出**。
 *
 * ## 返回值
 *
 * 正数 = 实测**高**出百分之几；负数 = 实测**低**百分之几。
 */
fun fullChargeVsOfficialPercent(medianKm: Double, officialKm: Double): Int? {
    if (officialKm <= 0.0 || medianKm <= 0.0) return null
    return ((medianKm / officialKm * 100.0) - 100.0).roundToInt()
}

/**
 * 「实测满电续航」卡片。
 *
 * ⚠️ 默认**折叠** —— 它是「参考知识」而不是「现在能开多少」，
 * 抢首屏会挤压记录页的历史列表（v0.14.0 同样的教训）。
 */
@Composable
fun RangeSegmentsCard(
    charges: List<Charge>,
    /** ⚠️ v0.27.0：用来核对「这一段是不是真的全程有行程记录」。 */
    drives: List<Drive> = emptyList(),
    modifier: Modifier = Modifier,
) {
    // ⚠️ v0.29.0（复审 P0-2）：先算官方标称（物理可行性判据要用它），再建段。
    val official = remember(charges) { officialFullChargeRangeKm(charges) }
    val segments = remember(charges, drives, official) {
        buildRangeSegments(charges, drives, officialRangeKm = official)
    }
    if (segments.isEmpty()) return

    val fullValues = segments.map { it.fullChargeKm }
    val median = com.teslamate.app.medianOf(fullValues) ?: return
    val best = segments.maxByOrNull { it.fullChargeKm }
    val worst = segments.minByOrNull { it.fullChargeKm }

    SectionCard(title = "实测满电续航", modifier = modifier) {
        // ⚠️ **v0.35.0（第 21 轮视觉评审 P1-6）：从 34sp 降到 26sp。**
        //
        // 记录页的主角现在是**线路余量表里「最长那一跳」的公里数**（44sp，在首屏）；
        // 这张卡在它下面，34sp 会形成**层级倒置**（下面的数字比上面的还大）。
        BigStat(
            "${median.roundToInt()}",
            // ⚠️ **v0.40.0（第 24 轮对抗性审计 ①）：标题必须带「折算值」。**
            //
            // 这个数是「从 X% 开到 Y% 跑了多少公里」按掉电比例**折算**到满电的，
            // 用的是**净掉电** —— 所以**下坡回充被抵扣了**，系统性偏乐观。
            // 不标出来，读者会把它当成「跑满一箱能跑多远」的实测。
            "实测满电续航（公里，中位 · 折算值）",
            mid = true,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            buildString {
                append("基于 ${segments.size} 段实测（每次充完到下次充上）")
                official?.let {
                    // ⚠️ **v0.40.0（第 24 轮对抗性审计 ①）：百分比方向曾经反了。**
                    // 详见 [fullChargeVsOfficialPercent] 的说明 ——
                    // 旧算法 `round(median/official*100)` 恒 ≥ 0，
                    // 于是「实测高 77%」这个**假恭维**活了 12 个版本。
                    val diff = fullChargeVsOfficialPercent(median, it)
                    append(" · 官方标称 ${it.roundToInt()} 公里")
                    if (diff != null) {
                        append(if (diff >= 0) "，实测高 $diff%" else "，实测低 ${-diff}%")
                    }
                }
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // ⚠️ **v0.29.0（HIG 评审 P0-3 / P1-9 / P2-1）：折叠区改用公共组件。**
        //
        // 原来这里是**手写**的折叠（`▸/▾` 字符 + 自定义动画 + 2dp 垂直内边距）：
        // ① 触控目标只有 **19dp**（labelSmall 行高 15sp + 2dp×2）—— 低于 WCAG 2.5.8 的 24dp；
        // ② 没有 `stateDescription` → TalkBack 读不出展开态（WCAG 4.1.2）；
        // ③ 用了 `▸/▾` 字符 —— 而 v0.17.0 的动效评审**明确废弃**了字符方案
        //    （字符替换是瞬时跳变，与内容展开不同步，是「廉价感」最直接的来源）；
        // ④ 动画时长写死 160/220/120/180，**没有一个对得上** `Motion.kt` 的 token
        //    （ExpandHeight 280 / ExpandFadeIn 200 / ExpandFadeOut 140 / ChevronRotate 240），
        //    而且用户直接操作的展开按项目自己的原则应该用 **spring** 而不是 tween。
        //
        // `Widgets.kt` 里的 `CollapsibleSection` **已经把这些问题全修过了**
        // （44dp 触控高 + stateDescription + ExpandMore 图标旋转 + Motion token）。
        // 新卡不该手写复刻并全部退化。
        //
        // 同时按 P1-9：默认态只留「主角 + 一行副标题 + 折叠入口」，
        // 其余说明全部进折叠区（原来默认态有 4 段灰字 + 主角 = 5 段文字，违反「简洁」）。
        CollapsibleSection("看 ${segments.size} 段明细") {
            Column {
                // ⚠️ **v0.29.0（HIG P0-4）：官方标称不能写死。**
                // 原来这里硬编码「官方标称 448 公里」，而上面那行是**动态算**出来的 ——
                // 车端标称一变，两处自相矛盾。
                // ⚠️ **v0.40.0（第 24 轮对抗性审计 ①/⑨）：这段话原来**自相矛盾**。**
                //
                // 原来写「两者是『折算 vs 实测』的关系」—— 但「官方标称」**也是实测**
                // （车机满电时自己报的那个数），**折算值**才是那个折算出来的。
                // 而且原来**只字未提**「回充被抵扣」这件对结论有决定性影响的事。
                //
                // 现在说清三件事：① 这是折算值、怎么折的；
                // ② 它**含下坡回充**（偏乐观）；③ 规划要用哪个数。
                Text(
                    "这是折算值：把「从 X% 开到 Y% 跑了多少公里」按掉电比例折算到满电，" +
                        "所以含没充满就走的段（比如 50%→29%）。" +
                        "折算用的是净掉电 —— 下坡回充会被抵扣，所以这个数偏乐观。" +
                        (official?.let { "官方标称 ${it.roundToInt()} 公里是车机满电时报的数，" } ?: "") +
                        "两个都是实测，只是口径不同：规划请用上面的线路余量表（那里不含回充抵扣）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                best?.let {
                    Text(
                        "最好：${it.fullChargeKm.roundToInt()} 公里" +
                            "（${it.startDate.fmtDate()} 从 ${it.fromSoc}% 开到 ${it.toSoc}%，实跑 ${it.km.roundToInt()} 公里）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                worst?.let {
                    Text(
                        "最差：${it.fullChargeKm.roundToInt()} 公里" +
                            "（${it.startDate.fmtDate()}，跑了 ${it.km.roundToInt()} 公里）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // ⚠️ v0.28.0（复审 P2-13）：「最好」那个数必须打折看。
                // 实测最好那趟是**山地下坡**的产物（海拔 1389~4327 m），
                // 回充让等效能耗降到 110 Wh/km —— **平路跑不出这个数**。
                Text(
                    "「最好」多半含下坡回充（长下坡几乎不耗电），别拿它当平路规划依据；" +
                        "「最差」才是市区工况更接近的那个数。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                // ⚠️ **v0.29.0（HIG P1-6）：明细改成两行式。**
                // 原来三列固定宽（78/72/72dp），200% 字号下
                // 「100% → 8%」需要 119.6dp、「351 公里」需要 101.4dp —— 全部折行且错位。
                segments.forEach { s ->
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    ) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                s.startDate.fmtDate(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(78.dp),
                            )
                            Text(
                                "${s.fromSoc}% → ${s.toSoc}%",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            "${s.km.roundToInt()} 公里 · ≈ ${s.fullChargeKm.roundToInt()} 公里/满电",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "口径：一次充电结束到下一次充电开始，按掉电百分比折算到满电。" +
                        "这是折算值，不是某一次满电实跑；" +
                        "市区与高速差 1.7 倍，中位数不代表任何一次具体出行。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
