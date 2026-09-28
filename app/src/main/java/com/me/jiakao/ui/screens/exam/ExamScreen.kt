package com.me.jiakao.ui.screens.exam

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.me.jiakao.ui.anim.rememberAnimationsEnabled
import com.me.jiakao.ui.components.EmptyState
import com.me.jiakao.ui.screens.practice.AnswerCardSheet
import com.me.jiakao.ui.screens.practice.QuestionPage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 模拟考试:倒计时(5 分钟橙 / 1 分钟红+轻震动)、不即时反馈、保持常亮、到点自动交卷 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamScreen(
    onFinished: (examId: Long) -> Unit,
    onBack: () -> Unit,
    viewModel: ExamViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSubmitDialog by remember { mutableStateOf(false) }
    val view = LocalView.current

    // 屏幕常亮(考试期间)
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // 5 分钟 / 1 分钟阈值轻震动
    LaunchedEffect(state.timeLeftSec) {
        if (state.timeLeftSec == 300 || state.timeLeftSec == 60) {
            vibrateLight(view.context)
        }
    }

    // 交卷完成 → 导航成绩页
    LaunchedEffect(Unit) {
        viewModel.events.collect { examId -> onFinished(examId) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { ExamClock(timeLeftSec = state.timeLeftSec) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出考试")
                    }
                },
                actions = {
                    TextButton(onClick = { showSubmitDialog = true }) {
                        Text("交卷", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.questions.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Filled.GridView, "题库为空,无法开考")
            }
            else -> ExamContent(
                state = state,
                viewModel = viewModel,
                padding = padding,
            )
        }
    }

    if (showSubmitDialog) {
        AlertDialog(
            onDismissRequest = { showSubmitDialog = false },
            title = { Text("确认交卷?") },
            text = {
                val answered = state.answers.count { it.value.isNotEmpty() }
                Text("已答 $answered/${state.totalCount} 题,交卷后立即评分,不能继续作答。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showSubmitDialog = false
                    viewModel.submit()
                }) { Text("确认交卷", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showSubmitDialog = false }) { Text("继续答题") }
            },
        )
    }
}

@Composable
private fun ExamContent(
    state: ExamUiState,
    viewModel: ExamViewModel,
    padding: androidx.compose.foundation.layout.PaddingValues,
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = state.index, pageCount = { state.questions.size })
    var showAnswerCard by remember { mutableStateOf(false) }
    // 底部栏翻页状态直接从 pagerState 派生(derivedStateOf),减少经 VM 回流的多余重组
    val currentIndex by remember { androidx.compose.runtime.derivedStateOf { pagerState.currentPage } }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { viewModel.onIndexChange(it) }
    }

    Column(Modifier.fillMaxSize().padding(padding)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            beyondViewportPageCount = 1,
            pageSpacing = 12.dp,
            key = { it },
        ) { page ->
            val question = state.questions[page]
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                QuestionPage(
                    question = question,
                    chosen = state.answers[question.id],
                    result = null, // 考试不即时判
                    revealed = false,
                    reciteMode = false,
                    pendingKeys = emptySet(),
                    examMode = true,
                    onChoose = viewModel::choose,
                    onConfirmMulti = {},
                    onOpenMedia = {},
                )
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(
                    onClick = { scope.launch { pagerState.animateScrollToPage(currentIndex - 1) } },
                    enabled = currentIndex > 0,
                ) {
                    Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = "上一题")
                }
                FilledTonalButton(onClick = { showAnswerCard = true }) {
                    Icon(Icons.Filled.GridView, contentDescription = null)
                    Text(
                        "答题卡 ${state.answers.count { it.value.isNotEmpty() }}/${state.totalCount}",
                        Modifier.padding(start = 6.dp),
                    )
                }
                IconButton(
                    onClick = { scope.launch { pagerState.animateScrollToPage(currentIndex + 1) } },
                    enabled = currentIndex < state.questions.lastIndex,
                ) {
                    Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "下一题")
                }
            }
        }
    }

    if (showAnswerCard) {
        AnswerCardSheet(
            questions = state.questions,
            results = state.answers.mapValues { (_, v) -> v.isNotEmpty() },
            currentIndex = currentIndex,
            onJump = { index ->
                showAnswerCard = false
                scope.launch { pagerState.animateScrollToPage(index) }
            },
            onDismiss = { showAnswerCard = false },
            showResults = false,
        )
    }
}

/** 倒计时:数字翻牌式过渡;剩 5 分钟变橙、1 分钟变红 */
@Composable
private fun ExamClock(timeLeftSec: Int) {
    val animationsEnabled = rememberAnimationsEnabled()
    val color = when {
        timeLeftSec <= 60 -> MaterialTheme.colorScheme.error
        timeLeftSec <= 300 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurface
    }
    AnimatedContent(
        targetState = timeLeftSec,
        transitionSpec = {
            if (animationsEnabled) {
                (slideInVertically { -it / 2 } + fadeIn()) togetherWith
                    (slideOutVertically { it / 2 } + fadeOut())
            } else {
                fadeIn() togetherWith fadeOut()
            }
        },
        label = "exam-clock",
    ) { left ->
        Text(
            text = "${formatClock(left)}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

private fun formatClock(totalSec: Int): String {
    val m = totalSec / 60
    val s = totalSec % 60
    return "剩余 %02d:%02d".format(m, s)
}

private fun vibrateLight(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
