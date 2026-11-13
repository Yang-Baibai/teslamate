package com.teslamate.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.teslamate.app.AppSettings
import com.teslamate.app.BuildConfig
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class GeoPt(val lat: Double, val lng: Double)

/**
 * 路线 / 位置点显示。改为高德静态图 API（通过自建服务端中转）：
 * - 1 张图同时含底图+路线+起终点 marker
 * - 不再依赖 osmdroid（国内瓦片不可达 + 配置脆弱）
 * - 服务端 30 分钟 LRU 缓存；同一行程重复打开零外部请求
 * - 大于 10 点自动降采样（高德 marker 限制 10/请求，路线段无限制）
 *
 * URL 由 App 拼 query，服务端填 key 后调高德。
 * 注：因 Coil 走自己的 ImageLoader 不会附带 OkHttp Authorization header，
 *     我们用了一个 Trick：把 token 通过 OkHttp 客户端拦截器注入。
 *     见 Api.kt 的 okHttpAuthInterceptor 字段（无需修改请求 URL）。
 */
@Composable
fun TrackMap(
    points: List<GeoPt>,
    drawLine: Boolean = true,
    modifier: Modifier = Modifier,
    startTitle: String = "起点",
    endTitle: String = "终点",
    pointTitle: String = "车辆位置",
    settings: AppSettings? = null,
) {
    if (points.isEmpty()) return

    // 服务端中转 URL：复用 settings.serverUrl（确保走 Tailscale 隧道）
    // 兜底用 MagicDNS 主机名而非硬编码 IP —— Tailscale 的 IP 可能变，
    // 主机名是稳定的（.env 的 APP_APK_URL 也已统一改成主机名）。
    val ctx = LocalContext.current
    val baseUrl = settings?.serverUrl?.takeIf { it.isNotBlank() }
        ?: "http://your-host:8090"
    val token = settings?.token.orEmpty()

    // 路线在 App 端先降采到 ≤ 100 点（服务端限 200，留余量）。1500 个原坐标
    // 会让 URL 超过 30KB，多数 HTTP 客户端会拒绝或 Coil 解析失败。
    val sampled = if (points.size > 100) downsample(points, 100) else points
    val url = buildStaticMapUrl(baseUrl, sampled, drawLine, width = 720, height = 480)

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        val dark = isDarkTheme()
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(ctx)
                .data(url)
                .addHeader("Authorization", "Bearer $token")
                .crossfade(true)
                .build(),
            // ⚠️ v0.8.1：原来恒为「行程轨迹地图」。但车况页展开的是**车辆位置**（单点），
            // 读屏用户听到"行程轨迹"会以为图里有一条轨迹。
            // pointTitle 参数一直存在却从未被使用 —— 正是为此准备的。
            contentDescription = if (drawLine) {
                "行程轨迹地图：$startTitle 至 $endTitle"
            } else {
                pointTitle
            },
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                // ⚠️ 高德静态图是**日间白底瓦片**，深色模式下一块刺眼白板。
                // 这里叠一层暗色蒙版把它压进深色主题（不改服务端请求）。
                // alpha 0.45：白底压到中灰，路名仍可读，又不至于在黑底上"发光"。
                .then(
                    if (dark) {
                        Modifier.drawWithContent {
                            drawContent()
                            drawRect(Color.Black.copy(alpha = 0.45f))
                        }
                    } else Modifier
                ),
            loading = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "加载地图…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // 加载状态变化时播报一次（否则读屏用户不知道地图在加载）
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            },
            error = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // ⚠️ v0.8.1 安全修正：原来这里直接输出 `"地图加载失败\n$url"`。
                    // 那个 URL 里含 `points=lng,lat;…`（**车辆实时经纬度**）和
                    // 内网服务器地址 —— 一旦截图/录屏/读屏朗读，就等于泄露位置。
                    // 现在只给通用文案，不暴露任何请求细节。
                    Text(
                        "地图加载失败\n请检查网络后下拉重试",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
        )
    }
}

internal fun buildStaticMapUrl(
    base: String,
    points: List<GeoPt>,
    drawLine: Boolean,
    width: Int,
    height: Int,
): String {
    val enc = StandardCharsets.UTF_8
    val pts = if (points.size > 200) downsample(points, 200) else points
    val ptsStr = pts.joinToString(";") { "${it.lng},${it.lat}" }
    val sb = StringBuilder(base.trimEnd('/'))
    sb.append("/api/v1/app/staticmap?")
    sb.append("points=").append(URLEncoder.encode(ptsStr, enc))
    sb.append("&width=").append(width)
    sb.append("&height=").append(height)
    if (!drawLine) sb.append("&markers=0")
    return sb.toString()
}

private fun downsample(pts: List<GeoPt>, target: Int): List<GeoPt> {
    if (pts.size <= target) return pts
    val out = mutableListOf<GeoPt>()
    out += pts.first()
    val step = (pts.size - 1).toDouble() / (target - 1)
    for (i in 1 until target - 1) {
        out += pts[(i * step).toInt()]
    }
    out += pts.last()
    return out
}
