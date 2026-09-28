"""稳定 ID 注册表（合同 §2：``id`` 稳定不复用，``s{科目}-{6位序号}``）。

设计要点
--------
1. **身份锚点**：优先用适配器给出的稳定源键 ``src_key``（如 CSV 的"编号"列）；
   没有源键时退回"规范化题干+选项"的内容哈希。前者让"改错别字"保持 id、``rev`` +1，
   后者让"换文件名/换行序但内容不变"仍然复用同一个 id。
2. **id 分配**：按科目单调自增（``s1-000001``…），计数器只增不减，**删除的 id 永不复用**。
3. **rev**：内容哈希变化才 +1；id 新建时 rev=1。
4. **退役**：本轮输入中消失的 id 标记 ``retired=true``，其源键/哈希从索引里摘除，
   于是"删掉又加回来"的题会拿到**新** id（合同要求不复用）。
5. 科目变更 = 换题（id 里含科目），因此匹配时要求 subject 一致，否则新分配 id。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable

from .util import dedupe_keep_order, ensure_dir, load_json, now_utc_iso, sha256_bytes

ID_RE = re.compile(r"^s([14])-(\d{6})$")
REGISTRY_SCHEMA = 1

#: 内容哈希里分隔题干与选项的哨兵字符，避免拼接歧义。
_HASH_SEP = "\x1f"


def format_id(subject: int, seq: int) -> str:
    """``(1, 7)`` → ``s1-000007``。"""
    if subject not in (1, 4):
        raise ValueError(f"subject 必须是 1 或 4，收到 {subject!r}")
    if seq < 1:
        raise ValueError("序号必须 ≥ 1")
    return f"s{subject}-{seq:06d}"


def parse_id(value: str) -> tuple[int, int]:
    """``s1-000007`` → ``(1, 7)``；非法则抛 ValueError。"""
    match = ID_RE.match(value or "")
    if not match:
        raise ValueError(f"id 非法（应形如 s1-000001）: {value!r}")
    return int(match.group(1)), int(match.group(2))


def content_hash(subject: int, stem: str, options: Iterable[dict]) -> str:
    """规范化题干 + 选项的内容哈希（含科目，用于身份判定）。

    选项只取 ``key`` 与 ``text``，与顺序无关（先按键排序），
    这样"管理员调整选项顺序"不会让题目变成新题。
    """
    opt_parts = sorted(f"{o.get('key', '')}={o.get('text', '')}" for o in options)
    payload = _HASH_SEP.join([str(subject), stem, *opt_parts])
    return sha256_bytes(payload.encode("utf-8"))


@dataclass
class AssignResult:
    """一次 id 分配的结果。"""

    id: str
    rev: int
    is_new: bool
    matched_by: str  # key | hash | new

    @property
    def changed(self) -> bool:
        """是否内容有变化（新建或 rev 提升）。"""
        return self.is_new or self.rev > 1


@dataclass
class IdRegistry:
    """``state/id_registry.json`` 的内存视图。"""

    path: Path
    data: dict[str, Any] = field(default_factory=dict)
    dirty: bool = False
    #: 本轮已分配出去的 id（同一份输入里出现两条完全相同的题时，第二条必须拿新 id）
    claimed: set[str] = field(default_factory=set)

    @classmethod
    def load(cls, path: str | Path) -> "IdRegistry":
        """读取注册表；不存在则新建空的。"""
        p = Path(path)
        raw = load_json(p)
        if not raw:
            raw = {
                "schema": REGISTRY_SCHEMA,
                "updated_at": now_utc_iso(),
                "counters": {},
                "by_key": {},
                "by_hash": {},
                "entries": {},
            }
        raw.setdefault("schema", REGISTRY_SCHEMA)
        raw.setdefault("counters", {})
        raw.setdefault("by_key", {})
        raw.setdefault("by_hash", {})
        raw.setdefault("entries", {})
        registry = cls(path=p, data=raw)
        registry._rebuild_indexes()
        return registry

    # ───────── 内部 ─────────

    def _rebuild_indexes(self) -> None:
        """保存/加载后重建索引（也顺手清理退役条目残留）。"""
        by_key: dict[str, str] = {}
        by_hash: dict[str, str] = {}
        counters: dict[str, int] = {}
        for qid, entry in self.data["entries"].items():
            subject, seq = parse_id(qid)
            counters[str(subject)] = max(counters.get(str(subject), 0), seq)
            if entry.get("retired"):
                continue
            if entry.get("key"):
                by_key[entry["key"]] = qid
            if entry.get("content_hash"):
                by_hash[entry["content_hash"]] = qid
        for key, value in self.data.get("counters", {}).items():
            counters[key] = max(counters.get(key, 0), int(value))
        self.data["counters"] = counters
        self.data["by_key"] = by_key
        self.data["by_hash"] = by_hash

    def _next_seq(self, subject: int) -> int:
        counters = self.data["counters"]
        seq = int(counters.get(str(subject), 0)) + 1
        counters[str(subject)] = seq
        return seq

    def _lookup(self, subject: int, key: str | None, c_hash: str) -> tuple[dict | None, str]:
        """按源键 → 内容哈希找已有条目，返回 (条目, 匹配方式)。

        已被本轮占用（``claimed``）的条目会被跳过 —— 否则同一份输入里两条内容相同的题
        会折叠成同一个 id。
        """
        entries = self.data["entries"]
        if key:
            qid = self.data["by_key"].get(key)
            if qid and qid in entries and not entries[qid].get("retired") and qid not in self.claimed:
                entry = entries[qid]
                if entry["subject"] == subject:
                    return entry, "key"
        qid = self.data["by_hash"].get(c_hash)
        if qid and qid in entries and not entries[qid].get("retired") and qid not in self.claimed:
            entry = entries[qid]
            if entry["subject"] == subject:
                return entry, "hash"
        return None, "new"

    # ───────── 对外 API ─────────

    def begin_batch(self) -> None:
        """开始一批分配（清空"本轮已占用"标记）。"""
        self.claimed = set()

    def assign(self, subject: int, key: str | None, c_hash: str) -> AssignResult:
        """为一道题分配/复用 id，并在内容变化时 ``rev`` +1。"""
        entries = self.data["entries"]
        entry, matched_by = self._lookup(subject, key, c_hash)
        if entry is None:
            seq = self._next_seq(subject)
            qid = format_id(subject, seq)
            entries[qid] = {
                "id": qid,
                "subject": subject,
                "rev": 1,
                "key": key or None,
                "content_hash": c_hash,
                "retired": False,
            }
            if key:
                self.data["by_key"][key] = qid
            self.data["by_hash"][c_hash] = qid
            self.claimed.add(qid)
            self.dirty = True
            return AssignResult(qid, 1, True, "new")

        self.claimed.add(entry["id"])

        changed = entry.get("content_hash") != c_hash
        if changed:
            old_hash = entry.get("content_hash")
            if old_hash and self.data["by_hash"].get(old_hash) == entry["id"]:
                del self.data["by_hash"][old_hash]
            entry["content_hash"] = c_hash
            entry["rev"] = int(entry.get("rev", 1)) + 1
            self.data["by_hash"][c_hash] = entry["id"]
            self.dirty = True
        # 源键可能变化（例如换了一列做主键），保持 latest 映射可用
        if key and entry.get("key") != key:
            old_key = entry.get("key")
            if old_key and self.data["by_key"].get(old_key) == entry["id"]:
                del self.data["by_key"][old_key]
            entry["key"] = key
            self.data["by_key"][key] = entry["id"]
            self.dirty = True
        return AssignResult(entry["id"], int(entry.get("rev", 1)), False, matched_by)

    def finalize(self, present_ids: Iterable[str], subjects: Iterable[int]) -> list[str]:
        """本轮未出现的 id 退役（仅限本轮处理到的科目，避免局部导入误杀）。"""
        present = set(present_ids)
        scope = {int(s) for s in subjects}
        retired: list[str] = []
        for qid, entry in self.data["entries"].items():
            if entry.get("retired") or entry["subject"] not in scope or qid in present:
                continue
            entry["retired"] = True
            if entry.get("key") and self.data["by_key"].get(entry["key"]) == qid:
                del self.data["by_key"][entry["key"]]
            if entry.get("content_hash") and self.data["by_hash"].get(entry["content_hash"]) == qid:
                del self.data["by_hash"][entry["content_hash"]]
            retired.append(qid)
            self.dirty = True
        retired.sort()
        return retired

    def entry(self, qid: str) -> dict | None:
        """按 id 取条目。"""
        return self.data["entries"].get(qid)

    def stats(self) -> dict:
        """注册表统计（报告用）。"""
        entries = self.data["entries"].values()
        return {
            "total": len(self.data["entries"]),
            "active": sum(1 for e in entries if not e.get("retired")),
            "retired": sum(1 for e in entries if e.get("retired")),
            "counters": dict(self.data["counters"]),
        }

    def save(self) -> Path:
        """写回 JSON（键排序，便于人工 diff）。"""
        self._rebuild_indexes()
        self.data["updated_at"] = now_utc_iso()
        payload = {
            "schema": self.data["schema"],
            "updated_at": self.data["updated_at"],
            "counters": {k: self.data["counters"][k] for k in sorted(self.data["counters"])},
            "by_key": {k: self.data["by_key"][k] for k in sorted(self.data["by_key"])},
            "by_hash": {k: self.data["by_hash"][k] for k in sorted(self.data["by_hash"])},
            "entries": {k: self.data["entries"][k] for k in sorted(self.data["entries"])},
        }
        import json

        ensure_dir(self.path.parent)
        tmp = json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
        from .util import atomic_write_text

        atomic_write_text(self.path, tmp)
        self.dirty = False
        return self.path
