package com.me.jiakao.core.media.internal

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import androidx.compose.runtime.compositionLocalOf

/**
 * 动图播放控制。
 *
 * Coil 解出来的动图 Drawable（`AnimatedImageDrawable` 被包在
 * [coil3.size.ScaleDrawable] 里，GIF 则是 `MovieDrawable`）都实现了系统的
 * [Animatable] 接口，且 `ScaleDrawable` 会把 `start/stop/isRunning` 透传给子 Drawable，
 * 所以暂停/恢复就是一次 `start()`/`stop()` —— 不需要重建请求，也不会丢当前帧。
 */
internal interface AnimPlayback {
    fun start()
    fun stop()
}

/** 无法播放时（静态图、仅首帧、未解码完）的空实现。 */
internal object NoopAnimPlayback : AnimPlayback {
    override fun start() = Unit
    override fun stop() = Unit
}

internal class DrawableAnimPlayback(private val drawable: Drawable?) : AnimPlayback {
    override fun start() {
        val animatable = drawable as? Animatable ?: return
        if (!animatable.isRunning) animatable.start()
    }

    override fun stop() {
        val animatable = drawable as? Animatable ?: return
        if (animatable.isRunning) animatable.stop()
    }
}

/** 播放控制器工厂：测试注入假实现来观察 start/stop 调用。 */
internal fun interface AnimPlaybackFactory {
    fun create(drawable: Drawable?): AnimPlayback
}

internal val LocalAnimPlaybackFactory = compositionLocalOf<AnimPlaybackFactory> {
    AnimPlaybackFactory { drawable -> DrawableAnimPlayback(drawable) }
}

/** 动图解码预算；默认是进程内共享的全局预算（≤ 2）。 */
internal val LocalAnimDecoderGate = compositionLocalOf { AnimDecoderGate.global }

/**
 * 上层（全屏查看器）接管动图播放意图时提供：`true` 播放、`false` 暂停。
 *
 * 一旦提供，`QuizMedia` 就**不再自己装点击监听**（点击语义交给上层，避免与双击缩放手势抢事件），
 * 只用这里的值驱动 `start()`/`stop()`。
 */
internal val LocalAnimPlayOverride = compositionLocalOf<Boolean?> { null }

/** 视频播放器池；默认是进程内共享的单播放器池。 */
internal val LocalVideoPlayerPool = compositionLocalOf { VideoPlayerPool.global }
