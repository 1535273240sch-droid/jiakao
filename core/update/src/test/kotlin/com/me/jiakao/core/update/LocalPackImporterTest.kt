package com.me.jiakao.core.update

import com.me.jiakao.core.model.Chapter
import com.me.jiakao.core.model.Question
import com.me.jiakao.core.model.QType
import com.me.jiakao.core.model.UpdateState
import com.me.jiakao.core.update.pack.PackCodec
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 离线整包导入测试：正常包、坏包、缺条目、版本过低、条目乱序。
 * 重点是「损坏包不改动现有数据」。
 */
class LocalPackImporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: FakeQuestionStore
    private lateinit var mediaStore: FakeMediaStore
    private lateinit var importer: LocalPackImporter

    private fun prepare(appVersionCode: Int = 1, seeded: List<Question> = emptyList(), version: Int = 0) {
        store = FakeQuestionStore(version, seeded)
        mediaStore = FakeMediaStore(temp.newFolder("media-${System.nanoTime()}"))
        importer = LocalPackImporter(
            questionStore = store,
            mediaStore = mediaStore,
            spoolRoot = temp.newFolder("dl-${System.nanoTime()}"),
            ioDispatcher = Dispatchers.Unconfined,
            appVersionCode = { appVersionCode },
        )
    }

    @Test
    fun `正常离线包导入题目与媒体`() = runTest {
        prepare()
        val states = ArrayList<UpdateState>()

        val result = importer.import(Fixtures.file("bundle/bundle-v2.zip").inputStream()) { states += it }

        assertEquals(2, result.version)
        assertEquals(5, result.recordsRead)
        assertEquals(3, result.mediaTotal)
        assertEquals(3, result.mediaDone)
        assertEquals(0, result.mediaFailed)

        assertEquals(2, store.currentVersion())
        assertEquals(5, store.count())
        assertEquals(listOf("s1-000001", "s1-000002", "s1-000003", "s1-000004", "s1-000006"), store.questions().map { it.id })
        assertEquals(2, store.chapters().size)
        assertEquals("道路交通安全法律法规", store.chapters()[0].name)

        assertEquals("三张被引用的媒体都应落盘", 3, mediaStore.committedShas().size)
        assertEquals("整包等价全量更新，媒体齐了就可以回收", store.allMediaRefs().map { it.sha256 }.toSet(), mediaStore.retained)
        assertTrue(states.contains(UpdateState.Importing))
        assertTrue(states.any { it is UpdateState.DownloadingMedia })
    }

    @Test
    fun `损坏包明确报错且不改动现有数据`() = runTest {
        prepare(seeded = listOf(sampleQuestion()), version = 1)

        val error = expectLocalPackError {
            importer.import(Fixtures.file("bundle/bundle-v2-corrupt.zip").inputStream())
        }

        assertTrue("损坏包应是 LocalPackCorruptException：${error.message}", error is LocalPackCorruptException)
        assertTrue(error.message!!, error.message!!.contains("不一致"))
        assertEquals("版本必须保持不变", 1, store.currentVersion())
        assertEquals("题目必须保持不变", 1, store.count())
        assertEquals("media 未被引用前不应提交", 0, mediaStore.commitCalls)
        assertEquals("一次 applyPack 都不应发生", 0, store.applyPackCalls)
    }

    @Test
    fun `缺少 bank jsonl 的包报错`() = runTest {
        prepare(seeded = listOf(sampleQuestion()), version = 1)

        val error = expectLocalPackError {
            importer.import(Fixtures.file("broken/no-bank.zip").inputStream())
        }

        assertTrue(error.message!!, error.message!!.contains("bank.jsonl"))
        assertEquals(1, store.currentVersion())
        assertEquals(0, store.applyPackCalls)
    }

    @Test
    fun `不是 ZIP 的输入报错`() = runTest {
        prepare()
        val error = expectLocalPackError {
            importer.import(ByteArrayInputStream("这不是一个 zip 文件".toByteArray(Charsets.UTF_8)))
        }
        assertTrue(error is LocalPackException)
        assertEquals(0, store.currentVersion())
    }

    @Test
    fun `题目数据损坏时回滚且版本不变`() = runTest {
        prepare(seeded = listOf(sampleQuestion()), version = 1)
        val bundle = zipBytes(
            listOf(
                "manifest.json" to Fixtures.text("manifest.json").toByteArray(Charsets.UTF_8),
                "bank.jsonl" to Fixtures.bytes("broken/bad-json.jsonl.gz"),
            ),
        )

        val error = expectLocalPackError { importer.import(ByteArrayInputStream(bundle)) }

        assertTrue("题目内容损坏：${error.message}", error is LocalPackCorruptException)
        assertEquals("失败必须回滚，版本不变", 1, store.currentVersion())
        assertEquals(1, store.count())
        assertTrue("失败的导入不能推进版本号", store.appliedVersions.isEmpty())
    }

    @Test
    fun `App 版本过低时拒绝导入`() = runTest {
        prepare(appVersionCode = 1, seeded = listOf(sampleQuestion()), version = 1)
        val bundle = zipBytes(
            listOf(
                "manifest.json" to Fixtures.text("manifest-minapp.json").toByteArray(Charsets.UTF_8),
                "bank.jsonl" to gunzip(Fixtures.bytes("full/bank-v1.jsonl.gz")),
            ),
        )

        val error = expectLocalPackError { importer.import(ByteArrayInputStream(bundle)) }

        assertTrue(error is LocalPackAppVersionException)
        assertEquals(99999, (error as LocalPackAppVersionException).requiredVersionCode)
        assertEquals(1, store.currentVersion())
        assertEquals(0, store.applyPackCalls)
    }

    @Test
    fun `ZIP 条目乱序也能导入`() = runTest {
        prepare()
        val bundle = outOfOrderBundle()

        val result = importer.import(ByteArrayInputStream(bundle))

        assertEquals(2, result.version)
        assertEquals(5, result.recordsRead)
        assertEquals(3, result.mediaDone)
        assertEquals(5, store.count())
    }

    // ───────────────────────── 工具 ─────────────────────────

    /** 媒体条目在前、manifest 在最后的包（ZIP 条目顺序不保证）。 */
    private fun outOfOrderBundle(): ByteArray {
        val entries = ArrayList<Pair<String, ByteArray>>()
        Fixtures.dir.resolve("media").walkTopDown()
            .filter { it.isFile }
            .forEach { file -> entries += "media/${file.parentFile.name}/${file.name}" to file.readBytes() }
        entries += "bank.jsonl" to gunzip(Fixtures.bytes("full/bank-v2.jsonl.gz"))
        entries += "manifest.json" to Fixtures.text("manifest.json").toByteArray(Charsets.UTF_8)
        return zipBytes(entries)
    }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    private fun sampleQuestion(): Question = Question(
        id = "s1-999999",
        subject = 1,
        vehicles = listOf("car"),
        type = QType.JUDGE,
        chapterId = "s1-c01",
        tags = emptyList(),
        stem = "已有题目",
        options = emptyList(),
        answer = listOf("A"),
        explain = "",
        media = emptyList(),
        rev = 1,
    )

    private suspend fun expectLocalPackError(block: suspend () -> Unit): LocalPackException {
        try {
            block()
        } catch (e: LocalPackException) {
            return e
        }
        fail("期望抛出 LocalPackException")
        throw AssertionError("unreachable")
    }
}
