package com.me.jiakao.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface AnswerStatDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stat: AnswerStatEntity)

    @Query("SELECT * FROM answer_stat WHERE question_id = :questionId")
    suspend fun get(questionId: String): AnswerStatEntity?

    @Query("SELECT * FROM answer_stat WHERE subject = :subject")
    suspend fun getBySubject(subject: Int): List<AnswerStatEntity>

    @Query("SELECT * FROM answer_stat WHERE subject = :subject")
    fun observeBySubject(subject: Int): Flow<List<AnswerStatEntity>>

    @Query("SELECT * FROM answer_stat")
    suspend fun getAll(): List<AnswerStatEntity>

    @Query("SELECT COUNT(*) FROM answer_stat")
    suspend fun countAll(): Int

    @Query("DELETE FROM answer_stat")
    suspend fun clear()
}

@Dao
internal interface WrongDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(wrong: WrongEntity)

    @Update
    suspend fun update(wrong: WrongEntity)

    @Query("SELECT * FROM wrong WHERE question_id = :questionId")
    suspend fun get(questionId: String): WrongEntity?

    @Query("SELECT question_id FROM wrong WHERE subject = :subject ORDER BY added_at, question_id")
    fun observeIds(subject: Int): Flow<List<String>>

    @Query("SELECT * FROM wrong")
    suspend fun getAll(): List<WrongEntity>

    @Query("DELETE FROM wrong WHERE question_id = :questionId")
    suspend fun delete(questionId: String)

    @Query("DELETE FROM wrong")
    suspend fun clear()
}

@Dao
internal interface FavoriteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favorite: FavoriteEntity)

    @Query("SELECT * FROM favorite WHERE question_id = :questionId")
    suspend fun get(questionId: String): FavoriteEntity?

    @Query("SELECT question_id FROM favorite WHERE subject = :subject ORDER BY added_at, question_id")
    fun observeIds(subject: Int): Flow<List<String>>

    @Query("SELECT * FROM favorite")
    suspend fun getAll(): List<FavoriteEntity>

    @Query("DELETE FROM favorite WHERE question_id = :questionId")
    suspend fun delete(questionId: String)

    @Query("DELETE FROM favorite")
    suspend fun clear()
}

@Dao
internal interface ExamDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(exams: List<ExamEntity>)

    @Insert
    suspend fun insert(exam: ExamEntity): Long

    @Query("SELECT * FROM exam WHERE subject = :subject ORDER BY started_at DESC, id DESC")
    fun observeBySubject(subject: Int): Flow<List<ExamEntity>>

    @Query("SELECT * FROM exam")
    suspend fun getAll(): List<ExamEntity>

    @Query("SELECT COUNT(*) FROM exam")
    suspend fun countAll(): Int

    @Query("DELETE FROM exam")
    suspend fun clear()
}

@Dao
internal interface PositionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(position: PositionEntity)

    @Query("SELECT idx FROM position WHERE `key` = :key")
    suspend fun get(key: String): Int?

    @Query("SELECT * FROM position")
    suspend fun getAll(): List<PositionEntity>

    @Query("DELETE FROM position")
    suspend fun clear()
}
