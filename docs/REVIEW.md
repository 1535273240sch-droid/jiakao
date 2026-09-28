# 拼装前审核报告（REVIEW）

审核日期：2026-09-28 · 范围：`jiakao-kit/` 五个模块的 `out/` 交付物 · 方式：静态审计 + 目录/文件级实测（**本机未编译**）

---

## 0. 一句话结论

**5 个模块的交付物都已落地，`verify.sh` 等价检查已从"不通过"变为通过**；本次审核发现并修掉了 **3 个会直接让拼装失败的硬问题**；剩余未完成项里只有 1 项会真正影响体验指标（02 缺 Baseline Profile 模块），其余是需要你裁决的语义/规范问题。

| 模块 | 交付 | 自测 | 本次审核判定 |
|---|---|---|---|
| 01-app-core | 根 Gradle + `:core:model` + `:core:data` | 37 单测全绿（作者实测） | ✅ 可拼装 |
| 02-app-ui | `:app` 全部界面（38 个 main .kt）+ debug 假数据 + 14 单测 | 未编译 | ⚠️ 可拼装，但有缺项：无 `baselineprofile/` 模块、无 `CONTRACT_ISSUES.md` |
| 03-media-engine | `:core:media` 20 个 main .kt + 49 单测 | 未跑（无 JDK/Wrapper） | ⚠️ 可拼装，但有缺项：无宏基准场景、无性能实测数字 |
| 04-update-engine | `:core:update` 25 个 main .kt + 88 单测 + 23 个夹具 | 未跑（无 JDK/SDK） | ✅ 可拼装 |
| 05-content-pipeline | `tools/pipeline` + `server` + 140 pytest | 140 通过（作者实测） | ✅ 可拼装 |

> 注：04 与 05 的 DONE.md 里都写了"本机没有 JDK/SDK/Gradle"。**这是错的**——工具链一直都在 `C:\Users\Administrator\jiakao-tools\`（`jdk-17.0.2` / `gradle-8.10.2` / `Sdk` / `gradle-home` / `mirrors.init.gradle.kts`），只是没加进 PATH。所以真机编译与测试在本机是**可执行**的，不是环境死限。

---

## 1. 本次实际修掉的问题

### 【阻塞】问题 1：版本目录缺 7 个别名 —— 拼装后**配置期就报错**

02 和 04 的 `build.gradle.kts` 引用了合同 §8 之外的别名，而拼装时版本目录只认 01 的那一份（02–05 的 `out/gradle/` 被 `assemble.sh` 丢弃）：

| 缺失别名 | 引用方 | 后果 |
|---|---|---|
| `libs.plugins.androidx.baselineprofile` | 02 `app/build.gradle.kts:405`（`plugins {}` 内） | **配置期失败**，`:app` 连 build script 都过不去 |
| `libs.hilt.compiler.androidx` | 02 `app/build.gradle.kts:3066`（ksp） | `@HiltWorker` 不处理 |
| `libs.jankstats` | 02 | 未解析引用 |
| `libs.compose.ui.test.manifest` | 02（`debugImplementation`） | **debug 变体**未解析引用 |
| `libs.compose.ui.test.junit4` / `libs.test.runner` | 02（androidTest） | androidTest 未解析引用 |
| `libs.androidx.hilt.compiler` | 04 `core/update/build.gradle.kts:1076`（ksp） | `UpdateWorker` 无法被 Hilt 处理 → 「编译过、运行炸」 |

**处理**：只往 `01-app-core/out/gradle/libs.versions.toml` **追加**（§8 原有 23+37+8 个条目一行未动）。已用脚本实测确认：相对于改动前 **0 行被删改、20 行新增**；且 CONTRACT.md §8 围栏里的每个键与值都能在目录里原样找到（`missing=[] value_mismatch=[]`）。追加的 7 个 library 别名 + 2 个插件别名，配合脚本已逐个验证**全部可解析**（唯一"失败"项来自 `01/build.gradle.kts:116` 的注释文字 `libs.plugins.xxx`，是误报）。

备份：`_attic/original/libs.versions.toml.01.bak`

### 【阻塞】问题 2：`assemble.sh` 会把构建产物合并进最终仓库

`assemble.sh` 原排除项只有 `_standalone/`、`DONE.md`、`CONTRACT_ISSUES.md`、`.gitkeep`。实测会被误合并的目录：

| 目录 | 规模 |
|---|---|
| `01-app-core/out/build`、`out/.gradle`、`out/.kotlin`、`out/core/{data,model}/build` | Gradle 中间产物与配置缓存 |
| `03-media-engine/out/core/media/build` | **660 个文件** |
| `05-content-pipeline/out/tools/pipeline/{.venv,work,dist,state,sample,bench}` | `.venv` 一个就 **2202 个文件 / 48.5 MB** |

三者都是各自模块 DONE.md 明确声明"拼装时勿提交 / 不入库"的东西。**处理**：给 `assemble.sh` 的 `copy_out` 加上通用排除（`build`、`.gradle`、`.kotlin`、`local.properties`、`.idea`、`*.iml`、`__pycache__`、`*.pyc`），并给 05 单独加流水线产物排除。备份：`_attic/original/assemble.sh.bak`

### 【阻塞】问题 3：debug 源集的 Fake 绑定会与真实模块 `DuplicateBindings`

`02-app-ui/out/app/src/debug/java/com/me/jiakao/fake/` 里有一整套**完整的** Hilt 假绑定（`FakeBindings.kt` 用 `@Binds @Singleton` 绑定 `QuizRepository`/`UserRepository`/`ExamService`/`MediaPrefetcher`/`BankUpdater`）。拼装后 `:app` 依赖真实的 `:core:data`/`:core:media`/`:core:update`，两边在 **debug 变体**里同名绑定 → Hilt 报 `DuplicateBindings`，`./gradlew :app:assembleDebug`（ASSEMBLY.md §4 明令的那条命令）直接失败。

**处理**：`assemble.sh` 在合并 02 时自动排除 `app/src/debug/java/com/me/jiakao/fake`（等价于手工执行 ASSEMBLY.md §4 的第一步），并已在 ASSEMBLY.md §4 注明，附"要恢复 UI 独立开发怎么拷回来"。

### 顺带整理

- 清掉 `02-app-ui/` 根目录 5 个误建目录（`-p`、`dir`、`echo`、两个 40 位十六进制名，均 0 字节）→ 移到 `_attic/02-app-ui-stray/`。
- 补齐 `02-app-ui/out/DONE.md`、`03-media-engine/out/DONE.md`（**文件头已注明"由拼装前审计生成，非原作者自述"**）——`verify.sh` 的硬前置，缺了它拼装文档链是断的。

---

## 2. 还需你裁决 / 尚未补的东西

### 2.1 影响指标但不阻塞编译

| # | 项 | 说明与建议 |
|---|---|---|
| 1 | **02 缺 `baselineprofile/` 模块** | `app/build.gradle.kts` 已应用 `androidx.baselineprofile` 插件、也已 `implementation(libs.profileinstaller)`，但配套的 `com.android.test` 模块、冷启动/滑动场景、`baseline-prof.txt` 全没有（`app/src/release/generated/baselineProfiles/` 是空目录）。合同 §7「冷启动 ≤ 600ms」在没 Profile 的情况下大概率不达标。**建议**：拼装后补一个最小 `baselineprofile` 模块（01 的 `settings.gradle.kts` 已用 `exists()` 守卫 `:baselineprofile`，目录一落就自动纳入，不用改 settings）。 |
| 2 | **02 无 `androidTest`** | 只有 `src/test` 的 14 个用例；Compose UI 测试与基准测试缺位。 |
| 3 | **03 缺 Macrobenchmark 场景** | TASK 交付物 6 要求"一屏 30 图滑动 P95 帧耗时"，实测只有 demo 里的 `benchmark` buildType 与 `profileable` 清单脚手架。 |
| 4 | **03 无性能实测数字** | TASK 交付物 8 要的"内存峰值 / 动图解码 CPU / 掉帧"没有；49 个单测是否全绿也没闭环（`build/` 里无 `test-results/`）。**这是本机可以补的**（工具链齐全）。 |
| 5 | **:app 清单缺两项**（04 的 ISSUES §9） | ① `AndroidManifest.xml` 未引用 `android:networkSecurityConfig="@xml/network_security_config"`（资源由 `:core:update` 的 debug 源集提供）→ 联调期 `http://10.0.2.2:8000/` 明文被拒，**会挡住 ASSEMBLY.md §6 的端到端联调**；② 未声明/请求 `POST_NOTIFICATIONS` → Android 13+ 更新通知被静默跳过。 |
| 6 | **02 的性能规范未落实** | 全模块 **0 处 `@Immutable`/`@Stable`**、**0 处 `derivedStateOf`**（TASK §4 明确要求）。四处每重组重复计算：`ExamScreen.kt:213`（100 题规模 `count{}`）、`ExamScreen.kt:230`（每次重组新建整个 Map）、`PracticeScreen.kt:191`、`QuestionPage.kt:139`（每重组 `sorted()`）。这是"整体没有什么性能问题"这条要求里**最实的一个欠账**。 |
| 7 | `ExamScreen`/模拟考试 | 已确认 `keepScreenOn`、震动、到点自动交卷在实现里；无需处理。 |

### 2.2 合同语义需裁决（三份 CONTRACT_ISSUES 的合并清单）

| # | 议题 | 冲突/风险 | 建议裁决 |
|---|---|---|---|
| 1 | `vehicles` 为空/缺失的语义 | **01 与 04/05 不一致**：01 把空 `vehicles` 当"适用所有车型"（防御性，避免漏题）；04 与 05 把缺失当 `["car"]`。两者对"缺失"结论相同，对"显式空数组"不同 | 统一为"缺失或空 → `["car"]`"，或明确"空 = 全车型"。改点很小（05 `normalize` / 01 的查询谓词） |
| 2 | 01 的 FTS4 内容列 `options_text` | TASK 的 `question` 列清单里没有它，01 自行加了一个派生列 | 确认接受（否则要改成 `applyPack` 事务内手工维护 FTS） |
| 3 | 04 的 `Plan.Unavailable(reason)` | TASK 只定义了 `UpToDate/Deltas/Full` 三种，04 加了第四种处理"本地版本高于远端""清单无可用包" | 确认接受；顺带把这两条边界写进合同 §3 |
| 4 | 05 的 bundle 内 `manifest.json` 自引用 | zip 内清单的 `bundle.sha256` 只能是占位值（自哈希无不动点，数学上无解） | **04 的 `importLocalPack()` 不得校验 zip 自身哈希**（只读版本/章节/题目/媒体），这点必须写进拼装核对表 |
| 5 | 04 已在 §8 之外补了 `androidx.hilt:hilt-compiler` | 我已把该条目落进 01 的目录 | 建议同步更新 `CONTRACT.md` §8，让合同与仓库一致 |
| 6 | 明文 HTTP / 镜像 | 01 把 `distributionUrl` 指向了腾讯云镜像、`core/data` 硬写 `buildToolsVersion = "34.0.0"` | 拼装后按 ASSEMBLY.md §5 决定是否改回官方源与默认 build-tools |
| 7 | 其他 | `released_at` 可复现边界、`kind/ext` 对应、`chapter_id` 正则、`min_app_version_code` 客户端语义、媒体"只增不删"、`OkHttpClient` 限定符（04 已用 `@UpdateHttp` 规避） | 均已在各模块 ISSUES 里给出最小假设，可一并写回合同 |

---

## 3. 性能审核（"整体没有什么性能问题"）

### 已经到位的（有代码证据）

- **数据库**：3010 题量级下 Robolectric 实测全量导入 **459ms**（预算 3000ms）、章节 id 查询 **1.1ms**（预算 20ms）、`getQuestions(50)` **1.6ms**（预算 15ms）；`QuizRepository` 全 Flow 带 `distinctUntilChanged`，取题 500 条批量 IN 查询、无 N+1；跨库统计用内存归并而非多次查询。
- **媒体**：动图解码器被进程级 `AnimDecoderGate` 硬限 **≤2**（拿不到槽位就退化成首帧静态图）；预取 ≤3 并发、同 sha 去重、VIDEO 跳过；Coil 内存缓存封顶 `min(80MB, 25% 堆)`、磁盘/网络缓存关闭；图片按宽高比预占位，避免列表跳动；`Context` 全部走 `applicationContext`，未发现泄漏。
- **更新**：题库包 `GZIPInputStream` + 逐行 `sequence` 流式解析，不整体入内存；下载 `.part` + Range 续传；导入单事务、失败整体回滚、版本号只在成功后推进；离线包边解边落盘算 sha。
- **UI**：38 个 main 文件**全部**懒列表带 `key` + `contentType`，**0 处** `collectAsState()`（全用 `collectAsStateWithLifecycle`），主线程无 IO，撒花粒子 `remember` 且上限 60、尊重系统"移除动画"。
- **流水线**：3000 题 + 500 媒体首次构建 5.01s（目标 ≤120s），缓存构建 2.18s；gzip `mtime=0` + zip 固定时间戳 → 同输入 dist 字节级一致。

### 欠账（按优先级）

1. 02 的 `@Immutable/@Stable` + `derivedStateOf`（见 2.1 #6）——直接影响 §7 的滑动 P95。
2. Baseline Profile 模块缺失（见 2.1 #1）——冷启动 600ms 的兑现手段。
3. **§7 的 5 个指标一个都没在真机/模拟器上量过**：冷启动、滑动 P95、3000 题导入、APK ≤25MB、同时动图解码器 ≤2。前四项预算断言都已写进测试代码（01 的 `PerfBenchmark.kt`、03 的 `AnimDecoderGateTest`），缺的只是"有无设备跑一次"。

---

## 4. "精美 + 羊皮卷风格质感"这条要求（**尚未实施**）

现状：02 的主题是 `ui/theme/Color.kt` 里的 Material3 配色——**驾校蓝 `#0E5FD8` + 交警橙 `#E8641B`**，纯色卡片、无任何纹理/纸质元素（另外已具备浅/深/跟随系统三态）。所以"羊皮卷风格"是**新增需求**，需要一次视觉改版。

落点（都在 02，不动其他模块）：

| 改什么 | 文件 |
|---|---|
| 配色（羊皮纸底色 #E8D7B0 系 / 墨褐文字 / 朱砂印章红做强调色） | `ui/theme/Color.kt`、`Theme.kt` |
| 字体（衬线 + 楷/宋，字号阶梯） | `ui/theme/Type.kt` |
| 纸纹质感 | 需**新增**：9-patch 或 AGSL/程序化噪声的纸纹着色器 + `Modifier.paperSurface()`（禁止用大尺寸 PNG 平铺，会直接违反 §7 掉帧预算） |
| 卡片/答题卡/成绩页换纸感、卷轴展开转场 | `ui/components/EntryCard.kt`、`ProgressRing.kt`、`screens/practice/AnswerCardSheet.kt`、`screens/exam/ExamResultScreen.kt`、`ui/anim/Anim.kt` |

> 这里有一个我无法从你的话里确定的点：**"羊皮卷风格"是给 App 的界面换皮，还是要求把"拼装审核报告"本身做成一份羊皮卷风格的文档？** 我按前者（App 换皮）理解和排期，因为"质感"和"性能问题"出现在同一句里、指向的应该是产品。如果其实是后者，说一声，我改成做文档。
>
> 另外它和 §7 性能预算有直接冲突点：纸纹必须走低开销路径（程序化/缓存 9-patch + 单一 `drawBehind`），一旦做成"每个列表项叠一张大背景图 + 半透明混合"，滑动 P95 立刻破 8ms。改版时会按这个约束来做。

---

## 5. 下一步（拼装执行清单）

本机**没有 bash**（也没有 git / adb / java 进 PATH），但工具链和 Python 都在：

```
JDK 17.0.2   C:\Users\Administrator\jiakao-tools\jdk-17.0.2
Gradle 8.10.2 C:\Users\Administrator\jiakao-tools\gradle-8.10.2
Android SDK  C:\Users\Administrator\jiakao-tools\Sdk
镜像 init 脚本 C:\Users\Administrator\jiakao-tools\mirrors.init.gradle.kts
Python       05-content-pipeline\out\tools\pipeline\.venv\Scripts\python.exe
```

因此两条路，任选：

**A. 我在本机直接拼装 + 编译（推荐，可立刻验证）**
用 `tar.exe`（Windows 自带）复现 `assemble.sh` 的合并规则落一个目标目录，然后
`gradle -I mirrors.init.gradle.kts :app:assembleDebug`，把首次编译的真实报错一次性消掉。
这也是唯一能验证"7 类别名补齐 + Fake 排除 + DuplicateBindings 假设"是否真的成立的办法（本次审核全部是**静态**结论，未编译）。

**B. 你按原流程在别处跑**：`bash verify.sh` → `bash assemble.sh ~/jiakao` → `./gradlew :app:assembleDebug`，把报错贴回来。

拼装后按 ASSEMBLY.md 的顺序还要做：裁决 §2.2 的合同问题 → 补 2.1 #5 的两项清单配置 → 补 baselineprofile → 真机跑 §7 五个指标 → 然后才是羊皮卷视觉改版（因为它会动到同一批 UI 文件，放在功能联调之后做最省事）。

---

## 6. 本次改动的备份与回滚

全部原始文件在 `_attic/original/`：`assemble.sh.bak`、`verify.sh.bak`、`libs.versions.toml.01.bak`；误建目录在 `_attic/02-app-ui-stray/`。
`_attic/` 不在任何模块的 `out/` 下，**不会被 `assemble.sh` 合并**。审核脚本也放在这里：`check_catalog.py`（别名解析）、`check_contract8.py`（§8 一致性）、`verify_check.cmd`（verify.sh 等价检查）。
