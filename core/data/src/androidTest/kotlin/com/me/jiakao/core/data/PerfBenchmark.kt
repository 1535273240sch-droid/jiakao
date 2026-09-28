package com.me.jiakao.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.me.jiakao.core.data.db.QuizDatabase
import com.me.jiakao.core.model.PackRecord
import com.me.jiakao.core.model.Scope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 鎬ц兘鍩哄噯(鍚堝悓 搂7 纭寚鏍?鐪熸満/妯℃嫙鍣?:
 * - 鍏ㄩ噺瀵煎叆 3000 棰?鈮?3000 ms
 * - 绔犺妭 id 鏌ヨ 鈮?20 ms(10 娆″钩鍧?棰勭儹 3 娆?
 * - getQuestions(50 ids) 鈮?15 ms(10 娆″钩鍧?棰勭儹 3 娆?
 *
 * 浣跨敤纾佺洏鏂囦欢搴?闈炲唴瀛?,璐磋繎鐢熶骇璺緞;瀹炴祴鏁板瓧鍐欏叆 out/DONE.md銆? */
@RunWith(AndroidJUnit4::class)
class PerfBenchmark {

    private lateinit var db: QuizDatabase
    private lateinit var store: QuestionStoreImpl
    private lateinit var repo: QuizRepositoryImpl

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(BENCH_DB)
        db = Room.databaseBuilder(context, QuizDatabase::class.java, BENCH_DB).build()
        store = QuestionStoreImpl(db)
        repo = QuizRepositoryImpl(db)
    }

    @After
    fun tearDown() {
        db.close()
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(BENCH_DB)
    }

    @Test
    fun import3000QuestionsWithin3s() = runBlocking {
        val questions = BenchFixtures.bulkQuestions(3000)
        val t0 = System.nanoTime()
        store.applyPack(1, null, questions.map { PackRecord.Upsert(it) }.asSequence(), replaceAll = true)
        val importMs = (System.nanoTime() - t0) / 1_000_000
        println("[PERF] import(3000)=${importMs}ms")
        assertEquals(3000, repo.ids(Scope(1)).size)
        assertTrue("import took ${importMs}ms (target 鈮?000ms)", importMs <= 3_000)
    }

    @Test
    fun chapterIdQueryWithin20ms() = runBlocking {
        store.applyPack(1, null, BenchFixtures.bulkQuestions(3000).map { PackRecord.Upsert(it) }.asSequence(), replaceAll = true)
        val chapterId = "s1-c03"
        repeat(3) { repo.ids(Scope(1), chapterId) } // 棰勭儹
        var total = 0L
        repeat(10) {
            val t0 = System.nanoTime()
            repo.ids(Scope(1), chapterId)
            total += (System.nanoTime() - t0) / 1_000_000
        }
        val avg = total / 10.0
        println("[PERF] ids(chapter)avg=${avg}ms")
        assertTrue("ids avg took ${avg}ms (target 鈮?0ms)", avg <= 20.0)
    }

    @Test
    fun getQuestions50Within15ms() = runBlocking {
        store.applyPack(1, null, BenchFixtures.bulkQuestions(3000).map { PackRecord.Upsert(it) }.asSequence(), replaceAll = true)
        val sample = repo.ids(Scope(1)).take(50)
        assertEquals(50, sample.size)
        repeat(3) { repo.getQuestions(sample) } // 棰勭儹
        var total = 0L
        repeat(10) {
            val t0 = System.nanoTime()
            repo.getQuestions(sample)
            total += (System.nanoTime() - t0) / 1_000_000
        }
        val avg = total / 10.0
        println("[PERF] getQuestions(50)avg=${avg}ms")
        assertTrue("getQuestions avg took ${avg}ms (target 鈮?5ms)", avg <= 15.0)
    }

    private companion object {
        const val BENCH_DB = "perf-bench.db"
    }
}
