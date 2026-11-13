package com.teslamate.app.ui

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.teslamate.app.AppLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App 内一键更新弹窗。
 * - 弹窗：Material 3 AlertDialog，"立即更新"/"稍后" 两按钮
 * - 立即更新：调 [Context.downloadAndInstall] → 触发 DownloadManager
 * - 稍后：调用方把 updateInfo 置 null，本会话内不再弹
 */
@Composable
fun UpdateDialog(
    info: AppLatest,
    onDismiss: () -> Unit,
    onUpdate: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { ScreenTitle("发现新版本 v${info.version_name}") },
        text = {
            if (info.mandatory) {
                Text(
                    "此版本为强制更新，请升级后继续使用。\n\n更新内容：\n${info.release_notes ?: "（无说明）"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    "更新内容：\n${info.release_notes ?: "（无说明）"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onUpdate(info.apk_url) },
            ) { Text("立即更新", color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            if (!info.mandatory) {
                TextButton(onClick = onDismiss) {
                    Text("稍后", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}

/**
 * 触发 APK 下载 + 安装。
 * - 用系统 DownloadManager（不写自定义服务，进度由系统 UI 展示）
 * - 完成后 BroadcastReceiver 监听 ACTION_DOWNLOAD_COMPLETE，弹出系统安装器
 * - MIUI 兜底：未授权安装包时引导用户去系统设置
 * 返回 true 表示成功开始下载；false 表示当场失败
 */
fun Context.downloadAndInstall(apkUrl: String): Boolean {
    if (apkUrl.isBlank()) {
        Toast.makeText(this, "更新地址无效", Toast.LENGTH_SHORT).show()
        return false
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
        // 引导用户去系统设置打开"允许安装未知应用"
        Toast.makeText(this, "请先到系统设置中允许本应用安装更新", Toast.LENGTH_LONG).show()
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) { /* 设备没这个入口就放过 */ }
        return false
    }

    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val request = DownloadManager.Request(Uri.parse(apkUrl))
        .setMimeType("application/vnd.android.package-archive")
        .setTitle("车况 v${com.teslamate.app.BuildConfig.VERSION_NAME} 准备就绪")
        .setDescription("下载完成后点击安装")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)
    // 公共下载目录，下载完系统会显示在通知栏
    @Suppress("DEPRECATION")
    request.setDestinationInExternalPublicDir(
        Environment.DIRECTORY_DOWNLOADS,
        "teslamate-app.apk",
    )

    val downloadId = try {
        dm.enqueue(request)
    } catch (e: Exception) {
        Toast.makeText(this, "下载启动失败：${e.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
        return false
    }

    // 注册一次性 receiver，下载完成后跳系统安装器
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id != downloadId) return
            ctx ?: return
            try {
                unregisterReceiver(this)
            } catch (_: Exception) { /* 二次 unregister 忽略 */ }
            val apkUri = Uri.fromFile(
                java.io.File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "teslamate-app.apk",
                ),
            )
            val install = Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                ctx.startActivity(install)
            } catch (e: Exception) {
                Toast.makeText(ctx, "无法启动安装器：${e.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
            }
        }
    }
    ContextCompat.registerReceiver(
        this,
        receiver,
        IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
        ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    return true
}
