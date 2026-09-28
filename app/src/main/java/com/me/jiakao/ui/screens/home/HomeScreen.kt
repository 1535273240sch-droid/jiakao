package com.me.jiakao.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.me.jiakao.core.model.Progress
import com.me.jiakao.core.model.UpdateState
import com.me.jiakao.ui.anim.rememberAnimationsEnabled
import com.me.jiakao.ui.components.EntryCard
import com.me.jiakao.ui.components.ProgressRing
import com.me.jiakao.ui.components.SegmentedControl

/** 首页:科目切换 + 学习进度环 + 全部入口卡片 + 题库版本/检查更新 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenPractice: (mode: String, subject: Int, chapterId: String?, special: String?) -> Unit,
    onOpenChapters: (subject: Int) -> Unit,
    onOpenExam: (subject: Int) -> Unit,
    onOpenWrongBook: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSpecialPicker by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("驾考通", style = MaterialTheme.typography.titleLarge) },
            actions = {
                IconButton(onClick = onOpenSearch) { Icon(Icons.Filled.Search, contentDescription = "搜索题目") }
                IconButton(onClick = onOpenStats) { Icon(Icons.Filled.BarChart, contentDescription = "学习统计") }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "设置") }
            },
        )

        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "subject", span = { GridItemSpan(2) }, contentType = "header") {
                SubjectSwitcher(
                    subject = state.subject,
                    onSwitch = viewModel::setSubject,
                )
            }
            item(key = "progress", span = { GridItemSpan(2) }, contentType = "header") {
                ProgressCard(state)
            }
            items(HOME_ENTRIES, key = { it.title }, contentType = { "entry" }) { entry ->
                EntryCard(
                    icon = entry.icon,
                    title = entry.title,
                    subtitle = entry.subtitle(state),
                    badge = entry.badge(state),
                    highlighted = entry.highlighted,
                    onClick = { entry.onClicked(state, onOpenPractice, onOpenChapters, onOpenExam, onOpenWrongBook, onOpenFavorites) { showSpecialPicker = true } },
                )
            }
            item(key = "bank", span = { GridItemSpan(2) }, contentType = "header") {
                BankVersionCard(state, onCheckUpdate = viewModel::checkUpdate)
            }
        }
    }

    if (showSpecialPicker) {
        SpecialPickerDialog(
            onDismiss = { showSpecialPicker = false },
            onPick = { special ->
                showSpecialPicker = false
                onOpenPractice("SPECIAL", state.subject, null, special)
            },
        )
    }
}

/** 科目一/科目四切换:淡入 + 轻位移动画(尊重系统动画开关) */
@Composable
private fun SubjectSwitcher(subject: Int, onSwitch: (Int) -> Unit) {
    val animationsEnabled = rememberAnimationsEnabled()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SegmentedControl(
            options = listOf(1, 4),
            selected = subject,
            onSelected = onSwitch,
            label = { if (it == 1) "科目一" else "科目四" },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    // 环形进度等下级内容随 AnimatedContent 由 ProgressCard 自身刷新;
    // 这里对切换动作做一次轻量视觉确认(仅动画开启时)
    AnimatedContent(
        targetState = subject,
        transitionSpec = {
            if (animationsEnabled) {
                (slideInHorizontally { if (targetState > initialState) it / 24 else -it / 24 } + fadeIn()) togetherWith
                    (slideOutHorizontally { if (targetState > initialState) -it / 24 else it / 24 } + fadeOut())
            } else {
                fadeIn() togetherWith fadeOut()
            }
        },
        label = "subject-confirm",
        modifier = Modifier.fillMaxWidth(),
    ) { current ->
        Text(
            text = if (current == 1) "道路交通安全法律法规 · 通用知识" else "安全文明驾驶常识",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun ProgressCard(state: HomeUiState) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val progress = state.progress
            ProgressRing(
                progress = progress?.fraction ?: 0f,
                modifier = Modifier.size(84.dp),
            ) {
                Text(
                    text = progress?.percent ?: "0%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (state.subject == 1) "科目一 · 学习进度" else "科目四 · 学习进度",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (progress != null) "已刷 ${progress.done}/${progress.total} 题 · 正确率 ${progress.accuracy}" else "暂无数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "含图 ${state.counts?.withMedia ?: 0} 题 · 动图 ${state.counts?.withAnim ?: 0} 题",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val Progress.fraction: Float
    get() = if (total > 0) done.toFloat() / total else 0f

private val Progress.percent: String
    get() = if (total > 0) "${done * 100 / total}%" else "0%"

private val Progress.accuracy: String
    get() = if (done > 0) "${correct * 100 / done}%" else "--"

private data class HomeEntry(
    val icon: ImageVector,
    val title: String,
    val highlighted: Boolean = false,
    val subtitle: (HomeUiState) -> String,
    val badge: (HomeUiState) -> Int? = { null },
    val onClicked: (
        HomeUiState,
        (String, Int, String?, String?) -> Unit,
        (Int) -> Unit,
        (Int) -> Unit,
        () -> Unit,
        () -> Unit,
        () -> Unit,
    ) -> Unit,
)

private val HOME_ENTRIES = listOf(
    HomeEntry(
        Icons.AutoMirrored.Filled.MenuBook,
        "顺序练习",
        subtitle = { st -> "共 ${st.counts?.total ?: 0} 题" },
        onClicked = { st, practice, _, _, _, _, _ -> practice("SEQUENTIAL", st.subject, null, null) },
    ),
    HomeEntry(
        Icons.Filled.Shuffle,
        "随机练习",
        subtitle = { "随机顺序刷题" },
        onClicked = { st, practice, _, _, _, _, _ -> practice("RANDOM", st.subject, null, null) },
    ),
    HomeEntry(
        Icons.Filled.GridView,
        "章节练习",
        subtitle = { st -> "${st.chapterCount} 个章节" },
        onClicked = { st, _, chapters, _, _, _, _ -> chapters(st.subject) },
    ),
    HomeEntry(
        Icons.Filled.Category,
        "专项练习",
        subtitle = { "有图 / 动图 / 判断 / 单选 / 多选" },
        onClicked = { _, _, _, _, _, _, showPicker -> showPicker() },
    ),
    HomeEntry(
        Icons.Filled.Timer,
        "模拟考试",
        highlighted = true,
        subtitle = { st -> if (st.subject == 1) "100 题 · 45 分钟" else "50 题 · 30 分钟" },
        onClicked = { st, _, _, exam, _, _, _ -> exam(st.subject) },
    ),
    HomeEntry(
        Icons.Filled.ErrorOutline,
        "错题本",
        subtitle = { st -> if (st.wrongCount > 0) "${st.wrongCount} 题待攻克" else "暂无错题" },
        badge = { st -> st.wrongCount },
        onClicked = { _, _, _, _, wrong, _, _ -> wrong() },
    ),
    HomeEntry(
        Icons.Filled.Star,
        "我的收藏",
        subtitle = { st -> if (st.favoriteCount > 0) "已收藏 ${st.favoriteCount} 题" else "收藏好题随时看" },
        onClicked = { _, _, _, _, _, fav, _ -> fav() },
    ),
)

@Composable
private fun BankVersionCard(state: HomeUiState, onCheckUpdate: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when (state.updateState) {
                    is UpdateState.Failed -> Icons.Filled.ErrorOutline
                    is UpdateState.Available -> Icons.Filled.CloudDownload
                    else -> Icons.Filled.CloudDone
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = updateSummary(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCheckUpdate) { Text("检查更新") }
        }
    }
}

private fun updateSummary(state: HomeUiState): String = when (val u = state.updateState) {
    is UpdateState.Available -> "发现新版本 v${u.toVersion},${formatBytes(u.downloadBytes)}"
    is UpdateState.UpToDate -> "题库 v${u.version} 已是最新"
    is UpdateState.Checking -> "正在检查更新…"
    is UpdateState.Failed -> "检查失败:${u.message}"
    else -> "题库版本 v${state.bankVersion} · 共 ${state.counts?.total ?: 0} 题"
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576f)
    bytes >= 1 shl 10 -> "%.0f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

@Composable
private fun SpecialPickerDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("专项练习") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "media" to "有图题",
                    "anim" to "动图题",
                    "judge" to "判断题",
                    "single" to "单选题",
                    "multi" to "多选题",
                ).forEach { (key, label) ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        onClick = { onPick(key) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
