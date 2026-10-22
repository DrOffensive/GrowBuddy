"""Alert state rows: one row per alert episode at one level (ended_ts NULL = active)."""

from __future__ import annotations

import sqlite3
from typing import Any

from growbuddy.db.connection import Database


async def active_alerts(db: Database, sensor_id: int | None = None) -> list[sqlite3.Row]:
    sql = """SELECT a.*, s.name AS sensor_name FROM alerts a
             LEFT JOIN sensors s ON s.id = a.sensor_id WHERE a.ended_ts IS NULL"""
    params: list[Any] = []
    if sensor_id is not None:
        sql += " AND a.sensor_id = ?"
        params.append(sensor_id)
    return await db.fetchall(sql + " ORDER BY a.started_ts DESC", params)


async def open_alert(
    db: Database,
    sensor_id: int | None,
    metric: str,
    level: str,
    started_ts: int,
    value: float | None,
    band: tuple[float | None, float | None] | None,
) -> int:
    lo, hi = band or (None, None)
    return await db.execute(
        """INSERT INTO alerts (sensor_id, metric, level, started_ts, value, band_min, band_max)
           VALUES (?, ?, ?, ?, ?, ?, ?)""",
        (sensor_id, metric, level, started_ts, value, lo, hi),
    )


async def close_alert(db: Database, alert_id: int, ended_ts: int) -> None:
    await db.execute("UPDATE alerts SET ended_ts = ? WHERE id = ?", (ended_ts, alert_id))


async def update_value(db: Database, alert_id: int, value: float | None) -> None:
    await db.execute("UPDATE alerts SET value = ? WHERE id = ?", (value, alert_id))


async def mark_notified(db: Database, alert_id: int, ts: int) -> None:
    await db.execute("UPDATE alerts SET notified_ts = ? WHERE id = ?", (ts, alert_id))


async def list_alerts(
    db: Database,
    *,
    limit: int,
    before: int | None = None,
    sensor_id: int | None = None,
    from_ts: int | None = None,
    to_ts: int | None = None,
) -> list[sqlite3.Row]:
    where: list[str] = []
    params: list[Any] = []
    for clause, value in (
        ("a.started_ts < ?", before),
        ("a.sensor_id = ?", sensor_id),
        ("a.started_ts >= ?", from_ts),
        ("a.started_ts <= ?", to_ts),
    ):
        if value is not None:
            where.append(clause)
            params.append(value)
    sql = f"""SELECT a.*, s.name AS sensor_name
              FROM alerts a LEFT JOIN sensors s ON s.id = a.sensor_id
              {"WHERE " + " AND ".join(where) if where else ""}"""
    return await db.fetchall(
        f"{sql} ORDER BY a.started_ts DESC, a.id DESC LIMIT ?", [*params, limit]
    )
