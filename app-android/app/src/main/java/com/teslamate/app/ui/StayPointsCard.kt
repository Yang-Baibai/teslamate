package com.teslamate.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teslamate.app.StayPoint
import com.teslamate.app.StayPoints
import com.teslamate.app.firstSegment
import com.teslamate.app.fmtDate
import kotlin.math.roundToInt

/**
 * ⚠️ **v0.28.0（第 17 轮官方 App 对标评审 A-3）：常驻点卡片。**
 *
 * ## 官方 App 给不了的
 *
 * 官方 Tesla App 只告诉你「**车现在在哪**」；
 * 这个告诉你「它**平时**停在哪、停多久」——
 * 家、公司、常去的商场，全在库里。
 *
 * ## ⚠️ 语义必须是「推断停留」
 *
 * 车停下来之后 TeslaMate 会**停止记录位置**（`states` 里 asleep 44 / offline 123，
 * 空闲位置点只有 8846 个、采样间隔约 5 分钟）。
 * 所以停留时长只能从「上一趟结束 → 下一趟开始」的**间隙**推断 ——
 * 那不是实时停车时长，界面必须这么说。
 *
 * ## 排序按「停过几次」，不按「累计多少小时」
 *
 * 复审的发现很关键：按累计小时排，**一次 5 天长假**（116 小时 / 1 次）
 * 会排在**真家充点**（26 小时 / 4 次）前面 ——
 * 而「停过几次」才是「常驻」的真正证据。
 * 所以按次数排，副行再给累计时长与**最长一次**（把长假那个离群值显式露出来）。
 */
@Composable
fun StayPointsCard(
    points: StayPoints?,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "常驻点", modifier = modifier) {
        val list = points?.points.orEmpty()
        if (list.isEmpty()) {
            Text(
                if (loading) "正在算…" else "还没有足够的停留记录",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        val accents = LocalAccentColors.current
        // ⚠️ 按**次数**倒序（常驻的证据），累计时长只作副行。
        val sorted = list.sortedByDescending { it.visits }
        val top = sorted.first()

        Text(
            "常驻 ${sorted.size} 处 · 停得最多的一处停了 ${top.visits} 次",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))

        sorted.take(6).forEachIndexed { idx, p ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        p.shortName(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (idx == 0) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        buildString {
                            append("停过 ")
                            append(p.visits)
                            append(" 次 · 累计 ")
                            append(p.total_hours.roundToInt())
                            append(" 小时")
                            // ⚠️ 复审：**最长一次**要显式露出来 ——
                            // 「一次停了 248 小时」是长假，不是「常驻」。
                            if (p.max_hours >= 24) {
                                append("（最长一次 ")
                                append(p.max_hours.roundToInt())
                                append(" 小时）")
                            }
                            p.last_seen?.let { append(" · 上次 ${it.fmtDate()}") }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 相对占用条：按**停过几次**占最多的那个点的比例。
                // ⚠️ **不是**状态色 —— 只是个量的大小。
                val maxVisits = top.visits.takeIf { it > 0 } ?: 1
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .width(56.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(accents.chartTrack)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((p.visits.toFloat() / maxVisits).coerceIn(0.04f, 1f))
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(accents.energyBlue)
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "停留时长由**相邻行程的时间间隙**推断 —— 车睡着后 TeslaMate 不再记位置，" +
                "所以这不是实时停车时长。App 也分不出哪个是「家」、哪个是「公司」，" +
                "这里只按**停过几次**排。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 地点的短名（地址第一段，如「新湖路」）；没有就用完整名。 */
private fun StayPoint.shortName(): String =
    name.firstSegment()?.takeIf { it.isNotBlank() } ?: name.ifBlank { "未知地点" }
