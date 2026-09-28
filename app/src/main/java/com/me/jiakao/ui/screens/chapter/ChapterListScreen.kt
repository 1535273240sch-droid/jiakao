package com.me.jiakao.ui.screens.chapter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.me.jiakao.core.model.ChapterStat
import com.me.jiakao.ui.components.EmptyState

/** 章节练习:章节列表 + 各章进度条,点击进入该章练习 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListScreen(
    subject: Int,
    onBack: () -> Unit,
    onOpenPractice: (mode: String, subject: Int, chapterId: String?, special: String?) -> Unit,
    viewModel: ChapterListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (subject == 1) "科目一 · 章节练习" else "科目四 · 章节练习", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
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
            state.stats.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(Icons.AutoMirrored.Filled.KeyboardArrowRight, "暂无章节")
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.stats, key = { it.chapter.id }, contentType = { "chapter" }) { stat ->
                    ChapterCard(stat) {
                        onOpenPractice("CHAPTER", stat.chapter.subject, stat.chapter.id, null)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterCard(stat: ChapterStat, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${stat.chapter.order}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 10.dp),
                )
                Text(
                    stat.chapter.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "进入章节", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val track = MaterialTheme.colorScheme.surfaceVariant
                val fg = MaterialTheme.colorScheme.primary
                androidx.compose.foundation.Canvas(
                    Modifier
                        .weight(1f)
                        .height(8.dp),
                ) {
                    val radius = androidx.compose.ui.geometry.CornerRadius(size.height / 2, size.height / 2)
                    drawRoundRect(color = track, cornerRadius = radius)
                    val doneFraction = if (stat.total > 0) stat.done.toFloat() / stat.total else 0f
                    if (doneFraction > 0f) {
                        drawRoundRect(
                            color = fg,
                            size = androidx.compose.ui.geometry.Size(size.width * doneFraction, size.height),
                            cornerRadius = radius,
                        )
                    }
                }
                Text(
                    text = if (stat.total > 0) {
                        "${stat.done}/${stat.total}" + if (stat.done > 0) " · ${stat.correct * 100 / stat.done}%" else ""
                    } else "0/0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
