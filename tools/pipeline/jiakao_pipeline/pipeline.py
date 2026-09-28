"""流水线编排：import → normalize → media（CLI 与测试共用同一套实现）。

中间产物（都落在 ``work/``）
- ``questions.raw.jsonl``   适配器原始输出（含素材原始引用与 src_key）
- ``media_src/``            从原始目录拷进来的素材（流水线自包含，便于复现）
- ``questions.norm.jsonl``  规范化后（媒体仍是 ``{"src": …}`` 引用）
- ``questions.jsonl``       **最终合同格式**（媒体已解析成 sha256 引用）
- ``media_index.json``      媒体处理结果（报告与后续步骤复用）
- ``.media_cache.json``     媒体缓存（源 mtime+size+参数 → 结果）
"""

from __future__ import annotations

import shutil
from dataclasses import dataclass, field
from pathlib import Path

from .adapters.base import RawQuestion
from .chapters import ChapterDef
from .idregistry import IdRegistry
from .media import (
    MediaCache,
    MediaParams,
    MediaResult,
    collect_media_sources,
    process_many,
    resolve_question_media,
)
from .normalize import NormalizeResult, normalize_questions
from .util import (
    atomic_write_text,
    dumps_pretty,
    ensure_dir,
    iter_jsonl_text,
    now_utc_iso,
    read_jsonl,
    sha256_file,
)

RAW_FILE = "questions.raw.jsonl"
NORM_FILE = "questions.norm.jsonl"
FINAL_FILE = "questions.jsonl"
MEDIA_DIR = "media_src"
MEDIA_INDEX = "media_index.json"
MEDIA_CACHE = ".media_cache.json"


class PipelineError(RuntimeError):
    """流水线步骤失败。"""


@dataclass
class ImportResult:
    """import 步骤结果。"""

    raw_count: int = 0
    media_count: int = 0
    missing_media: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    raw_path: Path | None = None

    def summary(self) -> str:
        """一行摘要。"""
        return (
            f"导入 {self.raw_count} 题，素材 {self.media_count} 个"
            + (f"，缺失 {len(self.missing_media)} 个" if self.missing_media else "")
        )


@dataclass
class MediaStepResult:
    """media 步骤结果。"""

    unique_sources: int = 0
    processed: int = 0
    cached: int = 0
    deduped_outputs: int = 0
    total_bytes: int = 0
    degraded: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    results: dict[str, MediaResult] = field(default_factory=dict)

    def summary(self) -> str:
        """一行摘要。"""
        return (
            f"媒体 {self.unique_sources} 个源文件 → {self.deduped_outputs} 个去重产物"
            f"（新处理 {self.processed}，缓存命中 {self.cached}），"
            f"合计 {self.total_bytes / 1024:.0f} KB"
        )


# ───────────────────────── import ─────────────────────────


def _copy_media(src: Path, dest_dir: Path) -> tuple[Path, bool]:
    """把素材拷进 ``work/media_src``；同名不同内容时用短哈希消歧。返回 (目标, 是否已存在)。"""
    dest = dest_dir / src.name
    if dest.exists():
        try:
            if dest.stat().st_size == src.stat().st_size and sha256_file(dest) == sha256_file(src):
                return dest, True
        except OSError:
            pass
        digest = sha256_file(src)[:8]
        dest = dest_dir / f"{src.stem}_{digest}{src.suffix}"
        if dest.exists():
            return dest, True
    shutil.copy2(src, dest)
    return dest, False


def run_import(
    adapter: str,
    input_path: str | Path,
    out_dir: str | Path,
    images_dir: str | Path | None = None,
    field_map: dict[str, str] | None = None,
    table: str | None = None,
    allow_missing_media: bool = False,
) -> ImportResult:
    """原始题库 → ``work/questions.raw.jsonl``（并把素材拷进 ``work/media_src``）。"""
    out_dir = Path(out_dir)
    media_dir = ensure_dir(out_dir / MEDIA_DIR)
    result = ImportResult()

    adapter_cls = _make_adapter(adapter, images_dir, field_map, table)
    rows: list[dict] = []
    missing: list[str] = []
    copied: dict[str, str] = {}

    for raw in adapter_cls.iter_raw(input_path):
        if raw.media:
            new_media = []
            for item in raw.media:
                # 适配器的 locate_media 已按 --images 解析过相对路径，这里只兜底非绝对路径
                source = Path(item.src)
                if not source.exists():
                    missing.append(f"{raw.source}: {source}")
                    continue
                key = str(source.resolve())
                if key not in copied:
                    dest, _ = _copy_media(source, media_dir)
                    copied[key] = dest.name
                new_media.append(type(item)(src=f"{MEDIA_DIR}/{copied[key]}", kind_hint=item.kind_hint))
            raw.media = new_media
        rows.append(raw.to_dict())

    if missing and not allow_missing_media:
        raise PipelineError(
            "以下素材文件不存在（用 --allow-missing-media 可只告警并跳过）:\n  - "
            + "\n  - ".join(missing[:20])
        )
    result.missing_media = missing
    if missing:
        result.warnings.append(f"跳过了 {len(missing)} 个缺失素材")

    atomic_write_text(out_dir / RAW_FILE, iter_jsonl_text(rows))
    result.raw_count = len(rows)
    result.media_count = len(copied)
    result.raw_path = out_dir / RAW_FILE
    result.warnings.extend(
        w for raw in rows for w in (raw.get("warnings") or [])
    )
    return result


def _make_adapter(adapter: str, images_dir, field_map, table):
    """构造适配器实例（按名字分派到带额外参数的子类）。"""
    from .adapters import load_adapters
    from .adapters.base import get_adapter

    load_adapters()
    cls = get_adapter(adapter)
    kwargs: dict = {"images_dir": images_dir}
    if field_map:
        kwargs["field_map"] = field_map
    if table and cls.__name__ == "SqliteAdapter":
        kwargs["table"] = table
    return cls(**kwargs)


# ───────────────────────── normalize ─────────────────────────


def load_raw_questions(path: str | Path) -> list[RawQuestion]:
    """读 ``questions.raw.jsonl``。"""
    return [RawQuestion.from_dict(row) for row in read_jsonl(path)]


def run_normalize(
    in_dir: str | Path,
    chapters: list[ChapterDef],
    state_dir: str | Path,
    strict: bool = True,
    raw_path: str | Path | None = None,
) -> NormalizeResult:
    """``questions.raw.jsonl`` → ``questions.norm.jsonl``（并更新 id 注册表）。"""
    in_dir = Path(in_dir)
    raw_file = Path(raw_path) if raw_path else in_dir / RAW_FILE
    if not raw_file.exists():
        raise PipelineError(f"找不到 {raw_file}，请先执行 import")
    registry = IdRegistry.load(Path(state_dir) / "id_registry.json")
    result = normalize_questions(load_raw_questions(raw_file), chapters, registry, strict=strict)
    atomic_write_text(in_dir / NORM_FILE, iter_jsonl_text(result.questions))
    atomic_write_text(
        in_dir / "normalize_report.json",
        dumps_pretty(
            {
                "generated_at": now_utc_iso(),
                "stats": result.stats,
                "errors": result.errors,
                "warnings": result.warnings,
                "retired": result.retired,
                "new_ids": result.new_ids,
                "rev_bumped": result.rev_bumped,
            }
        ),
    )
    return result


# ───────────────────────── media ─────────────────────────


def run_media(
    in_dir: str | Path,
    dist_dir: str | Path,
    params: MediaParams | None = None,
    jobs: int = 1,
    progress=None,
    norm_path: str | Path | None = None,
) -> MediaStepResult:
    """``questions.norm.jsonl`` → 转码落盘 ``dist/media`` + ``questions.jsonl``（合同格式）。"""
    in_dir = Path(in_dir)
    dist_dir = Path(dist_dir)
    norm_file = Path(norm_path) if norm_path else in_dir / NORM_FILE
    if not norm_file.exists():
        raise PipelineError(f"找不到 {norm_file}，请先执行 normalize")

    params = params or MediaParams()
    questions = list(read_jsonl(norm_file))
    sources = collect_media_sources(questions, in_dir)

    ensure_dir(dist_dir)
    cache = MediaCache(in_dir / MEDIA_CACHE)
    results, errors = process_many(sources, dist_dir, params, cache=cache, jobs=jobs, progress=progress)
    if errors:
        raise PipelineError(
            "媒体处理失败:\n  - " + "\n  - ".join(errors[:20])
        )

    step = MediaStepResult(unique_sources=len(sources), results=results)
    step.cached = sum(1 for r in results.values() if r.cached)
    step.processed = len(results) - step.cached
    distinct: dict[str, MediaResult] = {}
    for result in results.values():
        distinct.setdefault(result.sha256, result)
    step.deduped_outputs = len(distinct)
    step.total_bytes = sum(result.bytes for result in distinct.values())
    step.degraded = [r.src for r in results.values() if r.degraded]
    step.warnings = [w for r in results.values() for w in r.warnings]

    resolved_questions: list[dict] = []
    for question in questions:
        resolved, warns = resolve_question_media(question, results, in_dir)
        step.warnings.extend(warns)
        resolved_questions.append(resolved)

    order = {q["id"]: i for i, q in enumerate(questions)}
    resolved_questions.sort(key=lambda q: order[q["id"]])
    atomic_write_text(in_dir / FINAL_FILE, iter_jsonl_text(resolved_questions))
    atomic_write_text(
        in_dir / MEDIA_INDEX,
        dumps_pretty(
            {
                "schema": 1,
                "generated_at": now_utc_iso(),
                "params": params.signature(),
                "items": {result.src: result.to_dict() for result in results.values()},
            }
        ),
    )
    return step


def load_media_index(in_dir: str | Path) -> dict[str, dict]:
    """读 ``work/media_index.json`` 的 items（不存在返回空字典）。"""
    from .util import load_json

    payload = load_json(Path(in_dir) / MEDIA_INDEX, default={}) or {}
    return dict(payload.get("items") or {})
