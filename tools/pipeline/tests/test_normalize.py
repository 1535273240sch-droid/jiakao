"""规范化：选项键、答案、题型推断、章节映射、脏数据处理。"""

from __future__ import annotations

import pytest

from jiakao_pipeline.adapters.base import RawQuestion
from jiakao_pipeline.chapters import load_chapters
from jiakao_pipeline.idregistry import IdRegistry
from jiakao_pipeline.normalize import (
    NormalizeError,
    build_options,
    infer_subject,
    normalize_answer,
    normalize_one,
    normalize_questions,
    parse_type_hint,
)

from conftest import CHAPTERS_FILE

CHAPTERS = load_chapters(CHAPTERS_FILE)


@pytest.fixture
def registry(tmp_path):
    """空注册表。"""
    return IdRegistry.load(tmp_path / "state" / "id_registry.json")


def raw(**kwargs) -> RawQuestion:
    """构造 RawQuestion（默认一道合规的单选，按需覆盖）。"""
    base = dict(
        src_key="k1", stem="题干内容", subject=1, chapter="交通信号",
        options=[("A", "甲"), ("B", "乙")], answer=["A"],
    )
    base.update(kwargs)
    return RawQuestion(**base)


def test_parse_type_hint_variants() -> None:
    """中英文题型标注都能识别。"""
    assert parse_type_hint("判断") == "judge"
    assert parse_type_hint("判断题") == "judge"
    assert parse_type_hint("单选") == "single"
    assert parse_type_hint("多项选择") == "multi"
    assert parse_type_hint("multi") == "multi"
    assert parse_type_hint("奇怪") is None
    assert parse_type_hint(None) is None


def test_build_options_unifies_keys() -> None:
    """(A)/A./全角/数字/缺失 键都被归一，并去空项、按 key 排序。"""
    options, warnings = build_options([("（A）", "甲"), ("b.", "乙"), ("", "丙")])
    assert options == [{"key": "A", "text": "甲"}, {"key": "B", "text": "乙"}, {"key": "C", "text": "丙"}]
    assert any("缺少字母键" in w for w in warnings)
    assert build_options([("A", " "), ("B", "")]) == ([], [])


def test_build_options_dedupes_repeated_keys() -> None:
    """重复键要重新分配，避免出现两个 A。"""
    options, _ = build_options([("A", "甲"), ("A", "乙")])
    assert [o["key"] for o in options] == ["A", "B"]


def test_normalize_answer_variants() -> None:
    """答案支持 A,C / AC / 1,3 / 正确 / 错误。"""
    assert normalize_answer(["A、C"], ["A", "B", "C", "D"], False)[0] == ["A", "C"]
    assert normalize_answer(["AC"], ["A", "B", "C", "D"], False)[0] == ["A", "C"]
    assert normalize_answer(["1,3"], ["A", "B", "C", "D"], False)[0] == ["A", "C"]
    assert normalize_answer(["对"], ["A", "B"], True)[0] == ["A"]
    assert normalize_answer(["错误"], ["A", "B"], True)[0] == ["B"]
    assert normalize_answer(["C", "A", "A"], ["A", "B", "C"], False)[0] == ["A", "C"]


def test_normalize_answer_reports_unparsable() -> None:
    """答案里混入无法识别的 token 要告警，但可用部分仍然生效。"""
    answer, warnings = normalize_answer(["A", "反正"], ["A", "B"], False)
    assert answer == ["A"]
    assert warnings


def test_normalize_answer_all_invalid_raises() -> None:
    """完全解析不出来要抛错（不能产出空答案）。"""
    with pytest.raises(NormalizeError):
        normalize_answer(["ZZZ"], ["A", "B"], False)


def test_judge_question_gets_fixed_options(registry) -> None:
    """判断题选项固定 A 正确 / B 错误，即使原始选项是"对/错"。"""
    question, _, errors = normalize_one(
        raw(qtype="判断", options=[("A", "对"), ("B", "错")], answer=["对"]), CHAPTERS, registry
    )
    assert errors == []
    assert question["type"] == "judge"
    assert question["options"] == [{"key": "A", "text": "正确"}, {"key": "B", "text": "错误"}]
    assert question["answer"] == ["A"]


def test_judge_question_without_options_gets_default(registry) -> None:
    """判断题没写选项也能补全（中文题库常见）。"""
    question, warnings, errors = normalize_one(
        raw(qtype="判断", options=[], answer=["错误"]), CHAPTERS, registry
    )
    assert errors == []
    assert question["options"] == [{"key": "A", "text": "正确"}, {"key": "B", "text": "错误"}]
    assert question["answer"] == ["B"]
    assert any("判断题" in w for w in warnings)


def test_type_inference_from_answer_length(registry) -> None:
    """没有题型标注时：多答案 → 多选，单答案 → 单选。"""
    single, _, _ = normalize_one(
        raw(options=[("A", "甲"), ("B", "乙")], answer=["B"]), CHAPTERS, registry
    )
    multi, _, _ = normalize_one(
        raw(options=[("A", "甲"), ("B", "乙"), ("C", "丙")], answer=["A", "C"]), CHAPTERS, registry
    )
    assert single["type"] == "single"
    assert multi["type"] == "multi"


def test_type_hint_conflict_is_repaired_with_warning(registry) -> None:
    """标注与数据冲突时按数据修正并告警（不让脏标注把构建卡死）。"""
    question, warnings, errors = normalize_one(
        raw(qtype="单选", options=[("A", "甲"), ("B", "乙"), ("C", "丙")], answer=["A", "C"]),
        CHAPTERS, registry,
    )
    assert errors == []
    assert question["type"] == "multi"
    assert any("修正为多选" in w for w in warnings)


def test_missing_stem_is_error(registry) -> None:
    """题干为空必须报错。"""
    _, _, errors = normalize_one(raw(stem="   "), CHAPTERS, registry)
    assert errors and "题干为空" in errors[0]


def test_empty_answer_is_error(registry) -> None:
    """没有答案必须报错。"""
    _, _, errors = normalize_one(raw(options=[("A", "甲"), ("B", "乙")], answer=[]), CHAPTERS, registry)
    assert errors and "答案为空" in errors[0]


def test_single_option_is_error(registry) -> None:
    """选择题只有一个选项必须报错。"""
    _, _, errors = normalize_one(raw(options=[("A", "甲")], answer=["A"]), CHAPTERS, registry)
    assert errors and "至少需要 2 个选项" in errors[0]


def test_subject_inference(registry) -> None:
    """科目缺失时按章节文本推断，实在推不出按科目一并告警。"""
    assert infer_subject(raw(subject=None, chapter="科目四 安全驾驶"))[0] == 4
    assert infer_subject(raw(subject=None, chapter="s4-c02"))[0] == 4
    subject, warning = infer_subject(raw(subject=None, chapter="无从判断"))
    assert subject == 1 and warning


def test_vehicles_and_tags_normalized(registry) -> None:
    """车型白名单过滤、标签去重、默认 car。"""
    question, _, _ = normalize_one(
        raw(vehicles=["truck", "moon", "Truck"], tags=["法规", "法规", " "],
            options=[("A", "甲"), ("B", "乙")], answer=["A"]),
        CHAPTERS, registry,
    )
    assert question["vehicles"] == ["truck"]
    assert question["tags"] == ["法规"]
    default, _, _ = normalize_one(raw(options=[("A", "甲"), ("B", "乙")], answer=["A"]), CHAPTERS, registry)
    assert default["vehicles"] == ["car"]


def test_chapter_mapping_and_fallback_warning(registry) -> None:
    """章节 id/名称/关键词/兜底四条路径。"""
    question, _, _ = normalize_one(raw(chapter="s1-c05"), CHAPTERS, registry)
    assert question["chapter_id"] == "s1-c05"
    _, warnings, _ = normalize_one(raw(chapter="完全不认识"), CHAPTERS, registry)
    assert any("兜底" in w for w in warnings)


def test_batch_normalize_retires_missing_ids(tmp_path) -> None:
    """批量规范化：消失的题退役，重复题干+选项给告警，id 稳定。"""
    registry = IdRegistry.load(tmp_path / "state" / "id_registry.json")
    questions = [
        raw(src_key="a", stem="第一题", options=[("A", "甲"), ("B", "乙")], answer=["A"]),
        raw(src_key="b", stem="第二题", options=[("A", "甲"), ("B", "乙")], answer=["B"]),
        raw(src_key="c", stem="第一题", options=[("A", "甲"), ("B", "乙")], answer=["A"]),
    ]
    result = normalize_questions(questions, CHAPTERS, registry)
    assert result.stats["total"] == 3
    assert any("重复" in w for w in result.warnings)

    # 去掉第二题 → 它的 id 退役，且不再复用
    result2 = normalize_questions(questions[:1] + questions[2:], CHAPTERS, registry)
    assert len(result2.retired) == 1
    assert result2.retired[0] == result.questions[1]["id"]
    assert {q["id"] for q in result2.questions} == {result.questions[0]["id"], result.questions[2]["id"]}


def test_strict_mode_raises_with_all_errors(tmp_path) -> None:
    """strict 模式下把所有错误一次报出来（而不是只报第一条）。"""
    registry = IdRegistry.load(tmp_path / "state" / "id_registry.json")
    bad = [
        raw(src_key="x", stem=""),
        raw(src_key="y", stem="有题干但没答案", answer=[]),
    ]
    with pytest.raises(NormalizeError) as excinfo:
        normalize_questions(bad, CHAPTERS, registry, strict=True)
    assert len(excinfo.value.errors) == 2

    lenient = normalize_questions(bad, CHAPTERS, registry, strict=False)
    assert len(lenient.errors) == 2
    assert lenient.questions == []
