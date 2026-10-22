"""/api/v1 sensor routes: discovery, adopt/retire, per-sensor ranges and phase presets."""

from __future__ import annotations

import sqlite3

from fastapi import APIRouter, Depends, Query, Response

from growbuddy.api.deps import DbDep, HubDep, require_token
from growbuddy.api.schemas import (
    DiscoveredSensor,
    PhasePresetRequest,
    RangeSourceModel,
    Sensor,
    SensorAdopt,
    SensorDetail,
    SensorPatch,
    SensorRanges,
)
from growbuddy.clock import now_ms
from growbuddy.db.connection import Database
from growbuddy.db.queries import sensors as queries
from growbuddy.domain.environment import preset_from_phases
from growbuddy.errors import Conflict, Invalid, NotFound
from growbuddy.services.events import Level, record_event
from growbuddy.services.ranges import RangeMode, effective_ranges

router = APIRouter(prefix="/sensors", tags=["sensors"], dependencies=[Depends(require_token)])


async def _sensor_or_404(db: Database, sensor_id: int) -> sqlite3.Row:
    row = await queries.get_sensor(db, sensor_id)
    if row is None:
        raise NotFound(f"Sensor {sensor_id} not found")
    return row


async def _detail(db: Database, row: sqlite3.Row) -> SensorDetail:
    effective = await effective_ranges(db, row, now_ms())
    return SensorDetail(
        **Sensor.of(row).model_dump(),
        ranges=SensorRanges.of(effective.ranges),
        range_source=RangeSourceModel.of(effective.source),
    )


async def _set_manual(db: Database, row: sqlite3.Row) -> None:
    """Switch to manual; seed stored ranges with the current effective ones if none exist."""
    if row["range_mode"] == RangeMode.MANUAL:
        return
    if not await queries.has_stored_ranges(db, row["id"]):
        await queries.put_ranges(db, row["id"], (await effective_ranges(db, row, now_ms())).ranges)
    await queries.set_range_mode(db, row["id"], RangeMode.MANUAL)


@router.get("/discovered")
async def discovered(
    hub: HubDep, within_s: int | None = Query(default=None, ge=1)
) -> list[DiscoveredSensor]:
    return [DiscoveredSensor.of(d) for d in hub.discovered(within_s)]


@router.get("")
async def list_sensors(db: DbDep, include_retired: bool = False) -> list[Sensor]:
    return [Sensor.of(r) for r in await queries.list_sensors(db, include_retired)]


@router.post("", status_code=201)
async def adopt(body: SensorAdopt, db: DbDep, hub: HubDep) -> SensorDetail:
    existing = await queries.get_sensor_by_mac(db, body.mac)
    if existing is not None and existing["status"] == "active":
        raise Conflict(f"{body.mac} is already adopted", code="already_adopted")
    seen = hub.processor.seen(body.mac)
    if seen is not None and seen.encrypted:
        raise Invalid("Encrypted BTHome sensors are not supported", code="encrypted_sensor")
    sensor_id = await queries.adopt_sensor(db, body.mac, body.name, now_ms())
    await hub.reload_adopted()
    await record_event(
        db, Level.INFO, "ble", "sensor_adopted", f"Sensor '{body.name}' ({body.mac}) adopted",
        {"sensor_id": sensor_id, "reactivated": existing is not None},
    )  # fmt: skip
    return await _detail(db, await _sensor_or_404(db, sensor_id))


@router.get("/{sensor_id}")
async def get_sensor(sensor_id: int, db: DbDep) -> SensorDetail:
    return await _detail(db, await _sensor_or_404(db, sensor_id))


@router.patch("/{sensor_id}")
async def patch_sensor(sensor_id: int, body: SensorPatch, db: DbDep) -> SensorDetail:
    row = await _sensor_or_404(db, sensor_id)
    await queries.update_sensor(db, sensor_id, body.name, body.alerts_enabled)
    if body.range_mode is RangeMode.MANUAL:
        await _set_manual(db, row)
    elif body.range_mode is RangeMode.AUTO:
        await queries.set_range_mode(db, sensor_id, RangeMode.AUTO)
    return await _detail(db, await _sensor_or_404(db, sensor_id))


@router.delete("/{sensor_id}", status_code=204)
async def retire(sensor_id: int, db: DbDep, hub: HubDep) -> Response:
    row = await _sensor_or_404(db, sensor_id)
    if row["status"] == "active":
        await queries.retire_sensor(db, sensor_id, now_ms())
        await hub.reload_adopted()
        message = f"Sensor '{row['name']}' ({row['mac']}) retired"
        await record_event(
            db, Level.INFO, "ble", "sensor_retired", message, {"sensor_id": sensor_id}
        )
    return Response(status_code=204)


@router.get("/{sensor_id}/ranges")
async def get_ranges(sensor_id: int, db: DbDep) -> SensorRanges:
    """Effective ranges (auto: from the driving plant's phase)."""
    row = await _sensor_or_404(db, sensor_id)
    return SensorRanges.of((await effective_ranges(db, row, now_ms())).ranges)


@router.put("/{sensor_id}/ranges")
async def put_ranges(sensor_id: int, body: SensorRanges, db: DbDep) -> SensorRanges:
    """Store manual ranges; switches the sensor to manual mode."""
    await _sensor_or_404(db, sensor_id)
    await queries.put_ranges(db, sensor_id, body.to_domain())
    await queries.set_range_mode(db, sensor_id, RangeMode.MANUAL)
    return SensorRanges.of(await queries.get_ranges(db, sensor_id))


@router.post("/{sensor_id}/ranges/from-phases")
async def ranges_from_phases(sensor_id: int, body: PhasePresetRequest, db: DbDep) -> SensorRanges:
    await _sensor_or_404(db, sensor_id)
    templates = await queries.phase_templates(db)
    unknown = sorted(set(body.phases) - templates.keys())
    if unknown:
        raise Invalid(f"Unknown phase(s): {', '.join(unknown)}", code="unknown_phase")
    preset = preset_from_phases(templates[p] for p in body.phases)
    if body.apply:
        await queries.put_ranges(db, sensor_id, preset)
        await queries.set_range_mode(db, sensor_id, RangeMode.MANUAL)
    return SensorRanges.of(preset)
