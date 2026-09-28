package com.me.jiakao

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.me.jiakao.fake.FakeMediaPrefetcher
import com.me.jiakao.fake.FakeQuizRepository
import com.me.jiakao.fake.FakeUserRepository
import com.me.jiakao.ui.screens.practice.PracticeScreen
import com.me.jiakao.ui.screens.practice.PracticeViewModel
import com.me.jiakao.ui.theme.JiakaoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 答题页 UI 测试(手动注入 Fake,不依赖 Hilt):
 * 1) 答题即时反馈(解析展开);2) 答题卡跳转。
 */
@RunWith(AndroidJUnit4::class)
class PracticeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun createViewModel(mode: String = "SEQUENTIAL", subject: Int = 1): PracticeViewModel {
        val quiz = FakeQuizRepository()
        val user = FakeUserRepository(quiz)
        val handle = SavedStateHandle(
            mapOf(
                "mode" to mode,
                "subject" to subject,
                "chapterId" to null,
                "special" to null,
                "recite" to false,
            )
        )
        return PracticeViewModel(handle, quiz, user, FakeMediaPrefetcher(), TestSettings())
    }

    @Test
    fun answer_single_question_shows_feedback_and_explanation() {
        val vm = createViewModel()
        composeRule.setContent {
            JiakaoTheme(themeMode = com.me.jiakao.data.ThemeMode.LIGHT, fontScale = 1f) {
                PracticeScreen(onBack = {}, viewModel = vm)
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { vm.state.value.questions.isNotEmpty() }

        val q = vm.state.value.questions[vm.state.value.index]
        val correctOption = q.options.first { it.key in q.answer }
        composeRule.onNodeWithText(correctOption.text).performClick()

        composeRule.waitUntil(timeoutMillis = 3_000) { vm.state.value.results.isNotEmpty() }
        composeRule.onNodeWithText("正确答案").assertIsDisplayed()
    }

    @Test
    fun answer_wrong_question_shows_wrong_state() {
        val vm = createViewModel()
        composeRule.setContent {
            JiakaoTheme(themeMode = com.me.jiakao.data.ThemeMode.LIGHT, fontScale = 1f) {
                PracticeScreen(onBack = {}, viewModel = vm)
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { vm.state.value.questions.isNotEmpty() }

        val q = vm.state.value.questions[vm.state.value.index]
        val wrongOption = q.options.first { it.key !in q.answer }
        composeRule.onNodeWithText(wrongOption.text).performClick()

        composeRule.waitUntil(timeoutMillis = 3_000) {
            vm.state.value.results[q.id] == false
        }
        composeRule.onNodeWithText("正确答案").assertIsDisplayed()
    }

    @Test
    fun answer_card_jumps_to_selected_question() {
        val vm = createViewModel()
        composeRule.setContent {
            JiakaoTheme(themeMode = com.me.jiakao.data.ThemeMode.LIGHT, fontScale = 1f) {
                PracticeScreen(onBack = {}, viewModel = vm)
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { vm.state.value.questions.isNotEmpty() }

        composeRule.onNodeWithText("答题卡").performClick()
        composeRule.onNodeWithText("4").performClick()

        composeRule.waitUntil(timeoutMillis = 3_000) { vm.state.value.index == 3 }
    }

    @Test
    fun recite_mode_highlights_answer_without_judging() {
        val vm = createViewModel()
        composeRule.setContent {
            JiakaoTheme(themeMode = com.me.jiakao.data.ThemeMode.LIGHT, fontScale = 1f) {
                PracticeScreen(onBack = {}, viewModel = vm)
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { vm.state.value.questions.isNotEmpty() }

        composeRule.onNodeWithContentDescription("进入背题模式").performClick()
        composeRule.waitUntil { vm.state.value.reciteMode }
        composeRule.onNodeWithText("正确答案").assertIsDisplayed()
    }
}
