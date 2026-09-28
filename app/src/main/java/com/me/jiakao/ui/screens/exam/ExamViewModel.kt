package com.me.jiakao.ui.screens.exam

import androidx.lifecycle.SavedStateHandle
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.me.jiakao.core.model.ExamService
import com.me.jiakao.core.model.PracticeMode
import com.me.jiakao.core.model.Question
import com.me.jiakao.core.model.QuizRepository
import com.me.jiakao.core.model.Scope
import com.me.jiakao.core.model.UserRepository
import com.me.jiakao.data.AppSettings
import com.me.jiakao.nav.ExamRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 模拟考试状态(Immutable) */
@Immutable
data class ExamUiState(
    val isLoading: Boolean = true,
    val subject: Int = 1,
    val questions: List<Question> = emptyList(),
    val index: Int = 0,
    val answers: Map<String, List<String>> = emptyMap(),
    val timeLeftSec: Int = 0,
    val totalCount: Int = 0,
    val submitting: Boolean = false,
)

@HiltViewModel
class ExamViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val quiz: QuizRepository,
    private val user: UserRepository,
    private val examService: ExamService,
    private val settings: AppSettings,
) : ViewModel() {

    // 从 SavedStateHandle 按 key 读取(等价于 type-safe 导航写入的参数),便于 JVM 单测
    private val route = ExamRoute(
        subject = savedStateHandle.get<Int>("subject") ?: 1,
    )
    val rules = examService.rules(route.subject)

    private val _state = MutableStateFlow(
        ExamUiState(
            subject = route.subject,
            timeLeftSec = rules.timeLimitSec,
            totalCount = rules.questionCount,
        ),
    )
    val state: StateFlow<ExamUiState> = _state.asStateFlow()

    /** 交卷完成 → 发射 examId 供界面导航 */
    private val _events = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val events: SharedFlow<Long> = _events.asSharedFlow()

    private var submitted = false
    private val startedAt = System.currentTimeMillis()

    init {
        viewModelScope.launch {
            val s = settings.settings.first()
            val paper = examService.buildPaper(Scope(route.subject, s.vehicle))
            val questions = quiz.getQuestions(paper)
            _state.update { it.copy(isLoading = false, questions = questions, totalCount = questions.size) }
        }
        viewModelScope.launch {
            // 每秒倒计时;到 0 自动交卷
            while (true) {
                delay(1_000)
                val left = _state.value.timeLeftSec
                if (left <= 1) {
                    _state.update { it.copy(timeLeftSec = 0) }
                    submit()
                    break
                }
                _state.update { it.copy(timeLeftSec = left - 1) }
            }
        }
    }

    /** 选择答案(可修改;多选直接切换,交卷前均可更改) */
    fun choose(question: Question, key: String) {
        if (_state.value.submitting) return
        _state.update { st ->
            val current = st.answers[question.id] ?: emptyList()
            val next = when (question.type) {
                com.me.jiakao.core.model.QType.JUDGE, com.me.jiakao.core.model.QType.SINGLE ->
                    listOf(key)
                com.me.jiakao.core.model.QType.MULTI ->
                    (if (key in current) current - key else current + key).sorted()
            }
            st.copy(answers = st.answers + (question.id to next))
        }
    }

    fun onIndexChange(newIndex: Int) {
        _state.update { it.copy(index = newIndex) }
    }

    fun submit() {
        if (submitted) return
        submitted = true
        _state.update { it.copy(submitting = true) }
        viewModelScope.launch {
            val st = _state.value
            val duration = (rules.timeLimitSec - st.timeLeftSec).coerceAtLeast(0)
            val result = examService.grade(
                subject = route.subject,
                questions = st.questions,
                answers = st.answers,
                startedAt = startedAt,
                durationSec = duration,
            )
            val id = user.saveExam(result)
            // 错题计入错题本(考试不即时判,交卷时批量走 recordAnswer)
            st.questions
                .filter { it.id in result.wrongIds }
                .forEach { q -> user.recordAnswer(q, st.answers[q.id] ?: emptyList(), PracticeMode.EXAM) }
            _events.emit(id)
        }
    }
}
