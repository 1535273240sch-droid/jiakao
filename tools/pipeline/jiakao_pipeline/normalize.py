"""规范化：清洗文本、统一选项键、规范答案、推断题型、映射章节、分配稳定 id。

输入 :class:`~jiakao_pipeline.adapters.base.RawQuestion`，输出**合同 §2 字段顺序**的题目字典
（此时 ``media`` 还是 ``{"src": ...}`` 的源引用，等 ``media`` 步骤解析成 sha256 引用）。

设计原则：能修的都修 + 记告警（让构建不因为脏数据静默失败），修不了的才报错并在最后汇总抛出。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Iterable, Sequence

from .adapters.base import RawQuestion
from .chapters import ChapterDef, map_chapter
from .idregistry import IdRegistry, content_hash
from .models import VEHICLE_ORDER, vehicle_sort_key
from .util import clean_text, dedupe_keep_order, normalize_key_token

# ───────────────────────── 常量表 ─────────────────────────

JUDGE_TRUE_WORDS = {"正确", "对", "是", "√", "✓", "true", "t", "yes", "y", "right", "1"}
JUDGE_FALSE_WORDS = {"错误", "错", "否", "×", "✗", "x", "false", "f", "no", "n", "wrong", "0"}
JUDGE_WORDS = JUDGE_TRUE_WORDS | JUDGE_FALSE_WORDS
JUDGE_OPTIONS = [{"key": "A", "text": "正确"}, {"key": "B", "text": "错误"}]

TYPE_ALIASES = {
    "judge": {"judge", "判断", "判断题", "对错", "对错题", "是非题", "tf"},
    "single": {"single", "单选", "单选题", "单项选择题", "单项选择", "选择", "radio"},
    "multi": {"multi", "多选", "多选题", "多项选择题", "多项选择", "不定项", "checkbox"},
}

MAX_OPTIONS = 8
_ANSWER_SPLIT_RE = re.compile(r"[,，、;；/|\s]+")
_LETTERS_RUN_RE = re.compile(r"^[A-Ha-h]+$")
_DIGITS_RUN_RE = re.compile(r"^[1-8]+$")


class NormalizeError(ValueError):
    """规范化失败（携带全部错误明细）。"""

    def __init__(self, errors: Sequence[str]) -> None:
        self.errors = list(errors)
        head = "\n  - ".join(self.errors[:20])
        more = f"\n  … 另有 {len(self.errors) - 20} 条" if len(self.errors) > 20 else ""
        super().__init__(f"规范化失败，共 {len(self.errors)} 个错误:\n  - {head}{more}")


@dataclass
class NormalizeResult:
    """规范化产物。"""

    questions: list[dict] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    retired: list[str] = field(default_factory=list)
    new_ids: list[str] = field(default_factory=list)
    rev_bumped: list[str] = field(default_factory=list)
    stats: dict = field(default_factory=dict)

    @property
    def ok(self) -> bool:
        """是否没有错误。"""
        return not self.errors


# ───────────────────────── 单题处理 ─────────────────────────


def parse_type_hint(value: str | None) -> str | None:
    """题型文本 → judge|single|multi；识别不了返回 None。"""
    text = clean_text(value).lower().replace("题", "")
    if not text:
        return None
    for canonical, aliases in TYPE_ALIASES.items():
        if text in {a.lower() for a in aliases}:
            return canonical
    if "判断" in text or text in ("tf",):
        return "judge"
    if "多" in text:
        return "multi"
    if "单" in text:
        return "single"
    return None


def _judge_like_texts(texts: Iterable[str]) -> bool:
    """两个选项是否构成"正确/错误"语义对。"""
    normalized = {clean_text(t).lower() for t in texts if clean_text(t)}
    if len(normalized) != 2:
        return False
    if not normalized <= {w.lower() for w in JUDGE_WORDS}:
        return False
    has_true = bool(normalized & {w.lower() for w in JUDGE_TRUE_WORDS})
    has_false = bool(normalized & {w.lower() for w in JUDGE_FALSE_WORDS})
    return has_true and has_false


def _next_free_key(used: set[str], start: int) -> str:
    """从 start 位置开始找第一个未占用的字母键。"""
    for idx in range(start, MAX_OPTIONS):
        letter = chr(ord("A") + idx)
        if letter not in used:
            return letter
    for idx in range(MAX_OPTIONS):
        letter = chr(ord("A") + idx)
        if letter not in used:
            return letter
    raise NormalizeError(["选项数量超过 8 个，无法分配键"])


def build_options(raw_options: Sequence[tuple[str, str]]) -> tuple[list[dict], list[str]]:
    """统一选项键（A-D/1-4/去括号），去空项，按 key 排序。"""
    warns: list[str] = []
    cleaned: list[tuple[str, str]] = []
    missing_key = False
    for raw_key, raw_text in raw_options:
        text = clean_text(raw_text)
        if not text:
            continue
        key = normalize_key_token(raw_key)
        if not key:
            missing_key = True
        cleaned.append((key, text))
    if not cleaned:
        return [], warns
    used: set[str] = set()
    options: list[dict] = []
    for idx, (key, text) in enumerate(cleaned):
        if not key or key in used:
            key = _next_free_key(used, idx)
        used.add(key)
        options.append({"key": key, "text": text})
    options.sort(key=lambda o: o["key"])
    if missing_key:
        warns.append("部分选项缺少字母键，已按顺序补 A/B/C…")
    return options, warns


def _split_answer_text(text: str) -> list[str]:
    """把答案文本切成 token（"A,C" / "AC" / "12" / "对" 都能切）。"""
    out: list[str] = []
    for part in _ANSWER_SPLIT_RE.split(clean_text(text)):
        if not part:
            continue
        if len(part) > 1 and _LETTERS_RUN_RE.match(part):
            out.extend(part)
        elif len(part) > 1 and _DIGITS_RUN_RE.match(part):
            out.extend(part)
        else:
            out.append(part)
    return out


def normalize_answer(
    raw_answers: Sequence[str],
    option_keys: Sequence[str],
    expect_judge: bool,
) -> tuple[list[str], list[str]]:
    """答案 → 去重排序的字母列表。返回 ``(answer, warnings)``。"""
    warns: list[str] = []
    tokens: list[str] = []
    for value in raw_answers:
        tokens.extend(_split_answer_text(str(value)))
    if not tokens and raw_answers:
        tokens = [clean_text(v) for v in raw_answers if clean_text(v)]

    keys = set(option_keys) if option_keys else {"A", "B"}
    letters: list[str] = []
    unrecognized: list[str] = []
    for token in tokens:
        lowered = token.lower()
        if expect_judge and lowered in {w.lower() for w in JUDGE_TRUE_WORDS}:
            letters.append("A")
            continue
        if expect_judge and lowered in {w.lower() for w in JUDGE_FALSE_WORDS}:
            letters.append("B")
            continue
        letter = normalize_key_token(token)
        if letter and letter in keys:
            letters.append(letter)
        else:
            unrecognized.append(token)

    if unrecognized:
        warns.append(f"答案中的 {unrecognized} 无法对应到选项，已忽略")
    deduped = sorted(dedupe_keep_order(letters))
    if not deduped and raw_answers:
        raise NormalizeError([f"答案无法解析: {raw_answers!r}（选项键 {sorted(keys)}）"])
    return deduped, warns


def infer_subject(raw: RawQuestion) -> tuple[int, str | None]:
    """确定科目：显式字段 → 章节文本 → 默认科目一。"""
    if raw.subject in (1, 4):
        return raw.subject, None
    chapter_text = clean_text(raw.chapter)
    if chapter_text:
        lowered = chapter_text.lower()
        if "四" in chapter_text or "s4" in lowered or "subject4" in lowered:
            return 4, f"科目缺失，按章节 {chapter_text!r} 推断为科目四"
        if "一" in chapter_text or "s1" in lowered or "subject1" in lowered:
            return 1, f"科目缺失，按章节 {chapter_text!r} 推断为科目一"
    return 1, "科目字段缺失且无法推断，默认按科目一处理"


def normalize_one(
    raw: RawQuestion,
    chapters: list[ChapterDef],
    registry: IdRegistry,
) -> tuple[dict, list[str], list[str]]:
    """把一条原始题转成合同字段顺序的字典。

    返回 ``(question, warnings, errors)``。errors 非空时调用方应丢弃该题。
    """
    warns: list[str] = list(raw.warnings)
    errors: list[str] = []
    where = raw.source or raw.src_key

    stem = clean_text(raw.stem)
    if not stem:
        return {}, warns, [f"{where}: 题干为空"]

    subject, subject_warn = infer_subject(raw)
    if subject_warn:
        warns.append(f"{where}: {subject_warn}")

    options, option_warns = build_options(raw.options)
    warns.extend(f"{where}: {w}" for w in option_warns)

    hint = parse_type_hint(raw.qtype)
    judge_by_text = _judge_like_texts([o["text"] for o in options])
    if not options:
        answer_tokens = [t for value in raw.answer for t in _split_answer_text(str(value))]
        looks_judge = any(t.lower() in {w.lower() for w in JUDGE_WORDS} for t in answer_tokens) or (
            bool(answer_tokens) and all(normalize_key_token(t) in ("A", "B") for t in answer_tokens)
        )
        if looks_judge or hint == "judge":
            judge_by_text = True
            warns.append(f"{where}: 未提供选项，按判断题补 A 正确/B 错误")
        else:
            errors.append(f"{where}: 既没有选项也没有可识别的判断题答案")
            return {}, warns, errors

    # 答案：判断题允许"对/错"这类词
    expect_judge = (hint == "judge") or judge_by_text
    try:
        answer, answer_warns = normalize_answer(raw.answer, [o["key"] for o in options], expect_judge)
    except NormalizeError as exc:
        return {}, warns, [f"{where}: {e}" for e in exc.errors]
    warns.extend(f"{where}: {w}" for w in answer_warns)
    if not answer:
        errors.append(f"{where}: 答案为空")
        return {}, warns, errors

    # 题型：显式标注优先，与数据冲突时按数据修正并告警
    qtype = hint or ("judge" if judge_by_text else ("multi" if len(answer) >= 2 else "single"))
    if hint == "judge" and not judge_by_text and len(options) != 2:
        warns.append(f"{where}: 标注为判断题但有 {len(options)} 个选项，按判断题处理（选项固定为 A 正确/B 错误）")
    if hint == "judge":
        qtype = "judge"
    elif hint == "single" and len(answer) >= 2:
        qtype = "multi"
        warns.append(f"{where}: 标注为单选但有 {len(answer)} 个答案，已修正为多选")
    elif hint == "multi" and len(answer) < 2:
        qtype = "single"
        warns.append(f"{where}: 标注为多选但只有 {len(answer)} 个答案，已修正为单选")

    if qtype == "judge":
        options = [dict(o) for o in JUDGE_OPTIONS]
        if answer not in (["A"], ["B"]):
            errors.append(f"{where}: 判断题答案只能是 A/B，收到 {answer}")
            return {}, warns, errors

    if qtype != "judge" and len(options) < 2:
        errors.append(f"{where}: 选择题至少需要 2 个选项，只有 {len(options)} 个")
        return {}, warns, errors

    chapter_id, chapter_warn = map_chapter(chapters, subject, raw.chapter)
    if chapter_warn:
        warns.append(f"{where}: {chapter_warn}")

    vehicles = [v for v in dedupe_keep_order(v.lower() for v in raw.vehicles) if v in VEHICLE_ORDER]
    vehicles.sort(key=vehicle_sort_key)  # 合同枚举顺序（car→truck→bus→moto），与输入顺序无关
    if not vehicles:
        vehicles = ["car"]

    tags = dedupe_keep_order(t for t in (clean_text(t) for t in raw.tags) if t)

    media = [
        {"src": m.src, "kind_hint": m.kind_hint}
        for m in raw.media
        if clean_text(m.src)
    ]

    digest = content_hash(subject, stem, options)
    assignment = registry.assign(subject, raw.src_key or None, digest)
    question = {
        "id": assignment.id,
        "subject": subject,
        "vehicles": vehicles,
        "type": qtype,
        "chapter_id": chapter_id,
        "tags": tags,
        "stem": stem,
        "options": options,
        "answer": answer,
        "explain": clean_text(raw.explain),
        "media": media,
        "rev": assignment.rev,
        "_meta": {
            "src_key": raw.src_key,
            "source": where,
            "is_new": assignment.is_new,
            "matched_by": assignment.matched_by,
        },
    }
    return question, warns, errors


# ───────────────────────── 批量处理 ─────────────────────────


def normalize_questions(
    raw_questions: Iterable[RawQuestion],
    chapters: list[ChapterDef],
    registry: IdRegistry,
    strict: bool = True,
) -> NormalizeResult:
    """批量规范化，处理完再统一退役本轮消失的 id。"""
    result = NormalizeResult()
    subjects: set[int] = set()
    seen_signatures: dict[tuple, str] = {}
    registry.begin_batch()
    for raw in raw_questions:
        question, warns, errors = normalize_one(raw, chapters, registry)
        result.warnings.extend(warns)
        if errors:
            result.errors.extend(errors)
            continue
        meta = question.pop("_meta")
        if meta["is_new"]:
            result.new_ids.append(question["id"])
        elif question["rev"] > 1:
            result.rev_bumped.append(question["id"])
        signature = (
            question["subject"],
            question["stem"],
            tuple((o["key"], o["text"]) for o in question["options"]),
        )
        if signature in seen_signatures:
            result.warnings.append(
                f"{meta['source']}: 与 {seen_signatures[signature]} 题干+选项重复（id 不同，合同允许但建议去重）"
            )
        else:
            seen_signatures[signature] = question["id"]
        subjects.add(question["subject"])
        result.questions.append(question)

    if result.errors and strict:
        registry.save()
        raise NormalizeError(result.errors)

    result.retired = registry.finalize([q["id"] for q in result.questions], subjects)
    registry.save()

    by_type: dict[str, int] = {}
    by_subject: dict[int, int] = {}
    with_media = 0
    for question in result.questions:
        by_type[question["type"]] = by_type.get(question["type"], 0) + 1
        by_subject[question["subject"]] = by_subject.get(question["subject"], 0) + 1
        if question["media"]:
            with_media += 1
    result.stats = {
        "total": len(result.questions),
        "by_subject": by_subject,
        "by_type": by_type,
        "with_media": with_media,
        "new_ids": len(result.new_ids),
        "rev_bumped": len(result.rev_bumped),
        "retired": len(result.retired),
        "registry": registry.stats(),
    }
    return result
