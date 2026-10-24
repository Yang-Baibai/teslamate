package com.teslamate.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable

/**
 * 触感反馈封装。
 *
 * ⚠️ 为什么不用 `HapticFeedbackType`：Compose UI 1.7.6 的 `HapticFeedbackType` 只有
 * `LongPress` 和 `TextHandleMove` 两个常量（Confirm/Reject/SegmentTick 是 1.8.0 才加的）。
 * 所以这里直��调平台常量（路线 A），并用 `VibrationEffect` 精确控制振幅（路线 C）。
 *
 * 设计原则（不要每个交互都震）：
 *   • 开关/分段这类高频微交互 → 极轻 tick（amplitude 25~45）
 *   • 「状态改变」（保存成功、下载完成）→ 确认脉冲（amplitude 60~150）
 *   • 告警（胎压异常）→ 硬质短促双击
 *
 * 尊重系统设置：用户在「辅助功能 → 触摸反馈」关掉时，Android 自身会屏蔽部分震动；
 * 自定义 `VibrationEffect` **不受**该开关约束，所以要自己检查。
 */
object Haptics {

    private fun enabled(context: Context): Boolean {
        return try {
            // ⚠️ 常量在 Settings.System 而不是 Settings.Global
            Settings.System.getFloat(
                context.contentResolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED,
                1f,
            ) > 0f
        } catch (_: Exception) {
            true
        }
    }

    private fun vibrate(context: Context, amplitude: Int, durationMs: Long) {
        if (!enabled(context)) return
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(
                VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255))
            )
        } catch (_: Exception) {
            // 设备无振动马达或权限被拒，忽略
        }
    }

    /** 轻点：列表项按下、展开地图等高频微交互 */
    fun tick(context: Context) = vibrate(context, 30, 8)

    /** 选择：Tab 切换、分段控件切换 */
    fun selection(context: Context) = vibrate(context, 45, 10)

    /** 确认：保存成功、下载完成 */
    fun confirm(context: Context) {
        vibrate(context, 60, 12)
        Thread {
            Thread.sleep(45)
            vibrate(context, 150, 20)
        }.apply { isDaemon = true }.start()
    }

    /** 拒绝：连接测试失败 */
    fun reject(context: Context) = vibrate(context, 160, 30)

    /** 告警：胎压异常等安全事件（硬质短促双击） */
    fun alert(context: Context) {
        vibrate(context, 80, 18)
        Thread {
            Thread.sleep(80)
            vibrate(context, 80, 18)
        }.apply { isDaemon = true }.start()
    }

    /** 平台常量版本（不需要 VIBRATE 权限，受系统触感开关控制） */
    fun platformClick(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
}

/** 便捷：在 Composable 里用 */
@Composable
fun rememberHaptics(): Haptics = Haptics
