"""增量计算：两份全量快照 → 增量记录（upsert 行 + 删除行）。

合同 §3「更新语义」要求客户端能把增量按顺序"应用到本地题库"，
``replaceAll=false``，因此增量必须**逐题可判定**：
- 本地没有的 id、或本地有但内容不同的 id → upsert 整题；
- 本地有、新版没有的 id → 删除行 ``{"id": ..., "deleted": true}``。

本模块只做纯计算，落盘与命名由 build_pack 负责。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from .models import DeletedRow, sort_questions
from .util import read_jsonl


@dataclass
class DeltaResult:
    """一次 diff 的结果。"""

    upserts: list[dict] = field(default_factory=list)
    deletes: list[str] = field(default_factory=list)
    changed_ids: list[str] = field(default_factory=list)

    @property
    def records(self) -> list[dict]:
        """增量文件内容：upsert 行在前，删除行在后（删除行按 id 排序）。

        删除行的形状由合同 §2 末规定：``{"id": …, "deleted": true}``
        （用 :class:`~jiakao_pipeline.models.DeletedRow` 保证字段名与顺序）。
        """
        return [*self.upserts, *(DeletedRow(id=qid).model_dump() for qid in self.deletes)]

    @property
    def is_empty(self) -> bool:
        """是否没有任何变化。"""
        return not self.upserts and not self.deletes

    def stats(self) -> dict:
        """统计（报告用）。"""
        return {"upserts": len(self.upserts), "deletes": len(self.deletes)}


def index_by_id(questions: list[dict]) -> dict[str, dict]:
    """按 id 建索引。"""
    return {q["id"]: q for q in questions}


def compute_delta(
    old_questions: list[dict],
    new_questions: list[dict],
    chapter_order: dict[str, int] | None = None,
) -> DeltaResult:
    """比较两版全量题库，产出增量。

    ``old_questions`` 为空表示没有上一版（首个版本，无需增量）。
    upsert 行按合同排序键输出，保证同输入字节级可复现。
    """
    order = chapter_order or {}
    old_index = index_by_id(old_questions)
    new_index = index_by_id(new_questions)

    upserts = [
        question
        for qid, question in new_index.items()
        if qid not in old_index or old_index[qid] != question
    ]
    deletes = sorted(qid for qid in old_index if qid not in new_index)
    changed = sorted(
        qid
        for qid, question in new_index.items()
        if qid in old_index and old_index[qid] != question
    )
    return DeltaResult(upserts=sort_questions(upserts, order), deletes=deletes, changed_ids=changed)


def delta_from_files(
    old_path: str | Path | None,
    new_path: str | Path,
    chapter_order: dict[str, int] | None = None,
) -> DeltaResult:
    """从两个 jsonl 文件算增量（``old_path`` 为 None 视为首个版本）。"""
    old = list(read_jsonl(old_path)) if old_path else []
    new = list(read_jsonl(new_path))
    return compute_delta(old, new, chapter_order)


def apply_records(
    bank: dict[str, dict],
    records,
    replace_all: bool = False,
) -> dict[str, dict]:
    """按合同 §3 的更新语义，把增量/全量应用到一份"本地题库"。

    这是**客户端行为的参考实现**：``tests/test_contract_compat.py`` 用它验证
    "v1 + 增量 == v2 全量"。：app 侧 04 模块的实现必须与之一致。
    """
    target = {} if replace_all else dict(bank)
    for record in records:
        if record.get("deleted") is True:
            target.pop(record["id"], None)
        else:
            target[record["id"]] = record
    return target
