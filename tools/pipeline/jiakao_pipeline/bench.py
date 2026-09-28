"""性能基准（``make bench``）：合成 N 题 + M 媒体，量化"首次构建"与"缓存构建"耗时。

验收指标（TASK）：3000 题 + 500 媒体，首次构建 ≤ 2 分钟，有缓存 ≤ 15 秒。

基准数据全部是**程序合成的**（不含任何真题），生成耗时单独计时、不计入构建耗时。
"""

from __future__ import annotations

import shutil
import time
from dataclasses import dataclass, field
from pathlib import Path

from PIL import Image, ImageDraw

from .build_pack import build
from .chapters import load_chapters
from .media import MediaParams
from .pipeline import load_media_index, run_import, run_media, run_normalize
from .util import human_bytes

#: 验收阈值（秒）。
FIRST_BUILD_LIMIT = 120.0
CACHED_BUILD_LIMIT = 15.0


@dataclass
class BenchReport:
    """基准结果。"""

    timings: dict[str, float] = field(default_factory=dict)
    counts: dict[str, int] = field(default_factory=dict)
    sizes: dict[str, int] = field(default_factory=dict)
    lines: list[str] = field(default_factory=list)
    passed: bool = True

    def add(self, line: str) -> None:
        """追加一行输出。"""
        self.lines.append(line)

    def check(self, name: str, value: float, limit: float) -> None:
        """记录一条阈值判定。"""
        ok = value <= limit
        self.passed = self.passed and ok
        self.add(f"  {'✅' if ok else '❌'} {name}: {value:.2f}s（上限 {limit:.0f}s）")


def generate_dataset(root: Path, questions: int, media: int, media_edge: int = 240) -> tuple[Path, Path, float]:
    """合成原始 CSV + 媒体图片，返回 ``(csv 路径, 图片目录, 生成耗时秒)``。

    ``media_edge`` 控制合成图的长边。默认 240 是"小而快"的基准；
    想接近真实照片的编码开销可以用 ``--media-edge 1440``（并相应调大上限预期）。
    """
    started = time.perf_counter()
    raw_dir = root / "raw"
    images_dir = raw_dir / "images"
    images_dir.mkdir(parents=True, exist_ok=True)

    rows: list[list[str]] = []
    chapters_1 = ["s1-c01", "s1-c02", "s1-c03", "s1-c04", "s1-c05"]
    chapters_4 = ["s4-c01", "s4-c02", "s4-c03", "s4-c04", "s4-c05", "s4-c06"]
    for index in range(questions):
        subject = 1 if index % 3 else 4
        chapter = (chapters_1 if subject == 1 else chapters_4)[index % (5 if subject == 1 else 6)]
        kind = ("judge", "single", "multi")[index % 3]
        image = ""
        if index < media:
            name = f"gen_{index:05d}.png"
            image = name
            _draw_generated(images_dir / name, index, media_edge)
        if kind == "judge":
            options = ["", "", "", ""]
            answer = "正确" if index % 2 else "错误"
        elif kind == "single":
            options = [f"选项甲{index}", f"选项乙{index}", f"选项丙{index}", f"选项丁{index}"]
            answer = "ACBD"[index % 4]
        else:
            options = [f"多选甲{index}", f"多选乙{index}", f"多选丙{index}", f"多选丁{index}"]
            answer = "A、B" if index % 2 else "B、C、D"
        rows.append([
            f"B{index:06d}",
            "科目一" if subject == 1 else "科目四",
            {"judge": "判断", "single": "单选", "multi": "多选"}[kind],
            chapter,
            "基准,合成",
            f"第 {index} 题：这是一条用于性能基准的合成题干（不含真题内容）。",
            *options,
            answer,
            "合成解析。",
            image,
        ])

    csv_path = raw_dir / "questions.csv"
    with open(csv_path, "w", encoding="utf-8-sig", newline="") as fh:
        import csv as csv_module

        writer = csv_module.writer(fh)
        writer.writerow(["编号", "科目", "题型", "章节", "标签", "题干", "选项A", "选项B", "选项C", "选项D", "答案", "解析", "图片"])
        writer.writerows(rows)
    return csv_path, images_dir, time.perf_counter() - started


def _draw_generated(path: Path, index: int, edge: int = 240) -> None:
    """画一张内容唯一的合成图（默认 240×240，避免缩放开销干扰基准）。"""
    image = Image.new("RGB", (edge, edge), ((index * 7) % 256, (index * 13) % 256, (index * 29) % 256))
    draw = ImageDraw.Draw(image)
    inset = max(2, edge // 12)
    draw.rectangle((inset, inset, edge - inset, edge - inset), outline=(255, 255, 255), width=max(1, edge // 80))
    step_size = max(4, edge // 12)
    for step in range(index % 7 + 1):
        offset = inset + step * step_size
        if offset + step_size > edge:
            break
        draw.ellipse((offset, offset, offset + step_size, offset + step_size),
                     fill=((index * 3) % 256, 200, (index * 5) % 256))
    image.save(path, "PNG")


def dir_size(path: Path) -> int:
    """目录总字节数。"""
    if not path.exists():
        return 0
    return sum(item.stat().st_size for item in path.rglob("*") if item.is_file())


def run_bench(
    root: str | Path,
    questions: int = 3000,
    media: int = 500,
    jobs: int = 4,
    keep: bool = False,
    media_edge: int = 240,
) -> BenchReport:
    """执行基准并返回报告。"""
    root = Path(root)
    report = BenchReport()
    if root.exists() and not keep:
        shutil.rmtree(root, ignore_errors=True)
    root.mkdir(parents=True, exist_ok=True)

    work = root / "work"
    dist = root / "dist"
    state = root / "state"
    report.add(f"基准数据：{questions} 题 + {media} 媒体（合成图长边 {media_edge}px），jobs={jobs}")
    csv_path, images_dir, gen_seconds = generate_dataset(root, questions, media, media_edge)
    report.add(f"  合成数据生成：{gen_seconds:.2f}s（不计入构建耗时）")

    chapters = load_chapters()
    params = MediaParams()

    t0 = time.perf_counter()
    step = run_import("csv", csv_path, work, images_dir=images_dir)
    t1 = time.perf_counter()
    norm = run_normalize(work, chapters, state)
    t2 = time.perf_counter()
    media_step = run_media(work, dist, params=params, jobs=jobs)
    t3 = time.perf_counter()
    result = build(in_dir=work, dist_dir=dist, state_dir=state, chapters=chapters,
                   media_index=load_media_index(work), released_at="2026-01-01T00:00:00Z")
    t4 = time.perf_counter()

    import_seconds = t1 - t0
    normalize_seconds = t2 - t1
    media_seconds = t3 - t2
    build_seconds = t4 - t3
    first_total = t4 - t0

    report.timings = {
        "import": import_seconds,
        "normalize": normalize_seconds,
        "media": media_seconds,
        "build": build_seconds,
        "first_build_total": first_total,
    }
    report.counts = {
        "questions": step.raw_count,
        "normalized": norm.stats.get("total", 0),
        "media_sources": media_step.unique_sources,
        "media_outputs": media_step.deduped_outputs,
        "bank_version": result.bank_version,
    }
    report.sizes = {
        "media": dir_size(dist / "media"),
        "full": result.full_bytes,
        "bundle": result.bundle_bytes,
    }

    report.add("首次构建（import → normalize → media → build）：")
    report.add(f"  import {import_seconds:.2f}s / normalize {normalize_seconds:.2f}s / "
               f"media {media_seconds:.2f}s（{media_step.processed} 新处理 + {media_step.cached} 缓存）/ "
               f"build {build_seconds:.2f}s")
    report.check("首次构建总耗时", first_total, FIRST_BUILD_LIMIT)

    # ── 二次构建（缓存命中；内容未变 → bank_version 不变）──
    c0 = time.perf_counter()
    media_again = run_media(work, dist, params=params, jobs=jobs)
    build_again = build(in_dir=work, dist_dir=dist, state_dir=state, chapters=chapters,
                        media_index=load_media_index(work), released_at="2026-01-01T00:00:00Z")
    cached_total = time.perf_counter() - c0
    report.timings["cached_build_total"] = cached_total
    report.add(f"缓存构建（media + build）：{cached_total:.2f}s（缓存命中 {media_again.cached}/{media_again.unique_sources}）")
    report.check("缓存构建总耗时", cached_total, CACHED_BUILD_LIMIT)

    report.add("产物体积：")
    report.add(f"  media {human_bytes(report.sizes['media'])} / 全量包 {human_bytes(report.sizes['full'])} "
               f"/ 离线包 {human_bytes(report.sizes['bundle'])}")
    report.add(f"  题数 {report.counts['questions']}，媒体去重后 {report.counts['media_outputs']} 个产物，"
               f"bank_version {report.counts['bank_version']}"
               + ("（内容未变未递增）" if build_again.bank_version == result.bank_version else ""))
    if media_step.degraded:
        report.add(f"  ⚠ 降级处理 {len(media_step.degraded)} 个媒体")

    report.add("结论：" + ("✅ 全部达标" if report.passed else "❌ 有指标未达标"))
    if not keep:
        shutil.rmtree(root, ignore_errors=True)
    return report
