package com.teslamate.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.156.0（用户反馈 #153）：车辆控制是**独立的一级入口**。**
 *
 * ## 为什么要有这条
 *
 * 用户原话（2026-11-13）：
 * 「能否将车辆控制的相关部分独立出来，类似特斯拉APP的首页。
 * 现在集成在车况页面中，会显得车况页面特别啰嗦冗长。」
 *
 * ⇒ 车控从「车况页里的一张卡」搬成了**自己的 Tab**。
 *
 * ## 这条钉的是什么
 *
 * 钉的是**决定**，不是实现：
 * - 「车控」必须**在**一级入口里（不许又被塞回车况页）；
 * - 它紧跟在「车况」后面（底栏直达 ⇒ 找它不用滚车况页）；
 * - **第一个**入口仍然是「车况」（启动落点不变 ⇒ 老的肌肉记忆不失效）。
 *
 * ⚠️ 为什么不钉 Tab 的**总数**：以后再加/减别的入口不该让这条红掉；
 * 只有「车控在不在、挨不挨着车况」才是这次要保护的东西。
 */
class AppTabTest {

    private val labels: List<String> get() = Tab.entries.map { it.label }

    @Test
    fun `车控是独立的一级入口_不是车况页里的区块`() {
        val labels = labels
        assertTrue("一级入口里没有「车控」：$labels", labels.contains("车控"))
        assertTrue("一级入口里没有「车况」：$labels", labels.contains("车况"))
    }

    @Test
    fun `车控紧跟在车况之后_底栏直达不绕路`() {
        val labels = labels
        val status = labels.indexOf("车况")
        val control = labels.indexOf("车控")
        assertTrue("「车况」不在一级入口里：$labels", status >= 0)
        assertTrue("「车控」不在一级入口里：$labels", control >= 0)
        assertEquals(
            "「车控」没紧跟在「车况」后面：$labels",
            status + 1,
            control,
        )
    }

    @Test
    fun `第一个一级入口仍然是车况_启动落点不变`() {
        assertEquals("车况", Tab.entries.first().label)
    }

    /**
     * ⚠️ **v0.157.0**：设置**不在底栏** —— 它在顶栏的齿轮里。
     *
     * 用户原话（看了 147 之后）：
     * 「下面5个栏目感觉有点拥挤，把设置做成图标放在右上角呢？和刷新并排。」
     *
     * ⇒ 底栏只剩**内容页**；设置是**另一个维度**（改配置），
     *   混在内容页里本来就是混层级。
     *
     * ⚠️ 为什么**不钉底栏的项数**：将来再加一个内容页是正常演进，
     * 那时这条不该报红 ⇒ 只钉「设置不在底栏」这个**决定**。
     */
    @Test
    fun `设置不在底栏_它在顶栏的齿轮里`() {
        assertFalse("「设置」还在底栏里", Tab.SETTINGS.inBottomBar)
        val bar = Tab.entries.filter { it.inBottomBar }.map { it.label }
        assertFalse("底栏里还有「设置」：$bar", bar.contains("设置"))
    }
}
