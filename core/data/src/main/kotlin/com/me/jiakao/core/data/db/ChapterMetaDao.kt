package com.me.jiakao.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ChapterDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapter")
    suspend fun clear()

    @Query("SELECT * FROM chapter WHERE subject = :subject ORDER BY sort_order, id")
    suspend fun getBySubject(subject: Int): List<ChapterEntity>

    @Query("SELECT * FROM chapter WHERE subject = :subject ORDER BY sort_order, id")
    fun observeBySubject(subject: Int): Flow<List<ChapterEntity>>
}

@Dao
internal interface MetaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: MetaEntity)

    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM meta WHERE `key` = :key")
    fun observe(key: String): Flow<String?>
}
