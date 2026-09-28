"""SQLite 适配器：直连用户自备的 ``.db/.sqlite`` 题库表。

用法：``--adapter sqlite --input quiz.db --table questions``；
未指定 ``--table`` 时使用库中第一个含"题干"类列的普通表。
"""

from __future__ import annotations

import sqlite3
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


@register
class SqliteAdapter(Adapter):
    """SQLite → RawQuestion。"""

    name = "sqlite"

    def __init__(
        self,
        images_dir: str | Path | None = None,
        table: str | None = None,
        field_map: dict[str, str] | None = None,
    ) -> None:
        super().__init__(images_dir)
        self.table = table
        self.field_map = dict(field_map or {})

    def _aliases(self, field: str) -> list[str]:
        custom = self.field_map.get(field)
        aliases = list(DEFAULT_FIELD_ALIASES.get(field, ()))
        if custom:
            aliases.insert(0, custom)
        return aliases

    def iter_raw(self, path: str | Path) -> Iterator[RawQuestion]:
        """遍历表数据。"""
        p = Path(path)
        if not p.exists():
            raise AdapterError(f"SQLite 文件不存在: {p}")
        connection = sqlite3.connect(f"file:{p}?mode=ro", uri=True)
        try:
            connection.row_factory = sqlite3.Row
            table = self.table or self._first_table(connection, p)
            try:
                cursor = connection.execute(f'SELECT * FROM "{table}"')  # noqa: S608 - 表名来自 --table 或库内枚举
            except sqlite3.Error as exc:
                raise AdapterError(f"读取表 {table} 失败: {exc}") from exc
            for index, row in enumerate(cursor, 1):
                yield self._row_to_question({k: row[k] for k in row.keys()}, p, index, table)
        finally:
            connection.close()

    def _first_table(self, connection: sqlite3.Connection, path: Path) -> str:
        """挑第一个"看起来是题目表"的普通表。"""
        tables = [
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
            )
        ]
        if not tables:
            raise AdapterError(f"库中没有数据表: {path}")
        stem_aliases = {clean_text(a).lower() for a in self._aliases("stem")}
        for table in tables:
            columns = [clean_text(c[1]).lower() for c in connection.execute(f'PRAGMA table_info("{table}")')]
            if stem_aliases & set(columns):
                return table
        return tables[0]

    def _row_to_question(self, row: dict, path: Path, index: int, table: str) -> RawQuestion:
        stem = clean_text(pick_field(row, self._aliases("stem")))
        options = option_columns(row)
        answer_raw = pick_field(row, self._aliases("answer"))
        answer = []
        if answer_raw is not None:
            text = clean_text(answer_raw)
            if text:
                answer = [text]
        src_id = clean_text(pick_field(row, self._aliases("src_id")))
        src_key = f"sqlite:{path.name}:{table}:{src_id}" if src_id else f"sqlite:{path.name}:{table}:row{index}"
        question = RawQuestion(
            src_key=src_key,
            stem=stem,
            source=f"sqlite:{path.name}:{table}:{index}",
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
            question.warnings.append("SQLite 未提供答案")
        return question
