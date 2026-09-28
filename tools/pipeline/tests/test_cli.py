"""CLI 端到端：``python -m jiakao_pipeline`` 真的能从零跑出题库包。

等价于验收里的 ``make sample build serve``（serve 单独在 test_serve.py 里测）。
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

PIPELINE_ROOT = Path(__file__).resolve().parent.parent
CHAPTERS = PIPELINE_ROOT / "chapters.yaml"


def run_cli(cwd: Path, *args: str, expect: int = 0) -> subprocess.CompletedProcess:
    """在指定目录里跑 CLI。"""
    env = dict(os.environ)
    env["PYTHONPATH"] = str(PIPELINE_ROOT) + os.pathsep + env.get("PYTHONPATH", "")
    env["PYTHONIOENCODING"] = "utf-8"
    completed = subprocess.run(
        [sys.executable, "-m", "jiakao_pipeline", *args],
        cwd=str(cwd), env=env, capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    assert completed.returncode == expect, (
        f"命令 {args} 期望退出码 {expect}，实际 {completed.returncode}\n"
        f"stdout:\n{completed.stdout}\nstderr:\n{completed.stderr}"
    )
    return completed


def test_cli_info(tmp_path: Path) -> None:
    """info 打印版本与工具可用性。"""
    completed = run_cli(tmp_path, "info")
    assert "jiakao-pipeline" in completed.stdout
    assert "题库数据须自行合法获取" in completed.stdout


def test_cli_help_lists_all_commands(tmp_path: Path) -> None:
    """--help 里能看到全部子命令（与 TASK 的清单一致）。"""
    completed = run_cli(tmp_path, "--help")
    for command in ("import", "normalize", "media", "validate", "build", "diff", "serve", "sample", "bench", "info"):
        assert command in completed.stdout


def test_cli_full_flow(tmp_path: Path) -> None:
    """sample → import → normalize → media → validate → build 全流程。"""
    run_cli(tmp_path, "sample", "--out", "./sample")
    assert (tmp_path / "sample" / "questions.csv").exists()
    assert (tmp_path / "sample" / "images").is_dir()

    config = ["--chapters", str(CHAPTERS)]
    run_cli(tmp_path, "import", "--adapter", "csv", "--input", "./sample/questions.csv",
            "--images", "./sample/images", "--out", "./work")
    run_cli(tmp_path, "normalize", "--in", "./work", "--state", "./state", *config)
    run_cli(tmp_path, "media", "--in", "./work", "--out", "./dist", "--jobs", "2", "--quiet")
    run_cli(tmp_path, "validate", "--in", "./work", "--dist", "./dist", *config)
    completed = run_cli(tmp_path, "build", "--in", "./work", "--dist", "./dist", "--state", "./state", *config)

    assert "bank_version=1" in completed.stdout
    manifest = json.loads((tmp_path / "dist" / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["bank_version"] == 1
    assert manifest["full"]["count"] == 20
    assert (tmp_path / "dist" / "report.md").exists()
    assert (tmp_path / "state" / "id_registry.json").exists()

    from jiakao_pipeline.validate import validate_manifest

    assert validate_manifest(manifest) == []


def test_cli_validate_fails_with_nonzero_exit(tmp_path: Path) -> None:
    """校验失败必须非零退出（合同：构建前必须全过）。"""
    run_cli(tmp_path, "sample", "--out", "./sample")
    run_cli(tmp_path, "import", "--adapter", "csv", "--input", "./sample/questions.csv",
            "--images", "./sample/images", "--out", "./work")
    config = ["--chapters", str(CHAPTERS)]
    run_cli(tmp_path, "normalize", "--in", "./work", "--state", "./state", *config)
    run_cli(tmp_path, "media", "--in", "./work", "--out", "./dist", "--jobs", "2", "--quiet")

    # 破坏一道题的答案
    path = tmp_path / "work" / "questions.jsonl"
    lines = path.read_text(encoding="utf-8").splitlines()
    record = json.loads(lines[0])
    record["answer"] = ["Z"]
    lines[0] = json.dumps(record, ensure_ascii=False, separators=(",", ":"))
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    completed = run_cli(tmp_path, "validate", "--in", "./work", "--dist", "./dist", *config, expect=1)
    assert "失败" in completed.stdout

    # build 也必须拒绝
    completed = run_cli(tmp_path, "build", "--in", "./work", "--dist", "./dist",
                        "--state", "./state", *config, expect=1)
    assert "校验未通过" in completed.stdout


def test_cli_diff_command(tmp_path: Path) -> None:
    """diff 子命令产出可解压的增量文件。"""
    import gzip

    run_cli(tmp_path, "sample", "--out", "./sample")
    run_cli(tmp_path, "import", "--adapter", "csv", "--input", "./sample/questions.csv",
            "--images", "./sample/images", "--out", "./work")
    run_cli(tmp_path, "normalize", "--in", "./work", "--state", "./state", "--chapters", str(CHAPTERS))

    lines = (tmp_path / "work" / "questions.norm.jsonl").read_text(encoding="utf-8").splitlines()
    (tmp_path / "old.jsonl").write_text("\n".join(lines[:10]) + "\n", encoding="utf-8")
    (tmp_path / "new.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")

    run_cli(tmp_path, "diff", "--old", "./old.jsonl", "--new", "./new.jsonl",
            "--out", "./delta.jsonl.gz", "--chapters", str(CHAPTERS))
    records = gzip.decompress((tmp_path / "delta.jsonl.gz").read_bytes()).decode("utf-8").splitlines()
    assert len(records) == 10  # 后 10 题是新增


def test_cli_bench_available(tmp_path: Path) -> None:
    """bench 子命令存在且可跑小规模基准。"""
    completed = run_cli(tmp_path, "bench", "--questions", "60", "--media", "10", "--jobs", "2")
    assert "首次构建" in completed.stdout
    assert "缓存构建" in completed.stdout
    assert "达标" in completed.stdout


@pytest.mark.parametrize("adapter,filename", [("json", "questions.json"), ("sqlite", "questions.sqlite3")])
def test_cli_supports_all_adapters(tmp_path: Path, adapter: str, filename: str) -> None:
    """json / sqlite 适配器也能跑完整流程。"""
    run_cli(tmp_path, "sample", "--out", "./sample")
    config = ["--chapters", str(CHAPTERS)]
    run_cli(tmp_path, "import", "--adapter", adapter, "--input", f"./sample/{filename}",
            "--images", "./sample/images", "--out", "./work")
    run_cli(tmp_path, "normalize", "--in", "./work", "--state", "./state", *config)
    run_cli(tmp_path, "media", "--in", "./work", "--out", "./dist", "--jobs", "2", "--quiet")
    completed = run_cli(tmp_path, "build", "--in", "./work", "--dist", "./dist", "--state", "./state", *config)
    assert "bank_version=1" in completed.stdout
