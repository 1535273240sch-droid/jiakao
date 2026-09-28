"""人类可读报告渲染（``dist/report.md``）。

报告是唯一"人看的"产物：题量、题型/章节分布、媒体体积、告警列表、组卷可行性、构建产物清单。
所以这里只做纯函数式的拼装，便于测试与在 validate/build 之间复用。
"""

from __future__ import annotations

from dataclasses import dataclass, field

from .chapters import ChapterDef
from .util import human_bytes

#: 合同 §4 的组卷要求（科目 → 需要题量）。
EXAM_REQUIREMENT = {1: 100, 4: 50}
SUBJECT_NAME = {1: "科目一", 4: "科目四"}
TYPE_NAME = {"judge": "判断题", "single": "单选题", "multi": "多选题"}
KIND_NAME = {"image": "静图", "anim": "动图", "video": "视频"}
#: 报告里的"标黄"标记（媒体超预算/降级处理）。
WARN_MARK = "⚠"
#: 属于正常处理提示（不算降级）的告警关键词。
BENIGN_WARNING_HINTS = ("长边超过",)


def _benign_only(item: dict) -> bool:
    """该媒体的告警是否都只是"正常处理提示"（缩放等）。"""
    warnings = [w for w in (item.get("warnings") or []) if w]
    if not warnings:
        return False
    return all(any(hint in w for hint in BENIGN_WARNING_HINTS) for w in warnings)


@dataclass
class BuildSection:
    """构建产物摘要。"""

    bank_version: int = 0
    released_at: str = ""
    full_url: str = ""
    full_bytes: int = 0
    full_sha256: str = ""
    delta_rows: list[dict] = field(default_factory=list)
    bundle_url: str = ""
    bundle_bytes: int = 0
    note: str = ""


@dataclass
class ReportData:
    """报告数据。"""

    questions: list[dict]
    chapters: list[ChapterDef]
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    media_index: dict[str, dict] = field(default_factory=dict)
    build: BuildSection | None = None
    generated_at: str = ""
    source_note: str = "样例/自备数据，本流水线不内置任何真题"
    media_files_on_disk: int = 0
    media_bytes_on_disk: int = 0
    orphan_media: list[str] = field(default_factory=list)


def _table(headers: list[str], rows: list[list[str]]) -> str:
    """Markdown 表格。"""
    if not rows:
        return "_（无）_\n"
    out = ["| " + " | ".join(headers) + " |", "|" + "|".join(["---"] * len(headers)) + "|"]
    out.extend("| " + " | ".join(str(c) for c in row) + " |" for row in rows)
    return "\n".join(out) + "\n"


def collect_stats(data: ReportData) -> dict:
    """统计题量、分布与媒体体积。"""
    questions = data.questions
    by_subject: dict[int, int] = {}
    by_type: dict[str, int] = {}
    by_chapter: dict[str, int] = {}
    with_media = 0
    by_kind: dict[str, int] = {}
    media_refs: dict[str, dict] = {}
    for question in questions:
        by_subject[question["subject"]] = by_subject.get(question["subject"], 0) + 1
        by_type[question["type"]] = by_type.get(question["type"], 0) + 1
        by_chapter[question["chapter_id"]] = by_chapter.get(question["chapter_id"], 0) + 1
        refs = question.get("media") or []
        if refs:
            with_media += 1
        for ref in refs:
            media_refs.setdefault(ref["sha256"], ref)
            by_kind[ref["kind"]] = by_kind.get(ref["kind"], 0) + 1
    largest = None
    if media_refs:
        largest = max(media_refs.values(), key=lambda r: r["bytes"])
    degraded = [
        item
        for item in data.media_index.values()
        if item.get("degraded") or ((item.get("warnings") or []) and not _benign_only(item))
    ]
    resized = [
        item for item in data.media_index.values() if _benign_only(item)
    ]
    return {
        "total": len(questions),
        "by_subject": by_subject,
        "by_type": by_type,
        "by_chapter": by_chapter,
        "with_media": with_media,
        "with_anim": len(
            {q["id"] for q in questions if any(r["kind"] in ("anim", "video") for r in (q.get("media") or []))}
        ),
        "with_video": len(
            {q["id"] for q in questions if any(r["kind"] == "video" for r in (q.get("media") or []))}
        ),
        "media_refs": media_refs,
        "media_ref_count": len(media_refs),
        "media_ref_bytes": sum(r["bytes"] for r in media_refs.values()),
        "by_kind": by_kind,
        "largest": largest,
        "degraded": degraded,
        "resized": resized,
    }


def render_report(data: ReportData) -> str:
    """渲染 ``dist/report.md``。"""
    stats = collect_stats(data)
    lines: list[str] = []
    lines.append("# 题库构建报告\n")
    lines.append(f"- 生成时间：{data.generated_at}")
    lines.append(f"- 题目总数：**{stats['total']}**")
    subjects = " / ".join(
        f"{SUBJECT_NAME.get(s, s)} {c}" for s, c in sorted(stats["by_subject"].items())
    ) or "无"
    lines.append(f"- 分科目：{subjects}")
    lines.append(f"- 含媒体题：{stats['with_media']}（其中动图/视频题 {stats['with_anim']}，视频题 {stats['with_video']}）")
    lines.append(f"- 去重后媒体文件：{stats['media_ref_count']} 个，合计 {human_bytes(stats['media_ref_bytes'])}")
    if data.media_files_on_disk:
        lines.append(
            f"- dist/media 实占：{data.media_files_on_disk} 个文件 / {human_bytes(data.media_bytes_on_disk)}"
            "（跨版本复用，只增不删）"
        )
    lines.append(f"- 数据来源：{data.source_note}\n")

    lines.append("## 校验结果\n")
    if data.errors:
        lines.append(f"❌ **失败**：{len(data.errors)} 个错误\n")
        lines.append(_table(["#", "错误"], [[i, e] for i, e in enumerate(data.errors, 1)]))
    else:
        lines.append("✅ **通过**：合同 §2/§3 字段、命名、排序、媒体引用全部一致\n")
    if data.warnings:
        lines.append(f"### 告警（{len(data.warnings)}）\n")
        lines.append(_table(["#", "告警"], [[i, w] for i, w in enumerate(data.warnings, 1)]))

    lines.append("## 题型分布\n")
    lines.append(
        _table(
            ["题型", "数量"],
            [[TYPE_NAME.get(t, t), c] for t, c in sorted(stats["by_type"].items())],
        )
    )

    lines.append("## 章节分布\n")
    chapter_index = {c.id: c for c in data.chapters}
    rows = []
    for chapter in data.chapters:
        rows.append(
            [chapter.id, SUBJECT_NAME.get(chapter.subject, str(chapter.subject)), chapter.name,
             stats["by_chapter"].get(chapter.id, 0)]
        )
    lines.append(_table(["章节 id", "科目", "名称", "题数"], rows))
    unknown = {k: v for k, v in stats["by_chapter"].items() if k not in chapter_index}
    if unknown:
        lines.append(f"⚠ 章节表外的 chapter_id：{unknown}\n")

    lines.append("## 媒体\n")
    kind_rows = [[KIND_NAME.get(k, k), c] for k, c in sorted(stats["by_kind"].items())]
    lines.append(_table(["类型（引用次数）", "数量"], kind_rows))
    largest = stats["largest"]
    if largest:
        lines.append(
            f"- 最大媒体：`{largest['sha256'][:16]}…` {human_bytes(largest['bytes'])}"
            f" {largest['w']}×{largest['h']} {KIND_NAME.get(largest['kind'], largest['kind'])}\n"
        )
    if stats["resized"]:
        lines.append(
            f"- 缩放处理：{len(stats['resized'])} 个（长边 > 上限，属正常处理，明细见 media_index.json）\n"
        )
    if stats["degraded"]:
        lines.append(f"### {WARN_MARK} 降级/告警的媒体（{len(stats['degraded'])}）\n")
        lines.append(
            _table(
                ["源文件", "结果", "说明"],
                [
                    [
                        item.get("src", ""),
                        f"{item.get('kind', '')} {human_bytes(item.get('bytes', 0))}",
                        "；".join(item.get("warnings") or []) or "降级处理",
                    ]
                    for item in stats["degraded"]
                ],
            )
        )
    if data.orphan_media:
        lines.append(f"- 未被引用的存量媒体（{len(data.orphan_media)} 个，属正常跨版本保留）：{', '.join(data.orphan_media[:5])}…\n")

    lines.append("## 组卷可行性（合同 §4）\n")
    exam_rows = []
    for subject, need in sorted(EXAM_REQUIREMENT.items()):
        have = stats["by_subject"].get(subject, 0)
        exam_rows.append(
            [SUBJECT_NAME[subject], need, have, "✅ 可组卷" if have >= need else "⚠ 题量不足（不影响构建）"]
        )
    lines.append(_table(["科目", "需题量", "现有", "结论"], exam_rows))

    if data.build is not None:
        build = data.build
        lines.append("## 构建产物\n")
        lines.append(f"- bank_version：**{build.bank_version}**（released_at {build.released_at}）")
        if build.full_url:
            lines.append(
                f"- 全量快照：`{build.full_url}` {human_bytes(build.full_bytes)} sha256 `{build.full_sha256[:16]}…`"
            )
        if build.delta_rows:
            lines.append("- 增量：" + "、".join(
                f"`{row['url']}`（v{row['from']}→v{row['to']}，{row['upserts']} upsert / {row['deletes']} 删除，{human_bytes(row['bytes'])}）"
                for row in build.delta_rows
            ))
        else:
            lines.append("- 增量：无（首个版本或内容未变化）")
        if build.bundle_url:
            lines.append(f"- 离线整包：`{build.bundle_url}` {human_bytes(build.bundle_bytes)}")
        else:
            lines.append("- 离线整包：未生成（--no-bundle）")
        if build.note:
            lines.append(f"- 备注：{build.note}")
        lines.append("")

    return "\n".join(lines).rstrip() + "\n"
