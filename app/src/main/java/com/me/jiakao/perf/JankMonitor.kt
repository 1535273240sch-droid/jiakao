package com.me.jiakao.perf

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState

/**
 * JankStats 调试日志上报(TASK 交付物 5)。
 * 仅 debug 构建启用:每帧耗时 > 阈值时附带到 logcat tag=JankStats。
 */
object JankMonitor {

    private const val TAG = "JankStats"
    private var jankStats: JankStats? = null

    fun start(activity: ComponentActivity) {
        if (jankStats != null) return
        val holder = PerformanceMetricsState.getHolderForHierarchy(activity.window.decorView)
        jankStats = JankStats.createAndTrack(activity.window) { frame ->
            if (frame.isJank) {
                Log.d(TAG, "jank frame states=${frame.states.size}")
            }
        }.also { stats ->
            stats.isTrackingEnabled = true
        }
        holder.state?.putState("screen", "main")
    }

    fun stop() {
        jankStats?.isTrackingEnabled = false
        jankStats = null
    }
}
