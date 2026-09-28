"""JSON / JSONL 适配器：接受"对象数组"或"一行一题"两种形式。

题目对象可以自带 ``id``/``src_id`` 作为稳定身份；也可以用 ``options`` 数组 +
``answer`` 数组的规范形式。选项支持：
``[{"key":"A","text":"减速"}, ...]``、``["减速","加速"]``、``{"A":"减速"}``。
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Iterator

from ..util import clean_text
from .base import (
    DEFAULT_FIELD_ALIASES,
    Adapter,
    AdapterError,
    RawQuestion,
    normalize_vehicles,
    parse_subject,
    pick_field,
    register,
    split_list,
)


@register
class JsonAdapter(Adapter):
    """JSON / JSONL → RawQuestion。"""

    name = "json"

    def __init__(self, images_dir: str | Path | None = None, field_map: dict[str, str] | None = None) -> None:
        super().__init__(images_dir)
        self.field_map = dict(field_map or {})

    def _aliases(self, field: str) -> list[str]:
        custom = self.field_map.get(field)
        aliases = list(DEFAULT_FIELD_ALIASES.get(field, ()))
        if custom:
            aliases.insert(0, custom)
        return aliases

    def iter_raw(self, path: str | Path) -> Iterator[RawQuestion]:
        """读 JSON（对象数组 / 单对象）或 JSONL。"""
        p = Path(path)
        if not p.exists():
            raise AdapterError(f"JSON 文件不存在: {p}")
        records = self._load(p)
        for index, row in enumerate(records, 1):
            if not isinstance(row, dict):
                raise AdapterError(f"{p}:{index} 不是 JSON 对象")
            yield self._row_to_question(row, p, index)

    @staticmethod
    def _load(path: Path) -> list[dict]:
        text = path.read_text(encoding="utf-8-sig")
        stripped = text.strip()
        if not stripped:
            raise AdapterError(f"JSON 文件为空: {path}")
        if stripped.startswith("["):
            data = json.loads(stripped)
            if not isinstance(data, list):
                raise AdapterError(f"JSON 顶层应为数组: {path}")
            return data
        if stripped.startswith("{"):
            try:  # 先试整体单对象
                data = json.loads(stripped)
                if isinstance(data, dict):
                    for key in ("questions", "data", "items", "list"):
                        if isinstance(data.get(key), list):
                            return data[key]
                    return [data]
            except json.JSONDecodeError:
                pass
            # 退化为 JSONL
            rows: list[dict] = []
            for lineno, line in enumerate(stripped.splitlines(), 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError as exc:
                    raise AdapterError(f"{path}:{lineno} 不是合法 JSON: {exc}") from exc
            return rows
        raise AdapterError(f"JSON 顶层既不是数组也不是对象: {path}")

    def _row_to_question(self, row: dict, path: Path, index: int) -> RawQuestion:
        stem = clean_text(pick_field(row, self._aliases("stem")))
        options = self._parse_options(row)
        answer = self._parse_answer(pick_field(row, self._aliases("answer")))
        src_id = clean_text(pick_field(row, self._aliases("src_id")))
        src_key = f"json:{path.name}:{src_id}" if src_id else f"json:{path.name}:row{index}"
        question = RawQuestion(
            src_key=src_key,
            stem=stem,
            source=f"json:{path.name}:{index}",
            subject=parse_subject(pick_field(row, self._aliases("subject"))),
            vehicles=normalize_vehicles(pick_field(row, self._aliases("vehicles"))),
            qtype=clean_text(pick_field(row, self._aliases("type"))) or None,
            chapter=clean_text(pick_field(row, self._aliases("chapter"))) or None,
            tags=split_list(pick_field(row, self._aliases("tags"))),
            options=options,
            answer=answer,
            explain=clean_text(pick_field(row, self._aliases("explain"))),
            media=self.resolve_media(pick_field(row, self._aliases("media"))),
        )
        if not answer:
            question.warnings.append("JSON 未提供答案")
        return question

    @staticmethod
    def _parse_options(row: dict) -> list[tuple[str, str]]:
        """选项可能是数组/对象，也兼容 ``选项A`` 分列。"""
        raw = pick_field(row, ("options", "选项", "choices", "选项列表"))
        out: list[tuple[str, str]] = []
        if isinstance(raw, dict):
            for key, value in raw.items():
                out.append((clean_text(key), clean_text(value)))
        elif isinstance(raw, list):
            for idx, item in enumerate(raw):
                letter = chr(ord("A") + idx)
                if isinstance(item, dict):
                    out.append((clean_text(item.get("key", letter)) or letter, clean_text(item.get("text"))))
                else:
                    out.append((letter, clean_text(item)))
        elif raw is not None:
            text = clean_text(raw)
            if text:
                out.append(("", text))
        if out:
            return sorted(out, key=lambda kv: kv[0])
        # 回到 "选项A/A" 分列
        from .base import option_columns

        return option_columns(row)

    @staticmethod
    def _parse_answer(value: object) -> list[str]:
        """答案可能是数组、字母串或中文"正确/错误"。"""
        if value is None:
            return []
        if isinstance(value, (list, tuple, set)):
            return [clean_text(v) for v in value if clean_text(v)]
        text = clean_text(value)
        return [text] if text else []
