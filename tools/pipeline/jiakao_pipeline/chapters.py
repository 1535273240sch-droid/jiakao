"""章节表加载与章节映射（chapters.yaml）。

章节表是"章节 id 的定义域"：normalize 用它把原始数据的章节字段映射成 ``s1-c01`` 这样的 id，
validate 用它校验 ``chapter_id`` 合法性，build 用它写 ``manifest.chapters`` 并做全量排序。
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from pathlib import Path

import yaml

from .util import clean_text

CHAPTER_ID_RE = re.compile(r"^s([14])-c(\d{2})$")

#: 章节表默认查找顺序（显式入参 → 环境变量 → 当前工作目录 → 包目录的上一级）。
ENV_CHAPTERS = "JIAKAO_CHAPTERS"


class ChapterError(ValueError):
    """章节表本身不合法。"""


@dataclass(frozen=True)
class ChapterDef:
    """章节定义（含 keywords，keywords 不进入 manifest）。"""

    id: str
    subject: int
    name: str
    order: int
    keywords: tuple[str, ...] = field(default=())

    def manifest_dict(self) -> dict:
        """只输出合同 §3 ``chapters`` 允许的 4 个字段。"""
        return {"id": self.id, "subject": self.subject, "name": self.name, "order": self.order}


def default_chapters_path() -> Path:
    """返回默认章节表路径（存在性不保证）。"""
    here = Path(__file__).resolve()
    candidates = [
        Path.cwd() / "chapters.yaml",
        here.parent.parent / "chapters.yaml",  # tools/pipeline/chapters.yaml
        here.parent / "chapters.yaml",  # 打包进包内的兜底位置
    ]
    env = os.environ.get(ENV_CHAPTERS)
    if env:
        candidates.insert(0, Path(env))
    for cand in candidates:
        if cand.exists():
            return cand
    return Path.cwd() / "chapters.yaml"


def load_chapters(path: str | Path | None = None) -> list[ChapterDef]:
    """读章节表并做结构校验（id 格式、subject 一致、order 唯一）。"""
    resolved = Path(path) if path else default_chapters_path()
    if not resolved.exists():
        raise ChapterError(f"章节表不存在: {resolved}")
    with open(resolved, "r", encoding="utf-8") as fh:
        raw = yaml.safe_load(fh)
    if not isinstance(raw, dict) or "chapters" not in raw:
        raise ChapterError(f"章节表缺少 chapters 顶层键: {resolved}")

    chapters: list[ChapterDef] = []
    seen_ids: set[str] = set()
    seen_order: set[tuple[int, int]] = set()
    for idx, item in enumerate(raw["chapters"] or [], 1):
        if not isinstance(item, dict):
            raise ChapterError(f"chapters[{idx}] 不是映射")
        cid = clean_text(item.get("id"))
        match = CHAPTER_ID_RE.match(cid)
        if not match:
            raise ChapterError(f"章节 id 非法（应为 s1-c01 形式）: {cid!r}")
        subject = int(item.get("subject", match.group(1)))
        if subject != int(match.group(1)):
            raise ChapterError(f"章节 {cid} 的 subject={subject} 与 id 前缀不一致")
        name = clean_text(item.get("name"))
        if not name:
            raise ChapterError(f"章节 {cid} 缺少 name")
        order = int(item.get("order", idx))
        if cid in seen_ids:
            raise ChapterError(f"章节 id 重复: {cid}")
        if (subject, order) in seen_order:
            raise ChapterError(f"科目 {subject} 的 order={order} 重复")
        seen_ids.add(cid)
        seen_order.add((subject, order))
        keywords = tuple(clean_text(k) for k in (item.get("keywords") or []) if clean_text(k))
        chapters.append(ChapterDef(cid, subject, name, order, keywords))

    if not chapters:
        raise ChapterError(f"章节表为空: {resolved}")
    for subject in (1, 4):
        if not any(c.subject == subject for c in chapters):
            raise ChapterError(f"章节表缺少科目 {subject} 的章节")
    return chapters


def chapter_index(chapters: list[ChapterDef]) -> dict[str, ChapterDef]:
    """id → 章节定义。"""
    return {c.id: c for c in chapters}


def chapter_order_map(chapters: list[ChapterDef]) -> dict[str, int]:
    """id → order（排序用）。"""
    return {c.id: c.order for c in chapters}


def first_chapter_of(chapters: list[ChapterDef], subject: int) -> ChapterDef:
    """某科目的第一个章节（order 最小）。"""
    pool = [c for c in chapters if c.subject == subject]
    if not pool:
        raise ChapterError(f"章节表没有科目 {subject} 的章节")
    return min(pool, key=lambda c: c.order)


def map_chapter(
    chapters: list[ChapterDef],
    subject: int,
    raw_chapter: str | None,
) -> tuple[str, str | None]:
    """把原始章节字段映射成合同 chapter_id。

    返回 ``(chapter_id, warning)``；warning 非空表示用了兜底映射。
    匹配优先级：id 精确 → name 精确 → 关键词计数 → 该科目首章节兜底。
    """
    pool = [c for c in chapters if c.subject == subject]
    raw = clean_text(raw_chapter)
    if raw:
        lowered = raw.lower()
        for chapter in pool:
            if chapter.id.lower() == lowered:
                return chapter.id, None
        for chapter in pool:
            if chapter.name == raw:
                return chapter.id, None
        haystack = raw
        scored: list[tuple[int, int, ChapterDef]] = []
        for chapter in pool:
            hits = sum(1 for kw in chapter.keywords if kw and kw in haystack)
            if hits:
                scored.append((hits, -chapter.order, chapter))
        if scored:
            scored.sort(key=lambda t: (t[0], t[1]), reverse=True)
            best = scored[0][2]
            if len(scored) > 1 and scored[1][0] == scored[0][0]:
                return best.id, f"章节 {raw!r} 关键词命中并列，取 order 最小者 {best.id}"
            return best.id, None
    fallback = first_chapter_of(chapters, subject)
    return fallback.id, f"章节 {raw or '<空>'!r} 无法映射，兜底到 {fallback.id}（{fallback.name}）"
