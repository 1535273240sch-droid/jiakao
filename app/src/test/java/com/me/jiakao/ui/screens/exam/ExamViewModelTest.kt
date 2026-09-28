package com.me.jiakao.ui.screens.exam

import androidx.lifecycle.SavedStateHandle
import com.me.jiakao.core.model.ExamRules
import com.me.jiakao.core.model.QType
import com.me.jiakao.fake.FakeBank
import com.me.jiakao.fake.FakeExamService
import com.me.jiakao.fake.FakeQuizRepository
import com.me.jiakao.fake.FakeSettingsForTest
import com.me.jiakao.fake.FakeUserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExamViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun createViewModel(
        subject: Int = 1,
        timeLimitSec: Int = 4,
        questionCount: Int = 3,
        paperOverride: List<String>? = null,
    ): Quad<ExamViewModel, FakeUserRepository, FakeExamService, FakeQuizRepository> {
        val quiz = FakeQuizRepository()
        val user = FakeUserRepository(quiz)
        val examService = FakeExamService(quiz).apply {
            overrideRules = ExamRules(subject, questionCount, timeLimitSec, pointsPerQuestion = 1, passScore = 2)
            this.paperOverride = paperOverride
        }
        val handle = SavedStateHandle(mapOf("subject" to subject))
        val vm = ExamViewModel(handle, quiz, user, examService, FakeSettingsForTest())
        return Quad(vm, user, examService, quiz)
    }

    /** 组一套确定性的试卷:单选 + 判断 + 多选 */
    private fun deterministicPaper(): Triple<com.me.jiakao.core.model.Question, com.me.jiakao.core.model.Question, com.me.jiakao.core.model.Question> {
        val bank = FakeBank.questions.filter { it.subject == 1 && "car" in it.vehicles }
        val single = bank.first { it.type == QType.SINGLE }
        val judge = bank.first { it.type == QType.JUDGE }
        val multi = bank.first { it.type == QType.MULTI && it.answer.size < it.options.size }
        return Triple(single, judge, multi)
    }

    @Test
    fun loadsPaperWithExpectedSizeAndInitialClock() = runTest(dispatcher) {
        val (single, judge, multi) = deterministicPaper()
        val (vm, _, _, _) = createViewModel(paperOverride = listOf(single.id, judge.id, multi.id))
        runCurrent() // 只执行到首个挂起点,不推进倒计时
        val state = vm.state.first { !it.isLoading }
        assertEquals(3, state.questions.size)
        assertEquals(4, state.timeLeftSec)
        assertFalse(state.submitting)
    }

    @Test
    fun multiAnswerTogglesDirectly() = runTest(dispatcher) {
        val (single, judge, multi) = deterministicPaper()
        val (vm, _, _, _) = createViewModel(paperOverride = listOf(single.id, judge.id, multi.id))
        runCurrent()
        val state = vm.state.first { !it.isLoading }
        val multiQ = state.questions.first { it.type == QType.MULTI }
        val keyA = multiQ.options[0].key
        val keyB = multiQ.options[1].key

        vm.choose(multiQ, keyA)
        assertEquals(listOf(keyA), vm.state.value.answers[multiQ.id])
        vm.choose(multiQ, keyB)
        assertEquals(listOf(keyA, keyB), vm.state.value.answers[multiQ.id])
        vm.choose(multiQ, keyA) // 再次点击 = 取消
        assertEquals(listOf(keyB), vm.state.value.answers[multiQ.id])
    }

    @Test
    fun manualSubmitGradesAndFillsWrongBook() = runTest(dispatcher) {
        val (single, judge, multi) = deterministicPaper()
        val (vm, user, _, _) = createViewModel(paperOverride = listOf(single.id, judge.id, multi.id))
        runCurrent()
        val state = vm.state.first { !it.isLoading }

        // 第 1 题按正确答案作答,第 2 题故意答错
        val q0 = state.questions[0]
        q0.answer.forEach { key -> vm.choose(q0, key) }
        val q1 = state.questions[1]
        val wrongKey = q1.options.map { it.key }.first { it !in q1.answer }
        vm.choose(q1, wrongKey)

        var emittedId: Long? = null
        backgroundScope.launch { vm.events.collect { emittedId = it } }

        vm.submit()
        advanceUntilIdle()
        runCurrent()

        assertNotNull("submit should emit examId", emittedId)
        val exams = user.exams(1).first()
        assertEquals(1, exams.size)
        assertEquals(1, exams[0].score) // 每题 1 分,答对 1 题
        assertFalse(exams[0].passed)
        assertTrue("wrong answer should enter wrong book", q1.id in user.wrongIds(1).first())
        assertTrue(q0.id !in user.wrongIds(1).first())
    }

    @Test
    fun autoSubmitsWhenTimeIsUp() = runTest(dispatcher) {
        val (single, judge, multi) = deterministicPaper()
        val (vm, user, _, _) = createViewModel(timeLimitSec = 3, paperOverride = listOf(single.id, judge.id, multi.id))
        runCurrent()
        assertTrue(vm.state.value.timeLeftSec > 0)

        var emitted = false
        backgroundScope.launch { vm.events.collect { emitted = true } }

        advanceTimeBy(3_500)
        advanceUntilIdle()
        runCurrent()

        assertEquals(0, vm.state.value.timeLeftSec)
        assertTrue("countdown reaching zero should auto submit", emitted)
        assertEquals(1, user.exams(1).first().size)
    }
}
