package com.me.jiakao.core.update

import android.util.Log
import javax.inject.Inject

/** 极简日志端口；单测用 [NoopUpdateLogger]，设备上用 [AndroidUpdateLogger]。 */
interface UpdateLogger {
    fun debug(message: String)
    fun warn(message: String, error: Throwable? = null)
}

/** 什么都不做，供单测使用。 */
object NoopUpdateLogger : UpdateLogger {
    override fun debug(message: String) = Unit
    override fun warn(message: String, error: Throwable?) = Unit
}

/** 走 `android.util.Log`。 */
class AndroidUpdateLogger @Inject constructor() : UpdateLogger {

    override fun debug(message: String) {
        Log.d(TAG, message)
    }

    override fun warn(message: String, error: Throwable?) {
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
    }

    private companion object {
        const val TAG = "JiakaoUpdate"
    }
}
