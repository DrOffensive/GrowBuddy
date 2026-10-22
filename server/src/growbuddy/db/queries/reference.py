"""Reference data queries: strains, nutrient lines, feeding schedules, phase templates."""

from __future__ import annotations

import sqlite3
from collections.abc import Mapping
from typing import Any

from growbuddy.db.connection import Database
from growbuddy.domain.phase import Phase

STRAIN_FIELDS = (
    "name", "vendor", "category", "shop_category", "thc", "cbd", "flowering_days",
    "yield_indoor", "height", "url", "image_url", "description",
)  # fmt: skip
FACET_FIELDS = ("category", "vendor", "shop_category", "flowering_days", "thc")
PHASE_RANGE_FIELDS = (
    "enforce_limits", "temp_min", "temp_max", "humidity_min", "humidity_max", "vpd_min", "vpd_max",
)  # fmt: skip


def _strain_filters(q: str | None, filters: Mapping[str, str | None]) -> tuple[str, list[Any]]:
    where: list[str] = []
    params: list[Any] = []
    if q:
        where.append("(name LIKE ? OR vendor LIKE ?)")
        params += [f"%{q}%", f"%{q}%"]
    for column, value in filters.items():
        if value is not None:
            where.append(f"{column} = ?")
            params.append(value)
    return (f"WHERE {' AND '.join(where)}" if where else ""), params


async def search_strains(
    db: Database,
    *,
    q: str | None,
    filters: Mapping[str, str | None],
    limit: int,
    offset: int,
) -> tuple[list[sqlite3.Row], int]:
    clause, params = _strain_filters(q, filters)
    total = int(await db.fetchval(f"SELECT COUNT(*) FROM strains {clause}", params))
    rows = await db.fetchall(
        f"SELECT * FROM strains {clause} ORDER BY name COLLATE NOCASE, vendor LIMIT ? OFFSET ?",
        [*params, limit, offset],
    )
    return rows, total


async def strain_facets(db: Database) -> dict[str, list[str]]:
    facets: dict[str, list[str]] = {}
    for column in FACET_FIELDS:
        rows = await db.fetchall(
            f"""SELECT DISTINCT {column} AS v FROM strains
                WHERE {column} IS NOT NULL AND {column} != '' ORDER BY v COLLATE NOCASE"""
        )
        facets[column] = [r["v"] for r in rows]
    return facets


async def get_strain(db: Database, strain_id: int) -> sqlite3.Row | None:
    return await db.fetchone("SELECT * FROM strains WHERE id = ?", (strain_id,))


async def strain_exists_named(
    db: Database, name: str, vendor: str | None, exclude_id: int | None = None
) -> bool:
    return bool(
        await db.fetchval(
            """SELECT EXISTS (SELECT 1 FROM strains WHERE name = ? AND vendor IS ?
                   AND id IS NOT ?)""",
            (name, vendor, exclude_id),
        )
    )


async def insert_strain(db: Database, fields: Mapping[str, Any]) -> int:
    """A user-added strain (``custom = 1``)."""
    columns = [c for c in STRAIN_FIELDS if c in fields]
    return await db.execute(
        f"INSERT INTO strains ({', '.join(columns)}, custom)"
        f" VALUES ({', '.join('?' * len(columns))}, 1)",
        [fields[c] for c in columns],
    )


async def strain_in_use(db: Database, strain_id: int) -> bool:
    return bool(
        await db.fetchval("SELECT EXISTS (SELECT 1 FROM plants WHERE strain_id = ?)", (strain_id,))
    )


async def delete_strain(db: Database, strain_id: int) -> None:
    await db.execute("DELETE FROM strains WHERE id = ?", (strain_id,))


async def update_strain(db: Database, strain_id: int, changes: Mapping[str, Any]) -> None:
    columns = [c for c in STRAIN_FIELDS if c in changes]
    if columns:
        assignments = ", ".join(f"{c} = ?" for c in columns)
        await db.execute(
            f"UPDATE strains SET {assignments} WHERE id = ?",
            [*(changes[c] for c in columns), strain_id],
        )


async def nutrient_lines(db: Database) -> list[tuple[sqlite3.Row, list[sqlite3.Row]]]:
    lines = await db.fetchall("SELECT * FROM nutrient_lines ORDER BY brand COLLATE NOCASE")
    products = await db.fetchall("SELECT * FROM nutrient_products ORDER BY line_id, id")
    by_line: dict[int, list[sqlite3.Row]] = {}
    for p in products:
        by_line.setdefault(p["line_id"], []).append(p)
    return [(line, by_line.get(line["id"], [])) for line in lines]


async def feeding_schedules(db: Database) -> list[sqlite3.Row]:
    return await db.fetchall(
        """SELECT s.*, COUNT(w.week) AS weeks FROM feeding_schedules s
           LEFT JOIN feeding_schedule_weeks w ON w.schedule_id = s.id
           GROUP BY s.id ORDER BY s.display_name COLLATE NOCASE"""
    )


async def feeding_schedule(
    db: Database, schedule_id: int
) -> tuple[sqlite3.Row, list[sqlite3.Row]] | None:
    header = await db.fetchone("SELECT * FROM feeding_schedules WHERE id = ?", (schedule_id,))
    if header is None:
        return None
    weeks = await db.fetchall(
        "SELECT * FROM feeding_schedule_weeks WHERE schedule_id = ? ORDER BY week", (schedule_id,)
    )
    return header, weeks


async def phase_ranges(db: Database) -> list[sqlite3.Row]:
    """In growth order (Phase enum), not alphabetical."""
    order = {phase.value: i for i, phase in enumerate(Phase)}
    rows = await db.fetchall("SELECT * FROM phase_ranges")
    return sorted(rows, key=lambda r: order.get(r["phase"], len(order)))


async def get_phase_range(db: Database, phase: str) -> sqlite3.Row | None:
    return await db.fetchone("SELECT * FROM phase_ranges WHERE phase = ?", (phase,))


async def put_phase_range(db: Database, phase: str, values: Mapping[str, Any]) -> None:
    assignments = ", ".join(f"{c} = ?" for c in PHASE_RANGE_FIELDS)
    await db.execute(
        f"UPDATE phase_ranges SET {assignments} WHERE phase = ?",
        [*(values[c] for c in PHASE_RANGE_FIELDS), phase],
    )


async def save_custom_schedule(
    db: Database,
    schedule_id: int | None,
    profile_key: str,
    display_name: str,
    substrate: str | None,
    weeks: list[tuple[Any, ...]],
) -> int:
    """Creates (``schedule_id`` None) or replaces a custom schedule with its weeks.

    ``weeks`` rows are (week, phase, nutrients, ph, ec, environment, notes), JSON already encoded.
    """
    async with db.transaction() as conn:
        if schedule_id is None:
            async with conn.execute(
                """INSERT INTO feeding_schedules (profile_key, display_name, substrate, custom)
                   VALUES (?, ?, ?, 1)""",
                (profile_key, display_name, substrate),
            ) as cur:
                assert cur.lastrowid is not None
                schedule_id = cur.lastrowid
        else:
            await conn.execute(
                "UPDATE feeding_schedules SET display_name = ?, substrate = ? WHERE id = ?",
                (display_name, substrate, schedule_id),
            )
            await conn.execute(
                "DELETE FROM feeding_schedule_weeks WHERE schedule_id = ?", (schedule_id,)
            )
        await conn.executemany(
            """INSERT INTO feeding_schedule_weeks
                   (schedule_id, week, phase, nutrients, ph, ec, environment, notes)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            [(schedule_id, *w) for w in weeks],
        )
    return schedule_id


async def delete_schedule(db: Database, schedule_id: int) -> None:
    await db.execute("DELETE FROM feeding_schedules WHERE id = ?", (schedule_id,))
