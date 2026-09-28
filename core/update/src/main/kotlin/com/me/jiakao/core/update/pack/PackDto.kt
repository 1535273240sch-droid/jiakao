package com.me.jiakao.core.update.pack

import com.me.jiakao.core.model.MediaKind
import com.me.jiakao.core.model.MediaRef
import com.me.jiakao.core.model.Option
import com.me.jiakao.core.model.PackRecord
import com.me.jiakao.core.model.QType
import com.me.jiakao.core.model.Question
import com.me.jiakao.core.update.util.Sha256
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 题库行（合同 §2）的内部 DTO 与校验。这些类型是 :core:update 的私有实现细节：
 * pack 格式解析由 04 独占（见 TASK 交付物 1），外界只看到合同 §5 的 Question/PackRecord。
 *
 * 所有字段都声明为可空，是为了在「字段缺失」与「类型错误」时给出带行号的明确报错，
 * 而不是让 kotlinx-serialization 抛出难以定位的 MissingFieldException。
 */

/** 题目行 DTO；删除行只带 `id` + `deleted: true`。 */
@Serializable
internal data class QuestionDto(
    val id: String? = null,
    val subject: Int? = null,
    val vehicles: List<String>? = null,
    val type: String? = null,
    @SerialName("chapter_id") val chapterId: String? = null,
    val tags: List<String>? = null,
    val stem: String? = null,
    val options: List<OptionDto>? = null,
    val answer: List<String>? = null,
    val explain: String? = null,
    val media: List<MediaDto>? = null,
    val rev: Int? = null,
    /** 删除标记：`{"id":"s1-000003","deleted":true}`（合同 §2）。 */
    val deleted: Boolean? = null,
)

/** 选项 DTO。 */
@Serializable
internal data class OptionDto(
    val key: String? = null,
    val text: String? = null,
)

/** 媒体引用 DTO。 */
@Serializable
internal data class MediaDto(
    val sha256: String? = null,
    val ext: String? = null,
    val kind: String? = null,
    val w: Int? = null,
    val h: Int? = null,
    val bytes: Long? = null,
)

/** 合同 §2 允许的车型。 */
internal val VEHICLES: Set<String> = setOf("car", "truck", "bus", "moto")

/** 合同 §2：静图/动图统一 WebP，视频 mp4。 */
private val MEDIA_EXTENSIONS: Set<String> = setOf("webp", "mp4")

/** 判断题固定选项（合同 §2）。 */
private val JUDGE_OPTIONS: List<Pair<String, String>> = listOf("A" to "正确", "B" to "错误")

/**
 * 题目行 → [PackRecord]，任何字段缺失/类型/取值错误都抛带行号的 [PackFormatException]。
 *
 * 校验规则来自合同 §2：
 * - `subject` ∈ {1,4}；`type` ∈ {judge,single,multi}
 * - 判断题 options 固定 `A 正确 / B 错误`，答案有且只有一个
 * - `media.kind` ∈ {image,anim,video}；`ext` ∈ {webp,mp4}，且 video↔mp4、image/anim↔webp
 * - `sha256` 为小写 64 位十六进制
 *
 * 唯一放宽：`vehicles` 缺失或为空时按 `car` 处理（README 约定「车型默认小车」），
 * 避免题目因缺字段而在按车型筛选时整题消失。
 */
internal fun QuestionDto.toRecord(line: Int): PackRecord {
    fun fail(message: String): Nothing = throw PackFormatException(line, message)

    val questionId = id?.takeIf { it.isNotBlank() } ?: fail("缺少 id 或 id 为空")
    if (deleted == true) return PackRecord.Delete(questionId)

    val subjectValue = subject ?: fail("$questionId 缺少 subject")
    if (subjectValue != 1 && subjectValue != 4) {
        fail("$questionId 的 subject 非法：$subjectValue（应为 1 或 4）")
    }

    val rawType = type?.takeIf { it.isNotBlank() } ?: fail("$questionId 缺少 type")
    val qType = when (rawType.lowercase()) {
        "judge" -> QType.JUDGE
        "single" -> QType.SINGLE
        "multi" -> QType.MULTI
        else -> fail("$questionId 的 type 非法：$rawType（应为 judge|single|multi）")
    }

    val vehicleList = vehicles ?: emptyList()
    vehicleList.forEach { v ->
        if (v !in VEHICLES) fail("$questionId 的 vehicles 含非法值：$v（应为 car|truck|bus|moto）")
    }

    val chapterIdValue = chapterId?.takeIf { it.isNotBlank() } ?: fail("$questionId 缺少 chapter_id")

    val stemValue = stem?.takeIf { it.isNotBlank() } ?: fail("$questionId 缺少 stem")

    val optionDtos = options ?: fail("$questionId 缺少 options")
    if (optionDtos.isEmpty()) fail("$questionId 的 options 为空")
    val keys = LinkedHashSet<String>()
    val optionsValue = ArrayList<Option>(optionDtos.size)
    optionDtos.forEach { dto ->
        val key = dto.key?.takeIf { it.isNotBlank() } ?: fail("$questionId 的 options 缺少 key")
        if (!keys.add(key)) fail("$questionId 的 options.key 重复：$key")
        optionsValue += Option(key = key, text = dto.text ?: "")
    }

    if (qType == QType.JUDGE) {
        val actual = optionsValue.map { it.key to it.text }
        if (actual != JUDGE_OPTIONS) {
            fail("$questionId 是判断题，options 必须是 [A 正确, B 错误]，实际为 ${optionsValue.map { "${it.key} ${it.text}" }}")
        }
    }

    val answerValue = answer ?: fail("$questionId 缺少 answer")
    if (answerValue.isEmpty()) fail("$questionId 的 answer 为空")
    answerValue.forEach { letter ->
        if (letter !in keys) fail("$questionId 的 answer 含非选项字母：$letter")
    }
    if (answerValue.toSet().size != answerValue.size) fail("$questionId 的 answer 含重复项：$answerValue")
    if (qType == QType.JUDGE && answerValue.size != 1) {
        fail("$questionId 是判断题，answer 必须且只能有一个选项，实际为 $answerValue")
    }

    val mediaValue = (media ?: emptyList()).map { dto -> dto.toMediaRef(questionId, ::fail) }

    val revValue = rev ?: 1
    if (revValue < 1) fail("$questionId 的 rev 非法：$revValue（应为 ≥1）")

    return PackRecord.Upsert(
        Question(
            id = questionId,
            subject = subjectValue,
            vehicles = vehicleList.ifEmpty { listOf("car") },
            type = qType,
            chapterId = chapterIdValue,
            tags = tags ?: emptyList(),
            stem = stemValue,
            options = optionsValue,
            answer = answerValue,
            explain = explain ?: "",
            media = mediaValue,
            rev = revValue,
        ),
    )
}

private fun MediaDto.toMediaRef(questionId: String, fail: (String) -> Nothing): MediaRef {
    val sha = sha256 ?: fail("$questionId 的 media 缺少 sha256")
    if (!Sha256.isHex(sha)) fail("$questionId 的 media.sha256 非法：$sha（应为小写 64 位十六进制）")

    val extValue = ext?.lowercase() ?: fail("$questionId 的 media 缺少 ext")
    if (extValue !in MEDIA_EXTENSIONS) fail("$questionId 的 media.ext 非法：$ext（应为 webp|mp4）")

    val rawKind = kind?.lowercase() ?: fail("$questionId 的 media 缺少 kind")
    val kindValue = when (rawKind) {
        "image" -> MediaKind.IMAGE
        "anim" -> MediaKind.ANIM
        "video" -> MediaKind.VIDEO
        else -> fail("$questionId 的 media.kind 非法：$kind（应为 image|anim|video）")
    }
    val expectedExt = if (kindValue == MediaKind.VIDEO) "mp4" else "webp"
    if (extValue != expectedExt) {
        fail("$questionId 的 media 组合非法：kind=$rawKind 必须配 ext=$expectedExt，实际 ext=$extValue")
    }

    val width = w ?: fail("$questionId 的 media 缺少 w")
    val height = h ?: fail("$questionId 的 media 缺少 h")
    if (width <= 0 || height <= 0) fail("$questionId 的 media 尺寸非法：${width}x$height")

    val size = bytes ?: fail("$questionId 的 media 缺少 bytes")
    if (size <= 0) fail("$questionId 的 media.bytes 非法：$size")

    return MediaRef(sha256 = sha, ext = extValue, kind = kindValue, width = width, height = height, bytes = size)
}
