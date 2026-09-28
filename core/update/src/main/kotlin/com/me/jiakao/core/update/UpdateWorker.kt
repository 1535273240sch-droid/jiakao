package com.me.jiakao.core.update

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * 题库更新的加急前台任务（TASK 交付物 4）。
 *
 * 由 `BankUpdater.startUpdate()` 入队（同名唯一任务 `KEEP`，可重入）；
 * 实际工作委托给 [UpdateEngine]，因此状态机只有一份、UI 观察的是同一个 `StateFlow`。
 *
 * 重试策略：可重试失败交给 WorkManager 指数退避（`.part` 与已校验通过的包会保留，
 * 因此重试是续传而不是重下）；尝试次数超过 [MAX_ATTEMPTS] 后放弃并发通知。
 */
@HiltWorker
class UpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: UpdateEngine,
    private val notifier: UpdateNotifier,
    private val logger: UpdateLogger,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        UPDATE_FOREGROUND_NOTIFICATION_ID,
        notifier.foregroundNotification(engine.state.value),
    )

    override suspend fun doWork(): Result {
        try {
            setForeground(getForegroundInfo())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Android 12+ 后台启动前台服务受限时会抛异常：退化为普通后台任务继续执行，
            // 不因为通知问题让更新失败。
            logger.warn("前台通知启动失败，改为后台执行", e)
        }

        return when (val outcome = engine.runPendingUpdate()) {
            is UpdateOutcome.Success -> Result.success()

            is UpdateOutcome.Fatal -> {
                notifier.notifyFailed(outcome.reason)
                Result.failure()
            }

            is UpdateOutcome.Retryable -> {
                if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
                    logger.warn("更新重试 $MAX_ATTEMPTS 次仍失败：${outcome.reason}")
                    notifier.notifyFailed(outcome.reason)
                    Result.failure()
                } else {
                    Result.retry()
                }
            }
        }
    }

    private companion object {
        /** WorkManager 层面的最大尝试次数（超出后由 WorkManager 标记失败）。 */
        const val MAX_ATTEMPTS = 5
    }
}
