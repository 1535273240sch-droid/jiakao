package com.me.jiakao.core.update.plan

import com.me.jiakao.core.update.pack.FileRef

/**
 * 一次更新决策（[UpdatePlanner.plan] 的产物），严格对应合同 §3「更新语义」。
 */
sealed interface Plan {

    /** 本次更新需要下载的字节数（增量按链求和，全量取全量包大小）。 */
    val downloadBytes: Long

    /** 本地版本已不低于远端版本，无需更新。 */
    data class UpToDate(val version: Int) : Plan {
        override val downloadBytes: Long get() = 0L
    }

    /**
     * 依次应用连续增量链（`replaceAll = false`）。
     *
     * @param from 本地版本（链的起点）
     * @param to 远端版本（链的终点）
     * @param steps 按顺序执行的增量，保证 `steps[i].to == steps[i+1].from`
     */
    data class Deltas(val from: Int, val to: Int, val steps: List<DeltaStep>) : Plan {
        override val downloadBytes: Long get() = steps.sumOf { it.bytes }
    }

    /** 应用全量快照（`replaceAll = true`，先清空题表）。 */
    data class Full(val version: Int, val file: FileRef) : Plan {
        override val downloadBytes: Long get() = file.bytes
    }

    /**
     * 清单里既没有可用的全量包，也没有连续增量链，本次无法更新。
     *
     * 这是 TASK 三种决策之外的一种情况（TASK 只列了 UpToDate/Deltas/Full），
     * 用于避免纯函数抛异常；见 out/CONTRACT_ISSUES.md。
     */
    data class Unavailable(val reason: String) : Plan {
        override val downloadBytes: Long get() = 0L
    }
}

/** 增量链中的一环。 */
data class DeltaStep(
    val from: Int,
    val to: Int,
    val url: String,
    val sha256: String,
    val bytes: Long,
) {
    /** 转统一的文件引用，便于复用下载器。 */
    fun asFileRef(): FileRef = FileRef(url = url, sha256 = sha256, bytes = bytes)
}
