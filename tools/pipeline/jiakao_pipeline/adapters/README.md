# 适配器（如何把你自己的题库接进来）

适配器只干一件事：把你的原始题库变成统一的中间表示 `RawQuestion`
（见 `base.py`），后面 normalize / media / validate / build 全部与来源无关。

## 内置三个

| `--adapter` | 输入 | 说明 |
|---|---|---|
| `csv` | `.csv` | 最常用。编码自动试 utf-8-sig → utf-8 → gb18030 |
| `json` | `.json` / `.jsonl` | 对象数组、`{"questions":[...]}`、一行一题都行 |
| `sqlite` | `.db` / `.sqlite` | 需要时加 `--table 表名`，不给则自动挑表 |

## 列名怎么认

按别名表匹配（大小写、空格不敏感），中文英文都认，顺序即优先级：

| 内部字段 | 认得的列名（部分） | 必需 |
|---|---|---|
| `src_id` | 编号 / id / 题号 / qid | 否，但**强烈建议**（决定 id 稳定性） |
| `stem` | 题干 / stem / 题目 / question | ✅ |
| `answer` | 答案 / answer / 正确答案 / correct | ✅ |
| `type` | 题型 / type（judge/single/multi 或 判断/单选/多选） | 否，可推断 |
| `subject` | 科目 / subject（1、4、科目一、科四、s1） | 否，可从章节推 |
| `chapter` | 章节 / chapter / 分类 / 知识点 | 否，缺省按关键词映射 |
| `options` | 选项A..选项H / A..H / 选项（单列） | ✅（判断题可省） |
| `explain` | 解析 / explain / analysis | 否 |
| `tags` | 标签 / tags / 知识点（逗号/顿号分隔） | 否 |
| `vehicles` | 车型 / vehicles（car/truck/bus/moto 或 小汽车/货车…） | 否，默认 car |
| `media` | 图片 / media / image / 附件 | 否 |

列名对不上时，用命令行覆盖，例如：

```bash
python -m jiakao_pipeline import --adapter csv --input ./raw/q.csv \
  --map stem=我的题干 --map answer=我的答案 --map media=我的图
```

## 选项列的三种写法

1. 分列：`选项A,选项B,选项C,选项D`（或 `A,B,C,D`）
2. 单列竖线分隔：`A.减速通过|B.加速通过|C.停车等候`
3. JSON 数组（json 适配器）：`"options":[{"key":"A","text":"减速"},...]`

判断题可以不写选项，normalize 会自动补成合同的固定形式 `A 正确 / B 错误`。

## 媒体列

- 值可以是文件名、相对路径、绝对路径；相对路径按 `--images 目录` 解析。
- 多个用 `;` `|` `,` 分隔，或写 JSON 数组。
- 想强制类型用 `文件名::image` / `::anim` / `::video`；不写则由扩展名和时长自动判定。

## 新增一个适配器

1. 新建 `your_adapter.py`，继承 `Adapter`，实现 `iter_raw(path)`，产出 `RawQuestion`；
2. 用 `@register` 装饰器注册（`name` 就是 `--adapter` 的值）；
3. 在 `__init__.py` 的 `load_adapters()` 里 import 一下，让注册生效；
4. 补一个 `tests/test_adapters.py` 用例。

参考 `csv_adapter.py`（最短的实现）即可，注意三点：

- `src_key` 必须一题一个、且不随题干文字改动而变（改错别字要紧 id 不变、`rev` +1）；
- `stem` 用 `clean_text()` 清洗，但**不要**在这里做答案排序等业务规范化（那是 normalize 的活）；
- 遇到损坏数据抛 `AdapterError`，并在 `RawQuestion.warnings` 里记录可继续的告警。
