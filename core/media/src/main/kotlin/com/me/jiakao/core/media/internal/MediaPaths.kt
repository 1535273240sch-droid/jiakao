package com.me.jiakao.core.media.internal

import com.me.jiakao.core.model.MediaRef
import java.io.File

/**
 * 媒体文件路径推导（合同 §1：`<root>/{sha256 前 2 位}/{sha256}.{ext}`）。
 *
 * 只做纯路径拼接，不访问磁盘。sha256 / ext 非法时返回 `null`，
 * 从而把 `../../etc/passwd` 这类越权路径挡在仓库之外。
 */
internal object MediaPaths {

    /** 媒体根目录名：`filesDir/media`。 */
    const val DIR_NAME: String = "media"

    private val SHA256_RE = Regex("[0-9a-f]{64}")
    private val EXT_RE = Regex("[a-z0-9]{1,8}")

    fun root(filesDir: File): File = File(filesDir, DIR_NAME)

    /** 返回 [ref] 对应的目标文件；参数非法返回 `null`。 */
    fun of(root: File, ref: MediaRef): File? {
        val sha = ref.sha256.lowercase()
        if (!SHA256_RE.matches(sha)) return null
        val ext = ref.ext.lowercase()
        if (!EXT_RE.matches(ext)) return null
        return File(File(root, sha.substring(0, 2)), "$sha.$ext")
    }

    /** 从文件名 `{sha}.{ext}` 还原 sha256（小写）；没有扩展名时原样返回。 */
    fun shaOfFileName(name: String): String = name.substringBeforeLast('.', name).lowercase()
}
