package com.me.jiakao.core.media.internal

import android.content.Context
import com.me.jiakao.core.media.MediaStoreImpl

/**
 * 进程内唯一的 [MediaStoreImpl]。
 *
 * 为什么是全局单例而不是每次新建：`MediaStoreImpl.file()` 带一层存在性 LRU，而 `committed`
 * 事件是**按实例**发射的。Compose 层拿不到构造注入，只能从 Context 取；若 UI 与 Hilt 图各自
 * new 一个实例，那么某个实例 commit 成功后，另一个实例的存在性缓存里仍可能残留 `false`，
 * 占位图就永远刷不出来。因此 [MediaModule][com.me.jiakao.core.media.MediaModule]、Coil Fetcher
 * 与 Compose 三层共用同一个实例（首个创建者胜出）。
 *
 * 测试可通过 `LocalMediaStore`（见 `QuizMedia.kt`）注入临时目录的实现，无需触碰这里。
 */
internal object MediaStores {

    private val lock = Any()

    @Volatile
    private var instance: MediaStoreImpl? = null

    fun of(context: Context): MediaStoreImpl {
        val app = context.applicationContext
        instance?.let { return it }
        synchronized(lock) {
            instance?.let { return it }
            return MediaStoreImpl(MediaPaths.root(app.filesDir)).also { instance = it }
        }
    }
}
