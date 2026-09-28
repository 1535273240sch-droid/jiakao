package com.me.jiakao.core.update

import com.me.jiakao.core.model.UpdateState
import kotlinx.coroutines.flow.StateFlow

/** `min_app_version_code` 高于本机时统一的失败提示（TASK 交付物 4）。 */
const val MESSAGE_APP_TOO_OLD: String = "请先升级 App"

/**
 * 一次完整更新的执行结果，供 [UpdateWorker] 决定 WorkManager 的返回码。
 */
sealed interface UpdateOutcome {

    /** 更新完成（含「已是最新」）。 */
    data object Success : UpdateOutcome

    /** 可重试失败：网络、5xx、导入过程中的临时错误。 */
    data class Retryable(val reason: String) : UpdateOutcome

    /** 不可重试失败：源地址没配、清单/包损坏、App 版本过低。 */
    data class Fatal(val reason: String) : UpdateOutcome
}

/**
 * 更新状态机的内部入口：由 [BankUpdaterImpl] 实现，[UpdateWorker] 调用。
 *
 * 与 [com.me.jiakao.core.model.BankUpdater] 分开是刻意的：对外只暴露合同里的 API，
 * 「执行一次更新」属于模块内部能力。
 */
interface UpdateEngine {

    /** 当前更新状态（与 `BankUpdater.state` 是同一个流）。 */
    val state: StateFlow<UpdateState>

    /**
     * 执行一次完整更新：检查清单 → 下载校验 → 单事务导入 → 补齐媒体。
     *
     * 失败时状态机已切到 [UpdateState.Failed]，返回值只表达 WorkManager 该不该重试。
     */
    suspend fun runPendingUpdate(): UpdateOutcome
}
