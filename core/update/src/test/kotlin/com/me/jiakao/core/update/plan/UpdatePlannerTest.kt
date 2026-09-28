package com.me.jiakao.core.update.plan

import com.me.jiakao.core.update.Fixtures
import com.me.jiakao.core.update.pack.DeltaRef
import com.me.jiakao.core.update.pack.FileRef
import com.me.jiakao.core.update.pack.Manifest
import com.me.jiakao.core.update.pack.PackCodec
import com.me.jiakao.core.update.pack.PackFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 决策纯函数的单元测试：合同 §3「更新语义」的逐条落地。 */
class UpdatePlannerTest {

    private val manifestV2: Manifest = PackCodec.parseManifest(Fixtures.text("manifest.json"))

    @Test
    fun `本地与远端版本相同则已最新`() {
        assertEquals(Plan.UpToDate(2), UpdatePlanner.plan(2, manifestV2))
    }

    @Test
    fun `本地版本高于远端不降级`() {
        assertEquals(Plan.UpToDate(7), UpdatePlanner.plan(7, manifestV2))
    }

    @Test
    fun `本地无数据必须全量`() {
        val plan = UpdatePlanner.plan(0, manifestV2)
        assertTrue(plan.toString(), plan is Plan.Full)
        val full = plan as Plan.Full
        assertEquals(2, full.version)
        assertEquals("full/bank-v2.jsonl.gz", full.file.url)
        assertEquals(manifestV2.full!!.bytes, full.downloadBytes)
    }

    @Test
    fun `连续增量链且比全量小则走增量`() {
        val plan = UpdatePlanner.plan(1, manifestV2)
        assertTrue(plan.toString(), plan is Plan.Deltas)
        val deltas = plan as Plan.Deltas
        assertEquals(1, deltas.from)
        assertEquals(2, deltas.to)
        assertEquals(1, deltas.steps.size)
        assertEquals(manifestV2.deltas.single().bytes, deltas.downloadBytes)
        assertTrue("增量必须比全量小", deltas.downloadBytes < manifestV2.full!!.bytes)
    }

    @Test
    fun `增量总量不小于全量则退回全量`() {
        val manifest = PackCodec.parseManifest(Fixtures.text("manifest-delta-heavy.json"))
        val plan = UpdatePlanner.plan(1, manifest)
        assertTrue(plan.toString(), plan is Plan.Full)
    }

    @Test
    fun `增量链不连续则退回全量`() {
        // 夹具：bank_version=5，只有 3→4、4→5 两段，本地是 1，链不连续
        val manifest = PackCodec.parseManifest(Fixtures.text("manifest-chain-broken.json"))
        val plan = UpdatePlanner.plan(1, manifest)
        assertTrue(plan.toString(), plan is Plan.Full)
        assertEquals(5, (plan as Plan.Full).version)
    }

    @Test
    fun `既无全量也无连续增量链则 Unavailable`() {
        val manifest = PackCodec.parseManifest(Fixtures.text("manifest-nofull.json"))
        val plan = UpdatePlanner.plan(1, manifest)
        assertTrue(plan.toString(), plan is Plan.Unavailable)
    }

    @Test
    fun `增量成环不会死循环`() {
        // 夹具：deltas 含 2→1，即版本回退的「环」。PackCodec.validateManifest 要求每一步
        // to > from（版本严格递增），所以这种清单在**解析期**就被拒绝 —— 比让 UpdatePlanner
        // 在环里兜圈更早、更明确。UpdatePlanner.continuousChain 里的 maxSteps 与
        // `next.to <= current` 仍是防手工构造清单的第二道保险。
        assertThrows(PackFormatException::class.java) {
            PackCodec.parseManifest(Fixtures.text("manifest-cycle.json"))
        }
    }

    @Test
    fun `多段连续链依次应用`() {
        val manifest = manifestOf(
            bankVersion = 3,
            fullBytes = 10_000L,
            deltas = listOf(delta(1, 2, 10L), delta(2, 3, 20L)),
        )
        val plan = UpdatePlanner.plan(1, manifest)
        assertTrue(plan.toString(), plan is Plan.Deltas)
        val deltas = plan as Plan.Deltas
        assertEquals(listOf(1, 2), deltas.steps.map { it.from })
        assertEquals(listOf(2, 3), deltas.steps.map { it.to })
        assertEquals(30L, deltas.downloadBytes)
    }

    @Test
    fun `断链中间缺一段则退回全量`() {
        val manifest = manifestOf(
            bankVersion = 3,
            fullBytes = 10_000L,
            deltas = listOf(delta(2, 3, 10L)), // 缺 1→2
        )
        assertTrue(UpdatePlanner.plan(1, manifest) is Plan.Full)
    }

    @Test
    fun `增量总量恰好等于全量也退回全量`() {
        val manifest = manifestOf(
            bankVersion = 2,
            fullBytes = 100L,
            deltas = listOf(delta(1, 2, 100L)),
        )
        assertTrue(UpdatePlanner.plan(1, manifest) is Plan.Full)
    }

    @Test
    fun `没有全量但有连续增量时仍可增量更新`() {
        val manifest = manifestOf(bankVersion = 2, fullBytes = null, deltas = listOf(delta(1, 2, 100L)))
        val plan = UpdatePlanner.plan(1, manifest)
        assertTrue(plan.toString(), plan is Plan.Deltas)
        assertEquals(100L, plan.downloadBytes)
    }

    @Test
    fun `同一 from 有多个候选时取清单里靠前的`() {
        val manifest = manifestOf(
            bankVersion = 2,
            fullBytes = 10_000L,
            deltas = listOf(delta(1, 2, 10L), delta(1, 2, 20L)),
        )
        val plan = UpdatePlanner.plan(1, manifest) as Plan.Deltas
        assertEquals(10L, plan.downloadBytes)
    }

    private fun manifestOf(bankVersion: Int, fullBytes: Long?, deltas: List<DeltaRef>): Manifest = Manifest(
        bankVersion = bankVersion,
        chapters = emptyList(),
        full = fullBytes?.let { FileRef(url = "full/bank-v$bankVersion.jsonl.gz", sha256 = "a".repeat(64), bytes = it) },
        deltas = deltas,
        mediaBase = "media/",
    )

    private fun delta(from: Int, to: Int, bytes: Long): DeltaRef = DeltaRef(
        from = from,
        to = to,
        url = "delta/v$from-v$to.jsonl.gz",
        sha256 = "b".repeat(64),
        bytes = bytes,
        count = 3,
    )
}
