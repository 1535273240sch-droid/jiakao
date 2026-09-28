package com.me.jiakao.ui.screens.favorites

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
data class FavoritesUiState(
    val isLoading: Boolean = true,
    val questions: List<Question> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val quiz: QuizRepository,
    private val user: UserRepository,
    settings: AppSettings,
) : ViewModel() {

    val uiState: StateFlow<FavoritesUiState> = settings.settings.flatMapLatest { s ->
        user.favoriteIds(s.subject).mapLatest { ids ->
            FavoritesUiState(isLoading = false, questions = if (ids.isEmpty()) emptyList() else quiz.getQuestions(ids.toList()))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FavoritesUiState())

    fun toggleFavorite(question: Question) {
        viewModelScope.launch { user.toggleFavorite(question) }
    }
}
