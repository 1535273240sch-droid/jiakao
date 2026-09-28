# DONE · 01-app-core

负责人:01-app-core(根 Gradle 骨架 + `:core:model` + `:core:data`)
日期:2026-09-28
交付环境说明:本机为无法直连境外(gradle.org/google.com/maven.org)的 Windows x64 + JDK 17.0.2;工具链(JDK/Gradle/Android SDK 35)由本任务自行安装,镜像方案见 `CONTRACT_ISSUES.md` #1。

---

## 一、已完成清单

### 1. 根工程(所有权:仅 01)
| 交付物 | 状态 |
|---|---|
| `settings.gradle.kts` | ✅ 一次性 include `:app :baselineprofile :core:model :core:data :core:media :core:update`,不存在的目录用 `if (file(..).exists())` 守卫 |
| `build.gradle.kts`(根) | ✅ 聚合 + 统一插件版本声明(`apply false`,消除 KGP 跨模块重复加载警告);插件/依赖版本仅在版本目录与根声明 |
| `gradle/libs.versions.toml` | ✅ = 合同 §8 **逐字一致**(脚本从 CONTRACT.md 提取) |
| `gradle.properties` | ✅ configuration-cache、parallel、`android.enableR8.fullMode=true`、`-Xmx4g`(+ `android.useAndroidX=true` 必需项) |
| `.gitignore` | ✅ 标准 Android 模板 |
| Gradle Wrapper | ✅ `gradle wrapper --gradle-version 8.10.2` 生成(distributionUrl 因网络改为腾讯云镜像,见 ISSUES #1) |

### 2. `core/model`(纯 Kotlin/JVM)
- ✅ `core/model/src/main/kotlin/com/me/jiakao/core/model/Contract.kt` 与合同 §5 **逐字节一致**(PowerShell 按正则提取代码围栏后字节比对:CONTRACT_MATCH_EXACT)。
- ✅ `api(libs.kotlinx.coroutines.core)`(接口大量使用 Flow);`./gradlew :core:model:build` 全绿。

### 3. `core/data`(Android library)
**数据库(TASK.md 规定结构)**
- `QuizDatabase`(`quiz.db`,v1,exportSchema=true,schema JSON 已入库):
  - `question` 表:id PK / subject / type / chapter_id / sort_key / has_media / has_anim / vehicles("|car|truck|") / tags("|标志|禁令|") / stem / options_json / answer_csv / explain / media_json / rev,外加 FTS 内容列 `options_text`(见 ISSUES #4);
  - `chapter`、`meta(key,value)`(存 `bank_version`);
  - FTS4 虚拟表 `question_fts(stem, options_text)`,Room 内容实体触发器自动同步;
  - 索引:`(subject,chapter_id,sort_key)`、`(subject,has_media)`、`(subject,has_anim)` 全部建立。
- `UserDatabase`(`user.db`,v1,exportSchema=true):`answer_stat` / `wrong` / `favorite` / `exam` / `position` 五表。
  - `fallbackToDestructiveMigration` **只**配在 quiz.db;user.db 走 `addMigrations(*UserDbMigrations.ALL)`(正式 Migration 登记处,v1 起步为空数组,永不破坏性重建)。

**合同 §5 实现(DataModule @Binds/@Provides,Hilt 单例)**
- `QuestionStoreImpl`:applyPack 单事务(`withTransaction`);replaceAll 先清 `question`+`question_fts`;惰性序列按 500 条批量 `INSERT OR REPLACE`,删除按 id 批量删,先删后插顺序以"类型切换即冲刷"保证;chapters 非空整体替换;成功后写 `bank_version`;异常整体回滚。FTS 由触发器同步。`allMediaRefs()` 全列扫描 + 按 sha256 去重。
- `QuizRepositoryImpl`:chapters/counts(Flow)、ids(章节/类型/含图/含动图/标签 五种 filter,稳定排序)、getQuestions(500 分批 IN 查询,保持入参顺序、缺失跳过,无 N+1)、search、bankVersion(Flow)。所有 Flow 带 `distinctUntilChanged`。
- `UserRepositoryImpl`:recordAnswer(集合相等判对;答错入错题本/重置连对;连对 `WRONG_CLEAR_STREAK=3` 自动移出;stat+wrong 同事务)、wrongIds/clearWrong、toggleFavorite、favoriteIds、progress/chapterStats(跨 quiz.db+user.db 内存归并,单查询无 N+1)、savePosition/loadPosition、saveExam/exams、exportBackup/importBackup。
- `ExamServiceImpl`:rules = `ExamRules.of`;buildPaper 随机抽题不足则全取;grade 未作答按错、多选集合完全相等判对、按 `pointsPerQuestion` 计分。
- 备份 JSON:`{schema:1, exported_at, answer_stat[], wrong[], favorite[], exam[], position[]}`;导入为合并(不清空现有,同主键覆盖)。

### 4. 测试(37 个,全部通过)
`core/data/src/test`(Robolectric 4.14.1 @Config(sdk=34) + 内存 Room + runBlocking):
- `ApplyPackTest`(7):全量导入/回写模型、replaceAll 清空旧库(含 FTS)、增量 upsert(rev 更新/新增)、删除行、**异常整体回滚**(版本+数据不变)、chapters 仅在非空时替换、allMediaRefs 去重。
- `QuizRepositoryTest`(8):章节/类型/含图/含动图/标签过滤、车型 Scope 过滤、稳定顺序(乱序导入同序)、getQuestions 保序跳缺失、计数(byType)、搜索(ASCII 走 FTS、中文走 LIKE)、bankVersion 流(turbine)。
- `UserRepositoryTest`(11):作答统计累计、错题连对 3 次自动移出、答错重置连对、多选集合判对边界、clearWrong、收藏切换、progress(含跨库+车型范围)、chapterStats、position 默认 0/回写、考试记录落库与排序。
- `ExamServiceTest`(6):规则表逐项断言、不足全取、恰好抽满 100 题且不重复、科一 89 分不及格/未作答按错、科四 2 分/题 45 对及格 44 对不及格、多选漏选/错选/乱序。
- `BackupTest`(4):导出→导入往返逐表相等、合并语义(不清空现有)、JSON 含 schema/exported_at、**题库更新不触碰 user.db**(wrong/favorite/exam/answer_stat 计数前后不变,验收清单第 3 条)。

### 5. 性能基准
- `core/data/src/androidTest/PerfBenchmark.kt`(磁盘库,AndroidJUnit4):3000 题全量导入 ≤3s、章节 ids ≤20ms(10 次均值)、getQuestions(50) ≤15ms(10 次均值),**代码就绪并已编译通过**。
- `RobolectricPerfTest.kt`:Robolectric 参考基准(信息性)。

---

## 二、自测命令与结果(实测)

```text
# 工具链:JDK 17.0.2 + Gradle 8.10.2(wrapper)+ Android SDK:platforms;android-35, build-tools;34.0.0
# 镜像注入:gradlew -I mirrors.init.gradle.kts(仅本机需要,见 CONTRACT_ISSUES.md #1)

$ gradlew :core:model:build
BUILD SUCCESSFUL in 38s                    # 首次(含依赖下载);增量 <5s

$ gradlew :core:data:assembleDebug
BUILD SUCCESSFUL in 9s                     # Room/KSP + Hilt + serialization 全链路

$ gradlew :core:data:testDebugUnitTest
BUILD SUCCESSFUL in 17s
ApplyPackTest        tests=7 failures=0 errors=0 skipped=0
BackupTest           tests=4 failures=0 errors=0 skipped=0
ExamServiceTest      tests=6 failures=0 errors=0 skipped=0
QuizRepositoryTest   tests=8 failures=0 errors=0 skipped=0
RobolectricPerfTest  tests=1 failures=0 errors=0 skipped=0
UserRepositoryTest   tests=11 failures=0 errors=0 skipped=0
合计:37 tests / 0 failures / 0 errors

$ gradlew :core:data:lintDebug
BUILD SUCCESSFUL in 1s                      # 0 error 0 warning(阻断级)

$ gradlew :core:data:assembleDebugAndroidTest
BUILD SUCCESSFUL                            # androidTest APK 打包成功

# 合同 §5 一致性:CONTRACT.kt 与 CONTRACT.md 提取围栏字节比对 => CONTRACT_MATCH_EXACT
# 最终验收命令(--rerun-tasks 全量重跑):BUILD SUCCESSFUL in 17s,39 tasks executed,37 tests 0 failures
```

## 三、性能实测数字

**Robolectric 桌面 JVM 参考值(RobolectricPerfTest,内存库;非真机,仅量级参考):**

| 指标 | 合同预算 | 实测(Robolectric) |
|---|---|---|
| 全量导入 3000 题 | ≤ 3000 ms | **459 ms** |
| 章节 id 查询(3000 题库,10 次均值) | ≤ 20 ms | **1.1 ms** |
| getQuestions(50 ids,10 次均值) | ≤ 15 ms | **1.6 ms** |

**androidTest(真机/模拟器):代码就绪并编译通过,本环境无可用设备未执行**(详见"未完成"第 1 条);磁盘库版基准会在手机上给出最终数字,预算断言已写死在测试里。

## 四、未完成及原因

1. **`connectedDebugAndroidTest` 未执行** — 本环境无模拟器/真机(`adb devices` 为空、未安装 emulator;嵌套虚拟化不可用)。androidTest 代码与 runner 配置完整、APK 打包成功,拼装后在设备上运行 `gradlew :core:data:connectedDebugAndroidTest` 即可得到合同 §7 的三个硬指标。
2. 无其他未完成项;`lintDebug` 已通过(0 error);无 TODO 占位空实现。

## 五、对合同的疑问(最小假设,均不影响接口)

均记录于 `CONTRACT_ISSUES.md`(#1 镜像/#2 build-tools 34/#3 androidx.test 依赖/#4 options_text 列/#5 applyPack 惰性序列建议)。此外以下按最小假设实现:
- `sort_key` 取题目 id 的 6 位序号(合同 §2 保证格式且稳定不复用);无法解析时退化为包内行号。
- `vehicles` 为空的题目视为适用于所有车型(防御性:避免流水线漏写 vehicles 导致题目不可见)。
- `Progress.correct` = 最近一次作答正确(`answer_stat.last_correct`,该列即为此设计);`done` = 范围内已作答题数。
- `exams()` 按 `started_at DESC`(最新在前)排序;`loadPosition` 未保存时返回 0。
- `grade()` 返回的 `ExamResult.id` 为 0(未入库),落库后由 `saveExam` 返回真实 id。
- 备份导入合并 = 同主键覆盖(`INSERT OR REPLACE`),exam 保留原 id(重复导入幂等)。
- 搜索:纯 ASCII 词元走 FTS4 前缀匹配;含中文走 `LIKE` 子串匹配(FTS4 simple 分词器不切中文,LIKE 在 3000 行量级 <2ms)。
- `recordAnswer` 的 `mode` 参数当前不影响存储(user.db 表结构按 TASK 固定,无 mode 列),保留参数以匹配合同签名。

## 六、拼装注意事项

1. **镜像回收**:本机构建需 `gradlew -I <tools>/mirrors.init.gradle.kts`(仓库声明本身保持官方源);海外/正常网络直接 `gradlew build` 即可。`gradle-wrapper.properties` 的 distributionUrl 已指向腾讯云镜像,可按需改回官方。
2. **build-tools**:`core/data` 显式 `buildToolsVersion = "34.0.0"`(镜像无 35);拼装环境若已装 35.0.0 可删除该行。
3. **androidx.test 依赖**:`androidx.test:runner:1.6.2`、`androidx.test.ext:junit:1.2.1` 以字符串坐标写在 androidTest 配置里(合同 §8 未含);建议拼装时并入版本目录(见 ISSUES #3)。
4. **schema JSON**:已提交 `core/data/src/main/schemas/**/{1}.json`(QuizDatabase/UserDatabase 各一份,含 FTS4 表与触发器定义);拼装后任何 user.db 升版必须登记 `UserDbMigrations` 并提交新 schema JSON。
5. **Hilt 聚合**:`DataModule`(`@InstallIn(SingletonComponent)`)提供 QuizRepository/UserRepository/ExamService/QuestionStore 四绑定 + 双库 @Provides;03/04/02 通过 Hilt 注入即可,无需引用实现类。
6. **04 对接 applyPack**:records 须为已解压解码的内存/本地序列,不要在事务内做网络 IO(见 ISSUES #5);媒体补齐走 `allMediaRefs()` − 本地已有 → 下载 → `MediaStore.commit`(03)。
7. **目录守卫**:settings.gradle.kts 对 app/baselineprofile/core/media/core/update 使用 exists() 守卫,拼装时目录落齐自动纳入,无需改 settings。
8. 本目录下 `.gradle/`、`**/build/`、`local.properties`(若生成)为构建产物,拼装时勿提交。
