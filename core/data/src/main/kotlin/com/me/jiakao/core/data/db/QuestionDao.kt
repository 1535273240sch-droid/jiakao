package com.me.jiakao.core.data.db

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 题表 DAO。所有列表查询按 sort_key(题目 id 序号)稳定排序。 */
@Dao
internal interface QuestionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(questions: List<QuestionEntity>)

    @Query("DELETE FROM question WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM question")
    suspend fun clear()

    @Query("DELETE FROM question_fts")
    suspend fun clearFts()

    @Query("SELECT * FROM question WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<QuestionEntity>

    @Query("SELECT media_json FROM question")
    suspend fun getAllMediaJson(): List<String>

    // ── ids:按 Scope + 章节 + 过滤器组合查询(id 列表,稳定顺序)──
    // :vehiclePattern 为 "%|car|%" 形式;vehicles 为 "||"(空)的题目视为适用于所有车型。
    // filterType: 0=All 1=OfType 2=HasMedia 3=HasAnim 4=Tag

    @Query(
        """
        SELECT id FROM question
        WHERE subject = :subject
          AND (vehicles = '||' OR vehicles LIKE :vehiclePattern)
          AND (:chapterId IS NULL OR chapter_id = :chapterId)
          AND (
            :filterType = 0
            OR (:filterType = 1 AND type = :typeArg)
            OR (:filterType = 2 AND has_media = 1)
            OR (:filterType = 3 AND has_anim = 1)
            OR (:filterType = 4 AND tags LIKE :tagPattern)
          )
        ORDER BY sort_key, id
        """,
    )
    suspend fun ids(
        subject: Int,
        vehiclePattern: String,
        chapterId: String?,
        filterType: Int,
        typeArg: String?,
        tagPattern: String?,
    ): List<String>

    @Query(
        """
        SELECT id, chapter_id FROM question
        WHERE subject = :subject
          AND (vehicles = '||' OR vehicles LIKE :vehiclePattern)
        """,
    )
    suspend fun idChapterPairs(subject: Int, vehiclePattern: String): List<IdChapter>

    @Query(
        """
        SELECT IFNULL(COUNT(*), 0) AS total,
               IFNULL(SUM(has_media), 0) AS withMedia,
               IFNULL(SUM(has_anim), 0) AS withAnim
        FROM question
        WHERE subject = :subject
          AND (vehicles = '||' OR vehicles LIKE :vehiclePattern)
        """,
    )
    suspend fun countBasics(subject: Int, vehiclePattern: String): CountRow

    @Query(
        """
        SELECT IFNULL(COUNT(*), 0) AS total,
               IFNULL(SUM(has_media), 0) AS withMedia,
               IFNULL(SUM(has_anim), 0) AS withAnim
        FROM question
        WHERE subject = :subject
          AND (vehicles = '||' OR vehicles LIKE :vehiclePattern)
        """,
    )
    fun observeCountBasics(subject: Int, vehiclePattern: String): Flow<CountRow>

    @Query(
        """
        SELECT type, COUNT(*) AS cnt FROM question
        WHERE subject = :subject
          AND (vehicles = '||' OR vehicles LIKE :vehiclePattern)
        GROUP BY type
        """,
    )
    suspend fun countByType(subject: Int, vehiclePattern: String): List<TypeCount>

    @Query(
        """
        SELECT type, COUNT(*) AS cnt FROM question
        WHERE subject = :subject
          AND (vehicles = '||' OR vehicles LIKE :vehiclePattern)
        GROUP BY type
        """,
    )
    fun observeCountByType(subject: Int, vehiclePattern: String): Flow<List<TypeCount>>

    @Query(
        """
        SELECT q.* FROM question_fts
        JOIN question q ON q.rowid = question_fts.rowid
        WHERE question_fts MATCH :ftsMatch
          AND q.subject = :subject
          AND (q.vehicles = '||' OR q.vehicles LIKE :vehiclePattern)
        ORDER BY q.sort_key, q.id
        LIMIT :limit
        """,
    )
    suspend fun searchFts(subject: Int, vehiclePattern: String, ftsMatch: String, limit: Int): List<QuestionEntity>

    @Query(
        """
        SELECT * FROM question
        WHERE subject = :subject
          AND (vehicles = '||' OR vehicles LIKE :vehiclePattern)
          AND (stem LIKE '%' || :keyword || '%' ESCAPE '\'
               OR options_text LIKE '%' || :keyword || '%' ESCAPE '\')
        ORDER BY sort_key, id
        LIMIT :limit
        """,
    )
    suspend fun searchLike(subject: Int, vehiclePattern: String, keyword: String, limit: Int): List<QuestionEntity>
}

/** countBasics 查询结果行。 */
internal data class CountRow(
    @ColumnInfo(name = "total") val total: Int,
    @ColumnInfo(name = "withMedia") val withMedia: Int,
    @ColumnInfo(name = "withAnim") val withAnim: Int,
)

/** countByType 查询结果行。 */
internal data class TypeCount(
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "cnt") val cnt: Int,
)

/** idChapterPairs 查询结果行。 */
internal data class IdChapter(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "chapter_id") val chapterId: String,
)
