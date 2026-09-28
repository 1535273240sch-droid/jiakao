package com.me.jiakao.ui.screens.search

import com.me.jiakao.fake.FakeQuizRepository
import com.me.jiakao.fake.FakeSettingsForTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun debounceThenReturnResults() = runTest(dispatcher) {
        val vm = SearchViewModel(FakeQuizRepository(), FakeSettingsForTest())
        // WhileSubscribed 需要订阅者才启动上游
        backgroundScope.launch { vm.results.collect {} }

        vm.setQuery("实习期")
        advanceTimeBy(200)
        runCurrent()
        assertTrue("results should be empty during debounce window", vm.results.value.isEmpty())

        advanceTimeBy(150)
        advanceUntilIdle()
        runCurrent()
        assertTrue("results should appear after debounce", vm.results.value.isNotEmpty())
    }

    @Test
    fun blankQueryReturnsNoResults() = runTest(dispatcher) {
        val vm = SearchViewModel(FakeQuizRepository(), FakeSettingsForTest())
        backgroundScope.launch { vm.results.collect {} }
        advanceUntilIdle()
        vm.setQuery("   ")
        advanceTimeBy(300)
        advanceUntilIdle()
        assertTrue(vm.results.value.isEmpty())
    }
}
