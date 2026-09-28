package com.me.jiakao.fake

import com.me.jiakao.core.model.ChapterStat
import com.me.jiakao.core.model.ExamResult
import com.me.jiakao.core.model.PracticeMode
import com.me.jiakao.core.model.Progress
import com.me.jiakao.core.model.Question
import com.me.jiakao.core.model.QuizRepository
import com.me.jiakao.core.model.Scope
import com.me.jiakao.core.model.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** 假用户仓库:内存存储,语义与合同一致(错题自动入本、连对 3 次移出) */
@Singleton
class FakeUserRepository @Inject constructor(
    private val quiz: FakeQuizRepository,
) : UserRepository {

    private data class Record(
        val done: Boolean = false,
        val lastCorrect: Boolean = false,
        val inWrong: Boolean = false,
        val consecutiveCorrect: Int = 0,
    )

    private val records = MutableStateFlow<Map<String, Record>>(emptyMap())
    private val wrongSet = MutableStateFlow<Set<String>>(emptySet())
    private val favoriteSet = MutableStateFlow<Set<String>>(emptySet())
    private val positions = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val examList = MutableStateFlow<List<ExamResult>>(emptyList())
    private var nextExamId = 1L

    override suspend fun recordAnswer(question: Question, chosen: List<String>, mode: PracticeMode): Boolean {
        val correct = chosen.toSet() == question.answer.toSet()
        records.update { current ->
            val old = current[question.id] ?: Record()
            val newConsecutive = if (correct) old.consecutiveCorrect + 1 else 0
            // 错题连对 3 次自动移出
            val stillWrong = when {
                !correct -> true
                old.inWrong && newConsecutive >= 3 -> false
                else -> old.inWrong
            }
            current + (question.id to old.copy(
                done = true,
                lastCorrect = correct,
                inWrong = stillWrong,
                consecutiveCorrect = newConsecutive,
            ))
        }
        val rec = records.value[question.id] ?: Record()
        wrongSet.update { set -> if (rec.inWrong) set + question.id else set - question.id }
        return correct
    }

    override fun wrongIds(subject: Int): Flow<List<String>> = wrongSet.map { set ->
        set.filter { it.startsWith("s$subject-") }.sorted()
    }

    override suspend fun clearWrong(questionId: String) {
        wrongSet.update { it - questionId }
        records.update { current ->
            current[questionId]?.let { current + (questionId to it.copy(inWrong = false, consecutiveCorrect = 0)) } ?: current
        }
    }

    override fun favoriteIds(subject: Int): Flow<Set<String>> = favoriteSet.map { set ->
        set.filterTo(mutableSetOf()) { it.startsWith("s$subject-") }
    }

    override suspend fun toggleFavorite(question: Question): Boolean {
        val next = question.id !in favoriteSet.value
        favoriteSet.update { if (next) it + question.id else it - question.id }
        return next
    }

    override fun progress(scope: Scope): Flow<Progress> = combine(records, flowIds(scope)) { recs, ids ->
        val doneIds = ids.filter { recs[it]?.done == true }
        Progress(
            total = ids.size,
            done = doneIds.size,
            correct = doneIds.count { recs[it]?.lastCorrect == true },
        )
    }

    override fun chapterStats(scope: Scope): Flow<List<ChapterStat>> =
        combine(records, quiz.chapters(scope), flowIdsByChapter(scope)) { recs, chapters, byChapter ->
            chapters.map { chapter ->
                val ids = byChapter[chapter.id].orEmpty()
                val doneIds = ids.filter { recs[it]?.done == true }
                ChapterStat(
                    chapter = chapter,
                    total = ids.size,
                    done = doneIds.size,
                    correct = doneIds.count { recs[it]?.lastCorrect == true },
                )
            }
        }

    private fun flowIds(scope: Scope): Flow<List<String>> = flow {
        emit(quiz.ids(scope))
    }

    private fun flowIdsByChapter(scope: Scope): Flow<Map<String, List<String>>> = flow {
        val chapters = quiz.chapters(scope).first()
        emit(
            chapters.associate { chapter ->
                chapter.id to quiz.ids(scope, chapter.id)
            }
        )
    }

    override suspend fun savePosition(key: String, index: Int) {
        positions.update { it + (key to index) }
    }

    override suspend fun loadPosition(key: String): Int = positions.value[key] ?: 0

    override suspend fun saveExam(result: ExamResult): Long {
        val id = nextExamId++
        examList.update { (it + result.copy(id = id)).sortedBy { e -> e.startedAt } }
        return id
    }

    override fun exams(subject: Int): Flow<List<ExamResult>> = examList.map { list ->
        list.filter { it.subject == subject }
    }

    override suspend fun exportBackup(out: OutputStream) = withContext(Dispatchers.IO) {
        val json = """
            {"type":"jiakao-fake-backup","wrong":${wrongSet.value.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }},
             "favorites":${favoriteSet.value.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }},
             "positions":${positions.value.entries.joinToString(prefix = "{", postfix = "}") { "\"${it.key}\":${it.value}" }}}
        """.trimIndent()
        out.write(json.toByteArray(Charsets.UTF_8))
    }

    override suspend fun importBackup(input: InputStream) = withContext(Dispatchers.IO) {
        val text = input.readBytes().toString(Charsets.UTF_8)
        // 假实现:仅校验可读,不真正还原数据(拼装后由 01 的真实实现处理)
        require(text.contains("jiakao")) { "备份文件格式不正确" }
    }
}
