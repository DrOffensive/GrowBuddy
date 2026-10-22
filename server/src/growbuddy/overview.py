"""Dashboard composition for GET /environment/current: per adopted sensor the latest
reading, VPD, ranges and alarm levels, plus the plants located there."""

from __future__ import annotations

import sqlite3
from dataclasses import dataclass, field

from growbuddy.config import AlertSettings
from growbuddy.db.connection import Database
from growbuddy.db.queries import sensors as sensor_q
from growbuddy.domain.environment import AlarmLevel, Metric, Range, evaluate, vpd_kpa
from growbuddy.journal.plants import PlantView, list_plants
from growbuddy.services.ranges import RangeMode, RangeSource, effective_ranges


@dataclass(frozen=True, slots=True)
class CurrentReading:
    ts: int
    temperature_c: float | None
    humidity_pct: float | None
    vpd_kpa: float | None


@dataclass(frozen=True, slots=True)
class SensorOverview:
    sensor: sqlite3.Row
    online: bool
    reading: CurrentReading | None
    ranges: dict[Metric, Range]
    levels: dict[Metric, AlarmLevel | None]
    range_mode: RangeMode
    range_source: RangeSource | None
    plants: list[PlantView] = field(default_factory=list)


@dataclass(frozen=True, slots=True)
class EnvironmentOverview:
    sensors: list[SensorOverview]
    unlocated_plants: list[PlantView]


def _reading(row: sqlite3.Row | None) -> CurrentReading | None:
    if row is None:
        return None
    temp, hum = row["temperature_c"], row["humidity_pct"]
    vpd = round(vpd_kpa(temp, hum), 3) if temp is not None and hum is not None else None
    return CurrentReading(row["ts"], temp, hum, vpd)


def _levels(
    reading: CurrentReading | None, ranges: dict[Metric, Range]
) -> dict[Metric, AlarmLevel | None]:
    values = {
        Metric.TEMPERATURE: reading.temperature_c if reading else None,
        Metric.HUMIDITY: reading.humidity_pct if reading else None,
        Metric.VPD: reading.vpd_kpa if reading else None,
    }
    return {m: None if v is None else evaluate(v, ranges[m]) for m, v in values.items()}


async def current_environment(
    db: Database, alert_settings: AlertSettings, now: int
) -> EnvironmentOverview:
    plants = await list_plants(db, now)
    by_sensor: dict[int | None, list[PlantView]] = {}
    for plant in plants:
        by_sensor.setdefault(plant.row["sensor_id"], []).append(plant)

    offline_ms = alert_settings.offline_after_s * 1000
    sensors = []
    for row in await sensor_q.list_sensors(db):
        reading = _reading(await sensor_q.latest_reading(db, row["id"]))
        located = by_sensor.get(row["id"], [])
        effective = await effective_ranges(db, row, now, located)
        ranges = effective.ranges
        last_seen = row["last_seen"]
        sensors.append(
            SensorOverview(
                sensor=row,
                online=last_seen is not None and now - last_seen <= offline_ms,
                reading=reading,
                ranges=ranges,
                levels=_levels(reading, ranges),
                range_mode=effective.mode,
                range_source=effective.source,
                plants=located,
            )
        )
    return EnvironmentOverview(sensors=sensors, unlocated_plants=by_sensor.get(None, []))
