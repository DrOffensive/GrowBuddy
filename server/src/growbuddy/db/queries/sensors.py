"""Sensor, reading, sensor-range and phase-template queries."""

from __future__ import annotations

import sqlite3
from collections.abc import Mapping

from growbuddy.db.connection import Database
from growbuddy.domain.environment import Metric, PhaseTemplate, Range

# ---------------------------------------------------------------- sensors


async def adopted_macs(db: Database) -> dict[str, int]:
    rows = await db.fetchall("SELECT mac, id FROM sensors WHERE status = 'active'")
    return {r["mac"]: r["id"] for r in rows}


async def list_sensors(db: Database, include_retired: bool = False) -> list[sqlite3.Row]:
    where = "" if include_retired else "WHERE status = 'active'"
    return await db.fetchall(f"SELECT * FROM sensors {where} ORDER BY name COLLATE NOCASE")


async def get_sensor(db: Database, sensor_id: int) -> sqlite3.Row | None:
    return await db.fetchone("SELECT * FROM sensors WHERE id = ?", (sensor_id,))


async def get_sensor_by_mac(db: Database, mac: str) -> sqlite3.Row | None:
    return await db.fetchone("SELECT * FROM sensors WHERE mac = ?", (mac,))


async def adopt_sensor(db: Database, mac: str, name: str, now_ms: int) -> int:
    """Insert, or reactivate a retired sensor (keeping its history). Returns its id."""
    async with (
        db.transaction() as conn,
        conn.execute(
            """INSERT INTO sensors (mac, name, adopted_ts) VALUES (?, ?, ?)
               ON CONFLICT (mac) DO UPDATE SET name = excluded.name, status = 'active'
               RETURNING id""",
            (mac, name, now_ms),
        ) as cur,
    ):
        row = await cur.fetchone()
    assert row is not None
    return int(row[0])


async def update_sensor(
    db: Database, sensor_id: int, name: str | None, alerts_enabled: bool | None
) -> None:
    async with db.transaction() as conn:
        if name is not None:
            await conn.execute("UPDATE sensors SET name = ? WHERE id = ?", (name, sensor_id))
        if alerts_enabled is not None:
            await conn.execute(
                "UPDATE sensors SET alerts_enabled = ? WHERE id = ?", (alerts_enabled, sensor_id)
            )


async def set_range_mode(db: Database, sensor_id: int, mode: str) -> None:
    await db.execute("UPDATE sensors SET range_mode = ? WHERE id = ?", (mode, sensor_id))


async def has_stored_ranges(db: Database, sensor_id: int) -> bool:
    return bool(
        await db.fetchval(
            "SELECT EXISTS (SELECT 1 FROM sensor_ranges WHERE sensor_id = ?)", (sensor_id,)
        )
    )


async def retire_sensor(db: Database, sensor_id: int, now_ms: int) -> None:
    """Stop recording/alerting; history kept, located plants lose their location."""
    async with db.transaction() as conn:
        await conn.execute("UPDATE sensors SET status = 'retired' WHERE id = ?", (sensor_id,))
        await conn.execute("UPDATE plants SET sensor_id = NULL WHERE sensor_id = ?", (sensor_id,))
        await conn.execute(
            "UPDATE alerts SET ended_ts = ? WHERE sensor_id = ? AND ended_ts IS NULL",
            (now_ms, sensor_id),
        )


async def insert_reading(
    db: Database,
    sensor_id: int,
    ts: int,
    temperature_c: float | None,
    humidity_pct: float | None,
    battery_pct: int | None,
    voltage_v: float | None,
    rssi: int | None,
) -> None:
    async with db.transaction() as conn:
        await conn.execute(
            """INSERT INTO readings
                   (sensor_id, ts, temperature_c, humidity_pct, battery_pct, voltage_v, rssi)
               VALUES (?, ?, ?, ?, ?, ?, ?)""",
            (sensor_id, ts, temperature_c, humidity_pct, battery_pct, voltage_v, rssi),
        )
        await conn.execute(
            """UPDATE sensors SET last_seen = ?, last_rssi = ?,
                   last_battery = COALESCE(?, last_battery)
               WHERE id = ?""",
            (ts, rssi, battery_pct, sensor_id),
        )


async def latest_reading(db: Database, sensor_id: int) -> sqlite3.Row | None:
    return await db.fetchone(
        "SELECT * FROM readings WHERE sensor_id = ? ORDER BY ts DESC LIMIT 1", (sensor_id,)
    )


# ----------------------------------------------------------- sensor ranges


async def get_ranges(db: Database, sensor_id: int) -> dict[Metric, Range]:
    rows = await db.fetchall("SELECT * FROM sensor_ranges WHERE sensor_id = ?", (sensor_id,))
    by_metric = {r["metric"]: r for r in rows}
    result: dict[Metric, Range] = {}
    for metric in Metric:
        row = by_metric.get(metric.value)
        result[metric] = (
            Range(row["warn_min"], row["warn_max"], row["crit_min"], row["crit_max"])
            if row
            else Range()
        )
    return result


async def put_ranges(db: Database, sensor_id: int, ranges: Mapping[Metric, Range]) -> None:
    async with db.transaction() as conn:
        await conn.execute("DELETE FROM sensor_ranges WHERE sensor_id = ?", (sensor_id,))
        await conn.executemany(
            """INSERT INTO sensor_ranges (sensor_id, metric, warn_min, warn_max, crit_min, crit_max)
               VALUES (?, ?, ?, ?, ?, ?)""",
            [
                (sensor_id, m.value, r.warn_min, r.warn_max, r.crit_min, r.crit_max)
                for m, r in ranges.items()
                if not r.is_empty
            ],
        )


# ----------------------------------------------------------- phase templates


async def phase_templates(db: Database) -> dict[str, PhaseTemplate]:
    rows = await db.fetchall("SELECT * FROM phase_ranges")
    return {
        r["phase"]: PhaseTemplate(
            phase=r["phase"],
            enforce_limits=bool(r["enforce_limits"]),
            temp_min=r["temp_min"],
            temp_max=r["temp_max"],
            humidity_min=r["humidity_min"],
            humidity_max=r["humidity_max"],
            vpd_min=r["vpd_min"],
            vpd_max=r["vpd_max"],
        )
        for r in rows
    }
