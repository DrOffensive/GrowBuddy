"""Photo metadata queries (files live on disk, see journal/photos.py)."""

from __future__ import annotations

import sqlite3
from collections.abc import Iterable

from growbuddy.db.connection import Database
from growbuddy.db.queries.plants import placeholders


async def get_photo(db: Database, photo_id: str) -> sqlite3.Row | None:
    return await db.fetchone("SELECT * FROM photos WHERE id = ?", (photo_id,))


async def existing_ids(db: Database, photo_ids: Iterable[str]) -> set[str]:
    ids = list(photo_ids)
    if not ids:
        return set()
    rows = await db.fetchall(f"SELECT id FROM photos WHERE id IN ({placeholders(len(ids))})", ids)
    return {r["id"] for r in rows}


async def insert_photo(
    db: Database,
    photo_id: str,
    ts: int,
    path: str,
    thumb_path: str,
    width: int,
    height: int,
    size_bytes: int,
    created_ts: int,
) -> None:
    await db.execute(
        """INSERT INTO photos (id, ts, path, thumb_path, width, height, size_bytes, created_ts)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
        (photo_id, ts, path, thumb_path, width, height, size_bytes, created_ts),
    )


async def delete_photo(db: Database, photo_id: str) -> None:
    await db.execute("DELETE FROM photos WHERE id = ?", (photo_id,))


async def ids_in_use(db: Database, photo_ids: Iterable[str]) -> set[str]:
    """Photos referenced by a log entry or as a plant thumbnail."""
    ids = list(photo_ids)
    if not ids:
        return set()
    marks = placeholders(len(ids))
    rows = await db.fetchall(
        f"""SELECT photo_id AS id FROM log_photos WHERE photo_id IN ({marks})
            UNION SELECT thumbnail_photo_id FROM plants WHERE thumbnail_photo_id IN ({marks})""",
        [*ids, *ids],
    )
    return {r["id"] for r in rows}
