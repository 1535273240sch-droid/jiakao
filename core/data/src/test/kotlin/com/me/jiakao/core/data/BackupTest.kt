package com.me.jiakao.core.data

import com.me.jiakao.core.model.ExamResult
import com.me.jiakao.core.model.PackRecord
import com.me.jiakao.core.model.PracticeMode
import com.me.jiakao.core.model.Question
import com.me.jiakao.core.model.Scope
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 备份导出/导入往返 + 合并语义 + 题库更新不触碰 user.db。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupTest {

    private lateinit var quizDb: com.me.jiakao.core.data.db.QuizDatabase
    private lateinit var userDb: com.me.jiakao.core.data.db.UserDatabase
    private lateinit var store: QuestionStoreImpl
    private lateinit var userRepo: UserRepositoryImpl
    private lateinit var questions: List<Question>

    @Before
    fun setUp() = runBlocking {
        quizDb = Fixtures.quizDb()
        userDb = Fixtures.userDb()
        store = QuestionStoreImpl(quizDb)
        userRepo = UserRepositoryImpl(userDb, QuizRepositoryImpl(quizDb))
        questions = Fixtures.standardQuestions()
        store.applyPack(1, Fixtures.standardChapters, questions.map { PackRecord.Upsert(it) }.asSequence(), replaceAll = true)
    }

    private suspend fun seedUserData() {
        val q1 = questions.first { it.id == "s1-000001" }
        val q2 = questions.first { it.id == "s1-000002" }
        val q3 = questions.first { it.id == "s1-000003" }
        userRepo.recordAnswer(q1, listOf("B"), PracticeMode.SEQUENTIAL) // 错
        userRepo.recordAnswer(q2, listOf("B"), PracticeMode.SEQUENTIAL) // 对
        userRepo.recordAnswer(q3, listOf("A", "C"), PracticeMode.SEQUENTIAL) // 对
        assertTrue(userRepo.toggleFavorite(q2))
        userRepo.savePosition("s1-c01", 5)
        userRepo.savePosition("s1-c02", 9)
        userRepo.saveExam(
            ExamResult(0, 1, startedAt = 12345, durationSec = 2700, score = 92, passed = true,
                questionIds = listOf("s1-000001", "s1-000002"), wrongIds = listOf("s1-000001")),
        )
    }

    @Test
    fun `backup roundtrip restores all tables into a fresh database`() = runBlocking {
        seedUserData()
        val out = ByteArrayOutputStream()
        userRepo.exportBackup(out)

        val freshUserDb = Fixtures.userDb()
        val freshRepo = UserRepositoryImpl(freshUserDb, QuizRepositoryImpl(quizDb))
        freshRepo.importBackup(ByteArrayInputStream(out.toByteArray()))

        // 快照对比
        assertEquals(userDb.answerStatDao().getAll().sortedBy { it.questionId }, freshUserDb.answerStatDao().getAll().sortedBy { it.questionId })
        assertEquals(userDb.wrongDao().getAll().sortedBy { it.questionId }, freshUserDb.wrongDao().getAll().sortedBy { it.questionId })
        assertEquals(userDb.favoriteDao().getAll().sortedBy { it.questionId }, freshUserDb.favoriteDao().getAll().sortedBy { it.questionId })
        assertEquals(userDb.examDao().getAll().sortedBy { it.id }, freshUserDb.examDao().getAll().sortedBy { it.id })
        assertEquals(userDb.positionDao().getAll().sortedBy { it.key }, freshUserDb.positionDao().getAll().sortedBy { it.key })
        // 导入后返回真实业务数据
        assertEquals(setOf("s1-000002"), freshRepo.favoriteIds(1).first())
        assertEquals(1, freshRepo.exams(1).first().size)
    }

    @Test
    fun `import merges without clearing existing data`() = runBlocking {
        seedUserData()
        val out = ByteArrayOutputStream()
        userRepo.exportBackup(out)

        // 目标库:先有 q4 的统计与一条收藏(备份里没有)
        val targetDb = Fixtures.userDb()
        val targetRepo = UserRepositoryImpl(targetDb, QuizRepositoryImpl(quizDb))
        val q4 = questions.first { it.id == "s1-000004" }
        targetRepo.recordAnswer(q4, listOf("A"), PracticeMode.SEQUENTIAL)
        targetRepo.toggleFavorite(q4)

        targetRepo.importBackup(ByteArrayInputStream(out.toByteArray()))

        // 原有数据仍在 + 备份数据并入
        val stats = targetDb.answerStatDao().getAll().map { it.questionId }.toSet()
        assertEquals(setOf("s1-000001", "s1-000002", "s1-000003", "s1-000004"), stats)
        assertEquals(2, targetDb.favoriteDao().getAll().size)
        assertEquals(1, targetDb.examDao().getAll().size)
    }

    @Test
    fun `backup json carries schema and exported_at`() = runBlocking {
        seedUserData()
        val out = ByteArrayOutputStream()
        userRepo.exportBackup(out)
        val json = out.toString("UTF-8")
        assertTrue(json.contains("\"schema\":1"))
        assertTrue(json.contains("exported_at"))
    }

    @Test
    fun `bank update never touches user db`() = runBlocking {
        seedUserData()
        val wrongBefore = userDb.wrongDao().getAll().size
        val favoriteBefore = userDb.favoriteDao().getAll().size
        val examBefore = userDb.examDao().getAll().size
        val statBefore = userDb.answerStatDao().getAll().size

        // v2 增量:改 q1、删 q3、新增 q8
        val updatedQ1 = questions.first { it.id == "s1-000001" }.copy(rev = 2, stem = "修订后的题干")
        val newQ = Fixtures.question(id = "s1-000008", chapterId = "s1-c01")
        store.applyPack(
            2,
            null,
            sequenceOf(
                PackRecord.Upsert(updatedQ1),
                PackRecord.Delete("s1-000003"),
                PackRecord.Upsert(newQ),
            ),
            replaceAll = false,
        )

        assertEquals(wrongBefore, userDb.wrongDao().getAll().size)
        assertEquals(favoriteBefore, userDb.favoriteDao().getAll().size)
        assertEquals(examBefore, userDb.examDao().getAll().size)
        assertEquals(statBefore, userDb.answerStatDao().getAll().size)
        assertEquals(2, store.bankVersion())
        val quizRepo = QuizRepositoryImpl(quizDb)
        assertTrue(quizRepo.ids(Scope(1, "car"), "s1-c01").contains("s1-000008"))
        assertTrue(!quizRepo.ids(Scope(1, "car"), "s1-c02").contains("s1-000003")) // q3 已删除
    }
}
