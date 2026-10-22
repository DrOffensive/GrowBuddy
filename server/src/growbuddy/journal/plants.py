"""Plant service: validation, idempotent create, derived phase/age (docs/DOMAIN.md)."""

from __future__ import annotations

import sqlite3
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from growbuddy.db.connection import Database
from growbuddy.db.queries import photos as photo_q
from growbuddy.db.queries import plants as plant_q
from growbuddy.db.queries import sensors as sensor_q
from growbuddy.domain.phase import Milestone, Phase, PlantAge, derive_age, derive_phase
from growbuddy.errors import Invalid, NotFound


@dataclass(frozen=True, slots=True)
class PlantView:
    row: sqlite3.Row
    milestones: dict[Milestone, int]
    phase: Phase
    age: PlantAge | None


def _view(row: sqlite3.Row, milestones: dict[Milestone, int], now: int) -> PlantView:
    return PlantView(row, milestones, derive_phase(milestones, now), derive_age(milestones, now))


async def list_plants(
    db: Database, now: int, include_archived: bool = False, sensor_id: int | None = None
) -> list[PlantView]:
    rows = await plant_q.list_plants(db, include_archived, sensor_id)
    ms = await plant_q.milestones(db, [r["id"] for r in rows])
    return [_view(r, ms[r["id"]], now) for r in rows]


async def get_plant(db: Database, plant_id: str, now: int) -> PlantView:
    row = await plant_q.get_plant(db, plant_id)
    if row is None:
        raise NotFound(f"Plant {plant_id} not found")
    ms = await plant_q.milestones(db, [plant_id])
    return _view(row, ms[plant_id], now)


async def _validate_refs(db: Database, fields: Mapping[str, Any]) -> None:
    strain_id = fields.get("strain_id")
    if strain_id is not None and not await plant_q.strain_exists(db, strain_id):
        raise Invalid(f"Unknown strain {strain_id}", code="unknown_strain")
    sensor_id = fields.get("sensor_id")
    if sensor_id is not None:
        sensor = await sensor_q.get_sensor(db, sensor_id)
        if sensor is None or sensor["status"] != "active":
            raise Invalid(f"Sensor {sensor_id} is not an active sensor", code="unknown_sensor")
    photo_id = fields.get("thumbnail_photo_id")
    if photo_id is not None and not await photo_q.existing_ids(db, [photo_id]):
        raise Invalid(f"Unknown photo {photo_id}", code="unknown_photo")


async def create_plant(
    db: Database,
    now: int,
    *,
    plant_id: str,
    nickname: str,
    strain_id: int | None = None,
    is_autoflower: bool = False,
    sensor_id: int | None = None,
    thumbnail_photo_id: str | None = None,
) -> tuple[PlantView, bool]:
    """Create a plant; an existing id returns that plant unchanged. Returns (view, created)."""
    if await plant_q.get_plant(db, plant_id) is not None:
        return await get_plant(db, plant_id, now), False
    await _validate_refs(
        db,
        {"strain_id": strain_id, "sensor_id": sensor_id, "thumbnail_photo_id": thumbnail_photo_id},
    )
    await plant_q.insert_plant(
        db, plant_id, nickname, strain_id, is_autoflower, sensor_id, thumbnail_photo_id, now
    )
    return await get_plant(db, plant_id, now), True


async def update_plant(
    db: Database, plant_id: str, changes: Mapping[str, Any], now: int
) -> PlantView:
    """Apply ``changes`` (only keys present are changed; None clears nullable fields)."""
    await get_plant(db, plant_id, now)
    await _validate_refs(db, changes)
    await plant_q.update_plant(db, plant_id, changes)
    return await get_plant(db, plant_id, now)


async def delete_plant(db: Database, plant_id: str) -> None:
    if await plant_q.get_plant(db, plant_id) is None:
        raise NotFound(f"Plant {plant_id} not found")
    await plant_q.delete_plant(db, plant_id)
