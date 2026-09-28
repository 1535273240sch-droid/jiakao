package com.me.jiakao.core.media

import com.me.jiakao.core.media.internal.MediaPaths
import com.me.jiakao.core.model.MediaRef
import com.me.jiakao.core.model.MediaStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * [MediaStore] 的默认实现：内容寻址（content-addressed）的本地媒体仓库。
 *
 * 磁盘布局（合同 §1）：`<mediaDir>/{sha256 前 2 位}/{sha256}.{ext}`，其中 `mediaDir` 约定为
 * `filesDir/media`（见 [mediaDirOf]）。
 *
 * 设计要点：
 * - [file] 不做磁盘遍历，直接拼路径后 `isFile`，并用一个小型 LRU 缓存正面/负面的存在性结果，
 *   使滑动列表里的重复查询退化为纯内存操作。
 * - [commit] 以 64KB 缓冲流式计算 SHA-256，同时累计字节数；两者与 [MediaRef] 不一致就删除临时文件并
 *   返回 `false`（绝不落盘）。校验通过后用原子移动提交，跨卷时退化为 copy + delete。
 * - 同一个 sha256 的并发 [commit] 被串行化，后到的调用直接返回 `true`（内容寻址下文件必然一致）。
 * - [usedBytes] 由增量账本（`sha → 字节数`）维护，只在首次调用时扫一次盘初始化；
 *   账本只记本 store 认可的文件，因此进程外写进来的文件被 [retain] 删除时不会把计数减成负数。
 *
 * 本类不持有 [android.content.Context]，只依赖一个目录，因此可以在纯 JVM 单元测试中直接实例化。
 *
 * @param mediaDir 媒体根目录（`filesDir/media`）。
 * @param io 执行哈希与文件移动的调度器；测试可注入单线程调度器。
 * @param existenceCacheSize 存在性 LRU 容量。
 */
class MediaStoreImpl(
    mediaDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    existenceCacheSize: Int = DEFAULT_EXISTENCE_CACHE,
) : MediaStore {

    /** 媒体根目录，便于调试与统计。 */
    val root: File = mediaDir

    private val existence = ExistenceCache(existenceCacheSize)
    private val locks = KeyedLock()

    /**
     * 增量字节账本：`sha → 字节数`，只记录**本 store 知道**的文件。
     *
     * 为什么不是"一个总数字 + 每次删除都减"：那样一旦有进程外写进来的文件（04 的下载器、
     * adb push、离线包解压），`retain` 删掉它就会把账减成负数，把真实占用抹平。
     * 用账本记录已计入的 sha，删除时只减账本里有的条目，语义就是"本 store 认可的占用"。
     */
    private val sizes = ConcurrentHashMap<String, Long>()
    private val indexReady = AtomicBoolean(false)
    private val used = AtomicLong(0L)

    private val _committed = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val committed: SharedFlow<String> = _committed.asSharedFlow()

    override fun file(ref: MediaRef): File? {
        val path = pathOf(ref) ?: return null
        val cached = existence.get(path)
        if (cached != null) {
            return if (cached) File(path) else null
        }
        val f = File(path)
        val exists = f.isFile
        existence.put(path, exists)
        return if (exists) f else null
    }

    override suspend fun commit(ref: MediaRef, tmp: File): Boolean {
        val sha = ref.sha256.lowercase()
        val targetPath = pathOf(ref)
        if (targetPath == null) {
            withContext(io) { tmp.delete() }
            return false
        }
        // 同一 sha 的并发 commit 串行化。
        return locks.with(sha) {
            withContext(io) { commitLocked(ref, sha, targetPath, tmp) }
        }
    }

    private fun commitLocked(ref: MediaRef, sha: String, targetPath: String, tmp: File): Boolean {
        if (!tmp.isFile) return false
        val (actualSha, actualBytes) = try {
            tmp.sha256AndLength()
        } catch (e: IOException) {
            tmp.delete()
            return false
        }
        if (actualSha != sha || actualBytes != ref.bytes) {
            // 校验失败：不落盘。
            tmp.delete()
            return false
        }
        val dst = File(targetPath)
        if (dst.isFile) {
            // 内容寻址：目标已存在说明同 sha 已被提交过（并发或重试），直接视为成功。
            tmp.delete()
            existence.put(targetPath, true)
            return true
        }
        dst.parentFile?.mkdirs()
        if (!moveAtomically(tmp, dst)) {
            tmp.delete()
            return false
        }
        existence.put(targetPath, true)
        recordAdded(sha, dst.length())
        _committed.tryEmit(sha)
        return true
    }

    /**
     * 丢弃 [ref] 的存在性缓存并重新查盘，返回文件是否存在。
     *
     * 存在性缓存里同时存"在/不在"两种结论；若文件是由**进程外**写进来的
     * （04 的下载器另起进程、adb push、离线包解压），缓存可能残留 `false`。
     * 调用方（`QuizMedia` 在回到前台时）用本方法强制复查一次。
     */
    fun refresh(ref: MediaRef): Boolean {
        val path = pathOf(ref) ?: return false
        existence.invalidate(path)
        return file(ref) != null
    }

    override suspend fun retain(keepSha: Set<String>): Int = withContext(io) {
        ensureIndex()
        val keep = HashSet<String>(keepSha.size * 2)
        for (s in keepSha) keep.add(s.lowercase())

        var removed = 0
        val buckets = root.listFiles() ?: return@withContext 0
        for (bucket in buckets) {
            if (!bucket.isDirectory) continue
            val files = bucket.listFiles() ?: continue
            for (f in files) {
                if (!f.isFile) continue
                val sha = MediaPaths.shaOfFileName(f.name)
                if (sha in keep) continue
                if (f.delete()) {
                    removed++
                    existence.invalidate(f.path)
                    recordRemoved(sha)
                }
            }
            // 顺手清理空目录（失败不影响 retain 语义）。
            if (bucket.list()?.isEmpty() == true) bucket.delete()
        }
        removed
    }

    override fun usedBytes(): Long {
        ensureIndex()
        return used.get().coerceAtLeast(0L)
    }

    // ───────── 内部实现 ─────────

    /** 路径推导；sha/ext 非法（防目录穿越）时返回 null。 */
    private fun pathOf(ref: MediaRef): String? = MediaPaths.of(root, ref)?.path

    /** 首次访问时扫一次盘建立账本；之后完全靠增量维护，`usedBytes()` 不再碰磁盘。 */
    private fun ensureIndex() {
        if (!indexReady.compareAndSet(false, true)) return
        var total = 0L
        root.listFiles()?.forEach { bucket ->
            if (!bucket.isDirectory) return@forEach
            bucket.listFiles()?.forEach { f ->
                if (f.isFile) {
                    val length = f.length()
                    sizes[MediaPaths.shaOfFileName(f.name)] = length
                    total += length
                }
            }
        }
        used.set(total)
    }

    /** 索引尚未建立时跳过记录：之后的首次扫描会把盘上已有文件一并计入。 */
    private fun recordAdded(sha: String, length: Long) {
        if (!indexReady.get()) return
        sizes[sha] = length
        used.addAndGet(length)
    }

    /** 只减账本里记过的 sha：进程外写入、未计入的文件被删除时不会把账减成负数。 */
    private fun recordRemoved(sha: String) {
        val length = sizes.remove(sha) ?: return
        used.addAndGet(-length)
    }

    private fun moveAtomically(src: File, dst: File): Boolean = try {
        Files.move(src.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE)
        true
    } catch (e: AtomicMoveNotSupportedException) {
        copyAndDelete(src, dst)
    } catch (e: IOException) {
        // 部分文件系统（如某些 FAT/网络卷）在目标已存在或跨卷时抛 IOException。
        when {
            dst.isFile -> { src.delete(); true }
            else -> copyAndDelete(src, dst)
        }
    }

    private fun copyAndDelete(src: File, dst: File): Boolean = try {
        src.copyTo(dst, overwrite = true)
        src.delete()
        dst.isFile
    } catch (e: IOException) {
        false
    }

    companion object {
        /** `file()` 存在性缓存默认容量。 */
        const val DEFAULT_EXISTENCE_CACHE: Int = 256

        /** 合同 §1 约定的媒体根目录：`filesDir/media`。 */
        fun mediaDirOf(filesDir: File): File = MediaPaths.root(filesDir)
    }
}

/** 流式计算 SHA-256（64KB 缓冲）并返回小写十六进制与字节数。 */
internal fun File.sha256AndLength(bufferSize: Int = 64 * 1024): Pair<String, Long> {
    val digest = MessageDigest.getInstance("SHA-256")
    var length = 0L
    inputStream().use { ins ->
        val buf = ByteArray(bufferSize)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
            length += n
        }
    }
    return digest.digest().toHex() to length
}

internal fun ByteArray.toHex(): String {
    val hex = "0123456789abcdef"
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = hex[v ushr 4]
        out[i * 2 + 1] = hex[v and 0x0F]
    }
    return String(out)
}

/** 极简 LRU（LinkedHashMap accessOrder），线程安全。 */
internal class ExistenceCache(private val maxSize: Int) {
    private val map = object : LinkedHashMap<String, Boolean>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > maxSize
    }

    @Synchronized
    fun get(key: String): Boolean? = map[key]

    @Synchronized
    fun put(key: String, value: Boolean) {
        map[key] = value
    }

    @Synchronized
    fun invalidate(key: String) {
        map.remove(key)
    }
}

/**
 * 按 key 串行化的异步锁；同一 key 上的调用排队执行，最后一个离开时回收锁对象，避免 map 无界增长。
 */
internal class KeyedLock {
    private class Entry {
        val mutex = Mutex()
        var waiters = 0
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    suspend fun <T> with(key: String, block: suspend () -> T): T {
        val entry = entries.compute(key) { _, cur -> (cur ?: Entry()).also { it.waiters++ } }!!
        try {
            return entry.mutex.withLock { block() }
        } finally {
            entries.compute(key) { _, cur ->
                if (cur === entry) {
                    entry.waiters--
                    if (entry.waiters <= 0) null else entry
                } else {
                    cur
                }
            }
        }
    }
}
