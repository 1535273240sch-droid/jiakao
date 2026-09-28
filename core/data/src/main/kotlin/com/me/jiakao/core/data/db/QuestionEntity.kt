package com.me.jiakao.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 题表(quiz.db)。列结构 = TASK.md 规定;`options_text` 为 FTS4 内容同步所需的派生列
 * (全部选项文本拼接,见 out/CONTRACT_ISSUES.md #4)。
 */
@Entity(
    tableName = "question",
    indices = [
        Index(value = ["subject", "chapter_id", "sort_key"]),
        Index(value = ["subject", "has_media"]),
        Index(value = ["subject", "has_anim"]),
    ],
)
internal data class QuestionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "subject") val subject: Int,
    /** judge|single|multi(合同 §2 原词) */
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "chapter_id") val chapterId: String,
    /** 排序键:取题目 id 的 6 位序号(合同 §2,稳定不复用),保证稳定顺序 */
    @ColumnInfo(name = "sort_key") val sortKey: Int,
    @ColumnInfo(name = "has_media") val hasMedia: Boolean,
    /** 动图或视频 */
    @ColumnInfo(name = "has_anim") val hasAnim: Boolean,
    /** "|car|truck|" 形式;空列表存 "||" */
    @ColumnInfo(name = "vehicles") val vehicles: String,
    /** "|标志|禁令|" 形式 */
    @ColumnInfo(name = "tags") val tags: String,
    @ColumnInfo(name = "stem") val stem: String,
    @ColumnInfo(name = "options_json") val optionsJson: String,
    @ColumnInfo(name = "options_text") val optionsText: String,
    @ColumnInfo(name = "answer_csv") val answerCsv: String,
    @ColumnInfo(name = "explain") val explain: String,
    @ColumnInfo(name = "media_json") val mediaJson: String,
    @ColumnInfo(name = "rev") val rev: Int,
)

/** FTS4 虚拟表,外部内容表为 [QuestionEntity],Room 生成触发器自动同步,无需手工维护。 */
@Fts4(contentEntity = QuestionEntity::class)
@Entity(tableName = "question_fts")
internal data class QuestionFtsEntity(
    val stem: String,
    @ColumnInfo(name = "options_text") val optionsText: String,
)
