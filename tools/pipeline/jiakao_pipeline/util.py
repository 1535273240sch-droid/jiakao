"""通用工具：确定性 JSON / JSONL、sha256、可复现 gzip 与 zip、文本清洗。

本模块刻意不依赖第三方库，保证流水线的最小可运行集只有标准库时也能读文件。
"""

from __future__ import annotations

import gzip
import hashlib
import io
import json
import os
import re
import tempfile
import unicodedata
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Iterator, Sequence

# ───────────────────────── 路径与原子写入 ─────────────────────────


def ensure_dir(path: os.PathLike[str] | str) -> Path:
    """创建目录（含父级）并返回 Path。"""
    p = Path(path)
    p.mkdir(parents=True, exist_ok=True)
    return p


def atomic_write_bytes(path: os.PathLike[str] | str, data: bytes) -> None:
    """同目录临时文件 + os.replace，避免半截文件。"""
    p = Path(path)
    ensure_dir(p.parent)
    fd, tmp = tempfile.mkstemp(dir=str(p.parent), prefix=".tmp-", suffix=p.suffix)
    try:
        with os.fdopen(fd, "wb") as fh:
            fh.write(data)
        os.replace(tmp, p)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def atomic_write_text(path: os.PathLike[str] | str, text: str) -> None:
    """UTF-8 + LF 原子写文本。"""
    atomic_write_bytes(path, text.encode("utf-8"))


# ───────────────────────── 哈希 ─────────────────────────


def sha256_bytes(data: bytes) -> str:
    """返回 bytes 的小写 64 位十六进制 sha256。"""
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: os.PathLike[str] | str) -> str:
    """流式计算文件 sha256。"""
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ───────────────────────── 确定性 JSON ─────────────────────────

# 题目行是逐字节比较的对象，一律使用紧凑分隔符且不打乱键顺序（键顺序 = 合同 §2 顺序）。
_JSONL_SEPARATORS = (",", ":")


def dumps_compact(obj: Any) -> str:
    """紧凑 JSON（无多余空格，保留插入顺序，中文不转义）。"""
    return json.dumps(obj, ensure_ascii=False, separators=_JSONL_SEPARATORS, sort_keys=False)


def dump_line(obj: Any) -> str:
    """一行 JSONL（含结尾 \\n）。"""
    return dumps_compact(obj) + "\n"


def dumps_pretty(obj: Any) -> str:
    """人类可读 JSON（manifest.json 用），末尾带换行。"""
    return json.dumps(obj, ensure_ascii=False, indent=2, sort_keys=False) + "\n"


def read_jsonl(path: os.PathLike[str] | str) -> Iterator[dict]:
    """逐行读 JSONL，报错时给出文件与行号。"""
    with open(path, "r", encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.strip()
            if not line:
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError as exc:  # pragma: no cover - 依赖坏数据
                raise ValueError(f"{path}:{lineno} 不是合法 JSON: {exc}") from exc


def load_json(path: os.PathLike[str] | str, default: Any = None) -> Any:
    """读 JSON 文件，不存在返回 default。"""
    p = Path(path)
    if not p.exists():
        return default
    with open(p, "r", encoding="utf-8") as fh:
        return json.load(fh)


def iter_jsonl_text(rows: Iterable[dict]) -> str:
    """把记录序列拼成 JSONL 文本。"""
    return "".join(dump_line(r) for r in rows)


def write_jsonl(path: os.PathLike[str] | str, rows: Iterable[dict]) -> Path:
    """原子写 JSONL，返回写入的路径。"""
    text = iter_jsonl_text(rows)
    atomic_write_bytes(path, text.encode("utf-8"))
    return Path(path)


# ───────────────────────── 可复现压缩 ─────────────────────────


def gzip_bytes(data: bytes, level: int = 9) -> bytes:
    """固定 mtime=0 的 gzip，保证同输入字节级一致。

    ``gzip.GzipFile`` 在 CPython 中把 OS 字节固定写成 255（unknown），
    因此不同平台/时间下输出一致（压缩级别与 zlib 版本相同即可）。
    """
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb", compresslevel=level, mtime=0) as gz:
        gz.write(data)
    return buf.getvalue()


def gunzip_bytes(data: bytes) -> bytes:
    """gzip 解压（离线包与增量校验用）。"""
    return gzip.decompress(data)


# zip 的固定时间戳：1980-01-01 是 ZIP 格式的最小合法时间。
ZIP_FIXED_DT = (1980, 1, 1, 0, 0, 0)


def zip_bytes(entries: Sequence[tuple[str, bytes]], compresslevel: int = 6) -> bytes:
    """生成确定性 zip：条目顺序 = 入参顺序，时间戳固定，权限位固定。"""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED, compresslevel=compresslevel) as zf:
        for name, data in entries:
            info = zipfile.ZipInfo(filename=name, date_time=ZIP_FIXED_DT)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            zf.writestr(info, data)
    return buf.getvalue()


# ───────────────────────── 时间 ─────────────────────────


def now_utc_iso() -> str:
    """当前 UTC 时间，形如 2026-09-28T12:00:00Z。"""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def iso_from_epoch(epoch: int | float) -> str:
    """由 Unix 秒生成同样的 ISO 字符串。"""
    return datetime.fromtimestamp(int(epoch), tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def resolve_released_at(explicit: str | None = None) -> str:
    """released_at 优先级：显式入参 > SOURCE_DATE_EPOCH > 当前时间。

    SOURCE_DATE_EPOCH 让"可复现构建"能在测试里固定时间戳。
    """
    if explicit:
        return explicit
    epoch = os.environ.get("SOURCE_DATE_EPOCH")
    if epoch and epoch.strip().isdigit():
        return iso_from_epoch(int(epoch))
    return now_utc_iso()


# ───────────────────────── 文本清洗 ─────────────────────────

_WS_RE = re.compile(r"\s+")
_PUNCT_KEYS = " \t\u3000.、,，:：;；)）(（]【】[]{}<>\"'“”‘’"


def clean_text(value: Any) -> str:
    """清洗单行文本：NFC 规范化、全角空格/换行统一、折叠连续空白、去首尾空白。"""
    if value is None:
        return ""
    text = unicodedata.normalize("NFC", str(value))
    text = text.replace("\u3000", " ").replace("\r\n", " ").replace("\r", " ").replace("\n", " ")
    text = text.replace("\u00a0", " ")
    return _WS_RE.sub(" ", text).strip()


def strip_key_noise(value: Any) -> str:
    """去掉选项 key 周围的括号与标点：'(A)'→'A'、'A.'→'A'、' 1 '→'1'。"""
    text = clean_text(value)
    return text.strip(_PUNCT_KEYS)


def normalize_key_token(value: Any) -> str:
    """把选项键归一为大写字母（A-D）；无法识别返回空串。"""
    token = strip_key_noise(value).upper()
    if not token:
        return ""
    # 全角字母 → 半角
    token = unicodedata.normalize("NFKC", token)
    if len(token) == 1 and "A" <= token <= "Z":
        return token
    if len(token) == 1 and token.isdigit():
        idx = int(token)
        if 1 <= idx <= 8:
            return chr(ord("A") + idx - 1)
    # 形如 "选A"、"答案A"
    tail = re.sub(r"[^A-Z0-9]", "", token)
    if len(tail) == 1 and "A" <= tail <= "Z":
        return tail
    if len(tail) == 1 and tail.isdigit() and 1 <= int(tail) <= 8:
        return chr(ord("A") + int(tail) - 1)
    return ""


def human_bytes(count: int) -> str:
    """人类可读体积。"""
    value = float(count)
    for unit in ("B", "KB", "MB", "GB"):
        if value < 1024 or unit == "GB":
            return f"{value:.0f} {unit}" if unit == "B" else f"{value:.2f} {unit}"
        value /= 1024
    return f"{value:.2f} GB"  # pragma: no cover


def dedupe_keep_order(items: Iterable[str]) -> list[str]:
    """去重且保持首次出现顺序。"""
    seen: set[str] = set()
    out: list[str] = []
    for item in items:
        if item not in seen:
            seen.add(item)
            out.append(item)
    return out
