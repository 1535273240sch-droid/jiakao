package com.me.jiakao.core.media.testing

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.Image
import coil3.ImageLoader
import coil3.asImage
import coil3.request.ImageRequest
import com.me.jiakao.core.media.internal.AnimPlayback
import com.me.jiakao.core.media.internal.QuizMediaImageHost

/**
 * 测试用的"图片渲染"替身：立刻报告成功，并记录收到的 [ImageRequest]。
 *
 * 这样测的是 `QuizMedia` 自己的状态机（占位屏、刷新、解码预算降级），
 * 而不是 Robolectric 能不能真的解出一张 WebP。
 */
internal class TestImageHost(private val drawable: Drawable? = null) : QuizMediaImageHost {

    var renderCount: Int = 0
        private set

    val requests = mutableListOf<ImageRequest>()

    @Composable
    override fun Render(
        request: ImageRequest,
        imageLoader: ImageLoader,
        contentScale: ContentScale,
        modifier: Modifier,
        onSuccess: (Image) -> Unit,
        onError: () -> Unit,
    ) {
        renderCount++
        requests += request
        val image = drawable?.asImage() ?: Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImage()
        LaunchedEffect(request) { onSuccess(image) }
        Box(modifier.background(Color.Magenta))
    }
}

/** 记录调用次数的播放控制替身。 */
internal class RecordingAnimPlayback : AnimPlayback {

    enum class Action { START, STOP }

    var startCount: Int = 0
        private set

    var stopCount: Int = 0
        private set

    /** 最后一次调用；用于判断"当前是否在播"。注意 stop 可能先于第一次 start
     *  （控件还没可见时不该播），所以不能用 startCount > stopCount 来判断。 */
    var lastAction: Action? = null
        private set

    /** 调用序列，断言失败时打印出来便于定位时序问题。 */
    val calls = mutableListOf<Action>()

    val isRunning: Boolean get() = lastAction == Action.START

    override fun start() {
        startCount++
        lastAction = Action.START
        calls += Action.START
    }

    override fun stop() {
        stopCount++
        lastAction = Action.STOP
        calls += Action.STOP
    }
}
