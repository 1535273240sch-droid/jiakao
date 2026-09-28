# 04-update-engine · 合同问题与最小假设

> 合同是冻结的，本文件只记录「现象 / 建议改法 / 我按什么最小假设继续」。
> 拼装阶段由人统一裁决（ASSEMBLY.md §3）。

---

## 1.【需要人裁决·拼装必做】合同 §8 缺少 `androidx.hilt:hilt-compiler`

- **现象**：TASK 交付物 4 要求 `UpdateWorker` 是 `@HiltWorker`，而 `@HiltWorker` / `@AssistedInject`
  的注解处理需要 **`androidx.hilt:hilt-compiler`**（`com.google.dagger:hilt-android-compiler`
  只处理 Dagger/Hilt 本体，不处理 androidx 的 Worker 扩展）。§8 的 `[libraries]` 里只有
  `hilt-work = androidx.hilt:hilt-work`，没有对应的 compiler。
- **建议改法**：在 §8 增加一行
  ```toml
  androidx-hilt-compiler = { module = "androidx.hilt:hilt-compiler", version.ref = "hiltNavCompose" }
  ```
  （版本跟随 `hiltNavCompose = 1.2.0`，与 `hilt-work` 同版本）
- **我的最小假设**：在 `out/gradle/libs.versions.toml`（独立开发用的副本，拼装时被
  `assemble.sh --exclude='./gradle'` 丢弃）里加了该项，并只在 `:core:update` 用
  `ksp(libs.androidx.hilt.compiler)`。
- **拼装必做**：把这一行加到 **01 的 `gradle/libs.versions.toml`**，否则
  `UpdateWorker`/`AutoUpdateWorker` 不会被 Hilt 处理，`HiltWorkerFactory` 在运行时会
  `Could not instantiate worker` 而失败（编译期不报错，属于「编译过、运行炸」的坑）。

---

## 2.【需要裁决】§3 未规定两种边界：本地版本高于远端、清单既无全量也无连续增量链

- **现象**：§3 只写了「相等→最新」「有连续增量链且更小→增量」「否则→全量」，没有定义
  - 本地 `bank_version` **高于**远端（服务端回滚/换源）；
  - 清单里 `full` 为 null 且没有连续增量链（例如只发布了增量却被清理过的服务端）。
- **建议改法**：§3 补两条：①本地高于远端视为已最新，不做降级；②无可用包时
  `Failed(retryable=true, "题库清单缺少可用更新包…")`。
- **我的最小假设**：按上述处理。为此 `Plan` 在 TASK 的 `UpToDate | Deltas | Full` 之外
  多了第四种 `Unavailable(reason)`（纯函数不抛异常，交由状态机映射成 `Failed`）。
  如有权威裁决，改动点只在 `UpdatePlanner.plan()` 与 `BankUpdaterImpl` 的一个分支。

---

## 3.【提示 03】§5 `MediaStore.commit(ref, tmp: File)` 未说明 tmp 的位置约束

- **现象**：§1 规定下载临时目录是 `cacheDir/dl/`，§5 要求 `commit` 做
  「校验 → **原子移动**到目标路径」。二者合起来意味着 `tmp` 在 `cacheDir`、目标在
  `filesDir/media/...`，**通常跨目录**（在不同分区时甚至不能 rename）。
- **我的最小假设**：04 只负责把校验通过的 `cacheDir/dl/*` 交出去，不动 `tmp`；
  `commit` 内部需要自己处理「同目录 rename 失败则 copy+delete」，失败时删除 `tmp`。
- **建议改法**：§5 的 `commit` KDoc 补一句：「`tmp` 可能位于 `cacheDir`，实现须支持
  跨目录移动，且必须删除 `tmp`」。

---

## 4.【提示 01】§5 `QuestionStore.applyPack` 的 `records` 生命周期未约定

- **现象**：`records` 是惰性 `Sequence`，04 用 `PackCodec.stream()` 从**.part 文件流**逐行喂入。
  若 01 的实现**中途停止迭代**（例如按 500 条批量提交时 `break`）而**不抛异常**，底层
  文件流不会被关闭（Kotlin sequence 的 `use` 只在正常迭代完或抛异常时执行）。
- **我的最小假设**：01 要么把序列迭代到底，要么在失败时抛异常（合同 §3.3 本来就要求
  失败回滚，所以抛异常是唯一合理路径）。04 侧不做额外保护。
- **建议改法**：§5 `applyPack` KDoc 补一句：「实现必须完整迭代 `records`，或在失败时抛出
  异常；不得中途静默退出」。

---

## 5.【提示 05】§3 bundle 内 `bank.jsonl` 的字节格式未明确（明文 / GZIP）

- **现象**：§3 只写 `bundle/bundle-v2.zip` 内含 `manifest.json + bank.jsonl + media/**`，
  没有说 `bank.jsonl` 是否再压一层。
- **我的最小假设**：两者都支持 —— `PackCodec.stream(InputStream, gzipped = null)` 按 GZIP
  魔数 `1f 8b` 自动识别；条目名也接受 `bank.jsonl` / `bank.jsonl.gz` / `bank.json`。
- **建议改法**：§3 明确「bundle 内为明文 `bank.jsonl`（整包已是 zip 压缩，不再二次压缩）」。

---

## 6.【小】§2 未规定 `vehicles` 缺失/为空时的行为

- **现象**：若某题 `vehicles` 缺失或被写成 `[]`，按字面存储会让这道题在任何
  `Scope(subject, vehicle)` 查询里都查不到（等于凭空丢题）。
- **我的最小假设**：按 README「车型默认小车」，缺失/空 → `["car"]`；出现非法值（如 `plane`）
  仍然报 `PackFormatException`（带行号）。
- **建议改法**：§2 补一句「`vehicles` 缺省为 `["car"]`」。

---

## 7.【说明】§3「媒体异步补齐」与 §5 `UpdateState` 的关系

- **现象**：§3.4 说媒体「在题目导入后**异步**补齐」，§5 的状态机里又有
  `DownloadingMedia(done,total)`，TASK 4 说「成功后再进入 DownloadingMedia」；若把媒体
  放到**另一个**任务里，则 §3.6 的「全量更新成功**且媒体全部就绪**后 `retain()`」在两个任务
  之间无法判定。
- **我的最小假设**：媒体补齐在**同一个** WorkManager 加急任务内、题目导入之后再执行
  （状态依次 `DownloadingPack → Importing → DownloadingMedia → UpToDate`）。
  「异步」体现在：媒体失败不回滚题目、失败下次 `check()`/自动更新时重试，UI 先显示占位。
- **建议改法**：§3.4 把「异步」表述为「题目导入之后、失败不影响题目、可重试」，
  避免与任务边界混淆（如需真正拆成第二个任务，`retain` 的判定条件要重写）。

---

## 8.【提示拼装】§6 未约定 `OkHttpClient` 的限定符，存在 `DuplicateBindings` 风险

- **现象**：ASSEMBLY.md §4 说「04 提供 UpdateModule（BankUpdater，**自带 OkHttpClient**）」，
  而 03 的 Coil 网络层同样需要 `OkHttpClient`。若两边都在 `SingletonComponent` 里提供
  **无限定符**的 `OkHttpClient`，拼装时 Hilt 报 `DuplicateBindings`，且不会在各自模块
  编译期暴露。
- **我的最小假设**：`UpdateModule` 的 `OkHttpClient` 加自定义限定符 `@UpdateHttp`，
  因此即使 03 提供无限定符版本也不冲突。
- **建议改法**：ASSEMBLY.md §8 的冲突表补一行「`DuplicateBindings: OkHttpClient` → 给
  自己模块的绑定加限定符」。

---

## 9.【提示 02】:app 侧必须配合的三件事（拼装清单，非合同缺陷）

合同没写、但缺了就会「编译过、运行炸」：

1. `Application` 实现 `androidx.work.Configuration.Provider`，并
   `setWorkerFactory(HiltWorkerFactory())`（否则 `UpdateWorker` 无法实例化）；
2. debug 清单引用 `android:networkSecurityConfig="@xml/network_security_config"`
   （资源由 `:core:update` 的 debug 源集提供；若 02 自己也有同名配置且值不同，需在其
   application 上加 `tools:replace`）；
3. Android 13+ 由 :app 请求 `POST_NOTIFICATIONS` 运行时权限（04 只做兼容判断，
   未授权时静默跳过通知，不影响更新本身）。
