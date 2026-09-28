package com.me.jiakao.ui.screens.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.me.jiakao.core.model.BankCounts
import com.me.jiakao.core.model.BankUpdater
import com.me.jiakao.core.model.Progress
import com.me.jiakao.core.model.QuizRepository
import com.me.jiakao.core.model.Scope
import com.me.jiakao.core.model.UpdateState
import com.me.jiakao.core.model.UserRepository
import com.me.jiakao.data.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 首页 UI 状态(Immutable) */
@Immutable
data class HomeUiState(
    val isLoading: Boolean = true,
    val subject: Int = 1,
    val vehicle: String = "car",
    val counts: BankCounts? = null,
    val chapterCount: Int = 0,
    val progress: Progress? = null,
    val wrongCount: Int = 0,
    val favoriteCount: Int = 0,
    val bankVersion: Int = 0,
    val updateState: UpdateState = UpdateState.Idle,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val settings: AppSettings,
    private val quiz: QuizRepository,
    private val user: UserRepository,
    private val updater: BankUpdater,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = settings.settings.flatMapLatest { s ->
        val scope = Scope(s.subject, s.vehicle)
        combine(
            combine(quiz.counts(scope), quiz.chapters(scope), user.progress(scope)) { c, ch, p ->
                Triple(c, ch, p)
            },
            combine(
                user.wrongIds(s.subject).map { it.size },
                user.favoriteIds(s.subject).map { it.size },
                quiz.bankVersion,
                updater.state,
            ) { w, f, v, u -> Part2(w, f, v, u) },
        ) { part1, part2 ->
            HomeUiState(
                isLoading = false,
                subject = s.subject,
                vehicle = s.vehicle,
                counts = part1.first,
                chapterCount = part1.second.size,
                progress = part1.third,
                wrongCount = part2.wrongCount,
                favoriteCount = part2.favoriteCount,
                bankVersion = part2.bankVersion,
                updateState = part2.updateState,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** 顶部「检查更新」轻入口:只检查不下载 */
    fun checkUpdate() {
        viewModelScope.launch { updater.check() }
    }

    /** 首页科目切换(全局设置) */
    fun setSubject(subject: Int) {
        viewModelScope.launch { settings.setSubject(subject) }
    }

    private data class Part2(
        val wrongCount: Int,
        val favoriteCount: Int,
        val bankVersion: Int,
        val updateState: UpdateState,
    )
}
