package com.me.jiakao.core.media.internal

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Dimension
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 只解首帧的 [Decoder]。
 *
 * 动图超出"同时活动解码器 ≤ 2"预算时用它降级显示首帧静态图：`ImageDecoder.decodeBitmap()`
 * 对动图返回第一帧位图，**不会**产生 `AnimatedImageDrawable`，因此不占用 [AnimDecoderGate]
 * 的槽位，也不会有后台解码线程持续跑。
 *
 * 解码尺寸按请求尺寸等比降采样（永不放大），避免整帧原图进内存。
 * 走软件位图（`ALLOCATOR_SOFTWARE`）：动图首帧常用于需要读像素的场景，硬件位图不可读。
 */
internal class FirstFrameDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        var isSampled = false
        val bitmap = ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(source.file().toFile()),
        ) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            val srcWidth = info.size.width
            val srcHeight = info.size.height
            val (dstWidth, dstHeight) = targetSize(srcWidth, srcHeight)
            if (dstWidth != srcWidth || dstHeight != srcHeight) {
                isSampled = true
                decoder.setTargetSize(dstWidth, dstHeight)
            }
        }
        return DecodeResult(image = bitmap.asImage(), isSampled = isSampled)
    }

    /** 等比缩放到请求尺寸内，且永不放大。 */
    private fun targetSize(srcWidth: Int, srcHeight: Int): Pair<Int, Int> {
        if (srcWidth <= 0 || srcHeight <= 0) return srcWidth to srcHeight
        val targetWidth = options.size.width.pxOrNull()
        val targetHeight = options.size.height.pxOrNull()
        var multiplier = 1.0
        if (targetWidth != null) multiplier = min(multiplier, targetWidth.toDouble() / srcWidth)
        if (targetHeight != null) multiplier = min(multiplier, targetHeight.toDouble() / srcHeight)
        if (multiplier >= 1.0) return srcWidth to srcHeight
        return max(1, (srcWidth * multiplier).roundToInt()) to max(1, (srcHeight * multiplier).roundToInt())
    }

    private fun Dimension.pxOrNull(): Int? = when (this) {
        is Dimension.Pixels -> px
        Dimension.Undefined -> null
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder = FirstFrameDecoder(result.source, options)
    }
}
