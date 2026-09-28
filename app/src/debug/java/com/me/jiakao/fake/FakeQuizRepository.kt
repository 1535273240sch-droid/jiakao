package com.me.jiakao.fake

import com.me.jiakao.core.model.BankCounts
import com.me.jiakao.core.model.Chapter
import com.me.jiakao.core.model.QFilter
import com.me.jiakao.core.model.QType
import com.me.jiakao.core.model.Question
import com.me.jiakao.core.model.QuizRepository
import com.me.jiakao.core.model.Scope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/** 假题库仓库:内存数据,支持车辆/章节/筛选与搜索 */
@Singleton
class FakeQuizRepository @Inject constructor() : QuizRepository {

    private val all = FakeBank.questions
    private val chapters = FakeBank.chapters

    private val _bankVersion = MutableStateFlow(11)
    override val bankVersion: Flow<Int> = _bankVersion.asStateFlow()

    /** 当前版本(供假更新器同步读取) */
    fun currentVersion(): Int = _bankVersion.value

    /** 更新成功后由 FakeBankUpdater 调用 */
    fun bumpVersion(to: Int) {
        _bankVersion.value = to
    }

    private fun matches(q: Question, scope: Scope): Boolean =
        q.subject == scope.subject && scope.vehicle in q.vehicles

    private fun matchesFilter(q: Question, filter: QFilter): Boolean = when (filter) {
        QFilter.All -> true
        is QFilter.OfType -> q.type == filter.type
        QFilter.HasMedia -> q.media.isNotEmpty()
        QFilter.HasAnim -> q.media.any { it.kind != com.me.jiakao.core.model.MediaKind.IMAGE }
        is QFilter.Tag -> filter.tag in q.tags
    }

    override fun chapters(scope: Scope): Flow<List<Chapter>> = flow {
        emit(chapters.filter { it.subject == scope.subject }.sortedBy { it.order })
    }

    override fun counts(scope: Scope): Flow<BankCounts> = flow {
        val scoped = all.filter { matches(it, scope) }
        emit(
            BankCounts(
                total = scoped.size,
                withMedia = scoped.count { it.media.isNotEmpty() },
                withAnim = scoped.count { it.media.any { m -> m.kind != com.me.jiakao.core.model.MediaKind.IMAGE } },
                byType = mapOf(
                    QType.JUDGE to scoped.count { it.type == QType.JUDGE },
                    QType.SINGLE to scoped.count { it.type == QType.SINGLE },
                    QType.MULTI to scoped.count { it.type == QType.MULTI },
                ),
            )
        )
    }

    override suspend fun ids(scope: Scope, chapterId: String?, filter: QFilter): List<String> =
        all.asSequence()
            .filter { matches(it, scope) }
            .filter { chapterId == null || it.chapterId == chapterId }
            .filter { matchesFilter(it, filter) }
            .sortedBy { it.id }
            .map { it.id }
            .toList()

    override suspend fun getQuestions(ids: List<String>): List<Question> =
        ids.mapNotNull { FakeBank.byId[it] }

    override suspend fun search(scope: Scope, keyword: String, limit: Int): List<Question> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return emptyList()
        return all.asSequence()
            .filter { matches(it, scope) }
            .filter { q ->
                q.stem.contains(kw) ||
                    q.explain.contains(kw) ||
                    q.tags.any { it.contains(kw) } ||
                    q.options.any { it.text.contains(kw) }
            }
            .take(limit)
            .toList()
    }
}
