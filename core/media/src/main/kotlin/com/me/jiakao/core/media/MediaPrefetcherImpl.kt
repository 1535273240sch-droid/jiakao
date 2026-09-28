package com.me.jiakao.core.media

import android.content.Context
import coil3.ImageLoader
import com.me.jiakao.core.model.MediaKind
import com.me.jiakao.core.model.MediaPrefetcher
import com.me.jiakao.core.model.MediaRef
import com.me.jiakao.core.model.MediaStore
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 默认的 [MediaPrefetcher] 实现。
 *
 * 行为约定（任务书 §5）：
 * - **去重**：按内存缓存 key（即 sha256 + 解码变体）去重，重复调用不会把同一张图排两次队；
 * - **限流**：最多 [maxConcurrency] 个并发（默认 3），超出的排队等待 —— 预取绝不能抢占前台滑动的
 *   CPU/IO；
 * - **ANIM 只解首帧**：动图不预热整段动画（那会凭空产生动画解码器，违反"同时活动解码器 ≤ 2"），
 *   只把首帧位图放进缓存，并在此之前确认文件已存在；
 * - **VIDEO 跳过**：视频预热需要 ExoPlayer 实例，由播放器池在真正可见时才创建；
 * - **cancelAll**：取消所有排队中与进行中的预取。
 *
 * 实现说明：这里用 `ImageLoader.execute()` + 自建 `Semaphore` 而不是 `enqueue()`。
 * `enqueue()` 立即返回，无法真正限制并发，也无法精确取消；`execute()` 走同一套
 * 内存缓存/解码管线，用协程取消即可取消尚未开始的解码，语义上更贴近"取消未开始任务"。
 */
class MediaPrefetcherImpl(
    private val context: Context,
    private val store: MediaStore,
    private val imageLoader: ImageLoader,
    private val screenWidthPx: Int = context.resources.displayMetrics.widthPixels,
    maxConcurrency: Int = DEFAULT_CONCURRENCY,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : MediaPrefetcher {

    init {
        require(maxConcurrency > 0) { "maxConcurrency must be > 0" }
    }

    private val permits = Semaphore(maxConcurrency)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val jobs = ConcurrentHashMap.newKeySet<Job>()

    override fun prefetch(refs: List<MediaRef>) {
        if (refs.isEmpty()) return
        for (ref in refs) {
            if (ref.kind == MediaKind.VIDEO) continue
            // 文件还没落盘：先不排队，等 MediaStore.committed 之后调用方再调一次。
            if (store.file(ref) == null) continue

            val firstFrameOnly = ref.kind == MediaKind.ANIM
            val key = if (firstFrameOnly) MediaCacheKeys.firstFrameOf(ref) else MediaCacheKeys.of(ref)
            if (!inFlight.add(key)) continue

            val job = scope.launch {
                try {
                    permits.withPermit {
                        imageLoader.execute(
                            MediaRequests.prefetch(
                                context = context,
                                ref = ref,
                                store = store,
                                widthPx = screenWidthPx,
                                firstFrameOnly = firstFrameOnly,
                            ),
                        )
                        // 预取不关心结果：命中即入内存缓存，失败就算了（前台请求会再试一次）。
                    }
                } finally {
                    inFlight.remove(key)
                }
            }
            jobs.add(job)
            job.invokeOnCompletion { jobs.remove(job) }
        }
    }

    override fun cancelAll() {
        val pending = jobs.toList()
        jobs.clear()
        inFlight.clear()
        pending.forEach { it.cancel() }
    }

    companion object {
        /** 预取并发上限（任务书 §5：≤ 3）。 */
        const val DEFAULT_CONCURRENCY: Int = 3
    }
}
