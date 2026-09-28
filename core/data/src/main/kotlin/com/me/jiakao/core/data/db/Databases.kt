package com.me.jiakao.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * 题库库 `quiz.db`。
 *
 * 可整体重建(合同 §1):schema 升级允许 `fallbackToDestructiveMigration`,
 * 数据随时可通过题库包重新导入。
 */
@Database(
    entities = [QuestionEntity::class, QuestionFtsEntity::class, ChapterEntity::class, MetaEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class QuizDatabase : RoomDatabase() {
    abstract fun questionDao(): QuestionDao
    abstract fun chapterDao(): ChapterDao
    abstract fun metaDao(): MetaDao

    companion object {
        const val NAME = "quiz.db"
    }
}

/**
 * 用户库 `user.db`。
 *
 * **绝不允许破坏性变更**(合同 §1:更新题库绝不触碰):schema 升级必须在
 * [UserDbMigrations.ALL] 中登记正式 Migration,禁止配置 fallbackToDestructiveMigration。
 */
@Database(
    entities = [AnswerStatEntity::class, WrongEntity::class, FavoriteEntity::class, ExamEntity::class, PositionEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class UserDatabase : RoomDatabase() {
    abstract fun answerStatDao(): AnswerStatDao
    abstract fun wrongDao(): WrongDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun examDao(): ExamDao
    abstract fun positionDao(): PositionDao

    companion object {
        const val NAME = "user.db"
    }
}

/**
 * `user.db` 正式 Migration 登记(v1 起步,暂无历史版本)。
 *
 * 今后每次升 schema version,必须在此追加 `object : Migration(old, new) { ... }`
 * 并把新实例加入 [ALL];拼装时由 :core:update / :app 的 Room builder 通过
 * `addMigrations(*UserDbMigrations.ALL)` 装配。
 */
object UserDbMigrations {
    /** 全部已登记的 user.db 迁移;当前 v1,数组为空。 */
    val ALL: Array<Migration> = emptyArray()
}
