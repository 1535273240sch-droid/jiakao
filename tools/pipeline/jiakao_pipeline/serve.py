"""局域网联调服务器：静态托管 ``dist/``，行为尽量贴近生产（Nginx）配置。

比 ``python -m http.server`` 多的东西（都是 App 更新流程真正依赖的）
- **CORS**：``Access-Control-Allow-Origin: *`` + 暴露 ETag/Content-Range（模拟器/真机联调必需）
- **Range**：支持 ``Range: bytes=…`` → 206（合同 §3.5 要求断点续传）
- **ETag / If-None-Match** → 304（合同 §3.5 要求 manifest 用 ETag 校验）
- **缓存头**：``media/**`` 永久缓存（immutable），``manifest.json`` 不缓存，
  ``full/ delta/ bundle/`` 短缓存 —— 与 ``server/nginx.conf.example`` 保持一致
- **HEAD**、目录列表、可选的访问日志

生产环境请用 Nginx（见 ``out/server/nginx.conf.example``），本脚本只用于本地/局域网。
"""

from __future__ import annotations

import hashlib
import mimetypes
import os
import re
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MIME_OVERRIDES = {
    ".json": "application/json; charset=utf-8",
    ".jsonl": "application/x-ndjson; charset=utf-8",
    ".gz": "application/gzip",
    ".webp": "image/webp",
    ".mp4": "video/mp4",
    ".zip": "application/zip",
    ".md": "text/markdown; charset=utf-8",
    ".apk": "application/vnd.android.package-archive",
}
_RANGE_RE = re.compile(r"bytes=(\d*)-(\d*)")


def cache_control_for(rel_path: str) -> str:
    """按路径给缓存策略（与 nginx.conf.example 对齐）。"""
    rel = rel_path.replace("\\", "/").lstrip("/")
    if rel == "manifest.json":
        return "no-cache, must-revalidate"
    if rel.startswith("media/"):
        return "public, max-age=31536000, immutable"
    if rel.startswith(("full/", "delta/")):
        return "public, max-age=300"
    if rel.startswith("bundle/"):
        return "public, max-age=86400"
    return "no-cache"


class RangeRequestHandler(SimpleHTTPRequestHandler):
    """带 CORS / ETag / Range / 缓存头的静态文件处理器。"""

    server_version = "JiakaoDevServer/1.0"
    protocol_version = "HTTP/1.1"

    def __init__(self, *args, directory: str | None = None, quiet: bool = False, **kwargs) -> None:
        self.quiet = quiet
        super().__init__(*args, directory=directory, **kwargs)

    # ───────── 通用 ─────────

    def log_message(self, fmt: str, *args) -> None:
        """访问日志（--quiet 时静默）。"""
        if not self.quiet:
            super().log_message(fmt, *args)

    def end_headers(self) -> None:
        """统一补 CORS 头。"""
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.send_header(
            "Access-Control-Expose-Headers",
            "ETag, Content-Length, Content-Range, Accept-Ranges, Last-Modified, Cache-Control",
        )
        super().end_headers()

    def do_OPTIONS(self) -> None:  # noqa: N802 - 基类命名约定
        """预检请求。"""
        self.send_response(204)
        self.send_header("Content-Length", "0")
        self.end_headers()

    # ───────── GET / HEAD ─────────

    def send_head(self):
        """重写：加 ETag、缓存头与 Range 支持。"""
        self._remaining = None  # keep-alive 复用连接时必须重置
        path = self.translate_path(self.path)
        target = Path(path)
        if target.is_dir():
            return super().send_head()
        if not target.is_file():
            self.send_error(404, "File not found")
            return None

        rel = os.path.relpath(path, self.directory).replace(os.sep, "/")
        stat = target.stat()
        etag = self._etag(target, stat)
        if self.headers.get("If-None-Match") == etag:
            self.send_response(304)
            self.send_header("ETag", etag)
            self.send_header("Cache-Control", cache_control_for(rel))
            self.send_header("Content-Length", "0")
            self.end_headers()
            return None

        content_type = MIME_OVERRIDES.get(target.suffix.lower()) or (
            mimetypes.guess_type(str(target))[0] or "application/octet-stream"
        )
        start, end = self._parse_range(stat.st_size)
        length = end - start + 1

        self.send_response(206 if start or end < stat.st_size - 1 else 200)
        self.send_header("Content-Type", content_type)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("ETag", etag)
        self.send_header("Cache-Control", cache_control_for(rel))
        self.send_header("Content-Length", str(length))
        if start or end < stat.st_size - 1:
            self.send_header("Content-Range", f"bytes {start}-{end}/{stat.st_size}")
        self.end_headers()

        handle = target.open("rb")
        handle.seek(start)
        self._remaining = length
        return handle

    def copyfile(self, source, outputfile) -> None:
        """按 Range 限长拷贝（基类会一直拷到 EOF，206 会多传）。"""
        remaining = getattr(self, "_remaining", None)
        if remaining is None:
            return super().copyfile(source, outputfile)
        while remaining > 0:
            chunk = source.read(min(64 * 1024, remaining))
            if not chunk:
                break
            outputfile.write(chunk)
            remaining -= len(chunk)

    # ───────── 内部 ─────────

    @staticmethod
    def _etag(path: Path, stat: os.stat_result) -> str:
        """内容哈希做 ETag（gzip 与媒体都是不可变产物，哈希最稳）。"""
        digest = hashlib.sha256()
        try:
            with path.open("rb") as fh:
                for chunk in iter(lambda: fh.read(1 << 20), b""):
                    digest.update(chunk)
        except OSError:  # pragma: no cover - 读失败退回元数据
            return f'"{stat.st_mtime_ns:x}-{stat.st_size:x}"'
        return f'"{digest.hexdigest()[:32]}"'

    def _parse_range(self, size: int) -> tuple[int, int]:
        """解析 Range 头，返回 (start, end)；不合法或未请求则整个文件。"""
        header = self.headers.get("Range")
        if not header:
            return 0, size - 1
        match = _RANGE_RE.match(header.strip())
        if not match:
            return 0, size - 1
        raw_start, raw_end = match.group(1), match.group(2)
        if raw_start == "" and raw_end == "":
            return 0, size - 1
        if raw_start == "":  # 后缀范围 bytes=-500
            length = int(raw_end)
            return max(0, size - length), size - 1
        start = int(raw_start)
        end = int(raw_end) if raw_end else size - 1
        if start >= size:
            return 0, size - 1
        return start, min(end, size - 1)


def make_server(
    dist_dir: str | Path,
    port: int = 8000,
    host: str = "0.0.0.0",
    quiet: bool = False,
) -> ThreadingHTTPServer:
    """构造（未启动的）HTTP 服务器。"""
    dist = Path(dist_dir)
    if not dist.exists():
        raise FileNotFoundError(f"发布目录不存在: {dist}（先执行 make build）")
    handler = partial(RangeRequestHandler, directory=str(dist), quiet=quiet)
    server = ThreadingHTTPServer((host, port), handler)
    server.daemon_threads = True
    return server


def serve_forever(
    dist_dir: str | Path,
    port: int = 8000,
    host: str = "0.0.0.0",
    quiet: bool = False,
) -> None:
    """阻塞式启动（Ctrl+C 退出）。"""
    server = make_server(dist_dir, port, host, quiet)
    dist = Path(dist_dir)
    print(f"题库源地址（必须以 / 结尾）: http://127.0.0.1:{port}/")
    print(f"Android 模拟器请填:          http://10.0.2.2:{port}/")
    print(f"真机（同局域网）请填:        http://<本机IP>:{port}/")
    print(f"托管目录: {dist.resolve()}")
    print("支持: CORS / Range(206) / ETag(304) / media 永久缓存 / manifest 不缓存")
    print("生产环境请用 Nginx（见 out/server/nginx.conf.example）。Ctrl+C 停止。")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止。")
    finally:
        server.server_close()
