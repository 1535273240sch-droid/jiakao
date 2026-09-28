package com.me.jiakao.ui.screens.exam

import androidx.lifecycle.SavedStateHandle
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.me.jiakao.core.model.ExamResult
import com.me.jiakao.core.model.Question
import com.me.jiakao.core.model.QuizRepository
import com.me.jiakao.core.model.UserRepository
import com.me.jiakao.data.AppSettings
import com.me.jiakao.nav.ExamResultRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 成绩页状态:考试结果 + 错题详情 */
@Immutable
data class ExamResultUiState(
    val result: ExamResult,
    val wrongQuestions: List<Question> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ExamResultViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val quiz: QuizRepository,
    user: UserRepository,
    settings: AppSettings,
) : ViewModel() {

    private val route = ExamResultRoute(
        examId = savedStateHandle.get<Long>("examId") ?: -1L,
    )

    val uiState: StateFlow<ExamResultUiState?> = settings.settings
        .flatMapLatest { s -> user.exams(s.subject) }
        .mapLatest { exams -> exams.firstOrNull { it.id == route.examId } }
        .flatMapLatest { result ->
            flow {
                if (result == null) {
                    emit(null)
                } else {
                    val wrong = if (result.wrongIds.isEmpty()) emptyList() else quiz.getQuestions(result.wrongIds)
                    emit(ExamResultUiState(result, wrong))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
