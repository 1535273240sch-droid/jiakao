package com.me.jiakao.core.update.net

import com.me.jiakao.core.update.util.Sha256
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 基于 OkHttp 的下载器（合同 §3.5）。
 *
 * 关键行为：
 * - 先写 `cacheDir/dl/{key}.part`，`sha256`（以及可选的 `bytes`）校验通过才 rename 成正式临时文件；
 * - `Range` 断点续传：`key` 用内容 sha256，中断后重跑直接命中同一个 `.part`；
 * - 只对网络错误 / 5xx / 408 / 429 做指数退避重试，最多 [maxAttempts] 次；
 * - 协程取消时不删 `.part`，且通过 [okhttp3.Call.cancel] 及时断开 socket。
 *
 * @param tempDir 临时目录（合同 §1：`cacheDir/dl/`）
 * @param maxAttempts 单次下载最多尝试次数（含首次）
 * @param baseBackoffMs 首次退避时长，之后逐次翻倍（上限 8s）
 * @param sleeper 退避等待，测试可注入以免真的等待
 * @param dispatcher 阻塞 IO 的调度器（OkHttp 的 execute/读流都是阻塞调用）
 */
class OkHttpDownloader(
    private val client: OkHttpClient,
    tempDir: File,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val baseBackoffMs: Long = DEFAULT_BASE_BACKOFF_MS,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Downloader {

    private val tempDir: File = tempDir.apply { mkdirs() }
    private val unsafeKey = Regex("[^A-Za-z0-9._-]")

    // 注：Kotlin 不允许 override 重复声明默认值（会报 "An overriding function is not allowed
    // to specify default values for its parameters"）。默认值由接口 Downloader 声明并继承，
    // 调用方以 Downloader 类型调用即可省略参数。
    override suspend fun fetchText(url: HttpUrl, etag: String?): FetchResult {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return attemptFetchText(url, etag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownloadException) {
                if (!e.retryable || attempt >= maxAttempts) throw e
                sleeper(backoffMillis(attempt))
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (attempt >= maxAttempts) {
                    throw DownloadException("请求失败：$url：${e.message}", retryable = true, cause = e)
                }
                sleeper(backoffMillis(attempt))
            }
        }
    }

    override suspend fun downloadToTemp(
        url: HttpUrl,
        key: String,
        expectedSha256: String,
        expectedBytes: Long?,
        onProgress: (suspend (Long, Long) -> Unit)?,
    ): File {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return attemptDownload(url, key, expectedSha256, expectedBytes, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownloadException) {
                if (!e.retryable || attempt >= maxAttempts) throw e
                sleeper(backoffMillis(attempt))
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (attempt >= maxAttempts) {
                    throw DownloadException("下载失败：$url：${e.message}", retryable = true, cause = e)
                }
                sleeper(backoffMillis(attempt))
            }
        }
    }

    /** 断点文件路径（`{tempDir}/{key}.part`），供测试与“清理未完成下载”使用。 */
    fun partFile(key: String): File = File(tempDir, "${sanitize(key)}.part")

    /** 校验通过后的临时文件路径（`{tempDir}/{key}`）。 */
    fun targetFile(key: String): File = File(tempDir, sanitize(key))

    private suspend fun attemptFetchText(url: HttpUrl, etag: String?): FetchResult = withContext(dispatcher) {
        val builder = Request.Builder().url(url).get().header("Accept-Encoding", "identity")
        if (!etag.isNullOrBlank()) builder.header("If-None-Match", etag)
        execute(builder.build()).use { response ->
            when {
                response.code == HTTP_NOT_MODIFIED -> FetchResult.NotModified
                !response.isSuccessful -> throw statusFailure(url, response.code)
                else -> {
                    val body = response.body ?: throw DownloadException("响应体为空：$url", retryable = true)
                    FetchResult.Fetched(body = body.string(), etag = response.header("ETag"))
                }
            }
        }
    }

    private suspend fun attemptDownload(
        url: HttpUrl,
        key: String,
        expectedSha256: String,
        expectedBytes: Long?,
        onProgress: (suspend (Long, Long) -> Unit)?,
    ): File = withContext(dispatcher) {
        tempDir.mkdirs()
        // 已经下载并校验通过的临时文件（上次进程被杀 / 更早的一次重试留下的）直接复用：
        // 「断网/杀进程后重试不重复下载已校验通过的文件」。
        val target = targetFile(key)
        if (target.isFile && isVerified(target, expectedSha256, expectedBytes)) return@withContext target

        val part = partFile(key)
        var existing = if (part.isFile) part.length() else 0L

        val builder = Request.Builder().url(url).get().header("Accept-Encoding", "identity")
        if (existing > 0L) builder.header("Range", "bytes=$existing-")

        val response = execute(builder.build())
        var append = false
        var total = 0L
        try {
            when (response.code) {
                HTTP_PARTIAL_CONTENT -> {
                    val start = contentRangeStart(response.header("Content-Range"))
                    if (start != existing) {
                        // 服务端给的区间和本地断点对不上：断点作废，重来
                        part.delete()
                        throw DownloadException(
                            "Content-Range 与本地断点不一致（断点 $existing，服务端 $start）：$url",
                            retryable = true,
                        )
                    }
                    append = true
                    val length = response.body?.contentLength() ?: -1L
                    total = if (length >= 0L) existing + length else 0L
                }

                HTTP_OK -> {
                    existing = 0L
                    total = (response.body?.contentLength() ?: -1L).coerceAtLeast(0L)
                }

                HTTP_RANGE_NOT_SATISFIABLE -> {
                    // 断点比服务端文件还长（例如服务端换了包）：丢弃断点后重试
                    part.delete()
                    throw DownloadException("服务端拒绝 Range 续传（416），已丢弃断点：$url", retryable = true)
                }

                else -> throw statusFailure(url, response.code)
            }

            val body = response.body ?: throw DownloadException("响应体为空：$url", retryable = true)
            writeBody(
                source = body.byteStream(),
                part = part,
                append = append,
                startOffset = existing,
                total = total,
                onProgress = onProgress,
            )
        } finally {
            response.close()
        }

        verify(part, url, expectedSha256, expectedBytes)

        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            // 跨卷 / 被占用时退化为复制
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        target
    }

    private fun isVerified(file: File, expectedSha256: String, expectedBytes: Long?): Boolean {
        if (expectedBytes != null && file.length() != expectedBytes) return false
        return Sha256.of(file) == expectedSha256
    }

    private suspend fun writeBody(
        source: InputStream,
        part: File,
        append: Boolean,
        startOffset: Long,
        total: Long,
        onProgress: (suspend (Long, Long) -> Unit)?,
    ) {
        source.use { input ->
            FileOutputStream(part, append).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var done = startOffset
                onProgress?.invoke(done, total)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    done += read
                    onProgress?.invoke(done, total)
                }
                output.flush()
            }
        }
    }

    private fun verify(part: File, url: HttpUrl, expectedSha256: String, expectedBytes: Long?) {
        val actualBytes = part.length()
        if (expectedBytes != null && actualBytes != expectedBytes) {
            if (actualBytes < expectedBytes) {
                // 传输中断：保留 .part，下次续传
                throw DownloadException(
                    "下载不完整（$actualBytes/$expectedBytes 字节）：$url",
                    retryable = true,
                )
            }
            part.delete()
            throw DownloadException(
                "文件大小校验失败（实际 $actualBytes，期望 $expectedBytes 字节）：$url",
                retryable = false,
            )
        }
        val actualSha = Sha256.of(part)
        if (actualSha != expectedSha256) {
            // 内容不对，续传也不会变好：丢弃断点
            part.delete()
            throw DownloadException(
                "sha256 校验失败（实际 $actualSha，期望 $expectedSha256）：$url",
                retryable = false,
            )
        }
    }

    /** 执行请求；协程被取消时取消 OkHttp Call，避免 socket 悬空。 */
    private suspend fun execute(request: Request): Response {
        val call = client.newCall(request)
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) runCatching { call.cancel() }
        }
        try {
            return call.execute()
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            handle?.dispose()
        }
    }

    private fun statusFailure(url: HttpUrl, code: Int): DownloadException {
        val retryable = code >= 500 || code == HTTP_REQUEST_TIMEOUT || code == HTTP_TOO_MANY_REQUESTS
        return DownloadException("HTTP $code：$url", retryable = retryable)
    }

    private fun contentRangeStart(header: String?): Long {
        // 形如 "bytes 1024-2047/4096"
        val value = header?.substringAfter("bytes ", "")?.substringBefore('-')?.trim()
        return value?.toLongOrNull() ?: -1L
    }

    private fun sanitize(key: String): String {
        val safe = key.replace(unsafeKey, "_")
        return safe.ifBlank { "download" }
    }

    private fun backoffMillis(attempt: Int): Long {
        var delayMs = baseBackoffMs
        repeat((attempt - 1).coerceAtLeast(0)) {
            delayMs = (delayMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
        return delayMs
    }

    companion object {
        /** 默认最多尝试次数（含首次）。 */
        const val DEFAULT_MAX_ATTEMPTS: Int = 3

        /** 默认首次退避时长。 */
        const val DEFAULT_BASE_BACKOFF_MS: Long = 500L

        private const val MAX_BACKOFF_MS: Long = 8_000L
        private const val BUFFER_SIZE = 64 * 1024

        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HTTP_NOT_MODIFIED = 304
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
