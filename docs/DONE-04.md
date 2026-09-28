# DONE · 04-update-engine（`:core:update`）

**结论**：11 项交付物全部落地（25 个主源码文件 + 8 个测试文件共 88 个单测 + 23 个夹具文件），
合同 §3「更新语义」逐条实现。
**但本机没有 JDK / Android SDK / Gradle，也没有外网**，所以 `./gradlew :core:update:testDebugUnitTest`
**没有实际跑过** —— 这一点在下面「自测结果」里如实说明，并给出了我在本机能做的等价验证与待跑命令。
`out/CONTRACT_ISSUES.md` 有 1 项需要人裁决的拼装必做项（`androidx.hilt:hilt-compiler`）。

---

## 1. 交付物清单（全部在 `out/`）

### 1.1 最终产物 `out/core/update/`（拼装时整体合并进 `core/update/`）

| 文件 | 对应 TASK 交付物 | 职责 |
|---|---|---|
| `src/main/kotlin/.../pack/PackFormat.kt` | 1 | `Manifest` / `ManifestChapter` / `FileRef` / `DeltaRef` / `MediaEntry` / `PackFormatException`（带行号） |
| `src/main/kotlin/.../pack/PackDto.kt` | 1 | 题目行内部 DTO 与**严格校验**（合同 §2 的每一条取值规则），`QuestionDto.toRecord(line)` |
| `src/main/kotlin/.../pack/PackCodec.kt` | 1 | `parseManifest` / `parseLine` / `parseMediaIndex` / `stream`（GZIP 自动识别 + 逐行惰性） |
| `src/main/kotlin/.../net/Downloader.kt`、`net/OkHttpDownloader.kt`、`net/DownloadException.kt` | 2 | `.part` + `Range` 续传、`sha256`/`bytes` 校验、指数退避（仅网络/5xx/408/429）、进度回调、取消安全、`If-None-Match` |
| `src/main/kotlin/.../plan/Plan.kt`、`plan/UpdatePlanner.kt` | 3 | 纯函数 `plan(localVersion, manifest)`：`UpToDate / Deltas / Full`（+ `Unavailable`，理由见合同问题 2） |
| `src/main/kotlin/.../BankUpdaterImpl.kt`、`UpdateEngine.kt` | 4 | 状态机、`check()` 只取 manifest、`startUpdate()` 入队加急前台任务、单事务导入、成功后媒体补齐、`min_app_version_code` 门禁 |
| `src/main/kotlin/.../MediaSync.kt` | 5 | 并发 4 补齐媒体、优先级排序、失败仅计数、全部就绪才 `retain` |
| `src/main/kotlin/.../LocalPackImporter.kt` | 6 | 离线整包两遍策略导入（先落盘校验，后导入，最后提交媒体） |
| `src/main/kotlin/.../UpdateWorkScheduler.kt`、`UpdateWorker.kt`、`AutoUpdateWorker.kt` | 4、7 | WorkManager 加急前台任务（同名唯一 `KEEP`）、24h 周期任务（`UNMETERED` + 电量不低） |
| `src/main/kotlin/.../UpdateNotifier.kt` + `src/main/res/values/strings.xml` | 7 | 前台通知与「有新版本」通知，含 Android 13 `POST_NOTIFICATIONS` 兼容 |
| `src/main/kotlin/.../di/UpdateModule.kt`、`di/UpdateQualifiers.kt` | 8 | Hilt 装配：`BankUpdater`、自带 `OkHttpClient`（15s/30s + UA）、DataStore（`sourceUrl`） |
| `src/main/AndroidManifest.xml` | 8、9 | 权限与 WorkManager 前台服务 `dataSync` 类型 |
| `src/debug/AndroidManifest.xml`、`src/debug/res/xml/network_security_config.xml`、`src/debug/kotlin/.../DebugUpdateReceiver.kt` | 9 | debug 放行 `10.0.2.2`/`localhost`/局域网明文 + `adb` 调试广播（release 不含） |
| `src/test/kotlin/**`（7 个测试类 + `TestSupport.kt`）、`src/test/resources/fixtures/**` | 10 | 88 个单测 + 完整小型题库包夹具 |
| `build.gradle.kts` | — | 模块构建（依赖全部来自合同 §8，仅新增 hilt-compiler，见合同问题 1） |

### 1.2 独立开发脚手架 `out/_standalone/`（拼装时被 `assemble.sh` 丢弃）

- `settings.gradle.kts`（`:core:model` → `model_stub`、`:core:update` → `../core/update`）、
  `build.gradle.kts`、`gradle.properties`、`model_stub/`（合同 §5 源码原样抄录）
- `out/gradle/libs.versions.toml`（合同 §8 原样 + 1 项新增）
- `_standalone/tools/gen_fixtures.py`（COMMANDS.md 的 python3 版本）
  与 `gen_fixtures.ps1`（**本机实际使用的 PowerShell 版本**，见下）

---

## 2. 关键设计

### 2.1 状态机映射（TASK 交付物 4）

| 入口 | 状态序列 |
|---|---|
| `check()` | `Checking → Available(from,to,downloadBytes)` / `UpToDate(v)` / `Failed(msg,retryable)` |
| `startUpdate()` | 立即 `DownloadingPack(0, 预估字节)` → 入队 WorkManager（同名唯一 `KEEP`，可重入） |
| `UpdateEngine.runPendingUpdate()`（Worker 内） | `Checking → DownloadingPack → Importing → DownloadingMedia → UpToDate`；失败 `Failed` |
| `importLocalPack()` | `Importing → DownloadingMedia → UpToDate` / `Failed` |

`check()` 得到的 manifest 缓存在内存里给 `startUpdate()` 复用（省一次请求）；进程被杀后
Worker 会重新拉 manifest（带 `If-None-Match`），因此「杀进程 → 重试」不需要任何额外状态。

### 2.2 数据安全（PROMPT 硬性约束）

- **单事务**：题目只经 `QuestionStore.applyPack(...)` 一次性导入；失败时 01 回滚，**版本号只在成功后推进**。
  单测用 `FakeQuestionStore`（真的实现了「副本提交」的事务语义）验证「导入中途抛异常 → 版本与题目都不变」。
- **先校验后使用**：所有下载写 `cacheDir/dl/{key}.part`，`sha256`（+`bytes`）通过才 rename 成正式文件；
  校验失败删除断点，**取消/网络中断则保留断点**（下次 `Range` 续传）。
- **不重复下载**：`key` 用内容 sha256；已校验通过的临时文件在重试时直接复用（单测断言「零请求」）。
- **不触碰 `user.db`**：本模块只依赖 `QuestionStore` / `MediaStore` 两个接口，无任何数据库代码。
- **媒体失败不阻塞题目**：合同 §3.4；失败仅计数并在下次 `check()`/自动更新时重试（UI 显示占位）。

### 2.3 流式（内存平稳）

`PackCodec.stream()` = `GZIPInputStream` + `BufferedReader` + `sequence { yield(...) }`，
逐行解析、**不整体读入内存**；离线包也是「边解 zip 边落盘 + 边算 sha256」。
单测 `流式解析是惰性的` 用「5000 行里第 4000 行是坏行」证明：只取前 10 行不会报错，
迭代到底才在第 4000 行失败。

### 2.4 可测性设计

凡是需要 Android 的东西都放在接口后面，因此状态机可以在纯 JVM 单测里跑完整流程：

| 接口 | 生产实现 | 单测替身 |
|---|---|---|
| `Downloader` | `OkHttpDownloader` | `FakeDownloader`（观察并发/顺序/失败） |
| `UpdateSettingsSource` | `UpdateSettings`（DataStore） | `FakeUpdateSettings` |
| `UpdateWorkScheduler` | `WorkManagerUpdateScheduler` | `FakeWorkScheduler`（断言入队次数/参数） |
| `UpdateNotifier` / `AppVersionProvider` / `NetworkStateProvider` / `UpdateLogger` | Android 实现 | 固定/空实现 |

---

## 3. 自测结果

### 3.1 本机实际执行过的两件事

**(a) 夹具生成（已跑通）** — 本机无 `python3`，因此用等价的 PowerShell 版本：

```powershell
powershell -ExecutionPolicy Bypass -File out/_standalone/tools/gen_fixtures.ps1
```

输出（脚本自带自查）：

```
▶ 生成夹具 -> out/core/update/src/test/resources/fixtures
  ✓ manifest.json  bank_version=2 min_app=1
  ✓ manifest-v1.json / manifest-nofull.json / manifest-chain-broken.json
  ✓ manifest-delta-heavy.json / manifest-cycle.json / manifest-minapp.json / manifest-bad-schema.json
  ✓ full\bank-v1.jsonl.gz  lines=5
  ✓ full\bank-v2.jsonl.gz  lines=5
  ✓ delta\v1-v2.jsonl.gz   lines=3
  ✓ contract-sample.jsonl  lines=3 options[0].text=正确     ← 中文未乱码
✅ fixtures ok
```

夹具（`core/update/src/test/resources/fixtures/`，23 个文件、共约 72 KB 的确定性数据）：

- `manifest.json`（v2：全量 990B + 增量 628B → 满足「增量更小」）、`manifest-v1.json`、
  `manifest-nofull.json`、`manifest-chain-broken.json`、`manifest-delta-heavy.json`、
  `manifest-cycle.json`、`manifest-minapp.json`、`manifest-bad-schema.json`
- `full/bank-v{1,2}.jsonl.gz`、`delta/v1-v2.jsonl.gz`、`bundle/bundle-v2.zip`、
  `bundle/bundle-v2-corrupt.zip`、`broken/{bad-json,bad-field}.jsonl.gz`、`broken/truncated.gz`、
  `broken/no-bank.zip`、`media/{4 个内容寻址媒体}`、`media-wrong/{错 sha 内容}`、
  `contract-sample.jsonl`（合同 §2 三行逐字）
- 单测**不硬编码任何 sha**，全部从夹具现场计算 → 两个生成器（py/ps1）产物都可用

**(b) 静态自检（已跑通）** — 因为没有编译器，我写了脚本做结构级验证：

```
检查 Kotlin 文件数：34
OK 全部静态检查通过
```

检查项：每个 `.kt` 的 `{}`/`()` 平衡（剥掉字符串/字符/注释）、
**49 处 `Fixtures.*(...)` 引用的夹具文件确实存在**、
**所有 `com.me.jiakao.*` 的 import 都能在源码里找到对应声明**（含与合同 §5 桩的一致性）、
所有 `R.string.*` 都在 `strings.xml` 里有定义、4 个 XML 合法、
文件与类型名一致、无 `TODO(`/`NotImplementedError`。

### 3.2 没有执行的部分（原因 + 待跑命令）

`./gradlew :core:update:testDebugUnitTest` **未执行**：本机 `java`/`javac`/`gradle`/`python3`
均不存在（`where java` 无结果），没有 Android SDK（`%LOCALAPPDATA%\Android\Sdk` 不存在），
且 `dl.google.com` / `repo1.maven.org` / `services.gradle.org` 全部不可达（无外网，无法拉依赖）。
这是环境限制，不是代码问题。

请在具备 **JDK 17+ / Android SDK 35 / 首次联网** 的机器上执行：

```bash
cd out
# 1) 重新生成夹具（可选；本仓库已包含）：python3 _standalone/tools/gen_fixtures.py
# 2) 独立构建（首次会自动下载 Gradle 8.10.2 与依赖）
gradle wrapper --gradle-version 8.10.2 -p _standalone     # 或直接用系统 gradle
./_standalone/gradlew -p _standalone :core:update:testDebugUnitTest
./_standalone/gradlew -p _standalone :core:update:lintDebug
```

### 3.3 单测覆盖对照（88 个 `@Test`）

| TASK 验收要求 | 对应测试 |
|---|---|
| 三种 Plan | `UpdatePlannerTest`（含 12 例：等值/回滚/无本地/连续链/链断/增量过重/无全量/成环不死循环/多段链/同 from 取首个） |
| 断点续传 | `OkHttpDownloaderTest.已有断点时带 Range 续传`、`服务端忽略 Range 时从头重下`、`断点比服务端文件还长则丢弃断点重来`、`取消下载会保留断点且下次可续传`、`已校验通过的临时文件直接复用不重复下载`；`BankUpdaterImplTest.断网重试可续传不重复下载已校验通过的部分` |
| 校验失败 | `sha256 不匹配时报不可重试错误并删除断点`、`服务端返回内容比声明长时报不可重试错误`、`BankUpdaterImplTest.题库包内容与清单 sha 不符时报不可重试错误` |
| 事务回滚（导入中途抛异常版本不变） | `BankUpdaterImplTest.导入中途失败 版本不变且媒体不落盘`、`LocalPackImporterTest.题目数据损坏时回滚且版本不变` |
| 媒体失败不影响题目 | `BankUpdaterImplTest.媒体失败不影响题目导入`、`MediaSyncTest.单个媒体失败只计数不影响其它媒体` |
| 离线包正常 / 损坏 | `LocalPackImporterTest`（7 例：正常、损坏包、缺 `bank.jsonl`、非 zip、题目行损坏、App 版本过低、条目乱序） |
| `min_app_version_code` | `BankUpdaterImplTest.min_app_version_code 高于本机时提示升级 App`、`LocalPackImporterTest.App 版本过低时拒绝导入` |
| 与合同 §3 示例 manifest 字节级兼容解析 | `PackCodecTest.合同示例三行逐字解析`（`contract-sample.jsonl` 逐字段断言）、`清单解析正确且 sha 与夹具文件一致` |
| 流式 + 批量调用次数 | `PackCodecTest.流式解析是惰性的`、`BankUpdaterImplTest.3000 题全量包流式导入且只调用一次 applyPack` |
| 状态机映射 | `BankUpdaterImplTest.全量更新 下载导入媒体与状态机`（断言 `Checking→DownloadingPack→Importing→DownloadingMedia→UpToDate` 顺序） |

**未覆盖（需要设备/Robolectric，本模块刻意把它们挤出核心逻辑）**：
`WorkManagerUpdateScheduler` 的真实入队、`UpdateSettings` 的 DataStore 读写、
`AndroidUpdateNotifier` 的通知构建、`UpdateWorker`/`AutoUpdateWorker` 的 `@HiltWorker` 实例化、
`DebugUpdateReceiver`。这些是薄适配层，逻辑都在被单测覆盖的接口实现里。

### 3.4 端到端演练（fixtures 版，**逻辑推演**，等待在设备上复核）

05 的 `dist/` 尚未产出（05 的 `out/` 为空），所以按 TASK 用 fixtures 演练。
把 `out/core/update/src/test/resources/fixtures` 当静态服务端（正是合同 §3 的形态）：

```bash
python3 -m http.server 8000 --directory out/core/update/src/test/resources/fixtures
# App 内填 http://10.0.2.2:8000/  →  检查更新 → 更新
```

| 步骤 | 期望结果 | 被哪个测试钉住 |
|---|---|---|
| 首次（本地 0）检查 | `Available(0, 2, 990B)` | `本地无数据时提示全量更新` |
| 首次更新 | 全量包 + 3 张媒体落地，版本 → 2 | `全量更新 下载导入媒体与状态机` |
| 发新版走增量 | 本地 1 → `Available(1,2,628B)`，**只下 delta**，被删题目消失 | `增量更新 走 delta 而不是全量` |
| 更新中杀进程 | `.part` 保留，重试带 `Range` 续传 | `断网重试可续传…`、`取消下载会保留断点…` |
| 离线包 | `bundle/bundle-v2.zip` 导入成功；`bundle-v2-corrupt.zip` 明确报错且数据不变 | `LocalPackImporterTest` |
| min_app_version | `manifest-minapp.json` → 「请先升级 App」（不可重试） | `min_app_version_code 高于本机时提示升级 App` |

设备上人工复核还没做（需要能跑 Gradle 的机器 + 模拟器）。调试期可用
`adb shell am broadcast -a com.me.jiakao.DEBUG_CHECK_UPDATE`（debug 变体已实现）。

---

## 4. 对合同的疑问

见 **`out/CONTRACT_ISSUES.md`**（9 条）。其中**拼装必做**的只有 1 条：

> §8 缺少 `androidx.hilt:hilt-compiler`。不补的话 `@HiltWorker` 不生效，
> `HiltWorkerFactory` 运行时抛 `Could not instantiate …UpdateWorker`（编译期不报错）。

---

## 5. 拼装注意事项

1. **别合并 `_standalone/`**：`assemble.sh` 已排除；`out/gradle/libs.versions.toml` 也会被
   `--exclude='./gradle'` 排除，不会覆盖 01 的版本目录。合同问题 1 的那一行要**手动加到 01 的
   `gradle/libs.versions.toml`**。
2. **:app 侧三件事**（详见 `CONTRACT_ISSUES.md` §9）：
   `Application` 实现 `Configuration.Provider` + `HiltWorkerFactory`；debug 清单引用
   `android:networkSecurityConfig="@xml/network_security_config"`（资源来自本模块 debug 源集）；
   Android 13+ 请求 `POST_NOTIFICATIONS`。
3. **03 的 `MediaStore` / 01 的 `QuestionStore` 必须提供无限定符绑定**（04 按无限定符注入）。
   反过来，04 的 `OkHttpClient` 用了 `@UpdateHttp` 限定符，不会和 03 的撞 `DuplicateBindings`。
4. **01 的 `applyPack` 实现要点**：单事务、`replaceAll=true` 先清空题表、`chapters != null` 时整体替换章节、
   `records` **完整迭代或抛异常**（见 `CONTRACT_ISSUES.md` §4）。
   03 的 `commit` 要支持 `tmp` 在 `cacheDir`（跨目录移动，见 §3）。
5. **权限已由本模块清单声明**（INTERNET / ACCESS_NETWORK_STATE / FOREGROUND_SERVICE /
   FOREGROUND_SERVICE_DATA_SYNC / POST_NOTIFICATIONS），并对 WorkManager 的
   `SystemForegroundService` 补了 `foregroundServiceType="dataSync"`（Android 14 必需）。
6. **通知文案**在 `core/update/src/main/res/values/strings.xml`（`jiakao_update_*`），
   :app 若已有同名资源请注意覆盖关系。
7. 本模块 `apply` 了插件 `kotlin-serialization`、`ksp`、`hilt`；拼装后模块 `build.gradle.kts`
   的 `plugins {}` 与根工程版本目录保持一致即可。
8. 升级依赖时注意：`kotlinx-serialization`、`okhttp`、`workmanager`、`datastore` 都在 §8 目录里，
   04 没有使用 §8 之外的第三方库（hilt-compiler 例外，见上）。

---

## 6. 已知风险与未完成项

| 项 | 说明 |
|---|---|
| **未编译未跑测试** | 环境无 JDK/SDK/Gradle/外网。请按 §3.2 执行；静态自检只覆盖结构、符号、夹具存在性，**不能替代编译器** |
| Kotlin 编译期语法风险 | 仍是人工核对（如命名参数、`when` 穷尽性、smart-cast）；若首次编译报错，多半是这类小问题，属可快速修复 |
| `lintDebug` 未跑 | 可能有 `NotificationPermission` 之类的 warning（非 error），未做屏蔽 |
| 真机端到端未做 | 见 §3.4，需要能跑 Gradle 的机器 |
| 性能预算（3000 题 ≤ 3s、1.2MB 内存平稳） | 流式路径已由测试证明「只调用一次 applyPack」「解析惰性」；真正的 3s/内存指标必须在真机 + 01 的 Room 实现上测 |
| `Raw` 大包内存 | `LocalPackImporter` 只把 `manifest.json`/`media/index.json` 读进内存（很小），题目与媒体全部落盘 |
