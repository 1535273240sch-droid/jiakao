"""打包：产物结构、字段准确性、排序、可复现、幂等、增量链保留策略。"""

from __future__ import annotations

import json
import zipfile
from pathlib import Path

import pytest

from jiakao_pipeline.build_pack import BuildError, build
from jiakao_pipeline.chapters import chapter_order_map
from jiakao_pipeline.util import gunzip_bytes, sha256_bytes
from jiakao_pipeline.validate import validate_manifest

from conftest import DEFAULT_ROWS, Project, row


def test_dist_layout_matches_contract(tmp_path: Path) -> None:
    """dist 目录结构必须与合同 §3 一致。"""
    project = Project(tmp_path)
    project.dataset()
    project.run()
    result = project.build()

    assert (project.dist / "manifest.json").exists()
    assert (project.dist / f"full/bank-v{result.bank_version}.jsonl.gz").exists()
    assert (project.dist / f"bundle/bundle-v{result.bank_version}.zip").exists()
    assert (project.dist / "media").is_dir()
    assert (project.dist / "report.md").exists()
    assert result.bank_version == 1
    assert result.full_count == len(DEFAULT_ROWS)
    assert result.deltas == []


def test_manifest_fields_are_accurate(tmp_path: Path) -> None:
    """manifest 的 sha256/bytes/count 必须与磁盘实际内容一致，且通过 Schema。"""
    project = Project(tmp_path)
    project.dataset()
    project.run()
    project.build()
    manifest = project.manifest()

    assert validate_manifest(manifest) == []
    assert manifest["schema"] == 1
    assert manifest["media_base"] == "media/"
    assert manifest["released_at"].endswith("Z")
    assert [c["id"] for c in manifest["chapters"]][0] == "s1-c01"

    full_path = project.dist / manifest["full"]["url"]
    payload = full_path.read_bytes()
    assert manifest["full"]["bytes"] == len(payload)
    assert manifest["full"]["sha256"] == sha256_bytes(payload)
    assert manifest["full"]["count"] == len(gunzip_bytes(payload).decode("utf-8").splitlines())

    bundle_path = project.dist / manifest["bundle"]["url"]
    assert manifest["bundle"]["bytes"] == bundle_path.stat().st_size
    assert manifest["bundle"]["sha256"] == sha256_bytes(bundle_path.read_bytes())


def test_full_bank_is_sorted_by_contract_key(tmp_path: Path) -> None:
    """全量快照按 (subject, chapter.order, id) 排序。"""
    project = Project(tmp_path)
    project.dataset()
    project.run()
    project.build()
    order = chapter_order_map(project.chapters)
    keys = [
        (q["subject"], order[q["chapter_id"]], q["id"])
        for q in project.full_bank().values()
    ]
    assert keys == sorted(keys)


def test_bundle_contains_manifest_bank_and_media(tmp_path: Path) -> None:
    """离线整包 = manifest.json + bank.jsonl（明文）+ media/**。"""
    project = Project(tmp_path)
    project.dataset()
    project.run()
    result = project.build()
    with zipfile.ZipFile(project.dist / result.bundle_url) as archive:
        names = archive.namelist()
        assert "manifest.json" in names and "bank.jsonl" in names
        media_names = [n for n in names if n.startswith("media/")]
        assert media_names
        assert len(archive.read("bank.jsonl").decode("utf-8").splitlines()) == result.full_count
        inside = json.loads(archive.read("manifest.json").decode("utf-8"))
    assert inside["bank_version"] == result.bank_version
    assert inside["chapters"] == project.manifest()["chapters"]


def test_build_is_byte_reproducible(tmp_path: Path) -> None:
    """同输入 + 同 released_at → dist 产物字节级一致（含 gzip 与 zip）。"""
    first = Project(tmp_path / "a")
    first.dataset()
    first.run()
    first.build()

    second = Project(tmp_path / "b")
    second.dataset()
    second.run()
    second.build()

    def snapshot(root: Path) -> dict[str, bytes]:
        return {
            path.relative_to(root).as_posix(): path.read_bytes()
            for path in sorted(root.rglob("*"))
            if path.is_file()
        }

    assert snapshot(first.dist) == snapshot(second.dist)


def test_rebuild_without_changes_is_idempotent(tmp_path: Path) -> None:
    """内容未变时 bank_version 不递增、产物不变（便于 CI 反复跑）。"""
    project = Project(tmp_path)
    project.dataset()
    project.run()
    first = project.build()
    files_before = {p.name: p.read_bytes() for p in (project.dist / "full").iterdir()}

    project.run()  # 重跑 import/normalize/media
    second = project.build()
    assert second.bank_version == first.bank_version
    assert any("保持不变" in note for note in second.notes)
    assert {p.name: p.read_bytes() for p in (project.dist / "full").iterdir()} == files_before
    assert second.deltas == []


def test_always_bump_forces_new_version(tmp_path: Path) -> None:
    """--always-bump 即使内容没变也递增（并产出空增量，保证链连续）。"""
    project = Project(tmp_path)
    project.dataset()
    project.run()
    project.build()
    project.run()
    bumped = project.build(always_bump=True)
    assert bumped.bank_version == 2
    assert [d["to"] for d in bumped.deltas] == [2]
    assert (project.dist / "delta/v1-v2.jsonl.gz").exists()
    assert gunzip_bytes((project.dist / "delta/v1-v2.jsonl.gz").read_bytes()) == b""


def test_delta_chain_and_pruning(tmp_path: Path) -> None:
    """多版本 → 增量链连续；超过保留数时删掉最老的增量。"""
    project = Project(tmp_path)
    rows = list(DEFAULT_ROWS)
    project.dataset(rows)
    project.run()
    project.build()

    for index in range(2):
        rows = [dict(item) for item in rows]
        rows[0]["题干"] = f"{DEFAULT_ROWS[0]['题干']}（第 {index + 2} 版）"
        project.dataset(rows)
        project.run()
        project.build(keep_deltas=1)

    deltas = sorted(p.name for p in (project.dist / "delta").iterdir())
    assert deltas == ["v2-v3.jsonl.gz"], deltas
    manifest = project.manifest()
    assert [(d["from"], d["to"]) for d in manifest["deltas"]] == [(2, 3)]
    # 快照链在 state 里保留（供下次 diff），默认保留 5 份
    snapshots = sorted(p.name for p in (project.state / "snapshots").iterdir())
    assert len(snapshots) == 3


def test_deleted_question_produces_delete_row_and_id_is_retired(tmp_path: Path) -> None:
    """删掉一道题 → 增量含删除行，且该 id 永不复用。"""
    project = Project(tmp_path)
    rows = list(DEFAULT_ROWS)
    project.dataset(rows)
    project.run()
    project.build()
    removed_id = project.final_questions()[1]["id"]

    rows = [item for item in rows if item["编号"] != "T002"]
    project.dataset(rows)
    project.run()
    project.build()

    delta_path = project.dist / "delta/v1-v2.jsonl.gz"
    lines = [json.loads(line) for line in gunzip_bytes(delta_path.read_bytes()).decode("utf-8").splitlines() if line]
    assert {"id": removed_id, "deleted": True} in lines
    assert removed_id not in project.full_bank()

    # 再加回来：必须拿新 id（不复用）
    rows = list(DEFAULT_ROWS)
    project.dataset(rows)
    project.run()
    project.build()
    ids = {q["stem"]: q["id"] for q in project.final_questions()}
    assert ids[DEFAULT_ROWS[1]["题干"]] != removed_id


def test_old_media_is_retained(tmp_path: Path) -> None:
    """跨版本媒体只增不删（合同：旧文件保留）。"""
    project = Project(tmp_path)
    rows = list(DEFAULT_ROWS)
    project.dataset(rows, {"img_a.png": "png", "anim_a.gif": "gif"})
    project.run()
    project.build()
    before = {p.name for p in (project.dist / "media").rglob("*") if p.is_file()}

    rows = [dict(item) for item in rows if item["编号"] != "T001"]
    project.dataset(rows, {"img_a.png": "png", "anim_a.gif": "gif"})
    project.run()
    project.build()
    after = {p.name for p in (project.dist / "media").rglob("*") if p.is_file()}
    assert before <= after


def test_no_bundle_option(tmp_path: Path) -> None:
    """--no-bundle 时不产出离线包，manifest 里 bundle.bytes 为 0。"""
    project = Project(tmp_path)
    project.dataset()
    project.run()
    result = project.build(bundle=False)
    assert not list((project.dist / "bundle").glob("*.zip"))
    assert result.bundle_url == ""
    assert project.manifest()["bundle"]["bytes"] == 0


def test_build_blocks_on_validation_error(tmp_path: Path) -> None:
    """校验不过 → build 拒绝执行（合同：构建前必须全过）。"""
    project = Project(tmp_path)
    project.dataset()
    project.run()
    broken = project.final_questions()
    broken[0]["answer"] = ["Z"]
    from jiakao_pipeline.util import write_jsonl

    write_jsonl(project.work / "questions.jsonl", broken)
    with pytest.raises(BuildError, match="校验未通过"):
        project.build()
    assert not (project.dist / "manifest.json").exists()


def test_build_requires_media_step(tmp_path: Path) -> None:
    """没跑 media（没有 questions.jsonl）时要给明确提示。"""
    project = Project(tmp_path)
    project.dataset()
    with pytest.raises(BuildError, match="请先执行 import"):
        project.build()


def test_delta_only_rows_via_diff_files(tmp_path: Path) -> None:
    """单独用 diff 命令也能产出增量（与 build 内部一致）。"""
    from jiakao_pipeline.diff import delta_from_files

    project = Project(tmp_path)
    project.dataset()
    project.run()
    old = project.work / "questions.jsonl"
    new_rows = project.final_questions()
    new_rows[0]["stem"] += "（改）"
    new_rows[0]["rev"] = 2
    from jiakao_pipeline.util import write_jsonl

    new = write_jsonl(tmp_path / "new.jsonl", new_rows)
    delta = delta_from_files(old, new, chapter_order_map(project.chapters))
    assert [item["id"] for item in delta.upserts] == [new_rows[0]["id"]]
    assert delta.deletes == []


def test_row_helper_is_consistent() -> None:
    """夹具自身的小校验（避免测试数据写错导致假绿）。"""
    item = row("X", "科目一", "单选", "交通信号", "题干", ["甲", "乙"], "A")
    assert item["选项A"] == "甲" and item["选项C"] == ""
