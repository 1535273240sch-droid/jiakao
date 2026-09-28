"""章节表加载与章节映射。"""

from __future__ import annotations

from pathlib import Path

import pytest

from jiakao_pipeline.chapters import (
    ChapterError,
    chapter_order_map,
    first_chapter_of,
    load_chapters,
    map_chapter,
)

from conftest import CHAPTERS_FILE


@pytest.fixture
def chapters():
    """默认章节表。"""
    return load_chapters(CHAPTERS_FILE)


def test_default_chapters_cover_both_subjects(chapters) -> None:
    """章节表必须同时覆盖科目一与科目四，且 id/order 合法。"""
    subjects = {c.subject for c in chapters}
    assert subjects == {1, 4}
    for chapter in chapters:
        assert chapter.id.startswith(f"s{chapter.subject}-c")
        assert chapter.name
        assert chapter.order >= 1
    orders = chapter_order_map(chapters)
    assert orders["s1-c01"] == 1


def test_manifest_dict_only_has_contract_fields(chapters) -> None:
    """manifest 里只能出现合同 §3 规定的 4 个字段（keywords 不能泄漏）。"""
    payload = chapters[0].manifest_dict()
    assert set(payload) == {"id", "subject", "name", "order"}


def test_map_chapter_by_id_and_name(chapters) -> None:
    """先按 id 精确匹配，再按 name 精确匹配。"""
    assert map_chapter(chapters, 1, "s1-c02") == ("s1-c02", None)
    assert map_chapter(chapters, 1, "交通信号") == ("s1-c03", None)
    assert map_chapter(chapters, 4, "伤员急救知识") == ("s4-c05", None)


def test_map_chapter_by_keyword(chapters) -> None:
    """自由文本章节名按关键词命中数映射。"""
    chapter_id, warning = map_chapter(chapters, 1, "禁令标志与标线识别")
    assert chapter_id == "s1-c03"
    assert warning is None


def test_map_chapter_fallback_warns(chapters) -> None:
    """映射不到时兜底到该科目第一章并给出告警（不静默）。"""
    chapter_id, warning = map_chapter(chapters, 4, "完全无法识别的内容")
    assert chapter_id == "s4-c01"
    assert warning and "兜底" in warning


def test_first_chapter_of_returns_lowest_order(chapters) -> None:
    """取某科目 order 最小的章节。"""
    assert first_chapter_of(chapters, 4).id == "s4-c01"


def test_bad_chapter_id_rejected(tmp_path: Path) -> None:
    """id 不合规（应为 s1-c01 形式）要报错。"""
    path = tmp_path / "bad.yaml"
    path.write_text(
        "version: 1\nchapters:\n  - {id: 'X1', subject: 1, name: '错', order: 1}\n"
        "  - {id: 's4-c01', subject: 4, name: '对', order: 1}\n",
        encoding="utf-8",
    )
    with pytest.raises(ChapterError, match="章节 id 非法"):
        load_chapters(path)


def test_subject_mismatch_rejected(tmp_path: Path) -> None:
    """id 前缀与 subject 字段冲突要报错。"""
    path = tmp_path / "bad.yaml"
    path.write_text(
        "version: 1\nchapters:\n  - {id: 's1-c01', subject: 4, name: '错', order: 1}\n",
        encoding="utf-8",
    )
    with pytest.raises(ChapterError, match="subject"):
        load_chapters(path)


def test_missing_subject_rejected(tmp_path: Path) -> None:
    """只覆盖一个科目要报错（否则组卷会缺章节）。"""
    path = tmp_path / "bad.yaml"
    path.write_text(
        "version: 1\nchapters:\n  - {id: 's1-c01', subject: 1, name: '一', order: 1}\n",
        encoding="utf-8",
    )
    with pytest.raises(ChapterError, match="科目 4"):
        load_chapters(path)


def test_duplicate_order_rejected(tmp_path: Path) -> None:
    """同一科目内 order 重复要报错。"""
    path = tmp_path / "bad.yaml"
    path.write_text(
        "version: 1\nchapters:\n"
        "  - {id: 's1-c01', subject: 1, name: '一', order: 1}\n"
        "  - {id: 's1-c02', subject: 1, name: '二', order: 1}\n"
        "  - {id: 's4-c01', subject: 4, name: '三', order: 1}\n",
        encoding="utf-8",
    )
    with pytest.raises(ChapterError, match="order"):
        load_chapters(path)


def test_custom_chapters_file_is_used(tmp_path: Path) -> None:
    """章节表可自行编辑（改名/调 order 都生效）。"""
    path = tmp_path / "chapters.yaml"
    path.write_text(
        "version: 1\nchapters:\n"
        "  - {id: 's1-c01', subject: 1, name: '我的第一章', order: 7}\n"
        "  - {id: 's4-c01', subject: 4, name: '我的第四章', order: 2}\n",
        encoding="utf-8",
    )
    chapters = load_chapters(path)
    assert map_chapter(chapters, 1, "我的第一章") == ("s1-c01", None)
    assert chapter_order_map(chapters)["s4-c01"] == 2
