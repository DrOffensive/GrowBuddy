"""Seeds reference tables from growbuddy/seed/*.json on startup.

Strains and phase ranges are seeded only into an empty table, so edits made
through the API are never overwritten on restart. Nutrient lines and built-in
feeding schedules (``custom = 0``) are synced on every start instead: new lines,
products and schedules in the seed files reach existing installs, and changed
built-in schedules are updated. Custom schedules are never touched. The file
formats are described in growbuddy/seed/README.md.
"""

from __future__ import annotations

import json
import logging
from importlib import resources
from typing import Any

import aiosqlite

from growbuddy.db.connection import Database

log = logging.getLogger(__name__)


def _load(name: str) -> Any:
    return json.loads(resources.files("growbuddy.seed").joinpath(name).read_text(encoding="utf-8"))


async def _is_empty(conn: aiosqlite.Connection, table: str) -> bool:
    async with conn.execute(f"SELECT EXISTS (SELECT 1 FROM {table})") as cur:
        row = await cur.fetchone()
    return not (row and row[0])


async def _seed_strains(conn: aiosqlite.Connection) -> int:
    strains = _load("strains.json")["strains"]
    await conn.executemany(
        """INSERT OR IGNORE INTO strains (name, vendor, category, shop_category, thc, cbd,
               flowering_days, yield_indoor, height, url, image_url, description)
           VALUES (:name, :vendor, :category, :shop_category, :thc, :cbd,
               :flowering_days, :yield_indoor, :height, :url, :image_url, :description)""",
        strains,
    )
    return len(strains)


async def _sync_nutrient_lines(conn: aiosqlite.Connection) -> int:
    """Adds missing lines (by brand) and products (by name). Returns how many were added."""
    added = 0
    for line in _load("nutrient_lines.json")["lines"]:
        async with conn.execute(
            "INSERT OR IGNORE INTO nutrient_lines (brand, description) VALUES (?, ?)",
            (line["brand"], line.get("description")),
        ) as cur:
            added += cur.rowcount
        async with conn.execute(
            "SELECT id FROM nutrient_lines WHERE brand = ?", (line["brand"],)
        ) as cur:
            row = await cur.fetchone()
        assert row is not None
        for p in line["products"]:
            async with conn.execute(
                """INSERT OR IGNORE INTO nutrient_products (line_id, name, description, unit)
                   VALUES (?, ?, ?, ?)""",
                (row[0], p["name"], p.get("description"), p.get("unit") or "ml/L"),
            ) as cur:
                added += cur.rowcount
    return added


def _schedule_environment(env: dict[str, Any]) -> dict[str, Any]:
    return {
        "temp_day": env.get("tempDay"),
        "temp_night": env.get("tempNight"),
        "humidity": env.get("humidity"),
        "light_hours": env.get("lightHours"),
    }


def schedule_week_rows(schedule: dict[str, Any]) -> list[tuple[Any, ...]]:
    """(week, phase, nutrients, ph, ec, environment, notes) rows of a seed schedule, as stored."""
    return [
        (
            week["week"],
            week["phase"],
            json.dumps(week.get("nutrients", {})),
            json.dumps(week.get("pH")),
            json.dumps(week.get("EC")),
            json.dumps(_schedule_environment(week.get("environment", {}))),
            week.get("notes"),
        )
        for week in schedule["weekEntries"]
    ]


async def _sync_feeding_schedules(conn: aiosqlite.Connection) -> int:
    """Inserts or updates the built-in schedules. Returns how many changed."""
    changed = 0
    for schedule in _load("feeding_schedules.json")["schedules"]:
        header = (schedule["displayName"], schedule.get("substrate"))
        weeks = schedule_week_rows(schedule)
        async with conn.execute(
            """SELECT id, display_name, substrate, custom FROM feeding_schedules
               WHERE profile_key = ?""",
            (schedule["profileId"],),
        ) as cur:
            row = await cur.fetchone()
        if row is None:
            async with conn.execute(
                """INSERT INTO feeding_schedules (profile_key, display_name, substrate)
                   VALUES (?, ?, ?)""",
                (schedule["profileId"], *header),
            ) as cur:
                schedule_id = cur.lastrowid
        else:
            schedule_id = row[0]
            if row[3]:  # a custom schedule took this key; leave it alone
                continue
            async with conn.execute(
                """SELECT week, phase, nutrients, ph, ec, environment, notes
                   FROM feeding_schedule_weeks WHERE schedule_id = ? ORDER BY week""",
                (schedule_id,),
            ) as cur:
                stored = [tuple(r) for r in await cur.fetchall()]
            if (row[1], row[2]) == header and stored == weeks:
                continue
            await conn.execute(
                "UPDATE feeding_schedules SET display_name = ?, substrate = ? WHERE id = ?",
                (*header, schedule_id),
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
        changed += 1
    return changed


async def _seed_phase_ranges(conn: aiosqlite.Connection) -> int:
    phases = _load("phase_ranges.json")["phases"]
    await conn.executemany(
        """INSERT INTO phase_ranges (phase, enforce_limits, temp_min, temp_max,
               humidity_min, humidity_max, vpd_min, vpd_max)
           VALUES (:phase, :enforce_limits, :temp_min, :temp_max,
               :humidity_min, :humidity_max, :vpd_min, :vpd_max)""",
        phases,
    )
    return len(phases)


_EMPTY_ONLY = (
    ("strains", _seed_strains),
    ("phase_ranges", _seed_phase_ranges),
)
_SYNCED = (
    ("nutrient_lines", _sync_nutrient_lines),
    ("feeding_schedules", _sync_feeding_schedules),
)


async def seed_reference_data(db: Database) -> dict[str, int]:
    """Seed empty groups and sync the built-in ones. Returns ``{table: rows_changed}``."""
    seeded: dict[str, int] = {}
    async with db.transaction() as conn:
        for table, seed in _EMPTY_ONLY:
            if await _is_empty(conn, table):
                seeded[table] = await seed(conn)
        for table, sync in _SYNCED:
            if count := await sync(conn):
                seeded[table] = count
    for table, count in seeded.items():
        log.info("Seeded %s (%d entries)", table, count)
    return seeded
