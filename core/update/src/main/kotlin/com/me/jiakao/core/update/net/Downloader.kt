package com.me.jiakao.core.update.net

import java.io.File
import okhttp3.HttpUrl

/**
 * 下载器抽象。
 *
 * 生产实现是 OkHttp 的 [OkHttpDownloader]；抽成接口是为了让 MediaSync / 更新状态机
 * 可以在不联网的情况下单测（含并发度、失败计数等行为）。
 *
 * 契约（合同 §3.5）：所有下载先写 `cacheDir/dl/` 下的 `.part` 临时文件，校验通过再提交；
 * 支持 Range 断点续传；manifest 使用 ETag / If-None-Match。
 */
interface Downloader {

    /**
     * 拉取小体积文本（`manifest.json`）。
     *
     * @param etag 上次响应的 ETag；服务端返回 304 时结果为 [FetchResult.NotModified]
     */
    suspend fun fetchText(url: HttpUrl, etag: String? = null): FetchResult

    /**
     * 下载文件到临时目录并校验，返回**校验通过**的临时文件（调用方用完负责删除）。
     *
     * 行为：
     * - 已有 `.part` 时带 `Range: bytes=N-` 续传；服务端忽略 Range（200）则从头重下；
     * - 下载完成后校验 `sha256`（必须）与 `bytes`（[expectedBytes] 非空时）；
     * - 校验失败删除 `.part` 并抛 [DownloadException]（`retryable = false`）；
     * - 网络/5xx/408/429 失败指数退避重试，最多尝试 [OkHttpDownloader.maxAttempts] 次；
     * - **取消安全**：协程取消时不删除 `.part`，下次可继续续传。
     *
     * @param key 临时文件名（不含 `.part` 后缀）；内容寻址场景直接传 sha256，天然复用断点
     * @param onProgress `(已下载字节, 总字节)`；总字节未知时为 0
     */
    suspend fun downloadToTemp(
        url: HttpUrl,
        key: String,
        expectedSha256: String,
        expectedBytes: Long? = null,
        onProgress: (suspend (done: Long, total: Long) -> Unit)? = null,
    ): File
}

/** [Downloader.fetchText] 的结果。 */
sealed interface FetchResult {

    /** 拉取成功。 */
    data class Fetched(
        val body: String,
        /** 响应 ETag，下次请求用 `If-None-Match` 带上。 */
        val etag: String?,
    ) : FetchResult

    /** 服务端返回 304：内容未变。 */
    data object NotModified : FetchResult
}
