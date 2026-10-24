package com.teslamate.app

/**
 * **中位数**（教科书口径：偶数样本取两个中间值的平均）。
 *
 * ⚠️ v0.14.0（口径审计 P1-4）：这个函数以前在四个地方各写一遍，
 * 而且**只有两个改对了**：
 *
 * | 位置 | 原写法 | 偶数样本时 |
 * |---|---|---|
 * | `ChargeStats.typicalPowerKw` | `median()` | ✅ 两个中间值平均 |
 * | `ChargeStats.batteryCapacityKwh` | `sorted()[n/2]` | ❌ 上中位数（偏大） |
 * | `ChargeStats.typicalMinutesTo80` | `minutes[n/2]` | ❌ 上中位数（偏大） |
 * | `ConsumptionModel` 各档 Wh/km | `sorted()[n/2]` | ❌ 上中位数（偏大） |
 * | `ConsumptionModel` 巡航速度 | `peaks[n/2]` | ❌ 上中位数（偏大） |
 *
 * 第三方按 numpy / pandas / R 的 `median()` 复算会**系统性对不上**
 * （Wh/km 差 0.5~2%，耗时差 1 分钟）——
 * 而 `ConsumptionModel` 那两处直接决定界面上「实测 183 Wh/km」这个数的大小。
 *
 * 现在**全项目只有这一个实现**。
 */
fun medianOf(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
}

/**
 * 最近秩法的 P90（numpy `percentile(..., method="inverted_cdf")` 口径）。
 *
 * ⚠️ v0.14.0（口径审计 P1-5）：原来的写法
 * `minutes[(minutes.size * 0.9).toInt().coerceAtMost(n - 1)]`
 * 取的其实是**最大值**（n=5 → index 4；n=10 → index 9），
 * 却标成「P90」—— 而 numpy 的 `percentile(x, 90)` 用线性插值，**是第三个值**。
 * 三个口径三个数，标成 P90 是误导。
 *
 * 现在统一成最近秩法：`idx = ceil(0.9 * n) - 1`。
 *
 * ⚠️ 样本 < 10 时 P90 与最大值没有区别，此时**不给 P90**（返回 null），
 * 界面改用「历史最慢 N 分钟」的说法。
 */
fun p90NearestRank(values: List<Int>): Int? {
    val n = values.size
    if (n < 10) return null
    val idx = kotlin.math.ceil(0.9 * n).toInt() - 1
    return values.sorted()[idx.coerceIn(0, n - 1)]
}
