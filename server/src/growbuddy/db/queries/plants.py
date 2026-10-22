"""Plant queries (milestones come from the plant_milestones view)."""

from __future__ import annotations

import sqlite3
from collections.abc import Iterable, Mapping
from typing import Any

from growbuddy.db.connection import Database
from growbuddy.domain.phase import Milestone

_SELECT = """
SELECT p.*, s.name AS strain_name, s.vendor AS strain_vendor, s.category AS strain_category,
       s.flowering_days AS strain_flowering_days
FROM plants p LEFT JOIN strains s ON s.id = p.strain_id
"""
UPDATABLE = frozenset(
    {"nickname", "strain_id", "is_autoflower", "sensor_id", "thumbnail_photo_id", "archived"}
)


def placeholders(n: int) -> str:
    return ",".join("?" * n)


async def get_plant(db: Database, plant_id: str) -> sqlite3.Row | None:
    return await db.fetchone(f"{_SELECT} WHERE p.id = ?", (plant_id,))


async def list_plants(
    db: Database, include_archived: bool = False, sensor_id: int | None = None
) -> list[sqlite3.Row]:
    where, params = [], []
    if not include_archived:
        where.append("p.archived = 0")
    if sensor_id is not None:
        where.append("p.sensor_id = ?")
        params.append(sensor_id)
    clause = f"WHERE {' AND '.join(where)}" if where else ""
    return await db.fetchall(f"{_SELECT} {clause} ORDER BY p.created_ts, p.id", params)


async def existing_ids(db: Database, plant_ids: Iterable[str]) -> set[str]:
    ids = list(plant_ids)
    if not ids:
        return set()
    rows = await db.fetchall(f"SELECT id FROM plants WHERE id IN ({placeholders(len(ids))})", ids)
    return {r["id"] for r in rows}


async def locations(db: Database, plant_ids: Iterable[str]) -> dict[str, int | None]:
    ids = list(plant_ids)
    if not ids:
        return {}
    rows = await db.fetchall(
        f"SELECT id, sensor_id FROM plants WHERE id IN ({placeholders(len(ids))})", ids
    )
    return {r["id"]: r["sensor_id"] for r in rows}


async def milestones(db: Database, plant_ids: Iterable[str]) -> dict[str, dict[Milestone, int]]:
    ids = list(plant_ids)
    result: dict[str, dict[Milestone, int]] = {pid: {} for pid in ids}
    if not ids:
        return result
    rows = await db.fetchall(
        f"""SELECT plant_id, type, ts FROM plant_milestones
            WHERE plant_id IN ({placeholders(len(ids))})""",
        ids,
    )
    for r in rows:
        result[r["plant_id"]][Milestone(r["type"])] = r["ts"]
    return result


async def insert_plant(
    db: Database,
    plant_id: str,
    nickname: str,
    strain_id: int | None,
    is_autoflower: bool,
    sensor_id: int | None,
    thumbnail_photo_id: str | None,
    created_ts: int,
) -> None:
    await db.execute(
        """INSERT INTO plants (id, nickname, strain_id, is_autoflower, sensor_id,
               thumbnail_photo_id, created_ts)
           VALUES (?, ?, ?, ?, ?, ?, ?)""",
        (plant_id, nickname, strain_id, is_autoflower, sensor_id, thumbnail_photo_id, created_ts),
    )


async def update_plant(db: Database, plant_id: str, changes: Mapping[str, Any]) -> None:
    unknown = set(changes) - UPDATABLE
    if unknown:
        raise ValueError(f"not updatable: {sorted(unknown)}")
    if not changes:
        return
    assignments = ", ".join(f"{column} = ?" for column in changes)
    await db.execute(f"UPDATE plants SET {assignments} WHERE id = ?", [*changes.values(), plant_id])


async def delete_plant(db: Database, plant_id: str) -> None:
    """Delete the plant and the log entries that belong to it alone."""
    async with db.transaction() as conn:
        await conn.execute(
            """DELETE FROM logs WHERE id IN (
                   SELECT log_id FROM log_plants WHERE plant_id = ?
                   EXCEPT
                   SELECT log_id FROM log_plants WHERE plant_id != ?)""",
            (plant_id, plant_id),
        )
        await conn.execute("DELETE FROM plants WHERE id = ?", (plant_id,))


async def strain_exists(db: Database, strain_id: int) -> bool:
    return bool(
        await db.fetchval("SELECT EXISTS (SELECT 1 FROM strains WHERE id = ?)", (strain_id,))
    )
