package com.teslamate.app.ui

import com.teslamate.app.BatteryDecay
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.147.0：ⓘ 里一行一个事实、每行不许超宽。**
 *
 * ## 为什么要有这条
 *
 * 服务端下发的 caveat 是**整段**（实测视觉宽度 **750**，12 个话题用「；」挤在一起）
 * ⇒ 展开就是一堵字墙；而客户端的 ⓘ 预算只扫客户端字面量 ⇒ 服务端那半没人管。
 *
 * ⇒ 客户端改成**按「；」拆行**（信息一条没丢），并用这道单测钉住**每一行**的宽度。
 * ⇒ 服务端那半由 Go 单测（`TestDecayNoteLinesWithinWidth`）钉住。
 */
class BatteryDecayNoteTest {

    /** 服务端下发的**真实形状**（本机 2026-11-12 线上那份）。 */
    private fun serverShaped() = BatteryDecay(
        n = 18, strict_n = 18, excluded_n = 12, dropped_empty = 1,
        span_km = 10104.457, span_days = 90,
        naive_per_1000km = -0.104, naive_ci_lo = -0.162, naive_ci_hi = -0.047,
        naive_significant = true, r2_linear = 0.478,
        has_step = true, step_km = 96791.508598, step_date = "2026-09-28",
        step_kwh = -0.7, step_ci_lo = -1.078, step_ci_hi = -0.323, step_significant = true,
        r2_step = 0.744, naive_overstate = 5.446,
        underlying_per_1000km = -0.019, underlying_ci_lo = -0.081, underlying_ci_hi = 0.043,
        temp_per_c = 0.011, temp_r2 = 0.151, temp_n = 18,
        adjusted_per_1000km = -0.023, adjusted_ci_lo = -0.083, adjusted_ci_hi = 0.037,
        leave_one_out_min = -0.037, leave_one_out_max = -0.004,
        leave_one_out_total = 18, leave_one_out_significant = 0,
        conclusion = "weak",
        method = "严格档（充到 100%）按里程拟合；先剔除落差再给连续速率；显著性用 t 检验。",
        caveat = "检测到一次落差：2026-09-28（96792 公里处）-0.700 度（95% 区间 -1.078 ~ -0.323）；" +
            "它可能不是电池变化 —— 也可能是车机重新标定了续航估值；" +
            "本 App 分不清这两者，别把它当成「电池掉了一次」；" +
            "⚠️ 不扣这次落差的话，表面速率会被高估 5.4 倍（-0.104 → -0.019 度/1000 公里）；" +
            "把充电时气温放进模型后：连续速率 -0.023（区间 -0.083 ~ 0.037）⇒ 检不出；" +
            "气温系数 +0.01100 度/°C —— 20°C 的温差就值 0.220 度，与那个落差同量级；" +
            "留一法（去掉任意一个点重算）：速率落在 -0.037 ~ -0.004，其中 0/18 次仍然显著；" +
            "气温只解释残差方差的 15%（不是「大部分」）；残差里还有别的东西；" +
            "剔除了 1 场没有实际充电的场次（同一场会话被拆成两段的尾巴，不是独立测量点）；" +
            "容量口径是「续航估值 × 效率常数」（= 车机上报的续航估值 × 0.146，不是物理测量）；" +
            "结论只在这 10104 公里 / 90 天内有效，不能外推",
    )

    @Test
    fun `每一行都不许超宽`() {
        val lines = BatteryDecayText.detailLines(serverShaped())
        assertTrue("ⓘ 应该有内容", lines.isNotEmpty())
        for (l in lines) {
            assertTrue(
                "ⓘ 有一行宽 ${noteVisualWidth(l)} > $NOTE_LINE_LIMIT：$l",
                noteVisualWidth(l) <= NOTE_LINE_LIMIT,
            )
        }
    }

    @Test
    fun `服务端那一整段必须被拆成多行_不能还是一堵字墙`() {
        val lines = BatteryDecayText.detailLines(serverShaped())
        // 服务端那段 12 个话题 ⇒ 拆开后至少 10 行（原来只有 1 行 ⇒ 宽 750）。
        assertTrue("ⓘ 只拆出 ${lines.size} 行：$lines", lines.size >= 10)
    }
}