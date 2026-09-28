package com.me.jiakao.core.media.internal

import android.graphics.Rect as AndroidRect
import android.view.View
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.me.jiakao.core.model.MediaStore
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 组件测试用的语义标签；生产环境只是廉价的语义节点，不产生绘制开销。 */
internal object MediaTestTags {
    const val SKELETON = "quiz-media:skeleton"
    const val MISSING = "quiz-media:missing"
    const val IMAGE = "quiz-media:image"
    const val ANIM_BADGE = "quiz-media:anim-badge"
    const val VIDEO = "quiz-media:video"
    const val VIEWER = "media-viewer"
    const val VIEWER_PAGE = "media-viewer:page"
}

/**
 * 注入 [MediaStore] 的接缝：默认取进程内单例（`MediaStores.of`），
 * 组件测试用临时目录实现覆盖，从而完全离线、可重复。
 */
internal val LocalMediaStore = compositionLocalOf<MediaStore?> { null }

/** 取得当前生效的 [MediaStore]。 */
@Composable
internal fun rememberMediaStore(): MediaStore {
    val provided = LocalMediaStore.current
    val context = LocalContext.current
    val fallback = remember(context) { MediaStores.of(context) }
    return provided ?: fallback
}

/**
 * 用 `MediaRef.width/height` 预占位，杜绝布局跳动。
 *
 * 只在**单轴受限**时介入（典型：`fillMaxWidth()` + 高度 wrap）：
 * 由受限的那一轴按原始宽高比推算出另一轴，图片解码完成前后控件尺寸完全一致。
 * 两轴都受限（全屏查看器、固定尺寸卡片）或都不受限时不干预 —— 交给 `contentScale` 与父布局，
 * 也避免 `Modifier.aspectRatio` 在无限约束下抛异常。
 */
internal fun Modifier.reserveMediaAspect(ratio: Float?): Modifier {
    if (ratio == null || ratio <= 0f || !ratio.isFinite()) return this
    return this.layout { measurable, constraints ->
        val boundedWidth = constraints.hasBoundedWidth
        val boundedHeight = constraints.hasBoundedHeight
        val adjusted = when {
            boundedWidth && !boundedHeight -> {
                val height = (constraints.maxWidth / ratio).roundToInt().coerceIn(0, constraints.maxHeight)
                constraints.copy(minHeight = height, maxHeight = height)
            }

            boundedHeight && !boundedWidth -> {
                val width = (constraints.maxHeight * ratio).roundToInt().coerceIn(0, constraints.maxWidth)
                constraints.copy(minWidth = width, maxWidth = width)
            }

            else -> constraints
        }
        val placeable = measurable.measure(adjusted)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/** 可见性判定的最小可见面积占比。 */
private const val MIN_VISIBLE_FRACTION = 0.05f

/**
 * 上报"当前是否有 ≥ [MIN_VISIBLE_FRACTION] 的面积露出屏幕"。
 *
 * 实现：把控件在窗口坐标系里的矩形，与整个 Compose 视图在屏幕上的可见矩形求交。
 * 这样"滚出屏幕"和"被系统窗口遮挡"都能判出来。局限：滚动容器的**裁剪**发生在 Compose
 * 自己的绘制阶段（不反映在 View 层级上），所以当滚动容器没有铺满窗口时，
 * 滚出容器但仍在窗口内的元素会被判为可见。对本模块的用法（整屏 pager/列表）是准确的；
 * 这一点写在这里是为了让后来人知道边界在哪，而不是当成 bug 去"修"。
 */
@Composable
internal fun Modifier.reportVisibility(onVisibilityChanged: (Boolean) -> Unit): Modifier {
    val view = LocalView.current
    val callback by rememberUpdatedState(onVisibilityChanged)
    return this.onGloballyPositioned { coordinates ->
        callback(visibleFraction(coordinates, view) >= MIN_VISIBLE_FRACTION)
    }
}

private fun visibleFraction(coordinates: LayoutCoordinates, view: View): Float {
    if (!coordinates.isAttached) return 0f
    val bounds = coordinates.boundsInWindow()
    val width = bounds.width
    val height = bounds.height
    if (width <= 0f || height <= 0f) return 0f

    val visible = AndroidRect()
    if (!view.getGlobalVisibleRect(visible)) return 0f

    val inWindow = IntArray(2).also(view::getLocationInWindow)
    val onScreen = IntArray(2).also(view::getLocationOnScreen)
    val dx = (onScreen[0] - inWindow[0]).toFloat()
    val dy = (onScreen[1] - inWindow[1]).toFloat()

    val overlapWidth = min(bounds.right, visible.right - dx) - max(bounds.left, visible.left - dx)
    val overlapHeight = min(bounds.bottom, visible.bottom - dy) - max(bounds.top, visible.top - dy)
    if (overlapWidth <= 0f || overlapHeight <= 0f) return 0f
    return (overlapWidth * overlapHeight) / (width * height)
}

/** 精确跟踪 `Lifecycle.RESUMED`：动图只在 RESUMED 时播放（任务书 §3）。 */
@Composable
internal fun isLifecycleResumed(): Boolean {
    val owner = LocalLifecycleOwner.current
    var resumed by remember(owner) {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return resumed
}

/**
 * 骨架屏占位：深浅色自适应（底色取主题 `surfaceContainerHighest`，高光由底色向 `onSurface`
 * 混合 8% 得到，因此亮色主题更亮、暗色主题更暗）。
 *
 * [animate] 为 false 时只画纯色 —— 无限动画**只在可见时**才被组合出来，后台与屏幕外的占位
 * 屏不消耗任何动画帧。
 */
@Composable
internal fun MediaPlaceholder(
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val highlight = lerp(base, MaterialTheme.colorScheme.onSurface, 0.08f)
    Box(
        modifier = modifier
            .background(base)
            .then(if (animate) Modifier.shimmer(base = base, highlight = highlight) else Modifier),
    )
}

/**
 * 扫过式高光：无限动画只在 [MediaPlaceholder] 的 `animate == true` 分支里被组合出来，
 * 因此屏幕外/后台的占位屏不会产生任何动画帧。
 */
@Composable
private fun Modifier.shimmer(base: Color, highlight: Color): Modifier {
    val transition = rememberInfiniteTransition(label = "media-shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SHIMMER_DURATION_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "media-shimmer-progress",
    )
    return this.drawWithCache {
        val width = size.width
        val band = width * 0.6f
        val start = -band + progress * (width + band)
        val brush = Brush.linearGradient(
            colors = listOf(base, highlight, base),
            start = Offset(start, 0f),
            end = Offset(start + band, 0f),
        )
        onDrawBehind { drawRect(brush) }
    }
}

private const val SHIMMER_DURATION_MILLIS = 1_200
