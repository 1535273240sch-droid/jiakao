package com.me.jiakao.core.update.pack

import com.me.jiakao.core.model.Chapter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 题库包格式错误。
 *
 * 携带出错行号（1 基，0 表示不针对某一行，例如清单解析失败），
 * 便于 05 生成侧与人工排查定位到坏包的具体位置。
 */
class PackFormatException(
    /** 出错行号（1 基）；0 表示非行级错误。 */
    val line: Int,
    /** 人类可读的原因。 */
    val detail: String,
    cause: Throwable? = null,
) : RuntimeException(
    if (line > 0) "题库包格式错误（第 $line 行）：$detail" else "题库包格式错误：$detail",
    cause,
) {
    /** 不带行号的构造器（清单 / 结构级错误）。 */
    constructor(detail: String, cause: Throwable? = null) : this(0, detail, cause)
}

/**
 * 一个可下载文件的引用（全量快照、离线整包）。
 *
 * @param url 相对 manifest 所在目录的路径，或绝对 http(s) URL
 * @param sha256 文件内容小写 64 位十六进制摘要
 * @param bytes 文件字节数（下载后用于二次校验）
 * @param count 该文件内题目行数（可选，仅用于展示）
 */
@Serializable
data class FileRef(
    val url: String,
    val sha256: String,
    val bytes: Long,
    val count: Int? = null,
)

/**
 * 增量文件引用：从 [from] 版本应用到 [to] 版本。
 *
 * 文件名约定 `delta/v{A}-v{B}.jsonl.gz`（合同 §3），[from] < [to]。
 * 增量之间只有首尾相接（前一节的 `to` == 后一节的 `from`）才构成连续增量链。
 */
@Serializable
data class DeltaRef(
    val from: Int,
    val to: Int,
    val url: String,
    val sha256: String,
    val bytes: Long,
    val count: Int? = null,
) {
    /** 转成统一的文件引用，便于统一下载。 */
    fun asFileRef(): FileRef = FileRef(url = url, sha256 = sha256, bytes = bytes, count = count)
}

/** 清单中的章节条目（`manifest.chapters`），落地为合同 §5 的 [Chapter]。 */
@Serializable
data class ManifestChapter(
    val id: String,
    val subject: Int,
    val name: String,
    val order: Int,
) {
    /** 转合同 §5 模型。 */
    fun toChapter(): Chapter = Chapter(id = id, subject = subject, name = name, order = order)
}

/**
 * 离线整包内的媒体索引条目（可选文件 `media/index.json`）。
 *
 * 离线包不依赖它也能导入（媒体条目自带 `{sha}.{ext}` 文件名），但生成侧提供它时，
 * 导入会额外核对 `bytes` 与路径，损坏包能更早被发现。
 */
@Serializable
data class MediaEntry(
    val sha256: String,
    val ext: String,
    val bytes: Long,
    /** 包内路径，例如 `media/ab/abcd….webp`。 */
    val path: String,
)

/**
 * 题库清单 `manifest.json`（合同 §3）。
 *
 * 解析使用 `ignoreUnknownKeys = true`，服务端新增字段不会让旧客户端解析失败（向前兼容）。
 *
 * @param schema 格式版本，当前只支持 [SCHEMA]
 * @param bankVersion 远端题库版本号，与本地 `bankVersion()` 比较决定更新方式
 * @param minAppVersionCode 最低支持的 App versionCode，高于本机则必须提示升级 App
 * @param mediaBase 媒体根路径（相对源地址），以 `/` 结尾
 */
@Serializable
data class Manifest(
    val schema: Int = SCHEMA,
    @SerialName("bank_version") val bankVersion: Int,
    @SerialName("released_at") val releasedAt: String = "",
    @SerialName("min_app_version_code") val minAppVersionCode: Int = 1,
    val chapters: List<ManifestChapter> = emptyList(),
    val full: FileRef? = null,
    val deltas: List<DeltaRef> = emptyList(),
    @SerialName("media_base") val mediaBase: String = "media/",
    val bundle: FileRef? = null,
) {
    companion object {
        /** 当前支持的清单格式版本。 */
        const val SCHEMA: Int = 1
    }

    /** 章节是否整体替换（`chapters` 非空时替换，见合同 §5 `applyPack`）。 */
    fun chaptersOrNull(): List<Chapter>? = chapters.takeIf { it.isNotEmpty() }?.map { it.toChapter() }
}
