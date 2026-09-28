package com.me.jiakao.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.InputStream
import java.io.OutputStream

// ───────── 基础模型 ─────────
enum class QType { JUDGE, SINGLE, MULTI }
enum class MediaKind { IMAGE, ANIM, VIDEO }
enum class PracticeMode { SEQUENTIAL, RANDOM, CHAPTER, SPECIAL, WRONG, FAVORITE, EXAM }

data class Scope(val subject: Int, val vehicle: String = "car")

data class MediaRef(
    val sha256: String, val ext: String, val kind: MediaKind,
    val width: Int, val height: Int, val bytes: Long,
)
data class Option(val key: String, val text: String)
data class Question(
    val id: String, val subject: Int, val vehicles: List<String>, val type: QType,
    val chapterId: String, val tags: List<String>, val stem: String,
    val options: List<Option>, val answer: List<String>, val explain: String,
    val media: List<MediaRef>, val rev: Int,
)
data class Chapter(val id: String, val subject: Int, val name: String, val order: Int)

sealed interface PackRecord {
    data class Upsert(val question: Question) : PackRecord
    data class Delete(val id: String) : PackRecord
}

sealed interface QFilter {
    data object All : QFilter
    data class OfType(val type: QType) : QFilter
    data object HasMedia : QFilter      // 含图片/动图/视频
    data object HasAnim : QFilter       // 仅动图/视频
    data class Tag(val tag: String) : QFilter
}

data class BankCounts(val total: Int, val withMedia: Int, val withAnim: Int, val byType: Map<QType, Int>)
data class Progress(val total: Int, val done: Int, val correct: Int)
data class ChapterStat(val chapter: Chapter, val total: Int, val done: Int, val correct: Int)
data class ExamRules(val subject: Int, val questionCount: Int, val timeLimitSec: Int,
                     val pointsPerQuestion: Int, val passScore: Int) {
    companion object {
        fun of(subject: Int) = when (subject) {
            1 -> ExamRules(1, 100, 2700, 1, 90)
            4 -> ExamRules(4, 50, 1800, 2, 90)
            else -> error("subject must be 1 or 4")
        }
    }
}
data class ExamResult(
    val id: Long, val subject: Int, val startedAt: Long, val durationSec: Int,
    val score: Int, val passed: Boolean, val questionIds: List<String>, val wrongIds: List<String>,
)

// ───────── 01 实现：题库与用户数据 ─────────
interface QuestionStore {                                   // 04 调用
    suspend fun bankVersion(): Int                          // 无数据返回 0
    /** 单事务；records 为惰性序列，内部每 500 条批量写入；replaceAll=true 先清空题表；chapters 非空则整体替换章节 */
    suspend fun applyPack(newVersion: Int, chapters: List<Chapter>?, records: Sequence<PackRecord>, replaceAll: Boolean)
    suspend fun allMediaRefs(): List<MediaRef>              // 去重
}
interface QuizRepository {                                  // 02 调用
    fun chapters(scope: Scope): Flow<List<Chapter>>
    fun counts(scope: Scope): Flow<BankCounts>
    /** 稳定顺序；chapterId 为空表示全科目 */
    suspend fun ids(scope: Scope, chapterId: String? = null, filter: QFilter = QFilter.All): List<String>
    /** 保持入参顺序，缺失的跳过 */
    suspend fun getQuestions(ids: List<String>): List<Question>
    suspend fun search(scope: Scope, keyword: String, limit: Int = 50): List<Question>
    val bankVersion: Flow<Int>
}
interface UserRepository {                                  // 02 调用
    /** 记录作答；返回是否答对；答错自动入错题本，错题连对 3 次自动移出 */
    suspend fun recordAnswer(question: Question, chosen: List<String>, mode: PracticeMode): Boolean
    fun wrongIds(subject: Int): Flow<List<String>>
    suspend fun clearWrong(questionId: String)
    fun favoriteIds(subject: Int): Flow<Set<String>>
    suspend fun toggleFavorite(question: Question): Boolean  // 返回操作后是否已收藏
    fun progress(scope: Scope): Flow<Progress>
    fun chapterStats(scope: Scope): Flow<List<ChapterStat>>
    suspend fun savePosition(key: String, index: Int)
    suspend fun loadPosition(key: String): Int
    suspend fun saveExam(result: ExamResult): Long
    fun exams(subject: Int): Flow<List<ExamResult>>
    suspend fun exportBackup(out: OutputStream)             // JSON
    suspend fun importBackup(input: InputStream)
}
interface ExamService {                                     // 02 调用
    fun rules(subject: Int): ExamRules
    suspend fun buildPaper(scope: Scope): List<String>      // 题目 id 列表
    fun grade(subject: Int, questions: List<Question>, answers: Map<String, List<String>>,
              startedAt: Long, durationSec: Int): ExamResult
}

// ───────── 03 实现：媒体 ─────────
interface MediaStore {                                      // 04、02 调用
    fun file(ref: MediaRef): File?                          // 不存在返回 null
    fun has(ref: MediaRef): Boolean = file(ref) != null
    /** 校验 sha256+bytes → 原子移动到目标路径；不匹配返回 false 并删除 tmp */
    suspend fun commit(ref: MediaRef, tmp: File): Boolean
    /** 删除 keepSha 之外的文件，返回删除个数 */
    suspend fun retain(keepSha: Set<String>): Int
    fun usedBytes(): Long
    val committed: SharedFlow<String>                       // 每次 commit 成功发射 sha256，Compose 据此刷新占位
}
interface MediaPrefetcher {                                 // 02 调用
    fun prefetch(refs: List<MediaRef>)                      // 预热解码/内存缓存，可重复调用
    fun cancelAll()
}
/* 03 还必须在 :core:media 导出以下 Compose API（签名固定）：
@Composable fun QuizMedia(ref: MediaRef, modifier: Modifier = Modifier,
                          contentScale: ContentScale = ContentScale.Fit, onClick: (() -> Unit)? = null)
@Composable fun MediaViewer(refs: List<MediaRef>, startIndex: Int, onDismiss: () -> Unit)
*/

// ───────── 04 实现：更新 ─────────
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val fromVersion: Int, val toVersion: Int, val downloadBytes: Long) : UpdateState
    data class DownloadingPack(val doneBytes: Long, val totalBytes: Long) : UpdateState
    data object Importing : UpdateState
    data class DownloadingMedia(val done: Int, val total: Int) : UpdateState
    data class UpToDate(val version: Int) : UpdateState
    data class Failed(val message: String, val retryable: Boolean) : UpdateState
}
interface BankUpdater {                                     // 02 调用
    val state: StateFlow<UpdateState>
    val sourceUrl: StateFlow<String>                        // 持久化（DataStore），以 "/" 结尾
    suspend fun setSource(baseUrl: String)
    suspend fun check()                                     // 只检查，不下载
    fun startUpdate()                                       // WorkManager 前台加急任务，可重入
    suspend fun importLocalPack(input: InputStream)         // 导入 bundle-vN.zip
    fun scheduleAuto(enabled: Boolean)                      // 每 24h、Wi-Fi、电量不低
}
