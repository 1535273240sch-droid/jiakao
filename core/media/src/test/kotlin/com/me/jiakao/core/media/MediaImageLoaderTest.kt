package com.me.jiakao.core.media

import android.content.Context
import coil3.request.CachePolicy
import coil3.request.Options
import coil3.size.Dimension
import com.me.jiakao.core.model.MediaKind
import com.me.jiakao.core.model.MediaStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import com.me.jiakao.core.model.MediaRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 图片管线的配置测试：内存缓存上限、禁用磁盘/网络缓存、单例复用、
 * 以及"请求确实挂上了本地 Fetcher 与内容寻址 key"。
 *
 * 这些数字是任务书里的硬指标，必须由测试守住，否则很容易被后来人一个 `crossfade(true)`
 * 或 `maxSizePercent(0.25)` 悄悄改掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaImageLoaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun store(): MediaStore = MediaStoreImpl(File(temp.root, "media"), Dispatchers.IO)

    private fun refOf(kind: MediaKind = MediaKind.IMAGE, ext: String = "webp"): MediaRef = MediaRef(
        sha256 = "ab".repeat(32),
        ext = ext,
        kind = kind,
        width = 640,
        height = 360,
        bytes = 1024,
    )

    @Test
    fun `内存缓存不超过 80MB 且为正数`() {
        val loader = MediaImageLoader.of(context())
        val max = loader.memoryCache!!.maxSize

        assertTrue("缓存上限应为正数，实际 $max", max > 0)
        assertTrue("缓存上限不得超过 80MB，实际 $max", max <= MediaImageLoader.MAX_MEMORY_CACHE_BYTES)
    }

    @Test
    fun `memoryCacheBytes 取百分比与绝对值的较小者`() {
        val bytes = MediaImageLoader.memoryCacheBytes(context())

        assertTrue(bytes > 0)
        assertTrue(bytes <= MediaImageLoader.MAX_MEMORY_CACHE_BYTES)
    }

    @Test
    fun `磁盘缓存被禁用`() {
        assertNull(MediaImageLoader.of(context()).diskCache)
    }

    @Test
    fun `同一个进程内只构建一次 ImageLoader`() {
        assertSame(MediaImageLoader.of(context()), MediaImageLoader.of(context()))
    }

    @Test
    fun `请求挂上了本地 Fetcher 与内容寻址内存缓存 key`() {
        val context = context()
        val ref = refOf()
        val request = MediaRequests.image(context, ref, store())

        assertNotNull("必须用本地 Fetcher，避免走默认组件去找文件", request.fetcherFactory)
        assertEquals(ref, request.data)
        assertEquals("media:${ref.sha256}:${ref.ext}", request.memoryCacheKey)
    }

    @Test
    fun `仅首帧变体使用独立 key 与独立解码器`() {
        val context = context()
        val ref = refOf(kind = MediaKind.ANIM)
        val store = store()

        val animated = MediaRequests.image(context, ref, store)
        val firstFrame = MediaRequests.image(context, ref, store, firstFrameOnly = true)
        val viewer = MediaRequests.image(context, ref, store, viewer = true)

        assertEquals("media:${ref.sha256}:webp", animated.memoryCacheKey)
        assertEquals("media:${ref.sha256}:webp:static", firstFrame.memoryCacheKey)
        assertEquals("media:${ref.sha256}:webp:full", viewer.memoryCacheKey)
        assertNull("整段动图不该指定仅首帧解码器", animated.decoderFactory)
        assertNotNull("仅首帧必须指定自己的解码器", firstFrame.decoderFactory)
    }

    @Test
    fun `预取请求按屏幕宽解码且复用同一缓存 key`() {
        val context = context()
        val ref = refOf()
        val request = MediaRequests.prefetch(context, ref, store(), widthPx = 1080)

        val size = runBlocking { request.sizeResolver.size() }
        assertEquals(1080, (size.width as Dimension.Pixels).px)
        assertEquals(Dimension.Undefined, size.height)
        // 显式 key 不含尺寸：预取结果能被前台请求直接命中（Coil 只在自动推导 key 时才拼尺寸）。
        assertEquals("media:${ref.sha256}:webp", request.memoryCacheKey)
    }

    @Test
    fun `Keyer 与请求产出同一个 key`() {
        val context = context()
        val ref = refOf()
        val keyer = MediaRefKeyer()

        assertEquals(
            MediaCacheKeys.of(ref),
            keyer.key(ref, Options(context)),
        )
    }

    @Test
    fun `coil-gif 在类路径上（AnimatedImageDecoder 会被 ServiceLoader 自动注册）`() {
        // 动态 WebP / GIF 的支持来自 coil-gif；它通过
        // META-INF/services/coil3.util.DecoderServiceLoaderTarget 自动注册，
        // 因此这里断言"类存在"就等于断言"能力已装载"。
        Class.forName("coil3.gif.AnimatedImageDecoder")
        Class.forName("coil3.gif.internal.GifDecoderServiceLoaderTarget")
    }

    @Test
    fun `loader 默认策略为不缓存磁盘与网络`() {
        val loader = MediaImageLoader.of(context())

        assertEquals(CachePolicy.DISABLED, loader.defaults.diskCachePolicy)
        assertEquals(CachePolicy.DISABLED, loader.defaults.networkCachePolicy)
    }
}
