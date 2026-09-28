"""稳定 ID：内容不变复用、改内容 rev+1、删除不复用、科目变更换新号。"""

from __future__ import annotations

from pathlib import Path

from jiakao_pipeline.idregistry import (
    IdRegistry,
    content_hash,
    format_id,
    parse_id,
)


def _hash(subject: int = 1, stem: str = "题干", options=(("A", "甲"), ("B", "乙"))) -> str:
    """便捷内容哈希。"""
    return content_hash(subject, stem, [{"key": k, "text": v} for k, v in options])


def test_id_format_roundtrip() -> None:
    """id 形如 s1-000007，可双向解析。"""
    assert format_id(1, 7) == "s1-000007"
    assert format_id(4, 123456) == "s4-123456"
    assert parse_id("s4-000002") == (4, 2)


def test_sequence_is_monotonic_per_subject(tmp_path: Path) -> None:
    """两个科目各自独立自增，从 1 开始。"""
    registry = IdRegistry.load(tmp_path / "r.json")
    registry.begin_batch()
    assert registry.assign(1, "a", _hash(1, "一")).id == "s1-000001"
    assert registry.assign(4, "b", _hash(4, "四")).id == "s4-000001"
    assert registry.assign(1, "c", _hash(1, "三")).id == "s1-000002"


def test_same_content_same_id_and_rev(tmp_path: Path) -> None:
    """同一份内容重复导入：id 不变、rev 不变（幂等）。"""
    path = tmp_path / "r.json"
    registry = IdRegistry.load(path)
    registry.begin_batch()
    first = registry.assign(1, "k", _hash())
    registry.save()

    again = IdRegistry.load(path)
    again.begin_batch()
    second = again.assign(1, "k", _hash())
    assert second.id == first.id
    assert second.rev == 1
    assert second.is_new is False


def test_key_change_keeps_id_and_bumps_rev(tmp_path: Path) -> None:
    """同一源键（同一道题）改了题干 → id 保持不变、rev+1。"""
    path = tmp_path / "r.json"
    registry = IdRegistry.load(path)
    registry.begin_batch()
    first = registry.assign(1, "csv:q.csv:12", _hash(stem="旧题干"))
    registry.save()

    again = IdRegistry.load(path)
    again.begin_batch()
    second = again.assign(1, "csv:q.csv:12", _hash(stem="新题干"))
    assert second.id == first.id
    assert second.rev == 2
    assert second.matched_by == "key"


def test_hash_match_without_key(tmp_path: Path) -> None:
    """没有源键时靠内容哈希认人（换文件名/行序也能复用 id）。"""
    path = tmp_path / "r.json"
    registry = IdRegistry.load(path)
    registry.begin_batch()
    first = registry.assign(1, None, _hash())
    registry.save()

    again = IdRegistry.load(path)
    again.begin_batch()
    second = again.assign(1, None, _hash())
    assert second.id == first.id
    assert second.matched_by == "hash"


def test_deleted_id_is_never_reused(tmp_path: Path) -> None:
    """删掉一道题后重新加回来，必须拿到**新** id（合同要求不复用）。"""
    path = tmp_path / "r.json"
    registry = IdRegistry.load(path)
    registry.begin_batch()
    first = registry.assign(1, "k", _hash())
    # 本轮只有别的题 → 旧 id 退役
    other = registry.assign(1, "k2", _hash(stem="另一题"))
    retired = registry.finalize([other.id], [1])
    assert retired == [first.id]
    registry.save()

    again = IdRegistry.load(path)
    again.begin_batch()
    re_added = again.assign(1, "k", _hash())
    assert re_added.id != first.id
    assert re_added.id == "s1-000003"  # 计数器只增不减


def test_duplicate_content_in_same_batch_gets_new_id(tmp_path: Path) -> None:
    """同一批里两条内容完全相同的题不能折叠成同一个 id。"""
    registry = IdRegistry.load(tmp_path / "r.json")
    registry.begin_batch()
    first = registry.assign(1, "a", _hash())
    second = registry.assign(1, "b", _hash())
    assert first.id != second.id


def test_subject_change_creates_new_id(tmp_path: Path) -> None:
    """科目变了就是另一道题（id 里含科目）。"""
    registry = IdRegistry.load(tmp_path / "r.json")
    registry.begin_batch()
    first = registry.assign(1, "k", _hash(1))
    moved = registry.assign(4, "k", _hash(4))
    assert moved.id.startswith("s4-")
    assert moved.id != first.id


def test_finalize_only_touches_processed_subjects(tmp_path: Path) -> None:
    """只导入科目一时，科目四的 id 不能被误退役。"""
    registry = IdRegistry.load(tmp_path / "r.json")
    registry.begin_batch()
    s1 = registry.assign(1, "a", _hash(1))
    s4 = registry.assign(4, "b", _hash(4))
    retired = registry.finalize([], [1])
    assert s1.id in retired
    assert registry.entry(s4.id)["retired"] is False


def test_registry_file_is_stable_and_reloadable(tmp_path: Path) -> None:
    """注册表落盘后顺序稳定、统计正确。"""
    path = tmp_path / "state" / "id_registry.json"
    registry = IdRegistry.load(path)
    registry.begin_batch()
    registry.assign(1, "a", _hash(stem="一"))
    registry.assign(1, "b", _hash(stem="二"))
    registry.save()

    text = path.read_text(encoding="utf-8")
    assert text.index("s1-000001") < text.index("s1-000002")
    reloaded = IdRegistry.load(path)
    assert reloaded.stats()["active"] == 2
    assert reloaded.stats()["counters"]["1"] == 2


def test_content_hash_ignores_option_order() -> None:
    """选项顺序变化不算内容变化（同一道题）。"""
    forward = _hash(options=(("A", "甲"), ("B", "乙")))
    backward = _hash(options=(("B", "乙"), ("A", "甲")))
    assert forward == backward
    assert forward != _hash(options=(("A", "乙"), ("B", "甲")))
