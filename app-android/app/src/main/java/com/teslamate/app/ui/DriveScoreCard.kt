package com.teslamate.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teslamate.app.DrivingProfile
import com.teslamate.app.fmtKwh100
import kotlin.math.roundToInt

/**
 * ⚠️ **v0.27.0（第 17 轮官方 App 对标评审 A-1）：驾驶画像卡片。**
 *
 * ## 这是官方 Tesla App 给不了的东西
 *
 * 官方 App 只拿到车端汇总上报的**平均** Wh/km 和标定续航；
 * 而 TeslaMate 记的是**逐秒的轮速与车轮功率** ——
 * 所以「你这一趟开得有多野」只有我们算得出来。
 * （第三方 TeslaFi / ABetterDrive 有这个功能，官方 App 没有。）
 *
 * ## 一屏一个主角数字
 *
 * 主角 = **驾驶平顺分**（0~100）。
 * 下面是 5 个**分项**，每个都给真实数字与口径，不给雷达图
 * —— 雷达图的面积没有可解释的量纲，是装饰不是信息。
 *
 * ## 必须说清的四件事
 *
 * 1. **统计范围**：只算 **≥20 公里**的行程 ——
 *    实测近 30 趟里有 16 趟不到 20 公里（占里程 15%），
 *    而低分那几趟全是 6~9 公里的**挪车**（商场/充电站内）；
 *    不排除的话主角分数会被「你在停车场挪车」主导。
 * 2. **分数怎么来的**：权重是**行业惯例值**，不是从这辆车拟合出来的。
 * 3. **地形混杂**：实测「超速占比」与「下坡高度」的相关系数 **+0.635** ——
 *    山路上限速本来就高，所以超速这一项**掺了地形**，
 *    而「急刹车」与下坡的相关系数只有 **−0.16**（干净）。
 * 4. **能耗口径**：这里是**车轮功率毛耗电**（不抵扣下坡回充；**不是电池实耗**），
 *    用量页那个是**标定续航差法**（按车机报的续航变化反推）——
 *    两处口径不同，数值会差一截，不是谁算错了。
 *
 *    ⚠️ **v0.103.0 订正**：原来这里写的是「车轮功率积分（**真实耗电**）」+
 *    「标定续航差法（**高约 19%**）」——
 *    ① **同一个文件下面就写着「不能叫『真实耗电』」**（实测真实耗电 ≈198 Wh/km，
 *    车轮功率 ≈153，**低 24%**）⇒ 上面那句与下面那句自相矛盾；
 *    ② 「高约 19%」是**跟净值比**的（标定 181.9 vs 净值 155.2），
 *    而这张卡显示的分明是**毛耗电**（≈215~227，比标定法**高**）⇒ 跟显示值对不上。
 *    ⇒ 现在只说「口径不同、数值会差一截」，**不断言谁高谁低**
 *    （方向随有没有下坡回充翻转，见 `ui/EnergyNote.kt` 的注释）。
 */
@Composable
fun DriveScoreCard(
    profile: DrivingProfile?,
    /** 正在算（服务端要从 162 万个采样点里算） */
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "驾驶画像", modifier = modifier) {
        if (profile == null) {
            Text(
                if (loading) "正在算…" else "还没算过驾驶画像",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        val s = profile.summary
        if (s.drive_count == 0) {
            Text(
                "最近没有 ≥${profile.params.min_distance_km} 公里的行程，够不着评分",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        // ── 主角 ──
        // ⚠️ **v0.29.0（HIG 评审 P1-3）：**不跟车况页的续航主角同级。**
        // 续航「现在能开 XXX 公里」是车况页**唯一**的 44sp 锚点；
        // 驾驶画像再给一个 44sp 就成了两个平级主角（一屏一个主角数字）。
        //
        // ⚠️ **v0.35.0（第 21 轮视觉评审 P1-5）：从 34sp 降到 26sp。**
        // 原来它和「常驻点」都是 34sp，与 44sp 主角的层级差只有 **1.294×** ——
        // 缩略后这两个数字块**分不出主次**。现在层级比是 44 : 26 = **1.69×**。
        BigStat(
            s.median_score.roundToInt().toString(),
            // ⚠️ **v0.44.0（第 24 轮对抗性审计 ⑩）：名字要诚实。**
            //
            // 「平顺分」这个名字**只覆盖了 70 分**：
            // 实测 drive#410（2026-10-06 夜间）**急刹 0 次、急加 0 次**（完美平顺），
            // 得分只有 **54.1** —— 全扣在超速 50.4%、夜驾 100%、能耗 166.6 Wh/km。
            // ⇒ **30 分来自能耗与夜驾，不是「平顺」。**
            //
            // 所以副标题改成「合成指标」，并说明「平顺」只占一部分 ——
            // 读者才知道这个 70 分里到底有什么。
            //
            // ⚠️ **v0.103.0 订正**：这里原来写的是「只覆盖了 **60** 分」「**40** 分来自能耗与夜驾」——
            // 那是 v0.44.0 **抄错**的权重：服务端 `drivingScore()` 从 v0.27.0 初版起就是
            // 急刹 30 + 急加 20 + 速度 20 + 能耗 15 + 夜驾 15 ⇒ **平滑 70 / 能耗夜驾 30**。
            // v0.102.0 修了**用户可见的 ⓘ**，却漏了这段**注释** ⇒
            // 闸门 14 已扩展为**扫全文**（不再只扫 ⓘ 函数体），这类漏网以后会被挡住。
            "驾驶平顺分（中位 · 合成指标）",
            mid = true,
        )
        Spacer(Modifier.height(2.dp))
        // ⚠️ **v0.65.0（第 32 轮「减啰嗦」）：副标题只留**数据**，口径说明折进 ⓘ。**
        //
        // 车主原话：「驾驶画像的平顺分的解释，应该隐藏起来，
        // 需要我点击一个小角标才会展示。类似于每个指标的注释那种感觉。」
        //
        // 原来这里平铺着两句口径：
        // ①「其中 40 分来自能耗与夜驾，不只是平顺」（v0.44.0 加的）
        // ②「门槛 20 公里：这 20 趟平均 91 公里/趟，更短的行程不在里面」（v0.50.0 加的）
        // 两条都是**口径**不是数据 ⇒ 折到副标题后面的 ⓘ 里。
        //
        // ⚠️ **v0.47.0（第 28 轮 a11y P1-3）** 的「 · 换成 ，」仍然成立（读屏别念「点」）。
        val avgKmPerTrip = driveProfileAvgKmPerTrip(s.total_km, s.drive_count)
        TextWithNote(
            // ⚠️ **v0.74.0（模拟器实测）：副标题不再重复主角那个数。**
            //
            // 原来副标题结尾是「，平均 60 分」，而卡上的主角**也是 60** ——
            // 但两者**不是同一个统计量**：主角是**中位数**（`median_score`），
            // 副标题那个是**均值**（`avg_score`）。本机它们碰巧相等（都取整成 60），
            // 于是读起来像同一个数说了两遍。
            //
            // ⇒ 副标题只留**样本口径**（基于多少趟、多少公里），
            // 均值挪进 ⓘ，并**明说它是均值**，免得和主角的中位数混。
            text = "按 ≥${profile.params.min_distance_km} 公里的 ${s.drive_count} 趟算" +
                "，共 ${s.total_km.roundToInt()} 公里",
            note = noteDriveScore(s.avg_score.roundToInt(), avgKmPerTrip),
            a11yLabel = "驾驶平顺分的算法",
        )
        Spacer(Modifier.height(10.dp))

        // ── 分项 ──
        ScoreRow(
            "急刹车",
            "${s.hard_brake_total} 次 · ${fmtPer100(s.hard_brake_total, s.total_km)} 次/百公里",
            // 实测 corr(急刹车/百公里, 下坡) = −0.16 → 这项**不掺地形**
            hint = "刹车片磨损的直接来源；与下坡几乎无关（相关系数 −0.16）",
        )
        ScoreRow(
            "急加速",
            "${s.hard_accel_total} 次 · ${fmtPer100(s.hard_accel_total, s.total_km)} 次/百公里",
            hint = "轮胎与悬架的消耗来源",
        )
        ScoreRow(
            "能耗",
            "${s.wh_per_km_median_gross.fmtKwh100()} kWh/100km（中位）",
            // ⚠️ **v0.29.0（复审 P0-2）：口径必须写清楚。**
            //
            // 评审原话：「『能耗 … 车轮功率积分（真实耗电）』——
            // 实测真实耗电是 198.2 Wh/km，车轮功率是 152.7，**低 24%**；
            // 且下坡得白送分。」
            //
            // 所以：① 它是**车轮功率**（不含传动损耗、不含回充抵扣），
            //      不能叫「真实耗电」；
            //      ② 显示用**毛耗电**（不含回充抵扣），
            //      否则下坡那两趟的白分会显示成「16.2 kWh/100km」这种不可能的数。
            hint = "车轮功率积分，不含传动损耗与回充抵扣；不是电池实耗",
        )
        ScoreRow(
            "夜间行驶",
            "${s.night_pct_avg.roundToInt()}% 的时间",
            hint = "本地 ${profile.params.night_start_hour}:00–${profile.params.night_end_hour}:00",
        )
        ScoreRow(
            "≥${profile.params.over_speed_kph} km/h",
            "${s.over_speed_pct_avg.roundToInt()}% 的时间",
            // ⚠️ 复审：原来叫「超速」，而 120 km/h 是**国内高速的合法限速** ——
            // 叫「超速」等于把合法速度说成违法。改成中性描述。
            hint = "这项掺了地形：山路限速本来就高（与下坡相关系数 +0.635）",
        )

        Spacer(Modifier.height(10.dp))
        // ── 最近几趟的分数 ──
        // ⚠️ 只画最近 12 趟 —— 再多就成了「装饰」，而每个格子都该有量纲。
        //
        // ⚠️ **v0.28.0（复审 P0-1）：必须用 `take(12)` 而不是 `takeLast(12)`。**
        // 服务端是 `ORDER BY d.start_date DESC` —— `drives[0]` 是**最新**那趟。
        // 用 `takeLast` 拿到的是 30 趟里**最旧**的 12 趟（10-17~10-11），
        // 而标题写着「最近 12 趟」—— 说的是**过去**，图上画的是**更早**。
        val recent = profile.drives.take(12).reversed()
        if (recent.size >= 3) {
            // ⚠️ **v0.29.0（HIG 评审 P0-2 / P1-1）：只标关键值，数值放柱下方的带里。**
            //
            // 原来 12 根柱**每根**都标数值（10sp）—— 200% 字号下：
            // 柱槽 = (329 − 11×3)/12 = **24.67dp**，而「100」在 fs=2.0（em 20dp）下宽 **33dp**，
            // **超出 8.33dp** → 相邻柱的数值互相压字。
            // 而且每个数字坐在各自柱顶 → 12 个数字**锯齿状错位**，无法横向扫读。
            //
            // 现在：
            // ① 数值放到**柱下方的带**里 → 共用一条基线，能横向扫读（P1-1）；
            // ② 只标**最高**与**最低**两个 —— 其余留白，数字可以溢出到邻位的空白里，
            //    不会压到别的数字（P0-2）；
            // ③ 最高与最低相邻时只标最高（避免两个数字挤在一起）。
            val maxScore = recent.maxOf { it.score }
            val minScore = recent.minOf { it.score }
            val maxIdx = recent.indexOfFirst { it.score == maxScore }
            val minIdx = recent.indexOfFirst { it.score == minScore }
            val labelIdxs = if (maxIdx == minIdx || kotlin.math.abs(maxIdx - minIdx) <= 1) {
                setOf(maxIdx)
            } else {
                setOf(maxIdx, minIdx)
            }
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().height(38.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    recent.forEach { d ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height((d.score / 100.0 * 34).dp.coerceAtLeast(3.dp))
                                .clip(RoundedCornerShape(3.dp))
                                .background(scoreColor(d.score))
                        )
                    }
                }
                // 数值带：在柱**下方**，共用一条基线
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    recent.forEachIndexed { i, d ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (i in labelIdxs) {
                                Text(
                                    d.score.roundToInt().toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    // ⚠️ **v0.30.0（第 19 轮 HIG 评审 P0-3）：不许折行。**
                                    // 槽宽 24.67dp，而「100」在 fs=1.5 起就宽 25.20dp →
                                    // 折行会把整卡向下撑开、行高突变。
                                    // 允许**溢出**到邻位空白（只标 2 个，其余槽是空的）。
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Visible,
                                )
                            }
                        }
                    }
                }
            }
            Text(
                "最近 ${recent.size} 趟的分数（右边最新）· 柱越高分越高",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))
        // ── 口径披露 ──
        //
        // ⚠️ **v0.30.0（第 19 轮数据口径审计 P0-1）：两条免赔线的文案原来都写错了。**
        //
        // ① 「能耗 150 kWh/100km 以下不扣」—— 代码阈值是 `WhPerKmGross − 150`，
        //    而 `WhPerKmGross` 的单位是 **Wh/km**（= 15 kWh/100km）——
        //    **差 10 倍**。用户以为门槛是 150 kWh/100km（比实测 198 还高 7.6 倍），
        //    实际是 15 kWh/100km（本车实测毛耗电最低 166 Wh/km），
        //    所以这条免赔线在默认 20 趟里**一次都不触发**。
        //    现在按代码的真实单位写：150 Wh/km（即 15 kWh/100km）。
        //
        // ② 「≥120 km/h 的前 3% 不扣」—— 代码是 `over_pct − 3`，
        //    即「**超过 3 个百分点**的部分每 1 个百分点扣 1 分」，
        //    不是「最快的 3% 样本豁免」。实测 20 趟的超速占比中位 **19.1%**，
        //    「前 3% 免赔」在实践中几乎从不生效。
        // ⚠️ **v0.65.0（第 32 轮「减啰嗦」）**：整段评分公式（106 字）折进 ⓘ。
        //
        // 车主要的是「小角标」⇒ **本轮新加的折叠一律用 ⓘ**（InfoNote 带短标签），
        // 不再混用「怎么算的 ⌄」箭头折叠（那是电池卡里既有的老模式，不动它）。
        InfoNote(
            text =             "分数 = 100 − 急刹车(≤30) − 急加速(≤20) − ≥${profile.params.over_speed_kph}km/h(≤20)" +
                " − 能耗(≤15) − 夜驾(≤15)。" +
                "两条免赔线：≥${profile.params.over_speed_kph} km/h 的占比超过 3 个百分点的部分" +
                "每 1 个百分点扣 1 分；能耗 150 Wh/km（即 15 kWh/100km）以下不扣。" +
                "硬加/硬刹 = 2 秒内车速变化 ≥${profile.params.hard_event_threshold_kmh_per_2s} km/h" +
                "（噪声底 ±12，有 8 倍余量）；" +
                "夜间 ${profile.params.night_start_hour}:00–${profile.params.night_end_hour}:00、" +
                "≥${profile.params.over_speed_kph} km/h —— " +
                "都是行业惯例值，不是从这辆车拟合出来的。" +
                "⚠️ 120 km/h 是国内高速的合法限速，这一项扣的是「开得比限速快」，不是「违法」。",
            label = "怎么算的",
            a11yLabel = "驾驶平顺分的算法",
        )
    }
}

/** 每公里 / 每百公里的次数。 */
/**
 * ⚠️ **v0.50.0（第 28 轮通勤车主评审 P1-4）**：驾驶画像门槛行程的**平均长度**（公里/趟）。
 *
 * 真实数据：够得着 20 公里门槛的 20 趟，**平均 91.0 公里/趟**；
 * 而全车 364 趟的**单趟中位只有 5.6 公里**、**72.8% 的趟不到 20 公里**。
 *
 * ⇒ 这个门槛把用户**七成**的行程筛在外面 ——
 * 界面必须把这件事说出来，否则「平顺分 60」看起来像是对他全部日常的评价。
 *
 * 抽成**纯函数**是为了能被 JVM 单测直接断言。
 */
fun driveProfileAvgKmPerTrip(totalKm: Double, driveCount: Int): Int? =
    if (driveCount <= 0) null else (totalKm / driveCount).roundToInt()

/**
 * **驾驶平顺分的口径** —— 副标题后面那个 ⓘ 的正文。
 *
 * ⚠️ **v0.102.0（第 10 轮 CDO Polish）：这条 ⓘ 原来把权重写错了，而且超长。**
 *
 * ## ① 权重是错的：60/40 ⇒ **70/30**
 *
 * 原文写「这 100 分里只有 **60** 分是急刹/急加/速度，另外 **40** 分来自能耗与夜驾」。
 * 而服务端 `api/handlers.go` 的 `drivingScore()` 里五项**封顶**是
 * 急刹 30 + 急加 20 + 速度 20 + 能耗 15 + 夜驾 15 ⇒
 * 平滑相关 = **70**，能耗与夜驾 = **30**。
 *
 * ⇒ 这个 60 **从 v0.44.0 写下那天起就是错的**：
 * 服务端的评分函数从 v0.27.0 初版起就是 30/20/20/15/15
 * （v0.29.0 只把能耗的输入从净值换成毛耗电，没动封顶）。
 * ⇒ 同一张卡的「怎么算的」ⓘ 写的是
 * `急刹车(≤30) − 急加速(≤20) − ≥120km/h(≤20) − 能耗(≤15) − 夜驾(≤15)`
 * —— **两条 ⓘ 互相矛盾**：一条说 60/40，一条的公式加起来是 70/30。
 * ⇒ 同一句错话在 `mcp/teslamate_mcp.py` 里还有**第二份**（Hermes 侧），
 * 本轮一并改掉，并加闸门钉住（见 `scripts/client-gates.sh` 闸门 14）。
 *
 * ## ② 同一屏里重复的数
 *
 * - 「这 20 趟」：副标题已经写着「按 ≥20 公里的 **20 趟**算」⇒ ⓘ 里再说两遍是噪音；
 * - 「门槛 20 公里」：副标题已经写着「≥20 公里」⇒ 不重复；
 * - 「更短的行程不在里面」**保留** —— 它是 v0.50.0 特意加的，
 *   为的是让用户知道**自己那些短途被门槛筛掉了**
 *   （本车 72.8% 的趟不到 20 公里，单趟中位只有 5.6 公里）。
 *
 * 抽成**纯函数**是为了能被 JVM 单测直接断言
 * （与 [driveProfileAvgKmPerTrip] 同一个理由）。
 */
fun noteDriveScore(avgScore: Int, avgKmPerTrip: Int?): String {
    val sb = StringBuilder()
    sb.append("这 100 分里 70 分是急刹/急加/速度，30 分来自能耗与夜驾 —— ")
    sb.append("名字叫「平顺」，实际不是。")
    sb.append("卡上是中位数，平均 ").append(avgScore).append(" 分。")
    if (avgKmPerTrip != null) {
        sb.append("每趟平均 ").append(avgKmPerTrip).append(" 公里，更短的行程不在里面。")
    }
    return sb.toString()
}

private fun fmtPer100(count: Int, km: Double): String {
    if (km <= 0.1) return "—"
    return (count / km * 100).let { String.format(java.util.Locale.CHINA, "%.1f", it) }
}

/**
 * 分数 → 颜色。
 *
 * ⚠️ 复审：原来 `<60` 用 **danger 红** —— 太重。
 * 低分不是「告警」（车主没做错什么，只是开得野），
 * 用中性灰 + 旁边的数字表达就够了。
 *
 * ⚠️ **v0.29.0（HIG 评审 P1-7）：灰只保留「轨道」语义。**
 * 原来这里是三档（蓝 / 琥珀 / **灰**），而同一屏里：
 * 能耗曲线用灰表示「普通档」、常驻点用灰表示「轨道」——
 * 同一个灰在三个地方三种含义，直接冲突。
 * 现在改成**两档**（蓝 = 好，琥珀 = 需要留意），灰只用于轨道。
 */
@Composable
private fun scoreColor(score: Double): Color {
    val a = LocalAccentColors.current
    return if (score >= 85) a.energyBlue else a.warnAmber
}

/** 一个分项行：标题 + 数值 + （点 ⓘ 才展开的）一句解释。 */
@Composable
private fun ScoreRow(
    label: String,
    value: String,
    hint: String,
) {
    // ⚠️ **v0.65.0（第 32 轮「减啰嗦」）**：解释从「平铺」改成 **ⓘ 角标 + 就地展开**。
    //
    // 车主点名的那一处：「驾驶画像的平顺分的解释，应该隐藏起来，
    // 需要我点击一个小角标才会展示。类似于每个指标的注释那种感觉。」
    //
    // 原来这 5 个分项下面各挂一句解释，卡片高度被撑掉近一半；
    // 现在收成 5 个 ⓘ，展开才占地方。
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                // ⚠️ **v0.29.0（HIG 评审 P1-5）：不要固定宽。**
                // 原来 `width(96.dp)`：「≥120 km/h」在 fs=1.0 宽 72.3dp ✓，
                // fs=2.0 宽 **144.6dp** → 超出 48.6dp，**必折行**且与数值错位。
                // 改成 weight + 最小宽：空间够就并排，不够就换行，不会挤到数值。
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = 96.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // ⚠️ 徽标放**行尾**（值之后）—— 与 iOS 设置项的「ⓘ 披露按钮」同位。
            Spacer(Modifier.width(4.dp))
            InfoBadge(
                expanded = expanded,
                onToggle = { expanded = !expanded },
                a11yLabel = "$label 的说明",
            )
        }
        NoteBody(text = hint, visible = expanded)
    }
}
