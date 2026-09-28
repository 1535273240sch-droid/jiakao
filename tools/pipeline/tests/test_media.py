"""媒体：静图/动图/视频分类与转码、长边缩放、透明无损、去重、缓存、降级。"""

from __future__ import annotations

import io
from pathlib import Path

import pytest
from PIL import Image

from jiakao_pipeline.media import (
    MediaCache,
    MediaError,
    MediaParams,
    classify_source,
    encode_animation,
    encode_static,
    media_rel_path,
    media_url_path,
    mp4_duration_seconds,
    process_file,
    process_many,
    probe_dimensions,
)

from conftest import make_fake_mp4, make_gif, make_png, make_transparent_png


def test_media_paths_follow_contract() -> None:
    """内容寻址路径：media/{sha[:2]}/{sha}.{ext}。"""
    sha = "ab" + "0" * 62
    assert media_rel_path(sha, "webp") == f"media/ab/{sha}.webp"
    assert media_url_path(sha, "mp4") == f"ab/{sha}.mp4"


def test_static_png_to_webp(tmp_path: Path) -> None:
    """静图 → WebP，落盘在内容寻址路径，记录 w/h/bytes。"""
    source = make_png(tmp_path / "in" / "a.png", (10, 20, 30))
    result = process_file(source, MediaParams(), tmp_path / "dist")
    assert result.kind == "image" and result.ext == "webp"
    assert (result.w, result.h) == (64, 64)
    target = tmp_path / "dist" / media_rel_path(result.sha256, result.ext)
    assert target.exists()
    assert target.stat().st_size == result.bytes
    with Image.open(target) as image:
        assert image.format == "WEBP"


def test_long_edge_is_scaled_to_limit(tmp_path: Path) -> None:
    """长边超过上限时等比缩放。"""
    source = make_png(tmp_path / "in" / "big.png", (10, 20, 30), size=(2000, 1000))
    result = process_file(source, MediaParams(image_max_edge=1080), tmp_path / "dist")
    assert max(result.w, result.h) == 1080
    assert (result.w, result.h) == (1080, 540)
    assert any("缩放" in w for w in result.warnings)


def test_transparent_image_is_lossless(tmp_path: Path) -> None:
    """含真实透明像素的图走无损，像素必须逐点一致。"""
    source = make_transparent_png(tmp_path / "in" / "t.png")
    data, width, height, warnings = encode_static(source, MediaParams())
    assert (width, height) == (48, 48)
    with Image.open(io.BytesIO(data)) as decoded:
        assert decoded.convert("RGBA").tobytes() == Image.open(source).convert("RGBA").tobytes()
    assert warnings == []


def test_identical_content_is_deduplicated(tmp_path: Path, ) -> None:
    """两张内容相同的图（不同文件名）只落一个产物、共用同一个 sha256。"""
    first = make_png(tmp_path / "in" / "a.png", (7, 7, 7))
    second = make_png(tmp_path / "in" / "b.png", (7, 7, 7))
    results, errors = process_many(
        [(str(first), None), (str(second), None)], tmp_path / "dist", MediaParams(), jobs=1
    )
    assert not errors
    shas = {r.sha256 for r in results.values()}
    assert len(shas) == 1
    files = list((tmp_path / "dist" / "media").rglob("*.webp"))
    assert len(files) == 1


def test_gif_to_animated_webp_keeps_frames(tmp_path: Path) -> None:
    """GIF → 动态 WebP，帧数与时长保留，kind=anim。"""
    source = make_gif(tmp_path / "in" / "a.gif", frames=5)
    result = process_file(source, MediaParams(), tmp_path / "dist")
    assert result.kind == "anim" and result.ext == "webp"
    with Image.open(tmp_path / "dist" / media_rel_path(result.sha256, "webp")) as image:
        assert image.format == "WEBP"
        assert image.n_frames == 5
        image.seek(1)
        image.load()
        assert image.info.get("duration") == 80
        assert image.info.get("loop") == 0


def test_animation_budget_triggers_degrade(tmp_path: Path) -> None:
    """动图超预算时降质量/降帧并标黄（degraded=True + 告警）。"""
    source = make_gif(tmp_path / "in" / "a.gif", frames=12, size=(160, 120))
    tiny_budget = MediaParams(anim_max_bytes=1200)
    data, width, height, warnings, degraded = encode_animation(source, tiny_budget)
    assert degraded is True
    assert warnings
    assert len(data) < 24_000  # 明显小于未压缩帧数据


def test_cache_hit_on_second_run(tmp_path: Path) -> None:
    """二次构建命中缓存：不重新编码、结果完全一致。"""
    source = make_png(tmp_path / "in" / "a.png", (11, 22, 33))
    cache = MediaCache(tmp_path / "work" / ".media_cache.json")
    first, _ = process_many([(str(source), None)], tmp_path / "dist", MediaParams(), cache=cache, jobs=1)
    second, _ = process_many([(str(source), None)], tmp_path / "dist", MediaParams(), cache=cache, jobs=1)
    first_result = next(iter(first.values()))
    second_result = next(iter(second.values()))
    assert first_result.cached is False
    assert second_result.cached is True
    assert second_result.sha256 == first_result.sha256
    assert second_result.bytes == first_result.bytes


def test_cache_invalidated_when_source_changes(tmp_path: Path) -> None:
    """源文件变了（内容不同）缓存必须失效。"""
    source = make_png(tmp_path / "in" / "a.png", (11, 22, 33))
    cache = MediaCache(tmp_path / "work" / ".media_cache.json")
    first, _ = process_many([(str(source), None)], tmp_path / "dist", MediaParams(), cache=cache, jobs=1)
    make_png(source, (99, 22, 33), shapes=2)
    second, _ = process_many([(str(source), None)], tmp_path / "dist", MediaParams(), cache=cache, jobs=1)
    assert next(iter(second.values())).sha256 != next(iter(first.values())).sha256


def test_missing_source_raises(tmp_path: Path) -> None:
    """源文件不存在要报错。"""
    with pytest.raises(MediaError, match="不存在"):
        process_file(tmp_path / "nope.png", MediaParams(), tmp_path / "dist")


def test_mp4_duration_parser(tmp_path: Path) -> None:
    """从 moov/mvhd 读时长；坏文件返回 None。"""
    short = make_fake_mp4(tmp_path / "short.mp4", duration_seconds=3.0)
    long = make_fake_mp4(tmp_path / "long.mp4", duration_seconds=42.5)
    broken = tmp_path / "broken.mp4"
    broken.write_bytes(b"not a video")
    assert mp4_duration_seconds(short) == pytest.approx(3.0)
    assert mp4_duration_seconds(long) == pytest.approx(42.5)
    assert mp4_duration_seconds(broken) is None


def test_classify_short_video_as_anim_long_as_video(tmp_path: Path) -> None:
    """> 5s 判真视频，≤ 5s 判动图，未知扩展名兜底为视频。"""
    short = make_fake_mp4(tmp_path / "s.mp4", duration_seconds=2.0)
    long = make_fake_mp4(tmp_path / "l.mp4", duration_seconds=30.0)
    assert classify_source(short, None, MediaParams())[0] == "anim"
    assert classify_source(long, None, MediaParams())[0] == "video"
    assert classify_source(tmp_path / "x.bin", None, MediaParams())[0] == "video"
    assert classify_source(make_gif(tmp_path / "a.gif"), None, MediaParams())[0] == "anim"
    assert classify_source(make_png(tmp_path / "a.png", (1, 2, 3)), None, MediaParams())[0] == "image"
    assert classify_source(short, "image", MediaParams())[0] == "image"
    assert classify_source(short, "乱写", MediaParams())[1]


def test_video_without_ffmpeg_is_copied_with_warning(tmp_path: Path, monkeypatch) -> None:
    """没有 ffmpeg 时真视频原样保留 mp4（kind=video）并告警。"""
    monkeypatch.setattr("jiakao_pipeline.media.shutil.which", lambda name: None)
    source = make_fake_mp4(tmp_path / "in" / "v.mp4", duration_seconds=30.0)
    result = process_file(source, MediaParams(), tmp_path / "dist")
    assert result.kind == "video" and result.ext == "mp4"
    assert result.sha256 == __import__("hashlib").sha256(source.read_bytes()).hexdigest()
    assert any("ffmpeg" in w for w in result.warnings)


def test_non_mp4_video_without_ffmpeg_fails(tmp_path: Path, monkeypatch) -> None:
    """没有 ffmpeg 又需要转封装（webm→mp4）时必须报错而不是产出坏文件。"""
    monkeypatch.setattr("jiakao_pipeline.media.shutil.which", lambda name: None)
    source = tmp_path / "in" / "v.webm"
    source.parent.mkdir(parents=True, exist_ok=True)
    source.write_bytes(b"\x1a\x45\xdf\xa3fake")
    with pytest.raises(MediaError, match="ffmpeg"):
        process_file(source, MediaParams(), tmp_path / "dist")


def test_probe_dimensions(tmp_path: Path) -> None:
    """宽高探测：图片走 Pillow，mp4 走 tkhd（这里没有 tkhd → None）。"""
    image = make_png(tmp_path / "a.png", (1, 2, 3), size=(30, 20))
    assert probe_dimensions(image) == (30, 20)
    assert probe_dimensions(tmp_path / "broken.bin") is None


def test_exif_is_stripped(tmp_path: Path) -> None:
    """EXIF 必须被清除（合同要求）。"""
    source = tmp_path / "in" / "exif.jpg"
    source.parent.mkdir(parents=True, exist_ok=True)
    image = Image.new("RGB", (40, 40), (200, 100, 50))
    exif = image.getexif()
    exif[271] = "TestCamera"
    image.save(source, "JPEG", exif=exif)
    assert Image.open(source).info.get("exif")
    data, _, _, _ = encode_static(source, MediaParams())
    with Image.open(io.BytesIO(data)) as decoded:
        assert not decoded.info.get("exif")
