# CONTRACT_ISSUES · 02-app-ui 对冻结合同的疑问与建议

> 按 PROMPT 要求:不修改合同,记录问题并按最小假设继续,由人在拼装阶段统一裁决。

---

## ISSUE-01 合同 §8 依赖清单缺少若干交付物所需依赖(已在本目录 toml 副本中追加)

TASK.md 交付物 5 要求 `baselineprofile/`(Macrobenchmark + Baseline Profile)与 JankStats 调试日志、以及 Compose UI 测试,但合同 §8 的 toml 没有对应条目:

| 需要的依赖 | 用途 | 建议版本 |
|---|---|---|
| `androidx.benchmark:benchmark-macro-junit4` | baselineprofile 模块基准规则 | 沿用 §8 已有 `benchmark = "1.3.3"` |
| 插件 `androidx.baselineprofile`(id) | `:app:generateBaselineProfile` 任务 | 1.3.3 |
| 插件 `com.android.test` | baselineprofile 模块本身 | 沿用 §8 `agp` |
| `androidx.metrics:metrics-performance` | JankStats 调试日志上报 | 1.0.0-beta01 |
| `androidx.compose.ui:ui-test-junit4` / `ui-test-manifest` | Compose UI 测试 | 由 §8 的 composeBom 管理版本 |
| `androidx.test:runner` | instrumentation runner | 1.6.2 |
| `androidx.hilt:hilt-compiler` | `hilt-work`(@HiltWorkerFactory 初始化 04 的 Worker) | 沿用 §8 `hiltNavCompose = 1.2.0` |

**建议**:拼装时把 `out/gradle/libs.versions.toml` 中 `[ISSUE-01]` 注释下的条目合并进 01 的根 toml。
**最小假设**:`benchmark`/`agp`/`hiltNavCompose` 复用合同既有版本号,仅新增坐标,不改动合同版本。

## ISSUE-02 `UserRepository.progress` 无「今日」维度,首页"今日进度环"按累计进度实现

TASK.md 要求首页有「今日进度环」,合同 §5 的 `Progress(total, done, correct)` 只能表达科目累计。
**最小假设**:首页进度环展示当前科目的累计学习进度(文案为「学习进度」),并在 DONE.md 中注明。
**建议**:合同增补按天统计 API(如 `progressDaily(scope): Flow<Progress>`),或在 `UserRepository.recordAnswer` 增加时间戳语义。

## ISSUE-03 考试错题入错题本的路径不明确

合同 §5 `UserRepository.recordAnswer(question, chosen, mode)` 是逐题记录;模拟考试按合同 §4 由 `ExamService.grade` 批量评分、`saveExam` 一次性落库。
**最小假设**::app 在交卷成功后,对 `ExamResult.wrongIds` 逐题调用 `recordAnswer(q, 考生作答, PracticeMode.EXAM)` 使错题进入错题本(不影响已保存的 ExamResult)。01 的真实实现请确保 `mode=EXAM` 的调用同样遵循「答错入错题本、连对 3 次移出」语义,且不重复扣分。

## ISSUE-04 `ExamResult` 无按 id 查询 API

成绩页路由只有 `examId`,合同没有 `getExamById`;只能订阅 `exams(subject)` 全量流再过滤。
**最小假设**:用 `exams(subject).map { list -> list.firstOrNull { it.id == examId } }` 实现,O(n) 可接受。
**建议**:合同增加 `suspend fun examById(id: Long): ExamResult?`。

## ISSUE-05 独立开发脚手架(COMMANDS.md)缺仓库配置与部分 stub

COMMANDS.md 的 `_standalone/settings.gradle.kts` 配方缺少 `pluginManagement` 与依赖仓库声明(无法解析插件/依赖),且只包含 `:app :core:model`,而 TASK 验收要求 `:app` 的 build.gradle 只依赖 `:core:{model,data,media,update}` 四个模块。
**处理**:本目录 `_standalone/settings.gradle.kts` 已扩展:补齐仓库(阿里云镜像优先,官方仓库兜底——本机无法直连 Maven Central),并为 `:core:data / :core:update` 提供空 Android 库 stub、为 `:core:media` 提供导出合同签名 `QuizMedia/MediaViewer` 的 stub。COMMANDS.md 非冻结合同,故直接改进而未另行记录。

## ISSUE-06 `:core:media` stub 与真实实现的拼装提示

独立开发期 `:app` 依赖 `_standalone/media_stub`(占位实现,矢量占位图,无真实解码)。拼装时 settings 指向真实 `:core:media` 后,`:app` 源码无需任何修改(`QuizMedia/MediaViewer` 签名与合同一致)。若保留 `app/src/debug` 的 Fake 绑定会与 01/03/04 的 Hilt 模块绑定冲突,拼装 debug 变体时需删除 `app/src/debug/java/com/me/jiakao/fake/`(详见 DONE.md 拼装注意事项)。

## ISSUE-07 `QuizRepository.ids` 的排序稳定性依赖实现

随机练习/专项练习依赖 `ids` 返回「稳定顺序」(合同注释),`savePosition/loadPosition` 的 key 由 :app 自定义(含 mode/subject/chapter/special)。请 01 的实现保证同一 scope+filter 下 `ids` 结果顺序稳定(建议按 id 排序),否则恢复进度会错位。
