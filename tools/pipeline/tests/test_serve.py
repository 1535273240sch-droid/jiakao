"""联调服务器：CORS / ETag(304) / Range(206) / 缓存头 / 404。"""

from __future__ import annotations

import json
import threading
import urllib.error
import urllib.request
from pathlib import Path

import pytest

from jiakao_pipeline.serve import cache_control_for, make_server

from conftest import Project


@pytest.fixture
def served(tmp_path: Path):
    """起一个真实的本地 HTTP 服务器，yield (base_url, project)。"""
    project = Project(tmp_path)
    project.dataset()
    project.run()
    project.build()
    server = make_server(project.dist, port=0, host="127.0.0.1", quiet=True)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{server.server_address[1]}/"
    try:
        yield base, project
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def test_cache_control_policy() -> None:
    """缓存策略：media 永久、manifest 不缓存、full/delta 短缓存。"""
    assert "immutable" in cache_control_for("media/ab/x.webp")
    assert "no-cache" in cache_control_for("manifest.json")
    assert "max-age=300" in cache_control_for("full/bank-v1.jsonl.gz")
    assert "max-age=86400" in cache_control_for("bundle/bundle-v1.zip")


def test_manifest_is_served_with_cors_and_no_cache(served) -> None:
    """manifest.json 可访问、通过 Schema、带 CORS 与 no-cache。"""
    base, project = served
    with urllib.request.urlopen(base + "manifest.json", timeout=10) as response:
        assert response.status == 200
        assert response.headers["Access-Control-Allow-Origin"] == "*"
        assert "no-cache" in response.headers["Cache-Control"]
        assert response.headers["ETag"]
        assert "bytes" in response.headers["Accept-Ranges"]
        manifest = json.loads(response.read().decode("utf-8"))

    from jiakao_pipeline.validate import validate_manifest

    assert validate_manifest(manifest) == []


def test_etag_returns_304(served) -> None:
    """带 If-None-Match 时返回 304（合同 §3.5 要求 manifest 支持 ETag）。"""
    base, _ = served
    request = urllib.request.Request(base + "manifest.json")
    with urllib.request.urlopen(request, timeout=10) as response:
        etag = response.headers["ETag"]

    request = urllib.request.Request(base + "manifest.json", headers={"If-None-Match": etag})
    with pytest.raises(urllib.error.HTTPError) as excinfo:
        urllib.request.urlopen(request, timeout=10)
    assert excinfo.value.code == 304


def test_range_returns_partial_content(served) -> None:
    """Range 请求返回 206 + Content-Range（合同 §3.5 要求断点续传）。"""
    base, project = served
    manifest = project.manifest()
    url = base + manifest["full"]["url"]
    request = urllib.request.Request(url, headers={"Range": "bytes=0-9"})
    with urllib.request.urlopen(request, timeout=10) as response:
        assert response.status == 206
        assert response.headers["Content-Range"].startswith("bytes 0-9/")
        assert len(response.read()) == 10


def test_media_is_immutable_cached(served) -> None:
    """媒体文件带 immutable 缓存头且内容与 sha256 一致。"""
    base, project = served
    question = next(q for q in project.final_questions() if q["media"])
    ref = question["media"][0]
    with urllib.request.urlopen(base + "media/" + f"{ref['sha256'][:2]}/{ref['sha256']}.{ref['ext']}", timeout=10) as response:
        assert response.status == 200
        payload = response.read()
        assert "immutable" in response.headers["Cache-Control"]
    from jiakao_pipeline.util import sha256_bytes

    assert sha256_bytes(payload) == ref["sha256"]


def test_options_preflight(served) -> None:
    """CORS 预检请求返回 204 与允许头。"""
    base, _ = served
    request = urllib.request.Request(base + "manifest.json", method="OPTIONS")
    with urllib.request.urlopen(request, timeout=10) as response:
        assert response.status == 204
        assert response.headers["Access-Control-Allow-Methods"]


def test_missing_file_404(served) -> None:
    """不存在的路径返回 404。"""
    base, _ = served
    with pytest.raises(urllib.error.HTTPError) as excinfo:
        urllib.request.urlopen(base + "nope.json", timeout=10)
    assert excinfo.value.code == 404


def test_directory_listing_for_browsing(served) -> None:
    """根目录可以直接在浏览器里浏览（联调用）。"""
    base, _ = served
    with urllib.request.urlopen(base, timeout=10) as response:
        body = response.read().decode("utf-8", errors="replace")
    assert response.status == 200
    assert "manifest.json" in body


def test_make_server_rejects_missing_dir(tmp_path: Path) -> None:
    """dist 不存在时给出明确错误（提示先 build）。"""
    with pytest.raises(FileNotFoundError, match="先执行 make build"):
        make_server(tmp_path / "nope")
