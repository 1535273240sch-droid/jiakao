# 03-media-engine · 对冻结合同的疑问与建议

> 按合同开头的要求：发现缺陷只在**本文件**记录（现象 / 建议改法），并按**最小假设**继续实现。
> 下面每一条都写清"我按什么假设继续做的"，拼装阶段由人裁决。

---

## 1. `MediaStore.committed: SharedFlow<String>` 无法覆盖"订阅前已提交"

**现象（合同 §5 `MediaStore`）**
`committed` 是无 replay 的 `SharedFlow`。真实时序里很容易出现：

1. Compose 首帧先组合出占位屏，此时**还没开始 collect**（`LaunchedEffect` 要等首帧后才跑）；
2. 04 的下载器在这一瞬间 commit 成功并 `emit`；
3. 订阅者随后才挂上，事件已经丢了 → 占位图永远不刷新，直到用户切走再切回。

任务书要求"文件缺失 → 骨架屏占位，监听 `MediaStore.committed`，命中自身 sha 后自动刷新"，
单靠这个 flow 语义上是**不闭合**的（不是错误，是并发竞态）。

**我的最小假设**
`committed` 保持合同签名不变，但在 `QuizMedia` 里补两条兜底：
① 订阅后**立即复查一次**存在性；② 回到前台（`Lifecycle.RESUMED`）时再复查一次（走
`MediaStoreImpl.refresh()`，会丢弃存在性缓存的负面结论）。

**建议改法（供拼装裁决，二选一）**
- 把 `committed` 改成 `replay = 1` 的 `SharedFlow`（一行改动，语义立刻闭合）；或
- 增加 `fun exists(ref: MediaRef): Boolean` 之类的**状态型**只读接口，把"事件"降级为"提示"，让消费者以状态为准。

---

## 2. §8 缺 `androidx.benchmark:benchmark-macro-junit4` 的库别名与 `com.android.test` 插件别名

**现象**
§8 给了 `benchmark = "1.3.3"` 版本号，但 `[libraries]` 里没有对应条目，`[plugins]` 里也没有
`com.android.test`。而任务书第 6 条要求交付 Macrobenchmark 场景，缺这两项时**无法在不新增
依赖的前提下**写出一个 `com.android.test` 模块。

**我的最小假设**
脚手架（`out/_standalone/macrobench`，拼装时丢弃）里用 §8 已钉住的版本号显式声明：

```kotlin
id("com.android.test") version "8.7.3"
implementation("androidx.benchmark:benchmark-macro-junit4:${libs.versions.benchmark.get()}")
```

不引入任何新版本，只是把"版本号已给但别名没给"补全。正式工程里这部分由 `02` 的
`:baselineprofile` 承接。

**建议改法**：在 §8 补 `benchmark-macro-junit4 = { module = "androidx.benchmark:benchmark-macro-junit4", version.ref = "benchmark" }`
与 `android-test = { id = "com.android.test", version.ref = "agp" }`。

---

## 3. §8 未列出 Compose 组件的测试构件，但任务书要求 Compose UI 测试

**现象**
任务书第 6 条要求"Compose UI 测试（占位→刷新、动图暂停/恢复）"，这需要
`androidx.compose.ui:ui-test-junit4` 与 `ui-test-manifest`（提供测试用 `ComponentActivity`），
§8 的 `[libraries]` 里没有它们。

**我的最小假设**
两者由 §8 已声明的 `compose-bom`（`2024.12.01`）统一管版本，因此按 `testImplementation`
直接引用坐标即可，**不新增版本**：

```kotlin
testImplementation(platform(libs.compose.bom))
testImplementation("androidx.compose.ui:ui-test-junit4")
debugImplementation("androidx.compose.ui:ui-test-manifest")
```

**建议改法**：在 §8 补这两条别名（无 version.ref，交给 BOM），让"测试依赖"这件事在合同里可见。

---

## 4. §5 未约定 `commit` 失败时 `tmp` 的归属

**现象**
`commit(ref, tmp)` 的 KDoc 只说"不匹配返回 false 并删除 tmp"。但**移动失败**（磁盘满、
权限、跨卷 copy 失败）时，合同没说 tmp 该留还是该删。任务书说"校验失败删除 tmp"，
没提移动失败。

**我的最小假设**
统一按"**commit 返回 false ⇒ tmp 一定不存在**"实现（校验失败、路径非法、移动失败都删），
调用方（04）不需要自己清理。跨卷时退化为 copy + delete，`copyTo` 成功但 `delete` 失败
视为提交成功（文件已在目标位，残留的 tmp 由 04 的 `cacheDir/dl/` 自清理）。

**建议改法**：在 §5 的 `commit` 注释里补一句"返回 false 时实现必须保证 tmp 已被删除（或明确
声明由调用方清理）"，避免 04/03 各自理解。

---

## 5. 轻微：`MediaRef` 没有"解码变体"概念，缓存 key 只能由实现方自定

**现象**
同一份媒体在不同场景需要不同解码结果：整段动画 vs 仅首帧、列表尺寸 vs 全屏尺寸。
`MediaRef` 里没有这样的字段，Coil 默认又把 `Size` 拼进内存缓存 key —— 结果是"预取（屏幕宽）"
与"控件按自身尺寸解码"落在两条缓存上，预取等于白做。

**我的最小假设（已在代码里落实，不算缺陷，只是记录）**
`MediaCacheKeys` 生成 `media:{sha}:{ext}[:static|:full]` 并**显式**设置 `memoryCacheKey`。
Coil 见到显式 key 时走 fast path、不再混入 `Size`，于是"一个 sha 解码一次"，
预取与前台显示命中同一条缓存。

**建议**：合同层面无需改动；但如果将来要在 `MediaRef` 上加字段，请优先加"变体/用途"而不是
"尺寸"，尺寸应当由消费方按控件决定。
