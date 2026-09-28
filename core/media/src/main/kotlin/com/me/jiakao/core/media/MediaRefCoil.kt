package com.me.jiakao.core.media

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.me.jiakao.core.media.internal.MediaPaths
import com.me.jiakao.core.model.MediaRef
import com.me.jiakao.core.model.MediaStore
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * 内存缓存 key 的唯一来源。
 *
 * 为什么显式设置 key 而不是让 Coil 从 `data` 推导：
 * 1. `MediaRef` 是 data class，`hashCode` 会随 `bytes`/`width` 等字段变化，而媒体内容是
 *    **内容寻址**的 —— 只要 sha256 不变，解码结果就可以复用。
 * 2. Coil 默认会把 `Size` 拼进 key（`needsSizeInCacheKey`），那样"预取（屏幕宽）"与
 *    "控件按自身尺寸解码"就会落在两条缓存上，预取等于白做。显式 key 时 Coil 走 fast path，
 *    不再混入尺寸，于是**一个 sha 只解码一次**，滑动回看零成本。
 * 3. 不同解码意图必须分开，否则会互相污染：
 *    - [of]：常规显示（列表/题目卡片）与预取共用，按最宽消费者（屏幕宽）解码；
 *    - [firstFrameOf]：动图降级显示的"仅首帧"；
 *    - [viewerOf]：全屏查看器，尺寸更大，单独一份，避免全屏看到的是列表用的低分辨率图。
 */
internal object MediaCacheKeys {

    /** 常规显示 / 预取。 */
    fun of(ref: MediaRef): String = build(ref, null)

    /** 仅首帧（动图超出解码预算时的降级显示）。 */
    fun firstFrameOf(ref: MediaRef): String = build(ref, "static")

    /** 全屏查看器。 */
    fun viewerOf(ref: MediaRef): String = build(ref, "full")

    private fun build(ref: MediaRef, variant: String?): String = buildString {
        append("media:")
        append(ref.sha256.lowercase())
        append(':')
        append(ref.ext.lowercase())
        if (variant != null) {
            append(':')
            append(variant)
        }
    }
}

/**
 * 把 [MediaRef] 映射为内存缓存 key 的 [Keyer]。
 *
 * 本模块自己发起的请求会直接设置 [coil3.request.ImageRequest.Builder.memoryCacheKey]
 * （值同样来自 [MediaCacheKeys]）；这个 Keyer 是给"把 `MediaRef` 注册进自定义
 * `ComponentRegistry`"的场景准备的，两条路径产出的 key 完全一致。
 */
class MediaRefKeyer : Keyer<MediaRef> {
    override fun key(data: MediaRef, options: Options): String = MediaCacheKeys.of(data)
}

/**
 * 直接从本地媒体仓库读取文件的 [Fetcher]（不经网络、不经磁盘缓存）。
 *
 * 文件缺失时 [fetch] 返回 `null` —— 这是 Coil [Fetcher] 契约里"明确的 miss"：
 * Coil 会继续尝试下一个 Fetcher，最终以 `ErrorResult` 结束（**不会**抛异常、不会崩溃），
 * UI 层依靠 `MediaStore.committed` 在文件补齐后重新发起请求。
 *
 * @param fileProvider 每次 fetch 时解析文件；设计成惰性是为了覆盖"创建 Fetcher 之后文件才落盘"
 *   的竞态。
 */
class MediaRefFetcher(private val fileProvider: () -> File?) : Fetcher {

    /** 固定文件的便捷构造（测试/调试用）。 */
    constructor(file: File) : this({ file })

    /** 通过 [MediaStore] 解析 [ref]。 */
    constructor(store: MediaStore, ref: MediaRef) : this({ store.file(ref) })

    override suspend fun fetch(): FetchResult? {
        val file = fileProvider() ?: return null
        if (!file.isFile) return null
        return SourceFetchResult(
            source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
            mimeType = mimeTypeOf(file.name),
            dataSource = DataSource.DISK,
        )
    }

    /** 以 [MediaStore] 解析 [MediaRef] 的 Fetcher 工厂。 */
    class Factory(private val store: MediaStore) : Fetcher.Factory<MediaRef> {
        override fun create(data: MediaRef, options: Options, imageLoader: ImageLoader): Fetcher =
            MediaRefFetcher(store, data)
    }

    companion object {
        fun mimeTypeOf(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "mp4" -> "video/mp4"
            else -> null
        }

        /** 目标文件路径（不做磁盘访问），供调试与测试使用。 */
        fun pathOf(root: File, ref: MediaRef): File? = MediaPaths.of(root, ref)
    }
}
