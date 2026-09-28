package com.me.jiakao.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 作答统计(user.db)。last_correct = 最近一次作答是否正确。 */
@Entity(tableName = "answer_stat", indices = [Index("subject")])
internal data class AnswerStatEntity(
    @PrimaryKey @ColumnInfo(name = "question_id") val questionId: String,
    @ColumnInfo(name = "subject") val subject: Int,
    @ColumnInfo(name = "times") val times: Int,
    @ColumnInfo(name = "correct_times") val correctTimes: Int,
    @ColumnInfo(name = "last_correct") val lastCorrect: Boolean,
    @ColumnInfo(name = "last_at") val lastAt: Long,
)

/** 错题本(user.db)。right_streak 达到 WRONG_CLEAR_STREAK 自动移出。 */
@Entity(tableName = "wrong", indices = [Index("subject")])
internal data class WrongEntity(
    @PrimaryKey @ColumnInfo(name = "question_id") val questionId: String,
    @ColumnInfo(name = "subject") val subject: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "right_streak") val rightStreak: Int,
)

/** 收藏(user.db)。 */
@Entity(tableName = "favorite", indices = [Index("subject")])
internal data class FavoriteEntity(
    @PrimaryKey @ColumnInfo(name = "question_id") val questionId: String,
    @ColumnInfo(name = "subject") val subject: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/** 考试记录(user.db)。question/wrong id 列表以 JSON 数组存储。 */
@Entity(tableName = "exam", indices = [Index("subject")])
internal data class ExamEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "subject") val subject: Int,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "duration_sec") val durationSec: Int,
    @ColumnInfo(name = "score") val score: Int,
    @ColumnInfo(name = "passed") val passed: Boolean,
    @ColumnInfo(name = "question_ids_json") val questionIdsJson: String,
    @ColumnInfo(name = "wrong_ids_json") val wrongIdsJson: String,
)

/** 练习进度(user.db)。key 由调用方自定义,如 "s1-c03" 或 "wrong" 等。 */
@Entity(tableName = "position")
internal data class PositionEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "idx") val idx: Int,
)
