package com.teslamate.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.teslamate.app.AppSettings

/**
 * 「记录」页 —— 充电与行程合并，用段控件切换。
 *
 * ## 为什么合并
 *
 * 充电与行程**本来就是同一种东西**：按月分组的历史事件列表 + 点进去看详情。
 * 之前它们是底部两个平级 Tab，各自还顶着**同一张**「近 30 天」汇总卡 ——
 * 同一份 `stats(month)` 被抓两次、放在两个语义不同的地方（连措辞都不同：
 * 「最近 30 天」/「月 汇总」）。
 *
 * 现在：段控件表达从属关系（记录 → 充电 / 行程），汇总数据**只留在「用量」页**
 * （那里它才真正说得通：里程、能耗、充入量是**量**，不是**事件**）。
 *
 * ## 结构
 * ```
 * TopAppBar「记录」
 * SegmentedTabs（充电 | 行程）
 * └ 子列表（ChargesScreen / DrivesScreen，showHeader=false —— 不再有各自的顶栏和汇总卡）
 * ```
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsScreen(settings: AppSettings, modifier: Modifier) {
    var segment by remember { mutableIntStateOf(0) }

    Column(modifier) {
        TopAppBar(
            title = { ScreenTitle("记录") },
        )
        SegmentedTabs(
            labels = listOf("充电", "行程"),
            selectedIndex = segment,
            onSelect = { segment = it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        when (segment) {
            0 -> ChargesScreen(settings, Modifier.fillMaxSize(), showHeader = false)
            else -> DrivesScreen(settings, Modifier.fillMaxSize(), showHeader = false)
        }
    }
}
