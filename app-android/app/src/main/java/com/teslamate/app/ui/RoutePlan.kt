package com.teslamate.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teslamate.app.Charge
import com.teslamate.app.Drive
import com.teslamate.app.firstSegment
import com.teslamate.app.fmtDate
import com.teslamate.app.fmtKwh100
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * **路线余量表** —— 出发前 5 分钟最需要的那张表。
 *
 * ## 为什么必须有它
 *
 * 长途自驾党的原话：
 * > 「出发前 5 分钟，打开 App 就能看到一条这样的表：
 * >   起点 100% … 120 km 62% ← 石化大道西（你在这充过 3 次）典型 55 kW …
 * >   430 km 15% ⚠ 到此只剩 15%，低于你设的 20% 保底 … 结论：全程需充 1 次」
 *
 * 现有 App 里唯一跟「长途」有关的东西是**过去的**行程 ——
 * 有功率曲线、有电量曲线，但那都是**已经跑完的那一趟**。
 * 出发前要问的是「**下一次**跑这条线会怎样」，而 App 答不了。
 *
 * ## 数据从哪来（全部是**真实历史**，不编）
 *
 * - **线路模板**：从历史行程里找「走过 ≥200 km 的长途」，按起止围栏名分组。
 * - **余量剖面**：用那条线路**历史上真实的充电记录**拼 ——
 *   每段行程的起点/终点电量、电量掉到多少、在哪个站充的、充到多少。
 *   于是剖面里那些跳升就是**真实的充电**，不是模型算出来的。
 * - **沿途站点**：按里程表对齐到路线公里数，配上该站的历史典型功率与耗时。
 *
 * ## 为什么不做成「输入目的地 → 规划」
 *
 * 那需要路径规划 API（高德）+ 沿途 POI，而且**没有历史数据的线路就规划不出来**。
 * 而实测：真正决策过的站，90% 是**自己充过的**。
 * 所以先用**已有历史**做这张表 —— 数据全在、可复算、零新依赖。
 *
 * @see buildRouteTemplates 找出历史长途模板
 * @see buildRoutePlan 用模板 + 历史充电算出余量表
 */

/** 一条历史长途线路的模板。 */
data class RouteTemplate(
    /** 分组键（起点键 + 终点键） */
    val key: String,
    val fromLabel: String,
    val toLabel: String,
    /** 该线路的典型里程（km，中位数）—— 只用于文案 */
    val distanceKm: Double,
    /** 出发时的电量（%） */
    val startSoc: Int?,
    /** 到达时的电量（%） */
    val endSoc: Int?,
    /**
     * ⚠️ **v0.26.0：这里不再是「走过几次」，而是「这一趟被充电切成了几段」。**
     *
     * 原来这个字段叫 `occurrences`（同一条线路跑过几次），
     * 改成 journey 之后它装的是**段数** ——
     * 如果文案还写「走过 N 次」，就会把「一次旅程的 11 段」说成「走过 11 次」。
     * 所以字段改名，文案也一起改（见 [conclusion]）。
     */
    val legCount: Int,
    /**
     * ⚠️ **v0.30.0（长途自驾党 P1-2）：全部段数**（含充电站内挪车微行程）。
     *
     * [legCount] 是 `meaningfulLegCount`（**≥2 公里**的段）——
     * 62 公里那条 journey 实测 131 段，其中 60 段不到 2 公里。
     *
     * 原来 `RouteJourney.summary()` 显示 **全部**段数（191），
     * 而 `conclusion()` 显示 **≥2 公里**的段数（131）——
     * 同一个 journey 在两个地方显示不同段数，用户会以为有一个是错的。
     * 现在两个都给出，并说清差别。
     */
    val totalLegCount: Int = legCount,
    /** 沿途充电站数（不含到达后的那次） */
    val stopCount: Int,
    /** 最近一次走的时刻 */
    val lastDrivenAt: Instant?,
    /** 最近一次的起点里程表读数（km）—— 用来把充电站按里程表对齐到路线公里数 */
    val startOdoKm: Double?,
    // ⚠️ v0.14.0（口径审计 P0-4）：下面三个是**最近一次**那趟的真实值。
    //
    // 原来剖面是「中位数起点 + 某一次的充电跳升 + 中位数终点」的三明治 ——
    // 三者不同源，却以「按这条线路上真实发生过的充电推算」呈现。
    // 现在剖面**只用最近那一趟**的真实值，端点与充电点同源。
    /** 最近一次的真实里程（km） */
    val lastDistanceKm: Double? = null,
    /**
     * ⚠️ **v0.30.0（长途自驾党 P0-1）：这段线路的**真实均速**（km/h）。**
     *
     * 原来规划链路的计划车速**硬编码成 90 km/h** ——
     * 「到下一站剩 34%」是按一个跟这趟毫无关系的速度算的。
     * 实测：6227 公里那条 **64 km/h**，616 公里那条 **42 km/h**。
     */
    val avgSpeedKph: Double? = null,
    /** 最近一次的出发电量（%） */
    val lastStartSoc: Int? = null,
    /** 最近一次的到达电量（%） */
    val lastEndSoc: Int? = null,
    /**
     * ⚠️ v0.24.0：**最近那一趟的 drive id** ——
     * 用来拉它的**真实逐公里 SOC 剖面**（`/drives/{id}/soc_profile`）。
     */
    val lastDriveId: Int? = null,
    /**
     * ⚠️ **v0.26.0：整段 journey 的全部 drive id。**
     *
     * 用来向服务端要**整条路线**的真实逐公里剖面
     * （`GET /cars/{id}/route_profile?drive_ids=…`），
     * 而不是只拿最后一段 —— 后者会让剖面只覆盖旅程的最后 1/12。
     */
    val legIds: List<Int> = emptyList(),
    /** 这一段 journey 的每一趟（按里程顺序） */
    val legs: List<JourneyLeg> = emptyList(),
    /**
     * ⚠️ **v0.26.0：沿途充电站 —— 已经按行程边界归属好了。**
     *
     * 原来是在 [buildRoutePlan] 里用**里程窗口**去捞充电记录，
     * 结果捞到的是**上一趟的收尾充电**（落在本趟的起点里程上）。
     * 现在归属在 [buildJourneys] 里做：充电落在**两段之间的缺口**里才算沿途站。
     */
    val stops: List<JourneyStop> = emptyList(),
)

/** 线路上的一个充电站（按路线公里数对齐）。 */
data class RouteStop(
    val name: String,
    /** 在这条线路上的第几公里 */
    val kmAlongRoute: Double,
    /** 到站时的电量（%） */
    val socBefore: Int?,
    /** 充完后的电量（%） */
    val socAfter: Int?,
    /** 该站历史典型功率（kW） */
    val typicalPowerKw: Double?,
    /** 该站历史「充到 80%」典型耗时（分钟） */
    val minutesTo80: Int?,
    /**
     * ⚠️ v0.14.0（长途自驾党 P0-2）：从**本次到站电量**充到 [socAfter] 的典型耗时（分钟）。
     *
     * 出发前真正要回答的是「我到站 16%，充到 60% 要等多久」，
     * 而不是写死的「充到 80% 要多久」—— 后者跟这次决策毫无关系。
     */
    val minutesToTarget: Int? = null,
    /** 该站历史充电次数 */
    val sessions: Int,
    /**
     * ⚠️ **v0.25.0（长途充电规划专家 P1-2）：到下一个充电站还有多少公里。**
     *
     * 原来只给「在第几公里」，不给「到下一站多远」——
     * 而后者才是出发前真正要回答的那句：「我充到 80%，**够不够开到下一个**？」
     *
     * 数据来自相邻充电点的里程表差（`start_odometer_km`）——
     * 实测本车真实节奏：**中位 128 公里，最远一段 348 公里**。
     */
    val gapToNextKm: Double? = null,
    /**
     * ⚠️ **v0.25.0：充到 [socAfter] 之后，到下一站还能剩多少电（%）。**
     *
     * 算法：`到下一站需要的度数 = 站间距离 × 本车实测 Wh/km`，
     * 再换算成电量的百分点。
     * 与 20% 保底对齐 —— 低于 20% 就该提示「这一段不够，得再充一次」。
     */
    val socAtNextStop: Int? = null,
    /**
     * ⚠️ **v0.30.0（长途自驾党 P0-1）：算 [socAtNextStop] 用到的车速（km/h）。**
     *
     * 原来**硬编码 90 km/h** —— 与这趟毫无关系。
     * 现在用**这段线路历史的真实均速**
     * （6227 公里那条 **64 km/h**、616 公里那条 **42 km/h**）。
     *
     * ⚠️ 这个数**只决定用哪一档 Wh/km**：
     * 90–110 档 183.0 Wh/km vs 110–130 档 197.4 Wh/km —— 差 **7.9%**，
     * 在 128 公里的站距上就是 **2.8 个百分点**的到达电量差。
     */
    val assumedSpeedKph: Double? = null,
    /** 实际用的 Wh/km（界面要把它和车速一起标出来 —— 不交代前提就只是个数字） */
    val assumedWhPerKm: Double? = null,
)

/** 余量剖面上的一个点。 */
data class RouteSocPoint(
    /** 路线上的公里数 */
    val km: Double,
    /** 电量（%） */
    val soc: Int,
    /** true = 这是充电造成的跳升 */
    val isCharge: Boolean = false,
)

/** 整条线路的余量表。 */
data class RoutePlan(
    val template: RouteTemplate,
    /** 余量剖面（按公里数升序） */
    val profile: List<RouteSocPoint>,
    /** 沿途充电站（按公里数升序） */
    val stops: List<RouteStop>,
    /**
     * ⚠️ v0.14.0（口径审计 P0-4）：**终点处**的充电站。
     *
     * 它不是「沿途」的 —— 它在终点之后。混进沿途列表会让人以为
     * 「到了城东路得先充一次才能到」，而实际上那是到达之后的动作。
     */
    val endpointStops: List<RouteStop> = emptyList(),
) {
    /** 最低点（不含充电跳升后的值） */
    val lowestSoc: Int? get() = profile.minByOrNull { it.soc }?.soc

    /**
     * ⚠️ **v0.29.0（复审 P0-3）：最低点要带上它的公里数。**
     *
     * 原来 `lowestSoc`（全局最低）与 `belowFloorKm`（第一个跌破 20% 的点）
     * 是**两个不同的点**，而结论句把它们**拼在一起**：
     * 「第 227 公里只剩 5%」—— 但第 227 公里真实是 **18%**，
     * 5% 是整条路线的最低点（在第 1237 公里）。
     * **两个数来自不同位置，却被写成了同一句话。**
     */
    val lowestPoint: Pair<Double, Int>? get() =
        profile.minByOrNull { it.soc }?.let { it.km to it.soc }

    /**
     * 第一个跌破 20% 保底的点（公里 + **该点的**电量）。
     *
     * ⚠️ v0.29.0：公里与电量必须来自**同一个点**（见 [lowestPoint] 的说明）。
     */
    val firstBelowFloor: Pair<Double, Int>? get() =
        profile.firstOrNull { it.soc < 20 }?.let { it.km to it.soc }

    /** 低于 20% 保底的公里数 */
    val belowFloorKm: Double? get() = firstBelowFloor?.first

    /** 一句话结论 */
    fun conclusion(): String {
        val s = template
        val parts = mutableListOf<String>()
        parts += "${s.fromLabel} → ${s.toLabel}"
        parts += "${s.distanceKm.roundToInt()} 公里"
        // ⚠️ v0.26.0：改成「N 段 · M 次沿途充电」——
        // 原来写「走过 N 次」，而 N 现在是**段数**，
        // 会把「一次旅程被充电切成 11 段」说成「走过 11 次」。
        //
        // ⚠️ **v0.30.0（长途自驾党 P1-2）：两处段数不一致，必须说清。**
        // 这里的 `legCount` 是 `meaningfulLegCount`（**≥2 公里**的段，实测 131），
        // 而 `RouteJourney.summary()` 用的是 `legs.size`（**全部**段，实测 191）——
        // 差 60 段是充电站内挪车微行程（<2 km）。
        // 同一个 journey 在两个地方显示不同段数，用户会以为有一个是错的。
        // 现在两个数都写出来，并说清差别在哪。
        parts += if (s.totalLegCount > s.legCount) {
            "${s.totalLegCount} 段行程（其中 ${s.legCount} 段超过 2 公里）"
        } else {
            "${s.legCount} 段"
        }
        if (s.stopCount > 0) parts += "$s.stopCount 次沿途充电"
        return parts.joinToString(" · ")
    }

    /**
     * ⚠️ v0.14.0（长途自驾党 P0-1）：**决策句** —— 出发前唯一要问的那句。
     *
     * 原来这张卡只给线路身份（「A → B · 252 公里 · 走过 1 次」），
     * 从来没回答过「这次能不能一次开到？要在哪充？充多久？」
     * —— 数据都算出来了，只差翻译成一句话。
     *
     * ⚠️ v0.20.0（车主 P0-2）：**「不用中途充电」这句话是错的**。
     *
     * 原来的 `stops.isEmpty()` 分支写「不用中途充电」——
     * 但 `stops` 的含义是「**这条线路上你充电过的站**」，
     * 不是「这里需要充电」。你在这条线上一次都没充过电，
     * 结论就变成「不用中途充电」—— **把「没有数据」说成了「不需要」**。
     *
     * 车主原话：「出发前一晚我规划这条 252 公里的长途，结论句第一句是
     * 「不用中途充电」，于是我不安排任何补电；后半句我当成了脚注。」
     *
     * 现在：
     * ① 先说**电量结论**（到站剩多少 / 哪一公里跌破保底）—— 这才是决策依据；
     * ② 没有补电点时明说「这条线路上没有你的充电记录，中途补电给不出位置」，
     *    而不是假装「不需要充」。
     */
    /**
     * ⚠️ **v0.24.0（长途充电规划专家 P0-3）：决策句必须带**来源限定**。**
     *
     * 原来输出的是「需充 2 次」「第 252 公里只剩 16%」——
     * 读起来像是「**这次**要充 2 次」，但它 100% 来自**单趟历史 drive** 的实测值。
     *
     * 专家实测复刻：那条 252 公里的线路，App 会显示「需充 2 次」——
     * 而那 2 次其实是**上一趟的起点充电**（充电必然打断行程，
     * 实测 78/78 场充电都落在某趟 drive 的起点或终点附近，最近距离 ≤ 0.022 km）。
     *
     * **后果**：用户读到「需充 2 次」会真的去安排两次补电，
     * 而这条线真实的历史节奏是**平均每 128 km 充一次**（42 场）。
     * 这个数字会直接导致**错误的补电计划**。
     *
     * 现在每句都带「按 X 那趟实测」前缀 ——
     * 把「历史」和「预测」分开说，让用户自己判断可信度。
     */
    fun verdict(): String {
        val asOf = template.lastDrivenAt?.fmtDate()
        val src = asOf?.let { "按 $it 那趟实测" } ?: "按历史记录"
        val low = lowestSoc ?: return "$src：算不出结论（没有足够的电量记录）"
        // ⚠️ v0.29.0（复审 P0-3）：公里与电量必须来自**同一个点**。
        val below = firstBelowFloor
        val belowText = below?.let { (km, soc) ->
            " · 第 ${km.roundToInt()} 公里只剩 $soc%（低于 20% 保底）"
        }
        // ⚠️ **v0.30.0（长途自驾党 P0-4）：全程最低点必须出现在结论句里。**
        //
        // [lowestPoint] 从 v0.29.0 起就在算（`RoutePlan.kt:216-217`），
        // 但**全项目没有任何地方调用它** —— 于是最该看的那个数被算了却没显示。
        //
        // 实测：6227 公里那条的结论句只说「第 227 公里只剩 18%」，
        // 而全程最低是 **5%（第 1242 公里）** —— 1,443 个剖面点里有 **104 个**低于 20%。
        // 只说「第一次跌破」会让人以为「过了 227 公里就没事了」。
        val lowestText = lowestPoint?.let { (km, soc) ->
            // 与「第一个跌破」是同一个点时不重复说
            if (below == null || km > below.first + 0.5) {
                " · 全程最低 $soc%（第 ${km.roundToInt()} 公里）"
            } else {
                ""
            }
        }
        // ⚠️ **v0.30.0（长途自驾党 P0-2）：「平均每 N 公里停一次」必须换成真实站间距。**
        //
        // 原来 `gapKm = 总里程 ÷ 次数` —— **平均数掩盖了分布**。
        // 实测：6227 公里那条的真实站间距是 **2～348 公里**（中位 162、均值 157），
        // 616 公里那条是 **2～189 公里**（中位 133）。
        // 按「平均每 164 公里」准备，会在**最长那 348 公里**那段毫无准备。
        //
        // 现在给真实区间 + 中位 + **最长一段**（那才是要提前安排的）。
        val rhythm = if (stops.size >= 2) {
            val points = (listOf(0.0) + stops.map { it.kmAlongRoute } + listOf(template.distanceKm))
                .sorted()
            val gaps = points.zipWithNext { a, b -> b - a }.filter { it > 0.5 }
            if (gaps.isEmpty()) "" else {
                val maxGap = gaps.max()
                val minGap = gaps.min()
                val medGap = gaps.sorted()[gaps.size / 2]
                "（真实站间距 ${minGap.roundToInt()}～${maxGap.roundToInt()} 公里，" +
                    "中位 ${medGap.roundToInt()}；**最长一段 ${maxGap.roundToInt()} 公里**）"
            }
        } else {
            ""
        }
        return when {
            stops.isNotEmpty() ->
                "$src：那趟实际停了 ${stops.size} 次" + rhythm + (belowText ?: "") + (lowestText ?: "")
            below != null ->
                "$src：$belowText · " +
                    "这条线路上没有你的充电记录，中途补电给不出位置"
            else ->
                "$src：能一次开到 · 预计到达 $low%" +
                    "（这条线路上没有你的充电记录，中途补电给不出位置）"
        }
    }
}

/** 线路分组的键：起止各取「围栏名优先、否则地址第一段」。 */
private fun endpointKey(geofence: String?, place: String?): String =
    geofence?.takeIf { it.isNotBlank() } ?: place.firstSegment() ?: "?"

/**
 * 从历史行程里挑出**历史长途 journey**。
 *
 * ⚠️ **v0.26.0（第 17 轮长途自驾党 P0-1）：单位从「一趟 drive」改成「一段 journey」。**
 *
 * ## 为什么必须改
 *
 * 原来按「起止地名」分组、取**最近那一趟** —— 这在数据上是错的：
 * **充电必然打断行程**（实测 78/78 场充电都落在某趟 drive 的起点或终点附近，
 * 最近距离 ≤ 0.022 km），所以**一趟 drive 内部永远不会发生充电**。
 *
 * 后果（复刻 App 的实际输出）：
 * 1. 「沿途充电站」列出来的其实是**上一趟的收尾充电** ——
 *    它落在本趟的起点里程上，被里程窗口捞进来了。
 * 2. 本趟**真正**的那次充电落在终点里程上，被判成「终点处的充电」而剔除。
 * 3. 「站间距离」「到下一站剩多少电」全建立在**错位的站**上。
 *
 * 「11 条 ≥200 km 的线路」其实是**同一次长途被充电切碎的 11 段**。
 *
 * 现在用 [buildJourneys]：按**里程连续** + **「回家了」**两个判据切出连续出门，
 * 沿途站按**行程边界**归属 —— 详见 [RouteJourney.kt]。
 *
 * @param minDistanceKm 多远算「长途」（默认 **200 km**）
 * @return 按「最近一次走的时刻」倒序排列
 */
fun buildRouteTemplates(
    drives: List<Drive>,
    charges: List<Charge>,
    minDistanceKm: Double = 200.0,
): List<RouteTemplate> =
    buildJourneys(drives, charges, minDistanceKm).map { j ->
        RouteTemplate(
            key = j.key,
            fromLabel = j.fromLabel,
            toLabel = j.toLabel,
            distanceKm = j.distanceKm,
            // ⚠️ 端点取**整段旅程**的两端：
            // 起点 SOC 来自**首段**（出发状态），终点 SOC 来自**末段**（到达状态）。
            startSoc = j.legs.first().startSoc,
            endSoc = j.legs.last().endSoc,
            // ⚠️ v0.28.0（复审 P1-10）：用「有效段数」（排除充电站内挪车微行程）。
            legCount = j.meaningfulLegCount,
            // ⚠️ v0.30.0（P1-2）：**全部**段数也带上（两个数都要能显示）
            totalLegCount = j.legCount,
            stopCount = j.midStopCount,
            lastDrivenAt = j.endDate,
            startOdoKm = j.startKm,
            lastDistanceKm = j.distanceKm,
            // ⚠️ v0.30.0（P0-1）：真实均速（不再硬编码 90 km/h）
            avgSpeedKph = j.avgSpeedKph,
            lastStartSoc = j.legs.first().startSoc,
            lastEndSoc = j.legs.last().endSoc,
            lastDriveId = j.legs.last().driveId,
            legIds = j.legs.map { it.driveId },
            legs = j.legs,
            stops = j.stops,
        )
    }


/**
 * 用模板 + 历史充电 + 站点统计，算出这条线路的余量表。
 *
 * 余量剖面**不是模型算的**，而是把这条线路上**历史上真实发生过的充电**
 * 按里程表对齐到路线公里数上拼出来的：
 *   - 起点：模板的 start_soc
 *   - 每个站：到站时的 socBefore → 充完的 socAfter（一次跳升）
 *   - 终点：模板的 end_soc
 */
fun buildRoutePlan(
    template: RouteTemplate,
    charges: List<Charge>,
    stationStats: Map<String, StationStat>,
    /**
     * ⚠️ v0.24.0：**真实逐公里 SOC 剖面**（来自 `/drives/{id}/soc_profile`）。
     *
     * 原来剖面只有 2~6 个点，中间是**直线插值** ——
     * 山区长途（爬升 1500~2600 m）误差实测达 **13 公里**：
     * 真实跌破 20% 保底在第 244 公里，插值算出来是 231 公里。
     * 用户按 231 公里规划，到 244 公里时电已经见底 —— **会导致危险决策**。
     *
     * 有真实剖面就用它；没有（端点没拉到）才回落到插值。
     */
    socProfile: List<com.teslamate.app.SocProfilePoint>? = null,
    /**
     * ⚠️ **v0.25.0：到下一站剩多少电**要用的两个输入。
     *
     * - [consumption]：本车**实测**能耗模型（按车速分桶的 Wh/km）——
     *   不用「官方标称」，因为标称比实测乐观 50%。
     * - [batteryCapacityKwh]：本车**可用**电池容量（服务端按实测反推）。
     */
    consumption: com.teslamate.app.ConsumptionModel? = null,
    batteryCapacityKwh: Double? = null,
    /** 计划车速（km/h）—— 决定用哪一档 Wh/km。 */
    planSpeedKph: Double? = null,
): RoutePlan? {
    val startOdo = template.startOdoKm ?: return null
    // ⚠️ v0.14.0（口径审计 P0-4）：里程窗口用**最近那一趟的真实里程**，不用中位数。
    // 中位数可能比那一趟长或短，窗口就会捞到别次行程的充电。
    val runDistance = template.lastDistanceKm ?: template.distanceKm
    if (runDistance <= 0) return null

    // ⚠️ **v0.26.0（第 17 轮长途自驾党 P0-1）：沿途站不再用里程窗口「捞」。**
    //
    // 原来这段是：`charges.filter { odo in [startOdo-5, startOdo+runDistance+5] }`。
    // 而实测 **78/78 场充电都落在某趟 drive 的起点或终点附近**（≤0.022 km）——
    // 于是：
    //   · 落在**起点**里程上的那次充电（**上一趟的收尾**）被捞进来，里程是 0
    //     —— 于是「沿途第一个站在第 0 公里」；
    //   · 这一趟**真正**的那次充电落在**终点**里程上，又被「终点处的充电」规则剔除。
    //
    // 结论：**沿途站列表列的是上一趟的起点站**，
    // 而「站间距离」「到下一站剩多少电」全部建立在**错位的站**上。
    //
    // 现在：归属在 [buildJourneys] 里做好了 —— 充电落在**两段之间的缺口**里才算沿途站。
    val journeyStops = template.stops

    // ⚠️ **v0.25.0（长途充电规划专家 P1-2）：站间距离 + 「到下一站剩多少电」。**
    //
    // 原来只给「在第几公里」，不给「到下一站多远」——
    // 而后者才是出发前真正要回答的那句：
    // 「我充到 80%，**够不够开到下一个**？」
    //
    // 站间距离来自相邻充电点的里程差（实测本车真实节奏：中位 128 公里，最远 348 公里）。
    // 「到下一站剩多少」用**本车实测** Wh/km（不是官方标称 —— 标称乐观 50%）
    // 与**可用容量**换算，并与 20% 保底对齐。
    val whPerKm = consumption?.let { m ->
        planSpeedKph?.let { m.whSample(it)?.bucket?.whPerKm }
    }
    val stops = journeyStops.mapIndexed { idx, js ->
        val c = js.charge
        val stat = stationStats[stationKey(c)]
        // 到达后的那次充电没有「下一站」。
        val next = journeyStops.getOrNull(idx + 1)?.takeIf { !js.isEndpoint }
        val gapKm = next?.let { it.kmAlong - js.kmAlong }?.takeIf { it > 0.5 }
        // 充到 socAfter 后，到下一站还剩多少电（%）
        val socAtNext = if (gapKm != null && whPerKm != null && batteryCapacityKwh != null
            && batteryCapacityKwh > 0 && c.end_soc != null
        ) {
            val needKwh = gapKm * whPerKm / 1000.0
            val needPct = (needKwh / batteryCapacityKwh * 100).roundToInt()
            (c.end_soc - needPct).coerceIn(-100, 100)
        } else {
            null
        }
        RouteStop(
            // ⚠️ **v0.29.0（复审 P0-5）：`poi_name` 排到最前。**
            //
            // 原来 `geofence_name` 优先 —— 而本机 `geofences` 只有 1 行、叫「家」，
            // 于是**所有**在家充的电都显示成「家」，
            // 路上的服务区却显示成一长串地址（「臣田社区, 西乡街道, 宝安区, 深圳市…」）。
            //
            // `poi_name` 才是车主认得的名字（「金田服务区」「合江荔乡服务区」），
            // 所以它排第一；围栏名（如果有意义的地名）排第二；地址首段兜底。
            name = c.poi_name?.takeIf { it.isNotBlank() }
                ?: c.geofence_name?.takeIf { it.isNotBlank() }
                ?: c.place.firstSegment() ?: "充电站",
            kmAlongRoute = js.kmAlong,
            // ⚠️ **v0.28.0（复审 P0-①）：必须用**合并后**的电量。**
            //
            // 原来用代表记录的 `c.start_soc / c.end_soc` —— 而代表记录可能只是
            // 一次插枪被切成的**尾段**（0.1 kWh 那种），
            // 于是显示成「94% → 94%」（等于没充，实际充了 36.6 度）。
            // 实测 10 个合并行里 **8 行是错的**：修文县那行真实是 36%→96%
            // 却显示成 76%→96% —— 差 40 个百分点，**剖面的低点被抹平**，
            // 可能漏报「跌破 20% 保底」。
            socBefore = js.socBefore ?: c.start_soc,
            socAfter = js.socAfter ?: c.end_soc,
            typicalPowerKw = stat?.typicalPowerDcKw ?: stat?.typicalPowerKw,
            minutesTo80 = stat?.typicalMinutesTo80,
            sessions = stat?.sessions ?: 1,
            // ⚠️ v0.14.0（长途自驾党 P0-2）：「从**本次到站电量**充到 socAfter」要多久。
            // 原来只有写死的「充到 80% 约 N 分钟」——
            // 我到站 16%、充到 60% 就走，这两个数毫无关系。
            minutesToTarget = stat?.let {
                val kw = it.typicalPowerDcKw ?: it.typicalPowerKw
                val from = c.start_soc
                val to = c.end_soc
                if (from != null && to != null && kw != null) it.minutesTo(from, to, kw) else null
            },
            gapToNextKm = gapKm,
            socAtNextStop = socAtNext,
            // ⚠️ **v0.30.0（长途自驾党 P0-1）：把车速与实际用的 Wh/km 带出去。**
            //
            // 「到下一站剩 34%」不交代前提就只是个数字 ——
            // 它是按「这段线路历史均速 → 对应 Wh/km 档」算的，
            // **没做**温度修正、**没做**爬升修正、**没留**安全余量。
            assumedSpeedKph = planSpeedKph,
            assumedWhPerKm = whPerKm,
        )
    }

    // ⚠️ v0.14.0（口径审计 P0-4）：**终点处的充电不算「沿途站点」**。
    //
    // 原来它被当成沿途站，于是剖面末端竖起一道「16% → 66%」的墙，
    // 末段沿原路折返 —— 读者看到的是「到终点时电量涨到 66%」，
    // 而结论行却写「全程最低 16%（在第 252 公里）」，两个数互相打架。
    //
    // ⚠️ v0.26.0：判据从「里程是否超过终点 5 公里」改成
    // **「这个充电是不是落在最后一段之后的缺口里」**（journey 切分时就已经知道了）。
    val routeStops = stops.filterIndexed { i, _ -> !journeyStops[i].isEndpoint }
    val endpointStops = stops.filterIndexed { i, _ -> journeyStops[i].isEndpoint }

    // 剖面：起点 → (每个**沿途**站：到站电量 → 充完电量) → 终点
    //
    // ⚠️ v0.14.0：端点用**最近那一趟**的真实电量（lastStartSoc/lastEndSoc），
    // 不用中位数 —— 否则就是「中位数起点 + 单次充电点 + 中位数终点」的三明治。
    //
    // ⚠️ v0.24.0：**优先用真实逐公里剖面**（服务端从 positions 的 162 万个采样点算出），
    // 没有时才回落到「端点 + 充电跳升」的插值 ——
    // 因为插值在山区的误差达 13 公里，会让人按错误的公里数规划补电。
    val profile = ArrayList<RouteSocPoint>(routeStops.size * 2 + 2)
    val real = socProfile?.takeIf { it.size >= 3 }
    if (real != null) {
        // 真实剖面：逐 5 公里的实测电量
        real.forEach { p ->
            profile += RouteSocPoint(p.km, p.soc.roundToInt())
        }
        // 充电跳升：把「充完的电量」插在对应公里处（图上看得见那一跳）
        routeStops.forEach { s ->
            s.socAfter?.let { profile += RouteSocPoint(s.kmAlongRoute, it, isCharge = true) }
        }
        profile.sortBy { it.km }
    } else {
        (template.lastStartSoc ?: template.startSoc)?.let { profile += RouteSocPoint(0.0, it) }
        for (s in routeStops) {
            s.socBefore?.let { profile += RouteSocPoint(s.kmAlongRoute, it) }
            s.socAfter?.let { profile += RouteSocPoint(s.kmAlongRoute, it, isCharge = true) }
        }
        (template.lastEndSoc ?: template.endSoc)?.let { profile += RouteSocPoint(runDistance, it) }
    }

    if (profile.size < 2) return null
    return RoutePlan(template, profile, routeStops, endpointStops)
}

/**
 * 中位数（偶数样本取两个中间值的平均，与教科书一致）。
 *
 * ⚠️ v0.14.0（口径审计 P1-4）：实现搬到 `com.teslamate.app.medianOf`，
 * 与 ChargeStats / ConsumptionModel 共用同一份 —— 以前这里有第四个实现。
 */
private fun medianOf(values: List<Double>): Double? = com.teslamate.app.medianOf(values)

/**
 * 找出要显示的线路模板。
 *
 * ⚠️ v0.18.0（长途自驾党 P0-1）：**可选择线路**。
 *
 * 原来 `pickRouteTemplate` 直接取 `templates.firstOrNull()` ——
 * 卡片永远显示「**上次**开的长途」，不是「我这次要开的那条」。
 * 出发前 5 分钟，App 替你猜一条线路是没用的。
 *
 * 现在：传了 [selectedKey] 就用它；没传才回落到「最近走过的那条」。
 */
fun pickRouteTemplate(
    templates: List<RouteTemplate>,
    selectedKey: String? = null,
): RouteTemplate? =
    selectedKey?.let { k -> templates.firstOrNull { it.key == k } } ?: templates.firstOrNull()

/**
 * 线路选择 chips（横向可滑）。
 *
 * ⚠️ v0.18.0（长途自驾党 P0-1）：多条线路时给用户**选**的能力。
 * 只在有 2 条以上线路时出现 —— 单一线路给选择器是噪音。
 */
@Composable
fun RoutePlanChips(
    templates: List<RouteTemplate>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (templates.size < 2) return
    val accents = LocalAccentColors.current
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        // ⚠️ v0.20.0（车主 P1-4）：**末尾留白** ——
        // 原来末项会被卡片右边缘**竖着切掉一半**（只看得见一个字），
        // 用户分不清是「还有更多」还是渲染坏了。
        contentPadding = PaddingValues(end = 12.dp),
    ) {
        items(templates) { t ->
            val selected = t.key == selectedKey
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (selected) accents.energyBlue.copy(alpha = 0.16f) else accents.cardInset,
                // ⚠️ 选中态再加一道主色描边 —— 光靠底色深浅，在浅色模式下差别太弱。
                border = if (selected) {
                    androidx.compose.foundation.BorderStroke(1.dp, accents.energyBlue.copy(alpha = 0.55f))
                } else {
                    null
                },
                modifier = Modifier
                    // ⚠️ v0.20.0（a11y 评审 P0-3）：**用 selectable 而不是 clickable** ——
                    // 「选我要走的那条线」是这个 App 的核心功能，
                    // 而选中态原来只有底色 + 描边（**只靠颜色**，违反 WCAG 1.3.1），
                    // TalkBack 也不知道哪条被选中。
                    // `selectable` 给出 Role.Tab + selected 语义。
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onSelect(t.key) },
                    )
                    // ⚠️ 触控目标：原来 chip 高只有 28dp（16dp 文字 + 12dp 内距），
                    // 远低于 WCAG 2.5.8 的 44dp。
                    .heightIn(min = 44.dp)
                    .semantics(mergeDescendants = true) { },
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // ⚠️ v0.20.0（a11y 评审 P0-3）：**选中项加勾** ——
                    // 非颜色的选中标记（WCAG 1.3.1），色觉障碍用户也读得出。
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = accents.energyBlue,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        "${t.fromLabel} → ${t.toLabel}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        // ⚠️ v0.20.0（a11y 评审 P0-3）：允许两行 ——
                        // 原来 maxLines=1，2 倍字号下截成「广昆高速 → 城…」。
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // ⚠️ v0.26.0：显示**段数**而不是「走过 N 次」——
                    // journey 的段数是一次出门被充电切开的次数，不是「跑过几次」。
                    if (t.legCount >= 1) {
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "${t.legCount} 段",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * ⚠️ v0.14.0（苹果 HIG P1 + 新手车主 P1-2）：**折叠态的一行摘要**。
 *
 * 「记录」页首屏不该被一整张线路规划卡吃掉 —— 车主打开「记录」多数是想看
 * 「我最近干了什么」。但把规划入口整个删掉又不行，
 * 出发前 5 分钟确实要它。
 *
 * 折中：默认一行，**结论句直接写在行里**（不折进去），
 * 点一下才展开完整剖面与站点。
 */
@Composable
fun RoutePlanSummaryRow(
    plan: RoutePlan,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** ⚠️ v0.18.0：全部线路模板（用于选择 chips）。 */
    templates: List<RouteTemplate> = emptyList(),
    /** ⚠️ v0.18.0：当前选中的线路 key。 */
    selectedKey: String? = null,
    /** ⚠️ v0.18.0：切换选中的线路。 */
    onSelectKey: (String) -> Unit = {},
) {
    val accents = LocalAccentColors.current
    val t = plan.template
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = accents.cardSurface,
        modifier = modifier
            .fillMaxWidth()
            .cardDepth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(onClick = onClick),
                ) {
                    Text(
                        "线路余量表 · ${t.fromLabel} → ${t.toLabel}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        // 结论句直接写在摘要行里 —— 出发前 5 分钟只读这一行就够。
                        plan.verdict(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = accents.energyBlue,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = "展开线路余量表",
                    modifier = Modifier
                        // ⚠️ **v0.30.0（第 19 轮 HIG 评审 P1-6）：命中区 22dp → 24dp。**
                        // WCAG 2.5.8（Target Size Minimum）要求 ≥24dp；
                        // 22dp 差 2dp 不达标。
                        .size(24.dp)
                        .clickable(onClick = onClick),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // ⚠️ v0.18.0（长途自驾党 P0-1）：**线路选择 chips** ——
            // 卡片永远显示「上次开的那条」没用，出发前要选的是「我这次要开的那条」。
            // 只在有 2 条以上线路时出现。
            if (templates.size >= 2) {
                Spacer(Modifier.height(8.dp))
                RoutePlanChips(
                    templates = templates,
                    // ⚠️ 没手动选过时，**当前显示的那条**就是选中态 ——
                    // 否则用户看到卡片显示 A 路，chips 里却没有 A 路被选中。
                    selectedKey = selectedKey ?: plan.template.key,
                    onSelect = onSelectKey,
                )
            }
        }
    }
}

/**
 * 路线余量表卡片。
 *
 * ⚠️ v0.11.0（长途自驾党 P0-1）：这是「出发前 5 分钟」真正要看的那张表。
 *
 * 布局刻意做成**一张卡**而不是一个独立页面：
 * 它是「记录」的延伸（数据都来自历史记录），不是平级功能。
 */
@Composable
fun RoutePlanCard(
    plan: RoutePlan,
    modifier: Modifier = Modifier,
    onCollapse: (() -> Unit)? = null,
) {
    val accents = LocalAccentColors.current
    val t = plan.template

    SectionCard("线路余量表", modifier) {
        if (onCollapse != null) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "收起",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable(onClick = onCollapse)
                        .padding(vertical = 4.dp),
                )
                Spacer(Modifier.weight(1f))
            }
        }
        Text(
            plan.conclusion(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        // ⚠️ v0.14.0（长途自驾党 P0-1）：**决策句放在图的上方** ——
        // 出发前 5 分钟，读者只读这一行；图是证据，不是结论。
        Text(
            plan.verdict(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = accents.energyBlue,
        )
        Text(
            // ⚠️ Compose 的 Text 不解析 Markdown —— 之前写了 **粗体**，
            // 结果界面上直接出现两个星号。
            "按这条线路上真实发生过的充电推算（最近一次 ${t.lastDrivenAt?.fmtDate() ?: "—"}）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(10.dp))
        // 余量剖面：X 轴是路线公里数，Y 轴是电量。
        // 剖面里的**跳升**就是充电 —— 不用另加图例，一眼能看懂。
        //
        // ⚠️ v0.14.0（口径审计 P1-3）：加**20% 保底线**。
        // 这张图唯一的决策阈值就是 20%，而原来它既不在轴上也不在网格线上 ——
        // niceTicks(84) 给出的刻度是 0 / 50，读者只能靠底部文字反推位置。
        val profileKmMax = plan.profile.maxOfOrNull { it.km } ?: t.distanceKm
        val weights = plan.profile.map { it.km.toFloat() }
        LineChart(
            values = plan.profile.map { it.soc.toFloat() },
            color = accents.energyBlue,
            fill = true,
            yUnit = "%",
            xWeights = weights,
            xLabels = listOf(
                "0",
                "${(profileKmMax / 2).roundToInt()}",
                "${profileKmMax.roundToInt()}",
            ),
            referenceValue = 20f,
            referenceLabel = "20% 保底",
            contentDescription = "余量剖面：从 ${plan.profile.firstOrNull()?.soc ?: "?"}% 到 ${plan.profile.lastOrNull()?.soc ?: "?"}%，" +
                "沿途 ${plan.stops.size} 次充电，20% 保底线已标出",
        )
        Text(
            "余量 (%) · 横轴 = 路线公里数 · 跳升处即充电",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ⚠️ v0.20.0（车主 P0-2）：**站点区永远渲染** ——
        // 原来 `if (stops.isNotEmpty() || endpointStops.isNotEmpty())` 让整块消失，
        // 于是「没有补电点」和「这张卡没这栏」在界面上**长得一模一样**。
        // 用户根本看不出自己看到的是 0 个站点还是渲染漏了。
        //
        // 现在无站时也把这一栏画出来，明说「没有可参考的补电点」。
        if (plan.stops.isEmpty() && plan.endpointStops.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "沿途充电站",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "这条线路上没有你的充电记录 —— 没有可参考的补电点。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.height(6.dp))
            if (plan.stops.isNotEmpty()) {
                Text(
                    "沿途充电站",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                plan.stops.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            // 起点处的站标「起点」，比「0 km」好懂
                            if (s.kmAlongRoute < 1.0) "起点" else "${s.kmAlongRoute.roundToInt()} 公里",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(62.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            // ⚠️ v0.14.0（长途自驾党 P0-2）：三列决策信息 ——
                            // 到站多少 → 充到多少 ｜ 典型多少 kW ｜ 等多久。
                            // 原来只有「充到 80% 约 N 分钟」（与这次决策无关）
                            // 和「到站 16%」（到站低、充完带多少走，全都没说）。
                            val detail = buildString {
                                s.socBefore?.let { append("到站 $it%") }
                                s.socAfter?.let {
                                    if (isNotEmpty()) append(" → ")
                                    append("充到 $it%")
                                }
                                s.typicalPowerKw?.let {
                                    if (isNotEmpty()) append(" · ")
                                    append("典型 ${it.roundToInt()} kW")
                                }
                                s.minutesToTarget?.let {
                                    if (isNotEmpty()) append(" · ")
                                    // ⚠️ **v0.30.0（长途自驾党 P0-5）：「约 N 分钟」必须标出它是**折算**。**
                                    //
                                    // 这是按**整场平均功率**做的**线性折算**，
                                    // 而直流桩在高 SOC 段会**降功率**（曲线是弯的）——
                                    // 线性折算必然**高估**时间。
                                    // 实测某桩：App 显示「充到 80% 约 54 分钟」，
                                    // 同口径曲线**实测**中位是 **37.5 分钟**（**高估 44%**），
                                    // 单场最高高估 **94%**。
                                    //
                                    // 现在明说它是折算 —— 不标的话，
                                    // 它会和充电详情页的**实测**分钟数并排出现而互相矛盾。
                                    append("约 $it 分钟（按典型功率折算）")
                                }
                                if (isNotEmpty()) append(" · ")
                                // ⚠️ v0.14.0：只充过 1 次时明说，别让「典型」显得有统计支撑。
                                append(if (s.sessions <= 1) "仅 1 次实测" else "${s.sessions} 次")
                            }
                            Text(
                                detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // ⚠️ **v0.25.0（长途充电规划专家 P1-2）：站间距离 + 到下一站剩多少。**
                            //
                            // 这是出发前 5 分钟真正要回答的那句：
                            // 「我充到 80%，**够不够开到下一个**？」
                            //
                            // 原来只给「在第几公里」，用户得自己心算下一站在哪 ——
                            // 而下一站可能在 348 公里外（本车真实最远一段）。
                            s.gapToNextKm?.let { gap ->
                                val nextTxt = buildString {
                                    append("距下一站 ${gap.roundToInt()} 公里")
                                    s.socAtNextStop?.let { soc ->
                                        append(" · 到下一站剩 $soc%")
                                        if (soc < 20) append(" ⚠ 低于 20% 保底")
                                    }
                                }
                                Text(
                                    nextTxt,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = if ((s.socAtNextStop ?: 100) < 20) {
                                        accents.warnAmber
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                // ⚠️ **v0.30.0（长途自驾党 P0-1）：必须交代前提。**
                                //
                                // 「到下一站剩 34%」是全 App **唯一像预测**的一句，
                                // 也是出发前 5 分钟真正会读的那句 ——
                                // 而它只按「车速档的 Wh/km」算：
                                // **零**温度修正、**零**爬升修正、**零**安全余量。
                                //
                                // 实测：90 km/h 档 183.0 Wh/km vs 110–130 档 197.4（差 7.9%），
                                // 128 公里站距上就是 **2.8 个百分点**的到达电量差；
                                // 再叠加 616 公里那条的 896 m 爬升（按 App 自己的 600m+ 档口径 +4.7%）。
                                //
                                // 不说清楚，用户会把这个数当**保证值**。
                                if (s.socAtNextStop != null && s.assumedWhPerKm != null) {
                                    Text(
                                        "（按 ${s.assumedSpeedKph?.roundToInt() ?: 90} km/h 档 " +
                                            "${s.assumedWhPerKm.fmtKwh100()} kWh/100km 算，" +
                                            "未做气温/爬升修正，未留安全余量）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        // 到站电量低于 20% 保底 → 琥珀提示
                        s.socBefore?.let { soc ->
                            if (soc < 20) {
                                Text(
                                    // ⚠️ v0.14.0（新手车主）：「到站 16%」读不出是到达还是出发，
                                    // 改成「到达时剩 16%」。
                                    "到达时剩 $soc%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = accents.warnAmber,
                                )
                            }
                        }
                    }
                }
            }
            // ⚠️ v0.14.0（口径审计 P0-4）：终点处的充电**单独列出**，不混进「沿途」。
            if (plan.endpointStops.isNotEmpty()) {
                Text(
                    "到达后充过电",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                plan.endpointStops.forEach { s ->
                    Text(
                        "${s.name}（终点）" +
                            (s.socBefore?.let { " · 到达时 $it%" } ?: "") +
                            (s.socAfter?.let { "，充到 $it%" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        plan.lowestSoc?.let { low ->
            Spacer(Modifier.height(4.dp))
            val below = plan.belowFloorKm
            Text(
                if (low < 20 && below != null) {
                    "全程最低 $low%（在第 ${below.roundToInt()} 公里，低于 20% 保底）"
                } else {
                    "全程最低 $low%"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (low < 20) accents.warnAmber else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
