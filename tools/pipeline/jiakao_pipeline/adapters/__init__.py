"""题库适配器：把各种原始格式转成统一的 :class:`RawQuestion`。"""

from __future__ import annotations

from .base import (
    ADAPTERS,
    Adapter,
    AdapterError,
    RawMedia,
    RawQuestion,
    get_adapter,
    iter_raw_questions,
    load_adapters,
    register,
)

__all__ = [
    "ADAPTERS",
    "Adapter",
    "AdapterError",
    "RawMedia",
    "RawQuestion",
    "get_adapter",
    "iter_raw_questions",
    "load_adapters",
    "register",
]
