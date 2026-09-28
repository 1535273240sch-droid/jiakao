package com.me.jiakao.fake

import com.me.jiakao.core.model.BankUpdater
import com.me.jiakao.core.model.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 假更新器:按合同 UpdateState 的全状态机模拟一次完整更新流程
 * (检查 → 可用 → 下载题包 → 导入 → 补媒体 → 最新),拼装时由 04 替换。
 */
@Singleton
class FakeBankUpdater @Inject constructor(
    private val quiz: FakeQuizRepository,
) : BankUpdater {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _sourceUrl = MutableStateFlow("https://demo.jiakao.example.com/bank/")
    override val sourceUrl: StateFlow<String> = _sourceUrl.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var updateJob: Job? = null
    private var autoEnabled: Boolean = true

    override suspend fun setSource(baseUrl: String) {
        _sourceUrl.value = if (baseUrl.isBlank()) "" else baseUrl.trimEnd('/') + "/"
    }

    override suspend fun check() {
        _state.value = UpdateState.Checking
        delay(700)
        _state.value = when {
            _sourceUrl.value.isBlank() -> UpdateState.Failed("题库源地址无效,请先在设置中保存地址", retryable = false)
            quiz.currentVersion() >= REMOTE_VERSION -> UpdateState.UpToDate(quiz.currentVersion())
            else -> UpdateState.Available(
                fromVersion = quiz.currentVersion(),
                toVersion = REMOTE_VERSION,
                downloadBytes = PACK_BYTES,
            )
        }
    }

    override fun startUpdate() {
        updateJob?.cancel()
        updateJob = scope.launch {
            runCatching {
                _state.value = UpdateState.DownloadingPack(0, PACK_BYTES)
                var done = 0L
                while (done < PACK_BYTES) {
                    delay(180)
                    done = (done + PACK_BYTES / 8).coerceAtMost(PACK_BYTES)
                    _state.value = UpdateState.DownloadingPack(done, PACK_BYTES)
                }
                _state.value = UpdateState.Importing
                delay(900)
                _state.value = UpdateState.DownloadingMedia(0, MEDIA_TOTAL)
                for (n in 1..MEDIA_TOTAL) {
                    delay(220)
                    _state.value = UpdateState.DownloadingMedia(n, MEDIA_TOTAL)
                }
                quiz.bumpVersion(REMOTE_VERSION)
                _state.value = UpdateState.UpToDate(REMOTE_VERSION)
            }.onFailure {
                _state.value = UpdateState.Failed("模拟更新中断:${it.message}", retryable = true)
            }
        }
    }

    override suspend fun importLocalPack(input: InputStream) {
        input.use { _ -> delay(600) } // 假实现不真正解包
        quiz.bumpVersion(REMOTE_VERSION)
        _state.value = UpdateState.UpToDate(REMOTE_VERSION)
    }

    override fun scheduleAuto(enabled: Boolean) {
        autoEnabled = enabled
    }

    private companion object {
        const val REMOTE_VERSION = 12
        const val PACK_BYTES = 2_411_724L
        const val MEDIA_TOTAL = 12
    }
}
