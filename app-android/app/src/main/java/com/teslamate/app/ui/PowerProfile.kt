package com.teslamate.app.ui

import com.teslamate.app.TrackPoint
import kotlin.math.asin
import kotlin.math.cos
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
)

/**
 * 把采样点转成「累计里程 → 功率」剖面，并按 1 km 分箱取平均。
 *
 * 里程用**哈弗森公式**在原始 GPS 点（`lat`/`lng`，WGS-84）上算 ——
 * 不能用 `map_lat`/`map_lng`（那是 GCJ-02 偏移后的坐标，用于国内地图显示）。
 */
fun List<TrackPoint>.toPowerProfile(binKm: Double = 1.0): List<PowerSample> {
    if (size < 2) return emptyList()
    var acc = 0.0
    val raw = ArrayList<PowerSample>(size)
    raw += PowerSample(0.0, first().power?.toDouble() ?: 0.0)
    for (i in 1 until size) {
        acc += haversineKm(this[i - 1], this[i])
        raw += PowerSample(acc, this[i].power?.toDouble() ?: 0.0)
    }
    if (raw.size < 4) return raw

    // 按里程分箱取平均（抗混叠）
    val bins = HashMap<Int, MutableList<Double>>()
    raw.forEach { s ->
        val bin = (s.km / binKm).toInt()
        bins.getOrPut(bin) { mutableListOf() }.add(s.powerKw)
    }
    return bins.entries
        .sortedBy { it.key }
        .map { (bin, list) ->
            PowerSample(
                km = (bin + 0.5) * binKm,
                powerKw = list.average(),
            )
        }
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
