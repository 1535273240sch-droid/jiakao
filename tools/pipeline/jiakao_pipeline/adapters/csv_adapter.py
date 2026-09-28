"""CSV 适配器：最常用入口（用户自己导出/整理的表格）。

- 编码自动尝试 ``utf-8-sig``（Excel 导出的 BOM）→ ``utf-8`` → ``gb18030``。
- 表头支持中英文别名（见 :data:`base.DEFAULT_FIELD_ALIASES`）。
- 选项既支持 ``选项A..选项D``/``A..D`` 分列，也支持 ``选项`` 单列（``A.xx|B.yy``）。
- 结果提取自 **附件** 列的行（最后一题之后没有答案行时自动忽略）。
"""

from __future__ import annotations

import csv
import re
from pathlib import Path
from typing import Iterator

from ..util import clean_text
from .base import (
    DEFAULT_FIELD_ALIASES,
    Adapter,
    AdapterError,
    RawQuestion,
    normalize_vehicles,
    option_columns,
    parse_subject,
    pick_field,
    register,
    split_list,
)

_ENCODINGS = ("utf-8-sig", "utf-8", "gb18030")
_OPTION_ANSWER_RE = re.compile(r"^\s*【?([A-Ha-h１-８1-8])】?\s*[:：.、)）]?\s*(.*)$")


@register
class CsvAdapter(Adapter):
    """CSV → RawQuestion。"""

    name = "csv"

    def __init__(self, images_dir: str | Path | None = None, field_map: dict[str, str] | None = None) -> None:
        super().__init__(images_dir)
        #: 额外/覆盖的列名映射：``{"stem": "我的题干列"}``
        self.field_map = dict(field_map or {})

    def _aliases(self, field: str) -> list[str]:
        custom = self.field_map.get(field)
        aliases = list(DEFAULT_FIELD_ALIASES.get(field, ()))
        if custom:
            aliases.insert(0, custom)
        return aliases

    def iter_raw(self, path: str | Path) -> Iterator[RawQuestion]:
        """逐行产出题目。"""
        p = Path(path)
        if not p.exists():
            raise AdapterError(f"CSV 文件不存在: {p}")
        rows = self._read_rows(p)
        if not rows:
            raise AdapterError(f"CSV 没有数据行: {p}")
        for index, row in enumerate(rows, 1):
            question = self._row_to_question(row, p, index)
            if question is not None:
                yield question

    # ───────── 内部 ─────────

    def _read_rows(self, path: Path) -> list[dict]:
        last_error: Exception | None = None
        for encoding in _ENCODINGS:
            try:
                with open(path, "r", encoding=encoding, newline="") as fh:
                    return list(csv.DictReader(fh))
            except UnicodeDecodeError as exc:  # 换下一个编码
                last_error = exc
                continue
        raise AdapterError(f"CSV 编码无法识别（试过 {_ENCODINGS}）: {last_error}")

    def _row_to_question(self, row: dict, path: Path, index: int) -> RawQuestion | None:
        stem = clean_text(pick_field(row, self._aliases("stem")))
        options = option_columns(row)
        if not options:
            # 单列选项："A.减速|B.加速"
            options = self._split_inline_option(pick_field(row, ("选项", "choices", "option", "options")))
        answer = self._parse_answer(pick_field(row, self._aliases("answer")))
        if not stem and not options:
            return None  # 空行
        src_id = clean_text(pick_field(row, self._aliases("src_id")))
        src_key = f"csv:{path.name}:{src_id}" if src_id else f"csv:{path.name}:row{index}"
        q = RawQuestion(
            src_key=src_key,
            stem=stem,
            source=f"csv:{path.name}:{index}",
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
            q.warnings.append("CSV 未提供答案")
        return q

    @staticmethod
    def _parse_answer(value: object) -> list[str]:
        """答案列 → 字母列表（保留原样，最终规范化交给 normalize）。"""
        text = clean_text(value)
        if not text:
            return []
        return [text]

    @staticmethod
    def _split_inline_option(value: object) -> list[tuple[str, str]]:
        """把 ``A.减速通过|B.加速通过`` 拆成选项列表。"""
        text = clean_text(value)
        if not text:
            return []
        # 先按分隔符切，再用 "字母+分隔符" 二次切分
        chunks = [c for c in re.split(r"[|;；\n]+", text) if clean_text(c)]
        options: list[tuple[str, str]] = []
        for chunk in chunks:
            match = _OPTION_ANSWER_RE.match(chunk)
            if match:
                options.append((match.group(1), clean_text(match.group(2))))
            else:
                options.append(("", clean_text(chunk)))
        if len(options) == 1:
            parts = [c for c in re.split(r"(?=[A-Ha-h][.、:：)）])", text) if clean_text(c)]
            if len(parts) > 1:
                rebuilt: list[tuple[str, str]] = []
                for part in parts:
                    match = _OPTION_ANSWER_RE.match(part)
                    if match:
                        rebuilt.append((match.group(1), clean_text(match.group(2))))
                if len(rebuilt) > 1:
                    return rebuilt
        return options
