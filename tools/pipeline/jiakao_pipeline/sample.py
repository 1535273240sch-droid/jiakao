"""样例数据生成（``make sample``）：20 道**自编演示题** + Pillow 绘制的静图/动图。

声明：这里的所有题目与图片都是本模块用代码画出来的演示素材，
**不来自任何真实题库**，内容不构成考试依据。

产物（默认写到 ``./sample``）：
- ``questions.csv``      中文表头，供 csv 适配器（默认流程用这个）
- ``questions.json``     同内容，供 json 适配器
- ``questions.sqlite3``  同内容，供 sqlite 适配器
- ``images/``            静图 PNG（含大图、透明图、同内容重复图）+ 动图 GIF
- ``README.md``          样例说明

三种格式内容完全一致，因此 ``--adapter csv|json|sqlite`` 的构建结果应当可互相替代（有测试覆盖）。
"""

from __future__ import annotations

import csv
import math
import sqlite3
from dataclasses import dataclass, field
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from .util import clean_text, ensure_dir

#: 可能的 CJK 字体（取第一个存在的；都没有就退化成只画图形不写字）。
CJK_FONT_CANDIDATES = (
    "C:/Windows/Fonts/msyh.ttc",
    "C:/Windows/Fonts/simhei.ttf",
    "C:/Windows/Fonts/simsun.ttc",
    "/System/Library/Fonts/PingFang.ttc",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/truetype/arphic/uming.ttc",
)
CSV_HEADERS = ("编号", "科目", "题型", "章节", "标签", "题干", "选项A", "选项B", "选项C", "选项D", "答案", "解析", "图片")


@dataclass
class SampleQuestion:
    """一条样例题（同时也是 CSV/JSON/SQLite 三种格式的共同数据源）。"""

    no: int
    subject: int
    qtype: str
    chapter: str
    tags: list[str]
    stem: str
    options: list[str]
    answer: str
    explain: str = ""
    media: list[str] = field(default_factory=list)

    def row(self) -> dict:
        """转成 CSV 行（中文表头）。"""
        row = {
            "编号": f"S{self.no:03d}",
            "科目": "科目一" if self.subject == 1 else "科目四",
            "题型": {"judge": "判断", "single": "单选", "multi": "多选"}[self.qtype],
            "章节": self.chapter,
            "标签": "、".join(self.tags),
            "题干": self.stem,
            "答案": self.answer,
            "解析": self.explain,
            "图片": ";".join(self.media),
        }
        for index, letter in enumerate("ABCD"):
            row[f"选项{letter}"] = self.options[index] if index < len(self.options) else ""
        return row


# ───────────────────────── 题库内容（自编）─────────────────────────

SAMPLE_QUESTIONS: tuple[SampleQuestion, ...] = (
    SampleQuestion(
        1, 1, "judge", "交通信号", ["标志", "禁令"],
        "如图所示，这个标志的含义是禁止车辆停放。",
        [], "正确", "红色圆环加红色斜杠表示禁止车辆停放。", ["sign_no_parking.png"],
    ),
    SampleQuestion(
        2, 1, "single", "交通信号", ["标志", "限速"],
        "如图所示，这个标志表示该路段机动车最高时速不得超过多少？",
        ["30 公里", "40 公里", "50 公里", "60 公里"], "B",
        "白底红圈内的数字表示该路段的最高限速值。", ["sign_speed_40.png"],
    ),
    SampleQuestion(
        3, 1, "multi", "道路交通安全法律、法规和规章", ["违法", "记分"],
        "下列哪些行为属于严重交通违法行为？",
        ["饮酒后驾驶机动车", "使用伪造的机动车号牌", "按规定定期检验车辆", "驾驶拼装的机动车上道路行驶"],
        "A、B、D", "伪造号牌与驾驶拼装车辆都属于严重违法行为；定期检验是法定义务。",
        ["dup_scene_a.png"],
    ),
    SampleQuestion(
        4, 1, "judge", "道路通行条件及通行规定", ["高速公路", "雾天"],
        "在高速公路上行驶，遇能见度小于 50 米时，应当从最近的出口尽快驶离高速公路。",
        [], "正确", "能见度极低时继续行驶风险很大，应尽快驶离。",
    ),
    SampleQuestion(
        5, 1, "single", "道路通行条件及通行规定", ["让行", "路口"],
        "如图所示，在没有交通信号灯控制的路口，应当如何通行？",
        ["加速抢先通过", "减速慢行，让右方来车先行", "连续鸣喇叭通过", "停车等待其他车辆全部通过"],
        "B", "无信号灯路口应减速慢行并让右方来车先行。", ["scene_junction.png"],
    ),
    SampleQuestion(
        6, 1, "multi", "安全行车、文明驾驶基础知识", ["安全", "文明"],
        "下列哪些做法属于安全文明驾驶？",
        ["保持安全车距", "感到疲劳时停车休息", "通过人行横道减速慢行", "遇拥堵时强行加塞"],
        "A、B、C", "加塞抢行既不安全也不文明。", ["sign_transparent.png"],
    ),
    SampleQuestion(
        7, 1, "judge", "交通信号", ["标线"],
        "道路中央的黄色双实线表示禁止车辆跨越超车或者压线行驶。",
        [], "正确", "黄色双实线用于分隔对向交通流，禁止跨越。",
    ),
    SampleQuestion(
        8, 1, "single", "交通信号", ["信号灯", "动画"],
        "如图所示，动画中信号灯由绿灯变为黄灯，此时车辆尚未越过停止线，应当怎样做？",
        ["加速通过路口", "停车等待下一个绿灯", "鸣喇叭提醒前车", "缓慢驶入路口左转"],
        "B", "黄灯亮起时未越过停止线的车辆应停车等待。", ["anim_signal.gif"],
    ),
    SampleQuestion(
        9, 1, "judge", "机动车驾驶操作相关基础知识", ["灯光", "夜间"],
        "夜间会车时，应当在距对向来车 150 米以外改用近光灯。",
        [], "正确", "夜间会车应提前改用近光灯避免眩目。",
    ),
    SampleQuestion(
        10, 1, "multi", "道路交通安全法律、法规和规章", ["驾驶证"],
        "下列哪些情形不得申请机动车驾驶证？",
        ["患有妨碍安全驾驶的疾病", "吸食毒品后未戒除", "年龄不符合规定要求", "具备完全民事行为能力"],
        "A、B、C", "具备完全民事行为能力不是禁止条件。", ["dup_scene_b.png"],
    ),
    SampleQuestion(
        11, 1, "single", "道路通行条件及通行规定", ["超车", "安全"],
        "如图所示，双向两车道道路上，前车速度明显较慢，下列哪种做法正确？",
        ["从右侧借用非机动车道超车", "对向车道有来车时强行超车", "确认安全后从左侧超车", "持续鸣喇叭迫使前车让行"],
        "C", "超车应确认安全后从左侧进行。", ["scene_lane.png"],
    ),
    SampleQuestion(
        12, 1, "judge", "交通信号", ["标志", "警告"],
        "如图所示，黄色菱形标志用于警告前方路段存在需要注意的危险。",
        [], "正确", "黄色菱形是警告标志的基本形状。", ["sign_warning.png"],
    ),
    SampleQuestion(
        13, 4, "single", "安全文明驾驶操作要求", ["起步", "观察"],
        "驾驶机动车起步前，下列做法正确的是什么？",
        ["直接起步", "观察周围情况并确认安全后起步", "鸣喇叭后立即起步", "打开危险报警闪光灯后起步"],
        "B", "起步前应通过后视镜和转头观察确认安全。",
    ),
    SampleQuestion(
        14, 4, "multi", "恶劣气象和复杂道路条件下的安全驾驶知识", ["雾天", "灯光"],
        "雾天行车时，下列哪些做法正确？",
        ["开启雾灯", "开启危险报警闪光灯", "降低车速行驶", "开启远光灯提高亮度"],
        "A、B、C", "远光灯在雾天会产生漫反射，反而看不清路面。",
    ),
    SampleQuestion(
        15, 4, "judge", "爆胎等紧急情况下的临危处置方法", ["爆胎", "紧急"],
        "行驶中前轮突然爆胎，应当紧握转向盘，控制住车辆行驶方向，缓慢减速停车。",
        [], "正确", "爆胎瞬间猛打方向或急刹车极易导致失控。",
    ),
    SampleQuestion(
        16, 4, "single", "爆胎等紧急情况下的临危处置方法", ["侧滑", "动画"],
        "如图所示，车辆在湿滑路面转向时出现侧滑，正确的处置方法是什么？",
        ["迅速猛踩制动踏板", "向侧滑方向适度修正转向并缓抬加速踏板", "立即拉紧驻车制动", "向反方向大幅转向"],
        "B", "湿滑路面侧滑时应顺着侧滑方向修正并缓慢减速。", ["anim_turn_left.gif"],
    ),
    SampleQuestion(
        17, 4, "multi", "防范次生事故处置知识", ["警告", "高速"],
        "高速公路上车辆发生故障停车后，下列哪些做法正确？",
        ["开启危险报警闪光灯", "在来车方向 150 米以外放置三角警告牌", "人员迅速转移到护栏外安全地带", "在行车道内检修车辆"],
        "A、B、C", "在行车道内检修极易引发次生事故。",
    ),
    SampleQuestion(
        18, 4, "judge", "伤员急救知识", ["止血", "包扎"],
        "对四肢出血的伤员，可以采用加压包扎的方法止血。",
        [], "正确", "加压包扎是四肢出血常用的现场止血方法。",
    ),
    SampleQuestion(
        19, 4, "single", "文明行车常识", ["礼让", "行人", "动画"],
        "如图所示，行人正在通过人行横道，机动车应当怎样做？",
        ["鸣喇叭示意行人让行", "从行人后方加速通过", "停车让行", "缓慢行驶穿插通过"],
        "C", "遇行人通过人行横道时应停车让行。", ["anim_pedestrian.gif"],
    ),
    SampleQuestion(
        20, 4, "multi", "恶劣气象和复杂道路条件下的安全驾驶知识", ["隧道", "山路"],
        "通过隧道时，下列哪些做法正确？",
        ["开启前照灯", "降低行驶速度", "保持安全车距", "在隧道内掉头或者倒车"],
        "A、B、C", "隧道内禁止掉头、倒车和停车。",
    ),
)


# ───────────────────────── 画图 ─────────────────────────


def find_cjk_font() -> str | None:
    """可用的中文字体路径（没有则返回 None）。"""
    for candidate in CJK_FONT_CANDIDATES:
        if Path(candidate).exists():
            return candidate
    return None


def _font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    """取字体（无 CJK 字体时退化为 Pillow 内置位图字体）。"""
    path = find_cjk_font()
    if path:
        try:
            return ImageFont.truetype(path, size)
        except OSError:  # pragma: no cover - 字体文件损坏
            pass
    return ImageFont.load_default()


def _center_text(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int], text: str, size: int) -> None:
    """在矩形内居中写字（有 CJK 字体才画中文字符）。"""
    font = _font(size)
    left, top, right, bottom = box
    try:
        text_box = draw.textbbox((0, 0), text, font=font)
    except Exception:  # noqa: BLE001 - 位图字体
        text_box = (0, 0, size, size)
    width = text_box[2] - text_box[0]
    height = text_box[3] - text_box[1]
    draw.text(
        (left + (right - left - width) / 2 - text_box[0], top + (bottom - top - height) / 2 - text_box[1]),
        text,
        font=font,
        fill=(20, 20, 20),
    )


def draw_no_parking(size: int = 480) -> Image.Image:
    """禁止停放标志示意图（自绘几何图形，非国标原件）。"""
    img = Image.new("RGB", (size, size), (245, 247, 250))
    draw = ImageDraw.Draw(img)
    margin = size // 10
    draw.ellipse((margin, margin, size - margin, size - margin), fill=(255, 255, 255), outline=(200, 30, 30), width=size // 14)
    draw.line(
        (margin + size // 12, margin + size // 12, size - margin - size // 12, size - margin - size // 12),
        fill=(200, 30, 30), width=size // 16,
    )
    # 中间画一个简化车形（避免直接复制国标图案）
    body = (size // 2 - size // 8, size // 2 - size // 16, size // 2 + size // 8, size // 2 + size // 10)
    draw.rectangle(body, fill=(60, 90, 170))
    return img


def draw_speed_sign(text: str = "40", size: int = 480) -> Image.Image:
    """限速标志示意图。"""
    img = Image.new("RGB", (size, size), (245, 247, 250))
    draw = ImageDraw.Draw(img)
    margin = size // 12
    draw.ellipse((margin, margin, size - margin, size - margin), fill=(255, 255, 255), outline=(200, 30, 30), width=size // 12)
    _center_text(draw, (0, 0, size, size), text, size // 3)
    return img


def draw_warning_diamond(size: int = 480) -> Image.Image:
    """警告标志（黄底菱形）示意图。"""
    img = Image.new("RGB", (size, size), (245, 247, 250))
    draw = ImageDraw.Draw(img)
    pad = size // 10
    points = [(size // 2, pad), (size - pad, size // 2), (size // 2, size - pad), (pad, size // 2)]
    draw.polygon(points, fill=(255, 214, 0), outline=(30, 30, 30))
    draw.line([(size // 2, size // 3), (size // 2, size * 2 // 3)], fill=(30, 30, 30), width=size // 30)
    draw.ellipse((size // 2 - size // 40, size * 3 // 4 - size // 40, size // 2 + size // 40, size * 3 // 4 + size // 40), fill=(30, 30, 30))
    return img


def draw_transparent_badge(size: int = 400) -> Image.Image:
    """带真实透明像素的示意图（用 PNG 存，用来验证"透明图无损 WebP"路径）。"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.ellipse((0, 0, size - 1, size - 1), fill=(40, 160, 90, 255))
    draw.ellipse((size // 5, size // 5, size * 4 // 5, size * 4 // 5), fill=(255, 255, 255, 128))
    return img


def draw_scene(width: int, height: int, variant: str) -> Image.Image:
    """道路场景示意图（大图用来验证长边缩放到 1080）。"""
    img = Image.new("RGB", (width, height), (176, 208, 232))
    draw = ImageDraw.Draw(img)
    horizon = int(height * 0.42)
    draw.rectangle((0, horizon, width, height), fill=(96, 96, 100))
    # 车道分隔线
    dash = int(height * 0.06)
    for x in range(0, width, dash * 3):
        draw.rectangle((x, horizon + int(height * 0.22), x + dash * 2, horizon + int(height * 0.25)), fill=(240, 240, 230))
    if variant == "junction":
        draw.line((width // 2, horizon, width // 2, height), fill=(240, 240, 230), width=max(2, width // 200))
        draw.rectangle((int(width * 0.08), int(height * 0.62), int(width * 0.34), int(height * 0.74)), fill=(200, 60, 60))
    else:
        draw.rectangle((int(width * 0.3), int(height * 0.6), int(width * 0.62), int(height * 0.78)), fill=(60, 110, 200))
        draw.rectangle((int(width * 0.05), int(height * 0.78), int(width * 0.22), int(height * 0.9)), fill=(230, 230, 235))
    _center_text(draw, (0, 0, width, horizon // 2), "示意图（自绘，非国标原件）", max(14, width // 32))
    return img


def draw_animation(kind: str, frames: int = 16, size: tuple[int, int] = (320, 180)) -> list[Image.Image]:
    """生成动图帧序列：信号灯变色 / 车辆转向 / 行人过街。"""
    width, height = size
    out: list[Image.Image] = []
    for index in range(frames):
        img = Image.new("RGB", size, (200, 220, 235))
        draw = ImageDraw.Draw(img)
        draw.rectangle((0, int(height * 0.65), width, height), fill=(90, 90, 95))
        phase = index / frames
        if kind == "signal":
            for slot, color in enumerate(((220, 60, 60), (235, 200, 60), (60, 180, 90))):
                on = slot == int(phase * 3) % 3
                cx = int(width * (0.3 + 0.2 * slot))
                draw.ellipse((cx - 14, 30 - 14, cx + 14, 30 + 14), fill=color if on else (70, 70, 70))
            # 触发灯：绿→黄 的瞬间用高亮外框提示
            if int(phase * 3) % 3 == 1:
                draw.rectangle((int(width * 0.62), 10, int(width * 0.72), 52), outline=(255, 80, 80), width=3)
        elif kind == "turn":
            cx = int(width * (0.85 - 0.6 * phase))
            cy = int(height * (0.78 - 0.25 * math.sin(phase * math.pi)))
            draw.polygon(
                [(cx - 30, cy), (cx + 30, cy - 10), (cx + 30, cy + 10)],
                fill=(50, 90, 200),
            )
            draw.line((cx - 40, cy, cx + 40, cy), fill=(40, 40, 40), width=2)
        else:  # pedestrian
            cross_x = int(width * (0.1 + 0.8 * phase))
            for i in range(6):
                x = int(width * (0.1 + 0.8 * i / 5))
                draw.rectangle((x - 8, int(height * 0.6), x + 8, int(height * 0.68)), fill=(240, 240, 230))
            draw.ellipse((cross_x - 8, int(height * 0.66) - 26, cross_x + 8, int(height * 0.66) - 10), fill=(230, 120, 40))
            draw.rectangle((cross_x - 6, int(height * 0.66) - 10, cross_x + 6, int(height * 0.66) + 6), fill=(230, 120, 40))
        out.append(img)
    return out


def generate_images(images_dir: Path) -> dict[str, Path]:
    """生成全部样例图片，返回 ``文件名 → 路径``。"""
    ensure_dir(images_dir)
    written: dict[str, Path] = {}

    written["sign_no_parking.png"] = _save(images_dir / "sign_no_parking.png", draw_no_parking())
    written["sign_speed_40.png"] = _save(images_dir / "sign_speed_40.png", draw_speed_sign("40"))
    written["sign_warning.png"] = _save(images_dir / "sign_warning.png", draw_warning_diamond())
    written["sign_transparent.png"] = _save(images_dir / "sign_transparent.png", draw_transparent_badge())
    written["scene_junction.png"] = _save(images_dir / "scene_junction.png", draw_scene(900, 600, "junction"))
    written["scene_lane.png"] = _save(images_dir / "scene_lane.png", draw_scene(1600, 900, "lane"))

    # 同内容、不同文件名的两张图 → 验证"相同内容自动去重"
    dup = draw_scene(1000, 620, "junction")
    written["dup_scene_a.png"] = _save(images_dir / "dup_scene_a.png", dup)
    written["dup_scene_b.png"] = _save(images_dir / "dup_scene_b.png", dup.copy())

    for name, kind, frames in (
        ("anim_signal.gif", "signal", 18),
        ("anim_turn_left.gif", "turn", 20),
        ("anim_pedestrian.gif", "pedestrian", 16),
    ):
        written[name] = _save_gif(images_dir / name, draw_animation(kind, frames))
    return written


def _save(path: Path, image: Image.Image) -> Path:
    """存 PNG（原子性不重要，样例可重生成）。"""
    image.save(path, "PNG")
    return path


def _save_gif(path: Path, frames: list[Image.Image]) -> Path:
    """存 GIF 动图，固定 100ms/帧、无限循环。"""
    frames[0].save(
        path,
        "GIF",
        save_all=True,
        append_images=frames[1:],
        duration=100,
        loop=0,
        optimize=False,
    )
    return path


# ───────────────────────── 三种格式落盘 ─────────────────────────


def write_csv(path: Path, questions: tuple[SampleQuestion, ...] = SAMPLE_QUESTIONS) -> Path:
    """写 CSV（utf-8-sig，Excel 直接打开不乱码）。"""
    with open(path, "w", encoding="utf-8-sig", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=list(CSV_HEADERS))
        writer.writeheader()
        for question in questions:
            writer.writerow(question.row())
    return path


def write_json(path: Path, questions: tuple[SampleQuestion, ...] = SAMPLE_QUESTIONS) -> Path:
    """写 JSON（对象数组，字段名与 CSV 表头一致，方便对照）。"""
    import json

    payload = []
    for question in questions:
        row = question.row()
        options = {letter: row[f"选项{letter}"] for letter in "ABCD" if row[f"选项{letter}"]}
        payload.append(
            {
                "编号": row["编号"],
                "科目": row["科目"],
                "题型": row["题型"],
                "章节": row["章节"],
                "标签": row["标签"],
                "题干": row["题干"],
                "options": options,
                "answer": row["答案"],
                "解析": row["解析"],
                "media": question.media,
            }
        )
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def write_sqlite(path: Path, questions: tuple[SampleQuestion, ...] = SAMPLE_QUESTIONS) -> Path:
    """写 SQLite（表 questions，列名与 CSV 表头一致）。"""
    if path.exists():
        path.unlink()
    connection = sqlite3.connect(path)
    try:
        columns = ", ".join(f'"{name}" TEXT' for name in CSV_HEADERS)
        connection.execute(f"CREATE TABLE questions ({columns})")
        placeholders = ", ".join("?" for _ in CSV_HEADERS)
        rows = [
            tuple(question.row()[name] for name in CSV_HEADERS) for question in questions
        ]
        connection.executemany(f"INSERT INTO questions VALUES ({placeholders})", rows)
        connection.commit()
    finally:
        connection.close()
    return path


SAMPLE_README = """# 样例数据（自编演示用，非真题）

20 道自编题目 + 用 Pillow 画的示意图/动图，用来验证流水线本身。
**内容不构成考试依据，也不来自任何真实题库。**

- `questions.csv`      中文表头，默认流程走这个（`--adapter csv`）
- `questions.json`     同内容（`--adapter json`）
- `questions.sqlite3`  同内容（`--adapter sqlite`）
- `images/`            静图 PNG（含 1600×900 大图、透明图、两张同内容重复图）+ 动图 GIF

三种格式内容完全一致，命令行换 `--adapter` 即可，构建结果应当等价。
"""


def generate_sample(out_dir: str | Path) -> dict:
    """生成全部样例文件，返回 ``{'dir', 'questions', 'images'}``。"""
    out_dir = Path(out_dir)
    ensure_dir(out_dir)
    images = generate_images(out_dir / "images")
    write_csv(out_dir / "questions.csv")
    write_json(out_dir / "questions.json")
    write_sqlite(out_dir / "questions.sqlite3")
    (out_dir / "README.md").write_text(SAMPLE_README, encoding="utf-8")
    return {
        "dir": out_dir,
        "questions": len(SAMPLE_QUESTIONS),
        "images": images,
    }


def summarize(questions: tuple[SampleQuestion, ...] = SAMPLE_QUESTIONS) -> dict:
    """样例分布摘要（CLI 打印用）。"""
    by_subject: dict[int, int] = {}
    by_type: dict[str, int] = {}
    with_media = 0
    for question in questions:
        by_subject[question.subject] = by_subject.get(question.subject, 0) + 1
        by_type[question.qtype] = by_type.get(question.qtype, 0) + 1
        if question.media:
            with_media += 1
    return {
        "total": len(questions),
        "by_subject": by_subject,
        "by_type": by_type,
        "with_media": with_media,
        "note": clean_text("自编演示数据，不含真题"),
    }
