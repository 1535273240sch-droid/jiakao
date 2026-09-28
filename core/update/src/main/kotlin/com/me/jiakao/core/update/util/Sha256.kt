package com.me.jiakao.core.update.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * SHA-256 工具。
 *
 * 输出统一为小写 64 位十六进制（合同 §2：`sha256` 为文件内容小写 64 位十六进制），
 * 因此可以安全地用于媒体内容寻址路径与校验比对。
 */
object Sha256 {

    /** 十六进制摘要长度。 */
    const val HEX_LENGTH: Int = 64

    private const val BUFFER_SIZE = 64 * 1024
    private val HEX = "0123456789abcdef".toCharArray()

    /** 是否为合法的小写十六进制 sha256（长度 64、字符集 `[0-9a-f]`）。 */
    fun isHex(value: String): Boolean {
        if (value.length != HEX_LENGTH) return false
        for (c in value) {
            val ok = (c in '0'..'9') || (c in 'a'..'f')
            if (!ok) return false
        }
        return true
    }

    /** 字节数组摘要（小写十六进制）。 */
    fun of(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return hex(digest.digest(bytes))
    }

    /** 文件内容摘要（流式读取，不整体载入内存）。 */
    fun of(file: File): String = file.inputStream().buffered(BUFFER_SIZE).use { of(it) }

    /**
     * 流内容摘要。**不关闭** [input]，由调用方负责其生命周期
     * （下载校验场景下需要拿同一流继续处理）。
     */
    fun of(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        return hex(digest.digest())
    }

    /** 摘要结果转小写十六进制。 */
    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }
}
