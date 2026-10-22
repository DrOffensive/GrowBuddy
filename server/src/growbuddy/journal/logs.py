"""Log entry service: per-type validation, idempotent create, sensor snapshots."""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from typing import Any

from pydantic import ValidationError

from growbuddy.db.connection import Database
from growbuddy.db.queries import logs as log_q
from growbuddy.db.queries import photos as photo_q
from growbuddy.db.queries import plants as plant_q
from growbuddy.db.queries.logs import LogRecord
from growbuddy.domain.environment import vpd_kpa
from growbuddy.domain.logs import LogType, validate_log_data
from growbuddy.errors import Invalid, NotFound

SNAPSHOT_WINDOW_MS = 10 * 60_000


def _unique(ids: Iterable[str]) -> list[str]:
    return list(dict.fromkeys(ids))


def _validated_data(log_type: LogType, data: dict[str, Any] | None) -> dict[str, Any]:
    try:
        return validate_log_data(log_type, data)
    except ValidationError as exc:
        details = "; ".join(
            f"{'.'.join(str(p) for p in e['loc']) or 'data'}: {e['msg']}" for e in exc.errors()
        )
        raise Invalid(f"Invalid data for {log_type.value}: {details}", code="invalid_data") from exc


async def _check_refs(db: Database, plant_ids: Sequence[str], photo_ids: Sequence[str]) -> None:
    missing_plants = set(plant_ids) - await plant_q.existing_ids(db, plant_ids)
    if missing_plants:
        raise Invalid(
            f"Unknown plant(s): {', '.join(sorted(missing_plants))}", code="unknown_plant"
        )
    missing_photos = set(photo_ids) - await photo_q.existing_ids(db, photo_ids)
    if missing_photos:
        raise Invalid(
            f"Unknown photo(s): {', '.join(sorted(missing_photos))}", code="unknown_photo"
        )


async def sensor_snapshot(db: Database, plant_ids: Sequence[str], ts: int) -> list[dict[str, Any]]:
    """Nearest reading (±10 min) from each distinct location among ``plant_ids``."""
    locations = await plant_q.locations(db, plant_ids)
    snapshot = []
    for sensor_id in sorted({s for s in locations.values() if s is not None}):
        row = await log_q.nearest_reading(db, sensor_id, ts, SNAPSHOT_WINDOW_MS)
        if row is None:
            continue
        temp, hum = row["temperature_c"], row["humidity_pct"]
        snapshot.append(
            {
                "sensor_id": sensor_id,
                "sensor_name": row["sensor_name"],
                "ts": row["ts"],
                "temperature_c": temp,
                "humidity_pct": hum,
                "vpd_kpa": round(vpd_kpa(temp, hum), 3)
                if temp is not None and hum is not None
                else None,
            }
        )
    return snapshot


async def get_log(db: Database, log_id: str) -> LogRecord:
    rec = await log_q.get_log(db, log_id)
    if rec is None:
        raise NotFound(f"Log entry {log_id} not found")
    return rec


async def create_log(
    db: Database,
    now: int,
    *,
    log_id: str,
    log_type: LogType,
    plant_ids: Sequence[str],
    ts: int | None = None,
    comment: str | None = None,
    photo_ids: Sequence[str] = (),
    data: dict[str, Any] | None = None,
) -> tuple[LogRecord, bool]:
    """Create an entry; an existing id returns that entry unchanged. Returns (record, created)."""
    existing = await log_q.get_log(db, log_id)
    if existing is not None:
        return existing, False
    plants, photos = _unique(plant_ids), _unique(photo_ids)
    if not plants:
        raise Invalid("A log entry needs at least one plant", code="no_plants")
    clean = _validated_data(log_type, data)
    await _check_refs(db, plants, photos)
    ts = now if ts is None else ts
    rec = LogRecord(
        id=log_id,
        ts=ts,
        type=log_type.value,
        comment=comment,
        data=clean,
        sensor_snapshot=await sensor_snapshot(db, plants, ts),
        created_ts=now,
        updated_ts=now,
        plant_ids=sorted(plants),
        photo_ids=photos,
    )
    await log_q.insert_log(db, rec)
    return await get_log(db, log_id), True


async def update_log(db: Database, log_id: str, changes: dict[str, Any], now: int) -> LogRecord:
    """Apply ``changes`` among ts, comment, data, plant_ids, photo_ids. The type is fixed."""
    current = await get_log(db, log_id)
    plants = _unique(changes["plant_ids"]) if "plant_ids" in changes else None
    photos = _unique(changes["photo_ids"]) if "photo_ids" in changes else None
    if plants is not None and not plants:
        raise Invalid("A log entry needs at least one plant", code="no_plants")
    data = (
        _validated_data(LogType(current.type), changes["data"])
        if "data" in changes
        else current.data
    )
    await _check_refs(db, plants or [], photos or [])
    ts = changes["ts"] if changes.get("ts") is not None else current.ts
    snapshot = current.sensor_snapshot
    if ts != current.ts or (plants is not None and set(plants) != set(current.plant_ids)):
        snapshot = await sensor_snapshot(
            db, plants if plants is not None else current.plant_ids, ts
        )
    await log_q.update_log(
        db,
        log_id,
        ts=ts,
        comment=changes.get("comment", current.comment),
        data=data,
        sensor_snapshot=snapshot,
        updated_ts=now,
        plant_ids=plants,
        photo_ids=photos,
    )
    return await get_log(db, log_id)


async def delete_log(db: Database, log_id: str) -> None:
    if not await log_q.delete_log(db, log_id):
        raise NotFound(f"Log entry {log_id} not found")
