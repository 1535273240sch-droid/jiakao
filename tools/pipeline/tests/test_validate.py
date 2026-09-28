"""校验：合同 §2 的每一条规则都要能拦住坏数据。"""

from __future__ import annotations

import copy
from pathlib import Path

import pytest

from jiakao_pipeline.chapters import load_chapters
from jiakao_pipeline.validate import (
    load_manifest_schema,
    load_question_schema,
    validate,
    validate_manifest,
    validate_questions,
)

from conftest import CHAPTERS_FILE, Project

CHAPTERS = load_chapters(CHAPTERS_FILE)


def good_question(**overrides) -> dict:
    """一道完全合规的题（按需覆盖字段造坏数据）。"""
    question = {
        "id": "s1-000001",
        "subject": 1,
        "vehicles": ["car"],
        "type": "single",
        "chapter_id": "s1-c03",
        "tags": ["标志"],
        "stem": "如图所示，该标志的含义是什么？",
        "options": [{"key": "A", "text": "甲"}, {"key": "B", "text": "乙"}],
        "answer": ["A"],
        "explain": "解析",
        "media": [],
        "rev": 1,
    }
    question.update(overrides)
    return question


def errors_of(questions: list[dict], dist: Path | None = None) -> list[str]:
    """跑题目级校验并返回错误列表。"""
    collector, _ = validate_questions(questions, CHAPTERS, dist)
    return collector.errors


def test_valid_question_passes() -> None:
    """合规题目零错误。"""
    assert errors_of([good_question()]) == []


def test_schemas_are_loadable_and_strict() -> None:
    """两个 Schema 都能加载，且对外层多余字段敏感。"""
    question_schema = load_question_schema()
    manifest_schema = load_manifest_schema()
    assert question_schema["required"][0] == "id"
    assert "bundle" in manifest_schema["required"]
    assert errors_of([good_question(extra=1)])  # additionalProperties: false


def test_duplicate_id_detected() -> None:
    """id 必须唯一。"""
    errors = errors_of([good_question(), good_question()])
    assert any("id 重复" in e for e in errors)


def test_bad_id_format_detected() -> None:
    """id 必须形如 s{1|4}-{6位}。"""
    assert any("id" in e for e in errors_of([good_question(id="s1-1")]))
    assert any("id" in e for e in errors_of([good_question(id="s2-000001")]))


def test_judge_options_and_answer_must_be_canonical() -> None:
    """判断题必须是 A 正确 / B 错误，答案只能是单个 A 或 B。"""
    wrong_options = errors_of([good_question(type="judge", options=[{"key": "A", "text": "对"}, {"key": "B", "text": "错"}])])
    assert any("判断题选项" in e for e in wrong_options)
    wrong_answer = errors_of([
        good_question(type="judge", options=[{"key": "A", "text": "正确"}, {"key": "B", "text": "错误"}], answer=["A", "B"])
    ])
    assert any("判断题答案" in e for e in wrong_answer)


def test_answer_subset_and_cardinality() -> None:
    """answer ⊆ options.key；单选/判断 1 个；多选 ≥2 个。"""
    assert any("不在 options.key" in e for e in errors_of([good_question(answer=["C"])]))
    assert any("必须 1 个" in e for e in errors_of([good_question(answer=[])]))
    assert any("必须 ≥2 个" in e for e in errors_of([good_question(type="multi", answer=["A"])]))


def test_answer_must_be_sorted_and_unique() -> None:
    """answer 必须按字母序且去重。"""
    options = [{"key": "A", "text": "甲"}, {"key": "B", "text": "乙"}, {"key": "C", "text": "丙"}]
    errors = errors_of([good_question(type="multi", options=options, answer=["C", "A"])])
    assert any("字母序" in e for e in errors)


def test_unknown_chapter_detected() -> None:
    """chapter_id 必须在章节表内。"""
    assert any("chapter_id" in e for e in errors_of([good_question(chapter_id="s1-c99")]))


def test_empty_stem_and_bad_rev_detected() -> None:
    """stem 非空、rev ≥ 1。"""
    assert any("stem" in e for e in errors_of([good_question(stem=" ")]))
    assert any("rev" in e for e in errors_of([good_question(rev=0)]))


def test_duplicate_stem_warns_not_errors() -> None:
    """同科目题干+选项重复只是告警（不阻断构建）。"""
    other = copy.deepcopy(good_question(id="s1-000002"))
    collector, _ = validate_questions([good_question(), other], CHAPTERS, None)
    assert collector.errors == []
    assert any("重复" in w for w in collector.warnings)


def test_media_reference_must_exist(tmp_path: Path) -> None:
    """media 引用的文件必须真实存在且 sha256/bytes 一致。"""
    question = good_question(media=[{"sha256": "a" * 64, "ext": "webp", "kind": "image", "w": 10, "h": 10, "bytes": 100}])
    errors = errors_of([question], tmp_path)
    assert any("媒体文件缺失" in e for e in errors)


def test_media_kind_ext_mismatch(tmp_path: Path) -> None:
    """kind=anim 的 ext 必须是 webp。"""
    question = good_question(media=[{"sha256": "b" * 64, "ext": "mp4", "kind": "anim", "w": 10, "h": 10, "bytes": 5}])
    errors = errors_of([question], tmp_path)
    assert any("ext 必须是 webp" in e for e in errors)


def test_media_bytes_mismatch_detected(tmp_path: Path) -> None:
    """实际体积与声明不一致要报错。"""
    project = Project(tmp_path / "p")
    project.dataset()
    project.run()
    questions = project.final_questions()
    target = questions[0]
    assert target["media"], "样例第一题应当带图"
    target["media"][0]["bytes"] += 1
    collector, _ = validate_questions([target], CHAPTERS, project.dist)
    assert any("bytes 不一致" in e for e in collector.errors)


def test_media_sha_mismatch_detected(tmp_path: Path) -> None:
    """文件内容的 sha256 与声明不一致要报错。"""
    project = Project(tmp_path / "p")
    project.dataset()
    project.run()
    question = project.final_questions()[0]
    question["media"][0]["sha256"] = "c" * 64
    collector, _ = validate_questions([question], CHAPTERS, project.dist)
    assert any("媒体文件缺失" in e or "sha256 不一致" in e for e in collector.errors)


def test_validate_reports_and_exit_semantics(tmp_path: Path) -> None:
    """validate() 写出 dist/report.md，并在有错时 ok=False。"""
    project = Project(tmp_path / "p")
    project.dataset()
    project.run()
    result = validate(project.work, project.dist, project.chapters, generated_at="2026-01-01T00:00:00Z")
    assert result.ok is True
    assert result.report_path is not None and result.report_path.exists()
    text = result.report_path.read_text(encoding="utf-8")
    assert "题型分布" in text and "章节分布" in text and "组卷可行性" in text

    # 人为破坏一道题 → 校验失败
    broken = project.final_questions()
    broken[0]["answer"] = ["D"]
    from jiakao_pipeline.util import write_jsonl

    write_jsonl(project.work / "questions.jsonl", broken)
    failed = validate(project.work, project.dist, project.chapters, generated_at="2026-01-01T00:00:00Z")
    assert failed.ok is False
    assert failed.errors


def test_validate_missing_questions_file(tmp_path: Path) -> None:
    """work/questions.jsonl 不存在时给出可操作的提示。"""
    result = validate(tmp_path / "nope", None, CHAPTERS)
    assert result.ok is False
    assert "请先执行 import" in result.errors[0]


def test_manifest_schema_accepts_generated_manifest(tmp_path: Path) -> None:
    """真实产出的 manifest 必须通过 manifest.schema.json。"""
    project = Project(tmp_path / "p")
    project.dataset()
    project.run()
    project.build()
    assert validate_manifest(project.manifest()) == []


def test_manifest_schema_rejects_bad_version_pattern() -> None:
    """manifest 的 url 命名不合规要报错（full/bank-v{N}.jsonl.gz）。"""
    manifest = {
        "schema": 1,
        "bank_version": 1,
        "released_at": "2026-01-01T00:00:00Z",
        "min_app_version_code": 1,
        "chapters": [{"id": "s1-c01", "subject": 1, "name": "一", "order": 1}],
        "full": {"url": "full/bank.jsonl.gz", "sha256": "a" * 64, "bytes": 1, "count": 0},
        "deltas": [],
        "media_base": "media/",
        "bundle": {"url": "bundle/bundle-v1.zip", "sha256": "a" * 64, "bytes": 1},
    }
    errors = validate_manifest(manifest)
    assert any("full" in e for e in errors)
