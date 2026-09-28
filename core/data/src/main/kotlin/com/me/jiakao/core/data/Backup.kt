package com.me.jiakao.core.data

import kotlinx.serialization.Serializable

/**
 * 用户数据备份文件(user.db 导出/导入,JSON)。
 *
 * 结构:`{schema, exported_at, answer_stat[], wrong[], favorite[], exam[], position[]}`。
 * 导入为合并语义(不清空现有数据,同主键覆盖)。
 */
@Serializable
internal data class BackupEnvelope(
    val schema: Int,
    /** ISO-8601 UTC 时间串 */
    val exported_at: String,
    val answer_stat: List<AnswerStatDto> = emptyList(),
    val wrong: List<WrongDto> = emptyList(),
    val favorite: List<FavoriteDto> = emptyList(),
    val exam: List<ExamDto> = emptyList(),
    val position: List<PositionDto> = emptyList(),
)

@Serializable
internal data class AnswerStatDto(
    val question_id: String,
    val subject: Int,
    val times: Int,
    val correct_times: Int,
    val last_correct: Boolean,
    val last_at: Long,
)

@Serializable
internal data class WrongDto(
    val question_id: String,
    val subject: Int,
    val added_at: Long,
    val right_streak: Int,
)

@Serializable
internal data class FavoriteDto(
    val question_id: String,
    val subject: Int,
    val added_at: Long,
)

@Serializable
internal data class ExamDto(
    val id: Long,
    val subject: Int,
    val started_at: Long,
    val duration_sec: Int,
    val score: Int,
    val passed: Boolean,
    val question_ids: List<String>,
    val wrong_ids: List<String>,
)

@Serializable
internal data class PositionDto(
    val key: String,
    val idx: Int,
)

internal const val BACKUP_SCHEMA = 1
