package com.me.jiakao.core.media

import coil3.decode.DataSource
import coil3.fetch.SourceFetchResult
import com.me.jiakao.core.model.MediaKind
import com.me.jiakao.core.model.MediaRef
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [MediaRefFetcher] 的单元测试：命中返回磁盘数据源，缺失返回 null（明确 miss，不抛异常）。 */
class MediaRefFetcherTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `文件存在时返回指向本地文件的 SourceFetchResult`() = runTest {
        val bytes = "webp-bytes".toByteArray()
        val file = File(temp.root, "abc.webp").apply { writeBytes(bytes) }

        val result = MediaRefFetcher(file).fetch()

        val source = (result as SourceFetchResult).source
        assertEquals(DataSource.DISK, result.dataSource)
        assertEquals("image/webp", result.mimeType)
        assertEquals(file.canonicalPath, source.file().toFile().canonicalPath)
        assertEquals(bytes.toList(), source.source().use { it.readByteArray().toList() })
    }

    @Test
    fun `文件缺失时返回 null 而不是抛异常`() = runTest {
        val missing = File(temp.root, "missing.webp")
        assertNull(MediaRefFetcher(missing).fetch())
    }

    @Test
    fun `懒解析：创建 Fetcher 之后文件才出现，refresh 后即可命中`() = runTest {
        val ref = refOf("late".toByteArray())
        val root = File(temp.root, "media")
        val store = MediaStoreImpl(root)
        val fetcher = MediaRefFetcher(store, ref)

        assertNull("文件还没落盘", fetcher.fetch())

        val path = MediaRefFetcher.pathOf(root, ref)!!
        path.parentFile!!.mkdirs()
        path.writeBytes("late".toByteArray())

        assertNull("存在性缓存的负面结论会保留 —— 这正是 refresh() 存在的理由", fetcher.fetch())
        assertTrue(store.refresh(ref))
        assertEquals(DataSource.DISK, (fetcher.fetch() as SourceFetchResult).dataSource)
    }

    @Test
    fun `mimeType 按扩展名映射`() {
        assertEquals("image/webp", MediaRefFetcher.mimeTypeOf("a.webp"))
        assertEquals("image/gif", MediaRefFetcher.mimeTypeOf("a.gif"))
        assertEquals("video/mp4", MediaRefFetcher.mimeTypeOf("a.mp4"))
        assertNull(MediaRefFetcher.mimeTypeOf("a.bin"))
    }

    private fun refOf(bytes: ByteArray): MediaRef = MediaRef(
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),
        ext = "webp",
        kind = MediaKind.IMAGE,
        width = 10,
        height = 10,
        bytes = bytes.size.toLong(),
    )
}
