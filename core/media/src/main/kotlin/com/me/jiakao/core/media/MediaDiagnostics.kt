package com.me.jiakao.core.media

import android.content.Context
import com.me.jiakao.core.media.internal.AnimDecoderGate
import com.me.jiakao.core.media.internal.VideoPlayerPool
import kotlinx.coroutines.flow.StateFlow

/**
 * 运行时诊断信息（Demo / QA HUD / 性能自测用）。
 *
 * 这些数值正是任务书里被写死的硬指标，暴露出来是为了让它们**可被观察**：
 * "3 个动图同屏只有 2 个在播"、"内存缓存不超过 80MB"这类结论要能一眼看到，而不是靠猜。
 * 纯只读，不参与任何业务逻辑。
 */
object MediaDiagnostics {

    /** 当前活动的动图解码器数量（全局上限为 2：`AnimDecoderGate.DEFAULT_MAX_ACTIVE`）。 */
    val activeAnimDecoders: StateFlow<Int>
        get() = AnimDecoderGate.global.activeCount

    /** 当前图片内存缓存占用字节数。 */
    fun imageCacheBytes(context: Context): Long =
        MediaImageLoader.of(context).memoryCache?.size ?: 0L

    /** 图片内存缓存上限字节数（min(80MB, 可用堆 25%)）。 */
    fun imageCacheMaxBytes(context: Context): Long =
        MediaImageLoader.of(context).memoryCache?.maxSize ?: 0L

    /** 是否已有视频播放器在运行（全局单播放器池）。 */
    val isVideoPlayerActive: Boolean
        get() = VideoPlayerPool.global.isActive
}
