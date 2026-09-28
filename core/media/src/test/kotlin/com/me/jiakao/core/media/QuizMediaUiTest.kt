package com.me.jiakao.core.media

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.me.jiakao.core.media.internal.AnimDecoderGate
import com.me.jiakao.core.media.internal.AnimPlaybackFactory
import com.me.jiakao.core.media.internal.LocalAnimDecoderGate
import com.me.jiakao.core.media.internal.LocalAnimPlayOverride
import com.me.jiakao.core.media.internal.LocalAnimPlaybackFactory
import com.me.jiakao.core.media.internal.LocalMediaStore
import com.me.jiakao.core.media.internal.LocalQuizMediaImageHost
import com.me.jiakao.core.media.internal.MediaTestTags
import com.me.jiakao.core.media.testing.RecordingAnimPlayback
import com.me.jiakao.core.media.testing.TestImageHost
import com.me.jiakao.core.model.MediaKind
import com.me.jiakao.core.model.MediaRef
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `QuizMedia` 的 Compose 组件测试（Robolectric 上跑，`testDebugUnitTest` 即可覆盖）。
 *
 * 覆盖验收清单里的"占位 → 刷新"与动图播放门控；图片管线用 [TestImageHost] 替身，
 * 因此断言的是我们自己的状态机，与真实解码无关。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuizMediaUiTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: MediaStoreImpl

    private fun newStore(): MediaStoreImpl {
        store = MediaStoreImpl(File(temp.root, "media"), Dispatchers.IO)
        return store
    }

    private fun refOf(bytes: ByteArray, kind: MediaKind, w: Int = 480, h: Int = 360): MediaRef = MediaRef(
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),
        ext = "webp",
        kind = kind,
        width = w,
        height = h,
        bytes = bytes.size.toLong(),
    )

    private fun storeBytes(ref: MediaRef, bytes: ByteArray) {
        val tmp = File(temp.root, "tmp-${ref.sha256.take(8)}").apply { writeBytes(bytes) }
        assertTrue("夹具应能落盘", runBlocking { store.commit(ref, tmp) })
    }

    private fun setContent(
        ref: MediaRef,
        host: TestImageHost,
        playback: RecordingAnimPlayback = RecordingAnimPlayback(),
        gate: AnimDecoderGate = AnimDecoderGate(2),
        wantsPlay: MutableState<Boolean>? = null,
    ) {
        compose.setContent {
            CompositionLocalProvider(
                LocalMediaStore provides store,
                LocalQuizMediaImageHost provides host,
                LocalAnimPlaybackFactory provides AnimPlaybackFactory { playback },
                LocalAnimDecoderGate provides gate,
                // 非 null 即表示"上层接管播放意图"（查看器场景）。
                LocalAnimPlayOverride provides wantsPlay?.value,
            ) {
                Box(Modifier.fillMaxSize()) {
                    QuizMedia(ref = ref, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    @Test
    fun `文件缺失时显示骨架屏且不发起解码请求`() {
        newStore()
        val host = TestImageHost()
        val ref = refOf("missing".toByteArray(), MediaKind.IMAGE)

        setContent(ref, host)

        compose.waitForIdle()
        compose.onNodeWithTag(MediaTestTags.SKELETON).assertExists()
        assertEquals("文件不在本地时不应发起请求", 0, host.renderCount)
    }

    @Test
    fun `文件补齐后骨架屏自动被图片替换`() {
        newStore()
        val host = TestImageHost()
        val bytes = "later".toByteArray()
        val ref = refOf(bytes, MediaKind.IMAGE)

        setContent(ref, host)
        compose.waitForIdle()
        compose.onNodeWithTag(MediaTestTags.SKELETON).assertExists()

        // 模拟 04 下载完成：commit 后 committed 事件应触发刷新。
        storeBytes(ref, bytes)

        compose.waitUntil(timeoutMillis = 5_000) { host.renderCount > 0 }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(MediaTestTags.IMAGE).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(MediaTestTags.IMAGE).assertIsDisplayed()
        compose.onAllNodesWithTag(MediaTestTags.SKELETON).assertCountEquals(0)
    }

    @Test
    fun `动图在可见且前台时播放，上层接管后暂停`() {
        newStore()
        val host = TestImageHost()
        val playback = RecordingAnimPlayback()
        val bytes = "anim".toByteArray()
        val ref = refOf(bytes, MediaKind.ANIM)
        storeBytes(ref, bytes)

        // 上层（查看器）接管播放意图：wantsPlay = true 时应当播放。
        val wantsPlay = mutableStateOf(true)
        setContent(ref, host, playback = playback, wantsPlay = wantsPlay)

        compose.waitUntil(timeoutMillis = 5_000) { playback.startCount > 0 }
        assertTrue("拿到解码预算后应开始播放", playback.isRunning)

        // 注意：控件首次组合（还不可见）时会先 stop 一次，所以这里按"增量"等待，
        // 否则 waitUntil 可能被之前那次 stop 直接满足。
        val stopsBefore = playback.stopCount
        compose.runOnIdle { wantsPlay.value = false }
        compose.waitForIdle()
        assertTrue(
            "上层要求暂停后应收到新的 stop()：wantsPlay=${wantsPlay.value}, 调用序列=${playback.calls}",
            playback.stopCount > stopsBefore,
        )
        assertFalse("应停在 stop()，调用序列=${playback.calls}", playback.isRunning)
    }

    @Test
    fun `解码预算用尽时动图退化为仅解首帧且不播放`() {
        newStore()
        val host = TestImageHost()
        val playback = RecordingAnimPlayback()
        val bytes = "anim-busy".toByteArray()
        val ref = refOf(bytes, MediaKind.ANIM)
        storeBytes(ref, bytes)

        // 预算被别的动图占满：本控件应拿不到槽位。
        val gate = AnimDecoderGate(maxActive = 1)
        assertTrue(gate.tryAcquire())

        setContent(ref, host, playback = playback, gate = gate)

        compose.waitUntil(timeoutMillis = 5_000) { host.renderCount > 0 }
        compose.waitForIdle()

        assertEquals("没有解码预算时不应播放", 0, playback.startCount)
        assertNotNull("应改用仅首帧解码器", host.requests.last().decoderFactory)
        assertTrue(host.requests.last().memoryCacheKey!!.endsWith(":static"))
    }

    @Test
    fun `点击动图可切换暂停与播放（无 onClick 时）`() {
        newStore()
        val host = TestImageHost()
        val playback = RecordingAnimPlayback()
        val bytes = "anim-toggle".toByteArray()
        val ref = refOf(bytes, MediaKind.ANIM)
        storeBytes(ref, bytes)

        setContent(ref, host, playback = playback)

        compose.waitUntil(timeoutMillis = 5_000) { playback.startCount > 0 }

        val stopsBefore = playback.stopCount
        compose.onNodeWithTag(MediaTestTags.ANIM_BADGE).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { playback.stopCount > stopsBefore }

        val startsBefore = playback.startCount
        compose.onNodeWithTag(MediaTestTags.ANIM_BADGE).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { playback.startCount > startsBefore }
    }

    @Test
    fun `静态图不会创建播放控制`() {
        newStore()
        val host = TestImageHost()
        val playback = RecordingAnimPlayback()
        val bytes = "still".toByteArray()
        val ref = refOf(bytes, MediaKind.IMAGE)
        storeBytes(ref, bytes)

        setContent(ref, host, playback = playback)

        compose.waitUntil(timeoutMillis = 5_000) { host.renderCount > 0 }
        compose.waitForIdle()
        assertEquals(0, playback.startCount)
        assertNull(host.requests.last().decoderFactory)
        assertEquals("media:${ref.sha256}:webp", host.requests.last().memoryCacheKey)
    }

    @Test
    fun `图片请求带上了本地 Fetcher（不走默认组件）`() {
        newStore()
        val host = TestImageHost()
        val bytes = "fetcher".toByteArray()
        val ref = refOf(bytes, MediaKind.IMAGE)
        storeBytes(ref, bytes)

        setContent(ref, host)

        compose.waitUntil(timeoutMillis = 5_000) { host.renderCount > 0 }
        assertNotNull(host.requests.last().fetcherFactory)
        assertEquals(ref, host.requests.last().data)
    }
}
