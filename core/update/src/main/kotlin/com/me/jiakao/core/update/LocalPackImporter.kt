package com.me.jiakao.core.update

import com.me.jiakao.core.model.MediaRef
import com.me.jiakao.core.model.MediaStore
import com.me.jiakao.core.model.QuestionStore
import com.me.jiakao.core.model.UpdateState
import com.me.jiakao.core.update.pack.Manifest
import com.me.jiakao.core.update.pack.PackCodec
import com.me.jiakao.core.update.pack.PackFormatException
import com.me.jiakao.core.update.util.Sha256
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** 离线包导入失败：包损坏、缺少必需条目、内部错误。 */
open class LocalPackException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 离线包内容损坏（结构缺失、字节与 sha256 不符、题目行非法）。 */
class LocalPackCorruptException(message: String, cause: Throwable? = null) : LocalPackException(message, cause)

/** 离线包要求的 App 版本高于本机；必须提示用户升级 App。 */
class LocalPackAppVersionException(
    val requiredVersionCode: Int,
    val currentVersionCode: Int,
) : LocalPackException("请先升级 App（离线包要求 versionCode ≥ $requiredVersionCode，当前 $currentVersionCode）")

/**
 * 离线整包（`bundle-vN.zip`）导入结果。
 *
 * @param version 导入后的题库版本
 * @param recordsRead 从 `bank.jsonl` 读到的记录数（含删除行）
 * @param mediaTotal 需要落盘的媒体数（仅统计题目真正引用的）
 * @param mediaDone 成功提交数
 * @param mediaFailed 失败数（合同 §3.4：失败仅计数，下次可重试）
 */
data class LocalPackResult(
    val version: Int,
    val recordsRead: Int,
    val mediaTotal: Int,
    val mediaDone: Int,
    val mediaFailed: Int,
)

/**
 * 离线整包导入（TASK 交付物 6）。
 *
 * 流程（**两遍策略**，因为 ZIP 条目顺序不保证）：
 * 1. 落盘：整包流式解到 `cacheDir/dl/bundle-<uuid>/`，媒体条目边写边核对文件名里的 sha256
 *    —— 任何损坏在这一步暴露，**此时还没碰数据库**；
 * 2. 导入：`manifest.json` → `applyPack(newVersion, chapters, records, replaceAll = true)`，
 *    单事务（01 的 `QuestionStore` 保证），失败回滚、版本不动；
 * 3. 媒体：把落盘的媒体逐条 `MediaStore.commit`，失败仅计数；全部就绪则 `retain()`
 *    （整包等价于一次全量更新，见合同 §3.6）。
 *
 * 因此「损坏包」的语义是：明确报错，且现有题库与版本号完全不变。
 *
 * @param spoolRoot 落盘目录（合同 §1：`cacheDir/dl/`）
 * @param appVersionCode 取当前 App versionCode，用于校验 `min_app_version_code`
 */
class LocalPackImporter(
    private val questionStore: QuestionStore,
    private val mediaStore: MediaStore,
    private val spoolRoot: File,
    private val ioDispatcher: CoroutineDispatcher,
    private val appVersionCode: () -> Int,
) {

    /**
     * 导入离线包。
     *
     * @param input 离线包字节流；**由本函数负责关闭**
     * @param onState 状态回调：导入题目时 [UpdateState.Importing]，落媒体时 [UpdateState.DownloadingMedia]
     * @throws LocalPackException 包损坏 / 缺条目 / App 版本过低
     */
    suspend fun import(
        input: InputStream,
        onState: suspend (UpdateState) -> Unit = {},
    ): LocalPackResult {
        val spoolDir = File(spoolRoot, "bundle-${UUID.randomUUID()}")
        try {
            return withContext(ioDispatcher) {
                spoolDir.mkdirs()
                val spooled = try {
                    spool(input, spoolDir)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LocalPackException) {
                    throw e
                } catch (e: IOException) {
                    throw LocalPackCorruptException("离线包无法读取（不是合法的 ZIP？）：${e.message}", e)
                }

                val manifest = parseManifest(spooled)
                checkAppVersion(manifest)

                onState(UpdateState.Importing)
                var recordsRead = 0
                try {
                    questionStore.applyPack(
                        newVersion = manifest.bankVersion,
                        chapters = manifest.chaptersOrNull(),
                        records = PackCodec.stream(spooled.bankFile).onEach { recordsRead++ },
                        replaceAll = true,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LocalPackException) {
                    throw e
                } catch (e: PackFormatException) {
                    throw LocalPackCorruptException("离线包题目数据损坏：${e.message}", e)
                } catch (e: Exception) {
                    throw LocalPackException("离线包导入失败：${e.message}", e)
                }

                val media = commitMedia(spooled, onState)
                LocalPackResult(
                    version = manifest.bankVersion,
                    recordsRead = recordsRead,
                    mediaTotal = media.total,
                    mediaDone = media.done,
                    mediaFailed = media.failed,
                )
            }
        } finally {
            spoolDir.deleteRecursively()
        }
    }

    // ───────────────────────── 第一遍：落盘 + 校验 ─────────────────────────

    private class Spooled(
        val manifestFile: File,
        val bankFile: File,
        val mediaIndexFile: File?,
        val mediaFiles: Map<String, File>,
    )

    private class MediaSummary(val total: Int, val done: Int, val failed: Int)

    private fun spool(input: InputStream, spoolDir: File): Spooled {
        val mediaDir = File(spoolDir, "media")
        var manifestFile: File? = null
        var bankFile: File? = null
        var indexFile: File? = null
        val mediaFiles = LinkedHashMap<String, File>()

        ZipInputStream(BufferedInputStream(input, BUFFER_SIZE)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                try {
                    if (entry.isDirectory) continue
                    val path = normalize(entry.name)
                    val base = path.substringAfterLast('/')
                    when {
                        path.endsWith("media/index.json") -> {
                            val target = File(spoolDir, "media-index.json")
                            copyEntry(zip, target)
                            indexFile = target
                        }

                        path.contains("media/") -> {
                            val relative = path.substringAfterLast("media/")
                            val segments = relative.split('/')
                            if (segments.size != 2) continue
                            val sha = segments[1].substringBeforeLast('.')
                            val ext = segments[1].substringAfterLast('.', "")
                            if (!Sha256.isHex(sha) || ext.isEmpty()) continue
                            if (mediaFiles.containsKey(sha)) continue
                            val target = File(mediaDir, "$sha.$ext")
                            val actual = copyEntryWithSha(zip, target)
                            if (actual != sha) {
                                target.delete()
                                throw LocalPackCorruptException(
                                    "离线包损坏：media/$relative 的内容与文件名 sha256 不一致（实际 $actual）",
                                )
                            }
                            mediaFiles[sha] = target
                        }

                        base == "manifest.json" && path.count { it == '/' } <= 1 -> {
                            val target = File(spoolDir, "manifest.json")
                            copyEntry(zip, target)
                            manifestFile = target
                        }

                        base == "bank.jsonl" || base == "bank.jsonl.gz" || base == "bank.json" -> {
                            val target = File(spoolDir, "bank.jsonl")
                            copyEntry(zip, target)
                            bankFile = target
                        }

                        // 其它条目（README、notes.txt 等）忽略
                        else -> Unit
                    }
                } finally {
                    zip.closeEntry()
                }
            }
        }

        val manifest = manifestFile ?: throw LocalPackCorruptException("离线包缺少 manifest.json")
        val bank = bankFile ?: throw LocalPackCorruptException("离线包缺少 bank.jsonl")
        return Spooled(manifest, bank, indexFile, mediaFiles)
    }

    private fun parseManifest(spooled: Spooled): Manifest {
        val text = try {
            spooled.manifestFile.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            throw LocalPackCorruptException("离线包 manifest.json 读取失败：${e.message}", e)
        }
        return try {
            PackCodec.parseManifest(text, origin = "离线包 manifest.json")
        } catch (e: PackFormatException) {
            throw LocalPackCorruptException(e.message ?: "离线包 manifest.json 非法", e)
        }
    }

    private fun checkAppVersion(manifest: Manifest) {
        val current = appVersionCode()
        if (manifest.minAppVersionCode > current) {
            throw LocalPackAppVersionException(manifest.minAppVersionCode, current)
        }
    }

    // ───────────────────────── 第三遍：媒体提交 ─────────────────────────

    private suspend fun commitMedia(
        spooled: Spooled,
        onState: suspend (UpdateState) -> Unit,
    ): MediaSummary {
        if (spooled.mediaFiles.isEmpty()) return MediaSummary(0, 0, 0)

        // 以导入后的题库引用为准：包内多余（无人引用）的媒体不落盘
        val referenced = questionStore.allMediaRefs().associateBy { it.sha256 }
        val targets = spooled.mediaFiles.filterKeys { it in referenced }
        if (targets.isEmpty()) return MediaSummary(0, 0, 0)

        val indexEntries = spooled.mediaIndexFile?.let { file ->
            runCatching { PackCodec.parseMediaIndex(file.readText(Charsets.UTF_8)) }
                .getOrNull()?.associateBy { it.sha256 }
        }

        onState(UpdateState.DownloadingMedia(0, targets.size))
        var done = 0
        var failed = 0
        targets.forEach { (sha, file) ->
            val ref = referenced.getValue(sha)
            val indexOk = indexEntries?.get(sha)?.let { it.bytes == ref.bytes } ?: true
            val ok = indexOk && commitQuietly(ref, file)
            if (ok) done++ else failed++
            onState(UpdateState.DownloadingMedia(done + failed, targets.size))
        }

        if (failed == 0) {
            // 整包 = 一次全量更新；全部就绪才做垃圾回收（合同 §3.6）
            retainQuietly(referenced.values)
        }
        return MediaSummary(targets.size, done, failed)
    }

    /** `MediaStore.commit` 失败只计数，不影响已导入的题目。 */
    private suspend fun commitQuietly(ref: MediaRef, file: File): Boolean = try {
        mediaStore.commit(ref, file)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun retainQuietly(keep: Collection<MediaRef>) {
        try {
            mediaStore.retain(keep.mapTo(HashSet()) { it.sha256 })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 垃圾回收失败不影响本次导入结果
        }
    }

    // ───────────────────────── 工具 ─────────────────────────

    private fun copyEntry(zip: ZipInputStream, target: File) {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            out.flush()
        }
    }

    /** 边写盘边算 sha256（不缓存整条内容在内存里）。 */
    private fun copyEntryWithSha(zip: ZipInputStream, target: File): String {
        target.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(target).use { out ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                digest.update(buffer, 0, read)
            }
            out.flush()
        }
        return Sha256.hex(digest.digest())
    }

    private fun normalize(name: String): String =
        name.replace('\\', '/').removePrefix("./").trimStart('/')

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
