# 05 · 题库内容流水线（Python）

把**你手上的原始题库**（CSV / JSON / SQLite）变成 App 能增量更新的静态题库包 ——
也就是合同 §2（题目 JSON）与 §3（题库包格式）规定的那些文件。

> **数据来源声明**：本工具只做格式转换与打包，**不内置、不下载、不爬取任何真题**。
> 题库存量数据请自行合法获取（购买授权、官方开放数据、自行编写等），合规责任在使用者。

---

## 1. 快速开始

```bash
cd out/tools/pipeline

# 1) 环境（Python 3.11+）
make setup                     # 或：python tasks.py setup（Windows 无 make 时）
# 可选系统工具（不装也能跑）：ffmpeg（真视频转码）、gif2webp（动图回退）

# 2) 用自带样例跑通全流程（20 道自编演示题 + 静图/动图）
make sample build serve

# 3) 验证
curl -s http://localhost:8000/manifest.json | head
```

`make sample build serve` 之后：

- `http://localhost:8000/manifest.json` 可访问，且通过 `manifest.schema.json`；
- 手机/模拟器"题库源地址"填 `http://10.0.2.2:8000/`（模拟器）或 `http://<本机IP>:8000/`（真机，同一局域网）；
- **必须以 `/` 结尾**（合同 §3）。

其它命令：`make validate | test | bench | clean`，`make help` 看全部目标。

---

## 2. 接入你自己的题库数据

### 2.1 四步流水线

```bash
# ① 原始数据 → work/（自动把引用的图片拷进 work/media_src，便于复现）
python -m jiakao_pipeline import --adapter csv --input ./raw/questions.csv \
       --images ./raw/img --out ./work

# ② 清洗 / 统一选项答案 / 推断题型 / 映射章节 / 分配稳定 id
python -m jiakao_pipeline normalize --in ./work --chapters chapters.yaml --state ./state

# ③ 媒体转码（WebP/mp4）+ 内容寻址去重 → dist/media，并产出合同格式的 work/questions.jsonl
python -m jiakao_pipeline media --in ./work --out ./dist --jobs 8

# ④ 校验 + 打包（自动与上一版 diff、bank_version 自增、写 manifest 与报告）
python -m jiakao_pipeline validate --in ./work --dist ./dist
python -m jiakao_pipeline build    --in ./work --dist ./dist --state ./state
```

改完题库后**只重复 ①→④** 即可：`bank_version` 自增，`dist/delta/` 里出现 `v{A}-v{B}.jsonl.gz`，
App 端就能走增量更新。

### 2.2 最小 CSV 样例（字段映射示例）

```csv
编号,科目,题型,章节,标签,题干,选项A,选项B,选项C,选项D,答案,解析,图片
T001,科目一,判断,交通信号,标志;禁令,如图所示这个标志表示禁止停车,,, ,,正确,红圈红斜杠,img/a.png
T002,科目四,多选,伤员急救知识,急救,对伤员止血时哪些做法正确？,加压包扎,抬高患肢,直接撒药粉,用绳索捆扎,A、B,现场急救要点,img/b.gif
```

要点：

| 你关心的 | 说明 |
|---|---|
| **`编号` 列** | 强烈建议提供。它是"这道题是谁"的稳定锚点：改了题干，`id` 不变、`rev` +1。没有它就只能靠"题干+选项"的内容哈希认题（改错别字会变成新题） |
| 判断题 | `选项A..D` 可以留空，`答案` 写 `正确/错误/对/错/√/×` 都行，normalize 会自动补成合同的 `A 正确 / B 错误` |
| 答案写法 | `A`、`A,C`、`AC`、`A、C`、`1,3` 都能识别；最终统一成**按字母序排序**的数组 |
| 多选 | 判断题以外的题，答案 ≥2 个自动判为 `multi` |
| 章节 | 写章节 id（`s1-c03`）或章节名（`交通信号`）最准；写别的自由文本会按 `chapters.yaml` 的 `keywords` 猜，猜不到兜底到该科目第一章并在报告里告警 |
| 图片 | 相对路径按 `--images` 解析；多个用 `;`/`|`/`,` 分隔；可用 `文件名::anim` 强制指定类型（`image`/`anim`/`video`） |
| 选择题选项 | 也支持单列 `选项` 写 `A.减速|B.加速|C.停车` |

列名对不上时用 `--map` 覆盖，不用改 CSV：

```bash
python -m jiakao_pipeline import --adapter csv --input ./raw/q.csv \
  --map stem=我的题干 --map answer=我的答案 --map media=我的图
```

### 2.3 JSON / SQLite

```bash
python -m jiakao_pipeline import --adapter json   --input ./raw/questions.json   --out ./work
python -m jiakao_pipeline import --adapter sqlite --input ./raw/quiz.db --table questions --out ./work
```

JSON 支持对象数组、`{"questions":[...]}`、一行一题（`.jsonl`）；
选项可以是 `{"A":"甲","B":"乙"}`、`["甲","乙"]` 或 `[{"key":"A","text":"甲"}]`。
适配器细节与"如何新增一个适配器"见 `jiakao_pipeline/adapters/README.md`。

---

## 3. 产物长什么样（`dist/`）

```
dist/
  manifest.json                    清单（合同 §3；App 先拉它判断要不要更新）
  full/bank-v12.jsonl.gz           全量快照（GZIP，固定 mtime=0，可按字节复现）
  delta/v11-v12.jsonl.gz           增量：改动的题整题 upsert + 消失的题删除行
  media/{sha前2位}/{sha}.webp      内容寻址，跨版本复用，永不覆盖
  bundle/bundle-v12.zip            离线整包（U 盘/adb 导入用）
  report.md                        人类可读报告：题量/分布/媒体体积/告警/校验结果
```

`report.md` 每次构建都会刷新，建议发布前扫一眼"告警"和"组卷可行性"两节。

---

## 4. 发布

```bash
# 自有服务器 / Nginx（推荐，config 模板见 ../server/nginx.conf.example）
bash ../server/publish_rsync.sh user@host:/var/www/jiakao/ ./dist

# Cloudflare R2
bash ../server/publish_r2.sh r2://my-bucket ./dist

# GitHub Pages（注意仓库体积：media 会进 git 历史）
bash ../server/publish_ghpages.sh ./dist

# 本地/局域网联调（带 CORS + Range + ETag + 缓存头）
python -m jiakao_pipeline serve --dist ./dist --port 8000
```

发布后把根地址（**以 `/` 结尾**）填进 App 的"题库源地址"。手机上装好 App → 首次同步即可。

> 若走 CDN/R2，注意给 `full/`、`delta/`、`bundle/` 设置**较短缓存**（或发布时带版本号刷新），
> `media/` 可以永久缓存（文件名即内容哈希）。

---

## 5. 关键设计（与 04 模块的接口约定）

| 主题 | 做法 |
|---|---|
| **稳定 id** | `state/id_registry.json` 记录"源键/内容哈希 → id"。改内容 id 不变、`rev` +1；**删除的 id 永不复用**；id 按科目单调自增，只增不减 |
| **可复现构建** | 全量/增量 gzip 固定 `mtime=0`，bundle zip 固定时间戳与权限位，JSON 键顺序固定 → 同输入字节级一致（`--released-at` / `SOURCE_DATE_EPOCH` 可固定时间戳） |
| **幂等构建** | 题库内容（含章节表）没变时 `bank_version` 不递增，产物不变；`--always-bump` 可强制递增 |
| **增量链** | 默认保留最近 5 个连续增量（`--keep-deltas`）；客户端按合同 §3.2 判断"增量链是否连续 + 总字节 < 全量字节"，否则回退全量 |
| **媒体** | 内容寻址 + 处理缓存（源 `mtime+size` + 参数）；同内容自动去重；跨版本只增不删，`retain()` 由 App 侧负责 |
| **动图/视频** | GIF/APNG/短 MP4 → 动态 WebP（保持帧率，超 1.5MB 自动降质量/降帧并在报告标黄）；> 5s 视频保留 mp4（H.264 baseline + faststart，需要 ffmpeg） |
| **移除媒体** | 本流水线**不删** `dist/media` 里的旧文件（合同要求跨版本复用）。要清理由 App 的 `retain()` 或人工处理 |

### 用例：模拟客户端应用增量 == 全量

`tests/test_contract_compat.py` 里实现了一个"最小客户端"，按合同 §3 的更新语义
（`replaceAll=false` 依次应用增量 / `replaceAll=true` 全量重建）验证：

```
v1 + delta(v1→v2)  ==  v2 全量     （逐题逐字段，含 media 的 sha256/kind/w/h/bytes 与 rev）
断链 / 增量更贵 → 自动回退全量
```

---

## 6. 常见问题

**Q：`media` 报"素材文件不存在"？**
相对路径是按 `--images` 解析的。检查 `--images` 是否指向图片目录，或 CSV 里写的是绝对路径。

**Q：构建说"校验未通过"？**
看 `dist/report.md` 的错误列表。validate 会在构建前跑，任何一条不过都会非零退出 —— 这是故意的，
避免把坏数据推给 App。

**Q：`bank_version` 没涨？**
说明题库内容与上一版完全一致（幂等保护）。改数据后重跑 `import → normalize → media → build`；
确实想强制递增用 `--always-bump`。

**Q：动图太大？**
`--anim-max-bytes` 调小或调大；超预算的处理会写进报告"降级/告警的媒体"一节（标黄）。

**Q：真视频没被转码？**
装 `ffmpeg`（`apt install ffmpeg` / `brew install ffmpeg`）。没装时保留原文件并在报告里告警。

**Q：构建太慢？**
`--jobs` 控制媒体并行度（默认 `min(8, CPU核数)`）；同一批素材第二次构建走缓存，秒级完成。

**Q：能改章节表吗？**
可以，`chapters.yaml` 就是给使用者改的。注意 `id` 必须保持 `s{1|4}-c{两位}` 格式，
改了名字/order 会让 `bank_version` 递增（章节也属于题库内容）。

---

## 7. 目录结构

```
tools/pipeline/
  jiakao_pipeline/
    cli.py            # typer 命令入口（import/normalize/media/validate/build/diff/serve/sample/bench/info/publish）
    adapters/         # csv / json / sqlite 适配器 + 如何新增
    chapters.py       # 章节表加载与映射
    normalize.py      # 清洗、选项/答案规范化、题型推断、章节映射
    idregistry.py     # 稳定 id（state/id_registry.json）
    media.py          # 转码、去重、缓存、多进程
    validate.py       # 合同 §2/§3 校验（构建前置门槛）
    build_pack.py     # manifest / full / delta / media / bundle
    diff.py           # 增量计算 + 客户端应用语义的参考实现
    report.py         # dist/report.md
    serve.py          # 联调服务器（CORS/Range/ETag/缓存头）
    sample.py         # 20 道自编样例题 + Pillow 绘制素材
    bench.py          # 3000 题 + 500 媒体 性能基准
    schema/           # question.schema.json / manifest.schema.json
  chapters.yaml       # 科一/科四章节表（可编辑）
  tests/              # pytest（140 例，含合同兼容性测试）
  work/ dist/ state/  # 中间产物与本地状态（git 忽略，可重建）
```

## 8. 验收自测

```bash
make test          # pytest 全绿
make sample build  # 样例端到端
make bench         # 3000 题 + 500 媒体：首次构建 ≤ 2 分钟，有缓存 ≤ 15 秒
```
