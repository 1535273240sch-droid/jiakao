package com.me.jiakao.core.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.me.jiakao.core.model.BankUpdater
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** debug 变体专用的 Hilt 入口点。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugUpdateEntryPoint {
    fun bankUpdater(): BankUpdater
}

/**
 * 调试广播（仅 debug 变体，见 COMMANDS.md）：
 * ```
 * adb shell am broadcast -a com.me.jiakao.DEBUG_CHECK_UPDATE
 * adb shell am broadcast -a com.me.jiakao.DEBUG_START_UPDATE
 * adb shell am broadcast -a com.me.jiakao.DEBUG_IMPORT_BUNDLE --es path /sdcard/Download/bundle-v2.zip
 * adb shell am broadcast -a com.me.jiakao.DEBUG_SCHEDULE_AUTO --ez enabled true
 * ```
 * release 构建不含本类与对应的 `<receiver>` 声明。
 */
class DebugUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val appContext = context.applicationContext
        val updater = EntryPointAccessors.fromApplication(appContext, DebugUpdateEntryPoint::class.java).bankUpdater()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                when (action) {
                    ACTION_CHECK -> updater.check()
                    ACTION_START_UPDATE -> updater.startUpdate()
                    ACTION_IMPORT_BUNDLE -> {
                        val path = intent.getStringExtra(EXTRA_PATH)
                        if (path.isNullOrBlank()) {
                            updater.importLocalPack(java.io.ByteArrayInputStream(ByteArray(0)))
                        } else {
                            updater.importLocalPack(File(path).inputStream())
                        }
                    }

                    ACTION_SCHEDULE_AUTO -> updater.scheduleAuto(intent.getBooleanExtra(EXTRA_ENABLED, true))
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CHECK = "com.me.jiakao.DEBUG_CHECK_UPDATE"
        const val ACTION_START_UPDATE = "com.me.jiakao.DEBUG_START_UPDATE"
        const val ACTION_IMPORT_BUNDLE = "com.me.jiakao.DEBUG_IMPORT_BUNDLE"
        const val ACTION_SCHEDULE_AUTO = "com.me.jiakao.DEBUG_SCHEDULE_AUTO"

        /** `--es path <zip 路径>`。 */
        const val EXTRA_PATH = "path"

        /** `--ez enabled true|false`。 */
        const val EXTRA_ENABLED = "enabled"
    }
}
