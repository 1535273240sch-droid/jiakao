package com.me.jiakao.core.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 计费网络判定，用于「自动更新不耗流量下媒体」（TASK 交付物 7）。
 */
fun interface NetworkStateProvider {
    /** 当前网络是否不计费（Wi-Fi / 以太网）。 */
    fun isUnmetered(): Boolean
}

/** 基于 ConnectivityManager 的实现。 */
@Singleton
class ConnectivityNetworkStateProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : NetworkStateProvider {

    override fun isUnmetered(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
