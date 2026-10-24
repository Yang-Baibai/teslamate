package com.teslamate.app.ui

import com.teslamate.app.TrackPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 行程功率剖面 —— 「功率 (kW) 随里程变化」。
 *
 * ## 为什么是这条曲线
 *
 * 长途自驾党在实测 43 条行程后选了它当唯一最有价值的曲线，理由：
 *
 * 1. **一条线同时给出能耗、地形、回收三件事**，海拔曲线是它的子集 ——
 *    实测高速段爬升 600 m+ 只比平地多 5% Wh/km，说明海拔单独画几乎没有额外信息。
 * 2. **X 轴必须是里程，不是时间**。时间轴在这个场景里**会骗人**：
 *    堵车 40 分钟那段会被画成一大片低功率，看着像"省电了"，其实你是在原地烧电。
 *    长途的决策变量是「还剩多少公里到下一个服务区」。
 * 3. 它能解释 Wh/km 的异常 —— 看到 100 km/h 时功率 22 kW 巡航、一段爬坡跳到 48 kW、
 *    下坡掉到 -60 kW，下次跑同一条高速就会主动避开那个爬坡。
 *
 * ## 为什么按 1 km 分箱
 *
 * 服务端的 track 是「每 N 个取 1 个」的朴素降采样（`api/handlers.go`），
 * 直接连线会**混叠**（把高频抖动画成大起大落）。按 1 km 分箱取平均天然抗混叠。
 */
data class PowerSample(
    /** 累计里程（km） */
    val km: Double,
    /** 电池净功率（kW），正=耗电，负=回收 */
    val powerKw: Double,
    /**
     * 该分箱处的电量百分比（null = 这一箱没采到电量）。
     *
     * ⚠️ v0.9.0 新增：长途自驾党要的是「我会在哪一公里掉到 20%」，
     * 光有功率曲线答不了 —— 得把**电量**这条线也画出来。
     */
    val soc: Int? = null,
)

/**
 * 电量下降过程中的两个关键里程点。
 *
 * ⚠️ v0.9.0：长途自驾党的原话是
 * 「图上只有功率没有电量，我看不到'还能撑到哪个服务区'」。
 * 这两个点就是他在高速服务区规划时要读的数。
 */
data class SocMilestones(
    /** 掉到 20% 的里程（null = 整趟没掉到 20%） */
    val kmAt20: Double? = null,
    /** 掉到 10% 的里程（null = 整趟没掉到 10%） */
    val kmAt10: Double? = null,
)

/**
 * 算「掉到 20% / 10% 分别在第几公里」。
 *
 * 用**线性插值**而不是「第一个低于阈值的采样点」——
 * 后者会把「在 142 公里时掉到 21%，143 公里时掉到 19%」说成 143，
 * 而真实答案在 142.5。长途规划差半公里无所谓，但**口径要诚实**：
 * 报出来的就是插值出来的那个点。
 */
fun List<PowerSample>.socMilestones(): SocMilestones {
    fun cross(target: Int): Double? {
        var prev: PowerSample? = null
        for (s in this) {
            val soc = s.soc ?: continue
            if (soc <= target) {
                val p = prev
                return if (p != null && p.soc != null && p.soc > target) {
                    val span = (p.soc - soc).toDouble()
                    val frac = if (span <= 0.0) 0.0 else (p.soc - target) / span
                    p.km + (s.km - p.km) * frac.coerceIn(0.0, 1.0)
                } else {
                    s.km
                }
            }
            prev = s
        }
        return null
    }
    return SocMilestones(cross(20), cross(10))
}

/**
 * 把采样点转成「累计里程 → 功率」剖面，并按 1 km 分箱取平均。
 *
 * 里程用**哈弗森公式**在原始 GPS 点（`lat`/`lng`，WGS-84）上算 ——
 * 不能用 `map_lat`/`map_lng`（那是 GCJ-02 偏移后的坐标，用于国内地图显示）。
 *
 * @param realLengthKm 这趟行程的**真实里程**（km，通常取服务端的 `distance_km`）。
 *   传了就把分箱中心线性拉伸到 [0, realLengthKm]；不传则保留分箱中心。
 */
fun List<TrackPoint>.toPowerProfile(
    binKm: Double = 1.0,
    realLengthKm: Double? = null,
): List<PowerSample> {
    if (size < 2) return emptyList()
    var acc = 0.0
    val raw = ArrayList<PowerSample>(size)
    raw += PowerSample(0.0, first().power?.toDouble() ?: 0.0, first().battery_level)
    for (i in 1 until size) {
        acc += haversineKm(this[i - 1], this[i])
        raw += PowerSample(acc, this[i].power?.toDouble() ?: 0.0, this[i].battery_level)
    }
    if (raw.size < 4) return raw

    // 按里程分箱取平均（抗混叠）
    val bins = HashMap<Int, MutableList<Double>>()
    val socBins = HashMap<Int, MutableList<Int>>()
    raw.forEach { s ->
        val bin = (s.km / binKm).toInt()
        bins.getOrPut(bin) { mutableListOf() }.add(s.powerKw)
        s.soc?.let { v -> socBins.getOrPut(bin) { mutableListOf() }.add(v) }
    }
    val binned = bins.entries
        .sortedBy { it.key }
        .map { (bin, list) ->
            PowerSample(
                km = (bin + 0.5) * binKm,
                powerKw = list.average(),
                // ⚠️ v0.9.0：同一箱里的电量取平均（同样抗混叠）
                soc = socBins[bin]?.average()?.roundToInt(),
            )
        }

    // ⚠️ v0.14.0（口径审计 P0-2）：把分箱中心**重标定**到真实行程长度。
    //
    // 分箱中心 (bin + 0.5) * binKm 是「理论中心」，与行程实际长度无关：
    // 2.28 km 的行程会得到 0.5 / 1.5 / 2.5 三个中心，最后一个已经越过了终点。
    // 于是横轴的分母（2.5）与刻度标签的真实里程（2.28）对不上 ——
    // 标签「1」标称 1.14 km 却画在 45.6% 处，1.5 km 的数据点却在 60% 处。
    //
    // 现在把整条剖面**线性拉伸**到 [0, realLengthKm]：
    // 首点落在 0 km、末点落在真实终点，中间按比例分布。
    // 「首点 = 起点刻度、末点 = 终点刻度」这才真正成立。
    val realLen = realLengthKm
    if (realLen == null || realLen <= 0.0 || binned.size < 2) return binned
    val firstKm = binned.first().km
    val lastKm = binned.last().km
    val span = lastKm - firstKm
    if (span <= 0.0001) return binned
    val scale = realLen / span
    return binned.map { s -> s.copy(km = (s.km - firstKm) * scale) }
}

private fun haversineKm(a: TrackPoint, b: TrackPoint): Double {
    val r = 6371.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val la1 = Math.toRadians(a.lat)
    val la2 = Math.toRadians(b.lat)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(la1) * cos(la2) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
}
