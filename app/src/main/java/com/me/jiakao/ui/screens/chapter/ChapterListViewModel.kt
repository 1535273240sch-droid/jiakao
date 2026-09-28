package com.me.jiakao.ui.screens.chapter

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.me.jiakao.core.model.ChapterStat
import com.me.jiakao.core.model.QuizRepository
import com.me.jiakao.core.model.Scope
import com.me.jiakao.core.model.UserRepository
import com.me.jiakao.data.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@Immutable
data class ChapterUiState(
    val isLoading: Boolean = true,
    val stats: List<ChapterStat> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChapterListViewModel @Inject constructor(
    quiz: QuizRepository,
    user: UserRepository,
    settings: AppSettings,
) : ViewModel() {

    val uiState: StateFlow<ChapterUiState> = settings.settings.flatMapLatest { s ->
        val scope = Scope(s.subject, s.vehicle)
        combine(quiz.chapters(scope), user.chapterStats(scope)) { chapters, stats ->
            ChapterUiState(
                isLoading = false,
                stats = stats.sortedBy { it.chapter.order },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChapterUiState())
}
