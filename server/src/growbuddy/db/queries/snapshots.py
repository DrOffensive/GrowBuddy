"""Camera snapshot metadata queries (files live on disk, see camera/storage.py)."""

from __future__ import annotations

import sqlite3
from typing import Any

from growbuddy.db.connection import Database


async def insert_snapshot(
    db: Database,
    ts: int,
    path: str,
    thumb_path: str,
    width: int,
    height: int,
    size_bytes: int,
    kind: str,
) -> int:
    return await db.execute(
        """INSERT INTO snapshots (ts, path, thumb_path, width, height, size_bytes, kind)
           VALUES (?, ?, ?, ?, ?, ?, ?)""",
        (ts, path, thumb_path, width, height, size_bytes, kind),
    )


async def get_snapshot(db: Database, snapshot_id: int) -> sqlite3.Row | None:
    return await db.fetchone("SELECT * FROM snapshots WHERE id = ?", (snapshot_id,))


async def list_snapshots(
    db: Database,
    *,
    limit: int,
    before: int | None = None,
    from_ts: int | None = None,
    to_ts: int | None = None,
    kind: str | None = None,
) -> list[sqlite3.Row]:
    """Newest first; ``before`` is an exclusive ts cursor."""
    where: list[str] = []
    params: list[Any] = []
    for clause, value in (
        ("ts < ?", before),
        ("ts >= ?", from_ts),
        ("ts <= ?", to_ts),
        ("kind = ?", kind),
    ):
        if value is not None:
            where.append(clause)
            params.append(value)
    sql = f"SELECT * FROM snapshots {'WHERE ' + ' AND '.join(where) if where else ''}"
    return await db.fetchall(f"{sql} ORDER BY ts DESC, id DESC LIMIT ?", [*params, limit])


async def latest_snapshot_ts(db: Database) -> int | None:
    value = await db.fetchval("SELECT MAX(ts) FROM snapshots")
    return None if value is None else int(value)
