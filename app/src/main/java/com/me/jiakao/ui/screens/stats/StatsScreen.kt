package com.me.jiakao.ui.screens.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.me.jiakao.core.model.ChapterStat
import com.me.jiakao.core.model.ExamResult
import com.me.jiakao.ui.components.ProgressRing
import com.me.jiakao.ui.components.SectionTitle

/** 统计页:进度环 + 正确率 + 章节掌握度条形图(Canvas 自绘) + 历史考试折线(Canvas 自绘) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("学习统计", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "overview", contentType = "card") { OverviewCard(state) }
            item(key = "chapter-title", contentType = "header") { SectionTitle("章节掌握度") }
            items(state.chapterStats, key = { it.chapter.id }, contentType = { "chapter" }) { cs ->
                ChapterBar(cs)
            }
            item(key = "history", contentType = "card") { HistoryCard(state.exams, state.passScore) }
        }
    }
}

@Composable
private fun OverviewCard(state: StatsUiState) {
    val progress = state.progress
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProgressRing(
                progress = progress?.let { if (it.total > 0) it.done.toFloat() / it.total else 0f } ?: 0f,
                modifier = Modifier.size(88.dp),
            ) {
                Text(
                    progress?.let { if (it.total > 0) "${it.done * 100 / it.total}%" else "0%" } ?: "0%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (state.subject == 1) "科目一" else "科目四", style = MaterialTheme.typography.titleMedium)
                Text(
                    progress?.let { "已刷 ${it.done}/${it.total} 题" } ?: "暂无数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    progress?.let {
                        if (it.done > 0) "正确率 ${it.correct * 100 / it.done}%" else "正确率 --"
                    } ?: "正确率 --",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 单章节掌握度条:Canvas 自绘圆角条形,入场宽度动画 */
@Composable
private fun ChapterBar(stat: ChapterStat) {
    var played by remember(stat.chapter.id) { mutableStateOf(false) }
    LaunchedEffect(Unit) { played = true }
    val mastery = if (stat.total > 0) stat.correct.toFloat() / stat.total else 0f
    val doneFraction = if (stat.total > 0) stat.done.toFloat() / stat.total else 0f
    val animatedDone by animateFloatAsState(
        targetValue = if (played) doneFraction else 0f,
        animationSpec = tween(650),
        label = "chapter-done",
    )
    val animatedMastery by animateFloatAsState(
        targetValue = if (played) mastery else 0f,
        animationSpec = tween(650),
        label = "chapter-mastery",
    )
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val doneColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val masteryColor = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stat.chapter.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${stat.correct}/${stat.total}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            val radius = size.height / 2
            drawRoundRect(color = trackColor, cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius))
            if (animatedDone > 0f) {
                drawRoundRect(
                    color = doneColor,
                    size = androidx.compose.ui.geometry.Size(size.width * animatedDone, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                )
            }
            if (animatedMastery > 0f) {
                drawRoundRect(
                    color = masteryColor,
                    size = androidx.compose.ui.geometry.Size(size.width * animatedMastery, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                )
            }
        }
    }
}

/** 历史考试折线(Canvas 自绘,合格线参考) */
@Composable
private fun HistoryCard(exams: List<ExamResult>, passScore: Int) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("模拟考试成绩", style = MaterialTheme.typography.titleMedium)
            if (exams.size < 2) {
                Text(
                    if (exams.isEmpty()) "还没有考试记录,先来一场模拟考试吧" else "再考一场即可查看趋势",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val recent = exams.takeLast(12)
                val lineColor = MaterialTheme.colorScheme.primary
                val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                val passColor = MaterialTheme.colorScheme.tertiary
                val pointColor = MaterialTheme.colorScheme.secondary
                Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                    val left = 8.dp.toPx()
                    val right = size.width - 8.dp.toPx()
                    val top = 8.dp.toPx()
                    val bottom = size.height - 20.dp.toPx()
                    val width = right - left
                    val height = bottom - top
                    // 网格:0/50/100
                    listOf(0f, 0.5f, 1f).forEach { f ->
                        val y = bottom - height * f
                        drawLine(gridColor, Offset(left, y), Offset(right, y), 1.5f)
                    }
                    // 合格线
                    val passY = bottom - height * (passScore / 100f)
                    drawLine(passColor, Offset(left, passY), Offset(right, passY), 2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                    // 分数折线
                    val stepX = width / (recent.size - 1)
                    val points = recent.mapIndexed { i, e ->
                        Offset(left + stepX * i, bottom - height * (e.score / 100f).coerceIn(0f, 1f))
                    }
                    for (i in 0 until points.size - 1) {
                        drawLine(lineColor, points[i], points[i + 1], 3f, cap = StrokeCap.Round)
                    }
                    points.forEach { p ->
                        drawCircle(pointColor, radius = 5f, center = p)
                        drawCircle(androidx.compose.ui.graphics.Color.White, radius = 2.2f, center = p)
                    }
                }
                Text(
                    "最近 ${recent.size} 场 · 平均 ${(recent.sumOf { it.score } / recent.size)} 分 · 合格线 $passScore",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
