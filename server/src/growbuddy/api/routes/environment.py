"""/api/v1 environment routes: /environment/current, /readings."""

from __future__ import annotations

from dataclasses import asdict

from fastapi import APIRouter, Depends, Query

from growbuddy.alerts.settings import load_alert_settings
from growbuddy.api.deps import DbDep, SettingsDep, require_token
from growbuddy.api.schemas import (
    EnvironmentCurrent,
    Plant,
    ReadingSeries,
    SensorEnvironment,
    SeriesPoint,
)
from growbuddy.clock import now_ms
from growbuddy.db.queries import sensors as sensor_q
from growbuddy.errors import Invalid, NotFound
from growbuddy.overview import current_environment
from growbuddy.services.series import Resolution, reading_series

router = APIRouter(tags=["environment"], dependencies=[Depends(require_token)])

DEFAULT_SPAN_MS = 24 * 3_600_000


@router.get("/environment/current")
async def environment_current(db: DbDep, settings: SettingsDep) -> EnvironmentCurrent:
    now = now_ms()
    alert_settings = await load_alert_settings(db, settings.alerts)
    overview = await current_environment(db, alert_settings, now)
    return EnvironmentCurrent(
        ts=now,
        sensors=[SensorEnvironment.of(s) for s in overview.sensors],
        unlocated_plants=[Plant.of(p) for p in overview.unlocated_plants],
    )


@router.get("/readings")
async def readings(
    db: DbDep,
    sensor_id: int,
    from_ts: int | None = Query(default=None, alias="from", ge=0),
    to_ts: int | None = Query(default=None, alias="to", ge=0),
    resolution: Resolution = Resolution.AUTO,
    max_points: int = Query(default=2000, ge=10, le=10000),
) -> ReadingSeries:
    """Defaults to the last 24 h. Retired sensors keep their history."""
    if await sensor_q.get_sensor(db, sensor_id) is None:
        raise NotFound(f"Sensor {sensor_id} not found")
    to_ts = now_ms() if to_ts is None else to_ts
    from_ts = to_ts - DEFAULT_SPAN_MS if from_ts is None else from_ts
    if from_ts > to_ts:
        raise Invalid("'from' must not be after 'to'", code="invalid_range")
    series = await reading_series(db, sensor_id, from_ts, to_ts, resolution, max_points)
    return ReadingSeries(
        sensor_id=sensor_id,
        resolution=series.resolution.value,
        bucket_ms=series.bucket_ms,
        from_ts=from_ts,
        to_ts=to_ts,
        points=[SeriesPoint(**asdict(p)) for p in series.points],
    )
