package com.me.jiakao.fake

import com.me.jiakao.core.model.MediaPrefetcher
import com.me.jiakao.core.model.MediaRef
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** 假媒体预取器:记录调用供测试/调试 */
@Singleton
class FakeMediaPrefetcher @Inject constructor() : MediaPrefetcher {

    val prefetchCalls = AtomicInteger(0)
    val lastRefs = MutableStateFlow<List<MediaRef>>(emptyList())

    override fun prefetch(refs: List<MediaRef>) {
        if (refs.isEmpty()) return
        prefetchCalls.incrementAndGet()
        lastRefs.value = refs
    }

    override fun cancelAll() {
        lastRefs.value = emptyList()
    }
}
