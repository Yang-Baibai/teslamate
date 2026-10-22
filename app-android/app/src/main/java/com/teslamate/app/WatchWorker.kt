package com.teslamate.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * 后台车况监听：定时拉一次 /status，状态跳变时发本地通知。
 *
 * 触发场景：
 *   • 开始充电 → 通知「开始充电」
 *   • 停止充电 → 通知「充电完成，当前 XX%」
 *   • 胎压越界或单胎离群 → 通知「胎压异常」（同一异常只发一次，恢复正常后才重置）
 *   • 数据超过 N 小时未更新 → 通知「数据已 N 小时未更新」
 *
 * 为什么用 WorkManager 而不是常驻前台服务：
 *   • 前台服务要常驻一条通知、耗电明显、且 Android 14+ 还要额外权限
 *   • WorkManager 自带重启后恢复、约束条件（无网络不跑）、指数退避
 *   • 代价：系统可能延后执行。国产 ROM（HyperOS）尤其明显，所以设置页里
 *     提供了「忽略电池优化」的引导，并且开启通知时会给一次立即检查。
 */
class WatchWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val settings = ctx.currentSettings()
        if (!settings.configured || !settings.notifyEnabled) return Result.success()

        val api = settings.api() ?: return Result.retry()
        return try {
            val st = api.status(settings.carId)
            // 车辆名缓存起来，避免每轮都请求 /cars
            val carName = runCatching { api.cars() }
                .getOrNull()?.firstOrNull { it.id == settings.carId }?.name
                ?: "车辆"
            evaluate(ctx, settings, carName, st)
            Result.success()
        } catch (e: Exception) {
            // 网络/服务端问题：退避重试，不发通知（网络异常不是车况异常）
            if (runAttemptCount >= 3) Result.failure() else Result.retry()
        }
    }

    private suspend fun evaluate(
        ctx: Context,
        settings: AppSettings,
        carName: String,
        st: CarStatus,
    ) {
        val prev = ctx.currentWatchState()
        val tpms = TpmsReading(st.tpms_fl, st.tpms_fr, st.tpms_rl, st.tpms_rr)
        val charging = st.active_charge != null
        val now = Instant.now()

        // —— 充电开始 / 结束 ——
        if (charging && !prev.lastCharging) {
            Notifier.chargeStarted(
                ctx, carName,
                st.active_charge.battery_level ?: st.battery_level,
            )
        } else if (!charging && prev.lastCharging) {
            Notifier.chargeFinished(
                ctx, carName,
                st.battery_level ?: prev.lastBattery,
                prev.lastAddedKwh,
            )
        }

        // —— 胎压异常（去重：同一异常只发一次）——
        if (tpms.anyAlert && !prev.lastTpmsAlert) {
            Notifier.tpmsAlert(ctx, carName, tpms)
        }

        // —— 数据过期（去重）——
        val staleHours = Duration.between(st.updated_at, now).toHours()
        val isStale = staleHours >= STALE_THRESHOLD_HOURS
        if (isStale && !prev.lastStaleAlert) {
            Notifier.staleData(ctx, carName, staleHours)
        }

        ctx.saveWatchState(
            WatchState(
                lastCharging = charging,
                lastBattery = st.battery_level ?: prev.lastBattery,
                lastAddedKwh = if (charging) {
                    st.active_charge?.energy_added_kwh ?: prev.lastAddedKwh
                } else {
                    prev.lastAddedKwh
                },
                lastTpmsAlert = tpms.anyAlert,
                lastStaleAlert = isStale,
                carName = carName,
                lastCheckAt = now.toEpochMilli(),
            )
        )
    }

    companion object {
        private const val WORK_NAME = "teslamate_car_watch"

        /** 多久算「数据过期」。车停着不动是正常的，超过一天提醒一下即可。 */
        const val STALE_THRESHOLD_HOURS = 24L

        /**
         * 调度周期。WorkManager 最短 15 分钟，再短会被系统拒绝。
         * 想要更快只能在「设置」里手动点「立即检查」。
         */
        const val INTERVAL_MINUTES = 15L

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val req = PeriodicWorkRequestBuilder<WatchWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES,
                )
                .build()
            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                req,
            )
        }

        /** 立即跑一次（设置页的「立即检查」按钮 / 开启通知时先探一次） */
        fun runNow(context: Context) {
            val req = androidx.work.OneTimeWorkRequestBuilder<WatchWorker>().build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }
}
