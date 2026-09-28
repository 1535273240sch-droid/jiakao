package com.me.jiakao.core.update.plan

import com.me.jiakao.core.update.pack.DeltaRef
import com.me.jiakao.core.update.pack.Manifest

/**
 * 更新决策（纯函数，无副作用、无 IO），规则来自合同 §3「更新语义」：
 *
 * 1. 本地版本 == 远端版本 → 已最新；
 * 2. 存在**连续**增量链 `本地 → 远端` 且增量总字节 < 全量字节 → 依次应用增量；
 * 3. 否则应用全量（`replaceAll = true`）。
 *
 * 本实现额外处理的边界（均按最小假设，已记入 out/CONTRACT_ISSUES.md）：
 * - 本地版本 > 远端版本（服务端回滚）→ 视为已最新，不降级；
 * - 本地无数据（`bankVersion() == 0`）→ 必须全量；
 * - 清单既无全量也无连续增量链 → [Plan.Unavailable]。
 */
object UpdatePlanner {

    /** `QuestionStore.bankVersion()` 在无数据时的返回值。 */
    const val NO_LOCAL_DATA: Int = 0

    /**
     * 计算更新方案。
     *
     * @param localVersion 本地题库版本（无数据为 [NO_LOCAL_DATA]）
     * @param manifest 远端清单
     */
    fun plan(localVersion: Int, manifest: Manifest): Plan {
        val remoteVersion = manifest.bankVersion

        // 合同 §3.1：相等即最新；高于远端（服务端回滚）不降级
        if (localVersion >= remoteVersion) {
            return Plan.UpToDate(localVersion)
        }

        val chain = continuousChain(from = localVersion, to = remoteVersion, deltas = manifest.deltas)
        val full = manifest.full

        if (chain != null) {
            val deltaBytes = chain.sumOf { it.bytes }
            // 合同 §3.2：增量总字节 < 全量字节才走增量
            if (full == null || deltaBytes < full.bytes) {
                return Plan.Deltas(from = localVersion, to = remoteVersion, steps = chain)
            }
        }

        if (full != null) return Plan.Full(version = remoteVersion, file = full)

        return Plan.Unavailable("题库清单缺少可用更新包：既无连续增量链，也无全量包")
    }

    /**
     * 构造 `from → to` 的连续增量链；不存在返回 null。
     *
     * 「连续」= 每一步的 `from` 等于上一步的 `to`，直到恰好抵达 [to]。
     * 同一 `from` 有多个候选时取清单中靠前者。
     */
    private fun continuousChain(from: Int, to: Int, deltas: List<DeltaRef>): List<DeltaStep>? {
        if (from < 1) return null // 无本地数据无法走增量
        if (from >= to) return null

        val byFrom = HashMap<Int, DeltaRef>(deltas.size)
        deltas.forEach { delta -> if (!byFrom.containsKey(delta.from)) byFrom[delta.from] = delta }

        val steps = ArrayList<DeltaStep>()
        var current = from
        // 校验器保证 to > from，即版本严格递增，最多 deltas.size 步；这里再加一道保险防手工构造的清单成环
        val maxSteps = deltas.size + 1
        while (current < to) {
            if (steps.size >= maxSteps) return null
            val next = byFrom[current] ?: return null
            if (next.to <= current) return null
            steps += DeltaStep(
                from = next.from,
                to = next.to,
                url = next.url,
                sha256 = next.sha256,
                bytes = next.bytes,
            )
            current = next.to
        }
        return if (current == to && steps.isNotEmpty()) steps else null
    }
}
