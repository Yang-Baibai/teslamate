package com.teslamate.app.ui

import com.teslamate.app.BatteryDecay
import com.teslamate.app.fmt
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ⚠️ **v0.35.0（第 21 轮数据口径审计 P1-D + 独立复算）：电池衰减结论的**显示层**。**
 *
 * ## 为什么判定搬到了服务端
 *
 * 原来（v0.29.0）判定在**客户端**做：按里程做最小二乘，
 * 再算一个「效应/噪声」比值。那个比值**不是显著性检验** ——
 * 它拿「整段端到端变化」除以「单点残差 σ」，
 * 而斜率的显著性取决于 **SE(斜率) = σ / √Sxx**，两者不是一回事。
 *
 * 而且闸门是「样本 < 50 个就不给趋势」——
 * 用一个**样本数启发式**代替统计判据，
 * 结果是：数据明明支持结论（t = −5.18，95% 区间不含 0），却被闸门卡掉。
 *
 * 更要命的是**序列根本不是一条直线**。本机 17 个严格档满充点实测：
 *
 * | 模型 | 斜率（度/1000km） | R² |
 * |---|---|---|
 * | 朴素线性 | −0.1375 | 0.641 |
 * | **线性 + 一次阶跃** | **−0.0595** | **0.829** |
 *
 * 而**阶跃前后两段单独看都没有显著趋势** ——
 * 这正是「缓慢连续衰减 + 一次不连续」的指纹。
 * 旧界面的「每 1000 公里 −0.138 度」**高估了 2.3 倍**：
 * 它把一次性的阶跃**抹成了连续趋势**。
 *
 * 现在判定在服务端（`api/battery_decay.go`，带 Go 回归测试），
 * 客户端只负责把结论说清楚。
 */
object BatteryDecayText {

    /**
     * 结论行。
     *
     * ⚠️ **所有者审美：一屏一个主角，装饰宁删勿留** ——
     * 所以这里只给「样本与覆盖」+「连续衰减速率」+「那次阶跃」三行，
     * 其它推导（分裂半、R²、置信区间宽度）留在服务端的 `method` / `caveat` 里。
     */
    fun lines(d: BatteryDecay): List<String> {
        val out = mutableListOf<String>()

        // ── 第一行：样本与覆盖 ────────────────────────────────
        val sample = StringBuilder()
        sample.append(d.n).append(" 个 100% 满充点")
        if (d.excluded_n > 0) {
            sample.append("（另有 ").append(d.excluded_n).append(" 个 90~98% 的点未计入）")
        }
        sample.append(" · ").append(d.span_km.roundToInt()).append(" 公里 / ")
            .append(d.span_days).append(" 天")
        out += sample.toString()

        // ── 第二行：连续衰减速率（主角） ──────────────────────
        val rate = if (d.has_step) d.underlying_per_1000km else d.naive_per_1000km
        val lo = if (d.has_step) d.underlying_ci_lo else d.naive_ci_lo
        val hi = if (d.has_step) d.underlying_ci_hi else d.naive_ci_hi
        val significant = if (d.has_step) d.underlying_significant else d.naive_significant
        val rateLine = StringBuilder()
        rateLine.append("连续衰减 每 1000 公里 ").append(rate.fmt(3)).append(" 度")
        rateLine.append("（95% 区间 ").append(lo.fmt(3)).append(" ~ ").append(hi.fmt(3)).append("）")
        if (!significant) {
            rateLine.append(" —— 区间跨过 0，还不能当趋势看")
        }
        out += rateLine.toString()

        // ── 第三行：那次阶跃（如果检测到且显著） ──────────────
        if (d.has_step) {
            val step = StringBuilder()
            step.append(d.step_date).append(" 有一次 ").append(d.step_kwh.fmt(2)).append(" 度的台阶")
            step.append("（").append(d.step_km.roundToInt()).append(" 公里处）")
            if (d.step_significant) {
                step.append("—— 一次性事件，不是持续衰减")
            } else {
                step.append("（统计上不显著，仅供参考）")
            }
            if (d.naive_overstate > 1.15) {
                step.append("；不扣它，表面速率会高估 ")
                    .append(d.naive_overstate.fmt(1)).append(" 倍")
            }
            out += step.toString()
        }
        return out
    }

    /**
     * 口径提示（放在「怎么算的」那一层）。
     *
     * ⚠️ **诚实性要求**：容量口径是「续航估值 × 效率常数」，
     * 残差里**大部分是充电时气温**而不是测量噪声 ——
     * 不说清楚，用户会把这条线当成「电池在稳定衰减」。
     */
    fun caveatLine(d: BatteryDecay): String =
        "容量口径是「续航估值 × 效率常数」，残差里大部分是充电时气温而不是测量噪声；" +
            "结论只在这 ${d.span_km.roundToInt()} 公里 / ${d.span_days} 天内有效，不能外推"
}
