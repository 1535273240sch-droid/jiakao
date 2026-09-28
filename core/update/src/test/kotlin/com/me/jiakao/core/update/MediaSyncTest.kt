package com.me.jiakao.core.update

import com.me.jiakao.core.model.MediaKind
import com.me.jiakao.core.model.MediaRef
import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 媒体补齐测试：并发度、优先级、失败计数、retain、URL 拼接。
 * 用 [FakeDownloader] 精确观察并发与顺序（真实网络路径由 [com.me.jiakao.core.update.net.OkHttpDownloaderTest] 覆盖）。
 */
class MediaSyncTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val mediaBase = "http://static.example.com/media/".toHttpUrl()

    @Test
    fun `已经存在的媒体不会再下载`() = runTest {
        val (a, bytesA) = media("a", 100)
        val (b, bytesB) = media("b", 200)
        val (c, bytesC) = media("c", 300)
        val store = FakeMediaStore(temp.newFolder("media"))
        store.commit(a, writeTemp("a", bytesA))
        store.commit(b, writeTemp("b", bytesB))

        val downloader = FakeDownloader(
            temp.newFolder("dl"),
            bodies(a to bytesA, b to bytesB, c to bytesC),
        )
        val result = MediaSync(downloader, store).sync(mediaBase, listOf(a, b, c))

        assertEquals("只有缺的那一张需要下载", 1, result.total)
        assertEquals(1, result.done)
        assertEquals(0, result.failed)
        assertEquals(1, downloader.requestedPaths.size)
        assertEquals(c.sha256, downloader.requestedPaths.single().substringAfterLast('/').substringBeforeLast('.'))
    }

    @Test
    fun `并发度不超过 4`() = runTest {
        val mediaList = (1..8).map { media("m$it", 100 * it) }
        val store = FakeMediaStore(temp.newFolder("media"))
        val downloader = FakeDownloader(temp.newFolder("dl"), bodiesOf(mediaList)).apply { delayMs = 1 }
        val refs = mediaList.map { it.first }

        val result = MediaSync(downloader, store).sync(mediaBase, refs)

        assertEquals(8, result.total)
        assertEquals(8, result.done)
        assertEquals(MediaSync.DEFAULT_CONCURRENCY, downloader.maxActive.get())
        assertEquals(8, store.committedShas().size)
    }

    @Test
    fun `单个媒体失败只计数不影响其它媒体`() = runTest {
        val mediaList = (1..3).map { media("f$it", 100 * it) }
        val store = FakeMediaStore(temp.newFolder("media"))
        val downloader = FakeDownloader(temp.newFolder("dl"), bodiesOf(mediaList)).apply {
            failingShas = setOf(mediaList[1].first.sha256)
        }

        val result = MediaSync(downloader, store).sync(mediaBase, mediaList.map { it.first })

        assertEquals(3, result.total)
        assertEquals(2, result.done)
        assertEquals(1, result.failed)
        assertEquals(listOf(mediaList[1].first), result.failedRefs)
        assertEquals(2, store.committedShas().size)
    }

    @Test
    fun `下载顺序是优先集合 然后小静图 再动图`() = runTest {
        val smallImage = media("img-small", 100)
        val bigImage = media("img-big", 400)
        val smallAnim = media("anim-small", 800, MediaKind.ANIM)
        val bigAnim = media("anim-big", 1600, MediaKind.ANIM)
        val all = listOf(smallImage, bigImage, smallAnim, bigAnim)
        val refs = all.map { it.first }

        val store = FakeMediaStore(temp.newFolder("media"))
        val downloader = FakeDownloader(temp.newFolder("dl"), bodiesOf(all)).apply { delayMs = 1 }
        MediaSync(downloader, store).sync(mediaBase, refs, priority = setOf(bigAnim.first.sha256))

        val expected = listOf(
            bigAnim.first,     // 调用方指定优先
            smallImage.first,  // 小静图
            bigImage.first,    // 大静图
            smallAnim.first,   // 动图最后
        )
        assertEquals(expected.map { it.sha256 }, MediaSync.order(refs, setOf(bigAnim.first.sha256)).map { it.sha256 })
        assertEquals(expected.map { it.sha256 }, downloader.requestedPaths.map { it.substringAfterLast('/').substringBeforeLast('.') })
    }

    @Test
    fun `进度从 0 到总数`() = runTest {
        val mediaList = (1..3).map { media("p$it", 100 * it) }
        val store = FakeMediaStore(temp.newFolder("media"))
        val downloader = FakeDownloader(temp.newFolder("dl"), bodiesOf(mediaList))
        val progress = ArrayList<Pair<Int, Int>>()

        MediaSync(downloader, store).sync(mediaBase, mediaList.map { it.first }) { done, total ->
            progress += done to total
        }

        assertEquals(0 to 3, progress.first())
        assertEquals(3 to 3, progress.last())
        // sync() 先回调一次 (0, total)，之后每完成一个再回调一次（MediaSync.sync KDoc：
        // "每完成一个回调一次"）→ 3 个媒体共 4 次。
        assertEquals(4, progress.size)
    }

    @Test
    fun `retain 传出被引用的全部 sha`() = runTest {
        val mediaList = (1..2).map { media("r$it", 100 * it) }
        val store = FakeMediaStore(temp.newFolder("media"))
        val sync = MediaSync(FakeDownloader(temp.newFolder("dl"), bodiesOf(mediaList)), store)

        sync.retain(mediaList.map { it.first })

        assertEquals(mediaList.map { it.first.sha256 }.toSet(), store.retained)
    }

    @Test
    fun `媒体地址符合合同的内容寻址规则`() = runTest {
        val (ref, bytes) = media("url", 128)
        val store = FakeMediaStore(temp.newFolder("media"))
        val downloader = FakeDownloader(temp.newFolder("dl"), mapOf(ref.sha256 to bytes))

        MediaSync(downloader, store).sync(mediaBase, listOf(ref))

        val expected = "/media/${ref.sha256.take(2)}/${ref.sha256}.${ref.ext}"
        assertEquals(listOf(expected), downloader.requestedPaths)
        assertTrue(ref.sha256.take(2).length == 2)
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private fun media(id: String, size: Int, kind: MediaKind = MediaKind.IMAGE): Pair<MediaRef, ByteArray> {
        val bytes = mediaBytes(id, size)
        return mediaRefOf(bytes, kind) to bytes
    }

    private fun bodiesOf(items: List<Pair<MediaRef, ByteArray>>): Map<String, ByteArray> =
        items.associate { it.first.sha256 to it.second }

    private fun bodies(vararg items: Pair<MediaRef, ByteArray>): Map<String, ByteArray> =
        items.associate { it.first.sha256 to it.second }

    private fun writeTemp(name: String, bytes: ByteArray): File =
        File(temp.newFolder("tmp-$name"), "$name.bin").apply { writeBytes(bytes) }
}
