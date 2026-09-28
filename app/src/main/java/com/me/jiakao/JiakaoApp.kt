package com.me.jiakao

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * 合同 §6::app 只负责 @HiltAndroidApp 与 @AndroidEntryPoint。
 * 额外提供 WorkManager 按需初始化,使 04 的 BankUpdater 可注入 @HiltWorker。
 */
@HiltAndroidApp
class JiakaoApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
