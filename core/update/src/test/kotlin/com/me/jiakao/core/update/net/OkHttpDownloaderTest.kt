package com.me.jiakao.core.update.net

import com.me.jiakao.core.update.FixtureServer
import com.me.jiakao.core.update.Fixtures
import com.me.jiakao.core.update.util.Sha256
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 下载器测试：用 MockWebServer 当静态服务端（合同 §3），
 * 覆盖断点续传、校验失败、重试、ETag、取消安全。
 */
class OkHttpDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: FixtureServer
    private lateinit var downloader: OkHttpDownloader
    private lateinit var tempDir: File

    /// 退避不真的等待，测试才跑得快
    private val noSleep: suspend (Long) -> Unit = { }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = FixtureServer()
        server.start()
        tempDir = temp.newFolder("dl")
        downloader = OkHttpDownloader(client, tempDir, sleeper = noSleep)
    }

    @After
    fun tearDown() {
        server.close()
    }

    // ───────────────────────── 正常下载 ─────────────────────────

    @Test
    fun `下载成功后校验通过且不留下断点文件`() = runBlocking {
        val bytes = Fixtures.bytes("full/bank-v2.jsonl.gz")
        val sha = Sha256.of(bytes)
        val progress = ArrayList<Pair<Long, Long>>()

        val file = downloader.downloadToTemp(
            url = server.url("full/bank-v2.jsonl.gz"),
            key = sha,
            expectedSha256 = sha,
            expectedBytes = bytes.size.toLong(),
        ) { done, total -> progress += done to total }

        assertArrayEquals(bytes, file.readBytes())
        assertEquals(file, downloader.targetFile(sha))
        assertFalse("成功提交后不应残留 .part", downloader.partFile(sha).exists())
        assertEquals(1, server.requestCount("full/bank-v2.jsonl.gz"))
        assertTrue("进度应至少回调一次", progress.isNotEmpty())
        assertEquals(bytes.size.toLong(), progress.last().first)
        assertEquals(bytes.size.toLong(), progress.last().second)
    }

    // ───────────────────────── 断点续传 ─────────────────────────

    @Test
    fun `已有断点时带 Range 续传`() = runBlocking {
        val bytes = Fixtures.bytes("full/bank-v2.jsonl.gz")
        val sha = Sha256.of(bytes)
        val half = bytes.size / 2
        downloader.partFile(sha).writeBytes(bytes.copyOfRange(0, half))

        val file = downloader.downloadToTemp(
            url = server.url("full/bank-v2.jsonl.gz"),
            key = sha,
            expectedSha256 = sha,
            expectedBytes = bytes.size.toLong(),
        )

        assertArrayEquals(bytes, file.readBytes())
        assertEquals("续传只应发一次请求", 1, server.requestCount("full/bank-v2.jsonl.gz"))
        assertEquals("bytes=$half-", server.rangeHeaders().single().second)
        assertFalse(downloader.partFile(sha).exists())
    }

    @Test
    fun `服务端忽略 Range 时从头重下`() = runBlocking {
        val bytes = Fixtures.bytes("full/bank-v1.jsonl.gz")
        val sha = Sha256.of(bytes)
        downloader.partFile(sha).writeBytes(bytes.copyOfRange(0, bytes.size / 3))
        // 客户端带了 Range，但服务端照样回 200 全量
        server.serve("full/bank-v1.jsonl.gz") {
            MockResponse().setResponseCode(200).setBody(Buffer().write(bytes))
        }

        val file = downloader.downloadToTemp(
            url = server.url("full/bank-v1.jsonl.gz"),
            key = sha,
            expectedSha256 = sha,
            expectedBytes = bytes.size.toLong(),
        )

        assertArrayEquals("必须被完整重写，不能把两次内容拼起来", bytes, file.readBytes())
    }

    @Test
    fun `断点比服务端文件还长则丢弃断点重来`() = runBlocking {
        val bytes = Fixtures.bytes("full/bank-v1.jsonl.gz")
        val sha = Sha256.of(bytes)
        downloader.partFile(sha).writeBytes(bytes + ByteArray(64))

        val file = downloader.downloadToTemp(
            url = server.url("full/bank-v1.jsonl.gz"),
            key = sha,
            expectedSha256 = sha,
            expectedBytes = bytes.size.toLong(),
        )

        assertArrayEquals(bytes, file.readBytes())
        assertEquals("第一次 416，第二次重新下载", 2, server.requestCount("full/bank-v1.jsonl.gz"))
        val firstRange = server.rangeHeaders()[0].second
        assertNotNull("第一次应带 Range", firstRange)
        assertEquals("bytes=${bytes.size + 64}-", firstRange)
        assertEquals("第二次不应带 Range", null, server.rangeHeaders()[1].second)
    }

    // ───────────────────────── 校验失败 ─────────────────────────

    @Test
    fun `sha256 不匹配时报不可重试错误并删除断点`() {
        val wrong = wrongMediaFile()
        val claimedSha = wrong.nameWithoutExtension
        server.serveFile("media-wrong/${claimedSha.take(2)}/$claimedSha.webp", wrong)

        val error = runBlocking {
            try {
                downloader.downloadToTemp(
                    url = server.url("media-wrong/${claimedSha.take(2)}/$claimedSha.webp"),
                    key = claimedSha,
                    expectedSha256 = claimedSha,
                    expectedBytes = wrong.length(),
                )
                null
            } catch (e: DownloadException) {
                e
            }
        }

        assertTrue("必须抛出 DownloadException", error != null)
        assertFalse("sha 校验失败不应重试", error!!.retryable)
        assertTrue(error.message!!, error.message!!.contains("sha256"))
        assertFalse("坏内容不能留着当断点", downloader.partFile(claimedSha).exists())
        assertFalse(downloader.targetFile(claimedSha).exists())
        assertEquals(1, server.requestCount("media-wrong/${claimedSha.take(2)}/$claimedSha.webp"))
    }

    @Test
    fun `服务端返回内容比声明长时报不可重试错误`() {
        val bytes = Fixtures.bytes("full/bank-v1.jsonl.gz")
        val sha = Sha256.of(bytes)
        val error = runBlocking {
            try {
                downloader.downloadToTemp(
                    url = server.url("full/bank-v1.jsonl.gz"),
                    key = sha,
                    expectedSha256 = sha,
                    expectedBytes = bytes.size.toLong() - 1,
                )
                null
            } catch (e: DownloadException) {
                e
            }
        }
        assertTrue(error != null)
        assertFalse(error!!.retryable)
        assertTrue(error.message!!, error.message!!.contains("大小校验失败"))
    }

    // ───────────────────────── 重试策略 ─────────────────────────

    @Test
    fun `连续两次 5xx 后第三次成功`() = runBlocking {
        val bytes = Fixtures.bytes("full/bank-v1.jsonl.gz")
        val sha = Sha256.of(bytes)
        repeat(2) { server.enqueue("full/bank-v1.jsonl.gz") { MockResponse().setResponseCode(500) } }

        val file = downloader.downloadToTemp(
            url = server.url("full/bank-v1.jsonl.gz"),
            key = sha,
            expectedSha256 = sha,
            expectedBytes = bytes.size.toLong(),
        )

        assertArrayEquals(bytes, file.readBytes())
        assertEquals(3, server.requestCount("full/bank-v1.jsonl.gz"))
    }

    @Test
    fun `一直 5xx 时用尽尝试次数并抛可重试错误`() {
        val sha = "c".repeat(64)
        repeat(5) { server.enqueue("full/bank-v1.jsonl.gz") { MockResponse().setResponseCode(503) } }

        val error = runBlocking {
            try {
                downloader.downloadToTemp(
                    url = server.url("full/bank-v1.jsonl.gz"),
                    key = sha,
                    expectedSha256 = sha,
                    expectedBytes = 10L,
                )
                null
            } catch (e: DownloadException) {
                e
            }
        }

        assertTrue(error != null)
        assertTrue("5xx 属于可重试", error!!.retryable)
        assertEquals("默认最多尝试 3 次", OkHttpDownloader.DEFAULT_MAX_ATTEMPTS, server.requestCount("full/bank-v1.jsonl.gz"))
    }

    @Test
    fun `404 不重试`() {
        val sha = "d".repeat(64)
        val error = runBlocking {
            try {
                downloader.downloadToTemp(
                    url = server.url("full/does-not-exist.jsonl.gz"),
                    key = sha,
                    expectedSha256 = sha,
                    expectedBytes = 10L,
                )
                null
            } catch (e: DownloadException) {
                e
            }
        }
        assertTrue(error != null)
        assertFalse(error!!.retryable)
        assertTrue(error.message!!, error.message!!.contains("404"))
        assertEquals(1, server.requestCount("full/does-not-exist.jsonl.gz"))
    }

    // ───────────────────────── ETag ─────────────────────────

    @Test
    fun `manifest 使用 ETag 且 304 视为未变化`() = runBlocking {
        server.etag = "\"bank-v2\""
        val first = downloader.fetchText(server.url("manifest.json"))
        assertTrue(first is FetchResult.Fetched)
        val fetched = first as FetchResult.Fetched
        assertEquals("\"bank-v2\"", fetched.etag)
        assertEquals(Fixtures.text("manifest.json"), fetched.body)

        val second = downloader.fetchText(server.url("manifest.json"), etag = fetched.etag)
        assertEquals(FetchResult.NotModified, second)
        assertEquals(2, server.requestCount("manifest.json"))
    }

    // ───────────────────────── 取消安全 / 复用 ─────────────────────────

    @Test
    fun `取消下载会保留断点且下次可续传`() {
        val bytes = ByteArray(256 * 1024) { i -> (i % 251).toByte() }
        val sha = Sha256.of(bytes)
        server.serve("media/big/big.webp") {
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(bytes))
                .throttleBody(8 * 1024, 50, TimeUnit.MILLISECONDS)
        }

        runBlocking {
            val job = launch(Dispatchers.IO) {
                downloader.downloadToTemp(
                    url = server.url("media/big/big.webp"),
                    key = sha,
                    expectedSha256 = sha,
                    expectedBytes = bytes.size.toLong(),
                )
            }
            delay(300)
            job.cancel()
            withTimeout(5_000) { job.join() }
        }

        val part = downloader.partFile(sha)
        assertTrue("取消后必须留下 .part 供续传", part.isFile)
        assertTrue("应当只下了一部分", part.length() > 0 && part.length() < bytes.size)
        assertFalse("取消不应产出成品文件", downloader.targetFile(sha).exists())

        // 恢复正常速度后重试：应当续传而不是重下
        server.serve("media/big/big.webp") {
            MockResponse().setResponseCode(200).setBody(Buffer().write(bytes))
        }
        val resumed = runBlocking {
            downloader.downloadToTemp(
                url = server.url("media/big/big.webp"),
                key = sha,
                expectedSha256 = sha,
                expectedBytes = bytes.size.toLong(),
            )
        }
        assertArrayEquals(bytes, resumed.readBytes())
        val lastRange = server.rangeHeaders().last()
        assertTrue("续传应带 Range：${lastRange.second}", lastRange.second?.startsWith("bytes=") == true)
    }

    @Test
    fun `已校验通过的临时文件直接复用不重复下载`() = runBlocking {
        val bytes = Fixtures.bytes("full/bank-v2.jsonl.gz")
        val sha = Sha256.of(bytes)
        downloader.targetFile(sha).writeBytes(bytes)

        val file = downloader.downloadToTemp(
            url = server.url("full/bank-v2.jsonl.gz"),
            key = sha,
            expectedSha256 = sha,
            expectedBytes = bytes.size.toLong(),
        )

        assertArrayEquals(bytes, file.readBytes())
        assertEquals("不应发出任何请求", 0, server.requestCount("full/bank-v2.jsonl.gz"))
    }

    private fun wrongMediaFile(): File =
        Fixtures.dir.resolve("media-wrong").walkTopDown().first { it.isFile }
}
