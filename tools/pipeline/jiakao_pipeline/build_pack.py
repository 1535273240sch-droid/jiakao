"""打包：把规范化题目 + 媒体产出成合同 §3 的题库包（``dist/``）。

产物
- ``manifest.json``                     清单（字段/命名严格按合同 §3）
- ``full/bank-v{N}.jsonl.gz``           全量快照，按 (subject, chapter.order, id) 稳定排序
- ``delta/v{A}-v{B}.jsonl.gz``          对上一版快照的增量（upsert + 删除行），保留最近 K 个
- ``media/{sha[:2]}/{sha}.{ext}``       内容寻址，跨版本复用，永不覆盖
- ``bundle/bundle-v{N}.zip``            离线整包：manifest.json + bank.jsonl + media/**

可复现性：所有 gzip 固定 ``mtime=0``，zip 固定时间戳/权限位，JSON 键顺序固定 ——
同输入、同 ``released_at`` 时**字节级一致**。

版本号：内容（题目 + 章节表）与上一版相同则**不递增**（幂等构建，便于 CI 与本地反复跑）；
内容有变才 ``bank_version + 1`` 并写增量。可用 ``--always-bump`` 强制递增。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

from .chapters import ChapterDef, chapter_order_map
from .diff import compute_delta
from .media import media_rel_path
from .models import FullRef, Manifest
from .report import BuildSection, ReportData, render_report
from .util import (
    atomic_write_bytes,
    atomic_write_text,
    ensure_dir,
    gzip_bytes,
    load_json,
    dumps_pretty,
    now_utc_iso,
    read_jsonl,
    resolve_released_at,
    sha256_bytes,
    zip_bytes,
    iter_jsonl_text,
)
from .validate import ValidationResult, validate

DELTA_NAME_RE = re.compile(r"^v(\d+)-v(\d+)\.jsonl\.gz$")
SNAPSHOT_NAME_RE = re.compile(r"^v(\d+)\.jsonl$")
DEFAULT_KEEP_DELTAS = 5
DEFAULT_KEEP_SNAPSHOTS = 5


class BuildError(RuntimeError):
    """构建失败。"""


@dataclass
class BuildResult:
    """构建结果。"""

    bank_version: int = 0
    released_at: str = ""
    changed: bool = True
    full_url: str = ""
    full_bytes: int = 0
    full_sha256: str = ""
    full_count: int = 0
    deltas: list[dict] = field(default_factory=list)
    bundle_url: str = ""
    bundle_bytes: int = 0
    bundle_sha256: str = ""
    manifest_path: Path | None = None
    report_path: Path | None = None
    snapshot_path: Path | None = None
    validation: ValidationResult | None = None
    notes: list[str] = field(default_factory=list)

    def summary(self) -> str:
        """一行摘要。"""
        delta = "、".join(f"v{d['from']}→v{d['to']}" for d in self.deltas) or "无"
        return (
            f"bank_version={self.bank_version} 题数={self.full_count} "
            f"全量={self.full_bytes}B 增量={delta} 离线包={self.bundle_bytes}B"
        )


def canonical_questions(questions: list[dict], chapter_order: dict[str, int]) -> bytes:
    """按合同排序并序列化成 jsonl 字节（全量/快照/离线包共用的唯一序列化路径）。"""
    from .models import sort_questions

    ordered = sort_questions(questions, chapter_order)
    return iter_jsonl_text(ordered).encode("utf-8")


def _chapters_digest(chapters: list[ChapterDef]) -> str:
    """章节表的规范化摘要（用于版本变更判定）。"""
    payload = "".join(
        f"{c.id}\x1f{c.subject}\x1f{c.name}\x1f{c.order}\x1e"
        for c in sorted(chapters, key=lambda c: (c.subject, c.order, c.id))
    )
    return sha256_bytes(payload.encode("utf-8"))


def load_state_version(state_dir: Path) -> dict:
    """读 ``state/version.json``（不存在返回空字典）。"""
    return load_json(Path(state_dir) / "version.json", default={}) or {}


def _prune_dir(directory: Path, pattern: re.Pattern, parse, keep: int) -> list[str]:
    """按版本号保留最近 ``keep`` 个文件，返回被删除的相对路径列表。"""
    if not directory.exists():
        return []
    entries: list[tuple[int, int, Path]] = []
    for path in directory.iterdir():
        if not path.is_file():
            continue
        match = pattern.match(path.name)
        if not match:
            continue
        entries.append((int(match.group(1)), int(match.group(2)) if match.lastindex and match.lastindex > 1 else 0, path))
    entries.sort(key=lambda t: (t[0], t[1]))
    removed: list[str] = []
    for _, _, path in entries[:-keep] if keep > 0 else entries:
        removed.append(path.name)
        path.unlink()
    return removed


def _prune_deltas(delta_dir: Path, current_version: int, keep: int) -> list[str]:
    """保留"能连到当前版本"的最近 ``keep`` 个增量，其余删除。"""
    if not delta_dir.exists():
        return []
    parsed: list[tuple[int, int, Path]] = []
    for path in delta_dir.iterdir():
        if not path.is_file():
            continue
        match = DELTA_NAME_RE.match(path.name)
        if match:
            parsed.append((int(match.group(1)), int(match.group(2)), path))
    parsed.sort(key=lambda t: (t[1], t[0]), reverse=True)

    keep_paths: set[Path] = set()
    expected_to = current_version
    for from_v, to_v, path in parsed:
        if len(keep_paths) >= keep:
            break
        if to_v == expected_to and from_v < to_v:
            keep_paths.add(path)
            expected_to = from_v
    removed: list[str] = []
    for _, _, path in parsed:
        if path not in keep_paths:
            removed.append(path.name)
            path.unlink()
    return removed


def collect_media_files(dist_dir: Path, questions: list[dict]) -> tuple[list[tuple[str, Path]], list[str]]:
    """收集被引用的媒体文件（zip 条目名 → 磁盘路径）与缺失清单。"""
    entries: list[tuple[str, Path]] = []
    missing: list[str] = []
    seen: set[str] = set()
    for question in questions:
        for ref in question.get("media") or []:
            sha = ref["sha256"]
            if sha in seen:
                continue
            seen.add(sha)
            rel = media_rel_path(sha, ref["ext"])
            path = Path(dist_dir) / rel
            if path.exists():
                entries.append((rel, path))
            else:
                missing.append(f"{question['id']} → {rel}")
    entries.sort(key=lambda item: item[0])
    return entries, missing


#: 离线包内 manifest.json 里 bundle 字段的占位值（见 _build_bundle 的说明）。
BUNDLE_PLACEHOLDER_SHA = "0" * 64
BUNDLE_PLACEHOLDER_BYTES = 1_000_000_000


def _build_bundle(
    bank_bytes: bytes,
    media_entries: list[tuple[str, Path]],
    manifest_factory,
    bundle_rel: str,
) -> tuple[bytes, dict]:
    """生成离线整包，返回 ``(zip 字节, 写进 zip 的 manifest)``。

    自引用问题（合同 §3 的已知缺陷，见 ``out/CONTRACT_ISSUES.md`` #1）：
    manifest.json 要写进 zip 内，而它又要声明这个 zip 自己的 sha256/bytes ——
    "文件包含自身哈希"在数学上不存在不动点（迭代会在两个体积之间来回震荡）。
    因此按**最小假设**处理：

    - zip 内 manifest 的 ``bundle.sha256`` / ``bundle.bytes`` 用固定宽度的占位值
      （宽度固定 → 构建字节级可复现）；
    - ``dist/manifest.json``（发布清单）里的 ``bundle.sha256/bytes`` 是**真实值**，
      客户端下载离线包后按它校验 —— 校验链的唯一依据是发布清单，不是包内副本。

    离线导入端（04 ``importLocalPack``）只需从包内 manifest 读版本与章节、
    从 ``bank.jsonl`` 读题目、从 ``media/**`` 读媒体，无需校验 zip 自身。
    """
    manifest = manifest_factory(
        {"url": bundle_rel, "sha256": BUNDLE_PLACEHOLDER_SHA, "bytes": BUNDLE_PLACEHOLDER_BYTES}
    )
    entries = [("manifest.json", dumps_pretty(manifest).encode("utf-8")), ("bank.jsonl", bank_bytes)]
    entries.extend((name, path.read_bytes()) for name, path in media_entries)
    return zip_bytes(entries), manifest


def build(
    in_dir: str | Path,
    dist_dir: str | Path,
    state_dir: str | Path,
    chapters: list[ChapterDef],
    released_at: str | None = None,
    min_app_version_code: int = 1,
    keep_deltas: int = DEFAULT_KEEP_DELTAS,
    keep_snapshots: int = DEFAULT_KEEP_SNAPSHOTS,
    bundle: bool = True,
    always_bump: bool = False,
    skip_validate: bool = False,
    media_index: dict[str, dict] | None = None,
    report_path: str | Path | None = None,
) -> BuildResult:
    """执行一次构建。校验失败抛 :class:`BuildError`（除非 ``skip_validate``）。"""
    in_dir = Path(in_dir)
    dist_dir = Path(dist_dir)
    state_dir = Path(state_dir)
    questions_path = in_dir / "questions.jsonl"
    if not questions_path.exists():
        raise BuildError(f"找不到 {questions_path}，请先执行 import → normalize → media")

    released = resolve_released_at(released_at)
    questions = list(read_jsonl(questions_path))
    chapter_order = chapter_order_map(chapters)
    bank_bytes = canonical_questions(questions, chapter_order)
    version_key = sha256_bytes(bank_bytes + b"\x00" + _chapters_digest(chapters).encode("utf-8"))

    # ── 校验（构建前置门槛）──
    validation: ValidationResult | None = None
    if not skip_validate:
        validation = validate(
            in_dir, dist_dir, chapters, media_index=media_index,
            generated_at=released, write_report=False,
        )
        if not validation.ok:
            raise BuildError(
                "校验未通过，已中止构建（详见下方错误；完整报告见 dist/report.md）:\n  - "
                + "\n  - ".join(validation.errors[:20])
            )

    ensure_dir(dist_dir)
    ensure_dir(state_dir / "snapshots")
    ensure_dir(dist_dir / "delta")

    # ── 版本号：内容未变则不递增（幂等）──
    previous = load_state_version(state_dir)
    previous_version = int(previous.get("bank_version", 0) or 0)
    unchanged = (
        not always_bump
        and previous_version > 0
        and previous.get("version_key") == version_key
    )
    bank_version = previous_version if unchanged else previous_version + 1
    notes: list[str] = []
    if unchanged:
        notes.append(f"题库内容与 v{previous_version} 完全一致，bank_version 保持不变（--always-bump 可强制递增）")

    # ── 全量快照 ──
    full_gz = gzip_bytes(bank_bytes)
    full_name = f"bank-v{bank_version}.jsonl.gz"
    full_rel = f"full/{full_name}"
    full_path = dist_dir / full_rel
    if not (unchanged and full_path.exists()):
        atomic_write_bytes(full_path, full_gz)
    full_sha = sha256_bytes(full_path.read_bytes())
    count = bank_bytes.count(b"\n")

    # ── 快照落盘（state/snapshots/v{N}.jsonl，供下次 diff）──
    snapshot_path = state_dir / "snapshots" / f"v{bank_version}.jsonl"
    if not (unchanged and snapshot_path.exists()):
        atomic_write_bytes(snapshot_path, bank_bytes)

    # ── 增量 ──
    delta_refs: list[dict] = []
    delta_stats: list[dict] = []
    if previous_version > 0 and not unchanged:
        old_snapshot = state_dir / "snapshots" / f"v{previous_version}.jsonl"
        old_questions = list(read_jsonl(old_snapshot)) if old_snapshot.exists() else []
        delta = compute_delta(old_questions, questions, chapter_order)
        delta_name = f"v{previous_version}-v{bank_version}.jsonl.gz"
        delta_data = gzip_bytes(delta.records and iter_jsonl_text(delta.records).encode("utf-8") or b"")
        atomic_write_bytes(dist_dir / "delta" / delta_name, delta_data)
        delta_refs.append({
            "from": previous_version,
            "to": bank_version,
            "url": f"delta/{delta_name}",
            "sha256": sha256_bytes(delta_data),
            "bytes": len(delta_data),
        })
        delta_stats.append({**delta_refs[-1], **delta.stats()})
        if delta.is_empty:
            notes.append("题目内容无变化（章节表可能变了），增量文件为空但仍生成，保证增量链连续")

    # 历史增量：读回 dist/delta 里现存的、能连到当前版本的链
    delta_refs = _collect_delta_refs(dist_dir, bank_version) or delta_refs
    removed_deltas = _prune_deltas(dist_dir / "delta", bank_version, keep_deltas)
    if removed_deltas:
        notes.append(f"按保留策略删除旧增量：{', '.join(sorted(removed_deltas))}")
        delta_refs = _collect_delta_refs(dist_dir, bank_version)

    removed_snapshots = _prune_dir(
        state_dir / "snapshots", SNAPSHOT_NAME_RE, None, keep_snapshots
    )

    # ── 媒体 ──
    media_entries, missing_media = collect_media_files(dist_dir, questions)
    if missing_media:
        raise BuildError(
            "以下媒体文件在 dist 中缺失，请先运行 media 步骤:\n  - " + "\n  - ".join(missing_media[:20])
        )

    # ── bundle / manifest ──
    chapters_payload = [c.manifest_dict() for c in chapters]
    bundle_rel = f"bundle/bundle-v{bank_version}.zip"

    def make_manifest(bundle_ref: dict | None) -> dict:
        reference = bundle_ref or {"url": bundle_rel, "sha256": "0" * 64, "bytes": 0}
        manifest = Manifest(
            schema=1,
            bank_version=bank_version,
            released_at=released,
            min_app_version_code=min_app_version_code,
            chapters=chapters_payload,
            full=FullRef(url=full_rel, sha256=full_sha, bytes=len(full_gz), count=count),
            deltas=delta_refs,
            media_base="media/",
            bundle={"url": reference["url"], "sha256": reference["sha256"], "bytes": reference["bytes"]},
        )
        return manifest.model_dump(by_alias=True)

    bundle_sha = ""
    bundle_bytes_len = 0
    manifest_dict: dict
    if bundle:
        data, _internal_manifest = _build_bundle(bank_bytes, media_entries, make_manifest, bundle_rel)
        atomic_write_bytes(dist_dir / bundle_rel, data)
        bundle_sha = sha256_bytes(data)
        bundle_bytes_len = len(data)
        # 发布清单里的 bundle 字段是权威值（客户端下载后按它校验）
        manifest_dict = make_manifest({"url": bundle_rel, "sha256": bundle_sha, "bytes": bundle_bytes_len})
        notes.append(
            "离线包内 manifest.json 的 bundle.sha256/bytes 为占位值（自引用无法精确），"
            "权威值见 dist/manifest.json —— 见 out/CONTRACT_ISSUES.md #1"
        )
    else:
        notes.append("按 --no-bundle 跳过离线整包")
        manifest_dict = make_manifest({"url": bundle_rel, "sha256": sha256_bytes(b""), "bytes": 0})
        bundle_sha = manifest_dict["bundle"]["sha256"]
        bundle_bytes_len = 0

    atomic_write_text(dist_dir / "manifest.json", dumps_pretty(manifest_dict))

    # ── state ──
    atomic_write_text(
        state_dir / "version.json",
        dumps_pretty(
            {
                "bank_version": bank_version,
                "released_at": released,
                "version_key": version_key,
                "full_sha256": full_sha,
                "bundle_sha256": bundle_sha,
                "count": count,
                "updated_at": now_utc_iso(),
            }
        ),
    )

    result = BuildResult(
        bank_version=bank_version,
        released_at=released,
        changed=not unchanged,
        full_url=full_rel,
        full_bytes=len(full_gz),
        full_sha256=full_sha,
        full_count=count,
        deltas=delta_refs,
        bundle_url=bundle_rel if bundle else "",
        bundle_bytes=bundle_bytes_len,
        bundle_sha256=bundle_sha,
        manifest_path=dist_dir / "manifest.json",
        snapshot_path=snapshot_path,
        validation=validation,
        notes=notes,
    )

    # ── 报告 ──
    build_section = BuildSection(
        bank_version=bank_version,
        released_at=released,
        full_url=full_rel,
        full_bytes=len(full_gz),
        full_sha256=full_sha,
        delta_rows=delta_stats or [
            {**ref, "upserts": "-", "deletes": "-"} for ref in delta_refs
        ],
        bundle_url=bundle_rel if bundle else "",
        bundle_bytes=bundle_bytes_len,
        note="；".join(notes),
    )
    data = ReportData(
        questions=questions,
        chapters=chapters,
        errors=[],
        warnings=(validation.warnings if validation else []),
        media_index=media_index or {},
        build=build_section,
        generated_at=released,
        media_files_on_disk=len(media_entries),
        media_bytes_on_disk=sum(path.stat().st_size for _, path in media_entries),
    )
    target = Path(report_path) if report_path else dist_dir / "report.md"
    atomic_write_text(target, render_report(data))
    result.report_path = target
    return result


def _collect_delta_refs(dist_dir: Path, current_version: int) -> list[dict]:
    """扫描 ``dist/delta``，返回能连到 ``current_version`` 的增量链（按 from 升序）。"""
    delta_dir = Path(dist_dir) / "delta"
    if not delta_dir.exists():
        return []
    parsed: list[tuple[int, int, Path]] = []
    for path in delta_dir.iterdir():
        if not path.is_file():
            continue
        match = DELTA_NAME_RE.match(path.name)
        if match:
            parsed.append((int(match.group(1)), int(match.group(2)), path))
    by_to = {to_v: (from_v, path) for from_v, to_v, path in parsed}
    chain: list[tuple[int, int, Path]] = []
    cursor = current_version
    while cursor in by_to:
        from_v, path = by_to[cursor]
        chain.append((from_v, cursor, path))
        cursor = from_v
    chain.reverse()
    return [
        {
            "from": from_v,
            "to": to_v,
            "url": f"delta/{path.name}",
            "sha256": sha256_bytes(path.read_bytes()),
            "bytes": path.stat().st_size,
        }
        for from_v, to_v, path in chain
    ]
