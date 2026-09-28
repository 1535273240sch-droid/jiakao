package com.me.jiakao.ui.screens.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.me.jiakao.core.media.MediaViewer
import com.me.jiakao.core.model.MediaRef
import com.me.jiakao.core.model.Question
import com.me.jiakao.ui.components.EmptyState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 答题页(核心):HorizontalPager 左右滑题 + 即时反馈 + 答题卡 + 收藏 + 进度恢复 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeScreen(
    onBack: () -> Unit,
    viewModel: PracticeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var viewer by remember { mutableStateOf<ViewerState?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (state.questions.isEmpty()) state.title
                        else "${state.title} · ${state.index + 1}/${state.questions.size}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::toggleRecite,
                        modifier = Modifier.semantics {
                            contentDescription = if (state.reciteMode) "退出背题模式" else "进入背题模式"
                        },
                    ) {
                        Icon(
                            Icons.Filled.MenuBook,
                            contentDescription = null,
                            tint = if (state.reciteMode) {
                                MaterialTheme.colorScheme.secondary
                            } else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.isLoading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.questions.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { EmptyState(Icons.Filled.GridView, "这里还没有题目") }

            else -> PracticeContent(
                state = state,
                viewModel = viewModel,
                padding = padding,
                onOpenMedia = { refs, index -> viewer = ViewerState(refs, index) },
            )
        }
    }

    viewer?.let { v ->
        MediaViewer(refs = v.refs, startIndex = v.startIndex, onDismiss = { viewer = null })
    }
}

@Composable
private fun PracticeContent(
    state: PracticeUiState,
    viewModel: PracticeViewModel,
    padding: androidx.compose.foundation.layout.PaddingValues,
    onOpenMedia: (List<MediaRef>, Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = state.index,
        pageCount = { state.questions.size },
    )
    var showAnswerCard by remember { mutableStateOf(false) }

    // 底部栏的翻页状态直接从 pagerState 派生,避免经 VM 回流造成多余重组
    val currentIndex by remember { androidx.compose.runtime.derivedStateOf { pagerState.currentPage } }

    // 滑动 → 同步索引 / 存档 / 预取
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { viewModel.onIndexChange(it) }
    }

    Column(Modifier.fillMaxSize().padding(padding)) {
        LinearProgressIndicator(
            progress = { state.answeredCount.toFloat() / state.questions.size },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
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
                    result = state.results[question.id],
                    revealed = question.id in state.revealed,
                    reciteMode = state.reciteMode,
                    pendingKeys = state.pending[question.id] ?: emptySet(),
                    onChoose = viewModel::choose,
                    onConfirmMulti = viewModel::confirmMulti,
                    onOpenMedia = { mediaIndex -> onOpenMedia(question.media, mediaIndex) },
                )
            }
        }
        PracticeBottomBar(
            canPrev = currentIndex > 0,
            canNext = currentIndex < state.questions.lastIndex,
            isFavorite = state.questions.getOrNull(currentIndex)?.id in state.favorites,
            onPrev = { scope.launch { pagerState.animateScrollToPage(currentIndex - 1) } },
            onNext = { scope.launch { pagerState.animateScrollToPage(currentIndex + 1) } },
            onAnswerCard = { showAnswerCard = true },
            onToggleFavorite = { state.questions.getOrNull(currentIndex)?.let { viewModel.toggleFavorite(it) } },
        )
    }

    if (showAnswerCard) {
        AnswerCardSheet(
            questions = state.questions,
            results = state.results,
            currentIndex = currentIndex,
            onJump = { index ->
                showAnswerCard = false
                scope.launch { pagerState.animateScrollToPage(index) }
            },
            onDismiss = { showAnswerCard = false },
        )
    }
}

/** 底部操作栏:上一题 / 答题卡 / 收藏 / 下一题 */
@Composable
private fun PracticeBottomBar(
    canPrev: Boolean,
    canNext: Boolean,
    isFavorite: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onAnswerCard: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BarAction(
                icon = Icons.AutoMirrored.Filled.NavigateBefore,
                label = "上一题",
                enabled = canPrev,
                onClick = onPrev,
            )
            FilledTonalButton(onClick = onAnswerCard) {
                Icon(Icons.Filled.GridView, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("答题卡", modifier = Modifier.padding(start = 6.dp))
            }
            BarAction(
                icon = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                label = "收藏",
                enabled = true,
                tint = if (isFavorite) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onToggleFavorite,
            )
            BarAction(
                icon = Icons.AutoMirrored.Filled.NavigateNext,
                label = "下一题",
                enabled = canNext,
                onClick = onNext,
            )
        }
    }
}

@Composable
private fun BarAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = if (enabled) tint else tint.copy(alpha = 0.38f))
        }
    }
}

private data class ViewerState(val refs: List<MediaRef>, val startIndex: Int)
