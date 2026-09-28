package com.me.jiakao.ui.anim

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 尊重系统「移除动画」设置:动画时长缩放为 0 时返回 false,
 * 所有自定义动效据此降级为静态展示。
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
}

/**
 * 答错时的水平抖动:衰减震荡序列,总时长约 320ms。
 */
suspend fun Animatable<Float, *>.shake() {
    listOf(0f, -14f, 12f, -9f, 6f, -3f, 0f).forEach { offset ->
        animateTo(offset, tween(45, easing = LinearEasing))
    }
}

/** 在 [DrawScope] 内绘制随 [progress] 描边的「对勾」路径 */
fun DrawScope.drawCheck(progress: Float, color: Color, strokeWidth: Float) {
    if (progress <= 0f) return
    val path = Path().apply {
        moveTo(size.width * 0.20f, size.height * 0.52f)
        lineTo(size.width * 0.42f, size.height * 0.74f)
        lineTo(size.width * 0.82f, size.height * 0.28f)
    }
    val measure = PathMeasure().apply { setPath(path, false) }
    val dst = Path()
    measure.getSegment(0f, measure.length * progress.coerceIn(0f, 1f), dst, true)
    drawPath(
        dst,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private data class ConfettiParticle(
    val startX: Float,      // 0..1 相对宽度
    val startY: Float,      // 0..1 相对高度
    val angle: Float,       // 发射角
    val speed: Float,       // 相对速度
    val radius: Float,
    val color: Color,
    val shape: Int,         // 0 圆形 1 方块
    val rotationSpeed: Float,
)

private val CONFETTI_COLORS = listOf(
    Color(0xFFE8641B), Color(0xFF0E5FD8), Color(0xFF218A4B),
    Color(0xFFF2B705), Color(0xFFD94A8C), Color(0xFF7A5CD8),
)

/**
 * 合格撒花:Canvas 粒子(≤ 60),从画面中部向上抛洒后受重力下落;
 * 动画结束回调 [onFinished] 自动移除覆盖层并释放状态。
 */
@Composable
fun ConfettiOverlay(
    modifier: Modifier = Modifier,
    particleCount: Int = 60,
    durationMs: Int = 2600,
    onFinished: () -> Unit = {},
) {
    val enabled = rememberAnimationsEnabled()
    val particles = remember {
        List(particleCount.coerceAtMost(60)) {
            ConfettiParticle(
                startX = 0.5f + Random.nextFloat() * 0.3f - 0.15f,
                startY = 0.55f,
                angle = (-110f + Random.nextFloat() * 40f),
                speed = 0.35f + Random.nextFloat() * 0.5f,
                radius = 4f + Random.nextFloat() * 6f,
                color = CONFETTI_COLORS[Random.nextInt(CONFETTI_COLORS.size)],
                shape = if (Random.nextBoolean()) 1 else 0,
                rotationSpeed = Random.nextFloat() * 720f - 360f,
            )
        }
    }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(enabled) {
        if (!enabled) {
            onFinished()
            return@LaunchedEffect
        }
        progress.animateTo(1f, tween(durationMs, easing = LinearEasing))
        onFinished()
    }
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val t = progress.value
        if (t <= 0f || t >= 1f) return@Canvas
        val seconds = t * 2.6f
        val g = size.height * 0.9f // 重力加速度(px/s²)
        particles.forEach { p ->
            val rad = Math.toRadians(p.angle.toDouble())
            val vx = (cos(rad) * p.speed * size.width * 0.6f).toFloat()
            val vy = (sin(rad) * p.speed * size.height * 0.6f).toFloat()
            val x = p.startX * size.width + vx * seconds
            val y = p.startY * size.height + vy * seconds + 0.5f * g * seconds * seconds
            if (y < size.height + p.radius * 2) {
                val alpha = (1f - t).coerceIn(0f, 1f)
                if (p.shape == 0) {
                    drawCircle(color = p.color.copy(alpha = alpha), radius = p.radius, center = Offset(x, y))
                } else {
                    val half = p.radius * 0.8f
                    drawRect(
                        color = p.color.copy(alpha = alpha),
                        topLeft = Offset(x - half, y - half),
                        size = androidx.compose.ui.geometry.Size(half * 2, half * 2),
                    )
                }
            }
        }
    }
}

/** 通用入场动画:0 → [target] 缓动(EaseOutCubic),禁用动画时直接到 [target] */
@Composable
fun animateEntrance(target: Float, durationMs: Int = 700): Float {
    val enabled = rememberAnimationsEnabled()
    val anim = remember { Animatable(0f) }
    LaunchedEffect(target, enabled) {
        if (!enabled) {
            anim.snapTo(target)
        } else {
            anim.snapTo(0f)
            anim.animateTo(target, tween(durationMs, easing = EaseOutCubic))
        }
    }
    return anim.value
}
