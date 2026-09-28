package com.me.jiakao.core.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 当前 App 的 versionCode，用于 `min_app_version_code` 校验。 */
fun interface AppVersionProvider {
    fun versionCode(): Int
}

/** 从 PackageManager 读取 versionCode（minSdk 28，`longVersionCode` 可直接用）。 */
@Singleton
class PackageManagerAppVersionProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppVersionProvider {

    /**
     * 读取失败时返回 0（fail-closed）：宁可提示「请先升级 App」，
     * 也不要在版本不明时导入可能不兼容的题库。
     */
    override fun versionCode(): Int = try {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
    } catch (e: Exception) {
        0
    }
}
