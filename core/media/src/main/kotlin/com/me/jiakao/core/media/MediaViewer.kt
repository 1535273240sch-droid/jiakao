package com.me.jiakao.core.media

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.me.jiakao.core.media.internal.LocalAnimPlayOverride
import com.me.jiakao.core.media.internal.MediaTestTags
import com.me.jiakao.core.media.internal.ViewerGestureState
import com.me.jiakao.core.model.MediaRef
import kotlin.math.abs

/**
 * 全屏媒体查看器（合同 §5 固定签名）：`HorizontalPager` + 双指缩放（1x–5x）+ 双击放大/还原
 * + 放大后拖拽平移 + 下拉关闭（跟手，松手回弹或关闭）+ 动图单击暂停/播放 + `BackHandler` 关闭。
 *
 * 手势分工（这样划分是为了让三种手势互不抢事件）：
 * - **变换 / 下拉关闭**：自定义手势循环跑在 `PointerEventPass.Initial`（父节点先拿到事件），
 *   处理双指缩放、放大后的单指平移、未放大时的单指下拉；它消费掉自己处理的移动事件，
 *   于是左右翻页与点击都不会被误触发；
 * - **点击**：`detectTapGestures` 跑在默认的 Main 阶段，只看得到没被上面消费掉的"点"：
 *   单击切换动图暂停/播放，双击缩放；
 * - **翻页**：`HorizontalPager` 自己处理左右拖动（没有被上面消费）。
 *
 * 动图播放意图通过 `LocalAnimPlayOverride` 下发给 `QuizMedia`，此时 `QuizMedia` 不再自己装
 * 点击监听，避免与查看器的双击手势抢事件。
 *
 * 共享元素转场：不依赖 `SharedTransitionLayout`，但每页都用稳定的 `key`（下标）组合，
 * `02` 若要接共享转场，可以在外层包 `SharedTransitionLayout` 并按 sha256 命名。
 *
 * @param refs 媒体列表（按题目中的顺序）。
 * @param startIndex 初始页下标；越界会被夹到合法范围。
 * @param onDismiss 关闭回调（BackHandler 与下拉关闭都会调用）。
 */
@Composable
fun MediaViewer(
    refs: List<MediaRef>,
    startIndex: Int,
    onDismiss: () -> Unit,
) {
    if (refs.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, refs.lastIndex)) { refs.size }
    val density = LocalDensity.current
    val gesture = remember(density) {
        ViewerGestureState(dismissThresholdPx = with(density) { 96.dp.toPx() })
    }

    var viewport by remember { mutableStateOf(Size.Zero) }
    var animPaused by remember(refs.size) { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }

    // 拖动中直接用原始位移（严格跟手）；松手后由 animateFloatAsState 平滑回弹。
    val settledDrag by animateFloatAsState(
        targetValue = gesture.dismissDragPx,
        label = "media-viewer-dismiss",
    )
    val dismissTranslation = if (dragging) gesture.dismissDragPx else settledDrag

    // 翻页落定后复位缩放/平移，避免把上一页的放大状态带进下一页。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) gesture.reset()
        }
    }

    BackHandler(enabled = true, onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f * (1f - 0.5f * gesture.dismissProgress)))
            .onSizeChanged { viewport = it.toSize() }
            .pointerInput(gesture, viewport) {
                transformAndDismissGestures(
                    gesture = gesture,
                    viewport = viewport,
                    onDraggingChanged = { dragging = it },
                    onDismiss = onDismiss,
                )
            }
            .pointerInput(refs.size, viewport) {
                detectTapGestures(
                    onTap = { animPaused = !animPaused },
                    onDoubleTap = { position -> gesture.onDoubleTap(position, viewport) },
                )
            }
            .testTag(MediaTestTags.VIEWER),
    ) {
        HorizontalPager(
            state = pagerState,
            key = { it },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = dismissTranslation
                    val shrink = 1f - 0.12f * gesture.dismissProgress
                    scaleX = shrink
                    scaleY = shrink
                    alpha = 1f - gesture.dismissProgress
                },
        ) { page ->
            CompositionLocalProvider(LocalAnimPlayOverride provides !animPaused) {
                QuizMedia(
                    ref = refs[page],
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = gesture.scale
                            scaleY = gesture.scale
                            translationX = gesture.offset.x
                            translationY = gesture.offset.y
                        }
                        .testTag(MediaTestTags.VIEWER_PAGE),
                    contentScale = ContentScale.Fit,
                )
            }
        }

        if (refs.size > 1) {
            Text(
                text = "${pagerState.currentPage + 1} / ${refs.size}",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            )
        }
    }
}

/**
 * 变换 + 下拉关闭的手势循环（`PointerEventPass.Initial`）。
 *
 * 手写而不用 `detectTransformGestures` + `detectVerticalDragGestures` 的组合：那两个检测器会在
 * 同一次单指拖动上互相抢消费权（变换检测器把单指拖动也当成平移吃掉），下拉关闭就永远收不到事件。
 */
private suspend fun PointerInputScope.transformAndDismissGestures(
    gesture: ViewerGestureState,
    viewport: Size,
    onDraggingChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zooming = false
        var dismissing = false

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter(PointerInputChange::pressed)
            if (pressed.isEmpty()) break

            if (pressed.size >= 2) {
                zooming = true
                dismissing = false
                gesture.onTransform(
                    zoomChange = event.calculateZoom(),
                    panChange = event.calculatePan(),
                    viewport = viewport,
                )
                event.changes.forEach(PointerInputChange::consume)
            } else {
                val change = pressed.first()
                val delta = change.positionChange()
                when {
                    gesture.isZoomed -> {
                        gesture.onPan(delta, viewport)
                        change.consume()
                    }

                    // 双指抬起后残留的那根手指：忽略，避免画面突然跳一下。
                    zooming -> Unit

                    abs(delta.y) >= abs(delta.x) -> {
                        dismissing = true
                        onDraggingChanged(true)
                        gesture.onDismissDrag(delta.y)
                        change.consume()
                    }
                }
            }
        }

        if (dismissing) {
            onDraggingChanged(false)
            if (gesture.shouldDismiss()) onDismiss() else gesture.settleBack()
        }
    }
}
