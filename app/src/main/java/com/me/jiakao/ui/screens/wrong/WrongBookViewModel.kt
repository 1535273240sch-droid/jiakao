package com.me.jiakao.ui.screens.wrong

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.me.jiakao.core.model.Question
import com.me.jiakao.core.model.QuizRepository
import com.me.jiakao.core.model.UserRepository
import com.me.jiakao.data.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class WrongUiState(
    val isLoading: Boolean = true,
    val questions: List<Question> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WrongBookViewModel @Inject constructor(
    private val quiz: QuizRepository,
    private val user: UserRepository,
    settings: AppSettings,
) : ViewModel() {

    val uiState: StateFlow<WrongUiState> = settings.settings.flatMapLatest { s ->
        user.wrongIds(s.subject).mapLatest { ids ->
            WrongUiState(isLoading = false, questions = if (ids.isEmpty()) emptyList() else quiz.getQuestions(ids))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WrongUiState())

    fun removeFromWrong(questionId: String) {
        viewModelScope.launch { user.clearWrong(questionId) }
    }
}
