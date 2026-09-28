package com.me.jiakao.core.update.util

import com.me.jiakao.core.model.MediaRef
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 题库源地址与相对路径解析。
 *
 * 服务端为纯静态文件（合同 §3），manifest 内的 `url`、`media_base` 既可能是相对路径
 * （`full/bank-v12.jsonl.gz`）也可能是绝对 URL，这里统一处理。
 */
object Urls {

    /** 以 `/` 结尾、且为 http(s) 的规范化源地址。 */
    fun normalizeSource(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val url = trimmed.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("题库源地址必须是合法的 http:// 或 https:// 地址：$raw")
        // HttpUrl.toString() 对空路径会补 "/"，因此结果天然以 "/" 结尾
        return if (url.encodedPath.endsWith("/")) url.toString() else "$url/"
    }

    /** 解析源地址；非法返回 null。 */
    fun sourceOrNull(raw: String): HttpUrl? = raw.trim().takeIf { it.isNotEmpty() }?.toHttpUrlOrNull()

    /** 相对路径 → 绝对地址；已是绝对地址时原样返回；无法解析返回 null。 */
    fun resolve(base: HttpUrl, path: String): HttpUrl? {
        val p = path.trim()
        if (p.isEmpty()) return base
        if (p.startsWith("http://", ignoreCase = true) || p.startsWith("https://", ignoreCase = true)) {
            return p.toHttpUrlOrNull()
        }
        return base.resolve(p)
    }

    /** 媒体内容寻址相对路径：`{sha[0:2]}/{sha}.{ext}`（合同 §3）。 */
    fun mediaPath(ref: MediaRef): String = "${ref.sha256.take(2)}/${ref.sha256}.${ref.ext}"

    /** 媒体绝对地址：`media_base + {sha[0:2]}/{sha}.{ext}`。 */
    fun mediaUrl(mediaBase: HttpUrl, ref: MediaRef): HttpUrl? = mediaBase.resolve(mediaPath(ref))
}
