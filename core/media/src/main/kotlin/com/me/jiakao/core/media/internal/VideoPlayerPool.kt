package com.me.jiakao.core.media.internal

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * 全局单播放器池（任务书 §3）：整机最多一个 [ExoPlayer]，离开屏幕立即释放。
 *
 * 用"池"而不是每个视频一个实例，是因为视频页可能被滑动快速掠过：每次创建 ExoPlayer
 * 都要拉起解码器与渲染器，池化 + 及时释放能把峰值内存和解码器数量都压在 1 个。
 *
 * 线程约束：ExoPlayer 的 `prepare/release` 必须在同一 Looper（App 主线程）上执行；
 * 调用方（`QuizMedia` 的 `DisposableEffect`）天然在主线程，因此这里用同步块串行化访问。
 */
internal class VideoPlayerPool {

    private class Entry(val player: ExoPlayer) {
        var owner: Any? = null
        var mediaPath: String? = null
    }

    private val lock = Any()
    private var entry: Entry? = null

    /**
     * 借用播放器并绑定 [file]。
     *
     * @param owner 持有者标识；换持有者会切换媒体项（同一时刻只有一个持有者真正播放）。
     * @param muted 静音（合同要求题目视频静音循环）。
     * @param loop 单曲循环。
     */
    fun acquire(
        context: Context,
        owner: Any,
        file: File,
        muted: Boolean = true,
        loop: Boolean = true,
    ): ExoPlayer = synchronized(lock) {
        val current = entry ?: Entry(ExoPlayer.Builder(context.applicationContext).build()).also { entry = it }
        val player = current.player
        player.volume = if (muted) 0f else 1f
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        val path = file.absolutePath
        if (current.mediaPath != path) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file).toString()))
            player.prepare()
            current.mediaPath = path
        }
        current.owner = owner
        player.playWhenReady = true
        player
    }

    /** 暂停但保留播放器（例如宿主进入后台，仍在屏幕上）。 */
    fun pause(owner: Any) = synchronized(lock) {
        val current = entry ?: return@synchronized
        if (current.owner !== owner) return@synchronized
        current.player.playWhenReady = false
    }

    /** 释放播放器；只有当前持有者能触发，避免 A 页面退出时拆掉 B 页面的播放器。 */
    fun release(owner: Any) = synchronized(lock) {
        val current = entry ?: return@synchronized
        if (current.owner !== owner) return@synchronized
        entry = null
        current.owner = null
        current.mediaPath = null
        current.player.release()
    }

    /** 是否已有活动播放器（调试/测试用）。 */
    val isActive: Boolean get() = synchronized(lock) { entry != null }

    companion object {
        /** 进程内共享的单播放器池。 */
        val global: VideoPlayerPool = VideoPlayerPool()
    }
}
