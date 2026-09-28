"""适配器基类：原始题库 → :class:`RawQuestion`（未规范化、未分配 id 的中间表示）。

新增适配器只需三步（详见 ``README.md``）：
1. 继承 :class:`Adapter`，实现 :meth:`Adapter.iter_raw`；
2. 用 :func:`resolve_media` 把"图片/媒体"列解析成源文件路径；
3. 在 :data:`ADAPTERS` 注册名字。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Iterator, Sequence

from ..util import clean_text

#: 合同允许的车型。
VEHICLES = ("car", "truck", "bus", "moto")


class AdapterError(ValueError):
    """原始数据不合法（缺列、文件不存在等）。"""


@dataclass
class RawMedia:
    """一条待处理媒体：源文件路径 + 调用方给出的类型提示。"""

    src: str
    kind_hint: str | None = None


@dataclass
class RawQuestion:
    """规范化之前的题目。

    ``src_key`` 是稳定身份锚点（写 id 注册表用），务必与"题"一一对应且不随内容改动而变
    （例如 CSV 的"编号"列）。没有天然主键时适配器会退化为 ``文件:行号``。
    """

    src_key: str
    stem: str
    source: str = ""
    subject: int | None = None
    vehicles: list[str] = field(default_factory=list)
    qtype: str | None = None
    chapter: str | None = None
    tags: list[str] = field(default_factory=list)
    options: list[tuple[str, str]] = field(default_factory=list)
    answer: list[str] = field(default_factory=list)
    explain: str = ""
    media: list[RawMedia] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        """序列化成 ``work/questions.raw.jsonl`` 的一行。"""
        return {
            "src_key": self.src_key,
            "source": self.source,
            "subject": self.subject,
            "vehicles": list(self.vehicles),
            "type": self.qtype,
            "chapter": self.chapter,
            "tags": list(self.tags),
            "stem": self.stem,
            "options": [[k, v] for k, v in self.options],
            "answer": list(self.answer),
            "explain": self.explain,
            "media": [{"src": m.src, "kind_hint": m.kind_hint} for m in self.media],
            "warnings": list(self.warnings),
        }

    @classmethod
    def from_dict(cls, row: dict) -> "RawQuestion":
        """从 ``work/questions.raw.jsonl`` 的一行还原。"""
        return cls(
            src_key=row.get("src_key", ""),
            stem=row.get("stem", ""),
            source=row.get("source", ""),
            subject=row.get("subject"),
            vehicles=list(row.get("vehicles") or []),
            qtype=row.get("type"),
            chapter=row.get("chapter"),
            tags=list(row.get("tags") or []),
            options=[(o[0], o[1]) for o in (row.get("options") or [])],
            answer=list(row.get("answer") or []),
            explain=row.get("explain", ""),
            media=[RawMedia(m.get("src", ""), m.get("kind_hint")) for m in (row.get("media") or [])],
            warnings=list(row.get("warnings") or []),
        )


class Adapter:
    """适配器基类。子类实现 :meth:`iter_raw`。"""

    name: str = "base"

    def __init__(self, images_dir: str | Path | None = None) -> None:
        self.images_dir = Path(images_dir) if images_dir else None

    def iter_raw(self, path: str | Path) -> Iterator[RawQuestion]:
        """产出原始题目。"""
        raise NotImplementedError

    # ───────── 子类常用工具 ─────────

    def resolve_media(self, value: object) -> list[RawMedia]:
        """把媒体列解析成 :class:`RawMedia` 列表。

        支持：``a.png``、``a.png;b.gif``、``a.png|b.gif``、JSON 数组字符串、
        以及 ``path::kind`` 形式显式指定类型（kind ∈ image|anim|video）。
        """
        items: list[RawMedia] = []
        for token in _split_media(value):
            kind_hint: str | None = None
            if "::" in token:
                token, kind_hint = token.split("::", 1)
                kind_hint = clean_text(kind_hint).lower() or None
            token = clean_text(token)
            if not token:
                continue
            items.append(RawMedia(src=self.locate_media(token), kind_hint=kind_hint))
        return items

    def locate_media(self, reference: str) -> str:
        """把媒体引用解析成可读的路径字符串（相对/绝对皆可，import 阶段会拷贝进 work）。"""
        ref = clean_text(reference)
        if not ref:
            return ""
        candidate = Path(ref)
        if candidate.is_absolute():
            return str(candidate)
        if self.images_dir is not None:
            joined = self.images_dir / candidate
            # 原样返回（哪怕暂不存在），由 import 阶段统一报"文件缺失"
            return str(joined)
        return ref


_MEDIA_SPLIT_RE = re.compile(r"[;；|,，\n\r]+")


def _split_media(value: object) -> list[str]:
    """拆分媒体列。list/tuple 直接展开；字符串按分隔符切。"""
    if value is None:
        return []
    if isinstance(value, (list, tuple, set)):
        out: list[str] = []
        for item in value:
            if isinstance(item, dict):
                item = item.get("src") or item.get("path") or item.get("file") or ""
            out.extend(_split_media(item))
        return out
    text = clean_text(value)
    if not text:
        return []
    stripped = text.strip()
    if stripped.startswith("[") and stripped.endswith("]"):
        import json

        try:
            return _split_media(json.loads(stripped))
        except json.JSONDecodeError:
            pass
    return [part for part in (clean_text(p) for p in _MEDIA_SPLIT_RE.split(text)) if part]


# ───────── 列名映射 ─────────

#: 常见中英文列名 → 内部字段。适配器按顺序找第一个命中且非空的列。
DEFAULT_FIELD_ALIASES: dict[str, Sequence[str]] = {
    "src_id": ("编号", "id", "题号", "试题编号", "src_id", "no", "number", "qid"),
    "subject": ("科目", "subject", "科目号", "科目id"),
    "type": ("题型", "type", "题目类型", "qtype"),
    "chapter": ("章节", "chapter", "章节名", "科目章节", "chapter_id", "分类"),
    "tags": ("标签", "tags", "tag", "知识点", "考点"),
    "vehicles": ("车型", "vehicles", "vehicle", "适用车型"),
    "stem": ("题干", "stem", "题目", "问题", "question", "title"),
    "explain": ("解析", "explain", "analysis", "答案解析", "说明"),
    "answer": ("答案", "answer", "correct", "正确答案", "key"),
    "media": ("图片", "media", "image", "images", "媒体", "图", "附件"),
}


def pick_field(row: dict, aliases: Sequence[str]) -> object:
    """按别名顺序取第一个存在且非空的列值（大小写与空格不敏感）。"""
    lowered = {clean_text(k).lower(): v for k, v in row.items() if k is not None}
    for alias in aliases:
        key = clean_text(alias).lower()
        if key in lowered:
            value = lowered[key]
            if value is None:
                continue
            if isinstance(value, str) and not value.strip():
                continue
            return value
    return None


_SUBJECT_MAP = {
    "1": 1, "一": 1, "科目一": 1, "科一": 1, "s1": 1, "subject1": 1, "01": 1,
    "4": 4, "四": 4, "科目四": 4, "科四": 4, "s4": 4, "subject4": 4, "04": 4,
}


def parse_subject(value: object) -> int | None:
    """解析科目：``1/4``、``科目一``、``科四``、``s1`` → 1 或 4；无法识别返回 None。"""
    text = clean_text(value).lower().replace(" ", "")
    if not text:
        return None
    if text in _SUBJECT_MAP:
        return _SUBJECT_MAP[text]
    if text.startswith("subject"):
        text = text[len("subject"):]
    if text in _SUBJECT_MAP:
        return _SUBJECT_MAP[text]
    if text.isdigit() and int(text) in (1, 4):
        return int(text)
    return None


def normalize_vehicles(value: object) -> list[str]:
    """车型列 → 合同枚举；无法识别的一律丢弃（由 normalize 记告警）。"""
    out: list[str] = []
    for item in split_list(value):
        token = item.lower()
        alias = {
            "小汽车": "car", "小型汽车": "car", "轿车": "car", "c1": "car", "c2": "car",
            "货车": "truck", "大型货车": "truck", "b2": "truck",
            "客车": "bus", "大型客车": "bus", "a1": "bus",
            "摩托": "moto", "摩托车": "moto", "d": "moto", "e": "moto",
        }.get(token, token)
        if alias in VEHICLES and alias not in out:
            out.append(alias)
    return out


def split_list(value: object) -> list[str]:
    """把"标签/车型"这类列拆成列表（支持 JSON 数组、逗号、顿号、竖线）。"""
    if value is None:
        return []
    if isinstance(value, (list, tuple, set)):
        return [clean_text(v) for v in value if clean_text(v)]
    text = clean_text(value)
    if not text:
        return []
    if text.startswith("[") and text.endswith("]"):
        import json

        try:
            return split_list(json.loads(text))
        except json.JSONDecodeError:
            pass
    return [part for part in (clean_text(p) for p in re.split(r"[,，、;；|/]+", text)) if part]


def option_columns(row: dict) -> list[tuple[str, str]]:
    """收集 ``选项A``/``A``/``option_a`` 形式的选项列，按键排序。"""
    found: dict[str, str] = {}
    for raw_key, raw_value in row.items():
        if raw_key is None:
            continue
        key_text = clean_text(raw_key)
        letter = _option_letter(key_text)
        if letter is None or raw_value is None:
            continue
        text = clean_text(raw_value)
        if text:
            found.setdefault(letter, text)
    return sorted(found.items(), key=lambda kv: kv[0])


_OPTION_COL_RE = re.compile(r"^(?:选项|option|opt|choice|答案)?[\s_\-:：]*([A-Ha-h１２３４])[)）.]?$", re.I)


def _option_letter(column_name: str) -> str | None:
    """列名 → 选项字母；不是选项列返回 None。"""
    name = clean_text(column_name)
    if not name:
        return None
    match = _OPTION_COL_RE.match(name)
    if match:
        token = match.group(1)
        if token.isdigit():
            return chr(ord("A") + int(token) - 1)
        return token.upper()
    if len(name) == 1 and name.isdigit() and 1 <= int(name) <= 8:
        return chr(ord("A") + int(name) - 1)
    return None


#: 名字 → 适配器类，供 CLI ``--adapter`` 使用。
ADAPTERS: dict[str, type[Adapter]] = {}


def register(cls: type[Adapter]) -> type[Adapter]:
    """注册适配器（装饰器）。"""
    ADAPTERS[cls.name] = cls
    return cls


def get_adapter(name: str) -> type[Adapter]:
    """按名字取适配器类。"""
    key = clean_text(name).lower()
    if key not in ADAPTERS:
        raise AdapterError(f"未知适配器 {name!r}，可用: {', '.join(sorted(ADAPTERS))}")
    return ADAPTERS[key]


def load_adapters() -> dict[str, type[Adapter]]:
    """导入内置适配器模块（触发注册）并返回注册表。"""
    from . import csv_adapter, json_adapter, sqlite_adapter  # noqa: F401

    return ADAPTERS


def iter_raw_questions(
    adapter_name: str,
    path: str | Path,
    images_dir: str | Path | None = None,
) -> Iterable[RawQuestion]:
    """便捷入口：``get_adapter`` + ``iter_raw``。"""
    load_adapters()
    adapter_cls = get_adapter(adapter_name)
    return adapter_cls(images_dir).iter_raw(path)
