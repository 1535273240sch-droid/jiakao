package com.me.jiakao.core.update

import com.me.jiakao.core.model.MediaStore
import com.me.jiakao.core.model.QuestionStore
import com.me.jiakao.core.model.UpdateState
import com.me.jiakao.core.update.di.ApplicationScope
import com.me.jiakao.core.update.net.DownloadException
import com.me.jiakao.core.update.net.Downloader
import com.me.jiakao.core.update.net.FetchResult
import com.me.jiakao.core.update.pack.FileRef
import com.me.jiakao.core.update.pack.Manifest
import com.me.jiakao.core.update.pack.PackCodec
import com.me.jiakao.core.update.pack.PackFormatException
import com.me.jiakao.core.update.plan.Plan
import com.me.jiakao.core.update.plan.UpdatePlanner
import com.me.jiakao.core.update.util.Urls
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl

/**
 * [com.me.jiakao.core.model.BankUpdater] 的实现（TASK 交付物 4）。
 *
 * 状态机严格映射合同 §5 的 [UpdateState]：
 * ```
 * check()             : Checking → Available / UpToDate / Failed
 * startUpdate()       : (Worker) DownloadingPack → Importing → DownloadingMedia → UpToDate / Failed
 * importLocalPack()   : Importing → DownloadingMedia → UpToDate / Failed
 * ```
 *
 * 数据安全（合同 §3.3、§3.4、PROMPT 硬性约束）：
 * - 题目导入走 `QuestionStore.applyPack` **单事务**，成功后才推进版本号；失败版本不变；
 * - 所有下载先写 `.part`、校验 sha256/bytes 通过才使用；校验失败或取消都不会污染已提交数据；
 * - 媒体失败不回滚题目，仅计数，下次 `check()`/自动更新时重试；
 * - 全程不触碰 `user.db`（本模块只通过 [QuestionStore] / [MediaStore] 访问题库与媒体）。
 */
@Singleton
class BankUpdaterImpl @Inject constructor(
    private val questionStore: QuestionStore,
    private val mediaStore: MediaStore,
    private val downloader: Downloader,
    private val mediaSync: MediaSync,
    private val settings: UpdateSettingsSource,
    private val localPackImporter: LocalPackImporter,
    private val scheduler: UpdateWorkScheduler,
    private val appVersion: AppVersionProvider,
    private val logger: UpdateLogger,
    @ApplicationScope private val scope: CoroutineScope,
) : com.me.jiakao.core.model.BankUpdater, UpdateEngine {

    private val stateFlow = MutableStateFlow<UpdateState>(UpdateState.Idle)

    override val state: StateFlow<UpdateState> = stateFlow.asStateFlow()

    override val sourceUrl: StateFlow<String> =
        settings.sourceUrl.stateIn(scope, SharingStarted.Eagerly, "")

    /** 串行化 check/更新，避免两次更新并发写题库。 */
    private val runLock = Mutex()

    /** `check()` 得到的清单，`startUpdate()` 直接复用，省一次请求。 */
    @Volatile
    private var pendingManifest: Manifest? = null

    /** 待更新所需字节数，用于 `startUpdate()` 后立刻给出可见进度。 */
    @Volatile
    private var pendingBytes: Long = 0L

    // ───────────────────────── 对外 API ─────────────────────────

    override suspend fun setSource(baseUrl: String) {
        // 规范化 + 校验 http(s)（非法地址抛 IllegalArgumentException，由调用方提示）
        settings.setSource(baseUrl)
        pendingManifest = null
        pendingBytes = 0L
        stateFlow.value = UpdateState.Idle
    }

    /**
     * 只检查，不下载：拉 `manifest.json`（带 `If-None-Match`），比较版本后发出
     * [UpdateState.Available] / [UpdateState.UpToDate] / [UpdateState.Failed]。
     */
    override suspend fun check() = runLock.withLock {
        val base = currentBase()
        if (base == null) {
            stateFlow.value = UpdateState.Failed(MESSAGE_NO_SOURCE, retryable = false)
            return@withLock
        }
        stateFlow.value = UpdateState.Checking
        val localVersion = localVersion()

        when (val load = loadManifest(base)) {
            is ManifestLoad.Loaded -> {
                val manifest = load.manifest
                if (manifest.minAppVersionCode > appVersion.versionCode()) {
                    pendingManifest = null
                    pendingBytes = 0L
                    stateFlow.value = UpdateState.Failed(MESSAGE_APP_TOO_OLD, retryable = false)
                    return@withLock
                }
                when (val plan = UpdatePlanner.plan(localVersion, manifest)) {
                    is Plan.UpToDate -> {
                        pendingManifest = null
                        pendingBytes = 0L
                        stateFlow.value = UpdateState.UpToDate(plan.version)
                    }

                    is Plan.Unavailable -> {
                        pendingManifest = null
                        pendingBytes = 0L
                        stateFlow.value = UpdateState.Failed(plan.reason, retryable = true)
                    }

                    is Plan.Full, is Plan.Deltas -> {
                        pendingManifest = manifest
                        pendingBytes = plan.downloadBytes
                        stateFlow.value = UpdateState.Available(
                            fromVersion = localVersion,
                            toVersion = manifest.bankVersion,
                            downloadBytes = plan.downloadBytes,
                        )
                    }
                }
            }

            ManifestLoad.NotModified -> {
                pendingManifest = null
                pendingBytes = 0L
                stateFlow.value = UpdateState.UpToDate(localVersion)
            }

            is ManifestLoad.Failed -> {
                pendingManifest = null
                stateFlow.value = UpdateState.Failed(load.message, load.retryable)
            }
        }
    }

    /**
     * 入队加急前台更新任务（同名唯一任务 KEEP，可重入）。
     * 真正的下载/导入/媒体补齐在 [UpdateWorker] 里执行。
     */
    override fun startUpdate() {
        // WorkManager 可能因加急配额延后调度，先给出可见状态
        stateFlow.value = UpdateState.DownloadingPack(doneBytes = 0L, totalBytes = pendingBytes)
        scheduler.enqueueUpdate()
    }

    /**
     * 导入离线整包（`bundle-vN.zip`）。
     *
     * 失败**不抛异常**，统一通过 [state] 的 [UpdateState.Failed] 反馈（02 观察状态即可）；
     * 损坏包不改动任何现有数据（见 [LocalPackImporter]）。
     */
    override suspend fun importLocalPack(input: InputStream) = runLock.withLock {
        stateFlow.value = UpdateState.Importing
        try {
            val result = input.use { stream ->
                localPackImporter.import(stream) { state -> stateFlow.value = state }
            }
            pendingManifest = null
            pendingBytes = 0L
            stateFlow.value = UpdateState.UpToDate(result.version)
            logger.debug("离线包导入完成：version=${result.version} records=${result.recordsRead} media=${result.mediaDone}/${result.mediaTotal}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: LocalPackAppVersionException) {
            stateFlow.value = UpdateState.Failed(MESSAGE_APP_TOO_OLD, retryable = false)
        } catch (e: LocalPackException) {
            logger.warn("离线包导入失败", e)
            stateFlow.value = UpdateState.Failed(e.message ?: "离线包导入失败", retryable = false)
        } catch (e: Exception) {
            logger.warn("离线包导入异常", e)
            stateFlow.value = UpdateState.Failed("离线包导入失败：${e.message}", retryable = false)
        }
    }

    override fun scheduleAuto(enabled: Boolean) {
        scheduler.scheduleAuto(enabled)
    }

    // ───────────────────────── 执行一次更新 ─────────────────────────

    override suspend fun runPendingUpdate(): UpdateOutcome = runLock.withLock {
        val base = currentBase() ?: return@withLock fail(MESSAGE_NO_SOURCE, retryable = false)
        stateFlow.value = UpdateState.Checking
        val localVersion = localVersion()

        val manifest = pendingManifest ?: when (val load = loadManifest(base)) {
            is ManifestLoad.Loaded -> load.manifest
            ManifestLoad.NotModified -> {
                stateFlow.value = UpdateState.UpToDate(localVersion)
                return@withLock UpdateOutcome.Success
            }

            is ManifestLoad.Failed -> return@withLock fail(load.message, load.retryable)
        }

        if (manifest.minAppVersionCode > appVersion.versionCode()) {
            pendingManifest = null
            pendingBytes = 0L
            return@withLock fail(MESSAGE_APP_TOO_OLD, retryable = false)
        }

        when (val plan = UpdatePlanner.plan(localVersion, manifest)) {
            is Plan.UpToDate -> {
                pendingManifest = null
                pendingBytes = 0L
                stateFlow.value = UpdateState.UpToDate(plan.version)
                UpdateOutcome.Success
            }

            is Plan.Unavailable -> fail(plan.reason, retryable = true)

            is Plan.Full -> runPack(
                base = base,
                manifest = manifest,
                targetVersion = plan.version,
                files = listOf(plan.file),
                replaceAll = true,
            )

            is Plan.Deltas -> runPack(
                base = base,
                manifest = manifest,
                targetVersion = plan.to,
                files = plan.steps.map { it.asFileRef() },
                replaceAll = false,
            )
        }
    }

    /**
     * 下载 → 校验 → 单事务导入 → 补齐媒体。
     *
     * @param replaceAll 全量为 true（先清空题表），增量为 false；章节只在全量时整体替换
     */
    private suspend fun runPack(
        base: HttpUrl,
        manifest: Manifest,
        targetVersion: Int,
        files: List<FileRef>,
        replaceAll: Boolean,
    ): UpdateOutcome {
        val totalBytes = files.sumOf { it.bytes }
        val resolved = files.map { ref -> Urls.resolve(base, ref.url)?.let { ref to it } }
        if (resolved.any { it == null }) {
            return fail("题库包地址无法解析（${files.map { it.url }}）", retryable = false)
        }
        val downloads = resolved.filterNotNull()

        // ── 1. 下载并逐个校验（.part + Range 断点续传；已校验通过的文件重试时直接复用）
        val tempFiles = ArrayList<File>(downloads.size)
        var keepTempForResume = false
        var downloadedBytes = 0L
        stateFlow.value = UpdateState.DownloadingPack(doneBytes = 0L, totalBytes = totalBytes)
        try {
            for ((ref, url) in downloads) {
                val alreadyDone = downloadedBytes
                val file = downloader.downloadToTemp(
                    url = url,
                    key = ref.sha256,
                    expectedSha256 = ref.sha256,
                    expectedBytes = ref.bytes,
                ) { progress, _ ->
                    stateFlow.value = UpdateState.DownloadingPack(
                        doneBytes = (alreadyDone + progress).coerceAtMost(totalBytes),
                        totalBytes = totalBytes,
                    )
                }
                tempFiles += file
                downloadedBytes = alreadyDone + ref.bytes
            }

            // ── 2. 单事务导入：失败由 QuestionStore 回滚，版本号不动
            stateFlow.value = UpdateState.Importing
            try {
                questionStore.applyPack(
                    newVersion = targetVersion,
                    chapters = if (replaceAll) manifest.chaptersOrNull() else null,
                    records = PackCodec.stream(tempFiles),
                    replaceAll = replaceAll,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: PackFormatException) {
                logger.warn("题库包内容损坏", e)
                return fail("题库包内容损坏：${e.message}", retryable = false)
            } catch (e: Exception) {
                logger.warn("题库导入失败（已回滚）", e)
                return fail("题库导入失败（已回滚）：${e.message}", retryable = true)
            }

            // ── 3. 媒体异步补齐：失败不回滚题目（合同 §3.4）
            val mediaOutcome = syncMedia(base, manifest, replaceAll)
            pendingManifest = null
            pendingBytes = 0L
            stateFlow.value = UpdateState.UpToDate(targetVersion)
            logger.debug("题库更新完成：version=$targetVersion media=${mediaOutcome.done}/${mediaOutcome.total}")
            return UpdateOutcome.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadException) {
            // 可重试的下载失败保留已完成文件与 .part，下次直接续传
            keepTempForResume = e.retryable
            logger.warn("题库包下载失败：${e.message}")
            return fail("题库包下载失败：${e.message}", retryable = e.retryable)
        } catch (e: IOException) {
            keepTempForResume = true
            return fail("题库包下载失败：${e.message}", retryable = true)
        } finally {
            if (!keepTempForResume) tempFiles.forEach { it.delete() }
        }
    }

    private suspend fun syncMedia(base: HttpUrl, manifest: Manifest, replaceAll: Boolean): MediaSyncResult {
        val refs = questionStore.allMediaRefs()
        if (refs.isEmpty()) return MediaSyncResult(0, 0, 0)
        val mediaBase = Urls.resolve(base, manifest.mediaBase) ?: base
        val result = mediaSync.sync(mediaBase, refs) { done, total ->
            stateFlow.value = UpdateState.DownloadingMedia(done, total)
        }
        if (replaceAll && result.allReady) {
            mediaSync.retain(refs)
        }
        return result
    }

    // ───────────────────────── 内部工具 ─────────────────────────

    private sealed interface ManifestLoad {
        data class Loaded(val manifest: Manifest) : ManifestLoad
        data object NotModified : ManifestLoad
        data class Failed(val message: String, val retryable: Boolean) : ManifestLoad
    }

    private suspend fun loadManifest(base: HttpUrl): ManifestLoad {
        val url = base.resolve(PackCodec.MANIFEST_NAME)
            ?: return ManifestLoad.Failed("题库源地址无法解析 ${PackCodec.MANIFEST_NAME}", retryable = false)
        return try {
            when (val fetched = downloader.fetchText(url, etag = settings.etag.first())) {
                FetchResult.NotModified -> ManifestLoad.NotModified
                is FetchResult.Fetched -> {
                    val manifest = PackCodec.parseManifest(fetched.body)
                    if (!fetched.etag.isNullOrBlank()) settings.setEtag(fetched.etag)
                    ManifestLoad.Loaded(manifest)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadException) {
            ManifestLoad.Failed("检查更新失败：${e.message}", retryable = e.retryable)
        } catch (e: PackFormatException) {
            ManifestLoad.Failed(e.message ?: "题库清单非法", retryable = false)
        } catch (e: IOException) {
            ManifestLoad.Failed("检查更新失败：${e.message}", retryable = true)
        }
    }

    private suspend fun localVersion(): Int = questionStore.bankVersion()

    /** 直接从设置读取（而不是 `sourceUrl.value`），避免依赖 StateFlow 的收集时机。 */
    private suspend fun currentBase(): HttpUrl? = Urls.sourceOrNull(settings.sourceUrl.first())

    private fun fail(message: String, retryable: Boolean): UpdateOutcome {
        stateFlow.value = UpdateState.Failed(message, retryable)
        return if (retryable) UpdateOutcome.Retryable(message) else UpdateOutcome.Fatal(message)
    }

    private companion object {
        const val MESSAGE_NO_SOURCE = "未设置题库源地址"
    }
}
