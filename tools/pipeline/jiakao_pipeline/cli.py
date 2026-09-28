"""命令行入口：``python -m jiakao_pipeline <命令>``。

命令一览（与 TASK 的 Makefile 目标一一对应）
    import     原始题库 → work/questions.raw.jsonl（csv/json/sqlite 适配器）
    normalize  清洗/规范化/分配稳定 id → work/questions.norm.jsonl
    media      转码去重 → dist/media/** + work/questions.jsonl（合同格式）
    validate   合同 §2/§3 校验 → dist/report.md
    build      打包 → dist/manifest.json + full/ + delta/ + bundle/ + state/
    diff       两份全量快照 → 增量文件（单独使用）
    serve      dist/ 局域网联调（CORS/Range/ETag）
    sample     生成 20 道样例（自编，非真题）+ 静图/动图
    bench      3000 题 + 500 媒体 性能基准
    publish    调用 out/server/*.sh 发布脚本
    info       打印解析后的路径与配置
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Optional

import typer

from . import __version__
from .chapters import default_chapters_path, load_chapters
from .media import MediaParams
from .util import clean_text

app = typer.Typer(
    add_completion=False,
    no_args_is_help=True,
    help="驾考题库内容流水线（05-content-pipeline）：把原始题库变成 App 可增量更新的题库包。",
)


def _fix_console() -> None:
    """Windows 控制台编码兜底：不可编码字符替换而不是抛异常。"""
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="replace")  # type: ignore[attr-defined]
        except Exception:  # noqa: BLE001 - 非标准流
            pass


def _echo(message: str = "") -> None:
    """打印（统一入口，便于测试时替换）。"""
    typer.echo(message)


def _parse_map(pairs: Optional[list[str]]) -> dict[str, str]:
    """解析 ``--map 字段=列名``。"""
    out: dict[str, str] = {}
    for pair in pairs or []:
        if "=" not in pair:
            raise typer.BadParameter(f"--map 需要 字段=列名 形式，收到 {pair!r}")
        key, value = pair.split("=", 1)
        out[clean_text(key)] = clean_text(value)
    return out


def _load_chapters_or_die(chapters: Optional[Path], quiet: bool = False):
    """加载章节表，出错时给出可操作的提示。"""
    try:
        parsed = load_chapters(chapters)
    except Exception as exc:  # noqa: BLE001 - 章节表问题都归到这里
        _echo(f"章节表加载失败: {exc}")
        raise typer.Exit(code=2) from exc
    if not quiet:
        _echo(f"章节表: {chapters or default_chapters_path()}（{len(parsed)} 章）")
    return parsed


# ───────────────────────── import ─────────────────────────


@app.command("import")
def import_cmd(
    adapter: str = typer.Option("csv", "--adapter", "-a", help="csv | json | sqlite"),
    input_path: Path = typer.Option(..., "--input", "-i", help="原始题库文件"),
    out_dir: Path = typer.Option(Path("./work"), "--out", "-o", help="中间产物目录"),
    images: Optional[Path] = typer.Option(None, "--images", help="素材目录（相对路径按此解析）"),
    field_map: Optional[list[str]] = typer.Option(None, "--map", help="列名覆盖，可重复：--map stem=我的题干"),
    table: Optional[str] = typer.Option(None, "--table", help="sqlite 表名"),
    allow_missing_media: bool = typer.Option(False, "--allow-missing-media", help="素材缺失只告警不报错"),
) -> None:
    """原始题库 → 统一中间表示（并把素材拷进 work/media_src）。"""
    _fix_console()
    from .pipeline import PipelineError, run_import

    try:
        result = run_import(
            adapter=adapter,
            input_path=input_path,
            out_dir=out_dir,
            images_dir=images,
            field_map=_parse_map(field_map),
            table=table,
            allow_missing_media=allow_missing_media,
        )
    except PipelineError as exc:
        _echo(str(exc))
        raise typer.Exit(code=1) from exc
    _echo(f"import 完成：{result.summary()}")
    _echo(f"  → {result.raw_path}")
    if result.warnings:
        _echo(f"  告警 {len(result.warnings)} 条（详见 normalize 报告）")


# ───────────────────────── normalize ─────────────────────────


@app.command("normalize")
def normalize_cmd(
    in_dir: Path = typer.Option(Path("./work"), "--in", help="work 目录"),
    chapters: Optional[Path] = typer.Option(None, "--chapters", help="章节表 YAML"),
    state: Path = typer.Option(Path("./state"), "--state", help="状态目录（id 注册表）"),
    strict: bool = typer.Option(True, "--strict/--no-strict", help="遇到无法修复的题是否中止"),
) -> None:
    """清洗文本、统一选项/答案、推断题型、映射章节、分配稳定 id。"""
    _fix_console()
    from .normalize import NormalizeError
    from .pipeline import PipelineError, run_normalize

    chapter_defs = _load_chapters_or_die(chapters)
    try:
        result = run_normalize(in_dir, chapter_defs, state, strict=strict)
    except (PipelineError, NormalizeError) as exc:
        _echo(str(exc))
        raise typer.Exit(code=1) from exc
    stats = result.stats
    _echo(
        f"normalize 完成：{stats['total']} 题"
        f"（新增 id {stats['new_ids']}，rev+1 {stats['rev_bumped']}，退役 {stats['retired']}）"
    )
    _echo(f"  题型分布: {stats['by_type']}，分科目: {stats['by_subject']}，含媒体: {stats['with_media']}")
    if result.warnings:
        _echo(f"  告警 {len(result.warnings)} 条（见 work/normalize_report.json）")
    _echo(f"  → {in_dir / 'questions.norm.jsonl'}")


# ───────────────────────── media ─────────────────────────


@app.command("media")
def media_cmd(
    in_dir: Path = typer.Option(Path("./work"), "--in", help="work 目录"),
    out_dir: Path = typer.Option(Path("./dist"), "--out", help="产物目录（媒体落到 dist/media）"),
    jobs: Optional[int] = typer.Option(None, "--jobs", "-j", help="并行进程数（默认 CPU 核数，1 表示单进程）"),
    image_quality: int = typer.Option(82, "--image-quality", help="静图 WebP 质量"),
    image_max_edge: int = typer.Option(1080, "--image-max-edge", help="静图长边上限"),
    anim_max_edge: int = typer.Option(1080, "--anim-max-edge", help="动图长边上限"),
    anim_max_bytes: int = typer.Option(1_500_000, "--anim-max-bytes", help="动图单文件上限（超出降质量/降帧）"),
    video_max_seconds: float = typer.Option(5.0, "--video-max-seconds", help="超过该时长视为真视频"),
    quiet: bool = typer.Option(False, "--quiet", "-q", help="不显示进度条"),
) -> None:
    """素材转码：静图/动图 → WebP，真视频 → mp4；内容寻址去重 + 缓存。"""
    _fix_console()
    from .pipeline import PipelineError, run_media

    params = MediaParams(
        image_quality=image_quality,
        image_max_edge=image_max_edge,
        anim_max_edge=anim_max_edge,
        anim_max_bytes=anim_max_bytes,
        video_max_seconds=video_max_seconds,
    )
    jobs = _resolve_jobs(jobs)
    progress = _make_progress(quiet)
    try:
        result = run_media(in_dir, out_dir, params=params, jobs=jobs, progress=progress)
    except PipelineError as exc:
        _echo(str(exc))
        raise typer.Exit(code=1) from exc
    finally:
        if progress is not None:
            progress.close()

    _echo(f"media 完成：{result.summary()}（jobs={jobs}）")
    if result.degraded:
        _echo(f"  ⚠ 降级处理 {len(result.degraded)} 个（报告中标黄）")
    if result.warnings:
        _echo(f"  告警 {len(result.warnings)} 条（见 work/media_index.json 与 dist/report.md）")
    _echo(f"  → {in_dir / 'questions.jsonl'}（合同格式）")


class _Progress:
    """tqdm 进度条包装（可调用对象，匹配 ``process_many(progress=…)`` 的签名）。"""

    def __init__(self, total: int = 0) -> None:
        from tqdm import tqdm

        self.bar = tqdm(total=total, unit="个", desc="媒体转码", ncols=88)

    def __call__(self, done: int, total: int) -> None:
        self.bar.total = total
        self.bar.n = done
        self.bar.refresh()

    def close(self) -> None:
        """关闭进度条。"""
        self.bar.close()


def _make_progress(quiet: bool) -> Optional["_Progress"]:
    """按需创建进度条（quiet 或缺 tqdm 时返回 None）。"""
    if quiet:
        return None
    try:
        return _Progress()
    except ImportError:  # pragma: no cover - tqdm 是必装依赖
        return None


def _resolve_jobs(jobs: Optional[int]) -> int:
    """并行度：显式参数 > 环境变量 > CPU 核数（上限 8）。"""
    import os

    if jobs is not None:
        return max(1, jobs)
    env = os.environ.get("JIAKAO_JOBS")
    if env and env.isdigit():
        return max(1, int(env))
    return max(1, min(8, (os.cpu_count() or 1)))


# ───────────────────────── validate ─────────────────────────


@app.command("validate")
def validate_cmd(
    in_dir: Path = typer.Option(Path("./work"), "--in", help="work 目录"),
    dist: Path = typer.Option(Path("./dist"), "--dist", help="产物目录（校验媒体引用）"),
    chapters: Optional[Path] = typer.Option(None, "--chapters", help="章节表 YAML"),
    report: Optional[Path] = typer.Option(None, "--report", help="报告输出路径（默认 dist/report.md）"),
) -> None:
    """按合同 §2/§3 校验，失败非零退出。"""
    _fix_console()
    from .pipeline import load_media_index
    from .util import now_utc_iso
    from .validate import validate

    chapter_defs = _load_chapters_or_die(chapters)
    result = validate(
        in_dir, dist, chapter_defs,
        report_path=report,
        media_index=load_media_index(in_dir),
        generated_at=now_utc_iso(),
    )
    _echo(result.summary())
    if not result.ok:
        for error in result.errors[:20]:
            _echo(f"  ✗ {error}")
        if len(result.errors) > 20:
            _echo(f"  … 另有 {len(result.errors) - 20} 个错误")
        _echo(f"  完整报告: {result.report_path}")
        raise typer.Exit(code=1)
    for warning in result.warnings[:10]:
        _echo(f"  ! {warning}")
    if len(result.warnings) > 10:
        _echo(f"  … 另有 {len(result.warnings) - 10} 条告警")
    _echo(f"  报告: {result.report_path}")


# ───────────────────────── build ─────────────────────────


@app.command("build")
def build_cmd(
    in_dir: Path = typer.Option(Path("./work"), "--in", help="work 目录"),
    dist: Path = typer.Option(Path("./dist"), "--dist", help="产物目录"),
    state: Path = typer.Option(Path("./state"), "--state", help="状态目录（版本/快照）"),
    chapters: Optional[Path] = typer.Option(None, "--chapters", help="章节表 YAML"),
    released_at: Optional[str] = typer.Option(None, "--released-at", help="固定发布时间（可复现构建用）"),
    min_app_version_code: int = typer.Option(1, "--min-app-version-code"),
    keep_deltas: int = typer.Option(5, "--keep-deltas", help="保留最近几个增量"),
    keep_snapshots: int = typer.Option(5, "--keep-snapshots", help="保留最近几份快照"),
    bundle: bool = typer.Option(True, "--bundle/--no-bundle", help="是否生成离线整包"),
    always_bump: bool = typer.Option(False, "--always-bump", help="内容未变也递增 bank_version"),
    skip_validate: bool = typer.Option(False, "--skip-validate", help="跳过校验（不建议）"),
) -> None:
    """打包：manifest + full + delta + media + bundle + report。"""
    _fix_console()
    from .build_pack import BuildError, build
    from .pipeline import load_media_index

    chapter_defs = _load_chapters_or_die(chapters)
    try:
        result = build(
            in_dir=in_dir,
            dist_dir=dist,
            state_dir=state,
            chapters=chapter_defs,
            released_at=released_at,
            min_app_version_code=min_app_version_code,
            keep_deltas=keep_deltas,
            keep_snapshots=keep_snapshots,
            bundle=bundle,
            always_bump=always_bump,
            skip_validate=skip_validate,
            media_index=load_media_index(in_dir),
        )
    except BuildError as exc:
        _echo(str(exc))
        raise typer.Exit(code=1) from exc

    _echo(f"build 完成：{result.summary()}")
    for note in result.notes:
        _echo(f"  · {note}")
    if result.validation and result.validation.warnings:
        _echo(f"  校验告警 {len(result.validation.warnings)} 条")
    _echo(f"  manifest: {result.manifest_path}")
    _echo(f"  报告:     {result.report_path}")


# ───────────────────────── diff ─────────────────────────


@app.command("diff")
def diff_cmd(
    new: Path = typer.Option(..., "--new", help="新版全量 jsonl"),
    old: Optional[Path] = typer.Option(None, "--old", help="旧版全量 jsonl（缺省表示首个版本）"),
    out: Path = typer.Option(..., "--out", help="增量输出路径（.jsonl.gz）"),
    chapters: Optional[Path] = typer.Option(None, "--chapters", help="章节表 YAML（排序用）"),
) -> None:
    """两份全量快照 → 增量文件（upsert + 删除行）。"""
    _fix_console()
    from .chapters import chapter_order_map
    from .diff import delta_from_files
    from .util import atomic_write_bytes, gzip_bytes, iter_jsonl_text, sha256_bytes

    chapter_defs = _load_chapters_or_die(chapters, quiet=True)
    delta = delta_from_files(old, new, chapter_order_map(chapter_defs))
    payload = iter_jsonl_text(delta.records).encode("utf-8")
    data = gzip_bytes(payload)
    atomic_write_bytes(out, data)
    _echo(
        f"diff 完成：upsert {len(delta.upserts)}，删除 {len(delta.deletes)}，"
        f"输出 {out}（{len(data)} 字节，sha256 {sha256_bytes(data)[:16]}…）"
    )


# ───────────────────────── serve ─────────────────────────


@app.command("serve")
def serve_cmd(
    dist: Path = typer.Option(Path("./dist"), "--dist", help="发布目录"),
    port: int = typer.Option(8000, "--port", "-p"),
    host: str = typer.Option("0.0.0.0", "--host"),
    quiet: bool = typer.Option(False, "--quiet", "-q", help="静默访问日志"),
) -> None:
    """本地/局域网联调（CORS + Range + ETag + 缓存头）。"""
    _fix_console()
    from .serve import serve_forever

    try:
        serve_forever(dist, port=port, host=host, quiet=quiet)
    except FileNotFoundError as exc:
        _echo(str(exc))
        raise typer.Exit(code=1) from exc


# ───────────────────────── sample ─────────────────────────


@app.command("sample")
def sample_cmd(
    out: Path = typer.Option(Path("./sample"), "--out", "-o", help="样例输出目录"),
) -> None:
    """生成 20 道自编样例题 + Pillow 绘制的静图/动图（不含任何真题）。"""
    _fix_console()
    from .sample import generate_sample, summarize

    result = generate_sample(out)
    stats = summarize()
    _echo(
        f"sample 完成：{stats['total']} 题（分科目 {stats['by_subject']}，题型 {stats['by_type']}，"
        f"含媒体 {stats['with_media']}），图片 {len(result['images'])} 个"
    )
    _echo(f"  → {out}/questions.csv | questions.json | questions.sqlite3 | images/")
    _echo(f"  注意：{stats['note']}")


# ───────────────────────── bench ─────────────────────────


@app.command("bench")
def bench_cmd(
    questions: int = typer.Option(3000, "--questions", "-n", help="合成题目数"),
    media: int = typer.Option(500, "--media", "-m", help="合成媒体数"),
    out: Path = typer.Option(Path("./bench"), "--out", help="基准工作目录"),
    jobs: Optional[int] = typer.Option(None, "--jobs", "-j"),
    media_edge: int = typer.Option(240, "--media-edge", help="合成图长边（1440 更接近真实照片）"),
    keep: bool = typer.Option(False, "--keep", help="保留基准目录（便于排查）"),
) -> None:
    """性能基准：N 题 + M 媒体，报告首次构建与缓存构建耗时。"""
    _fix_console()
    from .bench import run_bench

    report = run_bench(
        out, questions=questions, media=media, jobs=_resolve_jobs(jobs), keep=keep, media_edge=media_edge
    )
    for line in report.lines:
        _echo(line)
    if not report.passed:
        raise typer.Exit(code=1)


# ───────────────────────── publish ─────────────────────────


@app.command("publish")
def publish_cmd(
    target: str = typer.Option(..., "--target", "-t", help="rsync | r2 | ghpages"),
    dist: Path = typer.Option(Path("./dist"), "--dist", help="发布目录"),
    dest: Optional[str] = typer.Option(None, "--dest", help="rsync 目标（user@host:/path/）或 R2 bucket"),
    dry_run: bool = typer.Option(False, "--dry-run", help="只打印将执行的命令"),
) -> None:
    """调用 out/server/ 下的发布脚本（rsync / Cloudflare R2 / GitHub Pages）。"""
    _fix_console()
    import shutil
    import subprocess

    mapping = {"rsync": "publish_rsync.sh", "r2": "publish_r2.sh", "ghpages": "publish_ghpages.sh"}
    if target not in mapping:
        _echo(f"--target 只支持 {', '.join(mapping)}，收到 {target!r}")
        raise typer.Exit(code=2)
    script = Path(__file__).resolve().parent.parent.parent / "server" / mapping[target]
    if not script.exists():
        _echo(f"找不到发布脚本: {script}")
        raise typer.Exit(code=1)

    args = ["bash", str(script), str(dist)]
    if dest:
        args.append(dest)
    if dry_run:
        _echo("将执行: " + " ".join(args))
        return
    if not shutil.which("bash"):
        _echo("本机没有 bash，无法执行发布脚本。请在 Linux/macOS（或 WSL/Git Bash）里运行：")
        _echo("  " + " ".join(args))
        _echo(f"脚本位置: {script}")
        return
    completed = subprocess.run(args, check=False)
    raise typer.Exit(code=completed.returncode)


# ───────────────────────── info ─────────────────────────


@app.command("info")
def info_cmd() -> None:
    """打印版本与解析后的路径（排查"文件到底在哪"用）。"""
    _fix_console()
    import os

    _echo(f"jiakao-pipeline {__version__}")
    _echo(f"python: {sys.version.split()[0]} ({sys.executable})")
    _echo(f"工作目录: {Path.cwd()}")
    _echo(f"章节表:   {default_chapters_path()}")
    _echo(f"默认 work/dist/state: ./work ./dist ./state")
    _echo(f"CPU 核数: {os.cpu_count()}（媒体并行度默认 min(8, 核数)，可用 --jobs/-j 或 JIAKAO_JOBS 覆盖）")
    try:
        import PIL

        _echo(f"Pillow:   {PIL.__version__}")
    except ImportError:  # pragma: no cover
        _echo("Pillow:   未安装（media 步骤不可用）")
    from .media import KINDS  # noqa: F401 - 触发可选依赖探测

    _echo(f"ffmpeg:   {'可用' if _which('ffmpeg') else '缺失（真视频原样保留，动图仍可用 Pillow）'}")
    _echo(f"gif2webp: {'可用' if _which('gif2webp') else '缺失（动图走 Pillow）'}")
    _echo("题库数据须自行合法获取；本工具不内置、不下载、不爬取任何真题。")


def _which(name: str) -> bool:
    """命令是否存在。"""
    import shutil

    return shutil.which(name) is not None


def main() -> None:
    """控制台脚本入口。"""
    app()


if __name__ == "__main__":  # pragma: no cover
    main()
