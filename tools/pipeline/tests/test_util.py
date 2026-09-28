"""通用工具：确定性压缩、文本清洗、键归一化。"""

from __future__ import annotations

import gzip
import io
import json
import zipfile

from jiakao_pipeline.util import (
    atomic_write_text,
    clean_text,
    dumps_compact,
    dump_line,
    gzip_bytes,
    human_bytes,
    normalize_key_token,
    resolve_released_at,
    sha256_bytes,
    strip_key_noise,
    zip_bytes,
)


def test_gzip_is_byte_reproducible() -> None:
    """同一输入两次 gzip 必须完全一致，且可从任意 gzip 工具解开。"""
    payload = "第一行\n第二行\n".encode("utf-8")
    first = gzip_bytes(payload)
    second = gzip_bytes(payload)
    assert first == second
    assert gzip.decompress(first) == payload
    # 头部 mtime 字段必须为 0（保证跨时间可复现）
    assert first[4:8] == b"\x00\x00\x00\x00"
    # OS 字节固定，不受运行平台影响
    assert first[9] == 255


def test_zip_is_byte_reproducible_and_ordered() -> None:
    """zip 条目顺序与时间戳固定 → 字节级可复现。"""
    entries = [("b.txt", b"B"), ("a.txt", b"A")]
    first = zip_bytes(entries)
    second = zip_bytes(entries)
    assert first == second
    with zipfile.ZipFile(io.BytesIO(first)) as archive:
        assert archive.namelist() == ["b.txt", "a.txt"]
        assert archive.read("a.txt") == b"A"
        for info in archive.infolist():
            assert info.date_time == (1980, 1, 1, 0, 0, 0)


def test_dump_line_is_compact_and_newline_terminated() -> None:
    """jsonl 行是紧凑 JSON + 换行，中文不转义。"""
    line = dump_line({"b": 1, "a": "中文"})
    assert line == '{"b":1,"a":"中文"}\n'
    assert dumps_compact({"a": [1, 2]}) == '{"a":[1,2]}'


def test_clean_text_normalizes_whitespace_and_unicode() -> None:
    """全角空格/换行/连续空白都折叠，前后空白去掉。"""
    assert clean_text("  驾  驶\t人\n员  ") == "驾 驶 人 员"
    assert clean_text("Ａ\u3000Ｂ") == "Ａ Ｂ"
    assert clean_text(None) == ""
    assert clean_text("第一行\r\n第二行") == "第一行 第二行"


def test_strip_key_noise_and_normalize_key_token() -> None:
    """选项键统一：括号/标点/全角/数字都能归一成 A-D。"""
    assert strip_key_noise("（A）") == "A"
    assert strip_key_noise("A.") == "A"
    assert normalize_key_token("A") == "A"
    assert normalize_key_token("(b)") == "B"
    assert normalize_key_token("１") == "A"
    assert normalize_key_token("4") == "D"
    assert normalize_key_token("选C") == "C"
    assert normalize_key_token("") == ""
    assert normalize_key_token("甲乙") == ""


def test_resolve_released_at_honours_source_date_epoch(monkeypatch) -> None:
    """SOURCE_DATE_EPOCH 让构建时间可固定（可复现构建必需）。"""
    monkeypatch.setenv("SOURCE_DATE_EPOCH", "1767225600")
    assert resolve_released_at() == "2026-01-01T00:00:00Z"
    assert resolve_released_at("2025-05-05T05:05:05Z") == "2025-05-05T05:05:05Z"
    monkeypatch.delenv("SOURCE_DATE_EPOCH")
    assert resolve_released_at().endswith("Z")


def test_atomic_write_replaces_content(tmp_path) -> None:
    """原子写：目标文件先不存在也可以，重复写覆盖。"""
    target = tmp_path / "nested" / "file.txt"
    atomic_write_text(target, "第一版")
    assert target.read_text(encoding="utf-8") == "第一版"
    atomic_write_text(target, "第二版")
    assert target.read_text(encoding="utf-8") == "第二版"
    assert not list(target.parent.glob(".tmp-*"))


def test_sha256_and_human_bytes() -> None:
    """sha256 是小写十六进制；体积格式化可读。"""
    assert sha256_bytes(b"") == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    assert human_bytes(512) == "512 B"
    assert human_bytes(2048).endswith("KB")


def test_json_roundtrip_with_chinese(tmp_path) -> None:
    """JSON 里中文不转义，且能原样读回。"""
    path = tmp_path / "x.json"
    atomic_write_text(path, json.dumps({"题干": "禁止停车"}, ensure_ascii=False))
    assert "禁止停车" in path.read_text(encoding="utf-8")
