package com.me.jiakao.core.media.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore

/**
 * 动图解码器预算：全局最多同时 [maxActive] 个活动解码器（任务书：≤ 2）。
 *
 * 超出的动图不进入动画解码，只显示首帧静态图；槽位释放后排队者自动获得。
 * [acquire] 会挂起直到有空槽，因此调用方直接在 `LaunchedEffect` 中等待即可，
 * 无需自己维护队列 —— 离开可见状态时取消协程就等于退队。
 *
 * [release] 带"未持有则不释放"的保护：忘记配对释放、或在没拿到槽位时误调 release，
 * 都不会凭空放大预算（那正是这类门控最容易出的 bug）。
 */
internal class AnimDecoderGate(val maxActive: Int = DEFAULT_MAX_ACTIVE) {

    init {
        require(maxActive > 0) { "maxActive must be > 0" }
    }

    private val semaphore = Semaphore(maxActive)
    private val lock = Any()

    private var active = 0

    private val _activeCount = MutableStateFlow(0)

    /** 当前活动解码器数量（可观察，用于调试/演示 HUD）。 */
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    /** 占用一个解码槽，无空槽时挂起等待。 */
    suspend fun acquire() {
        semaphore.acquire()
        synchronized(lock) {
            active++
            _activeCount.value = active
        }
    }

    /** 立即尝试占用一个解码槽；无空槽时返回 false，不挂起。 */
    fun tryAcquire(): Boolean {
        if (!semaphore.tryAcquire()) return false
        synchronized(lock) {
            active++
            _activeCount.value = active
        }
        return true
    }

    /** 释放一个解码槽；未持有时是空操作。 */
    fun release() {
        synchronized(lock) {
            if (active <= 0) return
            active--
            _activeCount.value = active
            semaphore.release()
        }
    }

    companion object {
        /** 任务书 §3 硬性指标：同时活动的动图解码器 ≤ 2。 */
        const val DEFAULT_MAX_ACTIVE: Int = 2

        /** 进程内共享的唯一预算。 */
        val global: AnimDecoderGate = AnimDecoderGate(DEFAULT_MAX_ACTIVE)
    }
}
