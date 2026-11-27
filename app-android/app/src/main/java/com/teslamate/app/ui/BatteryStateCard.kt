package com.teslamate.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.teslamate.app.ChargeStatus

/**
 * ⚠️ **v0.198.0：车况页的「电池状态」卡。**
 *
 * ## 为什么要有这张卡
 *
 * 用户定的方向是「电池健康 **＋ 电池状态**」⇒ 前几轮做的全是**电池健康**
 * （容量 / 衰减 / 循环 / 充电习惯）⇒ **当前充电状态**这块是空的：
 * 车况页看不出「现在在不在充 / 充到多少 / 上限多少 / 还差多久」。
 *
 * ## 这一轮的原则：**只透传，零新文案**
 *
 * ① 判定（官方枚举怎么解释）与措辞**全在服务端**（ADR #38 / ADR #41）——
 *    客户端**一个字都不自己拼**（连「当前 68%」那行也是服务端拼好的一行）；
 * ② 官方枚举**只认已核验的那几个** ⇒ 服务端不认识的当「充电状态不明」
 *    ⇒ App 也就不会自己猜（反幻觉，ADR #46）；
 * ③ **陈旧的值不当现状** —— 车睡着时服务端给 `stale` ⇒ 这里显示
 *    「**上次已知**：…」（与锁车 / 车窗那一族同一套说法，ADR #105 / #106）。
 *
 * ## 版面
 *
 * **不抢主角**：电量环（44sp）仍在最上面 ⇒ 这张卡用**次级档**（titleMedium）；
 * **只两行**：一句结论 ＋ 一行数字 ⇒ 不想多话就别多说。
 */
data class BatteryStateCardData(
    /** 状态结论（**服务端渲染好的那一句**）。 */
    val conclusion: String,
    /** 数字明细（**服务端拼好的一行**；空 = 服务端没给）。 */
    val detail: String?,
    /** 这份读数**不是当前的**（车睡着 ⇒ 断线前的最后已知值）。 */
    val stale: Boolean,
    /** **读到了**真实的当前状态吗？（false ⇒ 车端现在没有任何实时数据）。 */
    val available: Boolean,
)

/**
 * 把服务端的充电状态整理成这张卡要显示的东西（纯函数，可单测）。
 *
 * @return null ⇒ **整张卡不出现**（服务端没给结论 ⇒ 没什么可说的）。
 */
fun batteryStateCardData(cs: ChargeStatus): BatteryStateCardData? {
    val conclusion = cs.conclusion.takeIf { it.isNotBlank() } ?: return null
    return BatteryStateCardData(
        conclusion = conclusion,
        detail = cs.detail.takeIf { it.isNotBlank() },
        stale = cs.stale,
        available = cs.available,
    )
}

/**
 * ⚠️ **v0.198.0**：车况页的「电池状态」卡。
 */
@Composable
fun BatteryStateCard(cs: ChargeStatus, modifier: Modifier = Modifier) {
    val d = batteryStateCardData(cs) ?: return
    SectionCard(title = "电池状态", modifier = modifier) {
        // ⚠️ **读不到**（车端现在没有任何实时数据）⇒ 降级成**小字 ＋ 中性色**
        //   （别把「读不到」说得像结论 —— 与胎压卡的空态同一套做法）。
        // ⚠️ 陈旧 ⇒ 前缀「上次已知：」＋ **中性色**（不当现状、不当告警）。
        val unavailable = !d.available
        Text(
            text = if (d.stale) "上次已知：" + d.conclusion else d.conclusion,
            style = if (unavailable) {
                MaterialTheme.typography.bodySmall
            } else {
                MaterialTheme.typography.titleMedium
            },
            color = if (d.stale || unavailable) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        // ⚠️ 数字明细（服务端拼好的一行）—— **逐字透传**，客户端不重排。
        d.detail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
