# DONE · 03-media-engine（`:core:media`）

> 作者：03 模块实现方。日期 2026-09-28。
> 本文件是交付自述；文末保留了此前**拼装前审计**留下的一节（对当时快照的实测结论），供对照。

**一句话结论**：合同 §5 的接口与 Compose 签名全部按原样实现，`:core:media` 的
**59 个单元/组件测试全绿**（`testDebugUnitTest`，含 Compose UI 测试），Demo APK 与 Macrobenchmark
模块都能构建出包；**真机性能数字（内存峰值 / 动图 CPU / 掉帧）无法在本机产出** —— 这台机器上
没有任何 Android 设备或模拟器，详见第 2、4 节。

---

## 1. 已完成清单

### 1.1 交付物（`out/core/media/`，拼装时合入）

| 文件 | 内容 |
|---|---|
| `MediaStoreImpl.kt` | 合同 `MediaStore` 实现：内容寻址路径、64KB 流式 SHA-256 + 字节校验、原子移动（跨卷回退 copy+delete）、同 sha 并发串行化、存在性 LRU、增量字节账本、`retain` |
| `MediaRefCoil.kt` | `MediaRefKeyer`、`MediaRefFetcher` + `Factory`（本地文件 Fetcher，缺失返回 null 的"明确 miss"）、`MediaCacheKeys`（内容寻址缓存 key 的唯一来源） |
| `MediaImageLoader.kt` | 进程内唯一 Coil `ImageLoader`（内存缓存 ≤ 80MB、禁用磁盘/网络缓存、关闭 crossfade、`Precision.INEXACT`）、`MediaRequests`（UI/预取/查看器统一的请求构造入口）、`installAsCoilSingleton()` |
| `MediaPrefetcherImpl.kt` | 合同 `MediaPrefetcher` 实现：按缓存 key 去重、并发 ≤ 3、ANIM 只解首帧、VIDEO 跳过、`cancelAll()` |
| `QuizMedia.kt` | 合同签名 `QuizMedia(ref, modifier, contentScale, onClick)`：IMAGE / ANIM / VIDEO 三条通路、骨架屏→刷新、宽高比预占位、动图解码预算与暂停、全局单播放器池 |
| `MediaViewer.kt` | 合同签名 `MediaViewer(refs, startIndex, onDismiss)`：`HorizontalPager` + 双指缩放 1x–5x + 双击放大/还原 + 拖拽平移 + 下拉关闭（跟手）+ `BackHandler` |
| `MediaModule.kt` | Hilt `@InstallIn(SingletonComponent)`：提供 `MediaStore` / `MediaPrefetcher` / Coil `ImageLoader` 单例（合同 §6） |
| `MediaDiagnostics.kt` | 只读诊断（活动动图解码器数、图片内存缓存占用/上限、视频播放器是否在跑），把任务书的硬指标变成可观察数字 |
| `internal/MediaPaths.kt` | 路径推导（合同 §1 布局），非法 sha/ext 直接拒绝（防目录穿越） |
| `internal/MediaStores.kt` | 进程内唯一 `MediaStoreImpl`（Hilt 图 / Coil Fetcher / Compose 三层共用，保证 `committed` 与存在性缓存一致） |
| `internal/AnimDecoderGate.kt` | 全局动图解码预算（≤ 2），带"未持有不释放"保护 |
| `internal/FirstFrameDecoder.kt` | 仅解首帧的 `Decoder`（`ImageDecoder.decodeBitmap` + 等比降采样），预算耗尽时降级 |
| `internal/VideoPlayerPool.kt` | 全局单 ExoPlayer 池：静音、单曲循环、离开屏幕即 release |
| `internal/MediaUi.kt` | 骨架屏 shimmer（仅可见时跑无限动画）、可见性上报、宽高比预占位、RESUMED 追踪、测试注入点 |
| `internal/AnimPlayback.kt` | 动图播放控制（`Animatable.start/stop`）+ 供上层接管的 `LocalAnimPlayOverride` |
| `internal/QuizMediaImageHost.kt` | 图片渲染接缝（生产走 `AsyncImage`，测试注入替身） |
| `internal/ViewerGestureState.kt` | 查看器手势状态机（缩放/平移夹取/双击/下拉阈值），纯逻辑、可单测 |
| `src/test/...` | 8 个测试类 + 2 个测试替身，共 59 个用例（见第 3 节） |

写入位置严格限于 `out/core/media/**`：**没有**写 `out/settings.gradle.kts`、`out/build.gradle.kts`、
`out/gradle.properties`（`verify.sh` 会告警的那三个）。`out/gradle/libs.versions.toml` 是
`COMMANDS.md` 指定的脚手架副本（合同 §8 原文，一字未改），拼装时被 `assemble.sh` 排除。

### 1.2 关键实现决策（对应任务书的硬性约束）

- **路径与存在性**：`file()` 只拼路径 + `isFile`，外加 256 条 LRU 缓存"在/不在"两种结论；
  另提供 `refresh(ref)` 供"文件可能由进程外补上"时强制复查。
- **`commit` 校验**：64KB 缓冲流式 SHA-256，同时累计字节数；**任一不匹配即删除 tmp 并返回 false**
  （验收项"篡改 1 字节 → 返回 false 且不落盘"有专门用例）。同一 sha 的并发 commit 用 `KeyedLock`
  串行化，后到者命中已存在文件直接视为成功；`commit` 返回 false 时保证 tmp 已被删除。
- **`usedBytes()`**：增量账本（`sha → 字节数`），只在首次访问时扫一次盘初始化；只统计"本 store
  认可"的文件，因此进程外写入的文件被 `retain` 删除时不会把计数减成负数。
- **缓存 key**：显式设置 `memoryCacheKey = media:{sha}:{ext}[:static|:full]`。Coil 见到显式 key
  时不会把 `Size` 拼进 key，于是**预取（屏幕宽）与前台按控件尺寸解码命中同一条缓存**，
  同时"整段动画 / 仅首帧 / 全屏"三种解码结果互不污染。
- **动图门控**：`AnimDecoderGate` 全局 2 个槽位，且仅在 `文件就绪 && 可见 && RESUMED` 时申请；
  拿不到槽位就切到"仅首帧解码器"（不产生动画解码器）。暂停**不释放槽位**（释放会导致画面跳回
  首帧），只 `stop()` 冻结当前帧；离开可见或进入后台则释放槽位。
- **视频**：全局单 `ExoPlayer`，静音 + `REPEAT_MODE_ONE`，离开屏幕（`DisposableEffect`）即
  `release()`，后台只 `pause()`。
- **零布局跳动**：根节点 `fillMaxSize()` + `reserveMediaAspect(MediaRef.width/height)` 在单轴受限时
  按原始宽高比补出另一轴，图片解码完成前后尺寸完全一致；子节点用 `matchParentSize()` 给 Coil 的
  `AsyncImage` 提供**固定约束**（它按最小约束定尺寸，给松约束会量出 0 高 —— 这是被测试抓出来的
  真实坑，见第 5 节）。占位色取 `surfaceContainerHighest` 与 `onSurface` 混合，随深浅色主题自适应。

### 1.3 脚手架与 Demo（`out/_standalone/`，拼装时丢弃）

- `model_stub/`：合同 §5 源码副本 + `kotlin-jvm` 模块。
- `gradle/libs.versions.toml`：合同 §8 原文（`../gradle/libs.versions.toml`）。
- `settings.gradle.kts`：`include(:core:model, :core:media, :media-demo, :macrobench)` 的独立构建。
- `media-demo/`：app 模块（Hilt + Compose + Media3），四个场景：
  **3 动图同屏**（HUD 显示活动解码器数，验证 ≤ 2）、**滑动 100 张图**（含自动快速滚动按钮）、
  **网格 30 张/屏**（供 Macrobenchmark）、**视频**；任意媒体可点进全屏查看器。
  样例媒体：`assets/still.webp`、`assets/anim.webp`（动态 WebP）、`assets/clip.mp4`，
  另在运行时生成 100 张不同色调的静图（走真实 `commit` 路径）。
- `macrobench/`：`com.android.test` 模块，两个场景 —— `scrollThirtyImageGrid`
  （`FrameTimingMetric`，一屏约 30 张的 5 列网格快速滑动）与 `coldStartup`（`StartupTimingMetric`）。

---

## 2. 未完成与原因

| 项 | 状态 | 原因 |
|---|---|---|
| 真机性能数字（内存峰值、动图 CPU、P95 帧耗时） | **未产出** | 本机无 Android 设备/模拟器（`adb` 已装，`adb devices` 为空）；Macrobenchmark 与 `dumpsys` 都需要设备 |
| `:core:media:connectedDebugAndroidTest` | **未运行** | 同上。本模块的 UI 测试已改为 **Robolectric 版**，跑在 `testDebugUnitTest` 里，因此覆盖率没有因此缺失 |
| DONE 的性能表格 | **留空并给命令** | 见第 4 节，命令已备好，任何一台真机 5 分钟内可跑完 |
| APK 体积对比 §7 的 25MB 预算 | **未对比** | 那是 02 的整包指标；本机 Demo 的 debug APK 61.7MB（未开 R8、含 1.4MB 样例媒体与全部调试脚手架），不具备可比性 |

Demo 与 Macrobenchmark 的**构建**已验证通过（第 3.3 节），只差"跑起来测指标"这一步。

---

## 3. 自测命令与结果

### 3.1 环境（本机原本什么都没有，先补齐工具链）

原机器无 JDK / Gradle / Android SDK / adb / Python。本次安装（都在 `C:\dev`，不污染仓库）：

- JDK 17.0.20.1+1（清华 Adoptium 镜像）
- Gradle 8.10.2（腾讯云镜像）
- Android SDK：`platforms;android-35`、`build-tools;35.0.0`、`platform-tools`；
  `build-tools;34.0.0` 因官方 CDN 卡死在 0 字节，改从腾讯云 AndroidSDK 镜像手工落盘
- 依赖仓库：`out/_standalone/settings.gradle.kts` 前置了阿里云镜像（官方源在部分国内网络下
  下载 jar 会长时间挂住）。该文件拼装时被丢弃，不影响正式工程

### 3.2 测试（**全绿**）

```bash
gradle -p out/_standalone :core:media:testDebugUnitTest
# BUILD SUCCESSFUL — 59 tests, 0 failures
```

| 测试类 | 用例数 | 覆盖的验收点 |
|---|---|---|
| `MediaStoreImplTest` | 11 | **篡改 1 字节 → false 且不落盘**、字节数不符、并发 commit 串行化与幂等、`committed` 发射小写 sha、非法 sha/ext 拒绝、存在性缓存与 `refresh`、`usedBytes` 增量语义、`retain` 回收计数与空目录 |
| `MediaPrefetcherImplTest` | 7 | 同 sha 去重、文件缺失跳过、VIDEO 不预取、**ANIM 只解首帧**、内容寻址 key、**并发 ≤ 3**、`cancelAll` |
| `QuizMediaUiTest` | 7 | **文件缺失 → 骨架屏且不发请求**、**commit 后骨架屏自动被图片替换**、动图播放/上层接管暂停、**预算用尽 → 仅首帧且不播放**、点击切换暂停/播放、静态图不建播放控制 |
| `MediaViewerUiTest` | 7 | 渲染与页码、越界下标夹取、**返回键关闭**、**下拉超阈值关闭**、小幅下拉回弹、空列表回调关闭、双击不崩 |
| `MediaImageLoaderTest` | 10 | **内存缓存 ≤ 80MB 且为正**、**磁盘/网络缓存禁用**、单例复用、请求挂本地 Fetcher + 内容寻址 key、三种解码变体 key 互不相同、预取按屏幕宽、Keyer 与请求 key 一致、`coil-gif` 已在类路径（动态 WebP 能力已装载） |
| `AnimDecoderGateTest` | 3 | 上限 2、第三个挂起、释放后唤醒、未持有 release 不放大预算 |
| `ViewerGestureStateTest` | 10 | 缩放夹取 1x–5x、双击切换与焦点、未放大不吃平移、平移边界、下拉阈值、上拉阻尼、放大时不触发关闭 |
| `MediaRefFetcherTest` | 4 | 命中返回 DISK 数据源、缺失返回 null（明确 miss，不抛异常）、懒解析 + `refresh`、mimeType 映射 |
| **合计** | **59** | 0 failures（`build/test-results/testDebugUnitTest/` 下 8 份 XML） |

**验收清单对应**

| 验收项 | 状态 |
|---|---|
| `:core:media:testDebugUnitTest` 全绿 | ✅ 59/59 |
| Demo 中 3 个动图同时可见 → 仅 2 个播放，滚出屏幕即停 | ⚠️ 逻辑由 `AnimDecoderGateTest` + `QuizMediaUiTest` 覆盖（预算 2、超预算退化为首帧、不可见/后台释放槽位）；**肉眼确认需真机** |
| 连续滑动 100 张图内存不增长超过预算 | ❌ 需真机 `dumpsys meminfo`（命令见第 4 节） |
| 无布局跳动；深色/浅色占位色适配 | ⚠️ 尺寸非零由 UI 测试断言；配色随主题自适应。**肉眼确认需真机** |
| `commit` 篡改 1 字节 → false 且不落盘 | ✅ 单测覆盖 |

### 3.3 构建

```bash
gradle -p out/_standalone :media-demo:assembleDebug        # BUILD SUCCESSFUL
gradle -p out/_standalone :macrobench:assembleBenchmark    # BUILD SUCCESSFUL
# 产物：media-demo/build/outputs/apk/debug/media-demo-debug.apk（61.66 MB，debug 未开 R8）
#      macrobench/build/outputs/apk/benchmark/macrobench-benchmark.apk（40.37 MB）
```

这一步同时验证了 Hilt/KSP 注入图（`MediaModule` 三个单例）、Media3 `PlayerView`、
Coil 与 Compose 能在真实 app 模块里组装成 APK。

---

## 4. 需要在真机上补跑的数字（命令已备好）

```bash
adb install -r out/_standalone/media-demo/build/outputs/apk/debug/media-demo-debug.apk
adb shell am start -n com.me.jiakao.mediademo/.DemoActivity

# ① 内存峰值（切到"网格 30 张/屏"或"滑动 100 张图"，来回快速滑动后再看）
adb shell dumpsys meminfo com.me.jiakao.mediademo | grep -E "TOTAL|Graphics|Java Heap"
#    期望：TOTAL 不随滑动线性增长；Graphics 主要由图片内存缓存构成（上限 80MB）

# ② 掉帧（滑动过程中另开窗口执行）
adb shell dumpsys gfxinfo com.me.jiakao.mediademo | grep -E "Janky frames|95th|99th"
#    期望：95th ≤ 8ms（120Hz）/ 12ms（60Hz），Janky frames 接近 0

# ③ 动图解码线程 CPU（"3 动图同屏"页停留时）
adb shell top -H -n 1 | grep -i mediademo
#    期望：只有 2 个动画解码器在跑；把第三个滚出屏幕后线程数下降
#    HUD 上的"活动动图解码器 n/2"是同一件事的可视化

# ④ Macrobenchmark（自动给出 P50/P90/P95/P99 与启动耗时）
gradle -p out/_standalone :macrobench:connectedBenchmarkAndroidTest
```

---

## 5. 实现过程中被测试抓出来的两个真实问题（供后来人参考）

1. **`AsyncImage` 按"最小约束"定尺寸** → 子节点若只给松约束（`fillMaxSize` + 无界高度），量出来是
   0 高，媒体在"父布局两轴都有界但宽松"的槽位里会**完全不显示**。修法：子节点用
   `matchParentSize()` 拿固定约束，根节点用 `fillMaxSize()` 落实父布局给的空间。
   这由 `QuizMediaUiTest.assertIsDisplayed` 暴露。
2. **"每次删除都减一个总数"的 `usedBytes` 会计会算错**：进程外写入的文件（下载器 / adb push /
   离线包）被 `retain` 删掉时会把计数减成负数、把真实占用抹平。改成"只减账本里记过的 sha"。

另外一处**集成问题**：`coil-compose` 必须是 `api` 而非 `implementation`
（`MediaImageLoader.of()` 返回 `coil3.ImageLoader`、`MediaRefKeyer` 实现 `coil3.key.Keyer`），
否则 02 连 `SingletonImageLoader.Factory` 都写不出来 —— 这是构建 Demo 时被编译错误逼出来的修正。

---

## 6. 对合同的疑问

已全部写入 **`out/CONTRACT_ISSUES.md`**（5 条，含现象与建议改法），摘要：

1. `committed` 无 replay ⇒"订阅前已提交"的事件会丢，占位图可能永远不刷新（我加了订阅后复查 +
   回前台复查兜底；建议改 `replay = 1` 或加状态型接口）。
2. §8 给了 `benchmark` 版本号但没有库别名，也没有 `com.android.test` 插件别名（Macrobenchmark 必需）。
3. §8 未列 Compose UI 测试构件（`ui-test-junit4` / `ui-test-manifest`），但任务书要求 Compose UI 测试。
4. §5 未约定 `commit` **移动失败**时 `tmp` 的归属（我按"返回 false 即 tmp 不存在"实现）。
5. `MediaRef` 没有"解码变体"概念（记录性质，已在实现里用显式缓存 key 解决）。

---

## 7. 拼装注意事项（给 `assemble.sh` / 其它模块）

1. **无需任何根文件**：本模块只贡献 `core/media/**`；`settings.gradle.kts` 里照常
   `include(":core:media")` 即可，依赖方向 `:app → :core:media → :core:model`
   （本模块 `api(project(":core:model"))`）。
2. **`:core:model` 必须存在**（合同 §5 原件）。独立开发期我用 `_standalone/model_stub` 顶上，
   拼装后直接换成 01 的真件，本模块源码零改动。
3. **Coil 是 `api` 依赖**（理由见第 5 节末）。
4. **给 02 的接入建议**：
   - `Application` 实现 `SingletonImageLoader.Factory` 并返回 `MediaImageLoader.of(this)`，
     或在 `onCreate` 里调一行 `MediaImageLoader.installAsCoilSingleton(this)`（需早于任何 Coil 调用）。
   - 题目媒体一律用 `QuizMedia(ref, Modifier.fillMaxWidth())`；**不要**自己用
     `AsyncImage(model = ref)` 绕开本模块 —— 那条路径没有本地 Fetcher 与显式缓存 key，
     会退化成"找不到 Fetcher"的错误结果。
   - 全屏查看用 `MediaViewer(refs, startIndex) { ... }`；它已处理缩放/下拉关闭/返回键，
     并通过 `LocalAnimPlayOverride` 接管动图播放（单击切换暂停/播放、双击缩放）。
   - 需要调试 HUD 时读 `MediaDiagnostics`。
5. **允许 03 新增的公开 API**（只是"增加"，合同里的 4 个接口/Compose 签名一字未改）：
   `MediaStoreImpl`、`MediaRefKeyer`、`MediaRefFetcher`、`MediaImageLoader`、
   `MediaPrefetcherImpl`、`MediaDiagnostics`。
6. **动图能力来自 `coil-gif`**：`AnimatedImageDecoder` 由 Coil 的 ServiceLoader 机制自动注册
   （`META-INF/services/coil3.util.DecoderServiceLoaderTarget`）。因此本模块**不替换**
   `ComponentRegistry` —— 替换会丢掉 URI/资源/文件等默认 Fetcher，反而破坏 app 其它地方的 Coil 用法。
   `MediaRef` 通路靠"请求级 `fetcherFactory` + 显式 `memoryCacheKey`"接入。
7. **`usedBytes()` 语义**：只统计"本 store 认可"的文件（本进程 commit 过的 + 首次扫描时盘上已有的）。
   进程外新写入的文件要等下次冷启动扫描，或调用方经 `commit` 落盘。若要展示"含外部导入"的占用，
   先调一次 `retain(allShas)` 触发扫描。
8. **测试依赖**：仅测试期新增 `androidx.compose.ui:ui-test-junit4`、`ui-test-manifest`
   （版本由 §8 的 compose-bom 管）；脚手架 `_standalone` 里还用到 `org.robolectric:shadows-framework`
   与 benchmark 的测试工具链，均随 `_standalone` 丢弃。见 `CONTRACT_ISSUES.md` 第 2、3 条。
9. **Demo 样例媒体出处**（`out/_standalone/media-demo/src/main/assets/`，拼装时丢弃）：
   `still.webp` / `anim.webp` 取自 Google 的 WebP 示例集（`gstatic.com/webp/...`），
   `clip.mp4` 为 Big Buck Bunny 10s/360p 片段（`test-videos.co.uk`）。
   另：为演示"3 个不同的动图"，Demo 导入时对 `anim.webp` 的 ANIM 块 loop count 做了 2 字节改写，
   得到 3 个不同 sha（仍是合法动图，播放次数被 Coil 的 repeatCount 覆盖为无限循环）。
   **这些素材只服务于手工验收，不要带进正式工程**；正式题库媒体由 05 生成。
10. **`out/core/media/build/` 是构建残留**（约 3800 个文件）。当前根 `assemble.sh` 已排除
    `./build` 与 `*/build`，拼装不会带上；若用旧脚本请先手工删除。

---

## 附录 · 此前"拼装前审计"留下的一节（原文要点）

> 审计针对的是本模块的**中间快照**（当时 49 个用例、尚无 benchmark 模块、尚无 DONE/CONTRACT_ISSUES）。
> 保留在此以便对照；其中第 1~3 条在本次交付中已闭环。

- 审计实测：`out/core/media/` 20 个主源码文件 + 7 个测试文件 + 2 个替身 + `build.gradle.kts`；
  合同 §5 的 `MediaRef` / `MediaStore` / `MediaPrefetcher` / `QuizMedia` / `MediaViewer` 签名**逐条比对无不一致**
  （唯一差异是 `has()` 直接用接口默认实现）。
- 审计指出未完成：①缺 `out/DONE.md` 与 `out/CONTRACT_ISSUES.md`，且引入了 §8 之外的测试依赖未记录；
  ②缺 Macrobenchmark 场景；③无 Gradle Wrapper 且本机无 JDK，**49 个用例未被证实全绿**；
  ④预取用 `execute()` 而非任务书措辞里的 `enqueue`。
- 本次交付的闭环情况：①DONE.md + CONTRACT_ISSUES.md 已补齐（测试依赖已记录）；②`_standalone/macrobench`
  已补齐并可构建；③工具链补齐后 **59 个用例实测全绿**（比审计时多了 `MediaImageLoaderTest` 10 个）；
  ④`execute()` 的选择保留，理由写在 `MediaPrefetcherImpl` 的 KDoc 与本文第 1.2 节。
- 审计还修好了根 `assemble.sh`（排除 `build/`、`.gradle/`、`.kotlin/`、`local.properties`、`__pycache__` 等），
  本模块的构建残留因此不会再被带进最终仓库。
