package com.me.jiakao.core.media.internal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs

/**
 * 全屏查看器的手势状态机：缩放（1x–5x）、拖拽平移、双击放大/还原、下拉关闭。
 *
 * 抽成纯状态类的理由：手势本身很难稳定地做 UI 断言，但"缩放如何收敛、平移如何被夹在可视范围内、
 * 下拉到什么程度算关闭"这些**规则**是可以用单元测试钉死的。Compose 里只剩"把事件喂进来"。
 *
 * 坐标系约定：[offset] 是内容层的平移量（`graphicsLayer.translation` 的单位，会被缩放放大），
 * 因此手势位移要先除以当前 [scale] 才是"肉眼跟手"的平移量。
 */
internal class ViewerGestureState(
    private val minScale: Float = MIN_SCALE,
    private val maxScale: Float = MAX_SCALE,
    private val doubleTapScale: Float = DOUBLE_TAP_SCALE,
    /** 下拉关闭阈值（像素），由调用方按密度换算后传入。 */
    private val dismissThresholdPx: Float = DEFAULT_DISMISS_THRESHOLD_PX,
) {

    var scale by mutableFloatStateOf(minScale)
        private set

    var offset by mutableStateOf(Offset.Zero)
        private set

    /** 下拉位移（像素，正值向下）；用于跟手动画与关闭判定。 */
    var dismissDragPx by mutableFloatStateOf(0f)
        private set

    /** 当前是否处于放大状态。 */
    val isZoomed: Boolean get() = scale > minScale + ZOOM_EPSILON

    /** 下拉进度 0..1，1 表示达到关闭阈值。 */
    val dismissProgress: Float get() = (abs(dismissDragPx) / dismissThresholdPx).coerceIn(0f, 1f)

    /** 双指缩放/平移（来自 `calculateZoom` / `calculatePan`）。 */
    fun onTransform(zoomChange: Float, panChange: Offset, viewport: Size) {
        val newScale = (scale * zoomChange).coerceIn(minScale, maxScale)
        val pan = if (newScale > minScale) panChange / newScale else panChange
        scale = newScale
        offset = clampToViewport(offset + pan, newScale, viewport)
    }

    /** 单指平移（仅在放大后生效）。 */
    fun onPan(delta: Offset, viewport: Size) {
        if (!isZoomed) return
        offset = clampToViewport(offset + delta / scale, scale, viewport)
    }

    /** 双击：已放大则还原，否则放大到 [doubleTapScale] 并以双击点为中心。 */
    fun onDoubleTap(position: Offset, viewport: Size) {
        if (isZoomed) {
            scale = minScale
            offset = Offset.Zero
            return
        }
        val target = doubleTapScale.coerceIn(minScale, maxScale)
        val focus = Offset(
            x = (viewport.width / 2f - position.x) * (target - minScale),
            y = (viewport.height / 2f - position.y) * (target - minScale),
        )
        scale = target
        offset = clampToViewport(focus, target, viewport)
    }

    /** 下拉关闭：放大状态下不参与（那是平移），上拉带阻尼，下拉 1:1 跟手。 */
    fun onDismissDrag(deltaY: Float) {
        if (isZoomed) return
        val resistance = if (deltaY < 0f) UP_RESISTANCE else 1f
        dismissDragPx += deltaY * resistance
    }

    /** 松手时是否达到关闭阈值（上拉不算）。 */
    fun shouldDismiss(): Boolean = dismissDragPx > dismissThresholdPx

    /** 松手但没到阈值：回弹。 */
    fun settleBack() {
        dismissDragPx = 0f
    }

    /** 关闭动画的起点。 */
    fun dismissTarget(viewportHeight: Float): Float = if (dismissDragPx >= 0f) viewportHeight else -viewportHeight

    /** 复位（切换页面时调用）。 */
    fun reset() {
        scale = minScale
        offset = Offset.Zero
        dismissDragPx = 0f
    }

    private fun clampToViewport(candidate: Offset, scale: Float, viewport: Size): Offset {
        val maxX = ((scale - minScale) * viewport.width / 2f).coerceAtLeast(0f)
        val maxY = ((scale - minScale) * viewport.height / 2f).coerceAtLeast(0f)
        return Offset(
            x = candidate.x.coerceIn(-maxX, maxX),
            y = candidate.y.coerceIn(-maxY, maxY),
        )
    }

    companion object {
        const val MIN_SCALE: Float = 1f
        const val MAX_SCALE: Float = 5f
        const val DOUBLE_TAP_SCALE: Float = 2.5f
        const val DEFAULT_DISMISS_THRESHOLD_PX: Float = 220f

        private const val ZOOM_EPSILON = 0.01f
        private const val UP_RESISTANCE = 0.35f
    }
}
