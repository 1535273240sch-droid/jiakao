package com.me.jiakao.core.update.util

import com.me.jiakao.core.model.MediaKind
import com.me.jiakao.core.model.MediaRef
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 源地址规范化与相对路径解析（合同 §3 的 `url` / `media_base`）。 */
class UrlsTest {

    @Test
    fun `源地址补斜杠并去掉空白`() {
        assertEquals("http://192.168.1.10:8000/", Urls.normalizeSource("  http://192.168.1.10:8000  "))
        assertEquals("http://10.0.2.2:8000/", Urls.normalizeSource("http://10.0.2.2:8000/"))
    }

    @Test
    fun `保留子路径并补斜杠`() {
        assertEquals("https://cdn.example.com/bank/", Urls.normalizeSource("https://cdn.example.com/bank"))
        assertEquals("https://cdn.example.com/bank/", Urls.normalizeSource("https://cdn.example.com/bank/"))
    }

    @Test
    fun `空白表示未设置`() {
        assertEquals("", Urls.normalizeSource(""))
        assertEquals("", Urls.normalizeSource("   "))
        assertNull(Urls.sourceOrNull(""))
    }

    @Test
    fun `非 http 协议一律拒绝`() {
        listOf("ftp://example.com/", "file:///sdcard/bank", "example.com/bank", "javascript:alert(1)").forEach { bad ->
            try {
                Urls.normalizeSource(bad)
                fail("应当拒绝：$bad")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!, e.message!!.contains("http"))
            }
        }
    }

    @Test
    fun `相对路径按源地址解析`() {
        val base = "http://10.0.2.2:8000/".toHttpUrl()
        assertEquals(
            "http://10.0.2.2:8000/full/bank-v12.jsonl.gz",
            Urls.resolve(base, "full/bank-v12.jsonl.gz")!!.toString(),
        )
        assertEquals(
            "http://10.0.2.2:8000/media/",
            Urls.resolve(base, "media/")!!.toString(),
        )
        assertEquals("http://10.0.2.2:8000/", Urls.resolve(base, "")!!.toString())
    }

    @Test
    fun `绝对地址原样使用`() {
        val base = "http://10.0.2.2:8000/".toHttpUrl()
        assertEquals(
            "https://cdn.example.com/full/bank-v12.jsonl.gz",
            Urls.resolve(base, "https://cdn.example.com/full/bank-v12.jsonl.gz")!!.toString(),
        )
    }

    @Test
    fun `媒体路径按 sha 前两位分片且内容寻址`() {
        val sha = "ab" + "c".repeat(62)
        val ref = MediaRef(sha256 = sha, ext = "webp", kind = MediaKind.IMAGE, width = 480, height = 480, bytes = 8123)
        assertEquals("ab/$sha.webp", Urls.mediaPath(ref))
        assertEquals(
            "http://10.0.2.2:8000/media/ab/$sha.webp",
            Urls.mediaUrl("http://10.0.2.2:8000/media/".toHttpUrl(), ref)!!.toString(),
        )
        assertEquals(
            "http://10.0.2.2:8000/media/ab/$sha.mp4",
            Urls.mediaUrl("http://10.0.2.2:8000/media/".toHttpUrl(), ref.copy(ext = "mp4", kind = MediaKind.VIDEO))!!.toString(),
        )
    }
}
