package com.me.jiakao.core.update

import com.me.jiakao.core.model.MediaKind
import com.me.jiakao.core.model.MediaRef
import com.me.jiakao.core.model.MediaStore
import com.me.jiakao.core.update.net.DownloadException
import com.me.jiakao.core.update.net.Downloader
import com.me.jiakao.core.update.util.Urls
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl

/**
 * 媒体补齐结果。
 *
 * @param total 本次需要下载的媒体数（已扣掉本地已有）
 * @param done 成功提交数
 * @param failed 失败数（合同 §3.4：媒体失败不回滚题目，下次重试）
 * @param failedRefs 失败条目，便于下次优先重试或日志
 */
data class MediaSyncResult(
    val total: Int,
    val done: Int,
    val failed: Int,
    val failedRefs: List<MediaRef> = emptyList(),
) {
    /** 是否全部就绪（决定能否做 `retain()` 垃圾回收）。 */
    val allReady: Boolean get() = failed == 0
}

/**
 * 媒体补齐（合同 §3.4）：`allMediaRefs() − MediaStore.has` → 并发下载 → 校验 → `commit`。
 *
 * - 并发度默认 4（[DEFAULT_CONCURRENCY]）；
 * - 优先级：调用方给的 [sync] priority 集合最先，然后静态图（小文件）按体积升序，
 *   最后动图/视频按体积升序 —— 先把“代价小、收益大”的图补齐，动图（大文件）殿后；
 * - 单个媒体失败只计数，不影响题目导入，也不中断其它媒体；下次同步会重试（`.part` 可续传）；
 * - 取消安全：协程取消时向调用方抛 [CancellationException]，已提交的媒体保留。
 */
class MediaSync(
    private val downloader: Downloader,
    private val mediaStore: MediaStore,
    private val concurrency: Int = DEFAULT_CONCURRENCY,
) {

    /**
     * 补齐缺失媒体。
     *
     * @param mediaBase 媒体根地址（`源地址 + manifest.media_base`，以 `/` 结尾）
     * @param refs 题库引用的全部媒体（通常来自 `QuestionStore.allMediaRefs()`，已去重）
     * @param priority 优先下载的 sha256 集合（调用方给，例如当前正在看的题目）
     * @param onProgress `(完成数, 总数)`，每完成一个回调一次
     */
    suspend fun sync(
        mediaBase: HttpUrl,
        refs: List<MediaRef>,
        priority: Set<String> = emptySet(),
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): MediaSyncResult {
        val pending = order(refs, priority).filterNot { mediaStore.has(it) }
        val total = pending.size
        onProgress(0, total)
        if (total == 0) return MediaSyncResult(total = 0, done = 0, failed = 0)

        val permits = Semaphore(concurrency.coerceAtLeast(1))
        val doneCounter = AtomicInteger(0)
        val failedRefs = Collections.synchronizedList(ArrayList<MediaRef>())
        val progressLock = Mutex()

        coroutineScope {
            val jobs = pending.map { ref ->
                async {
                    permits.withPermit {
                        val ok = downloadOne(mediaBase, ref)
                        val done = doneCounter.incrementAndGet()
                        if (!ok) failedRefs += ref
                        progressLock.withLock { onProgress(done, total) }
                    }
                }
            }
            jobs.awaitAll()
        }

        val failed = failedRefs.size
        return MediaSyncResult(
            total = total,
            done = total - failed,
            failed = failed,
            failedRefs = failedRefs.toList(),
        )
    }

    /**
     * 垃圾回收：删除不再被引用的媒体（合同 §3.6）。
     * 只在「全量更新成功 + 媒体全部就绪」时由调用方触发。
     */
    suspend fun retain(keep: Collection<MediaRef>): Int = mediaStore.retain(keep.mapTo(HashSet()) { it.sha256 })

    private suspend fun downloadOne(mediaBase: HttpUrl, ref: MediaRef): Boolean {
        val url = Urls.mediaUrl(mediaBase, ref) ?: return false
        val tmp = try {
            downloader.downloadToTemp(
                url = url,
                key = ref.sha256,
                expectedSha256 = ref.sha256,
                expectedBytes = ref.bytes,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 媒体是“尽力而为”：任何单个媒体的失败都不影响题库可用性（合同 §3.4）
            return false
        }
        return try {
            mediaStore.commit(ref, tmp)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    companion object {

        /** 默认并发下载数（TASK 交付物 5）。 */
        const val DEFAULT_CONCURRENCY: Int = 4

        /**
         * 下载顺序：priority 集合 → 静态图（按 bytes 升序）→ 动图/视频（按 bytes 升序）。
         *
         * 「含动图较少的小文件先下」的落地方式：动图/视频体积通常远大于静图，
         * 先补静图能让更多题目立刻可用，动图走后台慢慢补。
         */
        fun order(refs: List<MediaRef>, priority: Set<String> = emptySet()): List<MediaRef> =
            refs.sortedWith(
                compareBy(
                    { if (it.sha256 in priority) 0 else 1 },
                    { if (it.kind == MediaKind.IMAGE) 0 else 1 },
                    { it.bytes },
                    { it.sha256 },
                ),
            )
    }
}
