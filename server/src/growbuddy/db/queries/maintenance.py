"""Retention queries: bulk deletes of expired rows."""

from __future__ import annotations

import sqlite3

from growbuddy.db.connection import Database
from growbuddy.db.queries.plants import placeholders


async def _delete(db: Database, sql: str, params: tuple[int, ...]) -> int:
    async with db.transaction() as conn, conn.execute(sql, params) as cur:
        return cur.rowcount


async def delete_readings_before(db: Database, cutoff: int) -> int:
    return await _delete(db, "DELETE FROM readings WHERE ts < ?", (cutoff,))


async def delete_events_before(db: Database, cutoff: int) -> int:
    return await _delete(db, "DELETE FROM events WHERE ts < ?", (cutoff,))


async def snapshots_before(db: Database, cutoff: int, limit: int) -> list[sqlite3.Row]:
    return await db.fetchall(
        "SELECT id, path, thumb_path FROM snapshots WHERE ts < ? ORDER BY ts LIMIT ?",
        (cutoff, limit),
    )


async def orphan_photos(db: Database, older_than: int, limit: int) -> list[sqlite3.Row]:
    """Photos not used by any log entry or plant thumbnail, uploaded before ``older_than``."""
    return await db.fetchall(
        """SELECT id, path, thumb_path FROM photos p
           WHERE p.created_ts < ?
             AND NOT EXISTS (SELECT 1 FROM log_photos lp WHERE lp.photo_id = p.id)
             AND NOT EXISTS (SELECT 1 FROM plants pl WHERE pl.thumbnail_photo_id = p.id)
           ORDER BY p.created_ts LIMIT ?""",
        (older_than, limit),
    )


async def delete_rows(db: Database, table: str, ids: list[int] | list[str]) -> None:
    if ids:
        await db.execute(f"DELETE FROM {table} WHERE id IN ({placeholders(len(ids))})", ids)


async def optimize(db: Database) -> None:
    await db.execute("PRAGMA optimize")
