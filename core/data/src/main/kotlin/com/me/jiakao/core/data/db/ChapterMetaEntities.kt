package com.me.jiakao.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 章节表(quiz.db)。`order` 是 SQL 关键字,列名用 sort_order。 */
@Entity(tableName = "chapter", indices = [Index("subject")])
internal data class ChapterEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "subject") val subject: Int,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)

/** 键值表(quiz.db),存 bank_version 等元信息。列名 key 由 Room 自动加引号。 */
@Entity(tableName = "meta")
internal data class MetaEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
) {
    companion object {
        const val KEY_BANK_VERSION = "bank_version"
    }
}
