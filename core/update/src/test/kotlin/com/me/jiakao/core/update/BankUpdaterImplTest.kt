package com.me.jiakao.core.update

import com.me.jiakao.core.model.MediaRef
import com.me.jiakao.core.model.PackRecord
import com.me.jiakao.core.model.Question
import com.me.jiakao.core.model.UpdateState
import com.me.jiakao.core.update.net.OkHttpDownloader
import com.me.jiakao.core.update.pack.PackCodec
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 更新状态机的端到端测试（MockWebServer 当静态服务端 + Fake QuestionStore/MediaStore）。
 *
 * 覆盖验收点：三种 Plan 的落地、增量走增量、断点续传、校验失败、
 * 事务回滚（导入中途失败版本不变）、媒体失败不影响题目、离线包正常/损坏、min_app_version_code。
 */
class BankUpdaterImplTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: FixtureServer
    private lateinit var store: FakeQuestionStore
    private lateinit var mediaStore: FakeMediaStore
    private lateinit var settings: FakeUpdateSettings
    private lateinit var scheduler: FakeWorkScheduler
    private lateinit var downloader: OkHttpDownloader
    private lateinit var scope: CoroutineScope
    private lateinit var updater: BankUpdaterImpl

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = FixtureServer()
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    // ───────────────────────── 只检查不下载 ─────────────────────────

    @Test
    fun `没配源地址时检查失败`() = runBlocking {
        build(source = "")
        updater.check()
        val state = updater.state.value
        assertTrue(state.toString(), state is UpdateState.Failed)
        assertFalse((state as UpdateState.Failed).retryable)
        assertEquals(0, server.requestCount("manifest.json"))
    }

    @Test
    fun `本地无数据时提示全量更新`() = runBlocking {
        build(localVersion = 0)
        updater.check()

        val state = updater.state.value
        assertTrue(state.toString(), state is UpdateState.Available)
        state as UpdateState.Available
        assertEquals(0, state.fromVersion)
        assertEquals(2, state.toVersion)
        assertEquals(Fixtures.file("full/bank-v2.jsonl.gz").length(), state.downloadBytes)
        assertEquals("check 不应下载题库包", 0, server.requestCount("full/bank-v2.jsonl.gz"))
    }

    @Test
    fun `落后一个版本时提示增量更新`() = runBlocking {
        build(localVersion = 1)
        updater.check()

        val state = updater.state.value as UpdateState.Available
        assertEquals(1, state.fromVersion)
        assertEquals(2, state.toVersion)
        assertEquals(Fixtures.file("delta/v1-v2.jsonl.gz").length(), state.downloadBytes)
    }

    @Test
    fun `已是最新时提示当前版本`() = runBlocking {
        build(localVersion = 2)
        updater.check()
        assertEquals(UpdateState.UpToDate(2), updater.state.value)
    }

    @Test
    fun `min_app_version_code 高于本机时提示升级 App`() = runBlocking {
        build(localVersion = 1, appVersion = 1)
        server.serve("manifest.json") { MockResponse().setResponseCode(200).setBody(Fixtures.text("manifest-minapp.json")) }

        updater.check()

        val state = updater.state.value
        assertTrue(state.toString(), state is UpdateState.Failed)
        state as UpdateState.Failed
        assertEquals("请先升级 App", state.message)
        assertFalse(state.retryable)
    }

    @Test
    fun `manifest 用 ETag 缓存 命中 304 直接判已最新`() = runBlocking {
        server.etag = "\"bank-v2\""
        build(localVersion = 2)

        updater.check()
        assertEquals(UpdateState.UpToDate(2), updater.state.value)
        assertEquals("\"bank-v2\"", settings.currentEtag())

        updater.check()
        assertEquals(UpdateState.UpToDate(2), updater.state.value)
        assertEquals(2, server.requestCount("manifest.json"))
        val secondRequest = server.ifNoneMatchHeaders().last()
        assertEquals("manifest.json", secondRequest.first)
        assertEquals("\"bank-v2\"", secondRequest.second)
    }

    // ───────────────────────── 完整更新流程 ─────────────────────────

    @Test
    fun `全量更新 下载导入媒体与状态机`() = runBlocking {
        build(localVersion = 0)
        val states = recordStates()
        updater.check()
        val available = updater.state.value as UpdateState.Available
        assertEquals(0, available.fromVersion)

        updater.startUpdate()
        assertEquals("startUpdate 必须入队 WorkManager 任务", 1, scheduler.updateRequests.get())
        assertTrue(updater.state.value is UpdateState.DownloadingPack)

        val outcome = updater.runPendingUpdate()

        assertEquals(UpdateOutcome.Success, outcome)
        assertEquals(UpdateState.UpToDate(2), updater.state.value)

        assertEquals(2, store.currentVersion())
        assertEquals(5, store.count())
        assertEquals(2, store.chapters().size)
        assertEquals(3, mediaStore.committedShas().size)
        assertEquals("全量 + 媒体齐了才回收", store.allMediaRefs().map { it.sha256 }.toSet(), mediaStore.retained)

        assertEquals(1, server.requestCount("full/bank-v2.jsonl.gz"))
        assertEquals("全量更新不需要增量包", 0, server.requestCount("delta/v1-v2.jsonl.gz"))

        assertStateSequence(
            states,
            UpdateState.Checking::class.java,
            UpdateState.DownloadingPack::class.java,
            UpdateState.Importing::class.java,
            UpdateState.DownloadingMedia::class.java,
            UpdateState.UpToDate::class.java,
        )
    }

    @Test
    fun `增量更新 走 delta 而不是全量`() = runBlocking {
        build(localVersion = 1, seeded = v1Questions())
        updater.check()
        assertEquals(1, (updater.state.value as UpdateState.Available).fromVersion)

        val outcome = updater.runPendingUpdate()

        assertEquals(UpdateOutcome.Success, outcome)
        assertEquals(2, store.currentVersion())
        assertEquals(listOf("s1-000001", "s1-000002", "s1-000003", "s1-000004", "s1-000006"), store.questions().map { it.id })
        assertEquals(2, store.question("s1-000002")!!.rev)
        assertNull("被删除的题目必须消失", store.question("s1-000005"))
        assertEquals(1, server.requestCount("delta/v1-v2.jsonl.gz"))
        assertEquals("走增量就不能下全量包", 0, server.requestCount("full/bank-v2.jsonl.gz"))
        assertNull("增量更新不做媒体回收", mediaStore.retained)
    }

    @Test
    fun `增量链不连续时退回全量`() = runBlocking {
        build(localVersion = 1, seeded = v1Questions())
        server.serve("manifest.json") { MockResponse().setResponseCode(200).setBody(Fixtures.text("manifest-chain-broken.json")) }

        updater.check()
        assertEquals(5, (updater.state.value as UpdateState.Available).toVersion)
        updater.runPendingUpdate()

        assertEquals(1, server.requestCount("full/bank-v2.jsonl.gz"))
        assertEquals(5, store.currentVersion())
    }

    @Test
    fun `无全量也无连续增量时报可重试失败`() = runBlocking {
        build(localVersion = 1, seeded = v1Questions())
        server.serve("manifest.json") { MockResponse().setResponseCode(200).setBody(Fixtures.text("manifest-nofull.json")) }

        updater.check()
        val state = updater.state.value as UpdateState.Failed
        assertTrue(state.retryable)

        assertEquals(UpdateOutcome.Retryable(state.message), updater.runPendingUpdate())
    }

    // ───────────────────────── 数据安全 ─────────────────────────

    @Test
    fun `导入中途失败 版本不变且媒体不落盘`() = runBlocking {
        build(localVersion = 1, seeded = v1Questions())
        updater.check()
        store.failAtRecord = 3   // 第 3 条记录时炸（模拟 Room 写入失败）

        val outcome = updater.runPendingUpdate()

        assertTrue(outcome.toString(), outcome is UpdateOutcome.Retryable)
        val state = updater.state.value as UpdateState.Failed
        assertTrue(state.retryable)
        assertEquals("事务失败必须保持原版本", 1, store.currentVersion())
        assertEquals(5, store.count())
        assertTrue("失败的导入不能推进版本号", store.appliedVersions.isEmpty())
        assertEquals("题目没导入成功就不该有媒体落盘", 0, mediaStore.commitCalls)
    }

    @Test
    fun `题库包内容与清单 sha 不符时报不可重试错误`() = runBlocking {
        build(localVersion = 0)
        // 字节数与清单一致、内容不同 → 长度校验过关、sha256 校验失败
        val tampered = Fixtures.bytes("full/bank-v2.jsonl.gz").copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0xFF).toByte()
        server.serve("full/bank-v2.jsonl.gz") {
            MockResponse().setResponseCode(200).setBody(okio.Buffer().write(tampered))
        }

        updater.check()
        val outcome = updater.runPendingUpdate()

        assertTrue(outcome.toString(), outcome is UpdateOutcome.Fatal)
        val state = updater.state.value as UpdateState.Failed
        assertFalse(state.retryable)
        assertTrue(state.message!!, state.message!!.contains("sha256"))
        assertEquals(0, store.currentVersion())
        assertEquals(1, server.requestCount("full/bank-v2.jsonl.gz"))
    }

    @Test
    fun `媒体失败不影响题目导入`() = runBlocking {
        build(localVersion = 0)
        val broken = firstV2MediaRef()
        val brokenPath = "media/${broken.sha256.take(2)}/${broken.sha256}.webp"
        repeat(5) { server.enqueue(brokenPath) { MockResponse().setResponseCode(500) } }

        updater.check()
        val outcome = updater.runPendingUpdate()

        assertEquals(UpdateOutcome.Success, outcome)
        assertEquals(2, store.currentVersion())
        assertEquals(5, store.count())
        assertEquals("坏掉的那张没落盘，其余正常", 2, mediaStore.committedShas().size)
        assertNull("媒体不全就不做回收", mediaStore.retained)
        assertEquals(UpdateState.UpToDate(2), updater.state.value)
    }

    @Test
    fun `3000 题全量包流式导入且只调用一次 applyPack`() = runBlocking {
        build(localVersion = 0)
        // JSONL = 每行一条记录，必须用换行分隔；用 "" 拼接会让 3000 条挤成一行而解析失败。
        val pack = gzipBytes((1..3000).joinToString("\n") { questionLine(it) })
        val sha = com.me.jiakao.core.update.util.Sha256.of(pack)
        val manifest = """{"schema":1,"bank_version":9,"min_app_version_code":1,"chapters":[],""" +
            """"full":{"url":"full/bank-v9.jsonl.gz","sha256":"$sha","bytes":${pack.size},"count":3000},""" +
            """"deltas":[],"media_base":"media/"}"""
        server.serve("manifest.json") { MockResponse().setResponseCode(200).setBody(manifest) }
        server.serve("full/bank-v9.jsonl.gz") {
            MockResponse().setResponseCode(200).setBody(okio.Buffer().write(pack))
        }

        updater.check()
        assertEquals(UpdateOutcome.Success, updater.runPendingUpdate())

        assertEquals(9, store.currentVersion())
        assertEquals(3000, store.count())
        assertEquals("整包必须单事务一次性导入，不能按行重复调用 applyPack", 1, store.applyPackCalls)
    }

    @Test
    fun `断网重试可续传不重复下载已校验通过的部分`() = runBlocking {
        build(localVersion = 0)
        val fullBytes = Fixtures.bytes("full/bank-v2.jsonl.gz")
        val fullSha = com.me.jiakao.core.update.util.Sha256.of(fullBytes)
        // 模拟上次被杀的进程：已经下了大半个包
        val half = fullBytes.size / 2
        downloader.partFile(fullSha).writeBytes(fullBytes.copyOfRange(0, half))

        updater.check()
        assertEquals(UpdateOutcome.Success, updater.runPendingUpdate())

        assertEquals(2, store.currentVersion())
        assertEquals("应带 Range 续传", "bytes=$half-", server.rangeHeaders().first { it.first == "full/bank-v2.jsonl.gz" }.second)
    }

    // ───────────────────────── 离线包 ─────────────────────────

    @Test
    fun `离线包导入成功`() = runBlocking {
        build(localVersion = 0)
        val states = recordStates()

        updater.importLocalPack(Fixtures.file("bundle/bundle-v2.zip").inputStream())

        assertEquals(UpdateState.UpToDate(2), updater.state.value)
        assertEquals(2, store.currentVersion())
        assertEquals(5, store.count())
        assertEquals(3, mediaStore.committedShas().size)
        assertTrue(states.any { it is UpdateState.DownloadingMedia })
    }

    @Test
    fun `损坏离线包 明确失败且数据不变`() = runBlocking {
        build(localVersion = 1, seeded = v1Questions())

        updater.importLocalPack(Fixtures.file("bundle/bundle-v2-corrupt.zip").inputStream())

        val state = updater.state.value
        assertTrue(state.toString(), state is UpdateState.Failed)
        state as UpdateState.Failed
        assertFalse(state.retryable)
        assertEquals(1, store.currentVersion())
        assertEquals(5, store.count())
        assertEquals(0, mediaStore.commitCalls)
    }

    // ───────────────────────── 源地址与调度 ─────────────────────────

    @Test
    fun `setSource 规范化并以斜杠结尾`() = runBlocking {
        build(source = "")
        updater.setSource("  ${server.baseUrl.removeSuffix("/")}  ")
        assertEquals(server.baseUrl, updater.sourceUrl.value)
        assertEquals(UpdateState.Idle, updater.state.value)
    }

    @Test
    fun `setSource 拒绝非 http 地址`() = runBlocking {
        build()
        val error = try {
            updater.setSource("ftp://example.com/bank")
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertTrue("必须拒绝非 http(s) 地址", error != null)
    }

    @Test
    fun `scheduleAuto 转发给调度器`() = runBlocking {
        build()
        updater.scheduleAuto(true)
        updater.scheduleAuto(false)
        assertEquals(listOf(true, false), scheduler.autoStates.toList())
    }

    @Test
    fun `没有先 check 也能直接更新 可重入`() = runBlocking {
        build(localVersion = 0)
        updater.startUpdate()
        updater.startUpdate()
        assertEquals(2, scheduler.updateRequests.get())

        assertEquals(UpdateOutcome.Success, updater.runPendingUpdate())
        assertEquals(2, store.currentVersion())
    }

    // ───────────────────────── 装配与工具 ─────────────────────────

    private fun build(
        source: String? = null,
        localVersion: Int = 0,
        appVersion: Int = 1,
        seeded: List<Question> = emptyList(),
    ) {
        store = FakeQuestionStore(localVersion, seeded)
        mediaStore = FakeMediaStore(temp.newFolder("media-${System.nanoTime()}"))
        settings = FakeUpdateSettings(source ?: server.baseUrl)
        scheduler = FakeWorkScheduler()
        downloader = OkHttpDownloader(client, temp.newFolder("dl-${System.nanoTime()}"), sleeper = { })
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        updater = BankUpdaterImpl(
            questionStore = store,
            mediaStore = mediaStore,
            downloader = downloader,
            mediaSync = MediaSync(downloader, mediaStore),
            settings = settings,
            localPackImporter = LocalPackImporter(
                questionStore = store,
                mediaStore = mediaStore,
                spoolRoot = temp.newFolder("spool-${System.nanoTime()}"),
                ioDispatcher = Dispatchers.Unconfined,
                appVersionCode = { appVersion },
            ),
            scheduler = scheduler,
            appVersion = FixedAppVersion(appVersion),
            logger = NoopUpdateLogger,
            scope = scope,
        )
    }

    private fun recordStates(): List<UpdateState> {
        val states = java.util.Collections.synchronizedList(ArrayList<UpdateState>())
        scope.launch { updater.state.collect { states += it } }
        return states
    }

    private fun v1Questions(): List<Question> =
        PackCodec.stream(Fixtures.bytes("full/bank-v1.jsonl.gz").inputStream())
            .filterIsInstance<PackRecord.Upsert>()
            .map { it.question }
            .toList()

    private fun firstV2MediaRef(): MediaRef =
        PackCodec.stream(Fixtures.bytes("full/bank-v2.jsonl.gz").inputStream())
            .filterIsInstance<PackRecord.Upsert>()
            .map { it.question }
            .first { it.media.isNotEmpty() }
            .media
            .first()

    private fun assertStateSequence(states: List<UpdateState>, vararg expected: Class<out UpdateState>) {
        var cursor = 0
        expected.forEach { type ->
            val index = states.drop(cursor).indexOfFirst { type.isInstance(it) }
            assertTrue("状态 ${type.simpleName} 没出现或乱序：$states", index >= 0)
            cursor += index + 1
        }
    }
}
