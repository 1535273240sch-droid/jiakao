package com.me.jiakao.core.media

import android.content.Context
import coil3.ImageLoader
import com.me.jiakao.core.media.internal.MediaStores
import com.me.jiakao.core.model.MediaPrefetcher
import com.me.jiakao.core.model.MediaStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * `:core:media` 的 Hilt 绑定（合同 §6）：提供 [MediaStore]、[MediaPrefetcher] 与 Coil [ImageLoader] 单例。
 *
 * - [MediaStore] 与 Compose 层共用同一个实例（见 `MediaStores`），保证 `committed` 事件与存在性缓存
 *   在 Hilt 图与 UI 之间一致；`02`/`04` 注入到的就是 UI 实际在用的那一个。
 * - [ImageLoader] 由 [MediaImageLoader] 构建：内存缓存 ≤ 80MB、禁用磁盘/网络缓存、关闭 crossfade；
 *   动图能力（动态 WebP / GIF）由 `coil-gif` 的 `AnimatedImageDecoder`（API 28+）经 Coil
 *   ServiceLoader 自动注册，无需替换 `ComponentRegistry`。
 */
@Module
@InstallIn(SingletonComponent::class)
object MediaModule {

    /** 媒体仓库：`filesDir/media`，进程内唯一。 */
    @Provides
    @Singleton
    fun provideMediaStore(@ApplicationContext context: Context): MediaStore = MediaStores.of(context)

    /** Coil 单例；`:app` 若实现 `SingletonImageLoader.Factory`，返回 [MediaImageLoader.of] 即可对齐。 */
    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext context: Context): ImageLoader = MediaImageLoader.of(context)

    /** 预取器：内部限流 ≤ 3 并发、按 sha 去重。 */
    @Provides
    @Singleton
    fun provideMediaPrefetcher(
        @ApplicationContext context: Context,
        store: MediaStore,
        imageLoader: ImageLoader,
    ): MediaPrefetcher = MediaPrefetcherImpl(
        context = context,
        store = store,
        imageLoader = imageLoader,
    )
}
