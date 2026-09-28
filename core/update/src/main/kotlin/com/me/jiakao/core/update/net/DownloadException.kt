package com.me.jiakao.core.update.net

/**
 * 下载失败。
 *
 * @param retryable 是否值得重试：网络错误、超时、5xx、408、429 为 true；
 *                  4xx（除上述）、sha256/bytes 校验失败为 false（重试也不会变好）。
 */
class DownloadException(
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
