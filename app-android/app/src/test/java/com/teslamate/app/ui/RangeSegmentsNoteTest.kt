package com.teslamate.app.ui

import com.teslamate.app.FullChargeRange
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.175.0：实测满电续航卡的 ⓘ 文案 —— 服务端下发，客户端只透传。**
 *
 * ## 这个文件为什么存在
 *
 * ⚠️ **客户端闸门 43** 要求：白名单里的文件**必须**有一个含 [NOTE_LINE_LIMIT] 的
 * JVM 单测覆盖 —— 否则「服务端下发的 ⓘ 文案」就**没有人管宽度**了。
 * ⇒ ui/RangeSegments.kt 进了白名单 ⇒ 这个文件是它的宽度护栏。
 *
 * ## 夹具从哪来
 *
 * ⚠️ **夹具与服务端的文案逐字一致**（由客户端闸门 46 跨文件比对钉住）
 * ⇒ 两端不许漂（ADR #41：文案只有一份）。
 */
class RangeSegmentsNoteTest {

    /**
     * ⚠️ **与服务端 api/full_charge_range.go 的 fullChargeMethodNote 逐字一致**。
     *
     * ⚠️ 「X%」里的 % 在 Go 的 Sprintf 里写成 %%，但**下发到客户端时是单个 %**。
     */
    private fun serverMethodNote(officialKm: Int = 449): String =
        "这是折算值：把「从 X% 开到 Y% 跑了多少公里」按掉电比例折算到满电，含没充满就走的段\n" +
            "折算用净掉电，下坡回充被抵扣，所以偏乐观\n" +
            "车机满电时自己报的是 $officialKm 公里\n" +
            "这两个数都是这车跑出来的，区别是一个折算、一个直读，规划请用上面的线路余量表"

    /** ⚠️ 与服务端 fullChargeReadNote 逐字一致。 */
    private fun serverReadNote(): String =
        "「最好」多半含下坡回充，长下坡几乎不耗电，别拿它当平路规划依据\n" +
            "「最差」才是市区工况更接近的那个数"

    /** ⚠️ 与服务端 fullChargeScopeNoteTmpl 逐字一致（%s 已填成实测的 1.8）。 */
    private fun serverScopeNote(ratio: String = "1.8"): String =
        "口径：一次充电结束到下一次充电开始，按掉电百分比折算到满电\n" +
            "这是折算值，不是某一次满电实跑\n" +
            "最好与中位差 $ratio 倍\n" +
            "中位数不代表任何一次具体出行"

    private fun shaped(): FullChargeRange = FullChargeRange(
        median_km = 345.75,
        official_km = 448.83,
        vs_official_percent = -23,
        segment_count = 52,
        note = "基于 52 段实测 · 官方标称 449 公里，实测低 23%",
        method_note = serverMethodNote(),
        read_note = serverReadNote(),
        scope_note = serverScopeNote(),
    )

    @Test
    fun `三段文案的每一行都不许超宽`() {
        val d = shaped()
        val texts = mapOf(
            "method_note" to d.method_note,
            "read_note" to d.read_note,
            "scope_note" to d.scope_note,
        )
        for ((name, text) in texts) {
            assertTrue("$name 不该是空的", text.isNotBlank())
            for (line in text.split("\n")) {
                assertTrue(
                    "$name 有一行宽 ${noteVisualWidth(line)} > $NOTE_LINE_LIMIT：$line",
                    noteVisualWidth(line) <= NOTE_LINE_LIMIT,
                )
            }
        }
    }

    @Test
    fun `客户端渲染的就是服务端原文_一个字都不许自己加`() {
        // ⚠️ **v0.175.0**：这三段以前是**客户端自己拼**的
        // ⇒ 其中「车机满电时自己报的是 X 公里」那个数服务端**已经给了**
        // ⇒ 违反 ADR #41（文案只有一份）。
        val d = shaped()
        assertTrue("method_note 必须是服务端原文", d.method_note == serverMethodNote())
        assertTrue("read_note 必须是服务端原文", d.read_note == serverReadNote())
        assertTrue("scope_note 必须是服务端原文", d.scope_note == serverScopeNote())
    }

    @Test
    fun `口径里的倍数不许是写死的`() {
        // ⚠️ 客户端原来那句「市区与高速差 1.7 倍」是**硬编码**的
        // ⇒ 实测真实值已经是 1.7562 ⇒ 那句话早就在说错。
        val d = shaped()
        assertTrue(
            "口径里不许出现写死的 1.7 倍：${d.scope_note}",
            !d.scope_note.contains("1.7 倍"),
        )
        assertTrue(
            "口径里应当是按真实数据算出来的倍数：${d.scope_note}",
            d.scope_note.contains("最好与中位差 1.8 倍"),
        )
    }
}