package com.me.jiakao.fake

import com.me.jiakao.core.model.QFilter
import com.me.jiakao.core.model.QType
import com.me.jiakao.core.model.Scope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 假题库仓库的筛选/搜索/计数语义 */
class FakeQuizRepositoryTest {

    private val repo = FakeQuizRepository()

    @Test
    fun `题库不少于200题且含图含动图`() {
        assertTrue("题目应≥200,实际 ${FakeBank.questions.size}", FakeBank.questions.size >= 200)
        assertTrue(FakeBank.questions.any { it.media.isNotEmpty() })
        assertTrue(FakeBank.questions.any { it.media.any { m -> m.kind != com.me.jiakao.core.model.MediaKind.IMAGE } })
    }

    @Test
    fun `ids 按车型过滤`() = runTest {
        val car = repo.ids(Scope(1, "car"))
        val truck = repo.ids(Scope(1, "truck"))
        assertTrue(car.isNotEmpty())
        assertTrue(truck.isNotEmpty())
        assertTrue("car 题量应多于 truck", car.size > truck.size)
    }

    @Test
    fun `ids 支持题型与媒体筛选`() = runTest {
        val scope = Scope(1, "car")
        val judges = repo.ids(scope, filter = QFilter.OfType(QType.JUDGE))
        val withMedia = repo.ids(scope, filter = QFilter.HasMedia)
        val withAnim = repo.ids(scope, filter = QFilter.HasAnim)
        val questions = repo.getQuestions(judges)
        assertTrue(questions.all { it.type == QType.JUDGE })
        assertTrue(repo.getQuestions(withMedia).all { it.media.isNotEmpty() })
        assertTrue(repo.getQuestions(withAnim).all { it.media.any { m -> m.kind != com.me.jiakao.core.model.MediaKind.IMAGE } })
    }

    @Test
    fun `getQuestions 保持入参顺序`() = runTest {
        val scope = Scope(1, "car")
        val ids = repo.ids(scope).take(5).reversed()
        val questions = repo.getQuestions(ids)
        assertEquals(ids, questions.map { it.id })
    }

    @Test
    fun `search 匹配题干与标签`() = runTest {
        val hits = repo.search(Scope(1, "car"), "实习期")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.subject == 1 })
    }

    @Test
    fun `counts 与 ids 一致`() = runTest {
        val scope = Scope(4, "car")
        val counts = repo.counts(scope).first()
        val ids = repo.ids(scope)
        assertEquals(ids.size, counts.total)
        assertTrue(counts.withMedia > 0)
    }
}
