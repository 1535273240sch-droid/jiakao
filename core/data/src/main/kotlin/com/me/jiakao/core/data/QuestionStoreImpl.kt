package com.me.jiakao.core.data

import androidx.room.withTransaction
import com.me.jiakao.core.data.db.ChapterDao
import com.me.jiakao.core.data.db.MetaDao
import com.me.jiakao.core.data.db.MetaEntity
import com.me.jiakao.core.data.db.QuestionDao
import com.me.jiakao.core.data.db.QuestionEntity
import com.me.jiakao.core.data.db.QuizDatabase
import com.me.jiakao.core.model.Chapter
import com.me.jiakao.core.model.MediaRef
import com.me.jiakao.core.model.PackRecord
import com.me.jiakao.core.model.QuestionStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.builtins.ListSerializer

/**
 * [QuestionStore] 实现:题库包导入(单事务、500 条批量、FTS 由 Room 内容同步触发器维护)。
 *
 * - `replaceAll=true` 先清空题表与 FTS(整体重建语义,quiz.db 可破坏)。
 * - `chapters` 非空时整体替换章节表。
 * - 全部写入成功后才提交 `bank_version`;任何异常整体回滚。
 */
@Singleton
internal class QuestionStoreImpl @Inject constructor(
    private val db: QuizDatabase,
) : QuestionStore {

    private val questionDao: QuestionDao get() = db.questionDao()
    private val chapterDao: ChapterDao get() = db.chapterDao()
    private val metaDao: MetaDao get() = db.metaDao()

    override suspend fun bankVersion(): Int =
        metaDao.get(MetaEntity.KEY_BANK_VERSION)?.toIntOrNull() ?: 0

    override suspend fun applyPack(
        newVersion: Int,
        chapters: List<Chapter>?,
        records: Sequence<PackRecord>,
        replaceAll: Boolean,
    ) {
        db.withTransaction {
            if (replaceAll) {
                questionDao.clear()
                questionDao.clearFts()
            }
            if (chapters != null) {
                chapterDao.clear()
                if (chapters.isNotEmpty()) chapterDao.upsertAll(chapters.map(Mappers::chapterToEntity))
            }

            // 惰性序列按 500 条批量写入;先删后插的顺序通过"类型变化即冲刷"保证
            var pendingUpserts: ArrayList<QuestionEntity>? = null
            var pendingDeletes: ArrayList<String>? = null
            var fallbackIndex = 0
            for (record in records) {
                when (record) {
                    is PackRecord.Upsert -> {
                        pendingDeletes?.takeIf { it.isNotEmpty() }?.let {
                            questionDao.deleteByIds(it)
                            pendingDeletes = null
                        }
                        val buffer = pendingUpserts
                            ?: ArrayList<QuestionEntity>(BATCH_UPSERT).also { pendingUpserts = it }
                        // id 序号作为排序键;无法解析时退化为包内行号
                        val sortKey = idSortKey(record.question.id) ?: fallbackIndex
                        buffer.add(Mappers.questionToEntity(record.question, sortKey))
                        if (buffer.size >= BATCH_UPSERT) {
                            questionDao.upsertAll(buffer)
                            pendingUpserts = null
                        }
                    }
                    is PackRecord.Delete -> {
                        pendingUpserts?.takeIf { it.isNotEmpty() }?.let {
                            questionDao.upsertAll(it)
                            pendingUpserts = null
                        }
                        val buffer = pendingDeletes
                            ?: ArrayList<String>(BATCH_DELETE).also { pendingDeletes = it }
                        buffer.add(record.id)
                        if (buffer.size >= BATCH_DELETE) {
                            questionDao.deleteByIds(buffer)
                            pendingDeletes = null
                        }
                    }
                }
                fallbackIndex++
            }
            pendingUpserts?.takeIf { it.isNotEmpty() }?.let { questionDao.upsertAll(it) }
            pendingDeletes?.takeIf { it.isNotEmpty() }?.let { questionDao.deleteByIds(it) }

            metaDao.upsert(MetaEntity(MetaEntity.KEY_BANK_VERSION, newVersion.toString()))
        }
    }

    override suspend fun allMediaRefs(): List<MediaRef> {
        val serializer = ListSerializer(MediaRefDto.serializer())
        val json = DataJson.json
        return questionDao.getAllMediaJson()
            .flatMap { raw -> json.decodeFromString(serializer, raw) }
            .map { it.toModel() }
            .distinctBy { it.sha256 }
    }

    private companion object {
        const val BATCH_UPSERT = 500
        const val BATCH_DELETE = 500
    }
}
