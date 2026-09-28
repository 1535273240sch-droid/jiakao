package com.me.jiakao.core.media.testing

import android.graphics.Bitmap
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.asImage
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * 测试替身：只实现 [MediaPrefetcherImpl] 用得到的那部分 [ImageLoader]。
 *
 * 它记录"执行了什么请求、并发峰值多少"，让"限流 ≤ 3、去重、跳过 VIDEO"这些**调度行为**
 * 可以在没有真实解码的情况下被断言。
 */
internal class FakeImageLoader(
    private val onExecute: suspend (ImageRequest) -> Unit = {},
) : ImageLoader {

    val executed = CopyOnWriteArrayList<ImageRequest>()

    private val active = AtomicInteger()

    @Volatile
    var maxConcurrent: Int = 0
        private set

    override val defaults: ImageRequest.Defaults get() = ImageRequest.Defaults.DEFAULT

    override val components: ComponentRegistry get() = ComponentRegistry.Builder().build()

    override val memoryCache: MemoryCache? get() = null

    override val diskCache: DiskCache? get() = null

    override fun enqueue(request: ImageRequest): Disposable = error("MediaPrefetcher 不应使用 enqueue")

    override suspend fun execute(request: ImageRequest): ImageResult {
        val current = active.incrementAndGet()
        maxConcurrent = max(maxConcurrent, current)
        try {
            onExecute(request)
            executed += request
            return SuccessResult(
                image = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImage(),
                request = request,
            )
        } finally {
            active.decrementAndGet()
        }
    }

    override fun shutdown() = Unit

    override fun newBuilder(): ImageLoader.Builder = error("测试替身不支持 newBuilder")
}
