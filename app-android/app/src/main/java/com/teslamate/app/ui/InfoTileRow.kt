package com.teslamate.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.teslamate.app.fmt

/**
 * 一行 **InfoTile** 的**列数规则**（纯逻辑，可单测）—— 大字号下改**单列**。
 *
 * ⚠️ **v0.115.0**：那些格子原来**并排**排死（`Row` + 固定 `weight(1f)`），
 * 且每格的值放不下时靠 `maxLines = 2` + `Ellipsis` 硬扛 ⇒ **读数被裁掉**。
 *
 * ## 两个洞（宽度预算钉在**最窄**的常见屏 360dp 上）
 *
 * | 行 | 格数 | 内容区 | 每格文字区 | 最宽的值 | 100% | 200% |
 * |---|---|---|---|---|---|---|
 * | 页面级（空调 / 车内温度、车外温度 / 功率） | 2 | **328dp** | **130dp** | 「31.5°C」 | 69dp ✓ | **138dp ✗** |
 * | 卡片里（充到 / 当时上限 / 充入、功率 / 已充入 / 当前电量） | 3 | **296dp** | **64dp** | 「12.3 度」 | 63dp ✓ | **126dp ✗** |
 *
 * ⇒ **三格那一行在 100% 下就只剩 1dp 余量** ⇒ **110% 就开始坏**（Android 的「大字体」档）。
 * ⇒ 「°」不是 ASCII ⇒ 按 **1.0em** 算（真实宽度约 0.4em）⇒ 串比看上去长；
 *   且「31.5」与「°C」之间**没有断行机会** ⇒ 放不下时不是折行而是**被裁**。
 *
 * ## 为什么不能「压文案」
 *
 * 把「°C」改成「℃」（1 个全角字）⇒ 200% 仍要 **116dp > 130dp**？—— 不，仍在 130dp 之内，
 * 但**三格那一行**（「12.3 度」在 64dp 里）压不掉 ⇒ 压文案救不了三格那行
 * ⇒ 正解是**换布局**（同 ADR #39 / #43）：放得下就并排，放不下就**单列**（每格独占整宽）。
 *
 * ⚠️ 文案与宽度预算在**本文件**（唯一事实源）；界面只负责摆版。
 */

/** InfoTile 的**值**字号：**`titleMedium` = 17sp**（`ui/Theme.kt`；⚠️ 别拿 M3 默认值算）。 */
const val INFO_TILE_VALUE_SP = 17f

/** InfoTile 的**标签**字号：**`labelMedium` = 13sp**（同上）。 */
const val INFO_TILE_LABEL_SP = 13f

/** InfoTile 的左右内边距合计（dp）：14dp × 2（`ui/Widgets.kt`）。 */
const val INFO_TILE_PADDING_H_DP = 28f

/**
 * **页面级**那一行的内容区宽度（dp）：360dp 窄屏 − 页面左右内边距 32dp。
 *
 * ⚠️ 与 `UsageMetrics.kt` 的 `USAGE_ROW_CONTENT_DP`（328f）**同一份**
 * —— 车况页那一行的口径早就有单测钉住。
 */
const val INFO_TILE_PAGE_CONTENT_DP = 328f

/**
 * **卡片里**那一行的内容区宽度（dp）：页面 328dp − 卡片内边距 32dp（`SectionCard` 16dp × 2）。
 */
const val INFO_TILE_CARD_CONTENT_DP = 296f

/** 页面级那两行的格间距（dp）。 */
const val INFO_TILE_GAP_PAGE_DP = 12f

/** 卡片里那两行的格间距（dp）。 */
const val INFO_TILE_GAP_CARD_DP = 10f

/**
 * 「12.3 度」—— **数与单位之间是不换行空格**（U+00A0）。
 *
 * ⚠️ 普通空格是**断行机会** ⇒ 三格并排时放不下，贪心折行会断在「12.3」后面
 * ⇒ **数与单位分离**（半个句子，ADR #42 的老毛病）。
 *
 * ⚠️ `NBSP` 是**共享常量**（住在 `SummaryMetrics.kt`；`RangeLayout.kt` 也只读引用它）。
 */
fun infoTileKwhValue(kwh: Double?): String = kwh?.let { "${it.fmt(1)}${NBSP}度" } ?: "—"

/**
 * 「11 kW」—— 同上：数与单位绑住。
 *
 * ⚠️ 参数用 [Number]（功率有的是 `Int?`、有的是 `Double?`）⇒ 两边都能传。
 * ⚠️ 读不到时**只显示「—」**（不显示「— kW」）—— 与原来的写法一致。
 */
fun infoTileKwValue(kw: Number?): String = kw?.let { "$it${NBSP}kW" } ?: "—"

/** 某个值在某字号倍率下需要的宽度（dp）。 */
fun infoTileValueNeededDp(value: String, fontScale: Float): Float =
    estimateTextWidthDpWithNbsp(value, INFO_TILE_VALUE_SP) * fontScale

/** 某个标签在某字号倍率下需要的宽度（dp）。 */
fun infoTileTitleNeededDp(title: String, fontScale: Float): Float =
    estimateTextWidthDpWithNbsp(title, INFO_TILE_LABEL_SP) * fontScale

/**
 * 这一行在某字号倍率下用几列。
 *
 * ⚠️ **1 = 单列（每格独占整宽）**，**n = 仍然并排**。
 *
 * 判定：**标签和值都要放得下**（标签也要 —— 「当时上限」4 个字在 200% 下要 104dp，
 * 比 64dp 的每格宽 ⇒ 标签自己就折行了）。
 *
 * ⚠️ 这是**宽度驱动**的，不许写死（写死 ⇒ 大字号下又裁读数，ADR #39 / #43 的老教训）。
 *
 * @param titles 每格的标签（从左到右）
 * @param values 每格的值（从左到右）
 * @param fontScale 当前字号倍率
 * @param contentDp 这一行的内容区宽度（dp）
 * @param gapDp 格与格之间的间距（dp）
 */
fun infoTileColumns(
    titles: List<String>,
    values: List<String>,
    fontScale: Float,
    contentDp: Float,
    gapDp: Float,
): Int {
    val n = minOf(titles.size, values.size)
    if (n <= 1) return 1
    val textDp = (contentDp - (n - 1) * gapDp) / n - INFO_TILE_PADDING_H_DP
    val widest = maxOf(
        titles.maxOf { infoTileTitleNeededDp(it, fontScale) },
        values.maxOf { infoTileValueNeededDp(it, fontScale) },
    )
    return if (widest <= textDp) n else 1
}

/**
 * 一行 InfoTile：**放得下就并排，放不下就单列**（每格独占整宽）。
 *
 * ⚠️ 宽度是**运行时量出来的**（`BoxWithConstraints`）⇒ 每台机器都按自己实际的宽度判断；
 * 单测钉的是**最窄**的常见屏（360dp）那个预算。
 *
 * @param tiles 每格的「标签 + 值」
 */
@Composable
fun InfoTileRow(
    tiles: List<Pair<String, String>>,
    gapDp: Dp = 12.dp,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val columns = infoTileColumns(
            tiles.map { it.first },
            tiles.map { it.second },
            LocalDensity.current.fontScale,
            maxWidth.value,
            gapDp.value,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (columns <= 1) {
                // 单列：每格独占整宽 ⇒ 值**一定**放得下（预算见本文件的说明）。
                tiles.forEach { (title, value) ->
                    InfoTile(title, value, Modifier.fillMaxWidth())
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gapDp),
                ) {
                    tiles.forEach { (title, value) ->
                        InfoTile(title, value, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
