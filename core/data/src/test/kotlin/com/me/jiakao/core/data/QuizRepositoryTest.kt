package com.me.jiakao.core.data

import com.me.jiakao.core.model.PackRecord
import com.me.jiakao.core.model.QFilter
import com.me.jiakao.core.model.QType
import com.me.jiakao.core.model.Scope
import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ids 过滤(章节/类型/含图/含动图/标签)、车型 Scope、搜索、计数、版本流。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuizRepositoryTest {

    private lateinit var quizDb: com.me.jiakao.core.data.db.QuizDatabase
    private lateinit var store: QuestionStoreImpl
    private lateinit var repo: QuizRepositoryImpl

    @Before
    fun setUp() = runBlocking {
        quizDb = Fixtures.quizDb()
        store = QuestionStoreImpl(quizDb)
        repo = QuizRepositoryImpl(quizDb)
        store.applyPack(
            7,
            Fixtures.standardChapters,
            Fixtures.standardQuestions().map { PackRecord.Upsert(it) }.asSequence(),
            replaceAll = true,
        )
    }

    @Test
    fun `ids filter by chapter`() = runBlocking {
        assertEquals(listOf("s1-000001", "s1-000002"), repo.ids(Scope(1), "s1-c01"))
        assertEquals(listOf("s1-000003", "s1-000004", "s1-000005"), repo.ids(Scope(1), "s1-c02"))
    }

    @Test
    fun `ids filter by type media anim tag`() = runBlocking {
        assertEquals(listOf("s1-000003"), repo.ids(Scope(1), filter = QFilter.OfType(QType.MULTI)))
        // q1/q4 静图 + q5 动图,HasMedia 含动图
        assertEquals(
            listOf("s1-000001", "s1-000004", "s1-000005"),
            repo.ids(Scope(1), filter = QFilter.HasMedia),
        )
        assertEquals(listOf("s1-000005"), repo.ids(Scope(1), filter = QFilter.HasAnim))
        assertEquals(listOf("s1-000001"), repo.ids(Scope(1), filter = QFilter.Tag("标志")))
        assertEquals(listOf("s1-000001"), repo.ids(Scope(1), filter = QFilter.Tag("禁令")))
        assertEquals(listOf("s1-000005"), repo.ids(Scope(1), filter = QFilter.Tag("动画")))
    }

    @Test
    fun `vehicle scope filters questions`() = runBlocking {
        // q7 仅 truck;car 用户可见 5 题;truck 用户可见 q3(car+truck)与 q7
        val car = repo.ids(Scope(1, "car"))
        assertEquals(5, car.size)
        assertTrue(!car.contains("s1-000007"))

        val truck = repo.ids(Scope(1, "truck"))
        assertEquals(listOf("s1-000003", "s1-000007"), truck)
    }

    @Test
    fun `ids order is stable by sort key regardless of import order`() = runBlocking {
        val reordered = Fixtures.standardQuestions().shuffled()
        val db2 = Fixtures.quizDb()
        val store2 = QuestionStoreImpl(db2)
        val repo2 = QuizRepositoryImpl(db2)
        store2.applyPack(1, null, reordered.map { PackRecord.Upsert(it) }.asSequence(), replaceAll = true)

        assertEquals(repo.ids(Scope(1)), repo2.ids(Scope(1)))
    }

    @Test
    fun `getQuestions preserves input order and skips missing`() = runBlocking {
        val loaded = repo.getQuestions(listOf("s1-000003", "s1-999999", "s1-000001", "s1-000003"))
        assertEquals(
            listOf("s1-000003", "s1-000001", "s1-000003"),
            loaded.map { it.id },
        )
        assertTrue(repo.getQuestions(emptyList()).isEmpty())
    }

    @Test
    fun `counts by scope`() = runBlocking {
        val counts = repo.counts(Scope(1, "car")).first()
        assertEquals(5, counts.total)
        assertEquals(3, counts.withMedia) // q1/q4 静图 + q5 动图
        assertEquals(1, counts.withAnim)
        assertEquals(2, counts.byType[QType.JUDGE]) // q1/q4
        assertEquals(2, counts.byType[QType.SINGLE]) // q2/q5
        assertEquals(1, counts.byType[QType.MULTI]) // q3

        assertEquals(1, repo.counts(Scope(4)).first().total)
    }

    @Test
    fun `search via FTS for ascii and LIKE for chinese`() = runBlocking {
        // ASCII → FTS4 前缀匹配
        assertEquals(listOf("s1-000002"), repo.search(Scope(1), "speed").map { it.id })
        // 中文 → LIKE 子串匹配
        assertEquals(listOf("s1-000001"), repo.search(Scope(1), "禁止车辆停放").map { it.id })
        assertEquals(listOf("s1-000005"), repo.search(Scope(1), "动画所示").map { it.id })
        // 空 keyword
        assertTrue(repo.search(Scope(1), "  ").isEmpty())
        // 无结果
        assertTrue(repo.search(Scope(1), "不存在的关键词").isEmpty())
    }

    @Test
    fun `bankVersion flow emits new version after applyPack`() = runBlocking {
        repo.bankVersion.test {
            assertEquals(7, awaitItem())
            val db2 = quizDb // 同库再导入
            QuestionStoreImpl(db2).applyPack(
                8,
                null,
                sequenceOf(PackRecord.Upsert(Fixtures.question(id = "s1-000008"))),
                replaceAll = false,
            )
            assertEquals(8, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
