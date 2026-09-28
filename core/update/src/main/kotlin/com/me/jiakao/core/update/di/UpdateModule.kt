package com.me.jiakao.core.update.di

import android.content.Context
import com.me.jiakao.core.model.MediaStore
import com.me.jiakao.core.model.QuestionStore
import com.me.jiakao.core.update.AndroidUpdateLogger
import com.me.jiakao.core.update.AndroidUpdateNotifier
import com.me.jiakao.core.update.AppVersionProvider
import com.me.jiakao.core.update.BankUpdaterImpl
import com.me.jiakao.core.update.ConnectivityNetworkStateProvider
import com.me.jiakao.core.update.LocalPackImporter
import com.me.jiakao.core.update.MediaSync
import com.me.jiakao.core.update.NetworkStateProvider
import com.me.jiakao.core.update.PackageManagerAppVersionProvider
import com.me.jiakao.core.update.UpdateEngine
import com.me.jiakao.core.update.UpdateLogger
import com.me.jiakao.core.update.UpdateNotifier
import com.me.jiakao.core.update.UpdateSettings
import com.me.jiakao.core.update.UpdateSettingsSource
import com.me.jiakao.core.update.UpdateWorkScheduler
import com.me.jiakao.core.update.WorkManagerUpdateScheduler
import com.me.jiakao.core.update.net.Downloader
import com.me.jiakao.core.update.net.OkHttpDownloader
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * `:core:update` 的 Hilt 装配（合同 §6：04 提供 `UpdateModule`，自带 `OkHttpClient`）。
 *
 * 依赖方向：本模块只依赖合同 §5 的接口 —— `QuestionStore`（01）与 `MediaStore`（03），
 * 两者由对方的 Hilt 模块提供，拼装时自动生效。
 *
 * 注意：`OkHttpClient` 用 [UpdateHttp] 限定，避免与 03（Coil 网络层）的同类型绑定撞
 * `DuplicateBindings`。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {

    @Binds
    @Singleton
    abstract fun bindBankUpdater(impl: BankUpdaterImpl): com.me.jiakao.core.model.BankUpdater

    @Binds
    @Singleton
    abstract fun bindUpdateEngine(impl: BankUpdaterImpl): UpdateEngine

    @Binds
    @Singleton
    abstract fun bindUpdateSettings(impl: UpdateSettings): UpdateSettingsSource

    @Binds
    @Singleton
    abstract fun bindUpdateNotifier(impl: AndroidUpdateNotifier): UpdateNotifier

    @Binds
    @Singleton
    abstract fun bindAppVersionProvider(impl: PackageManagerAppVersionProvider): AppVersionProvider

    @Binds
    @Singleton
    abstract fun bindNetworkStateProvider(impl: ConnectivityNetworkStateProvider): NetworkStateProvider

    @Binds
    @Singleton
    abstract fun bindUpdateWorkScheduler(impl: WorkManagerUpdateScheduler): UpdateWorkScheduler

    @Binds
    @Singleton
    abstract fun bindUpdateLogger(impl: AndroidUpdateLogger): UpdateLogger

    companion object {

        /** 连接 15s / 读取 30s（TASK 交付物 8）；不加总超时，长包下载不会被掐断。 */
        @Provides
        @Singleton
        @UpdateHttp
        fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
            val userAgent = buildString {
                append("JiakaoKit/1.0 (Android ")
                append(android.os.Build.VERSION.SDK_INT)
                append("; versionCode ")
                append(PackageManagerAppVersionProvider(context).versionCode())
                append(')')
            }
            return OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", userAgent)
                        .build()
                    chain.proceed(request)
                }
                .build()
        }

        /** 下载临时目录：`cacheDir/dl/`（合同 §1）。 */
        @Provides
        @Singleton
        @DownloadDir
        fun provideDownloadDir(@ApplicationContext context: Context): File =
            File(context.cacheDir, "dl").apply { mkdirs() }

        @Provides
        @Singleton
        fun provideDownloader(
            @UpdateHttp client: OkHttpClient,
            @DownloadDir downloadDir: File,
        ): Downloader = OkHttpDownloader(client, downloadDir)

        @Provides
        @Singleton
        fun provideMediaSync(downloader: Downloader, mediaStore: MediaStore): MediaSync =
            MediaSync(downloader, mediaStore)

        @Provides
        @Singleton
        fun provideLocalPackImporter(
            questionStore: QuestionStore,
            mediaStore: MediaStore,
            @DownloadDir downloadDir: File,
            @IoDispatcher ioDispatcher: CoroutineDispatcher,
            appVersionProvider: AppVersionProvider,
        ): LocalPackImporter = LocalPackImporter(
            questionStore = questionStore,
            mediaStore = mediaStore,
            spoolRoot = downloadDir,
            ioDispatcher = ioDispatcher,
            appVersionCode = appVersionProvider::versionCode,
        )

        @Provides
        @Singleton
        @IoDispatcher
        fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

        @Provides
        @Singleton
        @ApplicationScope
        fun provideApplicationScope(@IoDispatcher ioDispatcher: CoroutineDispatcher): CoroutineScope =
            CoroutineScope(SupervisorJob() + ioDispatcher)

        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L
    }
}
