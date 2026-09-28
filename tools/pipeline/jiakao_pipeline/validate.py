"""校验：构建前的硬门槛（不过则非零退出），并产出 ``dist/report.md``。

校验项（TASK"校验规则"）
1. JSON Schema（``schema/question.schema.json``）逐题通过；
2. ``id`` 唯一且形如 ``s{1|4}-\\d{6}``；
3. 判断题选项固定 ``A 正确 / B 错误``；
4. ``answer ⊆ options.key`` 且非空；判断/单选 ``len(answer)==1``，多选 ``≥2``；
5. ``chapter_id`` 在章节表内；
6. ``media`` 引用的文件存在，且 sha256 / bytes / 宽高一致；
7. ``stem`` 非空、``rev ≥ 1``；
8. 同科目"题干+选项"重复 → 告警；
9. 输出人类可读报告，含题量、题型/章节分布、含图/含动图数量、最大媒体、告警列表。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from importlib import resources
from pathlib import Path

from .chapters import ChapterDef
from .media import media_rel_path, probe_dimensions
from .models import Question
from .report import BuildSection, ReportData, render_report
from .util import atomic_write_text, read_jsonl, sha256_file

#: 单类错误最多收集多少条（避免一个坏文件刷屏），超出后只计数。
DEFAULT_MAX_ERRORS = 200
JUDGE_OPTION_TEXT = {"A": "正确", "B": "错误"}


@dataclass
class ValidationResult:
    """校验结果。"""

    ok: bool = True
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    stats: dict = field(default_factory=dict)
    report_path: Path | None = None
    report: str = ""

    def summary(self) -> str:
        """一行摘要（CLI 打印用）。"""
        status = "通过" if self.ok else "失败"
        return f"校验{status}：{self.stats.get('total', 0)} 题，{len(self.errors)} 错误，{len(self.warnings)} 告警"


class _Collector:
    """错误/告警收集器（带条数上限）。"""

    def __init__(self, max_errors: int = DEFAULT_MAX_ERRORS) -> None:
        self.errors: list[str] = []
        self.warnings: list[str] = []
        self.max_errors = max_errors
        self.error_count = 0

    def error(self, message: str) -> None:
        """记录错误。"""
        self.error_count += 1
        if len(self.errors) < self.max_errors:
            self.errors.append(message)

    def warn(self, message: str) -> None:
        """记录告警。"""
        self.warnings.append(message)

    def finalize(self) -> None:
        """把被截断的错误数补成一条说明。"""
        if self.error_count > len(self.errors):
            self.errors.append(f"… 另有 {self.error_count - len(self.errors)} 个错误未列出")


def load_questions(path: str | Path) -> list[dict]:
    """读题目 jsonl（按行返回原始字典）。"""
    return list(read_jsonl(path))


def load_question_schema() -> dict:
    """读内置的题目 JSON Schema。"""
    with resources.files("jiakao_pipeline.schema").joinpath("question.schema.json").open(
        "r", encoding="utf-8"
    ) as fh:
        return json.load(fh)


def load_manifest_schema() -> dict:
    """读内置的 manifest JSON Schema。"""
    with resources.files("jiakao_pipeline.schema").joinpath("manifest.schema.json").open(
        "r", encoding="utf-8"
    ) as fh:
        return json.load(fh)


def validate_manifest(manifest: dict) -> list[str]:
    """校验 manifest.json 是否符合 ``manifest.schema.json``。"""
    import jsonschema

    validator = jsonschema.Draft202012Validator(load_manifest_schema())
    return [
        f"manifest{''.join(f'[{p!r}]' for p in err.absolute_path)}: {err.message}"
        for err in sorted(validator.iter_errors(manifest), key=lambda e: list(e.absolute_path))
    ]


def validate_questions(
    questions: list[dict],
    chapters: list[ChapterDef],
    dist_dir: Path | None = None,
    media_index: dict[str, dict] | None = None,
    max_errors: int = DEFAULT_MAX_ERRORS,
) -> tuple[_Collector, dict]:
    """题目级校验（Schema + 语义 + 媒体），返回 ``(收集器, 统计)``。"""
    import jsonschema

    out = _Collector(max_errors)
    schema = load_question_schema()
    validator = jsonschema.Draft202012Validator(schema)
    chapter_ids = {c.id for c in chapters}

    seen_ids: dict[str, int] = {}
    seen_signatures: dict[tuple, str] = {}
    media_cache: dict[str, dict] = {}
    distinct_media: dict[str, dict] = {}

    for line_no, raw in enumerate(questions, 1):
        where = f"第 {line_no} 行"
        # ── Schema ──
        schema_errors = sorted(validator.iter_errors(raw), key=lambda e: list(e.absolute_path))
        for err in schema_errors:
            path = "".join(f"[{p!r}]" for p in err.absolute_path)
            out.error(f"{where} {raw.get('id', '')}{path}: {err.message}")
        qid = raw.get("id") if isinstance(raw.get("id"), str) else None
        if not qid:
            continue
        where = f"{qid}（第 {line_no} 行）"

        if qid in seen_ids:
            out.error(f"{where}: id 重复（第 {seen_ids[qid]} 行已出现）")
        else:
            seen_ids[qid] = line_no

        try:
            question = Question.model_validate(raw)
        except Exception as exc:  # noqa: BLE001 - pydantic 细节已在 Schema 里报过
            out.error(f"{where}: 字段不合法: {exc}")
            continue

        # ── 语义 ──
        option_keys = [o.key for o in question.options]
        if len(set(option_keys)) != len(option_keys):
            out.error(f"{where}: 选项键重复 {option_keys}")
        if question.type == "judge":
            actual = {o.key: o.text for o in question.options}
            if actual != JUDGE_OPTION_TEXT:
                out.error(f"{where}: 判断题选项必须是 A 正确/B 错误，实际 {actual}")
            if question.answer not in (["A"], ["B"]):
                out.error(f"{where}: 判断题答案必须是单个 A 或 B，实际 {question.answer}")
        if question.type in ("judge", "single") and len(question.answer) != 1:
            out.error(f"{where}: {question.type} 的答案必须 1 个，实际 {len(question.answer)} 个")
        if question.type == "multi" and len(question.answer) < 2:
            out.error(f"{where}: multi 的答案必须 ≥2 个，实际 {len(question.answer)} 个")
        invalid_answers = [a for a in question.answer if a not in option_keys]
        if invalid_answers:
            out.error(f"{where}: answer {invalid_answers} 不在 options.key {option_keys} 内")
        if question.answer != sorted(set(question.answer)):
            out.error(f"{where}: answer 未按字母序去重排序: {question.answer}")
        if question.chapter_id not in chapter_ids:
            out.error(f"{where}: chapter_id {question.chapter_id} 不在章节表内")
        if not question.stem.strip():
            out.error(f"{where}: stem 为空")
        if question.rev < 1:
            out.error(f"{where}: rev 必须 ≥1，实际 {question.rev}")
        if not question.tags and not question.media:
            out.warn(f"{where}: 既无标签也无媒体（不影响构建）")

        signature = (
            question.subject,
            question.stem,
            tuple((o.key, o.text) for o in question.options),
        )
        if signature in seen_signatures:
            out.warn(f"{where}: 与 {seen_signatures[signature]} 题干+选项重复")
        else:
            seen_signatures[signature] = qid

        # ── 媒体 ──
        for ref in question.media:
            expected_kind = {"image": "webp", "anim": "webp", "video": "mp4"}[ref.kind]
            if ref.ext != expected_kind:
                out.error(f"{where}: kind={ref.kind} 的 ext 必须是 {expected_kind}，实际 {ref.ext}")
            if ref.w <= 0 or ref.h <= 0:
                out.error(
                    f"{where}: 媒体 {ref.sha256[:12]}… 宽高未知（{ref.w}×{ref.h}）"
                    "，请安装 ffmpeg/ffprobe 后重跑 media 步骤"
                )
            distinct_media.setdefault(ref.sha256, {"ref": ref.model_dump(), "question": qid})
            if dist_dir is None:
                continue
            if ref.sha256 in media_cache:
                cached = media_cache[ref.sha256]
                if cached.get("error"):
                    out.error(f"{where}: {cached['error']}")
                continue
            check = _check_media_file(Path(dist_dir), ref.model_dump())
            media_cache[ref.sha256] = check
            if check.get("error"):
                out.error(f"{where}: {check['error']}")
            elif check.get("warning"):
                out.warn(f"{where}: {check['warning']}")

    out.finalize()
    stats = {
        "total": len(questions),
        "distinct_ids": len(seen_ids),
        "media_distinct": len(distinct_media),
        "media_checked": {k: v for k, v in media_cache.items() if not v.get("error")},
        "media_errors": {k: v["error"] for k, v in media_cache.items() if v.get("error")},
    }
    return out, stats


def _check_media_file(dist_dir: Path, ref: dict) -> dict:
    """校验单个媒体文件：存在 / sha256 / bytes / 宽高。"""
    path = dist_dir / media_rel_path(ref["sha256"], ref["ext"])
    if not path.exists():
        return {"error": f"媒体文件缺失: {path.relative_to(dist_dir).as_posix()}"}
    actual_bytes = path.stat().st_size
    if actual_bytes != ref["bytes"]:
        return {"error": f"媒体 {ref['sha256'][:12]}… bytes 不一致：manifest {ref['bytes']}，实际 {actual_bytes}"}
    actual_sha = sha256_file(path)
    if actual_sha != ref["sha256"]:
        return {"error": f"媒体 {path.name} sha256 不一致：引用 {ref['sha256'][:12]}…，实际 {actual_sha[:12]}…"}
    size = probe_dimensions(path)
    if size is None:
        return {"warning": f"媒体 {path.name} 宽高无法核对（工具缺失）"}
    if list(size) != [ref["w"], ref["h"]]:
        return {"error": f"媒体 {path.name} 宽高不一致：引用 {ref['w']}×{ref['h']}，实际 {size[0]}×{size[1]}"}
    return {}


def scan_media_dir(dist_dir: Path, referenced: set[str]) -> tuple[int, int, list[str]]:
    """扫描 ``dist/media``：返回 (文件数, 总字节, 未被引用的存量文件相对路径)。"""
    media_root = Path(dist_dir) / "media"
    if not media_root.exists():
        return 0, 0, []
    count = 0
    total = 0
    orphans: list[str] = []
    for path in sorted(media_root.rglob("*")):
        if not path.is_file():
            continue
        count += 1
        total += path.stat().st_size
        if path.stem not in referenced:
            orphans.append(path.relative_to(dist_dir).as_posix())
    return count, total, orphans


def validate(
    in_dir: str | Path,
    dist_dir: str | Path | None,
    chapters: list[ChapterDef],
    report_path: str | Path | None = None,
    questions_path: str | Path | None = None,
    media_index: dict[str, dict] | None = None,
    build: BuildSection | None = None,
    max_errors: int = DEFAULT_MAX_ERRORS,
    generated_at: str = "",
    write_report: bool = True,
) -> ValidationResult:
    """完整校验并（可选）写报告。"""
    in_dir = Path(in_dir)
    dist = Path(dist_dir) if dist_dir else None
    qpath = Path(questions_path) if questions_path else in_dir / "questions.jsonl"
    if not qpath.exists():
        return ValidationResult(
            ok=False,
            errors=[f"找不到 {qpath}，请先执行 import → normalize → media"],
            stats={"total": 0},
        )

    questions = load_questions(qpath)
    collector, stats = validate_questions(questions, chapters, dist, media_index, max_errors)

    referenced = {
        ref["sha256"] for q in questions for ref in (q.get("media") or []) if isinstance(ref, dict) and "sha256" in ref
    }
    files_on_disk = media_bytes = 0
    orphans: list[str] = []
    if dist is not None:
        files_on_disk, media_bytes, orphans = scan_media_dir(dist, referenced)

    data = ReportData(
        questions=questions,
        chapters=chapters,
        errors=collector.errors,
        warnings=collector.warnings,
        media_index=media_index or {},
        build=build,
        generated_at=generated_at,
        media_files_on_disk=files_on_disk,
        media_bytes_on_disk=media_bytes,
        orphan_media=orphans,
    )
    report = render_report(data)

    result = ValidationResult(
        ok=not collector.errors,
        errors=collector.errors,
        warnings=collector.warnings,
        stats={**stats, "media_files": files_on_disk, "media_bytes": media_bytes, "orphans": len(orphans)},
        report=report,
    )
    if write_report:
        target = Path(report_path) if report_path else (
            (dist / "report.md") if dist is not None else in_dir / "report.md"
        )
        atomic_write_text(target, report)
        result.report_path = target
    return result
