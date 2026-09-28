package com.me.jiakao.core.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.me.jiakao.core.model.UpdateState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 更新相关通知（前台服务通知 + 自动检查发现更新的提示）。
 *
 * 抽成接口便于单测；实现见 [AndroidUpdateNotifier]。
 */
interface UpdateNotifier {

    /** 前台任务的常驻通知（WorkManager `setForeground` 必需）。 */
    fun foregroundNotification(state: UpdateState): Notification

    /** 自动检查发现新版本时的提示（不自动下载）。 */
    fun notifyUpdateAvailable(fromVersion: Int, toVersion: Int, downloadBytes: Long)

    /** 更新失败的提示。 */
    fun notifyFailed(message: String)
}

/**
 * 系统通知实现。
 *
 * 兼容性：Android 13（API 33）起 `POST_NOTIFICATIONS` 是运行时权限，未授权时
 * [notifyUpdateAvailable] / [notifyFailed] 静默跳过（不抛异常、不崩）；
 * 前台服务通知必须存在，未授权时系统不展示该通知但任务照常执行。
 */
@Singleton
class AndroidUpdateNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : UpdateNotifier {

    override fun foregroundNotification(state: UpdateState): Notification {
        ensureChannel()
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.jiakao_update_foreground_title))
            .setContentText(foregroundText(state))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        contentIntent()?.let { builder.setContentIntent(it) }
        progressOf(state)?.let { (percent, indeterminate) ->
            builder.setProgress(100, percent, indeterminate)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    override fun notifyUpdateAvailable(fromVersion: Int, toVersion: Int, downloadBytes: Long) {
        if (!canPostNotifications()) return
        ensureChannel()
        val text = context.getString(R.string.jiakao_update_available_text, formatBytes(downloadBytes))
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.jiakao_update_available_title, toVersion))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setAutoCancel(true)
        contentIntent()?.let { builder.setContentIntent(it) }
        post(NOTIFICATION_ID_AVAILABLE, builder.build())
    }

    override fun notifyFailed(message: String) {
        if (!canPostNotifications()) return
        ensureChannel()
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.jiakao_update_failed_title))
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
        contentIntent()?.let { builder.setContentIntent(it) }
        post(NOTIFICATION_ID_FAILED, builder.build())
    }

    private fun foregroundText(state: UpdateState): String = when (state) {
        is UpdateState.Checking -> context.getString(R.string.jiakao_update_checking)
        is UpdateState.Available -> context.getString(R.string.jiakao_update_available_short)
        is UpdateState.DownloadingPack -> context.getString(
            R.string.jiakao_update_downloading_pack,
            formatBytes(state.doneBytes),
            formatBytes(state.totalBytes),
        )
        is UpdateState.Importing -> context.getString(R.string.jiakao_update_importing)
        is UpdateState.DownloadingMedia -> context.getString(
            R.string.jiakao_update_downloading_media,
            state.done,
            state.total,
        )
        is UpdateState.UpToDate -> context.getString(R.string.jiakao_update_done)
        is UpdateState.Failed -> context.getString(R.string.jiakao_update_failed_title)
        UpdateState.Idle -> context.getString(R.string.jiakao_update_checking)
    }

    private fun progressOf(state: UpdateState): Pair<Int, Boolean>? = when (state) {
        is UpdateState.DownloadingPack ->
            if (state.totalBytes > 0L) {
                ((state.doneBytes * 100) / state.totalBytes).toInt().coerceIn(0, 100) to false
            } else {
                0 to true
            }
        is UpdateState.DownloadingMedia ->
            if (state.total > 0) ((state.done * 100) / state.total) to false else 0 to true
        UpdateState.Importing -> 100 to true
        else -> null
    }

    private fun contentIntent(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        val manager = notificationManager() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.jiakao_update_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.jiakao_update_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun post(id: Int, notification: Notification) {
        notificationManager()?.notify(id, notification)
    }

    private fun notificationManager(): NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0L -> "0 B"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private companion object {
        const val CHANNEL_ID = "jiakao_update"
        const val NOTIFICATION_ID_AVAILABLE = 4102
        const val NOTIFICATION_ID_FAILED = 4103
    }
}

/** 前台任务通知 id（WorkManager `ForegroundInfo` 使用）。 */
const val UPDATE_FOREGROUND_NOTIFICATION_ID: Int = 4101
