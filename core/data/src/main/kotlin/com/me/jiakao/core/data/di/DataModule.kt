package com.me.jiakao.core.data.di

import android.content.Context
import androidx.room.Room
import com.me.jiakao.core.data.UserRepositoryImpl
import com.me.jiakao.core.data.db.QuizDatabase
import com.me.jiakao.core.data.db.UserDatabase
import com.me.jiakao.core.data.db.UserDbMigrations
import com.me.jiakao.core.model.ExamService
import com.me.jiakao.core.model.QuestionStore
import com.me.jiakao.core.model.QuizRepository
import com.me.jiakao.core.model.UserRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 合同 §6:提供 QuizRepository / UserRepository / ExamService / QuestionStore 四个绑定
 * 以及 quiz.db / user.db 两个 Room 单例。
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class DataModuleBinds {

    @Binds
    @Singleton
    internal abstract fun bindQuestionStore(impl: com.me.jiakao.core.data.QuestionStoreImpl): QuestionStore

    @Binds
    @Singleton
    internal abstract fun bindQuizRepository(impl: com.me.jiakao.core.data.QuizRepositoryImpl): QuizRepository

    @Binds
    @Singleton
    internal abstract fun bindUserRepository(impl: UserRepositoryImpl): UserRepository

    @Binds
    @Singleton
    internal abstract fun bindExamService(impl: com.me.jiakao.core.data.ExamServiceImpl): ExamService
}

@Module
@InstallIn(SingletonComponent::class)
internal object DataModuleProviders {

    /** quiz.db:可整体重建(合同 §1),schema 升级允许破坏性迁移,随后由题库包重新导入。 */
    @Provides
    @Singleton
    fun quizDatabase(@ApplicationContext context: Context): QuizDatabase =
        Room.databaseBuilder(context, QuizDatabase::class.java, QuizDatabase.NAME)
            .fallbackToDestructiveMigration()
            .build()

    /**
     * user.db:**绝不破坏性重建**(合同 §1)。schema 升级必须在
     * [UserDbMigrations.ALL] 登记正式 Migration;当前 v1 起步,数组为空。
     */
    @Provides
    @Singleton
    fun userDatabase(@ApplicationContext context: Context): UserDatabase =
        Room.databaseBuilder(context, UserDatabase::class.java, UserDatabase.NAME)
            .addMigrations(*UserDbMigrations.ALL)
            .build()
}
