"""Reading time series for charts: raw (optionally bucketed) or hourly."""

from __future__ import annotations

import math
import sqlite3
from dataclasses import dataclass
from enum import StrEnum

from growbuddy.db.connection import Database
from growbuddy.db.queries import readings as reading_q
from growbuddy.domain.environment import vpd_kpa
from growbuddy.services.rollup import watermark

AUTO_RAW_MAX_SPAN_MS = 48 * 3_600_000


class Resolution(StrEnum):
    RAW = "raw"
    HOUR = "hour"
    AUTO = "auto"


@dataclass(frozen=True, slots=True)
class Point:
    ts: int
    temperature_c: float | None
    humidity_pct: float | None
    vpd_kpa: float | None
    temp_min: float | None
    temp_max: float | None
    hum_min: float | None
    hum_max: float | None
    samples: int


@dataclass(frozen=True, slots=True)
class Series:
    resolution: Resolution
    bucket_ms: int
    points: list[Point]


def _round(value: float | None) -> float | None:
    return None if value is None else round(value, 2)


def _point(row: sqlite3.Row) -> Point:
    temp, hum = row["temp_avg"], row["hum_avg"]
    vpd = round(vpd_kpa(temp, hum), 3) if temp is not None and hum is not None else None
    return Point(
        ts=int(row["ts"]),
        temperature_c=_round(temp),
        humidity_pct=_round(hum),
        vpd_kpa=vpd,
        temp_min=_round(row["temp_min"]),
        temp_max=_round(row["temp_max"]),
        hum_min=_round(row["hum_min"]),
        hum_max=_round(row["hum_max"]),
        samples=int(row["samples"]),
    )


async def reading_series(
    db: Database,
    sensor_id: int,
    from_ts: int,
    to_ts: int,
    resolution: Resolution,
    max_points: int,
) -> Series:
    """VPD of aggregated points is computed from the averaged temperature and humidity."""
    if resolution is Resolution.AUTO:
        resolution = Resolution.RAW if to_ts - from_ts <= AUTO_RAW_MAX_SPAN_MS else Resolution.HOUR

    if resolution is Resolution.HOUR:
        rolled_until = await watermark(db) or 0
        rows = await reading_q.hourly_series(db, sensor_id, from_ts, to_ts, rolled_until)
        return Series(Resolution.HOUR, reading_q.HOUR_MS, [_point(r) for r in rows])

    count = await reading_q.count_raw(db, sensor_id, from_ts, to_ts)
    if count <= max_points:
        rows = await reading_q.raw_series(db, sensor_id, from_ts, to_ts)
        return Series(Resolution.RAW, 0, [_point(r) for r in rows])
    bucket_ms = max(1000, math.ceil((to_ts - from_ts + 1) / max_points / 1000) * 1000)
    rows = await reading_q.bucketed_series(db, sensor_id, from_ts, to_ts, bucket_ms)
    return Series(Resolution.RAW, bucket_ms, [_point(r) for r in rows])
