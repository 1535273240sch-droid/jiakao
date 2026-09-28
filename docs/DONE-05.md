# 05-content-pipeline · 完成报告（DONE）

模块负责人交付说明。所有产出在 `out/` 下，目录结构 = 最终仓库结构。
**未修改合同**；合同疑问记录在 [`out/CONTRACT_ISSUES.md`](CONTRACT_ISSUES.md)。

---

## 一、已完成

### 交付物清单（与 TASK 逐项对应）

```
out/tools/pipeline/
  pyproject.toml requirements.txt Makefile README.md .gitignore tasks.py
  chapters.yaml                          # 科一 5 章 / 科四 6 章，keywords 可编辑
  jiakao_pipeline/
    cli.py                               # import/normalize/media/validate/build/diff/serve/sample/bench/info/publish
    __main__.py __init__.py util.py models.py
    adapters/{__init__,base,csv_adapter,json_adapter,sqlite_adapter}.py + README.md
    normalize.py chapters.py idregistry.py media.py validate.py build_pack.py
    diff.py report.py serve.py sample.py bench.py pipeline.py
    schema/{question.schema.json,manifest.schema.json,__init__.py}
  tests/                                 # 12 个测试文件，140 个用例
  sample/ work/ dist/ state/             # 生成物（.gitignore）
out/server/
  serve.sh publish_rsync.sh publish_r2.sh publish_ghpages.sh nginx.conf.example
out/CONTRACT_ISSUES.md
```

### 功能要点（对照 TASK）

| 要求 | 实现 |
|---|---|
| 三适配器（csv/json/sqlite）+ 字段映射 + "如何新增适配器"文档 | `adapters/`，编码自动 utf-8-sig→utf-8→gb18030；`--map 字段=列名` 覆盖 |
| 清洗 / 统一选项键（A-D、对错→A/B）/ 答案规范化并排序 / 题型推断 / 章节映射 | `normalize.py` + `chapters.py`（id/名称/关键词/兜底四级映射，兜底会告警） |
| 稳定 ID：哈希绑定、改动保 id 且 rev+1、删除不复用 | `idregistry.py`（源键优先、内容哈希兜底；科目单调计数器只增不减；退役 id 永不复用） |
| 媒体：静图→WebP(q82，透明无损，长边>1080 缩)、动图→动态 WebP(保帧率、≤1.5MB 自动降质量/降帧)、真视频→mp4(H.264 baseline+faststart)、sha256 命名去重、EXIF 清除、缓存、多进程 | `media.py`（Pillow 优先，回退 gif2webp/ffmpeg；缓存键=源 mtime+size+参数；ProcessPoolExecutor） |
| 校验：Schema + id 唯一/格式 + 判断题选项 + answer 规则 + 章节表 + 媒体文件/sha/bytes/宽高 + stem/rev + 重复告警 + `dist/report.md` | `validate.py` + `report.py`（构建前置门槛，失败非零退出） |
| 打包：manifest / full（稳定排序）/ delta（upsert+删除行，保留最近 5 个且链连续）/ media 仅新增 / bundle 离线整包 / gzip mtime=0 可复现 | `build_pack.py` + `diff.py` |
| 命令：Makefile 的 setup/sample/validate/build/serve/publish-*/test + `make sample build serve` 端到端 | `Makefile`（另有 Windows 等价 `tasks.py`） |
| 发布：serve.sh（CORS/Range 提示）、publish_rsync/r2/ghpages、nginx.conf.example | `out/server/` |
| 样例：20 道自编题 + Pillow 静图/动图 | `sample.py`（三种格式同内容；含大图/透明图/同内容重复图/3 个动图） |

### 合同契合度

- 题目 JSON 逐字段、键顺序与合同 §2 一致；`manifest.json` 字段/命名与 §3 一致（`full/bank-v{N}.jsonl.gz`、`delta/v{A}-v{B}.jsonl.gz`、`media/{sha[0:2]}/{sha}.{ext}`、`bundle/bundle-v{N}.zip`、`media_base`）。
- 全量排序 = `(subject, chapter.order, id)`；增量 upsert 行同样排序。
- 确定性：gzip `mtime=0`、zip 固定时间戳/权限位、JSON 键顺序固定 → 同输入 + 同 `--released-at` 时 **dist 全树字节级一致**（有测试）。
- 组卷可行性（合同 §4）在报告里做软性提示（科目一需 100 题 / 科目四需 50 题），题量不足只告警不阻断构建。

---

## 二、自测命令与结果

环境：Windows 11 + Python 3.14.7（venv）；`ffmpeg` / `gif2webp` 本机缺失；无 `make`、无 `bash`。

```bash
cd out/tools/pipeline
python tasks.py setup            # 建 venv + 装依赖（等价 make setup）
python tasks.py sample build     # 等价 make sample build
python tasks.py validate test bench
```

| 自测项 | 结果 |
|---|---|
| **pytest** | ✅ `140 passed in 29.35s`（覆盖适配器、答案规范化、ID 稳定、媒体转码/去重/缓存、校验失败用例、增量 diff、可复现构建、HTTP 头、CLI 端到端） |
| **`sample build` 通过** | ✅ bank_version=1，20 题，full `3333B`/`3353B`，bundle `64275B`，`manifest.json` 通过 `manifest.schema.json` |
| **增量应用 == 全量**（合同兼容性） | ✅ `tests/test_contract_compat.py`：自写最小客户端按 §3 语义（含 `replaceAll` 分支、字节比较、断链回退）验证 `v1+delta(v1→v2) == v2 全量`，逐题逐字段（含 media 的 sha256/kind/w/h/bytes 与 rev） |
| **删除再构建 → 含删除行，id 不复用** | ✅ 实测：改 1 题 / 删 1 题 / 加 1 题 → `delta/v1-v2.jsonl.gz` 3 条记录（`upsert s1-000001 rev=2`、`upsert s4-000009`、`{"id":"s1-000004","deleted":true}`）；把删掉的题加回来会拿到**新** id |
| **性能（3000 题 + 500 媒体）** | ✅ 首次构建 **5.01s**（240px 合成图）/ **14.75s**（1440px，接近真实照片）；缓存构建 **2.18s / 2.27s**。目标 ≤120s / ≤15s |
| **HTTP 联调** | ✅ `manifest.json` 200 + `Access-Control-Allow-Origin: *` + `Cache-Control: no-cache` + ETag；`If-None-Match` → **304**；`Range: bytes=0-99` → **206** + `Content-Range`；`media/**.webp` → `immutable` + `image/webp`；`bundle` Range → 206 |
| **可复现构建** | ✅ 同输入两次构建（不同目录）→ dist 全树字节一致（含 full/delta/bundle） |
| **媒体缓存** | ✅ 二次构建 11/11 命中、0 重新编码；产物 sha256 不变 |
| **去重** | ✅ 同内容两张图 → 1 个产物、同一 sha256 |

`dist/report.md` 摘要（样例构建）：20 题（科一 12 / 科四 8；判断 7 / 单选 7 / 多选 6），11 题含媒体，去重后 10 个媒体共 61.69 KB，校验通过、0 错误、0 告警。

---

## 三、未完成 / 已知限制（请拼装时知悉）

1. **发布脚本未在真实环境执行**。本机是 Windows 且没有 `bash`，`serve.sh` / `publish_rsync.sh` / `publish_r2.sh` / `publish_ghpages.sh` 只做了逐行静态审查，**没有实际跑过 rsync / rclone / wrangler / git push**。
   → 建议在 Linux（或 WSL/Git Bash）上先对 `serve.sh` + `publish_rsync.sh` 做一次实跑；`nginx.conf.example` 已标注 `nginx -t` 与排查用的 `curl -I` 清单。
2. **ffmpeg 相关两条路径未端到端实测**（本机无 ffmpeg）：①真视频（>5s）转 H.264 baseline + faststart；②短 MP4 → 动态 WebP。
   已实现并有单测覆盖的是：**无 ffmpeg 时的降级行为**（真视频原样保留并告警、需要转封装时明确报错、mp4 时长解析、宽高探测）。
   → 有 ffmpeg 的机器上请用真实视频各跑一次，确认 `kind=video/mp4` 与 `kind=anim/webp` 产出符合预期。
3. **动图长边缩放**：合同只规定"静图长边 > 1080 缩到 1080"，我把同一规则也应用到了动图（`--anim-max-edge`，默认 1080），目的是控制体积。若不需要可显式调大该参数。
4. **`bundle` 内 manifest 的自引用**：`bundle.sha256` 在 zip 内是占位值（数学上无解，见 `CONTRACT_ISSUES.md` #1）。发布清单 `dist/manifest.json` 里的值是真实值。**04 的 `importLocalPack()` 不要校验 zip 自身哈希。**
5. **媒体只增不删**：`dist/media` 是内容寻址的，跨版本永不删除（删了会让旧版本客户端 404）。长期运行需要人工/CDN 生命周期策略，或按 `CONTRACT_ISSUES.md` #5 的结论处理。
6. **`bank_version` 幂等**：题库内容（含章节表）与上一版完全相同时**不递增**版本号（便于 CI 反复构建）；需要强制递增用 `--always-bump`。这是一条我做的设计选择，写进了 README 与报告。
7. **章节表内容是我按公开大纲整理的通用划分**（科一 5 章 / 科四 6 章），只保证结构可用；真实章节划分请使用者按自己的题库编辑 `chapters.yaml`（改 id 名字/order 会让版本号递增，注意同步给 App 侧）。
8. **多进程转码在 Windows(spawn) 下可用**（实测 `--jobs 8` 正常）；但 pytest 内部统一用 `jobs=1`，避免 spawn 在测试进程里的额外开销与递归风险。
9. **`python tasks.py distclean` 不删 `.venv`**（Windows 上正在使用的解释器删不掉，实测会把 venv 弄坏）。要连 venv 一起清理请先退出 shell。
10. **未做**（TASK 未要求，仅记录）：媒体 OCR/内容审核、题库查重的人工合并、CDN 回源鉴权、App 侧埋点统计。

---

## 四、拼装注意事项（给集成的人）

1. **目录映射**：`out/tools/pipeline` → 仓库根 `tools/pipeline`；`out/server` → 仓库根 `server`（与合同 §1 所有权表一致）。`work/ dist/ state/ sample/ bench/ .venv/` 都不要进仓库（`.gitignore` 已写）。
2. **依赖**：只需 Python 3.11+ 与 `requirements.txt`（pillow/pydantic/jsonschema/pyyaml/typer/tqdm/pytest），无付费服务、无网络访问。系统工具 `ffmpeg`（真视频）与 `gif2webp`（动图回退）可选，缺失时自动降级并在报告里告警。
3. **与 04 的接口就是 `dist/` 里的静态文件**，不需要任何后端。04 实现时请对齐这几点：
   - 权威清单是 `manifest.json`；`full`/`delta`/`bundle` 的 `sha256`+`bytes` 都应校验（下载后）。
   - 增量应用：`{"id":…,"deleted":true}` 删除、其余整题 upsert；`replaceAll=false`。
   - 判定"连续增量链"：从本地版本出发，沿 `to` 反查 `from`，能一路走到本地版本才算连续；总字节 ≥ `full.bytes` 时改用全量。
   - `chapters` 非空时整体替换章节表（05 每次都会写全量章节）。
   - `media_base` 是相对 manifest 所在目录的（`media/`）。
   - 05 保证 `id` 稳定不复用，所以 App 侧的错题本/收藏可以直接用 `id` 关联（题目内容变化只是 `rev` +1）。
4. **媒体清理**由 App 侧 `retain()` 负责（服务端只增不删）；`allMediaRefs()` 拿到的就是当前版本引用的全部媒体。
5. **发布顺序建议**：先传 `media/` → `full/`+`delta/`+`bundle/` → **最后传 `manifest.json`**（`publish_rsync.sh` 就是这么做的），避免客户端拿到指向未上传文件的清单。
6. **生产环境必须 HTTPS**：Android 9+ 默认禁止明文 HTTP；用 `nginx.conf.example` 的 server 块 + certbot 签证书即可。
7. 若要**离线包分发**：`adb push dist/bundle/bundle-vN.zip /sdcard/Download/`，App 设置页导入（合同 §3.5 的 `importLocalPack`）。

---

## 五、如何接入你自己的题库数据

完整说明见 `out/tools/pipeline/README.md` 第 2 节（含最小 CSV 样例与字段映射表）。

最短路径：

```bash
cd tools/pipeline
make setup                       # 或 python tasks.py setup

# 你的原始数据（示例：CSV + 一个图片目录）
python -m jiakao_pipeline import --adapter csv \
       --input ./raw/questions.csv --images ./raw/img --out ./work
python -m jiakao_pipeline normalize --in ./work --chapters chapters.yaml --state ./state
python -m jiakao_pipeline media     --in ./work --out ./dist --jobs 8
python -m jiakao_pipeline build     --in ./work --dist ./dist --state ./state
cat dist/report.md               # 先看告警与组卷可行性，再发布
```

要点：
- **一定要有"编号"类稳定主键列**（或 `--map src_id=你的列`），否则只能靠题干+选项的内容哈希认题，改错别字会变成新题。
- 列名对不上不用改数据，用 `--map stem=我的题干 --map answer=我的答案 --map media=我的图` 覆盖。
- 判断题可以不写选项列；答案写 `正确/错误/对/错/√/×`；多选答案写 `A、C` 或 `AC` 都行。
- 章节写 `s1-c03` 或章节名最准；写自由文本会按 `chapters.yaml` 的 `keywords` 猜，猜不到会兜底并告警。
- 换了别的数据库/格式：照 `jiakao_pipeline/adapters/README.md` 加一个适配器（继承 `Adapter`，实现 `iter_raw`，用 `@register` 注册）。

> 再次声明：本流水线**不含任何真题数据**，也不含下载/爬取功能；样例题与图片都是代码生成的演示素材。
