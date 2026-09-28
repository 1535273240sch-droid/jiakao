package com.me.jiakao.core.media.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.Image
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest

/**
 * 内部接缝：把"渲染一张 Coil 图片"这件事抽出来，并把解码结果回调给 [QuizMedia]。
 *
 * 生产实现只有 20 行，直接委托 `AsyncImage`（它自带 `ConstraintsSizeResolver`，
 * 会按控件实际像素尺寸降采样）。把它做成接缝是为了让 Compose UI 测试能注入可控实现：
 * 占位屏 → 刷新、动图暂停/恢复这类断言应该验证**我们的状态机**，
 * 而不是去赌 Robolectric 能不能真的解出一张动图。
 */
internal interface QuizMediaImageHost {

    @Composable
    fun Render(
        request: ImageRequest,
        imageLoader: ImageLoader,
        contentScale: ContentScale,
        modifier: Modifier,
        onSuccess: (Image) -> Unit,
        onError: () -> Unit,
    )
}

internal val LocalQuizMediaImageHost = compositionLocalOf<QuizMediaImageHost> { CoilQuizMediaImageHost }

private object CoilQuizMediaImageHost : QuizMediaImageHost {

    @Composable
    override fun Render(
        request: ImageRequest,
        imageLoader: ImageLoader,
        contentScale: ContentScale,
        modifier: Modifier,
        onSuccess: (Image) -> Unit,
        onError: () -> Unit,
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            imageLoader = imageLoader,
            modifier = modifier,
            contentScale = contentScale,
            onState = { state ->
                when (state) {
                    is AsyncImagePainter.State.Success -> onSuccess(state.result.image)
                    is AsyncImagePainter.State.Error -> onError()
                    else -> Unit
                }
            },
        )
    }
}
