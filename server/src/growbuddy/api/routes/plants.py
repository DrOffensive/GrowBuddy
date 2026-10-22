"""/api/v1 plant routes."""

from __future__ import annotations

from fastapi import APIRouter, Depends, Query, Response

from growbuddy.api.deps import DbDep, require_token
from growbuddy.api.pagination import Limit, split_page
from growbuddy.api.schemas import Log, LogPage, Plant, PlantCreate, PlantPatch
from growbuddy.clock import now_ms
from growbuddy.db.queries import logs as log_q
from growbuddy.journal import plants as svc

router = APIRouter(prefix="/plants", tags=["plants"], dependencies=[Depends(require_token)])


@router.get("")
async def list_plants(
    db: DbDep, include_archived: bool = False, sensor_id: int | None = None
) -> list[Plant]:
    views = await svc.list_plants(db, now_ms(), include_archived, sensor_id)
    return [Plant.of(v) for v in views]


@router.post("", status_code=201)
async def create_plant(body: PlantCreate, db: DbDep, response: Response) -> Plant:
    view, created = await svc.create_plant(
        db,
        now_ms(),
        plant_id=str(body.id),
        nickname=body.nickname,
        strain_id=body.strain_id,
        is_autoflower=body.is_autoflower,
        sensor_id=body.sensor_id,
        thumbnail_photo_id=str(body.thumbnail_photo_id) if body.thumbnail_photo_id else None,
    )
    if not created:
        response.status_code = 200
    return Plant.of(view)


@router.get("/{plant_id}")
async def get_plant(plant_id: str, db: DbDep) -> Plant:
    return Plant.of(await svc.get_plant(db, plant_id, now_ms()))


@router.patch("/{plant_id}")
async def patch_plant(plant_id: str, body: PlantPatch, db: DbDep) -> Plant:
    return Plant.of(await svc.update_plant(db, plant_id, body.changes(), now_ms()))


@router.delete("/{plant_id}", status_code=204)
async def delete_plant(plant_id: str, db: DbDep) -> Response:
    await svc.delete_plant(db, plant_id)
    return Response(status_code=204)


@router.get("/{plant_id}/logs")
async def plant_logs(
    plant_id: str, db: DbDep, limit: Limit = 100, before: int | None = Query(default=None)
) -> LogPage:
    await svc.get_plant(db, plant_id, now_ms())
    records = await log_q.list_logs(db, limit=limit + 1, before=before, plant_id=plant_id)
    items, next_before = split_page(records, limit, lambda r: r.ts)
    return LogPage(items=[Log.of(r) for r in items], next_before=next_before)
