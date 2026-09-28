package com.me.jiakao

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.me.jiakao.core.model.ExamRules
import com.me.jiakao.fake.FakeExamService
import com.me.jiakao.fake.FakeQuizRepository
import com.me.jiakao.fake.FakeUserRepository
import com.me.jiakao.ui.screens.exam.ExamScreen
import com.me.jiakao.ui.screens.exam.ExamViewModel
import com.me.jiakao.ui.theme.JiakaoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 考试 UI 测试:倒计时到点自动交卷并回调 */
@RunWith(AndroidJUnit4::class)
class ExamScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun exam_auto_submits_when_time_is_up() {
        val quiz = FakeQuizRepository()
        val user = FakeUserRepository(quiz)
        val examService = FakeExamService(quiz).apply {
            overrideRules = ExamRules(subject = 1, questionCount = 2, timeLimitSec = 2, pointsPerQuestion = 1, passScore = 1)
        }
        val vm = ExamViewModel(
            savedStateHandle = SavedStateHandle(mapOf("subject" to 1)),
            quiz = quiz,
            user = user,
            examService = examService,
            settings = TestSettings(),
        )
        var finishedId: Long? = null
        composeRule.setContent {
            JiakaoTheme(themeMode = com.me.jiakao.data.ThemeMode.LIGHT, fontScale = 1f) {
                ExamScreen(
                    onFinished = { finishedId = it },
                    onBack = {},
                    viewModel = vm,
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 15_000) { finishedId != null }
        assert(finishedId != null)
    }

    @Test
    fun exam_submit_button_shows_confirm_dialog() {
        val quiz = FakeQuizRepository()
        val user = FakeUserRepository(quiz)
        val examService = FakeExamService(quiz).apply {
            overrideRules = ExamRules(subject = 1, questionCount = 2, timeLimitSec = 600, pointsPerQuestion = 1, passScore = 1)
        }
        val vm = ExamViewModel(
            savedStateHandle = SavedStateHandle(mapOf("subject" to 1)),
            quiz = quiz,
            user = user,
            examService = examService,
            settings = TestSettings(),
        )
        composeRule.setContent {
            JiakaoTheme(themeMode = com.me.jiakao.data.ThemeMode.LIGHT, fontScale = 1f) {
                ExamScreen(onFinished = {}, onBack = {}, viewModel = vm)
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { vm.state.value.questions.isNotEmpty() }
        composeRule.onNodeWithText("交卷").performClick()
        composeRule.onNodeWithText("确认交卷?").assertExists()
    }
}
