package com.me.jiakao.core.data

import com.me.jiakao.core.data.db.ChapterEntity
import com.me.jiakao.core.data.db.ExamEntity
import com.me.jiakao.core.data.db.QuestionEntity
import com.me.jiakao.core.model.Chapter
import com.me.jiakao.core.model.ExamResult
import com.me.jiakao.core.model.Question
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** quiz.db 实体 ↔ 合同模型 的互转,集中在此便于核对字段。 */
internal object Mappers {
    private val json: Json = DataJson.json
    private val optionListSerializer = ListSerializer(OptionDto.serializer())
    private val mediaListSerializer = ListSerializer(MediaRefDto.serializer())
    private val stringListSerializer = ListSerializer(String.serializer())

    fun questionToEntity(q: Question, sortKey: Int): QuestionEntity = QuestionEntity(
        id = q.id,
        subject = q.subject,
        type = q.type.toStorage(),
        chapterId = q.chapterId,
        sortKey = sortKey,
        hasMedia = q.media.isNotEmpty(),
        hasAnim = q.media.any { it.kind == com.me.jiakao.core.model.MediaKind.ANIM || it.kind == com.me.jiakao.core.model.MediaKind.VIDEO },
        vehicles = q.vehicles.toWrapped(),
        tags = q.tags.toWrapped(),
        stem = q.stem,
        optionsJson = json.encodeToString(optionListSerializer, q.toOptionDtos()),
        optionsText = q.options.joinToString(" ") { it.text },
        answerCsv = q.answer.joinToString(","),
        explain = q.explain,
        mediaJson = json.encodeToString(mediaListSerializer, q.media.map { it.toDto() }),
        rev = q.rev,
    )

    fun entityToQuestion(e: QuestionEntity): Question = Question(
        id = e.id,
        subject = e.subject,
        vehicles = e.vehicles.unwrapList(),
        type = e.type.toQType(),
        chapterId = e.chapterId,
        tags = e.tags.unwrapList(),
        stem = e.stem,
        options = json.decodeFromString(optionListSerializer, e.optionsJson).toModel(),
        answer = e.answerCsv.split(',').filter { it.isNotEmpty() },
        explain = e.explain,
        media = json.decodeFromString(mediaListSerializer, e.mediaJson).map { it.toModel() },
        rev = e.rev,
    )

    fun chapterToEntity(c: Chapter): ChapterEntity =
        ChapterEntity(id = c.id, subject = c.subject, name = c.name, sortOrder = c.order)

    fun entityToChapter(e: ChapterEntity): Chapter =
        Chapter(id = e.id, subject = e.subject, name = e.name, order = e.sortOrder)

    fun encodeStringList(ids: List<String>): String = json.encodeToString(stringListSerializer, ids)

    fun decodeStringList(raw: String): List<String> = json.decodeFromString(stringListSerializer, raw)

    fun examEntityToResult(e: ExamEntity): ExamResult = ExamResult(
        id = e.id,
        subject = e.subject,
        startedAt = e.startedAt,
        durationSec = e.durationSec,
        score = e.score,
        passed = e.passed,
        questionIds = decodeStringList(e.questionIdsJson),
        wrongIds = decodeStringList(e.wrongIdsJson),
    )

    fun examResultToEntity(r: ExamResult, id: Long): ExamEntity = ExamEntity(
        id = id,
        subject = r.subject,
        startedAt = r.startedAt,
        durationSec = r.durationSec,
        score = r.score,
        passed = r.passed,
        questionIdsJson = encodeStringList(r.questionIds),
        wrongIdsJson = encodeStringList(r.wrongIds),
    )
}
