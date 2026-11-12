package com.teslamate.app

import java.util.Locale

/**
 * 温度口径文案 —— **服务端有一份逐字相同的**（api/app_copy.go）。
 *
 * ## 为什么客户端要有一份（v0.146.0）
 *
 * 服务端挂掉时，App 要**自己**把口径说出来（本地兜底那条路）。
 *
 * ⚠️ **v0.146.0 修掉的缺陷**：本地兜底原来在「车外温度读不到」时**返回 null**
 * ⇒ 界面**一句口径提示都没有** ⇒ 用户以为那个续航数是按气温修正过的
 * ⇒ **假安全**（服务端那条路一直会说「未按气温修正 · 可能偏乐观」）。
 *
 * ## 为什么是「逐字副本」而不是各写各的
 *
 * ADR #41：界面文案以服务端为唯一来源（否则 App 与飞书各说各话）。
 * 这里允许**一份逐字副本** —— 因为它是**兜底**，服务端活着时根本不用它。
 * ⇒ 两份不许漂 ⇒ **客户端闸门 35 跨文件逐字比对**（app_copy.go ⇄ 本文件）。
 */
internal object TempCaveatCopy {

    /** 车外温度**读不到**（与服务端 staleOutsideTempCaveat 逐字一致）。 */
    const val OUTSIDE_TEMP_UNAVAILABLE = "车外温度读不到 ⇒ 未按气温修正 · 可能偏乐观"

    /** 低温（≤10°C）样本不足 ⇒ 这个数不可信（%d = 样本条数）。 */
    const val LOW_TEMP_UNTRUSTWORTHY = "低温未修正：≤10°C 只有 %d 条样本，这个数不可信"

    /** 低温区间（10~22°C）不做低温修正（%d = 样本条数）。 */
    const val NO_LOW_TEMP_CORRECTION = "未做低温修正（≤10°C 样本 %d 条）"

    /** 按条数渲染（用 ROOT ⇒ 数字不受系统语言影响，永远是阿拉伯数字）。 */
    fun lowTempUntrustworthy(samples: Int): String =
        String.format(Locale.ROOT, LOW_TEMP_UNTRUSTWORTHY, samples)

    /** 按条数渲染。 */
    fun noLowTempCorrection(samples: Int): String =
        String.format(Locale.ROOT, NO_LOW_TEMP_CORRECTION, samples)
}
