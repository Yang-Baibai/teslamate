package com.teslamate.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * ⚠️ **v0.65.0（第 32 轮「减啰嗦」）：说明角标。**
 *
 * ## 为什么有这个组件
 *
 * 车主原话：「界面美观性上没什么问题，但**太啰嗦了**，细节上的文字直接展示出来的太多了，
 * 比如驾驶画像的平顺分的解释，应该隐藏起来，需要我点击一个小角标才会展示。
 * 类似于每个指标的注释那种感觉。」
 *
 * 排查结果：App 里有 **182 处**成段的解释文字（≥22 字，24 个文件），
 * 合计约 **4600 字**。它们大多不是数据，而是**算法口径说明** ——
 * 「这个分怎么算的」「这个数为什么是这个」「这个假设不成立」，
 * 对想深究的人很有价值，但**默认铺开会把界面压成一块字**。
 *
 * ## 折叠范围的约定（车主第 32 轮拍板）
 *
 * | 类型 | 例子 | 处理 |
 * |---|---|---|
 * | **指标注释 / 口径说明** | 「刹车片磨损的直接来源；与下坡几乎无关」 | **折到 ⓘ 后面** |
 * | **操作指引** | 「家里 WiFi：地址填 http://<电脑局域网IP>:8090」 | **折到 ⓘ 后面** |
 * | **告警 / 异常** | 「车已经 30 小时没上报数据了」 | **保持直接可见** |
 * | **错误 / 空态** | 「暂无足够数据（需要 ≥90% 的完整充电记录）」 | **保持直接可见** |
 *
 * 也就是说：**藏起来的是「补充说明」，不是「用户需要马上知道的事」。**
 *
 * ## 交互
 *
 * - 收起态：ⓘ 徽标（16dp 视觉，44dp+ 触控目标）；
 * - 点一下 → 在**原地**展开说明，再点收起；
 * - 动效复用 `Motion.ExpandSize`（高度）+ `Motion.FadeIn`（淡入），
 *   与既有 `CollapsibleSection` 同一套 token ⇒ 全 App 折叠手感一致。
 *
 * ## 无障碍
 *
 * - 徽标是**图标按钮** ⇒ 必须有 `contentDescription`（WCAG 1.1.1），
 *   念作「展开<说明名>」/「收起<说明名>」；
 * - `role = Role.Button` ⇒ TalkBack 播报为按钮而不是「图片」；
 * - 展开的正文是普通 `Text` ⇒ 读屏按正常文本顺序读，不合并进按钮。
 */

/**
 * ⓘ 说明徽标（可点）。
 *
 * 展开状态由调用方持有 —— 因为**徽标的位置**（指标行右侧）和
 * **正文的位置**（整行下面）通常不在同一行，
 * 状态提到调用方才能让正文落在正确的地方。
 */
@Composable
fun InfoBadge(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    /** 读屏时念的说明名，例如「急刹车的说明」 */
    a11yLabel: String = "说明",
) {
    Icon(
        imageVector = Icons.Filled.Info,
        contentDescription = if (expanded) "收起$a11yLabel" else "展开$a11yLabel",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .size(16.dp)
            // ⚠️ 触控目标：图标本身只有 16dp，低于 44dp 下限 ⇒ 撑到 48dp 的可点区域
            //（material3 的 minimumInteractiveComponentSize），**视觉不变**。
            .minimumInteractiveComponentSize()
            .pressable(onClick = onToggle, scaleDown = 0.88f)
            .semantics { role = Role.Button },
    )
}

/**
 * 说明正文（带展开/收起动画）。
 *
 * 放在**指标行下面** —— 与 `CollapsibleSection` 同一套动效 token。
 */
@Composable
fun NoteBody(
    text: String,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.FadeIn) + expandVertically(Motion.ExpandSize),
        exit = fadeOut(tween(Motion.ExpandFadeOut)) + shrinkVertically(Motion.ExpandSize),
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/**
 * 自包含版说明块：ⓘ 徽标（+ 可选短标签）+ 正文，竖排。
 *
 * 用于**没有指标行可挂**的独立说明段（设置页的操作指引、统计口径说明等）。
 *
 * @param label 收起态显示的短标签。给 null 就只剩一个 ⓘ 徽标
 *              （适合挂在已经有明确标题的地方）。
 */
@Composable
fun InfoNote(
    text: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    a11yLabel: String = "说明",
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InfoBadge(
                expanded = expanded,
                onToggle = { expanded = !expanded },
                a11yLabel = a11yLabel,
            )
            if (label != null) {
                Spacer(Modifier.width(2.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.pressable(
                        onClick = { expanded = !expanded },
                        scaleDown = 0.96f,
                    ),
                )
            }
        }
        NoteBody(text = text, visible = expanded)
    }
}

/**
 * 一段文字 + 紧跟其后的 ⓘ 徽标，点开在**下面**展开说明。
 *
 * 用于「主角数字下面的副标题」「图表上方的读图提示」这类
 * **本身是数据/提示、但附带一段口径说明**的地方。
 */
@Composable
fun TextWithNote(
    text: String,
    note: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelMedium,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    a11yLabel: String = "说明",
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                style = style,
                color = color,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            InfoBadge(
                expanded = expanded,
                onToggle = { expanded = !expanded },
                a11yLabel = a11yLabel,
            )
        }
        NoteBody(text = note, visible = expanded)
    }
}

/**
 * 指标行 + 说明（**指标注释的标准形态**）。
 *
 * 标签右侧一个 ⓘ 徽标，点开在**整行下面**就地展开说明。
 *
 * @param note 说明正文；null = 不显示徽标（保持旧的紧凑行）。
 */
@Composable
fun KeyValueWithNote(
    label: String,
    value: String,
    note: String,
    a11yLabel: String = "说明",
    modifier: Modifier = Modifier,
    valueStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            InfoBadge(
                expanded = expanded,
                onToggle = { expanded = !expanded },
                a11yLabel = a11yLabel,
            )
            Spacer(Modifier.weight(1f))
            Text(
                value,
                style = valueStyle,
                color = valueColor,
                maxLines = 3,
            )
        }
        NoteBody(text = note, visible = expanded)
    }
}
