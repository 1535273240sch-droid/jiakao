package com.me.jiakao.core.data

import com.me.jiakao.core.model.PackRecord
import com.me.jiakao.core.model.Scope
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric 桌面 JVM 参考基准(信息性,不做硬断言)。
 *
 * 真实性能验收以 core/data/src/androidTest 的 PerfBenchmark 为准(真机/模拟器)。
 * 此处数字仅用于在无设备环境下的量级参考,实测值写入 out/DONE.md。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RobolectricPerfTest {

    private val questionCount = 3000

    @Test
    fun benchmark() = runBlocking {
        val db = Fixtures.quizDb()
        val store = QuestionStoreImpl(db)
        val repo = QuizRepositoryImpl(db)
        val questions = Fixtures.bulkQuestions(questionCount)

        // 1) 3000 题全量导入
        var t0 = System.nanoTime()
        store.applyPack(1, null, questions.map { PackRecord.Upsert(it) }.asSequence(), replaceAll = true)
        val importMs = (System.nanoTime() - t0) / 1_000_000

        // 2) 章节 id 查询(预热 3 次后取 10 次平均)
        val chapterId = "s1-c03"
        repeat(3) { repo.ids(Scope(1), chapterId) }
        var idsTotal = 0L
        repeat(10) {
            t0 = System.nanoTime()
            repo.ids(Scope(1), chapterId)
            idsTotal += (System.nanoTime() - t0) / 1_000_000
        }
        val idsMs = idsTotal / 10.0

        // 3) 读取 50 题
        val sample = repo.ids(Scope(1)).take(50)
        repeat(3) { repo.getQuestions(sample) }
        var getTotal = 0L
        repeat(10) {
            t0 = System.nanoTime()
            repo.getQuestions(sample)
            getTotal += (System.nanoTime() - t0) / 1_000_000
        }
        val getMs = getTotal / 10.0

        println("[PERF-Robolectric] import($questionCount)=${importMs}ms  ids(chapter)avg=${idsMs}ms  getQuestions(50)avg=${getMs}ms")
    }
}
