"""pytest 公共夹具：造一个小而完整的流水线项目。

所有测试都在 ``tmp_path`` 里跑，互不干扰；图片一律很小（64×64 / 4 帧 GIF），
保证整套测试在几秒内跑完。
"""

from __future__ import annotations

import csv
import shutil
import sqlite3
from dataclasses import dataclass
from pathlib import Path

import pytest
from PIL import Image, ImageDraw

from jiakao_pipeline.chapters import ChapterDef, load_chapters
from jiakao_pipeline.pipeline import run_import, run_media, run_normalize
from jiakao_pipeline.util import iter_jsonl_text, read_jsonl

PIPELINE_ROOT = Path(__file__).resolve().parent.parent
CHAPTERS_FILE = PIPELINE_ROOT / "chapters.yaml"
CSV_HEADERS = ["编号", "科目", "题型", "章节", "标签", "题干", "选项A", "选项B", "选项C", "选项D", "答案", "解析", "图片"]


def make_png(path: Path, color: tuple[int, int, int], size: tuple[int, int] = (64, 64), shapes: int = 1) -> Path:
    """造一张内容可区分的小 PNG。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    image = Image.new("RGB", size, color)
    draw = ImageDraw.Draw(image)
    for index in range(shapes):
        offset = 4 + index * 6
        draw.rectangle((offset, offset, offset + 20, offset + 20), fill=(255 - color[0], 200, 40))
    image.save(path, "PNG")
    return path


def make_transparent_png(path: Path, size: tuple[int, int] = (48, 48)) -> Path:
    """造一张带真实透明像素的 PNG。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    image = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.ellipse((0, 0, size[0] - 1, size[1] - 1), fill=(10, 200, 90, 255))
    draw.ellipse((8, 8, size[0] - 9, size[1] - 9), fill=(255, 255, 255, 90))
    image.save(path, "PNG")
    return path


def make_gif(path: Path, frames: int = 4, size: tuple[int, int] = (48, 48)) -> Path:
    """造一个小动图。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    images = []
    for index in range(frames):
        image = Image.new("RGB", size, (20, 40 + index * 20, 160))
        draw = ImageDraw.Draw(image)
        draw.ellipse((index * 4, index * 4, index * 4 + 20, index * 4 + 20), fill=(250, 200, 30))
        images.append(image)
    images[0].save(path, "GIF", save_all=True, append_images=images[1:], duration=80, loop=0)
    return path


def make_fake_mp4(path: Path, duration_seconds: float = 10.0, timescale: int = 1000) -> Path:
    """手工拼一个最小 mp4（含 ftyp + moov/mvhd），让时长解析器有东西可读。

    没有真实视频编码，只用来测"时长 → kind 判定"和"无 ffmpeg 时原样保留"。
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    duration = int(duration_seconds * timescale)

    def box(name: bytes, payload: bytes) -> bytes:
        return (8 + len(payload)).to_bytes(4, "big") + name + payload

    mvhd_payload = (
        b"\x00\x00\x00\x00"          # version 0 + flags
        + (0).to_bytes(4, "big")     # creation
        + (0).to_bytes(4, "big")     # modification
        + timescale.to_bytes(4, "big")
        + duration.to_bytes(4, "big")
        + b"\x00" * 80               # rate/volume/matrix/…（内容不重要）
    )
    payload = box(b"ftyp", b"isom\x00\x00\x02\x00isomiso2") + box(b"moov", box(b"mvhd", mvhd_payload))
    path.write_bytes(payload)
    return path


def write_csv(path: Path, rows: list[dict], headers: list[str] | None = None) -> Path:
    """写一个中文表头的 CSV。"""
    headers = headers or CSV_HEADERS
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8-sig", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=headers)
        writer.writeheader()
        for row in rows:
            writer.writerow({h: row.get(h, "") for h in headers})
    return path


def row(no: str, subject: str, qtype: str, chapter: str, stem: str, options: list[str], answer: str,
        tags: str = "标签", explain: str = "", media: str = "") -> dict:
    """构造一行 CSV 数据（选项不足自动补空）。"""
    data = {
        "编号": no, "科目": subject, "题型": qtype, "章节": chapter, "标签": tags,
        "题干": stem, "答案": answer, "解析": explain, "图片": media,
    }
    for letter in "ABCD":
        data[f"选项{letter}"] = options["ABCD".index(letter)] if "ABCD".index(letter) < len(options) else ""
    return data


#: 默认测试题库（4 题：判断/单选/多选 + 一条带图）。
DEFAULT_ROWS = [
    row("T001", "科目一", "判断", "交通信号", "如图所示，该标志表示禁止停车。", [], "正确",
        tags="标志,禁令", explain="红圈红斜杠。", media="img_a.png"),
    row("T002", "科目一", "单选", "道路通行条件及通行规定", "通过无信号灯路口应当怎样通行？",
        ["加速通过", "减速让行", "鸣喇叭", "停车等待"], "B", tags="让行"),
    row("T003", "科目一", "多选", "道路交通安全法律、法规和规章", "下列哪些属于违法行为？",
        ["酒后驾驶", "礼让行人", "手持电话", "遵守限速"], "A、C", tags="法规"),
    row("T004", "科目四", "判断", "恶劣气象和复杂道路条件下的安全驾驶知识", "雨天行驶应降低车速。",
        [], "正确", tags="雨天", media="anim_a.gif"),
]


@dataclass
class Project:
    """一个临时流水线项目。"""

    root: Path
    adapter: str = "csv"

    def __post_init__(self) -> None:
        """建立目录并拷一份章节表（测试要能改章节表）。"""
        for name in ("work", "dist", "state", "raw"):
            (self.root / name).mkdir(parents=True, exist_ok=True)
        self.chapters_path = self.root / "chapters.yaml"
        if not self.chapters_path.exists():
            shutil.copy(CHAPTERS_FILE, self.chapters_path)

    # ── 路径 ──
    @property
    def work(self) -> Path:
        """work 目录。"""
        return self.root / "work"

    @property
    def dist(self) -> Path:
        """dist 目录。"""
        return self.root / "dist"

    @property
    def state(self) -> Path:
        """state 目录。"""
        return self.root / "state"

    @property
    def images(self) -> Path:
        """素材目录。"""
        return self.root / "raw" / "images"

    @property
    def chapters(self) -> list[ChapterDef]:
        """当前章节表。"""
        return load_chapters(self.chapters_path)

    # ── 数据 ──
    def dataset(self, rows: list[dict] | None = None, media: dict[str, str] | None = None) -> Path:
        """写原始题库文件（csv/json/sqlite 按 ``adapter`` 决定），返回路径。

        ``media`` 是 ``文件名 → "png"|"transparent"|"gif"|"mp4"`` 的描述。
        """
        rows = list(DEFAULT_ROWS if rows is None else rows)
        for name, kind in (media or {"img_a.png": "png", "anim_a.gif": "gif"}).items():
            target = self.images / name
            if kind == "png":
                make_png(target, (30, 90, 200))
            elif kind == "transparent":
                make_transparent_png(target)
            elif kind == "gif":
                make_gif(target)
            elif kind == "mp4":
                make_fake_mp4(target)
        if self.adapter == "csv":
            return write_csv(self.root / "raw" / "questions.csv", rows)
        if self.adapter == "json":
            import json

            payload = []
            for item in rows:
                payload.append({
                    "编号": item["编号"], "科目": item["科目"], "题型": item["题型"], "章节": item["章节"],
                    "标签": item["标签"], "题干": item["题干"], "答案": item["答案"], "解析": item["解析"],
                    "options": {letter: item[f"选项{letter}"] for letter in "ABCD" if item[f"选项{letter}"]},
                    "media": [m for m in str(item["图片"]).split(";") if m],
                })
            path = self.root / "raw" / "questions.json"
            path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
            return path
        if self.adapter == "sqlite":
            path = self.root / "raw" / "questions.sqlite3"
            if path.exists():
                path.unlink()
            connection = sqlite3.connect(path)
            try:
                connection.execute("CREATE TABLE quiz (" + ", ".join(f'"{h}" TEXT' for h in CSV_HEADERS) + ")")
                connection.executemany(
                    "INSERT INTO quiz VALUES (" + ", ".join("?" for _ in CSV_HEADERS) + ")",
                    [tuple(item.get(h, "") for h in CSV_HEADERS) for item in rows],
                )
                connection.commit()
            finally:
                connection.close()
            return path
        raise ValueError(f"未知适配器 {self.adapter}")

    # ── 流水线 ──
    def raw_path(self) -> Path:
        """当前适配器对应的原始题库文件路径。"""
        return {
            "csv": self.root / "raw" / "questions.csv",
            "json": self.root / "raw" / "questions.json",
            "sqlite": self.root / "raw" / "questions.sqlite3",
        }[self.adapter]

    def run(self, source: Path | None = None, jobs: int = 1, strict: bool = True) -> dict:
        """跑 import → normalize → media 三步。

        不传 ``source`` 时沿用已存在的原始题库文件（否则按默认数据生成一份）——
        这样"改数据 → 重新导入"的测试不会被默认数据覆盖。
        """
        if source is None:
            existing = self.raw_path()
            source = existing if existing.exists() else self.dataset()
        imported = run_import(self.adapter, source, self.work, images_dir=self.images)
        normalized = run_normalize(self.work, self.chapters, self.state, strict=strict)
        media = run_media(self.work, self.dist, jobs=jobs)
        return {"import": imported, "normalize": normalized, "media": media}

    def build(self, **kwargs):
        """跑 build。"""
        from jiakao_pipeline.build_pack import build
        from jiakao_pipeline.pipeline import load_media_index

        kwargs.setdefault("released_at", "2026-01-01T00:00:00Z")
        return build(
            in_dir=self.work,
            dist_dir=self.dist,
            state_dir=self.state,
            chapters=self.chapters,
            media_index=load_media_index(self.work),
            **kwargs,
        )

    # ── 产物读取 ──
    def final_questions(self) -> list[dict]:
        """读最终合同格式题目。"""
        return list(read_jsonl(self.work / "questions.jsonl"))

    def manifest(self) -> dict:
        """读 manifest.json。"""
        import json

        return json.loads((self.dist / "manifest.json").read_text(encoding="utf-8"))

    def full_bank(self, version: int | None = None) -> dict[str, dict]:
        """读全量快照（解压），返回 id → 题目。"""
        import gzip

        version = version or self.manifest()["bank_version"]
        payload = gzip.decompress((self.dist / "full" / f"bank-v{version}.jsonl.gz").read_bytes())
        question = {}
        for line in payload.decode("utf-8").splitlines():
            if line.strip():
                import json

                record = json.loads(line)
                question[record["id"]] = record
        return question


@pytest.fixture
def project(tmp_path: Path) -> Project:
    """默认 csv 项目。"""
    return Project(tmp_path)


@pytest.fixture
def json_project(tmp_path: Path) -> Project:
    """json 适配器项目。"""
    return Project(tmp_path, adapter="json")


@pytest.fixture
def sqlite_project(tmp_path: Path) -> Project:
    """sqlite 适配器项目。"""
    return Project(tmp_path, adapter="sqlite")


def write_jsonl(path: Path, rows: list[dict]) -> Path:
    """写 jsonl（测试里手写题目用）。"""
    path.write_text(iter_jsonl_text(rows), encoding="utf-8")
    return path
