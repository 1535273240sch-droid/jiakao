"""合同 §3「更新语义」兼容性测试 —— 05 与 04 的唯一接口。

按合同自行实现一个**最小客户端**（就是 04 的 ``BankUpdater`` 逻辑）：
1. 本地版本 == 远端版本 → 已最新；
2. 存在连续增量链 本地→远端 且总字节 < 全量字节 → 依次应用增量（replaceAll=false）；
   否则应用全量（replaceAll=true，先清空题表）；
3. 题目导入在单事务内完成，成功后才写 bank_version。

验证：``v1 + 增量v1→v2 == v2 全量``（逐题逐字段），并对媒体引用做同样核对。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

from jiakao_pipeline.diff import apply_records
from jiakao_pipeline.util import gunzip_bytes

from conftest import DEFAULT_ROWS, Project, row


@dataclass
class Client:
    """假装是 App：只认 dist 里的静态文件 + 一个本地题库。"""

    dist: Path
    bank: dict[str, dict] = field(default_factory=dict)
    bank_version: int = 0
    last_path: str = ""

    def manifest(self) -> dict:
        """拉远端 manifest（相当于一次 HTTP GET）。"""
        return json.loads((self.dist / "manifest.json").read_text(encoding="utf-8"))

    def fetch_full(self, version: int) -> list[dict]:
        """下载并解压全量快照。"""
        raw = (self.dist / "full" / f"bank-v{version}.jsonl.gz").read_bytes()
        return [json.loads(line) for line in gunzip_bytes(raw).decode("utf-8").splitlines() if line]

    def fetch_delta(self, from_version: int, to_version: int) -> list[dict]:
        """下载并解压增量。"""
        raw = (self.dist / "delta" / f"v{from_version}-v{to_version}.jsonl.gz").read_bytes()
        return [json.loads(line) for line in gunzip_bytes(raw).decode("utf-8").splitlines() if line]

    def pin(self, version: int) -> None:
        """把本地题库固定到某个版本（模拟"用户已经在用 v1"）。"""
        self.bank = {item["id"]: item for item in self.fetch_full(version)}
        self.bank_version = version

    def find_chain(self, manifest: dict) -> list[dict] | None:
        """按合同 §3.2 找"本地 → 远端"的连续增量链。"""
        target = manifest["bank_version"]
        by_to = {item["to"]: item for item in manifest["deltas"]}
        chain: list[dict] = []
        cursor = target
        while cursor in by_to:
            item = by_to[cursor]
            chain.append(item)
            cursor = item["from"]
        if not chain or cursor != self.bank_version:
            return None
        chain.reverse()
        return chain

    def update(self) -> str:
        """执行一次更新，返回采用的路径（up_to_date / delta / full）。"""
        manifest = self.manifest()
        if manifest["bank_version"] == self.bank_version:
            return "up_to_date"

        chain = self.find_chain(manifest)
        if chain is not None:
            total = sum(item["bytes"] for item in chain)
            if total < manifest["full"]["bytes"]:
                records: list[dict] = []
                for item in chain:
                    records.extend(self.fetch_delta(item["from"], item["to"]))
                self.bank = apply_records(self.bank, records, replace_all=False)  # 单事务
                self.bank_version = manifest["bank_version"]
                self.last_path = "delta"
                return "delta"

        self.bank = apply_records({}, self.fetch_full(manifest["bank_version"]), replace_all=True)
        self.bank_version = manifest["bank_version"]
        self.last_path = "full"
        return "full"

    def import_bundle(self, version: int) -> None:
        """离线整包导入（合同 §3 的 bundle）。"""
        import zipfile

        with zipfile.ZipFile(self.dist / "bundle" / f"bundle-v{version}.zip") as archive:
            records = [
                json.loads(line)
                for line in archive.read("bank.jsonl").decode("utf-8").splitlines()
                if line.strip()
            ]
            inside = json.loads(archive.read("manifest.json").decode("utf-8"))
        self.bank = apply_records({}, records, replace_all=True)
        self.bank_version = inside["bank_version"]
        self.last_path = "bundle"


def make_v2_rows() -> list[dict]:
    """v2：改一题（rev+1）、删一题、加一题。"""
    rows = [dict(item) for item in DEFAULT_ROWS]
    rows[0]["题干"] = DEFAULT_ROWS[0]["题干"].replace("禁止停车", "禁止临时停车")
    rows[0]["解析"] = "红圈红斜杠表示禁止停放。"
    del rows[1]  # 删掉 T002
    rows.append(row("T005", "科目四", "单选", "伤员急救知识", "对伤员进行止血包扎时应当注意什么？",
                    ["保持包扎松紧适度", "越紧越好", "不用消毒", "直接撒药粉"], "A", tags="急救"))
    return rows


def build_two_versions(project: Project) -> None:
    """先造 v1，再造 v2（正常流程：改数据 → import/normalize/media/build）。"""
    project.dataset(list(DEFAULT_ROWS))
    project.run()
    first = project.build()
    assert first.bank_version == 1

    project.dataset(make_v2_rows())
    project.run()
    second = project.build()
    assert second.bank_version == 2
    assert [(d["from"], d["to"]) for d in second.deltas] == [(1, 2)]


def test_delta_path_equals_full_bank(tmp_path: Path) -> None:
    """核心断言：v1 应用增量 → 逐字段等于 v2 全量。"""
    project = Project(tmp_path)
    build_two_versions(project)

    fresh = Client(project.dist)
    assert fresh.update() == "full"          # 本地为空 → 全量（replaceAll=true）
    assert fresh.bank == project.full_bank(2)

    client = Client(project.dist)
    client.pin(1)                            # 模拟"用户已经在用 v1"
    assert client.update() == "delta"
    assert client.bank_version == 2
    assert client.bank == project.full_bank(2)


def test_compatibility_holds_field_by_field(tmp_path: Path) -> None:
    """逐题逐字段核对（含媒体 sha256/kind/w/h/bytes、rev、answer 顺序）。"""
    project = Project(tmp_path)
    build_two_versions(project)

    client = Client(project.dist)
    client.pin(1)
    assert client.update() == "delta"
    expected = project.full_bank(2)
    assert set(client.bank) == set(expected)
    for qid, record in expected.items():
        assert client.bank[qid] == record, f"{qid} 字段不一致"
        assert client.bank[qid]["media"] == record["media"]
    # 改动的那题必须 rev+1 且 id 不变
    changed = expected[[k for k, v in expected.items() if "禁止临时停车" in v["stem"]][0]]
    assert changed["rev"] == 2


def test_full_path_clears_local_bank(tmp_path: Path) -> None:
    """全量路径（replaceAll=true）会清空题表：本地脏数据必须消失。"""
    project = Project(tmp_path)
    build_two_versions(project)

    client = Client(project.dist)
    client.bank = {"s9-999999": {"id": "s9-999999", "subject": 1}}  # 本地脏数据
    client.bank_version = 0
    assert client.update() == "full"
    assert client.bank == project.full_bank(2)
    assert "s9-999999" not in client.bank


def test_up_to_date_is_noop(tmp_path: Path) -> None:
    """本地版本 == 远端版本 → 已最新，不动题库。"""
    project = Project(tmp_path)
    build_two_versions(project)
    client = Client(project.dist)
    client.pin(2)
    before = dict(client.bank)
    assert client.update() == "up_to_date"
    assert client.bank == before


def test_chain_byte_rule_falls_back_to_full(tmp_path: Path) -> None:
    """增量链总字节 ≥ 全量字节时，客户端按合同改用全量（replaceAll=true）。"""
    project = Project(tmp_path)
    build_two_versions(project)

    class ShrunkClient(Client):
        """故意把全量报得很小，验证 §3.2 的字节比较分支。"""

        def manifest(self) -> dict:
            raw = super().manifest()
            raw["full"]["bytes"] = 1
            return raw

    client = ShrunkClient(project.dist)
    client.pin(1)
    assert client.update() == "full"
    assert client.bank == project.full_bank(2)


def many_rows(count: int = 40) -> list[dict]:
    """合成一批题目（用于验证"增量比全量便宜"才会走增量）。"""
    rows = []
    for index in range(count):
        subject = 1 if index % 3 else 4
        rows.append(row(
            f"B{index:03d}",
            "科目一" if subject == 1 else "科目四",
            "单选",
            "交通信号" if subject == 1 else "伤员急救知识",
            f"合成题干 {index}：用于增量链验证的题目内容（自编，非真题）。",
            [f"选项甲{index}", f"选项乙{index}", f"选项丙{index}", f"选项丁{index}"],
            "ACBD"[index % 4],
        ))
    return rows


def test_three_versions_chain(tmp_path: Path) -> None:
    """连续两个增量：v1 → v2 → v3 逐级应用后 == v3 全量。"""
    project = Project(tmp_path)
    rows = many_rows(40)
    project.dataset(rows)
    project.run()
    project.build()

    rows2 = [dict(item) for item in rows]
    rows2[0]["题干"] += "（第一次修订）"
    del rows2[5]
    rows2.append(row("B900", "科目一", "判断", "交通信号", "新增的判断题。", [], "正确"))
    project.dataset(rows2)
    project.run()
    project.build()

    rows3 = [dict(item) for item in rows2]
    rows3[1]["题干"] += "（第二次修订）"
    project.dataset(rows3)
    project.run()
    third = project.build()
    assert third.bank_version == 3
    assert [(d["from"], d["to"]) for d in third.deltas] == [(1, 2), (2, 3)]

    client = Client(project.dist)
    client.pin(1)
    # 链必须连续（能一路走回本地版本），且比全量便宜 → 一次 update 走完两个增量
    chain = client.find_chain(client.manifest())
    assert chain is not None
    assert [(item["from"], item["to"]) for item in chain] == [(1, 2), (2, 3)]
    assert sum(item["bytes"] for item in chain) < client.manifest()["full"]["bytes"]

    assert client.update() == "delta"
    assert client.bank_version == 3
    assert client.bank == project.full_bank(3)


def test_chain_break_falls_back_to_full(tmp_path: Path) -> None:
    """增量链断了（缺一环）必须回退全量，不能错误地局部应用。"""
    project = Project(tmp_path)
    project.dataset(list(DEFAULT_ROWS))
    project.run()
    project.build()

    rows = many_rows(40)
    project.dataset(rows)
    project.run()
    project.build()

    rows = [dict(item) for item in rows]
    rows[0]["题干"] += "（修订）"
    project.dataset(rows)
    project.run()
    project.build()

    # 模拟发布端只保留了 v2-v3（v1-v2 已被清理），manifest 里也就没有它了
    manifest = project.manifest()
    manifest["deltas"] = [item for item in manifest["deltas"] if item["from"] != 1]
    assert [item["from"] for item in manifest["deltas"]] == [2]
    (project.dist / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    client = Client(project.dist)
    client.pin(1)
    assert client.find_chain(client.manifest()) is None
    assert client.update() == "full"
    assert client.bank == project.full_bank(3)


def test_bundle_import_matches_full(tmp_path: Path) -> None:
    """离线整包导入（bundle）结果 == 同版本全量。"""
    project = Project(tmp_path)
    build_two_versions(project)
    client = Client(project.dist)
    client.import_bundle(2)
    assert client.bank == project.full_bank(2)
    assert client.bank_version == 2
