"""Log entry queries, including plant and photo links."""

from __future__ import annotations

import json
import sqlite3
from collections import defaultdict
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any

from growbuddy.db.connection import Database
from growbuddy.db.queries.plants import placeholders


@dataclass(frozen=True, slots=True)
class LogRecord:
    id: str
    ts: int
    type: str
    comment: str | None
    data: dict[str, Any]
    sensor_snapshot: list[dict[str, Any]]
    created_ts: int
    updated_ts: int
    plant_ids: list[str]
    photo_ids: list[str]


async def _links(
    db: Database, log_ids: Sequence[str]
) -> tuple[dict[str, list[str]], dict[str, list[str]]]:
    plants: dict[str, list[str]] = defaultdict(list)
    photos: dict[str, list[str]] = defaultdict(list)
    if log_ids:
        marks = placeholders(len(log_ids))
        for r in await db.fetchall(
            f"SELECT log_id, plant_id FROM log_plants WHERE log_id IN ({marks}) ORDER BY plant_id",
            log_ids,
        ):
            plants[r["log_id"]].append(r["plant_id"])
        for r in await db.fetchall(
            f"""SELECT log_id, photo_id FROM log_photos WHERE log_id IN ({marks})
                ORDER BY log_id, position""",
            log_ids,
        ):
            photos[r["log_id"]].append(r["photo_id"])
    return plants, photos


async def _records(db: Database, rows: list[sqlite3.Row]) -> list[LogRecord]:
    plants, photos = await _links(db, [r["id"] for r in rows])
    return [
        LogRecord(
            id=r["id"],
            ts=r["ts"],
            type=r["type"],
            comment=r["comment"],
            data=json.loads(r["data"]),
            sensor_snapshot=json.loads(r["sensor_snapshot"]) if r["sensor_snapshot"] else [],
            created_ts=r["created_ts"],
            updated_ts=r["updated_ts"],
            plant_ids=plants.get(r["id"], []),
            photo_ids=photos.get(r["id"], []),
        )
        for r in rows
    ]


async def get_log(db: Database, log_id: str) -> LogRecord | None:
    rows = await db.fetchall("SELECT * FROM logs WHERE id = ?", (log_id,))
    records = await _records(db, rows)
    return records[0] if records else None


async def list_logs(
    db: Database,
    *,
    limit: int,
    before: int | None = None,
    log_type: str | None = None,
    from_ts: int | None = None,
    to_ts: int | None = None,
    plant_id: str | None = None,
) -> list[LogRecord]:
    """Newest first; ``before`` is an exclusive ts cursor."""
    where: list[str] = []
    params: list[Any] = []
    join = ""
    if plant_id is not None:
        join = "JOIN log_plants lp ON lp.log_id = l.id"
        where.append("lp.plant_id = ?")
        params.append(plant_id)
    for clause, value in (
        ("l.ts < ?", before),
        ("l.type = ?", log_type),
        ("l.ts >= ?", from_ts),
        ("l.ts <= ?", to_ts),
    ):
        if value is not None:
            where.append(clause)
            params.append(value)
    sql = f"SELECT l.* FROM logs l {join} {'WHERE ' + ' AND '.join(where) if where else ''}"
    rows = await db.fetchall(f"{sql} ORDER BY l.ts DESC, l.id DESC LIMIT ?", [*params, limit])
    return await _records(db, rows)


async def _replace_links(
    conn: Any, log_id: str, plant_ids: Sequence[str] | None, photo_ids: Sequence[str] | None
) -> None:
    if plant_ids is not None:
        await conn.execute("DELETE FROM log_plants WHERE log_id = ?", (log_id,))
        await conn.executemany(
            "INSERT INTO log_plants (log_id, plant_id) VALUES (?, ?)",
            [(log_id, pid) for pid in plant_ids],
        )
    if photo_ids is not None:
        await conn.execute("DELETE FROM log_photos WHERE log_id = ?", (log_id,))
        await conn.executemany(
            "INSERT INTO log_photos (log_id, photo_id, position) VALUES (?, ?, ?)",
            [(log_id, pid, pos) for pos, pid in enumerate(photo_ids)],
        )


async def insert_log(db: Database, rec: LogRecord) -> None:
    async with db.transaction() as conn:
        await conn.execute(
            """INSERT INTO logs
                   (id, ts, type, comment, data, sensor_snapshot, created_ts, updated_ts)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                rec.id, rec.ts, rec.type, rec.comment, json.dumps(rec.data),
                json.dumps(rec.sensor_snapshot), rec.created_ts, rec.updated_ts,
            ),
        )  # fmt: skip
        await _replace_links(conn, rec.id, rec.plant_ids, rec.photo_ids)


async def update_log(
    db: Database,
    log_id: str,
    *,
    ts: int,
    comment: str | None,
    data: dict[str, Any],
    sensor_snapshot: list[dict[str, Any]],
    updated_ts: int,
    plant_ids: Sequence[str] | None,
    photo_ids: Sequence[str] | None,
) -> None:
    async with db.transaction() as conn:
        await conn.execute(
            """UPDATE logs SET ts = ?, comment = ?, data = ?, sensor_snapshot = ?, updated_ts = ?
               WHERE id = ?""",
            (ts, comment, json.dumps(data), json.dumps(sensor_snapshot), updated_ts, log_id),
        )
        await _replace_links(conn, log_id, plant_ids, photo_ids)


async def delete_log(db: Database, log_id: str) -> bool:
    async with (
        db.transaction() as conn,
        conn.execute("DELETE FROM logs WHERE id = ?", (log_id,)) as cur,
    ):
        return cur.rowcount > 0


async def nearest_reading(
    db: Database, sensor_id: int, ts: int, window_ms: int
) -> sqlite3.Row | None:
    return await db.fetchone(
        """SELECT r.*, s.name AS sensor_name FROM readings r JOIN sensors s ON s.id = r.sensor_id
           WHERE r.sensor_id = ? AND r.ts BETWEEN ? AND ?
           ORDER BY ABS(r.ts - ?) LIMIT 1""",
        (sensor_id, ts - window_ms, ts + window_ms, ts),
    )
