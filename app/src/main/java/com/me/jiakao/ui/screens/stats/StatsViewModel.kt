package com.me.jiakao.ui.screens.stats

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.me.jiakao.core.model.BankCounts
import com.me.jiakao.core.model.ChapterStat
import com.me.jiakao.core.model.ExamResult
import com.me.jiakao.core.model.ExamRules
import com.me.jiakao.core.model.Progress
import com.me.jiakao.core.model.QuizRepository
import com.me.jiakao.core.model.Scope
import com.me.jiakao.core.model.UserRepository
import com.me.jiakao.data.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 统计页状态 */
@Immutable
data class StatsUiState(
    val isLoading: Boolean = true,
    val subject: Int = 1,
    val progress: Progress? = null,
    val counts: BankCounts? = null,
    val chapterStats: List<ChapterStat> = emptyList(),
    val exams: List<ExamResult> = emptyList(),
    val passScore: Int = 90,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    quiz: QuizRepository,
    user: UserRepository,
    settings: AppSettings,
) : ViewModel() {

    val uiState: StateFlow<StatsUiState> = settings.settings.flatMapLatest { s ->
        val scope = Scope(s.subject, s.vehicle)
        combine(
            user.progress(scope),
            quiz.counts(scope),
            user.chapterStats(scope),
            user.exams(s.subject),
        ) { progress, counts, chapters, exams ->
            StatsUiState(
                isLoading = false,
                subject = s.subject,
                progress = progress,
                counts = counts,
                chapterStats = chapters.sortedBy { it.chapter.order },
                exams = exams.sortedBy { it.startedAt },
                passScore = ExamRules.of(s.subject).passScore,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())
}
