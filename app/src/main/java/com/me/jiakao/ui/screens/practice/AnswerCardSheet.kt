package com.me.jiakao.ui.screens.practice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.me.jiakao.core.model.Question

/** 答题卡状态 */
enum class CellStatus { CURRENT, CORRECT, WRONG, ANSWERED, UNANSWERED }

/**
 * 答题卡:ModalBottomSheet + 六列网格,点击任意格直接跳题。
 * 练习模式四色:当前/已对/已错/未答;考试模式三色:当前/已答/未答。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnswerCardSheet(
    questions: List<Question>,
    results: Map<String, Boolean>,
    currentIndex: Int,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
    showResults: Boolean = true,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("答题卡", style = MaterialTheme.typography.titleLarge)
            if (showResults) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Legend(MaterialTheme.colorScheme.primary, "当前")
                    Legend(MaterialTheme.colorScheme.tertiary, "已答对")
                    Legend(MaterialTheme.colorScheme.error, "已答错")
                    Legend(MaterialTheme.colorScheme.surfaceVariant, "未答")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Legend(MaterialTheme.colorScheme.primary, "当前")
                    Legend(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), "已答")
                    Legend(MaterialTheme.colorScheme.surfaceVariant, "未答")
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(
                    count = questions.size,
                    key = { it },
                    contentType = { "cell" },
                ) { index ->
                    val q = questions[index]
                    val status = when {
                        index == currentIndex -> CellStatus.CURRENT
                        showResults && results[q.id] == true -> CellStatus.CORRECT
                        showResults && results[q.id] == false -> CellStatus.WRONG
                        results.containsKey(q.id) -> CellStatus.ANSWERED
                        else -> CellStatus.UNANSWERED
                    }
                    Cell(
                        number = index + 1,
                        status = status,
                        onClick = { onJump(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Cell(number: Int, status: CellStatus, onClick: () -> Unit) {
    val bg = when (status) {
        CellStatus.CURRENT -> MaterialTheme.colorScheme.primary
        CellStatus.CORRECT -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
        CellStatus.WRONG -> MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
        CellStatus.ANSWERED -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        CellStatus.UNANSWERED -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when (status) {
        CellStatus.CURRENT -> MaterialTheme.colorScheme.onPrimary
        CellStatus.CORRECT, CellStatus.WRONG, CellStatus.ANSWERED -> Color.White
        CellStatus.UNANSWERED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .then(
                if (status == CellStatus.CURRENT) {
                    Modifier.border(2.5.dp, MaterialTheme.colorScheme.secondary, CircleShape)
                } else Modifier
            )
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = fg,
        )
    }
}
