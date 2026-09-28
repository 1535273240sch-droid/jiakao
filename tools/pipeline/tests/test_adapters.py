"""适配器：csv / json / sqlite 三种格式应当产出等价的 RawQuestion。"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from jiakao_pipeline.adapters import AdapterError, get_adapter, load_adapters
from jiakao_pipeline.adapters.base import (
    normalize_vehicles,
    option_columns,
    parse_subject,
    pick_field,
    split_list,
)

from conftest import DEFAULT_ROWS, Project, make_png, write_csv


def test_three_adapters_agree(tmp_path: Path) -> None:
    """同一份数据经 csv / json / sqlite 读出来必须一致（题干/选项/答案/图片）。"""
    load_adapters()
    csv_project = Project(tmp_path / "csv")
    json_project = Project(tmp_path / "json", adapter="json")
    sqlite_project = Project(tmp_path / "sqlite", adapter="sqlite")

    csv_path = csv_project.dataset()
    json_path = json_project.dataset()
    sqlite_path = sqlite_project.dataset()

    def snapshot(adapter: str, path: Path, images: Path) -> list[tuple]:
        cls = get_adapter(adapter)
        rows = list(cls(images_dir=images).iter_raw(path))
        return [
            (r.src_key.split(":")[-1], r.subject, tuple(r.options), tuple(r.answer), r.stem,
             tuple(Path(m.src).name for m in r.media), tuple(r.tags))
            for r in rows
        ]

    base = snapshot("csv", csv_path, csv_project.images)
    assert base == snapshot("json", json_path, json_project.images)
    assert base == snapshot("sqlite", sqlite_path, sqlite_project.images)
    assert len(base) == len(DEFAULT_ROWS)


def test_csv_headers_aliases_and_option_columns(tmp_path: Path) -> None:
    """英文列名 + A/B/C/D 分列也能读。"""
    path = tmp_path / "en.csv"
    write_csv(
        path,
        [{"id": "X1", "subject": "4", "type": "single", "chapter": "s4-c01", "stem": "问题？",
          "A": "甲", "B": "乙", "answer": "B", "tags": "a|b", "vehicles": "truck"}],
        headers=["id", "subject", "type", "chapter", "stem", "A", "B", "answer", "tags", "vehicles"],
    )
    rows = list(get_adapter("csv")().iter_raw(path))
    assert len(rows) == 1
    assert rows[0].options == [("A", "甲"), ("B", "乙")]
    assert rows[0].subject == 4
    assert rows[0].vehicles == ["truck"]
    assert rows[0].tags == ["a", "b"]
    assert rows[0].src_key == "csv:en.csv:X1"


def test_csv_single_column_options(tmp_path: Path) -> None:
    """选项写在一列的写法（| 分隔）也要能拆开。"""
    path = tmp_path / "inline.csv"
    write_csv(
        path,
        [{"编号": "I1", "科目": "科目一", "题型": "单选", "章节": "交通信号", "题干": "？",
          "选项": "A.甲|B.乙|C.丙", "答案": "C"}],
        headers=["编号", "科目", "题型", "章节", "题干", "选项", "答案"],
    )
    rows = list(get_adapter("csv")().iter_raw(path))
    assert rows[0].options == [("A", "甲"), ("B", "乙"), ("C", "丙")]


def test_csv_gbk_encoding(tmp_path: Path) -> None:
    """GBK 编码的 CSV 也能读（用 GB18030 兜底）。"""
    path = tmp_path / "gbk.csv"
    text = "编号,科目,题型,章节,题干,答案\nG1,科目一,判断,交通信号,这是个判断句,正确\n"
    path.write_bytes(text.encode("gb18030"))
    rows = list(get_adapter("csv")().iter_raw(path))
    assert rows[0].stem == "这是个判断句"
    assert rows[0].answer == ["正确"]


def test_csv_missing_file_raises(tmp_path: Path) -> None:
    """文件不存在要给出明确错误。"""
    with pytest.raises(AdapterError, match="不存在"):
        list(get_adapter("csv")().iter_raw(tmp_path / "nope.csv"))


def test_json_shapes(tmp_path: Path) -> None:
    """JSON 支持：对象数组 / {questions:[…]} / jsonl / 选项字典。"""
    payload = [{"编号": "J1", "科目": 1, "题干": "？", "options": {"A": "甲", "B": "乙"}, "answer": ["A"]}]
    array_path = tmp_path / "a.json"
    array_path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    wrapped_path = tmp_path / "b.json"
    wrapped_path.write_text(json.dumps({"questions": payload}, ensure_ascii=False), encoding="utf-8")
    jsonl_path = tmp_path / "c.jsonl"
    jsonl_path.write_text("\n".join(json.dumps(item, ensure_ascii=False) for item in payload), encoding="utf-8")

    for path in (array_path, wrapped_path, jsonl_path):
        rows = list(get_adapter("json")().iter_raw(path))
        assert rows[0].options == [("A", "甲"), ("B", "乙")]
        assert rows[0].answer == ["A"]


def test_json_empty_file_raises(tmp_path: Path) -> None:
    """空 JSON 要报错，不能静默产出 0 题。"""
    path = tmp_path / "empty.json"
    path.write_text("  \n", encoding="utf-8")
    with pytest.raises(AdapterError, match="为空"):
        list(get_adapter("json")().iter_raw(path))


def test_sqlite_auto_table_pick(tmp_path: Path) -> None:
    """不指定 --table 时自动挑有题干列的表；指定则用指定的。"""
    import sqlite3

    path = tmp_path / "db.sqlite3"
    connection = sqlite3.connect(path)
    connection.execute("CREATE TABLE other (a TEXT)")
    connection.execute("CREATE TABLE quiz (编号 TEXT, 题干 TEXT, 答案 TEXT)")
    connection.execute("INSERT INTO other VALUES ('忽略')")
    connection.execute("INSERT INTO quiz VALUES ('S1', '题干内容', '正确')")
    connection.commit()
    connection.close()

    rows = list(get_adapter("sqlite")().iter_raw(path))
    assert [r.stem for r in rows] == ["题干内容"]
    with_table = list(get_adapter("sqlite")(table="other").iter_raw(path))
    assert with_table[0].stem == ""  # other 表没有题干列


def test_media_column_variants(tmp_path: Path) -> None:
    """媒体列支持多文件、显式 kind 提示与相对 images 目录。"""
    make_png(tmp_path / "img" / "a.png", (1, 2, 3))
    path = tmp_path / "m.csv"
    write_csv(
        path,
        [{"编号": "M1", "题干": "？", "答案": "A", "选项A": "甲", "选项B": "乙", "图片": "a.png;;b.gif::anim"}],
        headers=["编号", "题干", "答案", "选项A", "选项B", "图片"],
    )
    rows = list(get_adapter("csv")(images_dir=tmp_path / "img").iter_raw(path))
    media = rows[0].media
    assert str(media[0].src).replace("\\", "/").endswith("img/a.png")
    assert media[0].kind_hint is None
    assert media[1].kind_hint == "anim"


def test_helper_functions() -> None:
    """基础小工具的边界行为。"""
    assert parse_subject("科目四") == 4
    assert parse_subject("s1") == 1
    assert parse_subject("三") is None
    assert normalize_vehicles("小汽车、货车") == ["car", "truck"]
    assert normalize_vehicles("飞机") == []
    assert split_list("a,b、c|d") == ["a", "b", "c", "d"]
    assert option_columns({"选项A": "甲", "B": "乙", "题干": "？"}) == [("A", "甲"), ("B", "乙")]
    assert pick_field({"题干": "x"}, ("stem", "题干")) == "x"
    assert pick_field({"题干": "  "}, ("题干",)) is None
