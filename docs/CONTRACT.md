# CONTRACT · 冻结合同（v1，任何 AI 不得修改）

发现合同有缺陷 → 在自己的 `out/CONTRACT_ISSUES.md` 记录（现象、建议改法），并按**最小假设**继续。由人在拼装阶段统一裁决。

---
## 1. 工程坐标
- 包名 / applicationId：`com.me.jiakao`；模块包名 `com.me.jiakao.<module>`
- Android：**minSdk 28**、compileSdk/targetSdk 35；Kotlin 2.0.x；Compose + Material3；Hilt(KSP)；Room(KSP)
- 全部异步用 Kotlin Coroutines/Flow，**禁止** LiveData、RxJava、Gson

### 模块与目录所有权（只能写自己的目录）
| 目录 | 模块 | 负责 |
|---|---|---|
| 根：`settings.gradle.kts` `build.gradle.kts` `gradle/` `gradle.properties` | — | **仅 01** |
| `core/model` | `:core:model`（纯 Kotlin/JVM） | 01（内容=本合同 §5 原样） |
| `core/data` | `:core:data` | 01 |
| `app` `baselineprofile` | `:app` `:baselineprofile` | 02 |
| `core/media` | `:core:media` | 03 |
| `core/update` | `:core:update` | 04 |
| `tools/pipeline` `server` | Python | 05 |

依赖方向：`:app → :core:{data,media,update} → :core:model`；`:core:update → :core:data, :core:media`（仅通过接口，Hilt 注入）。

### 文件位置约定（运行时）
- 题库库：`quiz.db`（可整体重建）；用户库：`user.db`（**更新题库绝不触碰**）
- 媒体：`filesDir/media/{sha256前2位}/{sha256}.{ext}`
- 下载临时目录：`cacheDir/dl/`

---
## 2. 题目 JSON（一行一题，snake_case）
字段：`id`(稳定不复用，`s{科目}-{6位序号}`) · `subject`(1|4) · `vehicles`(`car|truck|bus|moto`) · `type`(`judge|single|multi`) · `chapter_id` · `tags` · `stem` · `options[{key,text}]` · `answer`(按字母序) · `explain` · `media[{sha256,ext,kind,w,h,bytes}]` · `rev`(题目修订号，改内容+1)
规则：判断题 options 固定 `A 正确 / B 错误`；`media.kind`∈`image|anim|video`；`ext`∈`webp|mp4`（静图/动图统一 WebP，动图为动态 WebP）；sha256 为文件内容小写 64 位十六进制。

```jsonl
{"id":"s1-000001","subject":1,"vehicles":["car"],"type":"judge","chapter_id":"s1-c03","tags":["标志","禁令"],"stem":"如图所示，这个标志的含义是禁止车辆停放。","options":[{"key":"A","text":"正确"},{"key":"B","text":"错误"}],"answer":["A"],"explain":"红圈红斜杠蓝底为禁止停车标志。","media":[{"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","ext":"webp","kind":"image","w":480,"h":480,"bytes":8123}],"rev":1}
{"id":"s4-000002","subject":4,"vehicles":["car"],"type":"single","chapter_id":"s4-c02","tags":["动画","转弯"],"stem":"如图所示，机动车在此路口应如何通过？","options":[{"key":"A","text":"加速通过"},{"key":"B","text":"减速让行"},{"key":"C","text":"鸣笛通过"},{"key":"D","text":"停车等候"}],"answer":["B"],"explain":"路口有行人通行，应减速让行。","media":[{"sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","ext":"webp","kind":"anim","w":640,"h":360,"bytes":412345}],"rev":2}
{"id":"s1-000003","subject":1,"vehicles":["car","truck"],"type":"multi","chapter_id":"s1-c01","tags":["法规"],"stem":"下列哪些行为属于违法行为？","options":[{"key":"A","text":"酒后驾驶"},{"key":"B","text":"按规定礼让行人"},{"key":"C","text":"驾车时手持拨打电话"},{"key":"D","text":"遵守限速"}],"answer":["A","C"],"explain":"","media":[],"rev":1}
```
增量文件中的删除行：`{"id":"s1-000003","deleted":true}`

---
## 3. 题库包格式（服务端为纯静态文件，可放 GitHub Pages / R2 / Nginx / 局域网）
```
<base>/manifest.json
<base>/full/bank-v{N}.jsonl.gz          # 全量快照（GZIP）
<base>/delta/v{A}-v{B}.jsonl.gz         # 增量：upsert 行 + 删除行
<base>/media/{sha[0:2]}/{sha}.{ext}     # 内容寻址，跨版本复用，永不覆盖
<base>/bundle/bundle-v{N}.zip           # 离线整包：manifest.json + bank.jsonl + media/**（U盘/adb 导入）
```
```json
{
  "schema": 1,
  "bank_version": 12,
  "released_at": "2026-09-28T12:00:00Z",
  "min_app_version_code": 1,
  "chapters": [
    {"id":"s1-c01","subject":1,"name":"道路交通安全法律法规","order":1},
    {"id":"s4-c02","subject":4,"name":"安全文明驾驶常识","order":2}
  ],
  "full":   {"url":"full/bank-v12.jsonl.gz","sha256":"…","bytes":123456,"count":1500},
  "deltas": [{"from":11,"to":12,"url":"delta/v11-v12.jsonl.gz","sha256":"…","bytes":2048}],
  "media_base": "media/",
  "bundle": {"url":"bundle/bundle-v12.zip","sha256":"…","bytes":99999999}
}
```
### 更新语义（04 实现、05 生成必须一致）
1. 本地版本 = 远端版本 → 已最新。
2. 存在连续增量链 `本地→远端` 且总字节 < 全量字节 → 依次应用增量（`replaceAll=false`）；否则应用全量（`replaceAll=true`，先清空题表）。
3. **题目导入**在单个数据库事务内完成，成功后才写入 `bank_version`；失败则回滚，版本不变。
4. **媒体**在题目导入后异步补齐：`allMediaRefs()` − 本地已有 → 并发下载 → sha256+bytes 校验 → 原子移动。媒体失败不回滚题目，下次重试；UI 显示占位。
5. 所有下载：先写 `cacheDir/dl/*.part`，校验通过再提交；支持 Range 断点续传；manifest 用 ETag / If-None-Match。
6. `retain()` 垃圾回收：仅当全量更新成功且媒体全部就绪后，删除不再被引用的媒体。

---
## 4. 考试规则（`ExamRules.of(subject)`）
| 科目 | 题数 | 时限 | 每题分 | 合格线 |
|---|---|---|---|---|
| 1 | 100 | 2700s | 1 | 90 |
| 4 | 50 | 1800s | 2 | 90 |

组卷：按 `Scope(subject, vehicle)` 从题库随机抽题；多选题错选/漏选均不得分。

---
## 5. `:core:model` 源码（01 原样落地；02/03/04 独立开发时抄一份到 `out/_standalone/model_stub/`，拼装时丢弃）
路径：`core/model/src/main/kotlin/com/me/jiakao/core/model/Contract.kt`
```kotlin
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
```

---
## 6. Hilt 绑定约定
`@InstallIn(SingletonComponent::class)`：01 提供 `DataModule`（绑定 `QuizRepository/UserRepository/ExamService/QuestionStore`）；03 提供 `MediaModule`（`MediaStore/MediaPrefetcher`，Coil `ImageLoader` 单例）；04 提供 `UpdateModule`（`BankUpdater`，自带 `OkHttpClient`）。`:app` 只负责 `@HiltAndroidApp` 与 `@AndroidEntryPoint`。

## 7. 性能预算（验收硬指标，中端机 / 骁龙7系 或同级）
| 指标 | 目标 |
|---|---|
| 冷启动到首屏可交互 | ≤ 600 ms（Baseline Profile 后） |
| 题目左右滑动 | 0 掉帧，P95 帧耗时 ≤ 8 ms（120Hz 屏）/ 12 ms（60Hz） |
| 章节 id 查询 / 单题读取 | ≤ 20 ms / ≤ 5 ms |
| 全量导入 3000 题 | ≤ 3 s |
| APK 体积（不含外置媒体） | ≤ 25 MB（R8 full mode + 资源收缩） |
| 同时活动的动图解码器 | ≤ 2；图片内存缓存 ≤ 80 MB |
| 动图 | 不可见/后台即暂停，回前台恢复 |

## 8. 依赖版本（统一；拼装阶段由人统一升级，各 AI 不得自行改版本）
`gradle/libs.versions.toml`（01 落地；02/03/04 独立开发时抄一份）
```toml
[versions]
kotlin = "2.0.21"
agp = "8.7.3"
ksp = "2.0.21-1.0.28"
composeBom = "2024.12.01"
hilt = "2.52"
hiltNavCompose = "1.2.0"
room = "2.6.1"
coil = "3.0.4"
okhttp = "4.12.0"
workmanager = "2.10.0"
serialization = "1.7.3"
coroutines = "1.9.0"
navigation = "2.8.5"
lifecycle = "2.8.7"
activityCompose = "1.9.3"
datastore = "1.1.1"
media3 = "1.5.0"
profileinstaller = "1.4.1"
benchmark = "1.3.3"
robolectric = "4.14.1"
junit = "4.13.2"
mockk = "1.13.13"
turbine = "1.2.0"

[libraries]
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-foundation = { module = "androidx.compose.foundation:foundation" }
compose-animation = { module = "androidx.compose.animation:animation" }
compose-icons = { module = "androidx.compose.material:material-icons-extended" }
compose-tooling = { module = "androidx.compose.ui:ui-tooling" }
activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigation" }
lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-android-compiler", version.ref = "hilt" }
hilt-nav-compose = { module = "androidx.hilt:hilt-navigation-compose", version.ref = "hiltNavCompose" }
hilt-work = { module = "androidx.hilt:hilt-work", version.ref = "hiltNavCompose" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
coil-compose = { module = "io.coil-kt.coil3:coil-compose", version.ref = "coil" }
coil-gif = { module = "io.coil-kt.coil3:coil-gif", version.ref = "coil" }
coil-network-okhttp = { module = "io.coil-kt.coil3:coil-network-okhttp", version.ref = "coil" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }
work-runtime = { module = "androidx.work:work-runtime-ktx", version.ref = "workmanager" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
media3-exoplayer = { module = "androidx.media3:media3-exoplayer", version.ref = "media3" }
media3-ui = { module = "androidx.media3:media3-ui", version.ref = "media3" }
profileinstaller = { module = "androidx.profileinstaller:profileinstaller", version.ref = "profileinstaller" }
junit = { module = "junit:junit", version.ref = "junit" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

---
## 9. 交付约定（所有 AI）
- 所有产出放 `./out/`，目录结构 = 最终仓库结构（见 §1 所有权表）。独立开发用的脚手架放 `out/_standalone/`（拼装时自动丢弃）。
- 完成后写 `out/DONE.md`：已完成清单 / 未完成与原因 / 自测命令与结果 / 对合同的疑问 / 拼装注意事项。
- 代码必须可编译、有单元测试；不留 TODO 占位的空实现；公共 API 写 KDoc。
- 不得引入合同 §8 之外的依赖；确需新增，写入 `out/CONTRACT_ISSUES.md` 并说明理由。
