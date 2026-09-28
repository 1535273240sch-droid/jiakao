package com.me.jiakao.core.data

import com.me.jiakao.core.model.ExamResult
import com.me.jiakao.core.model.PackRecord
import com.me.jiakao.core.model.PracticeMode
import com.me.jiakao.core.model.QType
import com.me.jiakao.core.model.Scope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 错题本(连对 3 次移出/答错重置)、收藏、进度、章节统计、考试记录、作答统计。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserRepositoryTest {

    private lateinit var quizDb: com.me.jiakao.core.data.db.QuizDatabase
    private lateinit var userDb: com.me.jiakao.core.data.db.UserDatabase
    private lateinit var store: QuestionStoreImpl
    private lateinit var userRepo: UserRepositoryImpl
    private lateinit var questions: List<com.me.jiakao.core.model.Question>

    @Before
    fun setUp() = runBlocking {
        quizDb = Fixtures.quizDb()
        userDb = Fixtures.userDb()
        store = QuestionStoreImpl(quizDb)
        userRepo = UserRepositoryImpl(userDb, QuizRepositoryImpl(quizDb))
        questions = Fixtures.standardQuestions()
        store.applyPack(1, Fixtures.standardChapters, questions.map { PackRecord.Upsert(it) }.asSequence(), replaceAll = true)
    }

    private suspend fun answer(id: String, chosen: List<String>): Boolean {
        val q = questions.first { it.id == id }
        return userRepo.recordAnswer(q, chosen, PracticeMode.SEQUENTIAL)
    }

    @Test
    fun `recordAnswer accumulates stats`() = runBlocking {
        assertTrue(answer("s1-000001", listOf("A")))
        assertTrue(answer("s1-000001", listOf("A")))
        assertFalse(answer("s1-000001", listOf("B")))

        val stat = userDb.answerStatDao().get("s1-000001")!!
        assertEquals(3, stat.times)
        assertEquals(2, stat.correctTimes)
        assertFalse(stat.lastCorrect)
    }

    @Test
    fun `wrong question removed after 3 consecutive correct`() = runBlocking {
        assertFalse(answer("s1-000002", listOf("A"))) // 答错入本
        assertEquals(listOf("s1-000002"), userRepo.wrongIds(1).first())

        assertTrue(answer("s1-000002", listOf("B"))) // 连对 1
        assertEquals(listOf("s1-000002"), userRepo.wrongIds(1).first())
        assertTrue(answer("s1-000002", listOf("B"))) // 连对 2
        assertEquals(listOf("s1-000002"), userRepo.wrongIds(1).first())
        assertTrue(answer("s1-000002", listOf("B"))) // 连对 3 → 自动移出
        assertTrue(userRepo.wrongIds(1).first().isEmpty())
    }

    @Test
    fun `wrong again resets right streak`() = runBlocking {
        assertFalse(answer("s1-000002", listOf("A")))
        assertTrue(answer("s1-000002", listOf("B"))) // streak=1
        assertFalse(answer("s1-000002", listOf("A"))) // 答错 → streak 重置 0
        assertTrue(answer("s1-000002", listOf("B"))) // 1
        assertTrue(answer("s1-000002", listOf("B"))) // 2,仍在错题本
        assertEquals(listOf("s1-000002"), userRepo.wrongIds(1).first())
        assertTrue(answer("s1-000002", listOf("B"))) // 3 → 移出
        assertTrue(userRepo.wrongIds(1).first().isEmpty())
    }

    @Test
    fun `multi choice requires exact set equality`() = runBlocking {
        assertTrue(answer("s1-000003", listOf("C", "A"))) // 乱序同集合
        assertFalse(answer("s1-000003", listOf("A"))) // 漏选
        assertFalse(answer("s1-000003", listOf("A", "B", "C"))) // 错选
        assertFalse(answer("s1-000003", emptyList())) // 未作答
    }

    @Test
    fun `clearWrong removes entry`() = runBlocking {
        assertFalse(answer("s1-000001", listOf("B")))
        assertEquals(listOf("s1-000001"), userRepo.wrongIds(1).first())
        userRepo.clearWrong("s1-000001")
        assertTrue(userRepo.wrongIds(1).first().isEmpty())
    }

    @Test
    fun `toggleFavorite flips and flow follows`() = runBlocking {
        assertTrue(userRepo.toggleFavorite(questions.first { it.id == "s1-000001" }))
        assertEquals(setOf("s1-000001"), userRepo.favoriteIds(1).first())
        assertFalse(userRepo.toggleFavorite(questions.first { it.id == "s1-000001" }))
        assertTrue(userRepo.favoriteIds(1).first().isEmpty())
    }

    @Test
    fun `progress reflects scope and latest correctness`() = runBlocking {
        // s1/car 共 5 题;q1 答错、q2/q4 答对
        assertFalse(answer("s1-000001", listOf("B")))
        assertTrue(answer("s1-000002", listOf("B")))
        assertTrue(answer("s1-000004", listOf("A")))

        val p = userRepo.progress(Scope(1)).first()
        assertEquals(5, p.total)
        assertEquals(3, p.done)
        assertEquals(2, p.correct)

        // truck 范围:q3、q7 未作答
        val pt = userRepo.progress(Scope(1, "truck")).first()
        assertEquals(2, pt.total)
        assertEquals(0, pt.done)
    }

    @Test
    fun `chapterStats per chapter`() = runBlocking {
        assertFalse(answer("s1-000001", listOf("B"))) // c01
        assertTrue(answer("s1-000002", listOf("B"))) // c01
        assertTrue(answer("s1-000004", listOf("A"))) // c02

        val stats = userRepo.chapterStats(Scope(1)).first()
        val c01 = stats.first { it.chapter.id == "s1-c01" }
        val c02 = stats.first { it.chapter.id == "s1-c02" }
        assertEquals(2, c01.total)
        assertEquals(2, c01.done)
        assertEquals(1, c01.correct)
        assertEquals(3, c02.total)
        assertEquals(1, c02.done)
        assertEquals(1, c02.correct)
    }

    @Test
    fun `position defaults to zero and roundtrips`() = runBlocking {
        assertEquals(0, userRepo.loadPosition("s1-c01"))
        userRepo.savePosition("s1-c01", 17)
        assertEquals(17, userRepo.loadPosition("s1-c01"))
        userRepo.savePosition("s1-c01", 42)
        assertEquals(42, userRepo.loadPosition("s1-c01"))
    }

    @Test
    fun `exam save and list newest first`() = runBlocking {
        val r1 = ExamResult(0, 1, startedAt = 1000, durationSec = 2700, score = 96, passed = true, questionIds = listOf("s1-000001"), wrongIds = emptyList())
        val r2 = ExamResult(0, 1, startedAt = 2000, durationSec = 2700, score = 88, passed = false, questionIds = listOf("s1-000001"), wrongIds = listOf("s1-000001"))

        val id1 = userRepo.saveExam(r1)
        val id2 = userRepo.saveExam(r2)
        assertTrue(id1 > 0 && id2 > id1)

        val exams = userRepo.exams(1).first()
        assertEquals(2, exams.size)
        assertEquals(id2, exams[0].id) // 最新在前
        assertEquals(id1, exams[1].id)
        assertEquals(88, exams[0].score)
        assertFalse(exams[0].passed)
        assertEquals(listOf("s1-000001"), exams[0].wrongIds)

        assertTrue(userRepo.exams(4).first().isEmpty())
    }

    @Test
    fun `answer types map to qtype counts in progress scope`() = runBlocking {
        // 判断题按单元素集合判对
        assertTrue(answer("s1-000001", listOf("A")))
        assertFalse(answer("s1-000005", listOf("A"))) // single 答案是 B
        val stat = userDb.answerStatDao().get("s1-000005")!!
        assertEquals(1, stat.times)
        assertEquals(0, stat.correctTimes)
    }
}
