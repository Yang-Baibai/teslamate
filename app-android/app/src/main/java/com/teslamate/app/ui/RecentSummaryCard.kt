package com.teslamate.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teslamate.app.AppSettings
import com.teslamate.app.Stats
import com.teslamate.app.StatsBucket
import com.teslamate.app.fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 顶部 30 天汇总条的加载态（骨架条） */
@Composable
private fun SummarySkeleton() {
    SectionCard {
        SkeletonBox(
            Modifier
                .fillMaxWidth()
                .height(20.dp),
        )
        SkeletonBox(
            Modifier
                .fillMaxWidth()
                .height(20.dp),
        )
    }
}

/** 顶部汇总条里的一格 */
@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 列表页顶部的「最近 30 天」统计框。
 *
 * 口径：调用 /cars/{id}/stats?period=month（服务端聚合最近 30 个自然日，
 * 按 Asia/Shanghai 本地日界切桶），取其 summary。
 * 这与「统计」页的"月"是同一份数据，保证两处数字一致。
 *
 * fallback：stats 还没加载出来/失败时，先显示调用方给的旧汇总，不让顶部空着。
 */
@Composable
fun RecentSummaryCard(
    settings: AppSettings,
    fallback: @Composable () -> Unit,
) {
    var stats by remember { mutableStateOf<Stats?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(settings.serverUrl, settings.carId) {
        val api = settings.api() ?: return@LaunchedEffect
        stats = runCatching {
            withContext(Dispatchers.IO) { api.stats(settings.carId, "month") }
        }.getOrElse {
            failed = true
            null
        }
    }

    val s = stats?.summary
    when {
        s == null && !failed -> SummarySkeleton()
        s == null -> fallback()
        else -> SummaryContent(s)
    }
}

@Composable
private fun SummaryContent(s: StatsBucket) {
    SectionCard("最近 30 天") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Stat("里程", "${s.km.fmt(0)} km", Modifier.weight(1f))
            Stat("充入", "${s.charge_kwh.fmt(0)} kWh", Modifier.weight(1f))
        }
        Spacer(Modifier.padding(top = 10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Stat("充电次数", "${s.charge_count} 次", Modifier.weight(1f))
            Stat("行程次数", "${s.drive_count} 次", Modifier.weight(1f))
        }
        if ((s.wh_per_km ?: 0.0) > 0.1) {
            Spacer(Modifier.padding(top = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "平均能耗 ${s.wh_per_km?.fmt(0)} Wh/km",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                val dcPct =
                    if (s.charge_kwh > 0) (s.dc_kwh / s.charge_kwh * 100).toInt() else 0
                Text(
                    "快充占比 $dcPct%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.padding(top = 6.dp))
            // 快充占比条：一眼看出这 30 天的补能结构
            LinearProgressIndicator(
                progress = { if (s.charge_kwh > 0) (s.dc_kwh / s.charge_kwh).toFloat() else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
}
