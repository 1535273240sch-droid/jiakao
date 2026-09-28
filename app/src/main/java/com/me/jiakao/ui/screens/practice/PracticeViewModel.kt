package com.me.jiakao.ui.screens.practice

import androidx.lifecycle.SavedStateHandle
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.me.jiakao.core.model.MediaPrefetcher
import com.me.jiakao.core.model.MediaRef
import com.me.jiakao.core.model.PracticeMode
import com.me.jiakao.core.model.QType
import com.me.jiakao.core.model.Question
import com.me.jiakao.core.model.QuizRepository
import com.me.jiakao.core.model.Scope
import com.me.jiakao.core.model.UserRepository
import com.me.jiakao.data.AppSettings
import com.me.jiakao.nav.PracticeRoute
import com.me.jiakao.nav.specialFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 答题页状态(Immutable) */
@Immutable
data class PracticeUiState(
    val isLoading: Boolean = true,
    val mode: PracticeMode = PracticeMode.SEQUENTIAL,
    val subject: Int = 1,
    val title: String = "",
    val questions: List<Question> = emptyList(),
    val index: Int = 0,
    /** 已提交作答(题目 id → 选项 key 列表) */
    val answers: Map<String, List<String>> = emptyMap(),
    /** 多选未确认的暂选 */
    val pending: Map<String, Set<String>> = emptyMap(),
    /** 已判定结果(题目 id → 是否答对) */
    val results: Map<String, Boolean> = emptyMap(),
    /** 已展开解析的题目 */
    val revealed: Set<String> = emptySet(),
    val favorites: Set<String> = emptySet(),
    val reciteMode: Boolean = false,
) {
    val answeredCount: Int get() = results.size
}

@HiltViewModel
class PracticeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val quiz: QuizRepository,
    private val user: UserRepository,
    private val prefetcher: MediaPrefetcher,
    private val settings: AppSettings,
) : ViewModel() {

    // 从 SavedStateHandle 按 key 读取(type-safe 导航同样以属性名写入这些 key),
    // 避免在 JVM 单元测试中触碰 android.os.Bundle
    private val route = PracticeRoute(
        mode = savedStateHandle.get<String>("mode") ?: "SEQUENTIAL",
        subject = savedStateHandle.get<Int>("subject") ?: 1,
        chapterId = savedStateHandle.get<String>("chapterId"),
        special = savedStateHandle.get<String>("special"),
        recite = savedStateHandle.get<Boolean>("recite") ?: false,
    )
    val mode: PracticeMode = PracticeMode.valueOf(route.mode)
    val subject: Int = route.subject

    /** 进度恢复 key(练习位置同时写入 UserRepository 与 SavedStateHandle) */
    val positionKey: String = buildString {
        append("practice/").append(mode.name.lowercase()).append("/s").append(route.subject)
        route.chapterId?.let { append('/').append(it) }
        route.special?.let { append('/').append(it) }
    }

    private val _state = MutableStateFlow(
        PracticeUiState(mode = mode, subject = route.subject, title = titleFor(route)),
    )
    val state: StateFlow<PracticeUiState> = _state.asStateFlow()

    private companion object {
        const val KEY_INDEX = "practice.index"
    }

    init {
        viewModelScope.launch {
            val s = settings.settings.first()
            val scope = Scope(route.subject, s.vehicle)
            val ids = loadIds(scope)
            val questions = if (ids.isEmpty()) emptyList() else quiz.getQuestions(ids)
            val restored = (savedStateHandle.get<Int>(KEY_INDEX) ?: user.loadPosition(positionKey))
                .coerceIn(0, (questions.size - 1).coerceAtLeast(0))
            _state.update {
                it.copy(
                    isLoading = false,
                    questions = questions,
                    index = restored,
                    reciteMode = s.reciteMode || route.recite,
                )
            }
            prefetchAround(restored)
        }
        viewModelScope.launch {
            user.favoriteIds(route.subject).collect { fav ->
                _state.update { it.copy(favorites = fav) }
            }
        }
    }

    private suspend fun loadIds(scope: Scope): List<String> {
        return when (mode) {
            PracticeMode.SEQUENTIAL -> quiz.ids(scope)
            PracticeMode.RANDOM -> quiz.ids(scope).shuffled()
            PracticeMode.CHAPTER -> quiz.ids(scope, route.chapterId)
            PracticeMode.SPECIAL -> specialFilter(route.special)?.let { quiz.ids(scope, filter = it) } ?: emptyList()
            PracticeMode.WRONG -> user.wrongIds(route.subject).first()
            PracticeMode.FAVORITE -> user.favoriteIds(route.subject).first().toList()
            PracticeMode.EXAM -> emptyList() // 考试由 ExamViewModel 负责
        }
    }

    /** 切题:更新索引、持久化位置、预取后 3 题媒体 */
    fun onIndexChange(newIndex: Int) {
        val st = _state.value
        if (newIndex == st.index || newIndex !in st.questions.indices) return
        _state.update { it.copy(index = newIndex) }
        savedStateHandle[KEY_INDEX] = newIndex
        viewModelScope.launch { user.savePosition(positionKey, newIndex) }
        prefetchAround(newIndex)
    }

    private fun prefetchAround(index: Int) {
        val refs: List<MediaRef> = _state.value.questions
            .drop(index + 1)
            .take(3)
            .flatMap { it.media }
        if (refs.isNotEmpty()) prefetcher.prefetch(refs)
    }

    /** 选择选项:单选/判断点击即判;多选进入暂选 */
    fun choose(question: Question, key: String) {
        val st = _state.value
        if (st.reciteMode || st.answers.containsKey(question.id)) return
        when (question.type) {
            QType.JUDGE, QType.SINGLE -> submit(question, listOf(key))
            QType.MULTI -> {
                val current = st.pending[question.id] ?: emptySet()
                val next = if (key in current) current - key else current + key
                _state.update { it.copy(pending = it.pending + (question.id to next)) }
            }
        }
    }

    /** 多选确认 */
    fun confirmMulti(question: Question) {
        val chosen = _state.value.pending[question.id]?.toList()?.sorted() ?: return
        if (chosen.isEmpty()) return
        submit(question, chosen)
    }

    private fun submit(question: Question, chosen: List<String>) {
        val correct = chosen.toSet() == question.answer.toSet()
        _state.update {
            it.copy(
                answers = it.answers + (question.id to chosen),
                results = it.results + (question.id to correct),
                revealed = it.revealed + question.id,
                pending = it.pending - question.id,
            )
        }
        // 合同接口:即时反馈已在本地完成,记录作答异步落库
        viewModelScope.launch { user.recordAnswer(question, chosen, mode) }
    }

    fun toggleRecite() {
        _state.update { it.copy(reciteMode = !it.reciteMode) }
    }

    fun toggleFavorite(question: Question) {
        viewModelScope.launch { user.toggleFavorite(question) }
    }

    private fun titleFor(route: PracticeRoute): String = when (route.mode) {
        "SEQUENTIAL" -> "顺序练习"
        "RANDOM" -> "随机练习"
        "CHAPTER" -> "章节练习"
        "SPECIAL" -> com.me.jiakao.nav.specialLabel(route.special)
        "WRONG" -> "错题重练"
        "FAVORITE" -> "收藏练习"
        else -> "练习"
    }
}
