package com.me.jiakao.core.data

import androidx.room.withTransaction
import com.me.jiakao.core.data.db.AnswerStatDao
import com.me.jiakao.core.data.db.AnswerStatEntity
import com.me.jiakao.core.data.db.ExamDao
import com.me.jiakao.core.data.db.ExamEntity
import com.me.jiakao.core.data.db.FavoriteDao
import com.me.jiakao.core.data.db.FavoriteEntity
import com.me.jiakao.core.data.db.PositionDao
import com.me.jiakao.core.data.db.PositionEntity
import com.me.jiakao.core.data.db.UserDatabase
import com.me.jiakao.core.data.db.WrongDao
import com.me.jiakao.core.data.db.WrongEntity
import com.me.jiakao.core.model.ChapterStat
import com.me.jiakao.core.model.ExamResult
import com.me.jiakao.core.model.PracticeMode
import com.me.jiakao.core.model.Progress
import com.me.jiakao.core.model.Question
import com.me.jiakao.core.model.Scope
import com.me.jiakao.core.model.UserRepository
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * [UserRepository] 实现(user.db)。
 *
 * - 判对规则:多选题 = 选项集合完全相等(错选/漏选均不得分);单选/判断同规则。
 * - 错题本:答错进入(保留首次加入时间)并把连对计数清零;连对 [WRONG_CLEAR_STREAK] 次自动移出。
 * - progress/chapterStats:跨库统计 —— quiz.db 提供 Scope 集合与总数,user.db 提供作答统计,
 *   在内存中合并(均为单查询,无 N+1)。
 */
@Singleton
internal class UserRepositoryImpl @Inject constructor(
    private val db: UserDatabase,
    private val quiz: QuizRepositoryImpl,
) : UserRepository {

    private val statDao: AnswerStatDao get() = db.answerStatDao()
    private val wrongDao: WrongDao get() = db.wrongDao()
    private val favoriteDao: FavoriteDao get() = db.favoriteDao()
    private val examDao: ExamDao get() = db.examDao()
    private val positionDao: PositionDao get() = db.positionDao()

    override suspend fun recordAnswer(question: Question, chosen: List<String>, mode: PracticeMode): Boolean {
        // 判对:选项集合完全相等(未作答视为错)
        val correct = chosen.toSet() == question.answer.toSet()
        val now = System.currentTimeMillis()
        db.withTransaction {
            val existing = statDao.get(question.id)
            statDao.upsert(
                if (existing == null) {
                    AnswerStatEntity(
                        questionId = question.id,
                        subject = question.subject,
                        times = 1,
                        correctTimes = if (correct) 1 else 0,
                        lastCorrect = correct,
                        lastAt = now,
                    )
                } else {
                    existing.copy(
                        times = existing.times + 1,
                        correctTimes = existing.correctTimes + if (correct) 1 else 0,
                        lastCorrect = correct,
                        lastAt = now,
                    )
                },
            )

            val wrong = wrongDao.get(question.id)
            if (!correct) {
                // 答错:入错题本(已在则保留首次加入时间),连对计数清零
                wrongDao.upsert(
                    WrongEntity(
                        questionId = question.id,
                        subject = question.subject,
                        addedAt = wrong?.addedAt ?: now,
                        rightStreak = 0,
                    ),
                )
            } else if (wrong != null) {
                // 错题答对:连对 +1,达到阈值自动移出
                val streak = wrong.rightStreak + 1
                if (streak >= WRONG_CLEAR_STREAK) {
                    wrongDao.delete(question.id)
                } else {
                    wrongDao.update(wrong.copy(rightStreak = streak))
                }
            }
        }
        return correct
    }

    override fun wrongIds(subject: Int): Flow<List<String>> =
        wrongDao.observeIds(subject).distinctUntilChanged()

    override suspend fun clearWrong(questionId: String) {
        wrongDao.delete(questionId)
    }

    override fun favoriteIds(subject: Int): Flow<Set<String>> =
        favoriteDao.observeIds(subject)
            .map { it.toSet() }
            .distinctUntilChanged()

    override suspend fun toggleFavorite(question: Question): Boolean {
        val now = System.currentTimeMillis()
        return db.withTransaction {
            if (favoriteDao.get(question.id) != null) {
                favoriteDao.delete(question.id)
                false
            } else {
                favoriteDao.upsert(FavoriteEntity(question.id, question.subject, now))
                true
            }
        }
    }

    override fun progress(scope: Scope): Flow<Progress> {
        // done/correct 以 user.db 作答统计为准,但限定在 quiz.db 的 Scope 集合内
        val scopeIds = flow { emit(quiz.ids(scope).toHashSet()) }
        val total = flow { emit(quiz.totalFor(scope)) }
        return combine(scopeIds, statDao.observeBySubject(scope.subject), total) { ids, statRows, totalCount ->
            var done = 0
            var correct = 0
            for (row in statRows) {
                if (row.questionId in ids) {
                    done++
                    if (row.lastCorrect) correct++
                }
            }
            Progress(total = totalCount, done = done, correct = correct)
        }.distinctUntilChanged()
    }

    override fun chapterStats(scope: Scope): Flow<List<ChapterStat>> {
        val chapters = flow { emit(quiz.chapters(scope).first()) }
        val membership = flow { emit(quiz.chapterIdMap(scope)) }
        return combine(chapters, membership, statDao.observeBySubject(scope.subject)) { chapterList, idToChapter, statRows ->
            // 每章计数器 [done, correct]
            val countersByChapter = HashMap<String, IntArray>()
            for (row in statRows) {
                val chapterId = idToChapter[row.questionId] ?: continue
                val counters = countersByChapter.getOrPut(chapterId) { IntArray(2) }
                counters[0]++
                if (row.lastCorrect) counters[1]++
            }
            val totalByChapter = idToChapter.values.groupingBy { it }.eachCount()
            chapterList.map { ch ->
                val counters = countersByChapter[ch.id]
                ChapterStat(
                    chapter = ch,
                    total = totalByChapter[ch.id] ?: 0,
                    done = counters?.get(0) ?: 0,
                    correct = counters?.get(1) ?: 0,
                )
            }
        }.distinctUntilChanged()
    }

    override suspend fun savePosition(key: String, index: Int) {
        positionDao.upsert(PositionEntity(key, index))
    }

    override suspend fun loadPosition(key: String): Int = positionDao.get(key) ?: 0

    override suspend fun saveExam(result: ExamResult): Long =
        examDao.insert(
            ExamEntity(
                subject = result.subject,
                startedAt = result.startedAt,
                durationSec = result.durationSec,
                score = result.score,
                passed = result.passed,
                questionIdsJson = Mappers.encodeStringList(result.questionIds),
                wrongIdsJson = Mappers.encodeStringList(result.wrongIds),
            ),
        )

    override fun exams(subject: Int): Flow<List<ExamResult>> =
        examDao.observeBySubject(subject)
            .map { list -> list.map(Mappers::examEntityToResult) }
            .distinctUntilChanged()

    override suspend fun exportBackup(out: OutputStream) {
        val envelope = BackupEnvelope(
            schema = BACKUP_SCHEMA,
            exported_at = java.time.Instant.now().toString(),
            answer_stat = statDao.getAll().map {
                AnswerStatDto(it.questionId, it.subject, it.times, it.correctTimes, it.lastCorrect, it.lastAt)
            },
            wrong = wrongDao.getAll().map {
                WrongDto(it.questionId, it.subject, it.addedAt, it.rightStreak)
            },
            favorite = favoriteDao.getAll().map {
                FavoriteDto(it.questionId, it.subject, it.addedAt)
            },
            exam = examDao.getAll().map {
                ExamDto(
                    id = it.id,
                    subject = it.subject,
                    started_at = it.startedAt,
                    duration_sec = it.durationSec,
                    score = it.score,
                    passed = it.passed,
                    question_ids = Mappers.decodeStringList(it.questionIdsJson),
                    wrong_ids = Mappers.decodeStringList(it.wrongIdsJson),
                )
            },
            position = positionDao.getAll().map { PositionDto(it.key, it.idx) },
        )
        val json = DataJson.json.encodeToString(BackupEnvelope.serializer(), envelope)
        out.write(json.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    override suspend fun importBackup(input: InputStream) {
        val raw = input.readBytes().toString(Charsets.UTF_8)
        val envelope = DataJson.json.decodeFromString(BackupEnvelope.serializer(), raw)
        db.withTransaction {
            for (row in envelope.answer_stat) {
                statDao.upsert(
                    AnswerStatEntity(row.question_id, row.subject, row.times, row.correct_times, row.last_correct, row.last_at),
                )
            }
            for (row in envelope.wrong) {
                wrongDao.upsert(WrongEntity(row.question_id, row.subject, row.added_at, row.right_streak))
            }
            for (row in envelope.favorite) {
                favoriteDao.upsert(FavoriteEntity(row.question_id, row.subject, row.added_at))
            }
            if (envelope.exam.isNotEmpty()) {
                examDao.upsertAll(
                    envelope.exam.map {
                        ExamEntity(
                            id = it.id,
                            subject = it.subject,
                            startedAt = it.started_at,
                            durationSec = it.duration_sec,
                            score = it.score,
                            passed = it.passed,
                            questionIdsJson = Mappers.encodeStringList(it.question_ids),
                            wrongIdsJson = Mappers.encodeStringList(it.wrong_ids),
                        )
                    },
                )
            }
            for (row in envelope.position) {
                positionDao.upsert(PositionEntity(row.key, row.idx))
            }
        }
    }

    private companion object {
        /** 错题连对达到该次数自动移出(TASK.md)。 */
        const val WRONG_CLEAR_STREAK = 3
    }
}
