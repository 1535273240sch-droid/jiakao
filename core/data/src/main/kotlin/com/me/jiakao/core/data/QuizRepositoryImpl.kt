package com.me.jiakao.core.data

import com.me.jiakao.core.data.db.CountRow
import com.me.jiakao.core.data.db.MetaEntity
import com.me.jiakao.core.data.db.QuizDatabase
import com.me.jiakao.core.data.db.TypeCount
import com.me.jiakao.core.model.BankCounts
import com.me.jiakao.core.model.Chapter
import com.me.jiakao.core.model.QFilter
import com.me.jiakao.core.model.Question
import com.me.jiakao.core.model.QuizRepository
import com.me.jiakao.core.model.Scope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [QuizRepository] 实现。
 *
 * 约定:vehicles 为空("||")的题目视为适用于所有车型(最小假设,见 DONE.md);
 * 列表查询均走 (subject, chapter_id, sort_key) 索引并按 sort_key 稳定排序。
 */
@Singleton
internal class QuizRepositoryImpl @Inject constructor(
    private val db: QuizDatabase,
) : QuizRepository {

    private val dao get() = db.questionDao()

    override fun chapters(scope: Scope): Flow<List<Chapter>> =
        db.chapterDao().observeBySubject(scope.subject)
            .map { list -> list.map(Mappers::entityToChapter) }
            .distinctUntilChanged()

    override fun counts(scope: Scope): Flow<BankCounts> =
        combine(
            dao.observeCountBasics(scope.subject, scope.vehiclePattern()),
            dao.observeCountByType(scope.subject, scope.vehiclePattern()),
        ) { basics: CountRow, byType: List<TypeCount> ->
            BankCounts(
                total = basics.total,
                withMedia = basics.withMedia,
                withAnim = basics.withAnim,
                byType = byType.associate { it.type.toQType() to it.cnt },
            )
        }.distinctUntilChanged()

    override suspend fun ids(scope: Scope, chapterId: String?, filter: QFilter): List<String> {
        val (filterType, typeArg, tagPattern) = when (filter) {
            QFilter.All -> Triple(0, null, null)
            is QFilter.OfType -> Triple(1, filter.type.toStorage(), null)
            QFilter.HasMedia -> Triple(2, null, null)
            QFilter.HasAnim -> Triple(3, null, null)
            is QFilter.Tag -> Triple(4, null, "%${filter.tag.escapeLike()}%")
        }
        return dao.ids(
            subject = scope.subject,
            vehiclePattern = scope.vehiclePattern(),
            chapterId = chapterId,
            filterType = filterType,
            typeArg = typeArg,
            tagPattern = tagPattern,
        )
    }

    override suspend fun getQuestions(ids: List<String>): List<Question> {
        if (ids.isEmpty()) return emptyList()
        val byId = HashMap<String, Question>(ids.size * 2)
        // 500 一批,规避 SQLite 变量上限;单查询避免 N+1
        for (chunk in ids.chunked(BATCH)) {
            for (e in dao.getByIds(chunk)) {
                byId[e.id] = Mappers.entityToQuestion(e)
            }
        }
        return ids.mapNotNull(byId::get)
    }

    override suspend fun search(scope: Scope, keyword: String, limit: Int): List<Question> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return emptyList()
        val pattern = scope.vehiclePattern()
        // ASCII 词元 → FTS4 前缀匹配(走倒排索引);含 CJK → LIKE 子串匹配
        // (FTS4 simple 分词器不切中文,中文走 LIKE 才有结果,见 DONE.md)
        val tokens = kw.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val rows = if (tokens.all { it.isAsciiWord() }) {
            val match = tokens.joinToString(" ") { "${it.replace("\"", "")}*" }
            dao.searchFts(scope.subject, pattern, match, limit)
        } else {
            dao.searchLike(scope.subject, pattern, kw.escapeLike(), limit)
        }
        return rows.map(Mappers::entityToQuestion)
    }

    override val bankVersion: Flow<Int> =
        db.metaDao().observe(MetaEntity.KEY_BANK_VERSION)
            .map { it?.toIntOrNull() ?: 0 }
            .distinctUntilChanged()

    /** Scope 内 id → chapter_id 映射(单查询;chapterStats 跨库归并用)。 */
    internal suspend fun chapterIdMap(scope: Scope): Map<String, String> =
        dao.idChapterPairs(scope.subject, scope.vehiclePattern()).associate { it.id to it.chapterId }

    /** Scope 内题目总数(单查询;progress 跨库归并用)。 */
    internal suspend fun totalFor(scope: Scope): Int =
        dao.countBasics(scope.subject, scope.vehiclePattern()).total

    private fun String.isAsciiWord(): Boolean = isNotEmpty() && all { it.code in 0x21..0x7e }

    private companion object {
        const val BATCH = 500
    }
}

/** Scope → SQL LIKE 模式:"%|car|%"。 */
internal fun Scope.vehiclePattern(): String = "%|$vehicle|%"
