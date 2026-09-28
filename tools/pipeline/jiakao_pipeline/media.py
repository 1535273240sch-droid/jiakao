"""媒体处理：转码、去重、内容寻址输出、缓存、多进程。

规则（合同 §3 / TASK"媒体处理规则"）
- 静图 PNG/JPG/BMP/… → WebP：有损 q=82；含实际透明像素 → 无损；长边 > 1080 缩到 1080。
- 动图 GIF/APNG/短 MP4 → **动态 WebP**，保持原帧率，单文件 ≤ 1.5MB，超限自动降质量/降帧并告警。
- 真视频（> 5s）→ 保留 mp4（H.264 baseline + faststart），``kind=video``。
- 输出以内容 sha256 命名：``media/{sha[:2]}/{sha}.{ext}``；记录 w/h/bytes；同内容自动去重；清除 EXIF。
- 缓存：源文件 ``(mtime_ns, size)`` + 处理参数 → 结果；二次构建秒级。

Pillow 不支持的输入会依次回退 ``gif2webp`` / ``ffmpeg``（都是可选系统工具）。
"""

from __future__ import annotations

import io
import json
import os
import shutil
import struct
import subprocess
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Sequence

from PIL import Image, ImageOps

from .util import atomic_write_bytes, ensure_dir, load_json, sha256_bytes

#: 静态图片扩展名。
STATIC_EXTS = {".png", ".jpg", ".jpeg", ".bmp", ".tif", ".tiff", ".webp", ".avif"}
#: 可能是动图的扩展名。
ANIM_EXTS = {".gif", ".webp", ".png", ".apng"}
#: 视频容器扩展名（时长 ≤ 阈值则当动图处理）。
VIDEO_EXTS = {".mp4", ".m4v", ".mov", ".webm", ".mkv", ".avi"}
#: kind 提示的合法值。
KINDS = ("image", "anim", "video")


class MediaError(RuntimeError):
    """媒体处理失败（携带全部失败明细）。"""

    def __init__(self, errors: Sequence[str]) -> None:
        self.errors = list(errors)
        head = "\n  - ".join(self.errors[:10])
        more = f"\n  … 另有 {len(self.errors) - 10} 条" if len(self.errors) > 10 else ""
        super().__init__(f"媒体处理失败，共 {len(self.errors)} 个文件:\n  - {head}{more}")


@dataclass
class MediaParams:
    """转码参数（进缓存键，改参数会触发重新转码）。"""

    image_quality: int = 82
    image_max_edge: int = 1080
    anim_max_edge: int = 1080
    anim_max_bytes: int = 1_500_000
    video_max_seconds: float = 5.0
    webp_method: int = 4
    lossy_max_edge_marker: bool = True

    def signature(self) -> dict:
        """缓存键里的参数部分。"""
        return {
            "image_quality": self.image_quality,
            "image_max_edge": self.image_max_edge,
            "anim_max_edge": self.anim_max_edge,
            "anim_max_bytes": self.anim_max_bytes,
            "video_max_seconds": self.video_max_seconds,
            "webp_method": self.webp_method,
        }


@dataclass
class MediaResult:
    """一个源文件的处理结果。"""

    src: str
    sha256: str
    ext: str
    kind: str
    w: int
    h: int
    bytes: int
    warnings: list[str] = field(default_factory=list)
    degraded: bool = False
    cached: bool = False

    def ref(self) -> dict:
        """合同 §2 ``media`` 元素（字段顺序固定）。"""
        return {
            "sha256": self.sha256,
            "ext": self.ext,
            "kind": self.kind,
            "w": self.w,
            "h": self.h,
            "bytes": self.bytes,
        }

    def to_dict(self) -> dict:
        """缓存/日志用的扁平字典。"""
        return {
            "src": self.src,
            "sha256": self.sha256,
            "ext": self.ext,
            "kind": self.kind,
            "w": self.w,
            "h": self.h,
            "bytes": self.bytes,
            "warnings": list(self.warnings),
            "degraded": self.degraded,
            "cached": self.cached,
        }


# ───────────────────────── 路径 ─────────────────────────


def media_rel_path(sha256: str, ext: str) -> str:
    """``media/{sha[:2]}/{sha}.{ext}``（相对 dist 根）。"""
    return f"media/{sha256[:2]}/{sha256}.{ext}"


def media_url_path(sha256: str, ext: str) -> str:
    """相对 ``media_base``（即 manifest.media_base）的路径。"""
    return f"{sha256[:2]}/{sha256}.{ext}"


# ───────────────────────── 分类 ─────────────────────────


def classify_source(path: Path, hint: str | None, params: MediaParams) -> tuple[str, list[str]]:
    """判定 kind ∈ image|anim|video。返回 ``(kind, warnings)``。"""
    warns: list[str] = []
    ext = path.suffix.lower()
    if hint and hint in KINDS:
        return hint, warns
    if hint:
        warns.append(f"kind 提示 {hint!r} 非法，改用自动判定")

    if ext in VIDEO_EXTS:
        duration = mp4_duration_seconds(path) if ext in (".mp4", ".m4v", ".mov") else None
        if duration is None:
            warns.append("无法解析视频时长，按真视频（video）处理")
            return "video", warns
        if duration <= params.video_max_seconds:
            return "anim", warns
        return "video", warns

    if ext in ANIM_EXTS or ext in STATIC_EXTS:
        try:
            with Image.open(path) as im:
                if int(getattr(im, "n_frames", 1)) > 1:
                    return "anim", warns
                return "image", warns
        except Exception as exc:  # noqa: BLE001 - 交给后面的回退链
            warns.append(f"Pillow 无法识别（{exc}），按扩展名判定")
        if ext == ".gif":
            return "anim", warns
        return "image", warns

    # 未知扩展名：试着当图片，实在不行当视频
    try:
        with Image.open(path) as im:
            return ("anim" if int(getattr(im, "n_frames", 1)) > 1 else "image"), warns
    except Exception:  # noqa: BLE001
        warns.append(f"未知扩展名 {ext or '<无>'}，按真视频处理")
        return "video", warns


def mp4_duration_seconds(path: Path) -> float | None:
    """从 mp4/mov 的 ``moov/mvhd`` box 读时长（秒），读不到返回 None。"""
    try:
        size = path.stat().st_size
        with open(path, "rb") as fh:
            for box_type, start, box_size in _iter_boxes(fh, 0, size):
                if box_type == b"moov":
                    payload_start = fh.tell()
                    fh.seek(payload_start)
                    for sub_type, sub_start, sub_size in _iter_boxes(fh, payload_start, start + box_size):
                        if sub_type == b"mvhd":
                            fh.seek(sub_start)  # mvhd 载荷起点（version/flags）
                            return _parse_mvhd(fh)
    except (OSError, struct.error):
        return None
    return None


def _parse_mvhd(fh) -> float | None:
    """解析 mvhd：version/flags → creation/modification → timescale → duration。"""
    header = fh.read(4)
    if len(header) < 4:
        return None
    version = header[0]
    if version == 1:
        fh.read(16)
        timescale, duration = struct.unpack(">IQ", fh.read(12))
    else:
        fh.read(8)
        timescale, duration = struct.unpack(">II", fh.read(8))
    if not timescale or not duration:
        return None
    return duration / timescale


def _iter_boxes(fh, start: int, end: int):
    """遍历 ISO BMFF box（支持 64 位 size 与 size=0）。"""
    pos = start
    while pos < end:
        fh.seek(pos)
        header = fh.read(8)
        if len(header) < 8:
            return
        box_size, box_type = struct.unpack(">I4s", header)
        header_len = 8
        if box_size == 1:
            extra = fh.read(8)
            if len(extra) < 8:
                return
            box_size = struct.unpack(">Q", extra)[0]
            header_len = 16
        elif box_size == 0:
            box_size = end - pos
        if box_size < header_len:
            return
        yield box_type, pos + header_len, box_size
        pos += box_size


# ───────────────────────── 编码 ─────────────────────────


def _fit_within(im: Image.Image, max_edge: int) -> tuple[Image.Image, bool]:
    """长边超过 max_edge 时等比缩小。"""
    width, height = im.size
    longest = max(width, height)
    if longest <= max_edge:
        return im, False
    scale = max_edge / float(longest)
    new_size = (max(1, round(width * scale)), max(1, round(height * scale)))
    return im.resize(new_size, Image.LANCZOS), True


def _has_real_alpha(im: Image.Image) -> bool:
    """是否真的有半透明/透明像素（而不是带 alpha 通道但全不透明）。"""
    if im.mode in ("RGBA", "LA", "PA") or (im.mode == "P" and "transparency" in im.info):
        alpha = im.convert("RGBA").getchannel("A")
        return alpha.getextrema()[0] < 255
    return False


def _open_static(path: Path) -> Image.Image:
    """打开静态图：应用 EXIF 方向、清除 EXIF/ICC。"""
    with Image.open(path) as raw:
        im = ImageOps.exif_transpose(raw) or raw
        im.load()
        im = im.copy()
    im.info.pop("exif", None)
    im.info.pop("icc_profile", None)
    return im


def encode_static(path: Path, params: MediaParams) -> tuple[bytes, int, int, list[str]]:
    """静态图 → WebP 字节。"""
    warns: list[str] = []
    im = _open_static(path)
    im, resized = _fit_within(im, params.image_max_edge)
    if resized:
        warns.append(f"长边超过 {params.image_max_edge}，已等比缩放")
    has_alpha = _has_real_alpha(im)
    if has_alpha:
        im = im.convert("RGBA")
    elif im.mode not in ("RGB", "L"):
        im = im.convert("RGB")
    buf = io.BytesIO()
    save_kwargs: dict[str, Any] = {"method": params.webp_method}
    if has_alpha:
        save_kwargs.update({"lossless": True, "quality": 100})
    else:
        save_kwargs.update({"lossless": False, "quality": params.image_quality})
    im.save(buf, "WEBP", **save_kwargs)
    return buf.getvalue(), im.size[0], im.size[1], warns


def read_animation(path: Path) -> tuple[list[Image.Image], list[int], int, list[str]]:
    """读动图所有帧、每帧时长（ms）、循环次数。"""
    warns: list[str] = []
    frames: list[Image.Image] = []
    durations: list[int] = []
    with Image.open(path) as im:
        count = int(getattr(im, "n_frames", 1))
        if count <= 1:
            raise MediaError([f"{path.name}: 不是动图（只有 1 帧）"])
        loop = int(im.info.get("loop", 0) or 0)
        for index in range(count):
            im.seek(index)
            frame = im.convert("RGBA").copy()
            frame.info.pop("exif", None)
            frames.append(frame)
            durations.append(max(10, int(im.info.get("duration", 100) or 100)))
    return frames, durations, loop, warns


def save_animation(
    frames: Sequence[Image.Image],
    durations: Sequence[int],
    loop: int,
    params: MediaParams,
    quality: int,
) -> bytes:
    """把帧序列编码成动态 WebP。"""
    buf = io.BytesIO()
    frames[0].save(
        buf,
        "WEBP",
        save_all=True,
        append_images=list(frames[1:]),
        duration=list(durations),
        loop=loop,
        lossless=False,
        quality=quality,
        method=params.webp_method,
    )
    return buf.getvalue()


def encode_animation(path: Path, params: MediaParams) -> tuple[bytes, int, int, list[str], bool]:
    """动图 → 动态 WebP，超预算时降质量/降帧。返回 (bytes,w,h,warns,degraded)。"""
    warns: list[str] = []
    degraded = False
    frames, durations, loop, frame_warns = read_animation(path)
    warns.extend(frame_warns)

    resized_frames: list[Image.Image] = []
    resize_warned = False
    for frame in frames:
        frame, resized = _fit_within(frame, params.anim_max_edge)
        if resized and not resize_warned:
            warns.append(f"动图长边超过 {params.anim_max_edge}，已等比缩放")
            resize_warned = True
        resized_frames.append(frame)
    frames = resized_frames
    width, height = frames[0].size

    best: bytes | None = None
    for quality in (params.image_quality, 70, 60, 50, 40, 30):
        data = save_animation(frames, durations, loop, params, quality)
        if best is None or len(data) < len(best):
            best = data
        if len(data) <= params.anim_max_bytes:
            if quality != params.image_quality:
                warns.append(f"为满足 {params.anim_max_bytes} 字节预算，动图质量降到 q={quality}")
                degraded = True
            return data, width, height, warns, degraded

    # 仍超限：降帧（合并被丢弃帧的时长，保持总时长不变）
    for step in (2, 3, 4, 6):
        if len(frames) // step < 2:
            break
        sub_frames: list[Image.Image] = []
        sub_durations: list[int] = []
        for index in range(0, len(frames), step):
            chunk = durations[index:index + step]
            sub_frames.append(frames[index])
            sub_durations.append(max(10, sum(chunk)))
        for quality in (60, 45, 30):
            data = save_animation(sub_frames, sub_durations, loop, params, quality)
            if best is None or len(data) < len(best):
                best = data
            if len(data) <= params.anim_max_bytes:
                warns.append(
                    f"动图超预算，已降帧 {len(frames)}→{len(sub_frames)} 帧并降质量到 q={quality}"
                )
                return data, width, height, warns, True

    warns.append(
        f"动图仍超过 {params.anim_max_bytes} 字节预算（最终 {len(best or b'')} 字节），请人工压缩"
    )
    assert best is not None
    return best, width, height, warns, True


def encode_video(path: Path, params: MediaParams) -> tuple[bytes, int, int, list[str]]:
    """真视频 → mp4（H.264 baseline + faststart）；无 ffmpeg 时原样保留 mp4。"""
    warns: list[str] = []
    ffmpeg = shutil.which("ffmpeg")
    width, height = _probe_size(path)
    if ffmpeg:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "out.mp4"
            cmd = [
                ffmpeg, "-y", "-v", "error", "-i", str(path),
                "-c:v", "libx264", "-profile:v", "baseline", "-level", "3.0",
                "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-an",
                str(out),
            ]
            proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
            if proc.returncode == 0 and out.exists():
                return out.read_bytes(), width, height, warns
            warns.append(f"ffmpeg 转码失败（{proc.stderr.strip()[:200]}），原样保留源文件")
    else:
        warns.append("未找到 ffmpeg，真视频原样保留（未做 H.264 baseline/faststart 处理）")
    if path.suffix.lower() not in (".mp4", ".m4v"):
        raise MediaError([f"{path.name}: 需要 ffmpeg 才能把 {path.suffix} 转成 mp4，但系统未安装 ffmpeg"])
    return path.read_bytes(), width, height, warns


def _probe_size(path: Path) -> tuple[int, int]:
    """尽力取宽高：先用 ffprobe，失败退回 Pillow，再失败用 tkhd 解析，最后 0。"""
    ffprobe = shutil.which("ffprobe")
    if ffprobe:
        cmd = [
            ffprobe, "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=width,height", "-of", "csv=p=0", str(path),
        ]
        proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
        if proc.returncode == 0 and proc.stdout.strip():
            parts = proc.stdout.strip().split(",")
            if len(parts) >= 2 and parts[0].isdigit() and parts[1].isdigit():
                return int(parts[0]), int(parts[1])
    try:
        with Image.open(path) as im:
            return im.size[0], im.size[1]
    except Exception:  # noqa: BLE001
        pass
    size = _mp4_tkhd_size(path)
    return size or (0, 0)


def _mp4_tkhd_size(path: Path) -> tuple[int, int] | None:
    """从 moov/trak/tkhd 读显示宽高（16.16 定点，取高 16 位）。"""
    try:
        total = path.stat().st_size
        with open(path, "rb") as fh:
            for box_type, start, box_size in _iter_boxes(fh, 0, total):
                if box_type != b"moov":
                    continue
                for sub_type, sub_start, sub_size in _iter_boxes(fh, start, start + box_size):
                    if sub_type != b"trak":
                        continue
                    for leaf_type, leaf_start, leaf_size in _iter_boxes(fh, sub_start, sub_start + sub_size):
                        if leaf_type != b"tkhd":
                            continue
                        fh.seek(leaf_start)
                        body = fh.read(leaf_size)
                        if len(body) < 8:
                            continue
                        offset = 4 + (8 if body[0] == 1 else 4) + 8
                        if len(body) < offset + 8:
                            continue
                        width_fixed = struct.unpack(">I", body[-8:-4])[0]
                        height_fixed = struct.unpack(">I", body[-4:])[0]
                        return width_fixed >> 16, height_fixed >> 16
    except (OSError, struct.error):
        return None
    return None


def _gif2webp(path: Path, params: MediaParams, out_dir: Path) -> tuple[bytes, int, int, list[str]] | None:
    """回退链：gif2webp / ffmpeg 外部工具转动态 WebP。"""
    gif2webp = shutil.which("gif2webp")
    if gif2webp:
        out = out_dir / "out.webp"
        proc = subprocess.run(
            [gif2webp, "-q", str(params.image_quality), "-m", "4", str(path), "-o", str(out)],
            capture_output=True, text=True, check=False,
        )
        if proc.returncode == 0 and out.exists():
            data = out.read_bytes()
            try:
                with Image.open(io.BytesIO(data)) as im:
                    return data, im.size[0], im.size[1], ["Pillow 无法读取动图，已回退 gif2webp"]
            except Exception:  # noqa: BLE001
                return data, 0, 0, ["Pillow 无法读取动图，已回退 gif2webp（宽高未知）"]
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg:
        out = out_dir / "out.webp"
        cmd = [
            ffmpeg, "-y", "-v", "error", "-i", str(path),
            "-vcodec", "libwebp", "-lossless", "0", "-q:v", "70", "-loop", "0",
            "-preset", "default", "-an", "-vsync", "0", str(out),
        ]
        proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
        if proc.returncode == 0 and out.exists():
            data = out.read_bytes()
            size = _probe_size(out)
            return data, size[0], size[1], ["Pillow 无法读取动图，已回退 ffmpeg"]
    return None


# ───────────────────────── 单文件处理 ─────────────────────────


def process_file(
    src: Path,
    params: MediaParams,
    dist_dir: Path,
    tmp_dir: Path | None = None,
) -> MediaResult:
    """处理单个源文件并落到 ``dist/media/…``（内容寻址，已存在则跳过）。"""
    if not src.exists():
        raise MediaError([f"媒体源文件不存在: {src}"])
    kind, warns = classify_source(src, None, params)
    return process_file_as(src, params, dist_dir, kind, warns, tmp_dir)


def process_file_as(
    src: Path,
    params: MediaParams,
    dist_dir: Path,
    kind: str,
    warns: list[str],
    tmp_dir: Path | None = None,
) -> MediaResult:
    """按指定 kind 处理（kind_hint 已在分类阶段解析）。"""
    warns = list(warns)
    degraded = False
    if kind == "image":
        try:
            data, width, height, extra = encode_static(src, params)
        except MediaError:
            raise
        except Exception as exc:  # noqa: BLE001
            raise MediaError([f"{src.name}: 静态图转码失败: {exc}"]) from exc
        ext = "webp"
    elif kind == "anim":
        try:
            data, width, height, extra, degraded = encode_animation(src, params)
        except MediaError:
            fallback_dir = tmp_dir or Path(tempfile.mkdtemp(prefix="jiakao-media-"))
            ensure_dir(fallback_dir)
            fallback = _gif2webp(src, params, fallback_dir)
            if fallback is None:
                raise
            data, width, height, extra = fallback
        except Exception as exc:  # noqa: BLE001
            raise MediaError([f"{src.name}: 动图转码失败: {exc}"]) from exc
        ext = "webp"
    else:
        data, width, height, extra = encode_video(src, params)
        ext = "mp4"
    warns.extend(extra)

    digest = sha256_bytes(data)
    target = dist_dir / media_rel_path(digest, ext)
    if not target.exists():
        ensure_dir(target.parent)
        handle, tmp_name = tempfile.mkstemp(dir=str(target.parent), prefix=".tmp-", suffix=f".{ext}")
        try:
            with os.fdopen(handle, "wb") as fh:
                fh.write(data)
            os.replace(tmp_name, target)
        except BaseException:
            try:
                os.unlink(tmp_name)
            except OSError:
                pass
            raise
    return MediaResult(
        src=str(src),
        sha256=digest,
        ext=ext,
        kind=kind,
        w=int(width),
        h=int(height),
        bytes=len(data),
        warnings=warns,
        degraded=degraded,
    )


# ───────────────────────── 缓存 ─────────────────────────


class MediaCache:
    """``(源路径, mtime_ns, size, 参数) → 结果`` 的缓存。"""

    SCHEMA = 1

    def __init__(self, path: Path) -> None:
        self.path = Path(path)
        raw = load_json(self.path, default=None) or {}
        self.entries: dict[str, dict] = dict(raw.get("entries") or {})
        self.dirty = False

    @staticmethod
    def make_key(src: Path, params: MediaParams) -> str:
        """缓存键：绝对路径 + 参数签名。"""
        payload = json.dumps(
            {"path": str(Path(src).resolve()), "params": params.signature()},
            ensure_ascii=False, sort_keys=True,
        )
        return sha256_bytes(payload.encode("utf-8"))

    def lookup(self, src: Path, params: MediaParams) -> MediaResult | None:
        """命中且源文件未被改动时返回结果（cached=True）。"""
        entry = self.entries.get(self.make_key(src, params))
        if not entry:
            return None
        try:
            stat = Path(src).stat()
        except OSError:
            return None
        if entry.get("mtime_ns") != stat.st_mtime_ns or entry.get("size") != stat.st_size:
            return None
        return MediaResult(
            src=str(src),
            sha256=entry["sha256"],
            ext=entry["ext"],
            kind=entry["kind"],
            w=entry["w"],
            h=entry["h"],
            bytes=entry["bytes"],
            warnings=list(entry.get("warnings") or []),
            degraded=bool(entry.get("degraded")),
            cached=True,
        )

    def store(self, src: Path, params: MediaParams, result: MediaResult) -> None:
        """写入缓存条目。"""
        try:
            stat = Path(src).stat()
        except OSError:
            return
        self.entries[self.make_key(src, params)] = {
            "mtime_ns": stat.st_mtime_ns,
            "size": stat.st_size,
            "sha256": result.sha256,
            "ext": result.ext,
            "kind": result.kind,
            "w": result.w,
            "h": result.h,
            "bytes": result.bytes,
            "warnings": list(result.warnings),
            "degraded": result.degraded,
        }
        self.dirty = True

    def save(self) -> None:
        """落盘（键排序，便于人工查看）。"""
        if not self.dirty:
            return
        payload = {
            "schema": self.SCHEMA,
            "entries": {k: self.entries[k] for k in sorted(self.entries)},
        }
        atomic_write_bytes(self.path, (json.dumps(payload, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
        self.dirty = False


# ───────────────────────── 多进程驱动 ─────────────────────────


def _worker(payload: dict) -> dict:
    """子进程入口（必须模块级、可 pickle）。"""
    params = MediaParams(**payload["params"])
    try:
        kind, warns = classify_source(Path(payload["src"]), payload.get("kind_hint"), params)
        result = process_file_as(
            Path(payload["src"]), params, Path(payload["dist_dir"]), kind, warns,
        )
        return {"ok": True, **result.to_dict()}
    except MediaError as exc:
        return {"ok": False, "src": payload["src"], "error": "; ".join(exc.errors)}
    except Exception as exc:  # noqa: BLE001 - 子进程不能抛，统一回传
        return {"ok": False, "src": payload["src"], "error": f"{type(exc).__name__}: {exc}"}


def process_many(
    items: Sequence[tuple[str, str | None]],
    dist_dir: Path,
    params: MediaParams,
    cache: MediaCache | None = None,
    jobs: int = 1,
    kind_hints: dict[str, str] | None = None,
    progress: Callable[[int, int], None] | None = None,
) -> tuple[dict[str, MediaResult], list[str]]:
    """批量处理媒体。

    ``items`` 为 ``(源路径, kind_hint)`` 序列（同内容不同提示时以第一个为准）。
    返回 ``(src → 结果, 错误列表)``。
    """
    hints = dict(kind_hints or {})
    for src, hint in items:
        if hint and src not in hints:
            hints[src] = hint

    dist_dir = Path(dist_dir)
    unique_srcs: list[str] = []
    seen: set[str] = set()
    for src, _ in items:
        key = _src_key(src)
        if key not in seen:
            seen.add(key)
            unique_srcs.append(src)

    results: dict[str, MediaResult] = {}
    pending: list[dict] = []
    for src in unique_srcs:
        path = Path(src)
        if cache is not None:
            cached = cache.lookup(path, params)
            if cached is not None and (dist_dir / media_rel_path(cached.sha256, cached.ext)).exists():
                results[_src_key(src)] = cached
                continue
        pending.append({
            "src": str(path),
            "kind_hint": hints.get(src),
            "params": params.signature(),
            "dist_dir": str(dist_dir),
        })

    done = len(results)
    total = len(unique_srcs)
    if progress:
        progress(done, total)

    errors: list[str] = []
    if pending:
        raw_results = _run_pending(pending, jobs, progress, done, total)
        for payload, raw in zip(pending, raw_results):
            if raw.get("ok"):
                raw.pop("ok", None)
                result = MediaResult(
                    src=raw["src"], sha256=raw["sha256"], ext=raw["ext"], kind=raw["kind"],
                    w=raw["w"], h=raw["h"], bytes=raw["bytes"],
                    warnings=list(raw.get("warnings") or []),
                    degraded=bool(raw.get("degraded")), cached=False,
                )
                results[_src_key(payload["src"])] = result
                if cache is not None:
                    cache.store(Path(payload["src"]), params, result)
            else:
                errors.append(raw.get("error") or f"{payload['src']}: 未知错误")

    if cache is not None:
        cache.save()
    return results, errors


def _run_pending(
    pending: list[dict],
    jobs: int,
    progress: Callable[[int, int], None] | None,
    done: int,
    total: int,
) -> list[dict]:
    """执行挂起任务（jobs > 1 时用进程池）。"""
    if jobs <= 1 or len(pending) == 1:
        out: list[dict] = []
        for index, payload in enumerate(pending, 1):
            out.append(_worker(payload))
            if progress:
                progress(done + index, total)
        return out

    from concurrent.futures import ProcessPoolExecutor

    out = []
    with ProcessPoolExecutor(max_workers=jobs) as pool:
        for index, raw in enumerate(pool.map(_worker, pending), 1):
            out.append(raw)
            if progress:
                progress(done + index, total)
    return out


def _src_key(src: str) -> str:
    """媒体源的去重键（大小写不敏感，Windows 上很重要）。"""
    return os.path.normcase(os.path.abspath(src))


def collect_media_sources(questions: Iterable[dict], work_dir: Path) -> list[tuple[str, str | None]]:
    """从题目列表收集 ``(源路径, kind_hint)``（去重、保持出现顺序，路径转绝对）。"""
    items: list[tuple[str, str | None]] = []
    seen: set[str] = set()
    for question in questions:
        for media in question.get("media") or []:
            raw = media.get("src")
            if not raw:
                continue
            path = Path(raw)
            if not path.is_absolute():
                path = Path(work_dir) / raw
            key = _src_key(str(path))
            if key in seen:
                continue
            seen.add(key)
            items.append((str(path), media.get("kind_hint")))
    return items


def probe_dimensions(path: Path) -> tuple[int, int] | None:
    """取媒体宽高（Pillow → tkhd），取不到返回 None。"""
    try:
        with Image.open(path) as im:
            return im.size[0], im.size[1]
    except Exception:  # noqa: BLE001
        pass
    if Path(path).suffix.lower() in (".mp4", ".m4v", ".mov"):
        return _mp4_tkhd_size(Path(path))
    return None


# ───────────────────────── 题目媒体解析 ─────────────────────────


def resolve_question_media(
    question: dict,
    results: dict[str, MediaResult],
    work_dir: Path,
) -> tuple[dict, list[str]]:
    """把题目里的 ``{"src": …}`` 换成合同的 sha256 引用。"""
    warns: list[str] = []
    resolved: list[dict] = []
    for item in question.get("media") or []:
        src = item.get("src")
        if not src:
            continue
        path = Path(src)
        if not path.is_absolute():
            path = Path(work_dir) / src
        key = _src_key(str(path))
        result = results.get(key) or results.get(_src_key(src))
        if result is None:
            warns.append(f"{question['id']}: 媒体 {src} 没有处理结果")
            continue
        resolved.append(result.ref())
    question = dict(question)
    question["media"] = resolved
    return question, warns
