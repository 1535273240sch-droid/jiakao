package com.me.jiakao.core.media

import android.app.ActivityManager
import android.content.Context
import androidx.lifecycle.Lifecycle
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.lifecycle
import coil3.size.Dimension
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import com.me.jiakao.core.media.internal.FirstFrameDecoder
import com.me.jiakao.core.media.internal.MediaStores
import com.me.jiakao.core.model.MediaRef
import com.me.jiakao.core.model.MediaStore

/**
 * 进程内唯一的 Coil [ImageLoader]，也是 `:core:media` 对外承诺的"ImageLoader 单例"（合同 §6）。
 *
 * 配置（对应任务书 §2）：
 * - **内存缓存 ≤ 80MB**：取"应用可用堆的 25%"与"绝对值 80MB"的较小者；
 * - **禁用磁盘缓存与网络缓存**：媒体文件已经在 `filesDir/media`，再存一份纯属浪费；
 * - **关闭 crossfade**：题目切换本身有动画，叠加淡入会显得闪烁；
 * - **`Precision.INEXACT`**：允许解码器只做 2 的幂降采样（`inSampleSize`），滑动时显著省 CPU 与内存；
 * - 动图能力来自 `coil-gif`：`AnimatedImageDecoder`（API 28+ 动态 WebP / GIF）通过 Coil 的
 *   ServiceLoader 机制自动注册，所以这里不需要替换 `ComponentRegistry`
 *   （替换会丢掉 URI/资源/文件等默认 Fetcher，反而破坏 App 其他地方的 Coil 用法）。
 *
 * `MediaRef` 这条数据通路不走 `ComponentRegistry`，而是在
 * [MediaRequests.image] 里按请求挂 `MediaRefFetcher` 与显式 `memoryCacheKey`，
 * 再叠加 [MediaRefKeyer] 供外部自定义 registry 时使用。
 *
 * 需要把 Coil 的全局单例指向本 Loader 时（推荐做法，Coil 3 起要求尽早调用）：
 * ```
 * class App : Application(), SingletonImageLoader.Factory {
 *     override fun newImageLoader(context: PlatformContext) = MediaImageLoader.of(context)
 * }
 * ```
 */
object MediaImageLoader : SingletonImageLoader.Factory {

    /** 内存缓存绝对值上限（任务书硬指标）。 */
    const val MAX_MEMORY_CACHE_BYTES: Long = 80L * 1024 * 1024

    /** 内存缓存占应用可用堆的比例上限。 */
    private const val MEMORY_CACHE_PERCENT = 0.25

    private val lock = Any()

    @Volatile
    private var instance: ImageLoader? = null

    override fun newImageLoader(context: Context): ImageLoader = of(context)

    /**
     * 一行把 Coil 全局单例指向本模块的配置 —— 等价于让 `Application` 实现
     * [SingletonImageLoader.Factory]，但调用方不需要认识任何 Coil 类型。
     *
     * 在 `Application.onCreate()` 里调用一次即可（Coil 3 要求在任何 Coil API 之前设置）。
     * 如果单例已经被创建过（晚于第一次 Coil 调用），本方法**安静地不生效**：
     * 此时覆盖它反而会让进程里出现两个 loader（两份内存缓存），不如保持现状。
     */
    fun installAsCoilSingleton(context: Context) {
        val loader = of(context)
        runCatching { SingletonImageLoader.setSafe { loader } }
    }

    /** 取共享实例；首个调用者决定实例，后续调用复用。 */
    fun of(context: Context): ImageLoader {
        val app = context.applicationContext
        instance?.let { return it }
        synchronized(lock) {
            instance?.let { return it }
            return build(app).also { instance = it }
        }
    }

    private fun build(context: Context): ImageLoader = ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizeBytes(memoryCacheBytes(context))
                .build()
        }
        .diskCache(null)
        .diskCachePolicy(CachePolicy.DISABLED)
        .networkCachePolicy(CachePolicy.DISABLED)
        .crossfade(false)
        .precision(Precision.INEXACT)
        .build()

    /** `min(80MB, 可用堆 25%)`；拿不到可用堆时退回绝对值上限。 */
    fun memoryCacheBytes(context: Context): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val heapBytes = (activityManager?.memoryClass ?: 0).toLong() * 1024L * 1024L
        val percentBytes = (heapBytes * MEMORY_CACHE_PERCENT).toLong()
        val limit = minOf(MAX_MEMORY_CACHE_BYTES, percentBytes)
        return if (limit > 0L) limit else MAX_MEMORY_CACHE_BYTES
    }
}

/**
 * 构造 `MediaRef` 请求的唯一入口：UI、预取、查看器都走这里，保证
 * Fetcher / 缓存 key / 生命周期 / crossfade 四个开关始终一致。
 */
internal object MediaRequests {

    /**
     * 图片/动图请求。
     *
     * 注意**不设置** `size`：`AsyncImage` 见到未定义 sizeResolver 的请求时会挂上
     * `ConstraintsSizeResolver`，自动按控件实际像素尺寸降采样解码 —— 这正是"按控件尺寸解码"
     * 且零手动同步的做法。预取场景请用 [prefetch]。
     *
     * @param firstFrameOnly ANIM 超出解码预算时只解首帧（不产生动画解码器）。
     * @param viewer 全屏查看器用独立缓存 key：它解码尺寸更大，不能复用列表里的低分辨率位图。
     * @param lifecycle 传入后请求会跟随宿主生命周期：后台排队、销毁取消。
     */
    fun image(
        context: Context,
        ref: MediaRef,
        store: MediaStore,
        firstFrameOnly: Boolean = false,
        viewer: Boolean = false,
        lifecycle: Lifecycle? = null,
    ): ImageRequest = ImageRequest.Builder(context)
        .data(ref)
        .fetcherFactory(MediaRefFetcher.Factory(store), MediaRef::class)
        .memoryCacheKey(
            when {
                firstFrameOnly -> MediaCacheKeys.firstFrameOf(ref)
                viewer -> MediaCacheKeys.viewerOf(ref)
                else -> MediaCacheKeys.of(ref)
            },
        )
        .scale(Scale.FIT)
        .crossfade(false)
        .apply { if (firstFrameOnly) decoderFactory(FirstFrameDecoder.Factory()) }
        .apply { if (lifecycle != null) lifecycle(lifecycle) }
        .build()

    /**
     * 预取请求：尺寸固定为屏幕宽（题目页的图就是内容宽）。
     *
     * 因为缓存 key 显式指定且不含尺寸，预取结果能被按控件尺寸发起的前台请求直接命中；
     * 代价是"按屏幕宽解码"这个尺寸会成为该 sha 的共用尺寸 —— 题目页/列表都按内容宽显示，
     * 正是最宽的消费者，全屏查看器则用 [image] 的 `viewer` 变体单独解码。
     *
     * @param firstFrameOnly ANIM 只预解首帧，避免后台起动画解码器。
     */
    fun prefetch(
        context: Context,
        ref: MediaRef,
        store: MediaStore,
        widthPx: Int,
        firstFrameOnly: Boolean = false,
    ): ImageRequest = ImageRequest.Builder(context)
        .data(ref)
        .fetcherFactory(MediaRefFetcher.Factory(store), MediaRef::class)
        .memoryCacheKey(if (firstFrameOnly) MediaCacheKeys.firstFrameOf(ref) else MediaCacheKeys.of(ref))
        .size(Size(widthPx, Dimension.Undefined))
        .scale(Scale.FIT)
        .crossfade(false)
        .apply { if (firstFrameOnly) decoderFactory(FirstFrameDecoder.Factory()) }
        .build()
}
