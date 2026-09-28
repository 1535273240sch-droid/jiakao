package com.me.jiakao.core.update.pack

import com.me.jiakao.core.model.MediaKind
import com.me.jiakao.core.model.Option
import com.me.jiakao.core.model.PackRecord
import com.me.jiakao.core.model.QType
import com.me.jiakao.core.update.Fixtures
import com.me.jiakao.core.update.gzipBytes
import com.me.jiakao.core.update.questionLine
import com.me.jiakao.core.update.util.Sha256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PackCodecTest {

    // ───────────── 合同 §2 示例：字节级兼容 ─────────────

    @Test
    fun `合同示例三行逐字解析`() {
        val text = Fixtures.text("contract-sample.jsonl")
        val records = PackCodec.stream(text.byteInputStream(), gzipped = false).toList()
        assertEquals(3, records.size)

        val first = records[0] as PackRecord.Upsert
        assertEquals("s1-000001", first.question.id)
        assertEquals(1, first.question.subject)
        assertEquals(listOf("car"), first.question.vehicles)
        assertEquals(QType.JUDGE, first.question.type)
        assertEquals("s1-c03", first.question.chapterId)
        assertEquals(listOf("标志", "禁令"), first.question.tags)
        assertEquals("如图所示，这个标志的含义是禁止车辆停放。", first.question.stem)
        assertEquals(listOf(Option("A", "正确"), Option("B", "错误")), first.question.options)
        assertEquals(listOf("A"), first.question.answer)
        assertEquals("红圈红斜杠蓝底为禁止停车标志。", first.question.explain)
        assertEquals(1, first.question.media.size)
        assertEquals("a".repeat(64), first.question.media[0].sha256)
        assertEquals("webp", first.question.media[0].ext)
        assertEquals(MediaKind.IMAGE, first.question.media[0].kind)
        assertEquals(480, first.question.media[0].width)
        assertEquals(8123L, first.question.media[0].bytes)
        assertEquals(1, first.question.rev)

        val second = records[1] as PackRecord.Upsert
        assertEquals(4, second.question.subject)
        assertEquals(QType.SINGLE, second.question.type)
        assertEquals(2, second.question.rev)
        assertEquals(MediaKind.ANIM, second.question.media[0].kind)
        assertEquals(412345L, second.question.media[0].bytes)

        val third = records[2] as PackRecord.Upsert
        assertEquals(listOf("car", "truck"), third.question.vehicles)
        assertEquals(QType.MULTI, third.question.type)
        assertEquals(listOf("A", "C"), third.question.answer)
        assertEquals("", third.question.explain)
        assertTrue(third.question.media.isEmpty())
    }

    // ───────────── 流式与删除行 ─────────────

    @Test
    fun `GZIP 与明文都能自动识别`() {
        val fromGzip = PackCodec.stream(Fixtures.bytes("full/bank-v1.jsonl.gz").inputStream()).toList()
        assertEquals(5, fromGzip.size)
        val fromPlain = PackCodec.stream(Fixtures.text("contract-sample.jsonl").byteInputStream()).toList()
        assertEquals(3, fromPlain.size)
        assertEquals(5, PackCodec.stream(Fixtures.file("full/bank-v2.jsonl.gz")).toList().size)
    }

    @Test
    fun `增量里的删除行解析为 Delete`() {
        val records = PackCodec.stream(Fixtures.bytes("delta/v1-v2.jsonl.gz").inputStream()).toList()
        assertEquals(3, records.size)
        assertEquals(2, records.count { it is PackRecord.Upsert })
        val delete = records.filterIsInstance<PackRecord.Delete>().single()
        assertEquals("s1-000005", delete.id)
    }

    @Test
    fun `删除行缺少 id 报错`() {
        val error = expectFormatError(lineNo = 4) { PackCodec.parseLine("""{"deleted":true}""", lineNo = 4) }
        assertTrue(error.message!!, error.message!!.contains("id"))
    }

    @Test
    fun `bank v2 全量内容正确`() {
        val questions = PackCodec.stream(Fixtures.bytes("full/bank-v2.jsonl.gz").inputStream())
            .filterIsInstance<PackRecord.Upsert>()
            .map { it.question }
            .toList()
        assertEquals(listOf("s1-000001", "s1-000002", "s1-000003", "s1-000004", "s1-000006"), questions.map { it.id })
        assertEquals(2, questions.first { it.id == "s1-000002" }.rev)
        assertTrue(questions.first { it.id == "s1-000002" }.stem.contains("2026 修订"))
    }

    /**
     * 惰性验证：5000 行的包里第 4000 行是坏行。
     * 只取前 10 行不应触发错误，说明没有把整个包读进内存、也没有提前解析。
     */
    @Test
    fun `流式解析是惰性的`() {
        val builder = StringBuilder()
        for (i in 1..5000) {
            builder.append(if (i == 4000) """{"id":"s1-004000","subject":1,""" else questionLine(i)).append('\n')
        }
        val stream = PackCodec.stream(gzipBytes(builder.toString()).inputStream())

        val head = stream.take(10).toList()
        assertEquals(10, head.size)

        // 重新开一个流迭代到底，应该在坏行处失败并带上行号
        val error = expectFormatError(lineNo = 4000) {
            PackCodec.stream(gzipBytes(builder.toString()).inputStream()).toList()
        }
        assertEquals(4000, error.line)
    }

    // ───────────── 错误路径（带行号） ─────────────

    @Test
    fun `坏 JSON 报第 3 行`() {
        val error = expectFormatError(lineNo = 3) {
            PackCodec.stream(Fixtures.bytes("broken/bad-json.jsonl.gz").inputStream()).toList()
        }
        assertEquals(3, error.line)
    }

    @Test
    fun `非法字段值报错并带上行号`() {
        // 夹具把第 1 行的 subject 改成 7（非法取值）
        val error = expectFormatError(lineNo = 1) {
            PackCodec.stream(Fixtures.bytes("broken/bad-field.jsonl.gz").inputStream()).toList()
        }
        assertEquals(1, error.line)
        assertTrue(error.message!!, error.message!!.contains("subject"))
    }

    @Test
    fun `截断的 GZIP 报错而不是静默结束`() {
        expectFormatError {
            PackCodec.stream(Fixtures.bytes("broken/truncated.gz").inputStream()).toList()
        }
    }

    @Test
    fun `字段缺失报带行号的错误`() {
        val line = """{"id":"s1-000009","subject":1,"vehicles":["car"],"type":"judge"}"""
        val error = expectFormatError(lineNo = 7) { PackCodec.parseLine(line, lineNo = 7) }
        assertEquals(7, error.line)
        assertTrue(error.message!!, error.message!!.contains("chapter_id"))
    }

    @Test
    fun `未知字段被忽略`() {
        val line = questionLine(1).replaceFirst("{", """{"future_field":{"a":1},""")
        val record = PackCodec.parseLine(line, lineNo = 1)
        assertTrue(record is PackRecord.Upsert)
    }

    @Test
    fun `判断题选项必须固定为 A正确 B错误`() {
        val line = questionLine(1).replace("""{"key":"B","text":"错误"}""", """{"key":"B","text":"不确定"}""")
        val error = expectFormatError { PackCodec.parseLine(line, lineNo = 1) }
        assertTrue(error.message!!, error.message!!.contains("判断题"))
    }

    @Test
    fun `答案必须落在选项内`() {
        val line = questionLine(1).replace("""["A"]""", """["D"]""")
        val error = expectFormatError { PackCodec.parseLine(line, lineNo = 1) }
        assertTrue(error.message!!, error.message!!.contains("answer"))
    }

    @Test
    fun `sha256 必须是小写十六进制`() {
        // questionLine 里已有一个 "media":[]；再插入一个 media 键会形成重复键，
        // 而 kotlinx-serialization 默认「后者覆盖前者」，注入的坏值会被原空数组盖掉。
        // 因此必须替换原字段，而不是新增字段。
        val line = questionLine(1).replace(
            """"media":[]""",
            """"media":[{"sha256":"${"A".repeat(64)}","ext":"webp","kind":"image","w":1,"h":1,"bytes":10}]""",
        )
        val error = expectFormatError { PackCodec.parseLine(line, lineNo = 1) }
        assertTrue(error.message!!, error.message!!.contains("sha256"))
    }

    @Test
    fun `视频必须是 mp4 图片必须是 webp`() {
        val line = questionLine(1).replace(
            """"media":[]""",
            """"media":[{"sha256":"${"a".repeat(64)}","ext":"webp","kind":"video","w":1,"h":1,"bytes":10}]""",
        )
        val error = expectFormatError { PackCodec.parseLine(line, lineNo = 1) }
        assertTrue(error.message!!, error.message!!.contains("mp4"))
    }

    @Test
    fun `vehicle 空或缺失时按 car 处理`() {
        val line = questionLine(1).replace(""""vehicles":["car"],""", "")
        val record = PackCodec.parseLine(line, lineNo = 1) as PackRecord.Upsert
        assertEquals(listOf("car"), record.question.vehicles)
    }

    @Test
    fun `非法 vehicle 报错`() {
        val line = questionLine(1).replace(""""vehicles":["car"]""", """"vehicles":["plane"]""")
        val error = expectFormatError { PackCodec.parseLine(line, lineNo = 1) }
        assertTrue(error.message!!, error.message!!.contains("vehicles"))
    }

    // ───────────── 清单 ─────────────

    @Test
    fun `清单解析正确且 sha 与夹具文件一致`() {
        val manifest = PackCodec.parseManifest(Fixtures.text("manifest.json"))
        assertEquals(1, manifest.schema)
        assertEquals(2, manifest.bankVersion)
        assertEquals(1, manifest.minAppVersionCode)
        assertEquals("media/", manifest.mediaBase)
        assertEquals(2, manifest.chapters.size)
        assertEquals("s1-c01", manifest.chapters[0].id)
        assertEquals("道路交通安全法律法规", manifest.chapters[0].name)
        assertEquals(2, manifest.chaptersOrNull()!!.size)

        val full = manifest.full
        assertNotNull(full)
        assertEquals("full/bank-v2.jsonl.gz", full!!.url)
        assertEquals(Sha256.of(Fixtures.file("full/bank-v2.jsonl.gz")), full.sha256)
        assertEquals(Fixtures.file("full/bank-v2.jsonl.gz").length(), full.bytes)
        assertEquals(5, full.count)

        val delta = manifest.deltas.single()
        assertEquals(1, delta.from)
        assertEquals(2, delta.to)
        assertEquals(Sha256.of(Fixtures.file("delta/v1-v2.jsonl.gz")), delta.sha256)

        val bundle = manifest.bundle
        assertNotNull(bundle)
        assertEquals("bundle/bundle-v2.zip", bundle!!.url)
        assertEquals(Sha256.of(Fixtures.file("bundle/bundle-v2.zip")), bundle.sha256)
    }

    @Test
    fun `不支持的 schema 报错`() {
        val error = expectFormatError { PackCodec.parseManifest(Fixtures.text("manifest-bad-schema.json")) }
        assertTrue(error.message!!, error.message!!.contains("schema"))
    }

    @Test
    fun `清单里的全量增量都缺时报 Unavailable 由 planner 负责`() {
        // 解析本身必须成功（结构合法），缺包是 plan() 的判断
        val manifest = PackCodec.parseManifest(Fixtures.text("manifest-nofull.json"))
        assertEquals(null, manifest.full)
        assertEquals(3, manifest.bankVersion)
    }

    @Test
    fun `media index 解析`() {
        val text = """[{"sha256":"${"a".repeat(64)}","ext":"webp","bytes":10,"path":"media/aa/x.webp"}]"""
        val entries = PackCodec.parseMediaIndex(text)
        assertEquals(1, entries.size)
        assertEquals("media/aa/x.webp", entries[0].path)
    }

    private fun expectFormatError(lineNo: Int = -1, block: () -> Unit): PackFormatException {
        try {
            block()
        } catch (e: PackFormatException) {
            if (lineNo >= 0) assertEquals("行号不符：${e.message}", lineNo, e.line)
            return e
        }
        fail("期望 PackFormatException")
        throw AssertionError("unreachable")
    }
}
