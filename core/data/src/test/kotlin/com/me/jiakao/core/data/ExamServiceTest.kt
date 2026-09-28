package com.me.jiakao.core.data

import com.me.jiakao.core.model.PackRecord
import com.me.jiakao.core.model.QType
import com.me.jiakao.core.model.Scope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 考试引擎:规则表、组卷、评分(科一/科四、多选边界、未作答按错)。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExamServiceTest {

    private lateinit var quizDb: com.me.jiakao.core.data.db.QuizDatabase
    private lateinit var store: QuestionStoreImpl
    private lateinit var repo: QuizRepositoryImpl
    private lateinit var examService: ExamServiceImpl

    @Before
    fun setUp() = runBlocking {
        quizDb = Fixtures.quizDb()
        store = QuestionStoreImpl(quizDb)
        repo = QuizRepositoryImpl(quizDb)
        examService = ExamServiceImpl(repo)
    }

    @Test
    fun `rules table matches contract`() {
        val r1 = examService.rules(1)
        assertEquals(100, r1.questionCount)
        assertEquals(2700, r1.timeLimitSec)
        assertEquals(1, r1.pointsPerQuestion)
        assertEquals(90, r1.passScore)

        val r4 = examService.rules(4)
        assertEquals(50, r4.questionCount)
        assertEquals(1800, r4.timeLimitSec)
        assertEquals(2, r4.pointsPerQuestion)
        assertEquals(90, r4.passScore)
    }

    @Test
    fun `buildPaper takes all when bank is smaller than paper size`() = runBlocking {
        store.applyPack(1, null, Fixtures.standardQuestions().map { PackRecord.Upsert(it) }.asSequence(), replaceAll = true)
        val paper = examService.buildPaper(Scope(1))
        assertEquals(repo.ids(Scope(1)).toSet(), paper.toSet()) // 不足则全取(car 范围 5 题)
        assertEquals(5, paper.size)
    }

    @Test
    fun `buildPaper draws exactly questionCount distinct ids from scope`() = runBlocking {
        store.applyPack(1, null, Fixtures.bulkQuestions(150).map { PackRecord.Upsert(it) }.asSequence(), replaceAll = true)
        val paper = examService.buildPaper(Scope(1))
        assertEquals(100, paper.size)
        assertEquals(paper.size, paper.toSet().size) // 无重复
        val all = repo.ids(Scope(1)).toSet()
        assertTrue(paper.all { it in all })
    }

    @Test
    fun `grade subject1 scoring and pass line`() {
        val questions = Fixtures.bulkQuestions(100)
        val answers = questions.associate { it.id to it.answer }
        val result = examService.grade(1, questions, answers, startedAt = 0, durationSec = 1000)
        assertEquals(100, result.score)
        assertTrue(result.passed)
        assertTrue(result.wrongIds.isEmpty())
        assertEquals(questions.map { it.id }, result.questionIds)

        // 89 对 → 89 分,不及格;未作答按错
        val answers89 = questions.take(89).associate { it.id to it.answer }
        val r89 = examService.grade(1, questions, answers89, 0, 1000)
        assertEquals(89, r89.score)
        assertFalse(r89.passed)
        assertEquals(questions.drop(89).map { it.id }, r89.wrongIds)
    }

    @Test
    fun `grade subject4 two points per question`() {
        val questions = Fixtures.bulkQuestions(50, subject = 4).map {
            if (it.type == QType.MULTI) it.copy(type = QType.SINGLE, answer = listOf("A")) else it
        }
        // 45 对 → 90 分及格
        val pass = examService.grade(4, questions, questions.take(45).associate { it.id to it.answer }, 0, 100)
        assertEquals(90, pass.score)
        assertTrue(pass.passed)

        // 44 对 → 88 分不及格
        val fail = examService.grade(4, questions, questions.take(44).associate { it.id to it.answer }, 0, 100)
        assertEquals(88, fail.score)
        assertFalse(fail.passed)
    }

    @Test
    fun `grade multi partial selection is wrong`() {
        val multi = Fixtures.question(
            id = "s1-000100",
            type = QType.MULTI,
            options = listOf(
                com.me.jiakao.core.model.Option("A", "a"),
                com.me.jiakao.core.model.Option("B", "b"),
                com.me.jiakao.core.model.Option("C", "c"),
                com.me.jiakao.core.model.Option("D", "d"),
            ),
            answer = listOf("A", "C"),
        )
        val grade = { chosen: List<String> ->
            examService.grade(1, listOf(multi), mapOf(multi.id to chosen), 0, 1).score
        }
        assertEquals(1, grade(listOf("C", "A"))) // 乱序同集合 → 对
        assertEquals(0, grade(listOf("A"))) // 漏选
        assertEquals(0, grade(listOf("A", "B", "C"))) // 错选
        assertEquals(0, grade(emptyList())) // 未作答
        assertEquals(listOf(multi.id), examService.grade(1, listOf(multi), emptyMap(), 0, 1).wrongIds)
    }
}
