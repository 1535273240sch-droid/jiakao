package com.me.jiakao.data

import android.content.Context
import android.util.Log
import com.me.jiakao.core.model.QuestionStore
import com.me.jiakao.core.update.LocalPackImporter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 首次启动开箱即用题库预装器：
 * 当本地题库版本为 0 且存在预置 assets/starter_pack.zip 时，在后台自动解压并导入。
 * 完成后 QuestionStore 事务提交推进 bankVersion，所有 Flow 监听器自动感知刷新。
 */
@Singleton
class BankAutoSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val questionStore: QuestionStore,
    private val localPackImporter: LocalPackImporter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun seedAsync() {
        scope.launch {
            try {
                if (questionStore.bankVersion() == 0) {
                    val assetList = context.assets.list("") ?: emptyArray()
                    if ("starter_pack.zip" in assetList) {
                        Log.i("BankAutoSeeder", "检测到初始题库 starter_pack.zip，开始自动预装...")
                        context.assets.open("starter_pack.zip").use { input ->
                            val result = localPackImporter.import(input)
                            Log.i("BankAutoSeeder", "初始题库预装完成: 版本 ${result.version}, 题目数 ${result.recordsRead}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("BankAutoSeeder", "预置题库导入跳过: ${e.message}")
            }
        }
    }
}
