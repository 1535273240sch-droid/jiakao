"""增量计算与"客户端应用增量"的参考实现。"""

from __future__ import annotations

from jiakao_pipeline.diff import apply_records, compute_delta, delta_from_files


def q(qid: str, stem: str = "题干", rev: int = 1, subject: int = 1, chapter: str = "s1-c01") -> dict:
    """造一条最小题目记录。"""
    return {
        "id": qid, "subject": subject, "vehicles": ["car"], "type": "judge", "chapter_id": chapter,
        "tags": [], "stem": stem,
        "options": [{"key": "A", "text": "正确"}, {"key": "B", "text": "错误"}],
        "answer": ["A"], "explain": "", "media": [], "rev": rev,
    }


def test_new_and_deleted_and_changed() -> None:
    """新增 → upsert；消失 → 删除行；内容变了 → upsert。"""
    old = [q("s1-000001"), q("s1-000002", stem="旧题干"), q("s1-000003")]
    new = [q("s1-000001"), q("s1-000002", stem="新题干", rev=2), q("s1-000004")]
    delta = compute_delta(old, new)
    assert [item["id"] for item in delta.upserts] == ["s1-000002", "s1-000004"]
    assert delta.deletes == ["s1-000003"]
    assert delta.changed_ids == ["s1-000002"]
    assert delta.records[-1] == {"id": "s1-000003", "deleted": True}


def test_no_change_gives_empty_delta() -> None:
    """内容完全一致 → 增量为空。"""
    old = [q("s1-000001"), q("s1-000002")]
    delta = compute_delta(old, list(old))
    assert delta.is_empty


def test_first_version_has_no_delta() -> None:
    """没有上一版（空旧集）时全部都是 upsert。"""
    delta = compute_delta([], [q("s1-000001")])
    assert len(delta.upserts) == 1
    assert delta.deletes == []


def test_apply_records_matches_full_replace() -> None:
    """应用增量（replaceAll=False）== 直接用全量（replaceAll=True）。"""
    v1 = {item["id"]: item for item in [q("s1-000001"), q("s1-000002", stem="旧"), q("s1-000003")]}
    new_rows = [q("s1-000001"), q("s1-000002", stem="新", rev=2), q("s1-000004")]
    delta = compute_delta(list(v1.values()), new_rows)

    incremental = apply_records(v1, delta.records, replace_all=False)
    full = apply_records({}, new_rows, replace_all=True)
    assert incremental == full
    assert set(incremental) == {"s1-000001", "s1-000002", "s1-000004"}


def test_apply_records_full_path_clears_bank() -> None:
    """全量路径（replaceAll=True）必须丢掉本地多余数据。"""
    local = {item["id"]: item for item in [q("s1-000009")]}
    result = apply_records(local, [q("s1-000001")], replace_all=True)
    assert set(result) == {"s1-000001"}


def test_upserts_are_sorted_by_contract_key() -> None:
    """upsert 行按 (subject, chapter.order, id) 排序，保证可复现。"""
    rows = [q("s4-000001", subject=4, chapter="s4-c02"), q("s1-000002", chapter="s1-c01")]
    delta = compute_delta([], rows, {"s1-c01": 1, "s4-c02": 2})
    assert [item["id"] for item in delta.upserts] == ["s1-000002", "s4-000001"]


def test_delta_from_files(tmp_path) -> None:
    """从两个 jsonl 文件算增量。"""
    from jiakao_pipeline.util import write_jsonl

    old_path = write_jsonl(tmp_path / "old.jsonl", [q("s1-000001")])
    new_path = write_jsonl(tmp_path / "new.jsonl", [q("s1-000001"), q("s1-000002")])
    delta = delta_from_files(old_path, new_path)
    assert [item["id"] for item in delta.upserts] == ["s1-000002"]
    assert delta_from_files(None, new_path).is_empty is False
