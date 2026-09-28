"""合同 §2 / §3 的 pydantic 模型（字段顺序 = 合同字段顺序）。

这些模型既做类型校验，也做序列化器：``model_dump()`` 的键顺序就是合同里列出的顺序，
因此 jsonl 行是逐字节稳定的（这是"同输入字节级可复现"的前提）。
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

VehicleT = Literal["car", "truck", "bus", "moto"]
TypeT = Literal["judge", "single", "multi"]
KindT = Literal["image", "anim", "video"]
ExtT = Literal["webp", "mp4"]

VEHICLE_ORDER: tuple[str, ...] = ("car", "truck", "bus", "moto")


class Option(BaseModel):
    """选择题选项，key 为大写字母。"""

    key: str
    text: str


class MediaRef(BaseModel):
    """媒体引用（合同 §2 ``media`` 元素）。"""

    sha256: str
    ext: ExtT
    kind: KindT
    w: int
    h: int
    bytes: int


class Question(BaseModel):
    """一道题（合同 §2 的一行 JSON）。"""

    model_config = ConfigDict(extra="forbid")

    id: str
    subject: int
    vehicles: list[VehicleT]
    type: TypeT
    chapter_id: str
    tags: list[str]
    stem: str
    options: list[Option]
    answer: list[str]
    explain: str
    media: list[MediaRef]
    rev: int


class Chapter(BaseModel):
    """章节（manifest.chapters 元素 = 合同 §2 的 chapter_id 定义域）。"""

    id: str
    subject: int
    name: str
    order: int


class FullRef(BaseModel):
    """manifest.full。"""

    url: str
    sha256: str
    bytes: int
    count: int


class DeltaRef(BaseModel):
    """manifest.deltas 元素。``from`` 是 Python 关键字，故用别名。"""

    model_config = ConfigDict(populate_by_name=True)

    from_: int = Field(alias="from")
    to: int
    url: str
    sha256: str
    bytes: int


class BundleRef(BaseModel):
    """manifest.bundle。"""

    url: str
    sha256: str
    bytes: int


class Manifest(BaseModel):
    """题库包清单（合同 §3）。``schema`` 与 BaseModel.schema 冲突，用别名。"""

    model_config = ConfigDict(populate_by_name=True)

    schema_: int = Field(1, alias="schema")
    bank_version: int
    released_at: str
    min_app_version_code: int
    chapters: list[Chapter]
    full: FullRef
    deltas: list[DeltaRef]
    media_base: str
    bundle: BundleRef


class DeletedRow(BaseModel):
    """增量文件中的删除行（合同 §2 末尾）。"""

    id: str
    deleted: bool = True


def sort_key(question: dict, chapter_order: dict[str, int]) -> tuple:
    """合同 §3 规定的稳定排序键：(subject, chapter.order, id)。"""
    return (
        int(question["subject"]),
        chapter_order.get(question["chapter_id"], 10**6),
        question["id"],
    )


def sort_questions(questions: list[dict], chapter_order: dict[str, int]) -> list[dict]:
    """按合同排序键排序（返回新列表）。"""
    return sorted(questions, key=lambda q: sort_key(q, chapter_order))


def vehicle_sort_key(name: str) -> tuple[int, str]:
    """车型按合同枚举顺序排（car→truck→bus→moto），未知车型排最后。"""
    try:
        return (VEHICLE_ORDER.index(name), "")
    except ValueError:
        return (len(VEHICLE_ORDER), name)

