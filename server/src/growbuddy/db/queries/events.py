"""System event log queries (written by services/events.py)."""

from __future__ import annotations

import sqlite3
from typing import Any

from growbuddy.db.connection import Database


async def list_events(
    db: Database,
    *,
    limit: int,
    before: int | None = None,
    level: str | None = None,
    source: str | None = None,
) -> list[sqlite3.Row]:
    where: list[str] = []
    params: list[Any] = []
    for clause, value in (("ts < ?", before), ("level = ?", level), ("source = ?", source)):
        if value is not None:
            where.append(clause)
            params.append(value)
    sql = f"SELECT * FROM events {'WHERE ' + ' AND '.join(where) if where else ''}"
    return await db.fetchall(f"{sql} ORDER BY ts DESC, id DESC LIMIT ?", [*params, limit])
