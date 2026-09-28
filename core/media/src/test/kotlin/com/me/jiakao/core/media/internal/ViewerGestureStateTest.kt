package com.me.jiakao.core.media.internal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ViewerGestureState] 的单元测试：缩放区间、双击切换、平移夹取、下拉关闭判定。
 *
 * 手势本身不适合做 UI 断言，但"缩放/平移/关闭的规则"必须钉死 —— 这些规则一旦错了，
 * 表现是全屏查看器"甩飞了"或者"关不掉"，而这类 bug 在手工点击中很难稳定复现。
 */
class ViewerGestureStateTest {

    private val viewport = Size(width = 1000f, height = 2000f)

    private fun state(threshold: Float = 200f) = ViewerGestureState(dismissThresholdPx = threshold)

    @Test
    fun `初始为 1x 且未平移`() {
        val state = state()
        assertEquals(1f, state.scale, 0.0001f)
        assertEquals(Offset.Zero, state.offset)
        assertFalse(state.isZoomed)
    }

    @Test
    fun `双指缩放被夹在 1x 到 5x 之间`() {
        val state = state()

        state.onTransform(zoomChange = 3f, panChange = Offset.Zero, viewport = viewport)
        assertEquals(3f, state.scale, 0.0001f)

        state.onTransform(zoomChange = 10f, panChange = Offset.Zero, viewport = viewport)
        assertEquals(ViewerGestureState.MAX_SCALE, state.scale, 0.0001f)

        state.onTransform(zoomChange = 0.01f, panChange = Offset.Zero, viewport = viewport)
        assertEquals(ViewerGestureState.MIN_SCALE, state.scale, 0.0001f)
        assertFalse(state.isZoomed)
    }

    @Test
    fun `双击在 1x 与放大之间切换`() {
        val state = state()
        val tap = Offset(100f, 100f)

        state.onDoubleTap(tap, viewport)
        assertEquals(ViewerGestureState.DOUBLE_TAP_SCALE, state.scale, 0.0001f)
        assertTrue(state.isZoomed)

        state.onDoubleTap(tap, viewport)
        assertEquals(1f, state.scale, 0.0001f)
        assertEquals(Offset.Zero, state.offset)
    }

    @Test
    fun `双击放大以点击点为焦点并被夹在可视范围内`() {
        val state = state()
        // 点击左上角：内容应把该点移到中心附近，且偏移量不超过 (scale-1)*width/2
        state.onDoubleTap(Offset(0f, 0f), viewport)

        val maxX = (state.scale - 1f) * viewport.width / 2f
        val maxY = (state.scale - 1f) * viewport.height / 2f
        assertTrue("x 位移应在可视范围内", kotlin.math.abs(state.offset.x) <= maxX + 0.001f)
        assertTrue("y 位移应在可视范围内", kotlin.math.abs(state.offset.y) <= maxY + 0.001f)
        assertTrue("焦点在左侧，内容应向右推", state.offset.x > 0f)
    }

    @Test
    fun `未放大时单指平移被忽略`() {
        val state = state()
        state.onPan(Offset(50f, 50f), viewport)
        assertEquals(Offset.Zero, state.offset)
    }

    @Test
    fun `放大后平移跟手且被夹在边界内`() {
        val state = state()
        state.onTransform(zoomChange = 2f, panChange = Offset.Zero, viewport = viewport)

        state.onPan(Offset(10f, 10f), viewport)
        assertEquals(5f, state.offset.x, 0.0001f) // 除以 scale，肉眼跟手
        assertEquals(5f, state.offset.y, 0.0001f)

        state.onPan(Offset(10_000f, 10_000f), viewport)
        assertEquals("不能甩出边界", (2f - 1f) * viewport.width / 2f, state.offset.x, 0.001f)
        assertEquals((2f - 1f) * viewport.height / 2f, state.offset.y, 0.001f)
    }

    @Test
    fun `下拉超过阈值判定关闭`() {
        val state = state(threshold = 200f)

        state.onDismissDrag(120f)
        assertFalse(state.shouldDismiss())
        assertEquals(0.6f, state.dismissProgress, 0.001f)

        state.onDismissDrag(100f)
        assertTrue(state.shouldDismiss())
        assertEquals(1f, state.dismissProgress, 0.001f)
    }

    @Test
    fun `上拉带阻尼且永远不触发关闭`() {
        val state = state(threshold = 200f)

        state.onDismissDrag(-1000f)
        assertFalse("上拉不是关闭手势", state.shouldDismiss())
        assertEquals(-350f, state.dismissDragPx, 0.001f) // 1000 * 0.35
        assertTrue(state.dismissProgress <= 1f)
    }

    @Test
    fun `放大状态下不吃下拉关闭`() {
        val state = state()
        state.onDoubleTap(Offset(500f, 1000f), viewport)
        state.onDismissDrag(500f)
        assertEquals(0f, state.dismissDragPx, 0.001f)
        assertFalse(state.shouldDismiss())
    }

    @Test
    fun `settleBack 与 reset 分别回弹与复位`() {
        val state = state()
        state.onDismissDrag(150f)
        state.settleBack()
        assertEquals(0f, state.dismissDragPx, 0.001f)

        state.onDoubleTap(Offset(500f, 1000f), viewport)
        state.onPan(Offset(20f, 0f), viewport)
        state.onDismissDrag(50f)
        state.reset()
        assertEquals(1f, state.scale, 0.0001f)
        assertEquals(Offset.Zero, state.offset)
        assertEquals(0f, state.dismissDragPx, 0.001f)
    }
}
