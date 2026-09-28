package com.me.jiakao.core.media

import android.content.Context
import com.me.jiakao.core.media.testing.FakeImageLoader
import com.me.jiakao.core.model.MediaKind
import com.me.jiakao.core.model.MediaRef
import coil3.size.Dimension
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [MediaPrefetcherImpl] 的调度行为测试（Robolectric 提供 Context，图片解码全部走测试替身）。
 *
 * 断言的是任务书 §5 的四条约定：**去重**、**ANIM 只解首帧**、**并发 ≤ 3**、**cancelAll 取消未开始任务**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaPrefetcherImplTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var store: MediaStoreImpl

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun newStore(): MediaStoreImpl {
        store = MediaStoreImpl(File(temp.root, "media"), Dispatchers.IO)
        return store
    }

    /** 把 [bytes] 真实落盘，返回对应的 [MediaRef]。 */
    private fun stored(bytes: ByteArray, kind: MediaKind = MediaKind.IMAGE, ext: String = "webp"): MediaRef {
        val ref = MediaRef(
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),
            ext = ext,
            kind = kind,
            width = 480,
            height = 360,
            bytes = bytes.size.toLong(),
        )
        val tmp = File(temp.root, "tmp-${ref.sha256.take(8)}").apply { writeBytes(bytes) }
        assertTrue(runBlocking { store.commit(ref, tmp) })
        return ref
    }

    @Test
    fun `同一 sha 去重，只执行一次`() {
        val loader = FakeImageLoader()
        val prefetcher = MediaPrefetcherImpl(context(), newStore(), loader, screenWidthPx = 1080, scope = scope)
        val ref = stored("dedupe".toByteArray())

        prefetcher.prefetch(listOf(ref, ref, ref))

        awaitUntil { loader.executed.size == 1 }
        assertEquals(1, loader.executed.size)
    }

    @Test
    fun `文件缺失时跳过，不做无谓请求`() {
        val loader = FakeImageLoader()
        val prefetcher = MediaPrefetcherImpl(context(), newStore(), loader, screenWidthPx = 1080, scope = scope)
        val missing = MediaRef(
            sha256 = "a".repeat(64),
            ext = "webp",
            kind = MediaKind.IMAGE,
            width = 1,
            height = 1,
            bytes = 1,
        )

        prefetcher.prefetch(listOf(missing))

        Thread.sleep(150)
        assertEquals(0, loader.executed.size)
    }

    @Test
    fun `视频不预取`() {
        val loader = FakeImageLoader()
        val prefetcher = MediaPrefetcherImpl(context(), newStore(), loader, screenWidthPx = 1080, scope = scope)
        val video = stored("mp4-bytes".toByteArray(), kind = MediaKind.VIDEO, ext = "mp4")

        prefetcher.prefetch(listOf(video))

        Thread.sleep(150)
        assertEquals(0, loader.executed.size)
    }

    @Test
    fun `动图只预解首帧`() {
        val loader = FakeImageLoader()
        val prefetcher = MediaPrefetcherImpl(context(), newStore(), loader, screenWidthPx = 1080, scope = scope)
        val anim = stored("anim-bytes".toByteArray(), kind = MediaKind.ANIM)

        prefetcher.prefetch(listOf(anim))

        awaitUntil { loader.executed.size == 1 }
        val request = loader.executed.single()
        assertNotNull("应挂上首帧解码器，避免后台起动画解码器", request.decoderFactory)
        assertTrue(request.memoryCacheKey!!.endsWith(":static"))
    }

    @Test
    fun `图片预取使用内容寻址 key 与屏幕宽尺寸`() {
        val loader = FakeImageLoader()
        val prefetcher = MediaPrefetcherImpl(context(), newStore(), loader, screenWidthPx = 1080, scope = scope)
        val ref = stored("still".toByteArray())

        prefetcher.prefetch(listOf(ref))

        awaitUntil { loader.executed.size == 1 }
        val request = loader.executed.single()
        assertEquals("media:${ref.sha256}:webp", request.memoryCacheKey)
        val size = runBlocking { request.sizeResolver.size() }
        assertEquals(1080, (size.width as Dimension.Pixels).px)
        assertEquals(Dimension.Undefined, size.height)
    }

    @Test
    fun `并发被限制在三个以内`() {
        val loader = FakeImageLoader(onExecute = { delay(40) })
        val prefetcher = MediaPrefetcherImpl(context(), newStore(), loader, screenWidthPx = 1080, scope = scope)
        val refs = (1..9).map { stored("img-$it".toByteArray()) }

        prefetcher.prefetch(refs)

        awaitUntil(timeoutMs = 10_000) { loader.executed.size == refs.size }
        assertTrue("并发峰值应 ≤ 3，实际 ${loader.maxConcurrent}", loader.maxConcurrent <= 3)
    }

    @Test
    fun `cancelAll 取消尚未开始的任务`() {
        val loader = FakeImageLoader(onExecute = { delay(2_000) })
        val prefetcher = MediaPrefetcherImpl(context(), newStore(), loader, screenWidthPx = 1080, scope = scope)
        val refs = (1..9).map { stored("slow-$it".toByteArray()) }

        prefetcher.prefetch(refs)
        prefetcher.cancelAll()

        Thread.sleep(300)
        assertTrue("被取消后不应有任务跑完，实际 ${loader.executed.size}", loader.executed.size < refs.size)
    }

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        fail("条件在 ${timeoutMs}ms 内未满足")
    }
}
