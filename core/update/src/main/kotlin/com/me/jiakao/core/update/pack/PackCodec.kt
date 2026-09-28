package com.me.jiakao.core.update.pack

import com.me.jiakao.core.model.PackRecord
import com.me.jiakao.core.update.util.Sha256
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream

/**
 * 题库包解析（04 独占实现，见 TASK 交付物 1）。
 *
 * 三件事：
 * 1. [parseManifest] —— `manifest.json` → [Manifest]，并做结构与取值校验；
 * 2. [parseLine] —— 题目行 → [PackRecord]（`Upsert` / `Delete`），带行号的严格校验；
 * 3. [stream] —— GZIP（或明文）+ `BufferedReader` **逐行惰性**解析，不把整个题库读进内存。
 *
 * 向前兼容：JSON 解析统一使用 `ignoreUnknownKeys = true`，服务端新增字段不会导致旧客户端失败。
 */
object PackCodec {

    /** 清单文件名（合同 §3）。 */
    const val MANIFEST_NAME: String = "manifest.json"

    /** 离线整包内的题目文件名（合同 §3 bundle）。 */
    const val BANK_NAME: String = "bank.jsonl"

    /** 内部缓冲：64 KB 在流式导入时保持内存平稳。 */
    private const val BUFFER_SIZE = 64 * 1024

    private val GZIP_MAGIC = byteArrayOf(0x1F, 0x8B.toByte())

    /** 统一 JSON 配置：忽略未知字段（向前兼容），不接受隐式类型转换。 */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = false
    }

    private val mediaIndexSerializer =
        kotlinx.serialization.builtins.ListSerializer(MediaEntry.serializer())

    // ───────────────────────── 清单 ─────────────────────────

    /**
     * 解析并校验 `manifest.json`。
     *
     * @throws PackFormatException JSON 非法、`schema` 不支持、`sha256`/`bytes` 等取值非法
     */
    fun parseManifest(text: String, origin: String = MANIFEST_NAME): Manifest {
        val manifest = try {
            json.decodeFromString(Manifest.serializer(), text)
        } catch (e: SerializationException) {
            throw PackFormatException("$origin 不是合法清单 JSON：${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw PackFormatException("$origin 不是合法清单 JSON：${e.message}", e)
        }
        validateManifest(manifest, origin)
        return manifest
    }

    /** 清单结构校验；不合规抛 [PackFormatException]。 */
    fun validateManifest(manifest: Manifest, origin: String = MANIFEST_NAME) {
        fun fail(message: String): Nothing = throw PackFormatException("$origin：$message")
        if (manifest.schema != Manifest.SCHEMA) {
            fail("schema 不支持：${manifest.schema}（当前支持 ${Manifest.SCHEMA}），请升级 App")
        }
        if (manifest.bankVersion < 1) fail("bank_version 非法：${manifest.bankVersion}")
        if (manifest.minAppVersionCode < 1) fail("min_app_version_code 非法：${manifest.minAppVersionCode}")
        manifest.chapters.forEach { chapter ->
            if (chapter.id.isBlank()) fail("chapters 含空 id")
            if (chapter.subject != 1 && chapter.subject != 4) fail("章节 ${chapter.id} 的 subject 非法：${chapter.subject}")
        }
        val chapterIds = HashSet<String>(manifest.chapters.size)
        manifest.chapters.forEach { if (!chapterIds.add(it.id)) fail("chapters 含重复 id：${it.id}") }
        manifest.full?.let { validateFileRef(it, "full", ::fail) }
        manifest.bundle?.let { validateFileRef(it, "bundle", ::fail) }
        manifest.deltas.forEach { delta ->
            if (delta.from < 1 || delta.to <= delta.from) {
                fail("deltas 版本区间非法：from=${delta.from} to=${delta.to}")
            }
            validateFileRef(delta.asFileRef(), "deltas(${delta.from}→${delta.to})", ::fail)
        }
    }

    private fun validateFileRef(ref: FileRef, label: String, fail: (String) -> Nothing) {
        if (ref.url.isBlank()) fail("$label.url 为空")
        if (!Sha256.isHex(ref.sha256)) fail("$label.sha256 非法：${ref.sha256}（应为小写 64 位十六进制）")
        if (ref.bytes <= 0) fail("$label.bytes 非法：${ref.bytes}")
        if (ref.count != null && ref.count < 0) fail("$label.count 非法：${ref.count}")
    }

    /**
     * 解析离线整包内可选的 `media/index.json`（[MediaEntry] 列表）。
     *
     * @throws PackFormatException JSON 非法或条目取值不合规
     */
    fun parseMediaIndex(text: String, origin: String = "media/index.json"): List<MediaEntry> {
        val entries = try {
            json.decodeFromString(mediaIndexSerializer, text)
        } catch (e: SerializationException) {
            throw PackFormatException("$origin 不是合法 JSON：${e.message}", e)
        }
        entries.forEach { entry ->
            if (!Sha256.isHex(entry.sha256)) {
                throw PackFormatException("$origin：sha256 非法：${entry.sha256}")
            }
            if (entry.bytes <= 0) throw PackFormatException("$origin：bytes 非法：${entry.bytes}")
            if (entry.path.isBlank()) throw PackFormatException("$origin：path 为空")
        }
        return entries
    }

    // ───────────────────────── 题目行 ─────────────────────────

    /**
     * 解析单行题目 JSON（合同 §2）。
     *
     * `{"id":"…", …}` → [PackRecord.Upsert]；`{"id":"…","deleted":true}` → [PackRecord.Delete]。
     *
     * @param lineNo 1 基行号，仅用于报错定位
     * @throws PackFormatException 字段缺失、类型错误或取值非法
     */
    fun parseLine(line: String, lineNo: Int = 1): PackRecord {
        if (line.isBlank()) throw PackFormatException(lineNo, "空行不是合法的题目行")
        val dto = try {
            json.decodeFromString(QuestionDto.serializer(), line)
        } catch (e: SerializationException) {
            throw PackFormatException(lineNo, "不是合法 JSON：${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw PackFormatException(lineNo, "不是合法 JSON：${e.message}", e)
        }
        return dto.toRecord(lineNo)
    }

    // ───────────────────────── 流式解析 ─────────────────────────

    /**
     * 惰性流式解析题库包（不整体读入内存）。
     *
     * @param input 题库包字节流；**由本函数负责关闭**（在序列正常迭代完或解析抛错时）
     * @param gzipped `null` 表示按 GZIP 魔数自动识别（`.jsonl.gz` 与 `bank.jsonl` 都能吃），
     *                `true`/`false` 强制指定
     * @throws PackFormatException 行格式错误（带行号）或 GZIP 数据损坏
     *
     * 注意：序列是惰性的，逐行读取发生在迭代时。若调用方中途放弃迭代（既不迭代完也不抛错），
     * 底层流要到 GC 才会释放；正常路径（迭代完 / 迭代中抛异常）都会立即关闭。
     */
    fun stream(input: InputStream, gzipped: Boolean? = null): Sequence<PackRecord> {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, BUFFER_SIZE)
        val source: InputStream = if (gzipped == null) {
            val pushback = PushbackInputStream(buffered, GZIP_MAGIC.size)
            if (isGzip(pushback)) GZIPInputStream(pushback, BUFFER_SIZE) else pushback
        } else if (gzipped) {
            GZIPInputStream(buffered, BUFFER_SIZE)
        } else {
            buffered
        }
        return sequence {
            var lineNo = 0
            try {
                BufferedReader(InputStreamReader(source, Charsets.UTF_8), BUFFER_SIZE).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        lineNo++
                        if (line.isBlank()) continue
                        yield(parseLine(line, lineNo))
                    }
                }
            } catch (e: IOException) {
                throw PackFormatException(lineNo, "读取题库包失败：${e.message}", e)
            }
        }
    }

    /** 流式解析单个题库文件（自动识别 GZIP）。 */
    fun stream(file: File): Sequence<PackRecord> = stream(file.inputStream(), gzipped = null)

    /**
     * 依次流式解析多个题库文件（全量 + 增量链），整体仍是惰性的：
     * 前一个文件迭代完才打开下一个。
     */
    fun stream(files: List<File>): Sequence<PackRecord> = sequence {
        for (file in files) yieldAll(stream(file))
    }

    private fun isGzip(pushback: PushbackInputStream): Boolean {
        val head = ByteArray(GZIP_MAGIC.size)
        var read = 0
        while (read < head.size) {
            val n = pushback.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        if (read > 0) pushback.unread(head, 0, read)
        return read == GZIP_MAGIC.size && head[0] == GZIP_MAGIC[0] && head[1] == GZIP_MAGIC[1]
    }
}
