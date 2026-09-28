"""内置 JSON Schema（合同 §2 题目 / §3 manifest），随包分发。"""

from __future__ import annotations

__all__ = ["question_schema_path", "manifest_schema_path"]


def _files():
    from importlib import resources

    return resources.files("jiakao_pipeline.schema")


def question_schema_path() -> str:
    """返回题目 Schema 的路径字符串。"""
    return str(_files().joinpath("question.schema.json"))


def manifest_schema_path() -> str:
    """返回 manifest Schema 的路径字符串。"""
    return str(_files().joinpath("manifest.schema.json"))
