package com.me.jiakao.core.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 更新任务的排程端口。
 *
 * 抽成接口，一是让状态机单测不依赖 WorkManager/Android，二是把「怎么调度」集中在一处。
 */
interface UpdateWorkScheduler {

    /** 入队一次「下载 → 导入 → 补齐媒体」的加急前台任务（可重入，同名任务 KEEP）。 */
    fun enqueueUpdate()

    /** 打开/关闭 每 24h 的自动检查（Wi-Fi + 电量不低）。 */
    fun scheduleAuto(enabled: Boolean)
}

/** WorkManager 实现。 */
@Singleton
class WorkManagerUpdateScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : UpdateWorkScheduler {

    override fun enqueueUpdate() {
        val request = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager().enqueueUniqueWork(UPDATE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun scheduleAuto(enabled: Boolean) {
        val manager = workManager()
        if (!enabled) {
            manager.cancelUniqueWork(AUTO_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<AutoUpdateWorker>(AUTO_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    // 合同/TASK：每 24h、Wi-Fi（不计费网络）、电量不低
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .addTag(TAG)
            .build()
        manager.enqueueUniquePeriodicWork(AUTO_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun workManager(): WorkManager = WorkManager.getInstance(context)

    companion object {
        /** 手动更新任务的唯一名字（可重入：同名任务 KEEP）。 */
        const val UPDATE_WORK_NAME: String = "jiakao-bank-update"

        /** 自动检查任务的唯一名字。 */
        const val AUTO_WORK_NAME: String = "jiakao-bank-auto-update"

        /** 自动检查周期（小时）。 */
        const val AUTO_INTERVAL_HOURS: Long = 24L

        /** WorkManager 重试退避起点。 */
        private const val BACKOFF_SECONDS: Long = 30L

        private const val TAG = "jiakao-update"
    }
}
