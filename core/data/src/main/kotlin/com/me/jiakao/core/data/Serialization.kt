package com.me.jiakao.core.data

import com.me.jiakao.core.model.MediaKind
import com.me.jiakao.core.model.MediaRef
import com.me.jiakao.core.model.Option
import com.me.jiakao.core.model.QType
import com.me.jiakao.core.model.Question
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 模块内统一 JSON 配置。
 *
 * 合同 §5 的模型类不可修改(无 @Serializable),因此 DB 内 options_json / media_json
 * 以及备份文件使用下方 DTO 承载,字段名与合同 §2 题目 JSON 保持一致。
 */
internal object DataJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}

@Serializable
internal data class OptionDto(val key: String, val text: String)

@Serializable
internal data class MediaRefDto(
    val sha256: String,
    val ext: String,
    /** image|anim|video(合同 §2) */
    val kind: String,
    val w: Int,
    val h: Int,
    val bytes: Long,
)

// ───────── 枚举与存储字串的互转(存储值 = 合同 §2 原词) ─────────

internal fun QType.toStorage(): String = when (this) {
    QType.JUDGE -> "judge"
    QType.SINGLE -> "single"
    QType.MULTI -> "multi"
}

internal fun String.toQType(): QType = when (this) {
    "judge" -> QType.JUDGE
    "single" -> QType.SINGLE
    "multi" -> QType.MULTI
    else -> error("unknown question type: $this")
}

internal fun MediaKind.toStorage(): String = when (this) {
    MediaKind.IMAGE -> "image"
    MediaKind.ANIM -> "anim"
    MediaKind.VIDEO -> "video"
}

internal fun String.toMediaKind(): MediaKind = when (this) {
    "image" -> MediaKind.IMAGE
    "anim" -> MediaKind.ANIM
    "video" -> MediaKind.VIDEO
    else -> error("unknown media kind: $this")
}

internal fun List<String>.toWrapped(): String = "|${joinToString("|")}|"

/** "|car|truck|" → ["car","truck"];空串/“||” → 空列表 */
internal fun String.unwrapList(): List<String> =
    if (length <= 1) emptyList() else trim('|').split('|').filter { it.isNotEmpty() }

/** LIKE 关键字转义(配合 `ESCAPE '\'` 使用)。 */
internal fun String.escapeLike(): String =
    replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

internal fun Question.toOptionDtos(): List<OptionDto> = options.map { OptionDto(it.key, it.text) }

internal fun List<OptionDto>.toModel(): List<Option> = map { Option(it.key, it.text) }

internal fun MediaRef.toDto() = MediaRefDto(sha256, ext, kind.toStorage(), width, height, bytes)

internal fun MediaRefDto.toModel() = MediaRef(sha256, ext, kind.toMediaKind(), w, h, bytes)

/** 从题目 id(`s{科目}-{6位序号}`)解析排序键;无法解析时返回 null。 */
internal fun idSortKey(id: String): Int? = id.substringAfterLast('-').toIntOrNull()
