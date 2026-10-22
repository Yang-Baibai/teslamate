package com.teslamate.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 本地通知发送。
 *
 * 为什么是「本地通知」而不是 FCM 推送：
 * 这个 App 是单用户自用的私有部署，服务器在你自己家里。走 FCM 意味着要额外申请
 * Firebase 项目、塞 google-services.json、引入一个云端依赖——而服务器本来就在
 * 手机能访问的局域网/Tailscale 上，手机自己轮询自己判断状态就够了。
 *
 * 代价：手机必须能访问服务器（家里 WiFi 或 Tailscale 通），且系统可能在后台
 * 延迟执行轮询任务（WorkManager 在国产 ROM 上尤其明显，见 Settings 里的
 * 「忽略电池优化」说明）。
 */
object Notifier {

    /** 常规事件：充电开始/完成 */
    const val CHANNEL_EVENT = "car_events"

    /** 异常告警：胎压异常、数据过期 */
    const val CHANNEL_ALERT = "car_alerts"

    private const val ID_CHARGE_START = 1001
    private const val ID_CHARGE_END = 1002
    private const val ID_TPMS = 1003
    private const val ID_STALE = 1004

    /** Android 13+ 需要运行时授权；未授权时 notify() 会被系统静默忽略 */
    fun canNotify(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENT,
                "充电与行程事件",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "充电开始、充电完成" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                "车况异常告警",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "胎压异常、数据长时间未更新" }
        )
    }

    /** 点通知跳回 App（打开「车况」页） */
    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notify(
        context: Context,
        id: Int,
        channel: String,
        title: String,
        text: String,
    ) {
        if (!canNotify(context)) return
        val n: Notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // 权限在通知发出瞬间被撤销，忽略即可
        }
    }

    fun chargeStarted(context: Context, carName: String, soc: Int?) {
        notify(
            context, ID_CHARGE_START, CHANNEL_EVENT,
            "$carName 开始充电",
            buildString {
                append("当前电量 ")
                append(soc?.let { "$it%" } ?: "未知")
                append("，充电完成会通知你")
            },
        )
    }

    fun chargeFinished(context: Context, carName: String, soc: Int?, addedKwh: Double?) {
        notify(
            context, ID_CHARGE_END, CHANNEL_EVENT,
            "$carName 充电完成",
            buildString {
                append("当前电量 ")
                append(soc?.let { "$it%" } ?: "未知")
                addedKwh?.let { append("，充入 ${"%.1f".format(it)} kWh") }
            },
        )
    }

    fun tpmsAlert(context: Context, carName: String, reading: TpmsReading) {
        notify(
            context, ID_TPMS, CHANNEL_ALERT,
            "$carName 胎压异常 · 建议检查",
            buildString {
                append(reading.alertSummary())
                append(" 轮胎气压异常。")
                append(reading.detail())
                append(" bar（正常 ")
                append(TPMS_MIN_BAR)
                append("–")
                append(TPMS_MAX_BAR)
                append(" bar）")
            },
        )
    }

    fun staleData(context: Context, carName: String, hours: Long) {
        notify(
            context, ID_STALE, CHANNEL_ALERT,
            "$carName 数据已 ${hours} 小时未更新",
            "车辆长时间没有上报数据。可能是车已离线（正常），也可能是采集链路或网络异常，请留意。",
        )
    }
}
