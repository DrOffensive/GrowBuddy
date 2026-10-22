"""Reading time series and hourly rollup queries."""

from __future__ import annotations

import sqlite3

from growbuddy.db.connection import Database

HOUR_MS = 3_600_000


async def oldest_reading_ts(db: Database) -> int | None:
    value = await db.fetchval("SELECT MIN(ts) FROM readings")
    return None if value is None else int(value)


async def rollup_range(db: Database, from_ts: int, to_ts: int) -> None:
    """(Re)aggregate whole hours in [from_ts, to_ts) into readings_hourly."""
    await db.execute(
        f"""INSERT INTO readings_hourly
                (sensor_id, hour_ts, samples,
                 temp_min, temp_avg, temp_max, hum_min, hum_avg, hum_max)
            SELECT sensor_id, (ts / {HOUR_MS}) * {HOUR_MS} AS hour_ts, COUNT(*),
                   MIN(temperature_c), AVG(temperature_c), MAX(temperature_c),
                   MIN(humidity_pct), AVG(humidity_pct), MAX(humidity_pct)
            FROM readings WHERE ts >= ? AND ts < ?
            GROUP BY sensor_id, hour_ts
            ON CONFLICT (sensor_id, hour_ts) DO UPDATE SET
                samples = excluded.samples,
                temp_min = excluded.temp_min, temp_avg = excluded.temp_avg,
                temp_max = excluded.temp_max, hum_min = excluded.hum_min,
                hum_avg = excluded.hum_avg, hum_max = excluded.hum_max""",
        (from_ts, to_ts),
    )


async def count_raw(db: Database, sensor_id: int, from_ts: int, to_ts: int) -> int:
    return int(
        await db.fetchval(
            "SELECT COUNT(*) FROM readings WHERE sensor_id = ? AND ts BETWEEN ? AND ?",
            (sensor_id, from_ts, to_ts),
        )
    )


async def raw_series(db: Database, sensor_id: int, from_ts: int, to_ts: int) -> list[sqlite3.Row]:
    return await db.fetchall(
        """SELECT ts, temperature_c AS temp_avg, humidity_pct AS hum_avg,
                  NULL AS temp_min, NULL AS temp_max, NULL AS hum_min, NULL AS hum_max,
                  1 AS samples
           FROM readings WHERE sensor_id = ? AND ts BETWEEN ? AND ? ORDER BY ts""",
        (sensor_id, from_ts, to_ts),
    )


async def bucketed_series(
    db: Database, sensor_id: int, from_ts: int, to_ts: int, bucket_ms: int
) -> list[sqlite3.Row]:
    return await db.fetchall(
        f"""SELECT (ts / {bucket_ms}) * {bucket_ms} AS ts,
                   AVG(temperature_c) AS temp_avg, MIN(temperature_c) AS temp_min,
                   MAX(temperature_c) AS temp_max, AVG(humidity_pct) AS hum_avg,
                   MIN(humidity_pct) AS hum_min, MAX(humidity_pct) AS hum_max,
                   COUNT(*) AS samples
            FROM readings WHERE sensor_id = ? AND ts BETWEEN ? AND ?
            GROUP BY 1 ORDER BY 1""",
        (sensor_id, from_ts, to_ts),
    )


async def hourly_series(
    db: Database, sensor_id: int, from_ts: int, to_ts: int, rolled_until: int
) -> list[sqlite3.Row]:
    """Rolled-up hours before ``rolled_until`` plus live aggregation of later raw readings."""
    first_hour = (from_ts // HOUR_MS) * HOUR_MS
    return await db.fetchall(
        f"""SELECT hour_ts AS ts, temp_avg, temp_min, temp_max, hum_avg, hum_min, hum_max, samples
            FROM readings_hourly
            WHERE sensor_id = ? AND hour_ts >= ? AND hour_ts <= ? AND hour_ts < ?
            UNION ALL
            SELECT (ts / {HOUR_MS}) * {HOUR_MS}, AVG(temperature_c), MIN(temperature_c),
                   MAX(temperature_c), AVG(humidity_pct), MIN(humidity_pct), MAX(humidity_pct),
                   COUNT(*)
            FROM readings WHERE sensor_id = ? AND ts >= ? AND ts <= ?
            GROUP BY 1
            ORDER BY 1""",
        (
            sensor_id,
            first_hour,
            to_ts,
            rolled_until,
            sensor_id,
            max(first_hour, rolled_until),
            to_ts,
        ),
    )
