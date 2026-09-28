package com.me.jiakao.ui.screens.exam

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.SentimentDissatisfied
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.me.jiakao.core.model.ExamResult
import com.me.jiakao.core.model.Question
import com.me.jiakao.ui.anim.ConfettiOverlay
import com.me.jiakao.ui.anim.animateEntrance
import com.me.jiakao.ui.anim.rememberAnimationsEnabled
import com.me.jiakao.ui.components.EmptyState

/** 成绩页:分数环形缓动动画、合格撒花 / 不合格鼓励、错题回顾 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamResultScreen(
    examId: Long,
    onBack: () -> Unit,
    onReviewWrong: (subject: Int) -> Unit,
    onBackHome: () -> Unit,
    viewModel: ExamResultViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("考试成绩", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val result = state?.result
        if (result == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Filled.EmojiEvents, "成绩加载中…")
            }
        } else {
            ResultContent(
                result = result,
                wrongQuestions = state!!.wrongQuestions,
                padding = padding,
                onReviewWrong = { onReviewWrong(result.subject) },
                onBackHome = onBackHome,
            )
        }
    }
}

@Composable
private fun ResultContent(
    result: ExamResult,
    wrongQuestions: List<Question>,
    padding: androidx.compose.foundation.layout.PaddingValues,
    onReviewWrong: () -> Unit,
    onBackHome: () -> Unit,
) {
    var confettiDone by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "score") { ScoreBlock(result) }
            item(key = "stats") { StatsRow(result) }
            item(key = "actions") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onReviewWrong, modifier = Modifier.fillMaxWidth()) {
                        Text(if (result.wrongIds.isEmpty()) "返回首页" else "错题一键加入复习")
                    }
                    if (result.wrongIds.isNotEmpty()) {
                        OutlinedButton(onClick = onBackHome, modifier = Modifier.fillMaxWidth()) {
                            Text("返回首页")
                        }
                    }
                }
            }
            if (wrongQuestions.isNotEmpty()) {
                item(key = "wrong-header") {
                    Text(
                        "错题回顾(${wrongQuestions.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                items(wrongQuestions, key = { it.id }, contentType = { "wrong" }) { q ->
                    WrongReviewCard(q)
                }
            }
        }
        // 合格撒花(≤60 粒子,自动释放)
        if (result.passed && !confettiDone) {
            ConfettiOverlay(
                modifier = Modifier.fillMaxSize(),
                onFinished = { confettiDone = true },
            )
        }
    }
}

@Composable
private fun ScoreBlock(result: ExamResult) {
    val animationsEnabled = rememberAnimationsEnabled()
    // 分数 0 → score 缓动
    val scoreAnim = remember { Animatable(0f) }
    LaunchedEffect(result.score) {
        if (animationsEnabled) {
            scoreAnim.snapTo(0f)
            scoreAnim.animateTo(result.score.toFloat(), tween(1200, easing = FastOutSlowInEasing))
        } else {
            scoreAnim.snapTo(result.score.toFloat())
        }
    }
    // 环形进度随分数增长
    val ringProgress = (scoreAnim.value / 100f).coerceIn(0f, 1f)
    val ringTrackColor = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.15f)
    val ringColor = if (result.passed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    val scoreTextColor = if (result.passed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 16.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = ringTrackColor,
                    startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    topLeft = Offset(inset, inset), size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = ringColor,
                    startAngle = -90f, sweepAngle = 360f * ringProgress, useCenter = false,
                    topLeft = Offset(inset, inset), size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${scoreAnim.value.toInt()}",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black,
                    color = scoreTextColor,
                )
                Text("满分 100", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        PassFailLabel(passed = result.passed)
    }
}

@Composable
private fun PassFailLabel(passed: Boolean) {
    val animationsEnabled = rememberAnimationsEnabled()
    if (passed) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Text(
                "恭喜通过,一把过!",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    } else {
        // 不合格:轻柔呼吸鼓励动画
        val pulse = if (animationsEnabled) {
            val transition = rememberInfiniteTransition(label = "encourage")
            val v by transition.animateFloat(
                initialValue = 0.86f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                label = "encourage-alpha",
            )
            v
        } else 1f
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.graphicsLayerAlpha(pulse),
        ) {
            Icon(Icons.Filled.SentimentDissatisfied, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Text(
                "还差一点,再练练一定过!",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun Modifier.graphicsLayerAlpha(alpha: Float): Modifier =
    this.graphicsLayer(alpha = alpha)

@Composable
private fun StatsRow(result: ExamResult) {
    val accuracy = if (result.questionIds.isEmpty()) 0 else
        ((result.questionIds.size - result.wrongIds.size) * 100 / result.questionIds.size)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatCell("用时", formatDuration(result.durationSec), Modifier.weight(1f))
        StatCell("答对", "${result.questionIds.size - result.wrongIds.size} 题", Modifier.weight(1f))
        StatCell("正确率", "$accuracy%", Modifier.weight(1f))
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WrongReviewCard(question: Question) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(question.stem, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                "正确答案:${question.answer.sorted().joinToString(" ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            if (question.explain.isNotBlank()) {
                Text(
                    question.explain,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatDuration(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return if (m > 0) "${m}分${s}秒" else "${s}秒"
}
