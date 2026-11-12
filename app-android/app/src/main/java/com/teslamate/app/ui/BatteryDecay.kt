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
 * ## ⚠️ **v0.41.0（第 24 轮对抗性审计 ②③④）：连「显著」两个字都不能随便说**
 *
 * v0.39.0 报的是「连续衰减 每 1000 公里 -0.060 度（95% 区间 -0.118 ~ -0.001）」。
 * 复审把它拆开之后发现**三个问题**：
 *
 * ① **区间上界离 0 只有 0.001**（点估计的 1.0%）—— 把结论架在刀尖上；
 * ② **气温是混杂因素**：旧文案说「残差里大部分是充电时气温」，
 *    实测 r = +0.42 ⇒ R² = **0.18**（气温只解释 18%，不是大部分），
 *    可气温系数 +0.0092 度/°C，20°C 温差就值 0.18 度 —— **与那个落差同量级**。
 *    把气温放进模型后，结论直接翻成「检不出」。
 * ③ **那个 0.04 度的空会话混进了回归**：17 个点里有一个是
 *    `100%→100%、0.04 度、1 分钟`（同一场会话被 TeslaMate 拆成的尾巴）。
 *    **剔除它之后，连续速率的 95% 区间变成 [-0.115, +0.007] —— 跨过 0 了。**
 *    也就是说 v0.39.0 的「显著」**是靠那个空会话撑着的**。
 *
 * ## v0.41.0 的做法
 *
 * 服务端（`api/battery_decay.go`）现在额外做三件事：
 * **加气温模型**、**留一法**、**剔除空会话**，
 * 并给出一个**结论强度**（`detectable` / `weak` / `not_detectable`）。
 *
 * 客户端**照着这个强度说话**，不再自己下「显著 / 不显著」的判断。
 */
object BatteryDecayText {

    /**
     * **结论行** —— 这张卡唯一常驻的说明（主角）。
     *
     * ⚠️ **所有者审美：一屏一个主角，装饰宁删勿留** ——
     * 样本量、那次落差、口径边界全是**补充说明** ⇒ 折进 ⓘ（v0.69.0）；
     * 其它推导（分裂半、R²、置信区间宽度）进「怎么算的」折叠区。
     */
    fun headline(d: BatteryDecay): String = rateLine(d)

    /**
     * **补充说明** —— 折进结论行的 ⓘ（v0.69.0）。
     *
     * ⚠️ **v0.69.0（真机 UI 树实测）**：这三段原先是**三段直接可见的灰字**，
     * 合计 **101 个汉字** —— 真机滚到电池卡底部时它们占了半屏，
     * 而它们全都是「结论的依据」，不是结论本身。
     *
     * 结论（衰减速率）留在外面；样本量 / 那次落差 / 口径边界折进来。
     * 一段都没删，只是换了位置。
     *
     * ⚠️ **v0.99.0（第 9 轮）：这三段里只有第一段是「独有」的。**
     *
     * 原来的第 2 段（落差）与第 3 段（容量口径 + 不能外推），
     * **服务端 `caveat` 里一字不差地已经有了** —— 而且更全
     * （落差还带日期、公里处、「不扣它会高估 3.3 倍」；
     * 口径还带「= 车机上报的续航估值 × 0.146」）。
     * `caveat` 由 [detailLines] 渲染在**同一张卡**的「怎么算的」折叠区里。
     *
     * ⇒ 客户端这第二份副本是 **v0.41.0 注释早就警告过的漂移源**
     * （「口径提示与服务端判定**必须是同一份**，否则必然漂移」）。
     * ⇒ 这一轮删掉它：**不是丢信息，是去掉一个会自己跑偏的副本。**
     *
     * 保留第 1 段（样本与覆盖）—— 其中「12 个 90~98% 未计入」
     * 是**服务端 `caveat` 里没有的**（`caveat` 只说了剔除 1 场空会话）。
     */
    fun note(d: BatteryDecay): String {
        val parts = mutableListOf<String>()

        // ── 样本与覆盖 ────────────────────────────────────────
        val sample = StringBuilder()
        sample.append(d.n).append(" 个 100% 满充点")
        if (d.excluded_n > 0) {
            sample.append("（").append(d.excluded_n).append(" 个 90~98% 未计入）")
        }
        sample.append(" · ").append(d.span_km.roundToInt()).append(" 公里 / ")
            .append(d.span_days).append(" 天")
        // ⚠️ v0.41.0：空会话要**说清楚剔了什么** ——
        // 悄悄少一个点，读者会以为「怎么从 29 个变成 16 个」。
        if (d.dropped_empty > 0) {
            sample.append(" · 剔除 ").append(d.dropped_empty).append(" 场没实际充电的")
        }
        parts += sample.toString()

        // ── 那次落差（如果检测到） ───────────────────────────
        if (d.has_step) {
            val step = StringBuilder()
            step.append("有一次 ").append(d.step_kwh.fmt(2)).append(" 度落差，已扣除")
            // ⚠️ **v0.41.0：不能替用户下「只是重新标定」的判断** ——
            // 那是**物理断言**，而数据分不清「电池真掉了一次」与
            // 「车机重新标定了续航估值」。所以只说「分不清」。
            // 完整的那次落差（日期 / 公里处 / 不扣它会高估几倍）由服务端
            // `caveat` 呈现，见「怎么算的」。
            step.append("（车机重新标定还是电池变化，分不清）。")
            parts += step.toString()
        }

        return parts.joinToString("\n\n")
    }

    /**
     * 连续衰减速率那一行 —— **按结论强度说话**。
     *
     * ⚠️ 「weak」（本机数据的真实状态）不能写成「显著」，
     * 也不能只写「区间跨过 0」（那太技术，读者会以为是 App 没算出来）。
     * 正确说法是：**测到了，但和 0 区分不开**。
     */
    private fun rateLine(d: BatteryDecay): String {
        val rate = if (d.has_step) d.underlying_per_1000km else d.naive_per_1000km
        val lo = if (d.has_step) d.underlying_ci_lo else d.naive_ci_lo
        val hi = if (d.has_step) d.underlying_ci_hi else d.naive_ci_hi
        val sb = StringBuilder()
        when (d.conclusion) {
            "detectable" -> {
                sb.append("连续衰减 每 1000 公里 ").append(rate.fmt(3)).append(" 度")
                sb.append("（95% 区间 ").append(lo.fmt(3)).append(" ~ ").append(hi.fmt(3)).append("）")
            }
            "weak" -> {
                // ⚠️ v0.41.0：**主语是「测到」**，不是「有」。
                //
                // ⚠️ **v0.74.0（模拟器实测）：结论提到最前。**
                //
                // 原来：「连续衰减：测到 -0.054 度/1000 公里，但与 0 区分不开（95% 区间 …）」
                // —— 数字在前、结论在后 ⇒ 眼睛先扫到「-0.054 度/1000 公里」，
                // 读成「电池每千公里掉 0.054 度」，**直到句尾才知道这个数其实站不住**。
                //
                // 这与「百分比数字本身就是误读入口」是同一个病：
                // **先给出结论，数字只当证据。**
                sb.append("连续衰减：与 0 区分不开")
                sb.append("（测到 ").append(rate.fmt(3)).append(" 度/1000 公里")
                sb.append("，95% 区间 ").append(lo.fmt(3)).append(" ~ ").append(hi.fmt(3)).append("）")
            }
            "not_detectable" -> {
                sb.append("连续衰减：检不出趋势")
                sb.append("（95% 区间 ").append(lo.fmt(3)).append(" ~ ").append(hi.fmt(3)).append("，跨过 0）")
            }
            else -> {
                sb.append("样本不足以判定衰减趋势")
            }
        }
        return sb.toString()
    }

    /**
     * 「怎么算的」折叠区里的内容。
     *
     * ⚠️ **v0.41.0：改用服务端给的 caveat** ——
     * 原来这里有一份**客户端自己写的**口径提示，
     * 它说「残差里大部分是充电时气温」—— 实测只有 **12%**（R² = 0.121）。
     * 口径提示与服务端判定**必须是同一份**，否则必然漂移。
     */
    fun detailLines(d: BatteryDecay): List<String> {
        val out = mutableListOf<String>()
        // ⚠️ **v0.147.0：服务端下发的 caveat 是**整段** ⇒ 这里按「；」拆成一行一个事实。
        //
        // 实测（2026-11-12）：那整段视觉宽度 **750**（12 个话题挤在一起）⇒ 展开是一堵字墙；
        // 而 ⓘ 预算（160）只扫客户端字面量 ⇒ 服务端这一半一个判据都没有。
        // ⇒ 拆行：**信息一条没丢**，只是从「段落」变成「清单」；
        //   每行的宽度由 [NOTE_LINE_LIMIT] 钉住（客户端 JVM 单测 + 服务端 Go 单测各钉一半）。
        d.caveat.split("；").map { it.trim() }.filter { it.isNotEmpty() }.forEach { out += it }
        if (d.method.isNotBlank()) out += "算法：" + d.method
        // ⚠ v0.41.0：稳健性检查的三个数**也要给出来** ——
        // 用户要判断「这个结论靠不靠谱」，就得能看到它有多脆。
        // ⚠️ **v0.147.0**：原来这三件挤成一行（实测 159 宽）⇒ 拆成两行（一行一个话题）。
        if (d.temp_n >= 5) {
            out += "气温模型：加充电时气温后是 " + d.adjusted_per_1000km.fmt(3) +
                " 度/1000 公里（区间 " + d.adjusted_ci_lo.fmt(3) + " ~ " + d.adjusted_ci_hi.fmt(3) +
                "）；气温系数 " + d.temp_per_c.fmt(5) + " 度/°C"
            out += "留一法：去掉任意一个点重算，速率落在 " + d.leave_one_out_min.fmt(3) +
                " ~ " + d.leave_one_out_max.fmt(3) +
                "，" + d.leave_one_out_significant + "/" + d.leave_one_out_total + " 次仍然显著"
        }
        return out
    }

    // ⚠️ **v0.69.0**：原 `caveatLine()` 已删除 —— 它的内容进了 `note()`。
    // 「口径」属补充说明，不该常驻；结论只有 `headline()` 一条。
}

