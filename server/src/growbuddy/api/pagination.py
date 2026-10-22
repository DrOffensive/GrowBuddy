"""Cursor pagination helpers: ?limit= (1..1000) and ?before=<ts>, newest first."""

from __future__ import annotations

from collections.abc import Callable
from typing import Annotated, TypeVar

from fastapi import Query

T = TypeVar("T")

Limit = Annotated[int, Query(ge=1, le=1000)]


def split_page(
    records: list[T], limit: int, ts_of: Callable[[T], int]
) -> tuple[list[T], int | None]:
    """``records`` was fetched with ``limit + 1``; returns (items, next_before)."""
    items = records[:limit]
    return items, (ts_of(items[-1]) if len(records) > limit else None)
