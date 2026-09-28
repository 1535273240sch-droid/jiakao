package com.me.jiakao.core.update

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.me.jiakao.core.model.BankUpdater
import com.me.jiakao.core.model.UpdateState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * 每 24h 的自动检查任务（TASK 交付物 7）。
 *
 * 约束：`UNMETERED`（Wi-Fi / 不计费网络）+ 电量不低；**只做 check**，发现更新发系统通知。
 * 只有在确认当前是非计费网络时才顺带入队真正的更新任务（含媒体下载），
 * 避免在移动数据上自动耗流量。
 */
@HiltWorker
class AutoUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val bankUpdater: BankUpdater,
    private val scheduler: UpdateWorkScheduler,
    private val notifier: UpdateNotifier,
    private val networkState: NetworkStateProvider,
    private val logger: UpdateLogger,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            bankUpdater.check()
            when (val state = bankUpdater.state.value) {
                is UpdateState.Available -> {
                    notifier.notifyUpdateAvailable(
                        fromVersion = state.fromVersion,
                        toVersion = state.toVersion,
                        downloadBytes = state.downloadBytes,
                    )
                    if (networkState.isUnmetered()) {
                        logger.debug("自动更新：非计费网络，入队更新任务")
                        scheduler.enqueueUpdate()
                    } else {
                        logger.debug("自动更新：当前网络计费，仅通知不下载")
                    }
                    Result.success()
                }

                is UpdateState.Failed -> {
                    logger.warn("自动检查更新失败：${state.message}")
                    if (state.retryable) Result.retry() else Result.success()
                }

                else -> Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("自动检查更新异常", e)
            Result.retry()
        }
    }
}
