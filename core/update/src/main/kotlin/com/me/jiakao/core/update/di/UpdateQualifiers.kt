package com.me.jiakao.core.update.di

import javax.inject.Qualifier

/** 更新模块自身的 IO 调度器（与 01 的调度器注解同名不同包，互不冲突）。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** 与 App 同生命周期的协程作用域。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * `:core:update` 自带的 OkHttpClient。
 *
 * 特意加限定符：03 也要 OkHttpClient（Coil 网络层），若两边都提供无限定符的同类型绑定，
 * 会在拼装时撞 Hilt `DuplicateBindings`。见 out/DONE.md「拼装注意事项」。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UpdateHttp

/** `cacheDir/dl/`：题库包与媒体的下载临时目录（合同 §1）。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadDir
