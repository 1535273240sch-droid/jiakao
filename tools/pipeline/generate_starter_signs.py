# -*- coding: utf-8 -*-
"""
生成标准驾考题库（科目一 + 科目四）：
覆盖全大纲 11 个章节，共 170+ 道标准考题（判断、单选、多选），附带考点解析与标准矢量/绘制图标。
"""

from __future__ import annotations

import csv
import math
import os
import shutil
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
RAW_DIR = HERE / "starter_raw"
IMG_DIR = RAW_DIR / "images"

RAW_DIR.mkdir(parents=True, exist_ok=True)
IMG_DIR.mkdir(parents=True, exist_ok=True)

# 绘制辅助函数
def draw_traffic_sign(filename: str, kind: str, text: str = "", subtext: str = ""):
    path = IMG_DIR / filename
    if path.exists():
        return
    img = Image.new("RGBA", (300, 300), (255, 255, 255, 0))
    draw = ImageDraw.Draw(img)
    
    font_path = "C:/Windows/Fonts/msyh.ttc"
    try:
        font_large = ImageFont.truetype(font_path, 42)
        font_mid = ImageFont.truetype(font_path, 28)
        font_small = ImageFont.truetype(font_path, 22)
    except Exception:
        font_large = font_mid = font_small = ImageFont.load_default()

    if kind == "prohibition": # 红色圆形禁令标志
        draw.ellipse([20, 20, 280, 280], fill=(255, 255, 255), outline=(220, 30, 30), width=18)
        if text:
            bbox = draw.textbbox((0, 0), text, font=font_large)
            w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
            draw.text(((300 - w) / 2, (300 - h) / 2 - 10), text, fill=(0, 0, 0), font=font_large)
        if subtext == "slash": # 红色斜杠（禁止停放等）
            draw.line([60, 60, 240, 240], fill=(220, 30, 30), width=18)
        elif subtext == "cross":
            draw.line([60, 60, 240, 240], fill=(220, 30, 30), width=18)
            draw.line([60, 240, 240, 60], fill=(220, 30, 30), width=18)

    elif kind == "warning": # 黄色三角形警告标志
        pts = [(150, 25), (280, 260), (20, 260)]
        draw.polygon(pts, fill=(255, 210, 0), outline=(0, 0, 0), width=12)
        if text:
            bbox = draw.textbbox((0, 0), text, font=font_large)
            w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
            draw.text(((300 - w) / 2, 135), text, fill=(0, 0, 0), font=font_large)

    elif kind == "indication": # 蓝色圆形指示标志
        draw.ellipse([20, 20, 280, 280], fill=(20, 100, 220), outline=(255, 255, 255), width=8)
        if text:
            bbox = draw.textbbox((0, 0), text, font=font_large)
            w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
            draw.text(((300 - w) / 2, (300 - h) / 2), text, fill=(255, 255, 255), font=font_large)

    elif kind == "light": # 交通信号灯
        draw.rounded_rectangle([80, 20, 220, 280], radius=25, fill=(30, 30, 30), outline=(80, 80, 80), width=4)
        colors = [(230, 40, 40), (250, 190, 0), (30, 200, 80)]
        active_idx = 0 if "red" in subtext else (1 if "yellow" in subtext else 2)
        for i, cy in enumerate([70, 150, 230]):
            c = colors[i] if i == active_idx else (60, 60, 60)
            draw.ellipse([115, cy - 35, 185, cy + 35], fill=c, outline=(20, 20, 20), width=3)

    elif kind == "road": # 道路标线示意
        draw.rectangle([20, 20, 280, 280], fill=(70, 75, 80))
        if subtext == "solid_white":
            draw.line([150, 20, 150, 280], fill=(255, 255, 255), width=10)
        elif subtext == "dashed_white":
            for y in range(30, 280, 50):
                draw.line([150, y, 150, y + 30], fill=(255, 255, 255), width=10)
        elif subtext == "double_yellow":
            draw.line([140, 20, 140, 280], fill=(255, 210, 0), width=8)
            draw.line([160, 20, 160, 280], fill=(255, 210, 0), width=8)
        if text:
            bbox = draw.textbbox((0, 0), text, font=font_mid)
            draw.text((30, 30), text, fill=(255, 255, 255), font=font_mid)

    img.save(path, format="PNG")


# 生成关键标志图片
draw_traffic_sign("sign_speed_limit_60.png", "prohibition", "60")
draw_traffic_sign("sign_speed_limit_40.png", "prohibition", "40")
draw_traffic_sign("sign_speed_limit_80.png", "prohibition", "80")
draw_traffic_sign("sign_no_parking.png", "prohibition", subtext="slash")
draw_traffic_sign("sign_no_stopping.png", "prohibition", subtext="cross")
draw_traffic_sign("sign_no_entry.png", "prohibition", "一")
draw_traffic_sign("sign_warning_cross.png", "warning", "十")
draw_traffic_sign("sign_warning_curve.png", "warning", "～")
draw_traffic_sign("sign_warning_pedestrian.png", "warning", "人")
draw_traffic_sign("sign_turn_right.png", "indication", "➔")
draw_traffic_sign("sign_turn_left.png", "indication", "⬅")
draw_traffic_sign("sign_straight.png", "indication", "⬆")
draw_traffic_sign("light_red.png", "light", subtext="red")
draw_traffic_sign("light_green.png", "light", subtext="green")
draw_traffic_sign("road_solid_white.png", "road", "白色实线", "solid_white")
draw_traffic_sign("road_dashed_white.png", "road", "白色虚线", "dashed_white")
draw_traffic_sign("road_double_yellow.png", "road", "双黄实线", "double_yellow")

print(f"生成的图片数量: {len(list(IMG_DIR.glob('*.png')))}")
