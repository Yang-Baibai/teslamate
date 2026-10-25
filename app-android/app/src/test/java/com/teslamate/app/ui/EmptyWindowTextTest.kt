package com.teslamate.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⚠️ **v0.48.0（第 28 轮通勤车主评审 P1-1）**：用量页「今天」段的**零行驶日空态**。
 *
 * ## 为什么要有这条测试
 *
 * 真实数据：87 天里 **42 天零行驶（48.3%）** ——
 * 「今天」段**有一半概率**是空的。
 * 空的形态原来是「0.0 公里 · 今天」，那看起来像 **App 出故障**，而不是「今天没开车」。
 *
 * ## 盯住什么
 *
 * ① 「今天」用**时点**的说法（「今天还没开车」）；
 * ② 更长的窗口用**区间**的说法（「7 天没有行程记录」）——
 *    混用会把「这个区间没开车」说成「现在还没开车」；
 * ③ 文案里**不能**出现「0.0」这种看起来像故障的数字。
 */
class EmptyWindowTextTest {

    @Test
    fun `今天用时点的说法`() {
        assertEquals("今天还没开车", emptyWindowText("今天"))
    }

    @Test
    fun `更长的窗口用区间的说法`() {
        assertEquals("7 天没有行程记录", emptyWindowText("7 天"))
        assertEquals("本月没有行程记录", emptyWindowText("本月"))
        assertEquals("1 年没有行程记录", emptyWindowText("1 年"))
    }

    @Test
    fun `空态文案里不能出现像故障的数字`() {
        for (label in listOf("今天", "7 天", "本月", "1 年")) {
            val t = emptyWindowText(label)
            assertEquals("空态不能出现「0.0」: $t", false, t.contains("0.0"))
            assertEquals("空态不能出现「公里」: $t", false, t.contains("公里"))
        }
    }

    @Test
    fun `文案里不能出现没有格式化的模板变量`() {
        // ⚠️ Kotlin 的字符串模板陷阱：`"$periodLabel没有行程记录"` 会被解析成
        // 变量名 `periodLabel没有行程记录`（中文字符是合法标识符字符）⇒ 编译失败。
        // 这里断言最终文案里**没有**残留的 `${` 或 `$`。
        val t = emptyWindowText("本月")
        assertEquals("不该有残留的模板符号: $t", false, t.contains("\$"))
    }
}
