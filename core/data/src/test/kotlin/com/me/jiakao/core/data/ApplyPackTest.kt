package com.me.jiakao.core.data

import com.me.jiakao.core.model.PackRecord
import com.me.jiakao.core.model.Question
import com.me.jiakao.core.model.Scope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 题库包导入:全量/增量/删除/回滚/章节替换。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApplyPackTest {

    private val quizDb = Fixtures.quizDb()
    private val store = QuestionStoreImpl(quizDb)
    private val repo = QuizRepositoryImpl(quizDb)
    private val scope = Scope(1)

    private fun upserts(questions: List<Question>): Sequence<PackRecord> =
        questions.map { PackRecord.Upsert(it) }.asSequence()

    @Test
    fun `full import sets version rows chapters and roundtrips models`() = runBlocking {
        store.applyPack(12, Fixtures.standardChapters, upserts(Fixtures.standardQuestions()), replaceAll = true)

        assertEquals(12, store.bankVersion())
        assertEquals(12, repo.bankVersion.first())
        val all = repo.ids(Scope(1))
        assertEquals(listOf("s1-000001", "s1-000002", "s1-000003", "s1-000004", "s1-000005"), all)
        assertEquals(
            listOf("s1-c01", "s1-c02", "s1-c03"),
            repo.chapters(Scope(1)).first().map { it.id },
        )
        // 实体 ↔ 模型往返无损
        val expected = Fixtures.standardQuestions().first { it.id == "s1-000003" }
        assertEquals(expected, repo.getQuestions(listOf("s1-000003")).single())
    }

    @Test
    fun `replace all clears previous bank`() = runBlocking {
        store.applyPack(1, null, upserts(Fixtures.standardQuestions()), replaceAll = true)
        store.applyPack(
            2,
            null,
            upserts(listOf(Fixtures.question(id = "s1-000009", stem = "新题库唯一题目"))),
            replaceAll = true,
        )

        assertEquals(listOf("s1-000009"), repo.ids(scope))
        // 旧题干不再可搜到(FTS 已同步清空)
        assertTrue(repo.search(scope, "禁止停车").isEmpty())
        assertTrue(repo.search(scope, "新题库").isNotEmpty())
    }

    @Test
    fun `incremental upsert updates rev and adds new`() = runBlocking {
        store.applyPack(1, null, upserts(Fixtures.standardQuestions().take(3)), replaceAll = true)
        val updated = Fixtures.standardQuestions().first { it.id == "s1-000002" }.copy(rev = 5, stem = "更新后的题干")
        store.applyPack(
            2,
            null,
            upserts(listOf(updated, Fixtures.question(id = "s1-000006", chapterId = "s1-c01"))),
            replaceAll = false,
        )

        assertEquals(4, repo.ids(scope).size)
        val loaded = repo.getQuestions(listOf("s1-000002")).single()
        assertEquals(5, loaded.rev)
        assertEquals("更新后的题干", loaded.stem)
    }

    @Test
    fun `delete records remove rows`() = runBlocking {
        store.applyPack(1, null, upserts(Fixtures.standardQuestions()), replaceAll = true)
        store.applyPack(2, null, sequenceOf(PackRecord.Delete("s1-000001")), replaceAll = false)

        assertFalse(repo.ids(scope).contains("s1-000001"))
        assertTrue(repo.search(scope, "禁止车辆停放").isEmpty())
        assertEquals(4, repo.ids(scope).size) // car 范围:q2/q3/q4/q5(q7 仅 truck)
    }

    @Test
    fun `exception rolls back entire pack`() = runBlocking {
        store.applyPack(1, null, upserts(Fixtures.standardQuestions().take(3)), replaceAll = true)

        val boom = RuntimeException("boom")
        val throwing = sequence {
            yield(PackRecord.Upsert(Fixtures.question(id = "s1-000010", chapterId = "s1-c01")))
            yield(PackRecord.Upsert(Fixtures.question(id = "s1-000011", chapterId = "s1-c01")))
            throw boom
        }
        val thrown = runCatching {
            store.applyPack(2, null, throwing, replaceAll = false)
        }.exceptionOrNull()

        // 异常原样抛出(跨协程边界后按类型与消息比对),事务整体回滚
        assertTrue(thrown is RuntimeException)
        assertEquals("boom", thrown?.message)
        // 版本与数据全部回滚
        assertEquals(1, store.bankVersion())
        assertEquals(3, repo.ids(scope).size)
        assertTrue(repo.ids(scope).none { it == "s1-000010" || it == "s1-000011" })
    }

    @Test
    fun `chapters replaced only when non null`() = runBlocking {
        store.applyPack(1, Fixtures.standardChapters, upserts(Fixtures.standardQuestions().take(2)), replaceAll = true)

        store.applyPack(2, null, upserts(Fixtures.standardQuestions().drop(2).take(1)), replaceAll = false)
        assertEquals(3, repo.chapters(Scope(1)).first().size) // 未被触碰(s1 三章)

        store.applyPack(
            3,
            listOf(com.me.jiakao.core.model.Chapter("s1-c01", 1, "只剩一章", 1)),
            upserts(emptyList()),
            replaceAll = false,
        )
        val chapters = repo.chapters(Scope(1)).first()
        assertEquals(1, chapters.size)
        assertEquals("只剩一章", chapters.single().name)
    }

    @Test
    fun `allMediaRefs dedupes by sha256`() = runBlocking {
        store.applyPack(1, null, upserts(Fixtures.standardQuestions()), replaceAll = true)
        val refs = store.allMediaRefs()
        // q1 与 q4 用了不同 sha 的图,q5 用动图 → 共 3 个去重引用
        assertEquals(3, refs.size)
        assertEquals(refs.map { it.sha256 }.distinct(), refs.map { it.sha256 })
    }
}
