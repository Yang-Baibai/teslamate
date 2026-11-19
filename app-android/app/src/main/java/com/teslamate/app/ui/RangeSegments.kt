package com.teslamate.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.teslamate.app.FullChargeRange
import com.teslamate.app.fmtDate
import kotlin.math.roundToInt

/**
 * ⚠️ **v0.174.0：实测满电续航卡 —— 数据全部来自服务端，客户端只渲染。**
 *
 * ## 为什么不再本地算
 *
 * 原来这张卡的折算在**客户端**（buildRangeSegments / officialFullChargeRangeKm /
 * fullChargeVsOfficialPercent 三个函数都在这个文件里）。两个后果：
 *
 * ① **飞书里问不出来** —— mission §2.1「App 能看到的，Hermes 也要能取」；
 * ② **两端各算一套阈值** —— 掉电 20 个百分点、1.5 倍物理可行性这两个阈值
 *    只写在客户端 ⇒ 以后任一边改了，用户在 App 与飞书里会看到**互相矛盾的两个数**。
 *
 * ⇒ 现在判定、阈值、文案都在**服务端**（api/full_charge_range.go +
 *   `GET /api/v1/cars/{id}/full_charge_range`），App **只透传**（ADR #38 / ADR #41）。
 *
 * ## ⚠️ 服务端没给数 ⇒ **不显示这张卡**
 *
 * 拿不到时（服务器挂掉 / 老服务端 / 官方标称缺失）⇒ 整张卡**不出现**。
 * ⚠️ **绝不在客户端自己算一套顶替** —— 那会变成第二个口径（v0.108.0 充电统计
 *    已经踩过这个坑并写进注释：绝不在客户端自己算一套顶替）。
 */

/**
 * 「实测满电续航」卡片。
 *
 * ⚠️ 默认**折叠** —— 它是「参考知识」而不是「现在能开多少」，
 * 抢首屏会挤压记录页的历史列表（v0.14.0 同样的教训）。
 *
 * @param data 服务端给的结论；null = 拿不到 ⇒ **不显示这张卡**。
 */
@Composable
fun RangeSegmentsCard(
    data: FullChargeRange?,
    modifier: Modifier = Modifier,
) {
    // ⚠️ **v0.174.0**：服务端没给数 ⇒ 整张卡不出现（不编、不显示半截）。
    val median = data?.median_km ?: return
    val official = data.official_km
    val segments = data.segments
    if (segments.isEmpty()) return

    val best = segments.maxByOrNull { it.full_charge_km }
    val worst = segments.minByOrNull { it.full_charge_km }

    SectionCard(title = "实测满电续航", modifier = modifier) {
        // ⚠️ **v0.35.0（第 21 轮视觉评审 P1-6）：从 34sp 降到 26sp。**
        //
        // 记录页的主角现在是**线路余量表里「最长那一跳」的公里数**（44sp，在首屏）；
        // 这张卡在它下面，34sp 会形成**层级倒置**（下面的数字比上面的还大）。
        BigStat(
            "${median.roundToInt()}",
            // ⚠️ **v0.40.0（第 24 轮对抗性审计 ①）：副标题必须带「折算值」。**
            //
            // 这个数是「从 X% 开到 Y% 跑了多少公里」按掉电比例**折算**到满电的，
            // 用的是**净掉电** —— 所以**下坡回充被抵扣了**，系统性偏乐观。
            // 不标出来，读者会把它当成「跑满一箱能跑多远」的实测。
            //
            // ⚠️ **v0.41.0（第 25 轮 HIG 复审 P1-1）：删掉重复的「实测满电续航」。**
            // 卡标题（`SectionCard(title = "实测满电续航")`）已经说了，
            // 副标题再说一遍 —— 同一个词在同一张卡里出现两次。
            "公里 · 中位（折算值）",
            mid = true,
        )
        Spacer(Modifier.height(2.dp))
        // ⚠️ **v0.41.0（第 25 轮 HIG 复审 P1-2）：坏消息要用琥珀色，不用中性灰。**
        //
        // 「实测低 23%」是**低于官方标称** —— 按本仓库的色彩语义，
        // 低于阈值的结论应该是琥珀（`warnAmberText`，对比度 6.39:1 / 8.39:1 ✅）。
        // ⚠️ **不用特斯拉红**：`Theme.kt` 写明红 = 强调/危险/品牌点缀，
        // **不用于表达健康电量** —— 这里是标称口径差，不是电量告警。
        val accents = LocalAccentColors.current
        Text(
            buildAnnotatedString {
                // ⚠️ **v0.174.0**：这一句是**服务端下发的原文**（note）——
                // 「基于 N 段实测 · 官方标称 X 公里，实测低 Y%」**整句**由服务端拼好。
                //
                // ⚠️ 客户端**一个字都不许自己拼**（ADR #41：文案只有一份）——
                // 啰嗦棘轮（客户端闸门 5）当场抓到过客户端自己拼的那半句。
                //
                // ⚠️ **v0.40.0（第 24 轮对抗性审计 ①）**：百分比方向曾经反过。
                // 详见服务端 fullChargeVsOfficialPercent 的说明 ——
                // 旧算法 `round(median/official*100)` 恒 ≥ 0，
                // 于是「实测高 77%」这个**假恭维**活了 12 个版本。
                append(data.note)
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
                //
                // ⚠️ **v0.174.0**：官方标称的值也来自服务端（official_km）。
                //
                // ⚠️ **v0.40.0（第 24 轮对抗性审计 ①/⑨）：这段话原来**自相矛盾**。**
                //
                // 原来写「两者是『折算 vs 实测』的关系」—— 但「官方标称」**也是实测**
                // （车机满电时自己报的那个数），**折算值**才是那个折算出来的。
                // 而且原来**只字未提**「回充被抵扣」这件对结论有决定性影响的事。
                //
                // 现在说清三件事：① 这是折算值、怎么折的；
                // ② 它**含下坡回充**（偏乐观）；③ 规划要用哪个数。
                //
                // ⚠️ **v0.41.0（第 25 轮车主复审 P8/P9）：压缩 + 改掉含混的说法。**
                //
                // 车主复审实测：原来 85 em → v0.40.0 的 135 em（**+59%**），
                // fs=1.0 下 4 → **6 行**、fs=2.0 下 7 → **12 行** ——
                // 藏在「折叠」区里却比正文还长。
                // 现在压到 ~100 em，且把「两个都是实测」改成
                // 「**一个折算、一个直读**」（P9：卡名就叫「实测满电续航」，
                // 再说「两个都是实测」读者会以为是同一类东西）。
                // ⚠️ **v0.65.0（第 32 轮「减啰嗦」）**：这一段是**折算口径**
                // （怎么折到满电、为什么偏乐观、规划该用哪个数）——
                // 属于**补充说明**而不是数据 ⇒ 折进 ⓘ，默认态只留主角与明细。
                // ⚠️ **v0.175.0**：这一段整句来自**服务端**（method_note）。
                //
                // ⚠️ 以前是**客户端自己拼**的 —— 其中「车机满电时自己报的是 X 公里」
                // 那个数服务端**已经给了**（note 里就有）⇒ 违反 ADR #41（文案只有一份）。
                // ⇒ 现在客户端一个字都不加，只透传。
                InfoNote(
                    text = data.method_note,
                    label = "这个数怎么算的",
                    a11yLabel = "实测满电续航的折算口径",
                )
                // ⚠️ **v0.176.0**：这两行改成**两行式**（与下面的段明细同款）。
                //
                // ## 为什么改
                //
                // 原来是一句**自由句**：「最好：607 公里（2026-09-12 从 96% 开到 6%，实跑 546 公里）」
                // ⇒ 模拟器实拍（1440×3200@600）：**末尾「里」单独折成一行**
                // ⇒ 一句话断成两行，而第二行只剩一个字（典型的「孤字成行」）。
                // ⚠️ 下面那段**段明细**在 v0.86.0 就改成两行式了（日期 ｜ 电量 / 实跑 ｜ 折算）
                // ⇒ 这两行还留在自由句形态 ⇒ **同一张卡里两套行样式**。
                //
                // ## 为什么这么排
                //
                // ① 第一行 = **结论**（最好/最差 + 折算满电公里）—— 最重要，直接可见；
                // ② 第二行 = **上下文**（哪一天、从多少开到多少）—— 辅助，淡一点；
                // ③ 「实跑多少公里」**不重复**（下面段明细里每一段都有）—— 不做重复信息。
                //
                // ⚠️ 宽度预算：第二行最长「2026-09-12 从 96% 开到 6%」≈ 19 字符
                // ⇒ 200% 字号下 78dp + 228sp ≈ 306dp ≤ 328dp（内容区）⇒ 不折行。
                best?.let {
                    // ⚠️ **v0.176.0**：顶部留 8dp ——
                    // 原来「最好」那行**紧贴**上面 ⓘ 的标签行（ⓘ 未展开时只有徽标＋标签一行）
                    // ⇒ 读起来像「这个数怎么算的」是「607 公里」的**标签** ⇒ 歧义。
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)) {
                        Text(
                            "最好：约 ${it.full_charge_km.roundToInt()} 公里/满电",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${it.start_date?.fmtDate().orEmpty()} 从 ${it.from_soc}% 开到 ${it.to_soc}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                worst?.let {
                    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            "最差：约 ${it.full_charge_km.roundToInt()} 公里/满电",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${it.start_date?.fmtDate().orEmpty()} 从 ${it.from_soc}% 开到 ${it.to_soc}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // ⚠️ v0.28.0（复审 P2-13）：「最好」那个数必须打折看。
                // 实测最好那趟是**山地下坡**的产物（海拔 1389~4327 m），
                // 回充让等效能耗降到 110 Wh/km —— **平路跑不出这个数**。
                // ⚠️ **v0.65.0（第 32 轮「减啰嗦」）**：这段是**读法说明**
                // （为什么「最好」不能当平路依据、哪个更接近市区工况）——
                // 补充说明 ⇒ 折进 ⓘ；上面的「最好 / 最差」两个数仍是直接可见的。
                // ⚠️ **v0.175.0**：整句来自**服务端**（read_note）。
                InfoNote(
                    text = data.read_note,
                    label = "最好/最差怎么读",
                    a11yLabel = "最好最差的读法说明",
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
                                s.start_date?.fmtDate().orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                // ⚠️ **v0.47.0（第 28 轮 a11y P1-4）：**不要固定宽** ——
                                // `fmtDate()` 是 "yyyy-MM-dd"（10 字符 ≈ 5.11em），
                                // labelSmall 12sp 在 fs=2.0 时 24sp → 122.7dp > 78dp
                                // ⇒ 日期在连字符处断成「2026-09-」/「12」两行，
                                // 第二行只剩一个数字，读起来像另一个字段。
                                // 临界 fontScale = 78 / (5.114 × 12) = **1.27**。
                                // ⚠️ v0.29.0 那一轮把**后面两列**改成了两行式，**漏了日期这一列**。
                                modifier = Modifier.widthIn(min = 78.dp),
                            )
                            Text(
                                "${s.from_soc}% → ${s.to_soc}%",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        // ⚠️ **v0.86.0（真机复核 · 用户原话「展开的明细布局不太好看」）**：
                        // 原来第二行是**一句话**「138 公里，约 330 公里/满电」——
                        // 44 段读下来数字**不成列**，没法横向比较，只能一行行扫。
                        // ⇒ 改成**两列**：实跑公里 | 折算满电公里（列间留固定间距）。
                        //   ⚠️ 「约」保留（这是**折算值**，不是实测满电里程）。
                        //   ⚠️ TTS：「·」会被念出来，「≈」也会被跳过 ⇒ 不用它们。
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                "${s.km.roundToInt()} 公里",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.widthIn(min = 78.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "约 ${s.full_charge_km.roundToInt()} 公里/满电",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                // ⚠️ **v0.65.0（第 32 轮「减啰嗦」）**：这一段是**口径**
                // （一段续航怎么定义、为什么是折算值、中位数代表什么）——
                // 补充说明 ⇒ 折进 ⓘ。
                // ⚠️ **v0.175.0**：整句来自**服务端**（scope_note）。
                //
                // ⚠️ **这里原来硬编码着「市区与高速差 1.7 倍」** ——
                // 而那是个**具体的分布断言**（随数据变化）⇒ 实测已经是 1.7562
                // ⇒ 那句话**早就在说错**，而没有任何判据会红。
                // ⇒ 现在倍数由服务端按**真实段数据**算。
                InfoNote(
                    text = data.scope_note,
                    label = "口径",
                    a11yLabel = "续航口径说明",
                )
            }
        }
    }
}