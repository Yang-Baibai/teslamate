package com.teslamate.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * - **线路模板**：从历史行程里找「走过 ≥300 km 的长途」，按起止围栏名分组。
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
    /** 该线路的典型里程（km，中位数） */
    val distanceKm: Double,
    /** 出发时的电量（%） */
    val startSoc: Int?,
    /** 到达时的电量（%） */
    val endSoc: Int?,
    /** 走过几次 */
    val occurrences: Int,
    /** 最近一次走的时刻 */
    val lastDrivenAt: Instant?,
    /** 最近一次的起点里程表读数（km）—— 用来把充电站按里程表对齐到路线公里数 */
    val startOdoKm: Double?,
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
}

/** 线路分组的键：起止各取「围栏名优先、否则地址第一段」。 */
private fun endpointKey(geofence: String?, place: String?): String =
    geofence?.takeIf { it.isNotBlank() } ?: place.firstSegment() ?: "?"

/**
 * 从历史行程里挑出**历史长途模板**。
 *
 * @param minDistanceKm 多远算「长途」（默认 300 km）
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
            distanceKm = medianOf(list.mapNotNull { it.distance_km }),
            startSoc = medianOf(list.mapNotNull { it.start_soc }.map { it.toDouble() }).roundToInt(),
            endSoc = medianOf(list.mapNotNull { it.end_soc }.map { it.toDouble() }).roundToInt(),
            occurrences = list.size,
            lastDrivenAt = latest.start_date,
            startOdoKm = latest.odometer_start,
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
): RoutePlan? {
    val startOdo = template.startOdoKm ?: return null
    if (template.distanceKm <= 0) return null

    // 落在这条线路里程区间内的历史充电
    val onRoute = charges.filter { c ->
        val odo = c.start_odometer_km ?: return@filter false
        odo >= startOdo - 5 && odo <= startOdo + template.distanceKm + 5
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
        )
    }

    // 剖面：起点 → (每个站：到站电量 → 充完电量) → 终点
    val profile = ArrayList<RouteSocPoint>(stops.size * 2 + 2)
    template.startSoc?.let { profile += RouteSocPoint(0.0, it) }
    for (s in stops) {
        s.socBefore?.let { profile += RouteSocPoint(s.kmAlongRoute, it) }
        s.socAfter?.let { profile += RouteSocPoint(s.kmAlongRoute, it, isCharge = true) }
    }
    template.endSoc?.let { profile += RouteSocPoint(template.distanceKm, it) }

    if (profile.size < 2) return null
    return RoutePlan(template, profile, stops)
}

/** 中位数（偶数样本取两个中间值的平均，与教科书一致）。 */
private fun medianOf(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
}

/**
 * 找出最值得看的线路模板：**长途优先**，其次是最近走过的。
 */
fun pickRouteTemplate(templates: List<RouteTemplate>): RouteTemplate? =
    templates.firstOrNull()

/**
 * 路线余量表卡片。
 *
 * ⚠️ v0.11.0（长途自驾党 P0-1）：这是「出发前 5 分钟」真正要看的那张表。
 *
 * 布局刻意做成**一张卡**而不是一个独立页面：
 * 它是「记录」的延伸（数据都来自历史记录），不是平级功能。
 */
@Composable
fun RoutePlanCard(plan: RoutePlan, modifier: Modifier = Modifier) {
    val accents = LocalAccentColors.current
    val t = plan.template

    SectionCard("线路余量表", modifier) {
        Text(
            plan.conclusion(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
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
        val weights = plan.profile.map { it.km.toFloat() }
        LineChart(
            values = plan.profile.map { it.soc.toFloat() },
            color = accents.energyBlue,
            fill = true,
            yUnit = "%",
            xWeights = weights,
            xLabels = listOf(
                "0",
                "${(t.distanceKm / 2).roundToInt()}",
                "${t.distanceKm.roundToInt()}",
            ),
            contentDescription = "余量剖面：从 ${t.startSoc ?: "?"}% 到 ${t.endSoc ?: "?"}%，" +
                "沿途 ${plan.stops.size} 次充电",
        )
        Text(
            "余量 (%) · 横轴 = 路线公里数 · 跳升处即充电",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (plan.stops.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
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
                        if (s.kmAlongRoute < 1.0) "起点" else "${s.kmAlongRoute.roundToInt()} km",
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
                        val detail = buildString {
                            s.typicalPowerKw?.let { append("典型 ${it.roundToInt()} kW") }
                            s.minutesTo80?.let {
                                if (isNotEmpty()) append(" · ")
                                append("充到 80% 约 $it 分钟")
                            }
                            append("（${s.sessions} 次）")
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
                                "到站 $soc%",
                                style = MaterialTheme.typography.labelMedium,
                                color = accents.warnAmber,
                            )
                        }
                    }
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
