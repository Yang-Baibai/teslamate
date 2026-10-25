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
    /** 出发时的电量（%，中位数） */
    val startSoc: Int?,
    /** 到达时的电量（%，中位数） */
    val endSoc: Int?,
    /** 走过几次 */
    val occurrences: Int,
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
    /** 最近一次的出发电量（%） */
    val lastStartSoc: Int? = null,
    /** 最近一次的到达电量（%） */
    val lastEndSoc: Int? = null,
    /**
     * ⚠️ v0.24.0：**最近那一趟的 drive id** ——
     * 用来拉它的**真实逐公里 SOC 剖面**（`/drives/{id}/soc_profile`）。
     */
    val lastDriveId: Int? = null,
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

    /** 低于 20% 保底的公里数 */
    val belowFloorKm: Double? get() = profile.firstOrNull { it.soc < 20 }?.km

    /** 一句话结论 */
    fun conclusion(): String {
        val s = template
        val parts = mutableListOf<String>()
        parts += "${s.fromLabel} → ${s.toLabel}"
        parts += "${s.distanceKm.roundToInt()} 公里"
        parts += "走过 ${s.occurrences} 次"
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
        val below = belowFloorKm
        return when {
            stops.isNotEmpty() ->
                "$src：需充 ${stops.size} 次" +
                    (below?.let { " · 第 ${it.roundToInt()} 公里低于 20% 保底" } ?: "")
            below != null ->
                "$src：第 ${below.roundToInt()} 公里只剩 $low%（低于 20% 保底）· " +
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
 * 从历史行程里挑出**历史长途模板**。
 *
 * @param minDistanceKm 多远算「长途」（默认 **200 km**）
 * @return 按「最近一次走的时刻」倒序排列
 */
fun buildRouteTemplates(
    drives: List<Drive>,
    minDistanceKm: Double = 200.0,
): List<RouteTemplate> {
    val long = drives.filter {
        val km = it.distance_km ?: 0.0
        km >= minDistanceKm && it.start_soc != null
    }
    if (long.isEmpty()) return emptyList()

    val groups = long.groupBy { d ->
        endpointKey(d.start_geofence, d.start_place) + "→" + endpointKey(d.end_geofence, d.end_place)
    }

    return groups.mapNotNull { (key, list) ->
        val latest = list.maxByOrNull { it.start_date } ?: return@mapNotNull null
        val fromLabel = latest.start_geofence ?: latest.start_place.firstSegment() ?: "起点"
        val toLabel = latest.end_geofence ?: latest.end_place.firstSegment() ?: "终点"
        RouteTemplate(
            key = key,
            fromLabel = fromLabel,
            toLabel = toLabel,
            distanceKm = medianOf(list.mapNotNull { it.distance_km }) ?: 0.0,
            startSoc = medianOf(list.mapNotNull { it.start_soc }.map { it.toDouble() })?.roundToInt(),
            // ⚠️ v0.14.0（口径审计 P1-1）：原来这里用 `medianOf(...)` 之后拿到的
            // 可能是**空列表 → 0.0**，于是「没有终点电量记录」的线路
            // 端点画成 0%，报出「全程最低 0%（低于 20% 保底）」——
            // 一个**假警报**，会让车主以为必须在终点前充电。
            // 现在空 → null → 不画该点。
            endSoc = medianOf(list.mapNotNull { it.end_soc }.map { it.toDouble() })?.roundToInt(),
            occurrences = list.size,
            lastDrivenAt = latest.start_date,
            startOdoKm = latest.odometer_start,
            // ⚠️ v0.14.0（口径审计 P0-4）：剖面必须与充电点**同源** ——
            // 端点用最近那一趟的真实值，不用中位数。
            lastDistanceKm = latest.distance_km,
            lastStartSoc = latest.start_soc,
            lastEndSoc = latest.end_soc,
            // ⚠️ v0.24.0：记下最近那一趟的 drive id，
            // 用来拉它的**真实逐公里 SOC 剖面**。
            lastDriveId = latest.id,
        )
    }.sortedByDescending { it.lastDrivenAt }
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
): RoutePlan? {
    val startOdo = template.startOdoKm ?: return null
    // ⚠️ v0.14.0（口径审计 P0-4）：里程窗口用**最近那一趟的真实里程**，不用中位数。
    // 中位数可能比那一趟长或短，窗口就会捞到别次行程的充电。
    val runDistance = template.lastDistanceKm ?: template.distanceKm
    if (runDistance <= 0) return null

    // 落在这条线路里程区间内的历史充电。
    //
    // ⚠️ v0.14.0（口径审计 P0-4）：窗口**只认最近那一趟**的里程区间。
    // 里程表单调递增，所以「[最近那趟的起点, 最近那趟的终点]」天然把
    // 上一次同线路、反向返程的充电排除在外（它们的里程表更小）。
    val onRoute = charges.filter { c ->
        val odo = c.start_odometer_km ?: return@filter false
        odo >= startOdo - 5 && odo <= startOdo + runDistance + 5
    }.sortedBy { it.start_odometer_km ?: 0.0 }

    val stops = onRoute.map { c ->
        val stat = stationStats[stationKey(c)]
        RouteStop(
            name = c.geofence_name ?: c.place.firstSegment() ?: "充电站",
            kmAlongRoute = ((c.start_odometer_km ?: startOdo) - startOdo).coerceAtLeast(0.0),
            socBefore = c.start_soc,
            socAfter = c.end_soc,
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
        )
    }

    // ⚠️ v0.14.0（口径审计 P0-4）：**终点处的充电不算「沿途站点」**。
    //
    // 原来它被当成沿途站，于是剖面末端竖起一道「16% → 66%」的墙，
    // 末段沿原路折返 —— 读者看到的是「到终点时电量涨到 66%」，
    // 而结论行却写「全程最低 16%（在第 252 公里）」，两个数互相打架。
    // （实测：那条 252 km 的线路，唯一列出的「沿途充电站」就是终点城东路。）
    val routeStops = stops.filter { it.kmAlongRoute <= runDistance - 5 }
    val endpointStops = stops.filter { it.kmAlongRoute > runDistance - 5 }

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
                    // ⚠️ v0.20.0（车主 P1-4）：**次数 ≥1 就显示** ——
                    // 原来只在 >1 时渲染，于是「只跑过 1 次」和「没有数据」
                    // 在界面上长得一模一样 —— 而出发前判断「这条线我熟不熟」
                    // 恰恰要区分这两种情况。
                    if (t.occurrences >= 1) {
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "×${t.occurrences}",
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable(onClick = onClick),
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
                                    append("约 $it 分钟")
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
