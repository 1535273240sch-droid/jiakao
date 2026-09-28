package com.me.jiakao.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.me.jiakao.core.model.Question
import com.me.jiakao.core.model.QuizRepository
import com.me.jiakao.core.model.Scope
import com.me.jiakao.data.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val quiz: QuizRepository,
    private val settings: AppSettings,
) : ViewModel() {

    /** 搜索关键词(防抖 250ms 后触发查询) */
    val query = MutableStateFlow("")

    val results: StateFlow<List<Question>> = query
        .debounce(250)
        .distinctUntilChanged()
        .flatMapLatest { kw ->
            flow {
                if (kw.isBlank()) {
                    emit(emptyList())
                } else {
                    val s = settings.settings.first()
                    emit(quiz.search(Scope(s.subject, s.vehicle), kw.trim(), limit = 50))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) {
        query.value = value
    }
}
