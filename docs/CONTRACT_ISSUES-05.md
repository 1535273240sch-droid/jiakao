# 05-content-pipeline · 合同疑问与缺陷记录

按合同开头的要求："发现合同有缺陷 → 在自己的 `out/CONTRACT_ISSUES.md` 记录（现象、建议改法），
并按**最小假设**继续。"下面是本模块实际撞到的问题，以及我采用的假设。

---

## #1 `bundle` 内的 `manifest.json` 自引用（**必读**，影响 04 的离线导入）

**现象**
合同 §3 规定 `bundle/bundle-v{N}.zip` 内含 `manifest.json + bank.jsonl + media/**`；
而 `manifest.json` 里的 `bundle` 字段又要声明**这个 zip 自身**的 `sha256` 与 `bytes`。
"文件内容包含自身哈希"在数学上不存在不动点：用定点迭代会让 zip 体积在两个值之间来回震荡，
永远不收敛（实测确认，不是实现 bug）。

**最小假设（已实现）**
- `dist/manifest.json`（**发布清单**）中的 `bundle.sha256/bytes` 是**真实值**，客户端下载离线包后
  就用它校验 —— 校验链的唯一依据是发布清单。
- zip 内那份 `manifest.json` 的 `bundle.sha256` 用占位值 `0×64`、`bytes` 用固定宽度占位值
  （固定宽度是为了让构建字节级可复现）；其余字段（`bank_version` / `chapters` / `full` / `deltas`）
  与发布清单逐字段一致。
- 因此 **04 的 `importLocalPack()` 不应对 zip 内的 manifest 做"校验 bundle 哈希"**，
  只应读取 `bank_version` / `chapters`（版本与章节）、读 `bank.jsonl`（题目）、读 `media/**`（媒体）。
  这与合同 §3.5 对"下载"的校验要求不冲突：离线导入没有下载过程。

**建议改法（任选其一）**
1. 明确"bundle 内 manifest 的 `bundle` 字段允许为 `null`/省略"，或
2. `bundle` 只放 `bank.jsonl + media/**`，发布清单由服务器单独提供，或
3. 把清单拆成两层：`manifest.json`（含 bundle 哈希，不放 zip 内）与 `pack.json`（放 zip 内，不含自引用）。

---

## #2 `released_at` 与"字节级可复现"的边界

**现象**
TASK 要求 `gzip` 使用固定 `mtime=0` 以保证"同输入字节级可复现"，但 `manifest.json` 里必然含
`released_at` 时间戳，两次构建天然不同字节。

**最小假设（已实现）**
- "可复现"的范围 = `full/bank-v{N}.jsonl.gz`、`delta/*.jsonl.gz`、`bundle/bundle-v{N}.zip`
  以及**给定 `--released-at` 后的** `manifest.json`；
- 提供 `--released-at` 与 `SOURCE_DATE_EPOCH` 环境变量来固定时间戳，供 CI/回归比对使用
  （测试 `test_build_is_byte_reproducible` 就是这么做的）。

**建议改法**：在合同/TASK 里写明"可复现"是否含 `manifest.json`，以及推荐用 `SOURCE_DATE_EPOCH`。

---

## #3 `kind` 与 `ext` 的对应关系没有明写

**现象**
合同 §2 只说 `media.kind ∈ image|anim|video`、`ext ∈ webp|mp4`，以及"静图/动图统一 WebP"。
但 `kind=anim` + `ext=mp4` 在文法上是合法的 —— 而语义上不该存在（动图必须是 WebP）。

**最小假设（已实现）**：`validate` 强制 `image→webp`、`anim→webp`、`video→mp4`，违反即报错。

**建议改法**：在 §2 里直接写成 `kind=image|anim → ext=webp`、`kind=video → ext=mp4`。

---

## #4 增量链"不完整"时的行为未定义

**现象**
§3「更新语义」第 2 条只定义了两种情形：连续链且更便宜 → 增量；否则 → 全量。
但没写"链存在但不完整"（例如服务端只保留了 `v8→v9`，客户端停在 `v5`）时该怎么办。

**最小假设（已实现）**：视为"没有可用链" → 回退全量（`replaceAll=true`）。
`tests/test_contract_compat.py::test_chain_break_falls_back_to_full` 覆盖这一条。

**建议改法**：在 §3 里补一句"若不存在从本地版本到远端版本的完整连续链，则一律走全量"。

---

## #5 媒体"只增不删"与 `retain()` 的职责边界

**现象**
TASK 说打包时 `media/**` "仅新增文件，旧文件保留"；§3.6 又说客户端 `retain()` 会删除"不再被引用的媒体"。
服务端与客户端的清理职责没有对齐：服务端永远不删，`dist/media` 会随版本单调增长。

**最小假设（已实现）**：05 **只增不删**（`dist/media` 是内容寻址的，删了会让旧版本客户端 404）。
需要清理时由运维在 CDN/服务器侧按版本做生命周期，或人工确认后删除。

**建议改法**：明确"服务端只增不删；清理仅发生在客户端本地"，并给出服务端保留策略建议
（例如"至少保留最近 N 个版本引用到的媒体"）。

---

## #6 `chapter_id` 的命名规则只在示例中出现

**现象**
§2 的示例里 `chapter_id` 是 `s1-c03`、`s4-c02`，但 §3 的 `chapters[]` 只要求"id 字符串"，
没写格式约束，也没说 `chapter_id` 是否必须是 manifest 里出现过的 id。

**最小假设（已实现）**：按示例推断格式 `s{1|4}-c\d{2}`，并要求每个题目的 `chapter_id`
必须存在于章节表（否则 App 的章节列表会漏题）。`chapters.yaml` 是给使用者编辑的，
格式约束写在文件头部注释里。

**建议改法**：在 §2/§3 里把 `chapter_id` 的正则与"必须在 manifest.chapters 内"写进合同。

---

## #7 `min_app_version_code` 语义未定义

**现象**
§3 的 manifest 里有 `min_app_version_code`，但没写客户端拿到它该做什么（低于则拒绝更新？提示升级？）。

**最小假设（已实现）**：默认写 `1`，并支持 `--min-app-version-code` 覆盖；
05 侧不参与任何拦截判断。

**建议改法**：在 §3 里写明客户端语义（建议："`versionCode < min_app_version_code` 时应提示升级并停止更新"）。

---

## #8 `vehicles` 是否允许为空

**现象**
§2 说 `vehicles` 是 `car|truck|bus|moto` 的数组，§4 组卷按 `Scope(subject, vehicle)` 抽题，
但没说空数组是否合法（空数组意味着这道题在任何车型下都抽不到，等于废题）。

**最小假设（已实现）**：`vehicles` 至少 1 个元素；原始数据缺省时填 `["car"]`，
未识别的车型值丢弃并在报告里体现（`normalize` 阶段）。

**建议改法**：在 §2 里明确 `vehicles` 非空，并说明"缺省语义 = car"。
