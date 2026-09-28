package com.me.jiakao.ui.screens.practice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.me.jiakao.core.model.Option
import com.me.jiakao.core.model.QType
import com.me.jiakao.core.model.Question
import com.me.jiakao.ui.anim.drawCheck
import com.me.jiakao.ui.anim.rememberAnimationsEnabled
import com.me.jiakao.ui.anim.shake
import com.me.jiakao.ui.components.QuizMediaHost

/**
 * 单个题目页:题干 + 媒体 + 选项卡 + 解析。
 * 动效:选项弹性缩放;答对「对勾描边」+ 轻触感;答错水平抖动 + 红色闪烁 + 展开解析;
 * 背题模式直接高亮正确答案。
 */
@Composable
fun QuestionPage(
    question: Question,
    chosen: List<String>?,
    result: Boolean?,
    revealed: Boolean,
    reciteMode: Boolean,
    pendingKeys: Set<String>,
    modifier: Modifier = Modifier,
    examMode: Boolean = false,
    onChoose: (Question, String) -> Unit,
    onConfirmMulti: (Question) -> Unit,
    onOpenMedia: (mediaIndex: Int) -> Unit,
) {
    val view = LocalView.current
    val animationsEnabled = rememberAnimationsEnabled()
    val shakeAnim = remember { Animatable(0f) }
    val flashAnim = remember { Animatable(0f) }
    val checkProgress = remember { Animatable(0f) }

    LaunchedEffect(result) {
        if (examMode) return@LaunchedEffect // 考试不给对错反馈
        when (result) {
            true -> {
                checkProgress.snapTo(0f)
                if (animationsEnabled) {
                    checkProgress.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
                } else {
                    checkProgress.snapTo(1f)
                }
                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
            false -> {
                if (animationsEnabled) {
                    shakeAnim.shake()
                    // 红色闪烁两次
                    listOf(1f, 0f, 0.7f, 0f).forEach { flashAnim.animateTo(it, tween(70)) }
                }
                view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            }
            null -> Unit
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StemHeader(question)
        question.media.forEachIndexed { i, ref ->
            QuizMediaHost(
                ref = ref,
                onClick = { onOpenMedia(i) },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            question.options.forEach { option ->
                OptionRow(
                    option = option,
                    question = question,
                    chosen = chosen,
                    result = if (examMode) null else result,
                    reciteMode = reciteMode,
                    pendingSelected = if (examMode) option.key in (chosen ?: emptyList()) else option.key in pendingKeys,
                    shakeOffset = shakeAnim.value,
                    flashAlpha = flashAnim.value,
                    checkProgress = checkProgress.value,
                    enabled = if (examMode) true else (!reciteMode && chosen == null),
                    onChoose = onChoose,
                )
            }
        }
        if (question.type == QType.MULTI && chosen == null && !reciteMode && !examMode) {
            MultiConfirmBar(
                selected = pendingKeys.toList().sorted(),
                onConfirm = { onConfirmMulti(question) },
            )
        }
        ExplanationCard(
            question = question,
            visible = revealed || reciteMode,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StemHeader(question: Question) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = when (question.type) {
                QType.JUDGE -> MaterialTheme.colorScheme.secondaryContainer
                QType.SINGLE -> MaterialTheme.colorScheme.primaryContainer
                QType.MULTI -> MaterialTheme.colorScheme.tertiaryContainer
            },
        ) {
            Text(
                text = when (question.type) {
                    QType.JUDGE -> "判断"
                    QType.SINGLE -> "单选"
                    QType.MULTI -> "多选"
                },
                style = MaterialTheme.typography.labelMedium,
                color = when (question.type) {
                    QType.JUDGE -> MaterialTheme.colorScheme.onSecondaryContainer
                    QType.SINGLE -> MaterialTheme.colorScheme.onPrimaryContainer
                    QType.MULTI -> MaterialTheme.colorScheme.onTertiaryContainer
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Text(
            text = question.stem,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

@Composable
private fun OptionRow(
    option: Option,
    question: Question,
    chosen: List<String>?,
    result: Boolean?,
    reciteMode: Boolean,
    pendingSelected: Boolean,
    shakeOffset: Float,
    flashAlpha: Float,
    checkProgress: Float,
    enabled: Boolean,
    onChoose: (Question, String) -> Unit,
) {
    val isCorrectKey = option.key in question.answer
    val isChosenKey = option.key in (chosen ?: emptyList())
    val answered = chosen != null

    // 状态推导:正确键(答对/背题高亮)、错选键、普通
    val showCorrect = (answered && result == true && isChosenKey) ||
        (answered && result == false && isCorrectKey) ||
        (reciteMode && isCorrectKey)
    val showWrong = answered && result == false && isChosenKey && !isCorrectKey

    val borderColor by animateColorAsState(
        targetValue = when {
            showWrong -> MaterialTheme.colorScheme.error
            showCorrect -> MaterialTheme.colorScheme.tertiary
            pendingSelected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
        },
        animationSpec = tween(180),
        label = "opt-border",
    )
    val containerColor by animateColorAsState(
        targetValue = when {
            showWrong -> MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
            showCorrect -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
            pendingSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            else -> MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(180),
        label = "opt-bg",
    )

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val clickModifier = if (enabled) {
        Modifier.clickable(interactionSource = interaction, indication = null) { onChoose(question, option.key) }
    } else Modifier

    Box(
        modifier = Modifier
            .offsetX(shakeOffset)
            .graphicsLayer {
                val bounce = if (pendingSelected || (answered && isChosenKey)) 1.015f else 1f
                val press = if (pressed) 0.975f else 1f
                scaleX = bounce * press
                scaleY = bounce * press
            }
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(containerColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(14.dp))
            .then(clickModifier),
    ) {
        // 答错红色闪烁层
        if (flashAlpha > 0.01f && showWrong) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.error.copy(alpha = flashAlpha * 0.18f)),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = option.key,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = when {
                    showWrong -> MaterialTheme.colorScheme.error
                    showCorrect -> MaterialTheme.colorScheme.tertiary
                    pendingSelected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            showWrong -> MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                            showCorrect -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)
                            pendingSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        }
                    )
                    .wrapContentSize(Alignment.Center),
            )
            Text(
                text = option.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (showWrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (showCorrect) {
                val checkColor = MaterialTheme.colorScheme.tertiary
                Canvas(modifier = Modifier.size(22.dp)) {
                    drawCheck(
                        progress = checkProgress,
                        color = checkColor,
                        strokeWidth = 2.4.dp.toPx(),
                    )
                }
            }
            if (showWrong) {
                Text("✗", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun Modifier.offsetX(shake: Float): Modifier = this.graphicsLayer { translationX = shake }

@Composable
private fun MultiConfirmBar(selected: List<String>, onConfirm: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (selected.isEmpty()) "请选择答案(可多选)" else "已选 ${selected.joinToString("、")}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onConfirm,
            enabled = selected.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text("确认")
        }
    }
}

@Composable
private fun ExplanationCard(question: Question, visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(260)) + fadeIn(tween(260)),
        exit = shrinkVertically(tween(200)) + fadeOut(tween(180)),
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "正确答案",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        question.answer.sorted().joinToString(" "),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    if (question.tags.isNotEmpty()) {
                        Text(
                            "·  ${question.tags.joinToString(" / ")}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = question.explain.ifBlank { "本题暂无解析。" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
