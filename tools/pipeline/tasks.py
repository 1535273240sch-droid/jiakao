#!/usr/bin/env python3
"""Makefile 的 Windows 等价物（本机没有 GNU make 时用这个）。

    python tasks.py setup
    python tasks.py sample build serve
    python tasks.py test
    python tasks.py bench

目标与 Makefile 完全一致，命令也一致 —— 只是用 Python 直接调度，
避免 Windows 上装 make / 依赖 POSIX shell。
"""

from __future__ import annotations

import os
import shlex
import subprocess
import sys
import venv
from pathlib import Path

ROOT = Path(__file__).resolve().parent
VENV = ROOT / ".venv"
BIN = VENV / ("Scripts" if os.name == "nt" else "bin")
PY = BIN / ("python.exe" if os.name == "nt" else "python")
CLI = [str(PY), "-m", "jiakao_pipeline"]

WORK = "./work"
DIST = "./dist"
STATE = "./state"
SAMPLE = "./sample"


def _run(args: list[str], **kwargs) -> None:
    """打印并执行命令，失败即中止。"""
    print("$ " + " ".join(shlex.quote(a) for a in args), flush=True)
    subprocess.run(args, cwd=str(ROOT), check=True, **kwargs)


def _need_venv() -> None:
    """确保虚拟环境存在。"""
    if not PY.exists():
        print(f"创建虚拟环境: {VENV}")
        venv.EnvBuilder(with_pip=True).create(str(VENV))


def _cli(*args: str) -> None:
    """执行 CLI 子命令。"""
    _run([*CLI, *args])


# ───────── 目标 ─────────


def setup() -> None:
    """建虚拟环境并安装依赖。"""
    _need_venv()
    _run([str(PY), "-m", "pip", "install", "--disable-pip-version-check", "-r", "requirements.txt"])


def sample_raw() -> None:
    """只生成样例原始数据。"""
    _cli("sample", "--out", SAMPLE)


def do_import() -> None:
    """样例 CSV → work/。"""
    _cli("import", "--adapter", "csv", "--input", f"{SAMPLE}/questions.csv",
         "--images", f"{SAMPLE}/images", "--out", WORK)


def normalize() -> None:
    """规范化 + 稳定 id。"""
    _cli("normalize", "--in", WORK, "--chapters", "chapters.yaml", "--state", STATE)


def media() -> None:
    """媒体转码。"""
    jobs = os.environ.get("JOBS", "4")
    _cli("media", "--in", WORK, "--out", DIST, "--jobs", jobs, "--quiet")


def sample() -> None:
    """样例生成 → import → normalize → media。"""
    for step in (sample_raw, do_import, normalize, media):
        step()


def validate() -> None:
    """合同校验。"""
    _cli("validate", "--in", WORK, "--dist", DIST)


def build() -> None:
    """打包。"""
    _cli("build", "--in", WORK, "--dist", DIST, "--state", STATE)


def serve() -> None:
    """局域网联调。"""
    _cli("serve", "--dist", DIST, "--port", os.environ.get("PORT", "8000"))


def test() -> None:
    """pytest。"""
    _run([str(PY), "-m", "pytest", "-q"])


def bench() -> None:
    """性能基准。"""
    _cli("bench", "--questions", "3000", "--media", "500", "--jobs", os.environ.get("JOBS", "4"))


def clean() -> None:
    """清理产物（保留源码与样例）。"""
    import shutil

    for name in (WORK, DIST, STATE, ".pytest_cache"):
        shutil.rmtree(ROOT / name, ignore_errors=True)
    for path in ROOT.rglob("__pycache__"):
        shutil.rmtree(path, ignore_errors=True)
    print("已清理 work/ dist/ state/ .pytest_cache __pycache__")


def distclean() -> None:
    """清理样例与基准目录（**不删 .venv**：Windows 上正在使用的解释器删不掉）。"""
    import shutil

    clean()
    for name in (SAMPLE, "bench"):
        shutil.rmtree(ROOT / name, ignore_errors=True)
    print("已清理 sample/ bench/")
    print(f"如需连虚拟环境一起清理：先退出当前 shell，再删除 {VENV}")


TARGETS = {
    "setup": setup, "sample-raw": sample_raw, "import": do_import, "normalize": normalize,
    "media": media, "sample": sample, "validate": validate, "build": build, "serve": serve,
    "test": test, "bench": bench, "clean": clean, "distclean": distclean,
}


def main(argv: list[str]) -> int:
    """按顺序执行若干目标。"""
    if not argv or argv[0] in ("-h", "--help", "help"):
        print(__doc__)
        print("可用目标:\n  " + "\n  ".join(TARGETS))
        return 0
    for name in argv:
        if name not in TARGETS:
            print(f"未知目标: {name}\n可用: {', '.join(TARGETS)}")
            return 2
        print(f"\n=== {name} ===")
        TARGETS[name]()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
