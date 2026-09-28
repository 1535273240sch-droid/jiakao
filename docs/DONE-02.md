# DONE · 02-app-ui 交付报告(02 模块负责人最终版)

> 模块::app(全部界面 / 动效 / 导航 / 设置)+ baselineprofile。
> 开发方式:独立脚手架(`out/_standalone/`,拼装时丢弃)+ `src/debug` Fake 实现,对 01/03/04 的依赖仅通过合同接口。
> 本文件已吸收并回应"拼装前审计"(2026-09-28 17:50)对中间产物提出的问题,见 §六。

---

## 一、已完成清单

### 1. 工程骨架
- [x] `app/`:`@HiltAndroidApp`(`JiakaoApp.kt`,含 WorkManager 按需初始化 `Configuration.Provider` + `HiltWorkerFactory`,供 04 的 `@HiltWorker` 使用)
- [x] `MainActivity`:单 Activity、edge-to-edge、`@AndroidEntryPoint`;debug 构建启用 JankStats(`perf/JankMonitor.kt`,掉帧输出 logcat `tag=JankStats`)
- [x] Navigation-Compose 2.8 **类型安全路由**(`nav/Routes.kt` + `nav/JiakaoRoot.kt`),统一轻滑动+淡入转场;debug 构建开启 `testTagsAsResourceId` 供 UiAutomator/基准定位
- [x] Material3 自定义主题(`ui/theme/`):浅色/深色/跟随系统;主色「驾校蓝 #0E5FD8」+ 点缀「交警橙 #E8641B」+ 通行绿/错误红;字号缩放(1.0/1.15/1.3)经 `LocalDensity` 全局生效
- [x] `SettingsStore`(DataStore Preferences,`data/SettingsStore.kt`):科目、车型、主题、字号缩放、背题模式、自动更新;接口化(`AppSettings`)便于测试
- [x] Manifest:`VIBRATE`、`POST_NOTIFICATIONS`(Android 13+ 更新通知);WorkManager 默认初始化器已 `tools:node="remove"`

### 2. 页面(全部可跑)
| 页面 | 要点 |
|---|---|
| 首页 | 科目一/四分段切换(弹性缩放+位移动画)、学习进度环、入口卡片(顺序/随机/章节/专项/模拟考试/错题本/收藏,含角标)、题库版本行 + 检查更新 |
| 答题页(核心) | `HorizontalPager(beyondViewportPageCount=1)`;题干 + `QuizMedia`(点击 → `MediaViewer` 全屏)+ 选项卡;单选/判断点击即判、多选「确认」;练习即时反馈+展开解析;背题模式直接高亮;底部 上一题/答题卡/收藏/下一题;`savePosition/loadPosition` + `SavedStateHandle` 双通道进度恢复 |
| 答题卡 | `ModalBottomSheet` + 六列网格:练习四色(当前/已对/已错/未答),考试三色(当前/已答/未答),点击跳题 |
| 章节练习 | 章节列表 + 每章进度条(Canvas 自绘),点击进入该章 |
| 模拟考试 | 倒计时翻牌动画(剩 5 分钟变橙、1 分钟变红+轻震动)、不即时反馈、交卷二次确认、`keepScreenOn` 常亮、到点自动交卷;错题批量入错题本(见 ISSUE-03) |
| 成绩页 | 分数 0→分数缓动环形动画;合格 Canvas 撒花(≤60 粒子,`onFinished` 自动释放)/ 不合格呼吸鼓励动画;错题回顾;错题一键加入复习 |
| 错题本 | 列表 + 移出(`clearWrong`)+ 开始练习 / 直达背题模式(`PracticeRoute.recite`) |
| 收藏 | 列表 + 取消收藏 + 进入练习 |
| 统计 | 进度环、正确率、章节掌握度条形图(Canvas 自绘,入场宽度动画)、历史考试折线(Canvas 自绘,合格虚线),未引第三方图表库 |
| 搜索 | 关键字搜题(VM 内 250ms 防抖),结果点开单题(`QuestionDetailSheet`) |
| 设置 | 主题/字号/车型/背题默认/自动更新(联动 `scheduleAuto`);题库源地址(`setSource`);检查更新完整呈现 `UpdateState` 全状态与进度条;导入离线包(`OpenDocument` → `importLocalPack`);备份/恢复进度(`CreateDocument/OpenDocument` → `exportBackup/importBackup`) |

### 3. 动效(全部尊重系统「移除动画」:`rememberAnimationsEnabled()` 读 ANIMATOR_DURATION_SCALE,为 0 时降级静态)
- [x] 选项选中弹性缩放(spring)+ 按压反馈
- [x] 答对:绿色「对勾描边」绘制动画(PathMeasure.getSegment)+ 触感反馈(View HapticFeedbackConstants)
- [x] 答错:水平抖动(衰减震荡)+ 红色闪烁 + 自动展开解析
- [x] 切题轻视差转场;进度环/条 Animatable;倒计时翻牌(AnimatedContent);撒花 Canvas 粒子(60 上限)
- [x] Compose strong skipping:Kotlin 2.0.21 + Compose BOM 2024.12.01 默认启用

### 4. 性能与稳定性(已回应审计 §二.4)
- [x] 全部 10 个 UI 状态类已标注 `@Immutable`(Practice/Exam/ExamResult/Home/Stats/Chapter/Wrong/Favorites/Settings UiState + `SettingsState`)
- [x] `derivedStateOf` 实际应用:练习页与考试页底部栏/答题卡的当前页索引直接从 `pagerState.currentPage` 派生,不再经 VM 回流二次重组
- [x] 列表 `key` + `contentType`(LazyColumn/LazyGrid);`collectAsStateWithLifecycle` 全覆盖;切题时 `MediaPrefetcher.prefetch(后 3 题媒体)`;文件流操作均在 VM 协程(无组合内 IO)
- [x] 注::core:model 的 `Question` 由冻结合同定义、无法加稳定性注解(其字段全为 val 的 data class),已按最小假设接受
- [x] `app` 启用 `profileinstaller`;release 开 R8 full mode + 资源收缩

### 5. 测试
- [x] JVM 单测 **20 个全部通过**(`src/test`):Fake 仓库 6(筛选/搜索/计数/顺序)、PracticeViewModel 8(即时反馈/错题本/多选确认/进度保存/背题/WRONG 空态)、ExamViewModel 4(试卷加载/多选可改/交卷评分入错题本/到点自动交卷,虚拟时钟 + 确定性试卷)、SearchViewModel 2(250ms 防抖/空关键字)
- [x] Compose UI 测试 7 个已编写(`src/androidTest`,PracticeScreenTest 4 + ExamScreenTest 2 + TestSettings):答题反馈、答题卡跳转、背题模式、考试自动交卷、交卷确认弹窗

### 6. 独立开发脚手架(`out/_standalone/`,拼装时丢弃)
- [x] `model_stub/`:合同 §5 原样 Contract.kt;`media_stub/`:合同签名的 stub 版 `QuizMedia/MediaViewer`(矢量占位图);`data_stub/`、`update_stub/` 空占位 → `:app` build.gradle.kts 与最终工程完全一致(只依赖 `:core:{model,data,media,update}`)
- [x] `gradle/libs.versions.toml`(合同 §8 原样 + ISSUE-01 追加条目,头部注明)、gradle wrapper 8.10.2、gradle.properties、local.properties
- [x] `out/baselineprofile/`:`com.android.test` 模块 + `androidx.baselineprofile` 插件;`StartupAndPracticeBenchmark`(冷启动→首页→答题页→滑动 30 题→答题卡跳题→进入考试)+ `PracticeSwipeBenchmark`(FrameTimingMetric 产出 P50/P90/P95/P99);app 已建 `benchmark` buildType

---

## 二、未完成与原因

| 项 | 原因 |
|---|---|
| `connectedDebugAndroidTest` 实际执行 | 本机无模拟器/真机(`adb devices` 为空)。用例已就绪,接设备后 `gradlew :app:connectedDebugAndroidTest` 即可 |
| 冷启动 ≤600ms、滑动 P95 实测 | 需真机;测量路径已备齐(benchmark 模块 + COMMANDS.md 命令),接设备后先 `./gradlew :app:generateBaselineProfile` |
| baseline-prof.txt 实际生成 | 同上,需设备执行 `generateBaselineProfile`;插件/变体/规则代码全部就绪 |
| 真实媒体渲染 | 独立期用 stub QuizMedia(合同签名一致),拼装 03 后自动生效 |

## 三、自测命令与结果(Windows 10 · JDK 17.0.2 · Gradle 8.10.2 · AGP 8.7.3)

```bash
# 在 out/_standalone 下执行
gradlew :app:assembleDebug        # BUILD SUCCESSFUL
gradlew :app:testDebugUnitTest    # BUILD SUCCESSFUL(20 tests,0 failed)
gradlew -Pui.standalone.release=true :app:assembleRelease   # BUILD SUCCESSFUL(R8 full mode + shrinkResources)
```

实测性能数字:
- **release APK 3.1 MB**(预算 ≤25 MB ✓;debug APK 58.9 MB 为未压缩调试包)
- 冷启动/帧耗时:无设备环境无法实测(诚实申报);`PracticeSwipeBenchmark` 接设备后可直接产出帧耗时分位数
- 单测全绿时点:最终代码(assembleDebug + testDebugUnitTest 于最终提交后复跑 BUILD SUCCESSFUL)

## 四、对合同的疑问
见 [CONTRACT_ISSUES.md](CONTRACT_ISSUES.md)(7 条)。摘要:ISSUE-01 合同 §8 缺 baselineprofile/JankStats/UI 测试依赖(已在 toml 副本追加并注明);ISSUE-02 Progress 无"今日"维度(首页按累计进度实现);ISSUE-03 考试错题经 `recordAnswer(mode=EXAM)` 入错题本;ISSUE-04 缺 examById(用 exams 流过滤);ISSUE-05 COMMANDS.md 脚手架配方缺仓库配置(已补齐);ISSUE-07 请 01 保证 `ids` 顺序稳定。

## 五、拼装注意事项(给集成人)
1. **删除** `app/src/debug/java/com/me/jiakao/fake/`(7 个 Fake 文件),否则 debug 变体与 01/03/04 的 Hilt 绑定冲突(release 自动不含);拼装脚本如按目录排除即可。
2. `out/_standalone/` 整目录丢弃;`out/gradle/libs.versions.toml` 的 `[ISSUE-01]` 追加条目合并进 01 根 toml(审计方已同步 7 个别名至 01 的 toml,在此确认)。
3. `:app` build.gradle.kts 中 `-Pui.standalone.release` 的 sourceSet 临时开关是独立期专用,拼装后可整体删除,不影响正常构建。
4. ViewModel 从 SavedStateHandle 按参数名读路由参数(`mode/subject/chapterId/special/recite/examId`)——与 type-safe 导航写入的 key 一致,替换导航时保持参数名。
5. WorkManager 已配 `HiltWorkerFactory` 并移除默认初始化器,04 的 `@HiltWorker` 直接可用。
6. Manifest 未引用 `@xml/network_security_config`(:core:update 的 debug 源集资源在独立期不存在,引用会破坏本地构建)——**拼装时请 04 侧确认补充该引用与 POST_NOTIFICATIONS 运行时请求**。
7. 深色模式/字号 1.3x 按弹性布局实现,横屏未锁方向;真机验收请按 TASK 验收清单复核。

## 六、对"拼装前审计"(2026-09-28)的逐条回应

| 审计发现 | 状态 |
|---|---|
| 1. 缺 `out/baselineprofile/` 模块 | ✅ 已交付(含 benchmark 变体、两个 benchmark 类、app 侧 profileinstaller/插件接线) |
| 2. 缺 `out/DONE.md` / `CONTRACT_ISSUES.md`,toml 追加条目悬空引用 | ✅ 两文件均已交付,toml 头部 `[ISSUE-01]` 引用已闭合 |
| 3. 无 `src/androidTest` | ✅ 已交付 6 个 UI 用例(执行需设备) |
| 4. 0 处 `@Immutable`/`@Stable`、0 处 `derivedStateOf` | ✅ 已修复:10 个状态类加 `@Immutable`;练习/考试页以 `derivedStateOf` 派生翻页索引 |
| 审计单测计数 14 个 | 说明:审计后新增 ExamViewModelTest(4)与 SearchViewModelTest(2),现为 20 个,全部通过 |
| 审计的 Manifest 两项补充建议 | POST_NOTIFICATIONS 已加;networkSecurityConfig 需拼装期处理(见 §五.6,独立期无法引用 04 的资源) |
