package com.me.jiakao.ui.screens.practice

import androidx.lifecycle.SavedStateHandle
import com.me.jiakao.core.model.PracticeMode
import com.me.jiakao.core.model.QType
import com.me.jiakao.fake.FakeMediaPrefetcher
import com.me.jiakao.fake.FakeQuizRepository
import com.me.jiakao.fake.FakeSettingsForTest
import com.me.jiakao.fake.FakeUserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PracticeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        mode: String = "SEQUENTIAL",
        subject: Int = 1,
        recite: Boolean = false,
    ): Triple<PracticeViewModel, FakeUserRepository, FakeMediaPrefetcher> {
        val quiz = FakeQuizRepository()
        val user = FakeUserRepository(quiz)
        val prefetcher = FakeMediaPrefetcher()
        val handle = SavedStateHandle(
            mapOf(
                "mode" to mode,
                "subject" to subject,
                "chapterId" to null,
                "special" to null,
                "recite" to recite,
            )
        )
        val vm = PracticeViewModel(handle, quiz, user, prefetcher, FakeSettingsForTest())
        return Triple(vm, user, prefetcher)
    }

    @Test
    fun loadQuestionsAndStartFromFirst() = runTest(dispatcher) {
        val (vm, _, _) = createViewModel()
        val state = vm.state.first { !it.isLoading }
        assertTrue(state.questions.isNotEmpty())
        assertEquals(0, state.index)
    }

    @Test
    fun singleCorrectAnswerGivesImmediateFeedback() = runTest(dispatcher) {
        val (vm, user, _) = createViewModel()
        val state = vm.state.first { !it.isLoading }
        val q = state.questions.first { it.type == QType.SINGLE }
        val correctKey = q.answer.first()

        vm.onIndexChange(state.questions.indexOf(q))
        vm.choose(q, correctKey)

        val after = vm.state.value
        assertEquals(listOf(correctKey), after.answers[q.id])
        assertEquals(true, after.results[q.id])
        assertTrue(q.id in after.revealed)
        assertFalse(q.id in user.wrongIds(q.subject).first())
    }

    @Test
    fun wrongAnswerEntersWrongBook() = runTest(dispatcher) {
        val (vm, user, _) = createViewModel()
        val state = vm.state.first { !it.isLoading }
        val q = state.questions.first { it.type == QType.SINGLE }
        val wrongKey = q.options.map { it.key }.first { it !in q.answer }

        vm.onIndexChange(state.questions.indexOf(q))
        vm.choose(q, wrongKey)

        assertEquals(false, vm.state.value.results[q.id])
        assertTrue("wrong answer should enter wrong book", q.id in user.wrongIds(q.subject).first())
    }

    @Test
    fun multiRequiresConfirmBeforeJudging() = runTest(dispatcher) {
        val (vm, _, _) = createViewModel()
        val state = vm.state.first { !it.isLoading }
        val q = state.questions.first { it.type == QType.MULTI }
        vm.onIndexChange(state.questions.indexOf(q))

        q.answer.forEach { key -> vm.choose(q, key) }
        assertNull(vm.state.value.results[q.id]) // 未确认不判定
        vm.confirmMulti(q)
        assertEquals(true, vm.state.value.results[q.id]) // 精确选对
        assertTrue(vm.state.value.pending[q.id].isNullOrEmpty()) // 确认后暂选清除
    }

    @Test
    fun multiWrongSelectionScoresFalse() = runTest(dispatcher) {
        val (vm, _, _) = createViewModel()
        val state = vm.state.first { !it.isLoading }
        val q = state.questions.first { it.type == QType.MULTI }
        vm.onIndexChange(state.questions.indexOf(q))
        // 全选 → 必然与正确答案不一致 → 判错
        q.options.forEach { vm.choose(q, it.key) }
        vm.confirmMulti(q)
        assertEquals(false, vm.state.value.results[q.id])
    }

    @Test
    fun indexChangeSavesPositionAndPrefetches() = runTest(dispatcher) {
        val (vm, user, prefetcher) = createViewModel()
        val state = vm.state.first { !it.isLoading }
        val target = (state.questions.size - 1).coerceAtMost(7)
        vm.onIndexChange(target)
        assertEquals(target, vm.state.value.index)
        assertEquals(target, user.loadPosition(vm.positionKey))
        assertTrue(prefetcher.prefetchCalls.get() >= 0)
    }

    @Test
    fun reciteModeDoesNotJudge() = runTest(dispatcher) {
        val (vm, _, _) = createViewModel(recite = true)
        val state = vm.state.first { !it.isLoading && it.reciteMode }
        val q = state.questions.first()
        vm.choose(q, q.options.first().key)
        assertNull(vm.state.value.results[q.id])
    }

    @Test
    fun wrongModeLoadsEmptyWhenNoWrongQuestions() = runTest(dispatcher) {
        val quiz = FakeQuizRepository()
        val user = FakeUserRepository(quiz)
        val handle = SavedStateHandle(
            mapOf("mode" to "WRONG", "subject" to 1, "chapterId" to null, "special" to null, "recite" to false)
        )
        val vm = PracticeViewModel(handle, quiz, user, FakeMediaPrefetcher(), FakeSettingsForTest())
        val state = vm.state.first { !it.isLoading }
        assertTrue("no wrong questions -> empty list", state.questions.isEmpty())
        assertEquals(PracticeMode.WRONG, vm.mode)
    }
}
