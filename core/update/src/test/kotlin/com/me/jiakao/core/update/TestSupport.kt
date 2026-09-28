package com.me.jiakao.core.update

import com.me.jiakao.core.model.Chapter
import com.me.jiakao.core.model.MediaKind
import com.me.jiakao.core.model.MediaRef
import com.me.jiakao.core.model.MediaStore
import com.me.jiakao.core.model.PackRecord
import com.me.jiakao.core.model.Question
import com.me.jiakao.core.model.QuestionStore
import com.me.jiakao.core.update.net.DownloadException
import com.me.jiakao.core.update.net.Downloader
import com.me.jiakao.core.update.net.FetchResult
import com.me.jiakao.core.update.util.Sha256
import com.me.jiakao.core.update.util.Urls
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

// ───────────────────────── 夹具 ─────────────────────────

/**
 * 夹具目录定位：Gradle 单测的工作目录是模块目录（`core/update`），
 * 但独立脚手架与拼装后的工程布局不同，因此多试几个候选路径。
 */
object Fixtures {

    val dir: File by lazy {
        val candidates = listOf(
            File("src/test/resources/fixtures"),
            File("core/update/src/test/resources/fixtures"),
            File("out/core/update/src/test/resources/fixtures"),
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error("找不到 fixtures 目录（cwd=${File("").absolutePath}）；请先运行 _standalone/tools/gen_fixtures.ps1")
    }

    fun file(relative: String): File = File(dir, relative).also {
        check(it.isFile) { "缺少夹具：$relative（期望 ${it.absolutePath}）" }
    }

    fun bytes(relative: String): ByteArray = file(relative).readBytes()

    fun text(relative: String): String = file(relative).readText(Charsets.UTF_8)
}

/** 把夹具目录当静态服务端（合同 §3 的服务端就是纯静态文件）。 */
class FixtureServer(private val root: File = Fixtures.dir) : AutoCloseable {

    val server = MockWebServer()
    private val overrides = ConcurrentHashMap<String, () -> MockResponse>()
    private val queued = ConcurrentHashMap<String, ArrayDeque<MockResponse>>()
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    private val seenRanges = CopyOnWriteArrayList<Pair<String, String?>>()
    private val seenIfNoneMatch = CopyOnWriteArrayList<Pair<String, String?>>()

    /** 非空时所有响应带该 ETag，且 `If-None-Match` 命中则 304。 */
    @Volatile
    var etag: String? = null

    val baseUrl: String get() = server.url("/").toString()

    fun url(path: String): HttpUrl = server.url("/$path")

    fun start() {
        server.dispatcher = dispatcher()
        server.start()
    }

    fun requestCount(path: String): Int = counts[path]?.get() ?: 0

    fun requestPaths(): List<String> = counts.keys.toList()

    /** 记录每次请求的 (路径, Range 头)，用于验证断点续传。 */
    fun rangeHeaders(): List<Pair<String, String?>> = seenRanges.toList()

    /** 记录每次请求的 (路径, If-None-Match 头)，用于验证 ETag 缓存。 */
    fun ifNoneMatchHeaders(): List<Pair<String, String?>> = seenIfNoneMatch.toList()

    /** 让某路径的下一次请求返回给定响应（用于注入 5xx / 404 / 坏内容）。 */
    fun enqueue(path: String, response: () -> MockResponse) {
        queued.computeIfAbsent(path) { ArrayDeque() }.add(response())
    }

    /** 覆盖某路径的响应。 */
    fun serve(path: String, response: () -> MockResponse) {
        overrides[path] = response
    }

    /** 让某路径返回本地文件（例如故意错 sha 的媒体）。 */
    fun serveFile(path: String, file: File) {
        overrides[path] = { fileResponse(file, null) }
    }

    fun reset() {
        counts.clear()
        seenRanges.clear()
        seenIfNoneMatch.clear()
        queued.clear()
    }

    private fun dispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = (request.path ?: "/").trimStart('/').substringBefore('?')
            counts.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            val range = request.getHeader("Range")
            seenRanges += path to range
            seenIfNoneMatch += path to request.getHeader("If-None-Match")

            queued[path]?.let { queue ->
                synchronized(queue) {
                    val next = queue.pollFirst()
                    if (next != null) return next
                }
            }
            overrides[path]?.let { return it() }

            val currentEtag = etag
            if (currentEtag != null && request.getHeader("If-None-Match") == currentEtag) {
                return MockResponse().setResponseCode(304).addHeader("ETag", currentEtag)
            }

            val file = File(root, path)
            if (!file.isFile || !file.canonicalPath.startsWith(root.canonicalPath)) {
                return MockResponse().setResponseCode(404)
            }
            return fileResponse(file, range)
        }
    }

    private fun fileResponse(file: File, range: String?): MockResponse {
        val bytes = file.readBytes()
        val response = MockResponse()
        etag?.let { response.addHeader("ETag", it) }
        if (range != null && range.startsWith("bytes=")) {
            val start = range.removePrefix("bytes=").substringBefore('-').toLongOrNull() ?: 0L
            if (start >= bytes.size) {
                return MockResponse().setResponseCode(416).addHeader("Content-Range", "bytes */${bytes.size}")
            }
            response.setResponseCode(206)
            response.addHeader("Content-Range", "bytes $start-${bytes.size - 1}/${bytes.size}")
            response.setBody(Buffer().write(bytes, start.toInt(), bytes.size - start.toInt()))
        } else {
            response.setResponseCode(200)
            response.setBody(Buffer().write(bytes))
        }
        return response
    }

    override fun close() {
        server.shutdown()
    }
}

// ───────────────────────── Fake QuestionStore（含单事务语义） ─────────────────────────

/**
 * 内存版 `QuestionStore`，**模拟单事务语义**：`applyPack` 先把记录写进一份副本，
 * 全部成功后才提交（含版本号）。中途抛异常时题库与版本完全不变，与 01 的 Room 实现一致。
 */
class FakeQuestionStore(
    private var version: Int = 0,
    initialQuestions: List<Question> = emptyList(),
) : QuestionStore {

    private val questionsById = LinkedHashMap<String, Question>()
    private val chapterList = ArrayList<Chapter>()

    var applyPackCalls: Int = 0
        private set
    val appliedVersions = ArrayList<Int>()

    /** 读到第 N 条记录时抛异常，用于验证「导入中途失败 → 版本不变」。 */
    var failAtRecord: Int? = null

    /** 自定义异常（例如模拟 Room 写入失败）。 */
    var failWith: (() -> Throwable)? = null

    init {
        initialQuestions.forEach { questionsById[it.id] = it }
    }

    override suspend fun bankVersion(): Int = version

    /** 当前题库版本，供测试断言（合同 QuestionStore 只有挂起的 bankVersion()）。 */
    fun currentVersion(): Int = version

    override suspend fun applyPack(
        newVersion: Int,
        chapters: List<Chapter>?,
        records: Sequence<PackRecord>,
        replaceAll: Boolean,
    ) {
        applyPackCalls++
        val working = if (replaceAll) LinkedHashMap() else LinkedHashMap(questionsById)
        var count = 0
        for (record in records) {
            count++
            failAtRecord?.let { threshold ->
                if (count == threshold) throw failWith?.invoke() ?: IllegalStateException("模拟导入中断（第 $count 条）")
            }
            when (record) {
                is PackRecord.Upsert -> working[record.question.id] = record.question
                is PackRecord.Delete -> working.remove(record.id)
            }
        }
        // 到这里才算提交：单事务
        questionsById.clear()
        questionsById.putAll(working)
        if (chapters != null) {
            chapterList.clear()
            chapterList.addAll(chapters)
        }
        appliedVersions += newVersion
        version = newVersion
    }

    override suspend fun allMediaRefs(): List<MediaRef> =
        questionsById.values.flatMap { it.media }.distinctBy { it.sha256 }

    fun questions(): List<Question> = questionsById.values.toList()

    fun question(id: String): Question? = questionsById[id]

    fun chapters(): List<Chapter> = chapterList.toList()

    fun count(): Int = questionsById.size
}

// ───────────────────────── Fake MediaStore ─────────────────────────

/** 用临时目录模拟 `:core:media`，`commit` 同样校验 sha256 + bytes。 */
class FakeMediaStore(private val root: File) : MediaStore {

    private val files = ConcurrentHashMap<String, File>()
    private val commits = MutableSharedFlow<String>(extraBufferCapacity = 64)

    override val committed: SharedFlow<String> = commits

    @Volatile
    var failCommit: Boolean = false

    var retained: Set<String>? = null
        private set

    var commitCalls: Int = 0
        private set

    override fun file(ref: MediaRef): File? = files[ref.sha256]?.takeIf { it.isFile }

    override suspend fun commit(ref: MediaRef, tmp: File): Boolean {
        commitCalls++
        if (failCommit) {
            tmp.delete()
            return false
        }
        val valid = tmp.isFile && tmp.length() == ref.bytes && Sha256.of(tmp) == ref.sha256
        if (!valid) {
            tmp.delete()
            return false
        }
        val target = File(root, "${ref.sha256}.${ref.ext}").apply { parentFile?.mkdirs() }
        tmp.copyTo(target, overwrite = true)
        tmp.delete()
        files[ref.sha256] = target
        commits.tryEmit(ref.sha256)
        return true
    }

    override suspend fun retain(keepSha: Set<String>): Int {
        retained = keepSha
        return files.keys.count { it !in keepSha }
    }

    override fun usedBytes(): Long = files.values.sumOf { it.length() }

    fun committedShas(): Set<String> = files.keys.toSet()
}

// ───────────────────────── Fake 设置 / 调度 / 版本 ─────────────────────────

/** 内存版设置（DataStore 的替身）。 */
class FakeUpdateSettings(initialSource: String = "") : UpdateSettingsSource {

    private val source = MutableStateFlow(if (initialSource.isBlank()) "" else Urls.normalizeSource(initialSource))
    private val etagValue = MutableStateFlow<String?>(null)

    override val sourceUrl: Flow<String> = source
    override val etag: Flow<String?> = etagValue

    override suspend fun setSource(raw: String) {
        source.value = Urls.normalizeSource(raw)
    }

    override suspend fun setEtag(value: String?) {
        etagValue.value = value
    }

    fun currentSource(): String = source.value

    fun currentEtag(): String? = etagValue.value
}

/** 记录入队行为的调度器替身（真实实现是 WorkManager）。 */
class FakeWorkScheduler : UpdateWorkScheduler {

    val updateRequests = AtomicInteger()
    val autoStates = CopyOnWriteArrayList<Boolean>()

    override fun enqueueUpdate() {
        updateRequests.incrementAndGet()
    }

    override fun scheduleAuto(enabled: Boolean) {
        autoStates += enabled
    }
}

/** 固定 versionCode。 */
class FixedAppVersion(private val code: Int) : AppVersionProvider {
    override fun versionCode(): Int = code
}

// ───────────────────────── Fake Downloader（MediaSync 用） ─────────────────────────

/**
 * 按 sha 服务的下载器替身：URL 末段 `{sha}.{ext}` 决定返回哪段字节，
 * 因此可以同时验证「媒体地址拼得对不对」与并发/失败行为。
 */
class FakeDownloader(
    private val tempDir: File,
    private val bodies: Map<String, ByteArray>,
) : Downloader {

    private val active = AtomicInteger()
    val maxActive = AtomicInteger()
    val requestedPaths = CopyOnWriteArrayList<String>()

    /** 永远失败的媒体 sha（模拟 404 / 校验不过）。 */
    var failingShas: Set<String> = emptySet()

    /** 每个下载耗时（毫秒）；配合 runTest 观察并发度。 */
    var delayMs: Long = 0L

    override suspend fun fetchText(url: HttpUrl, etag: String?): FetchResult =
        throw UnsupportedOperationException("MediaSync 不拉 manifest")

    override suspend fun downloadToTemp(
        url: HttpUrl,
        key: String,
        expectedSha256: String,
        expectedBytes: Long?,
        onProgress: (suspend (Long, Long) -> Unit)?,
    ): File {
        val current = active.incrementAndGet()
        maxActive.updateAndGet { maxOf(it, current) }
        try {
            requestedPaths += url.encodedPath
            if (delayMs > 0L) delay(delayMs)
            val sha = url.pathSegments.last().substringBeforeLast('.')
            if (sha in failingShas) throw DownloadException("模拟下载失败：${url.encodedPath}", retryable = true)
            val bytes = bodies[sha] ?: throw DownloadException("没有该媒体的字节：$sha", retryable = false)
            val file = File(tempDir, key).apply { parentFile?.mkdirs() }
            file.writeBytes(bytes)
            onProgress?.invoke(bytes.size.toLong(), bytes.size.toLong())
            return file
        } finally {
            active.decrementAndGet()
        }
    }
}

// ───────────────────────── 小工具 ─────────────────────────

fun gzipBytes(text: String): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    return out.toByteArray()
}

fun zipBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

/** 一行合法题目 JSON（合同 §2 形状）。 */
fun questionLine(index: Int): String =
    """{"id":"s1-${"%06d".format(index)}","subject":1,"vehicles":["car"],"type":"judge",""" +
        """"chapter_id":"s1-c01","tags":[],"stem":"第 $index 题","options":[""" +
        """{"key":"A","text":"正确"},{"key":"B","text":"错误"}],"answer":["A"],"explain":"","media":[],"rev":1}"""

/** 由字节内容造 [MediaRef]（sha/bytes 自洽）。 */
fun mediaRefOf(
    content: ByteArray,
    kind: MediaKind = MediaKind.IMAGE,
    ext: String = "webp",
): MediaRef = MediaRef(
    sha256 = Sha256.of(content),
    ext = ext,
    kind = kind,
    width = 100,
    height = 100,
    bytes = content.size.toLong(),
)

/** 确定性媒体字节：同一 id 永远同一内容。 */
fun mediaBytes(id: String, size: Int): ByteArray = ByteArray(size) { i -> ((i * 31 + id.hashCode()) and 0xFF).toByte() }

/** 临时目录，测试结束由 JUnit 的 TemporaryFolder 或调用方清理。 */
fun tempDir(prefix: String, parent: File): File = File(parent, "$prefix-${System.nanoTime()}").apply { mkdirs() }
