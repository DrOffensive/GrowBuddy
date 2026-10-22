"""/api/v1 log entry routes."""

from __future__ import annotations

from fastapi import APIRouter, Depends, Query, Response

from growbuddy.api.deps import DbDep, require_token
from growbuddy.api.pagination import Limit, split_page
from growbuddy.api.schemas import Log, LogCreate, LogPage, LogPatch
from growbuddy.clock import now_ms
from growbuddy.db.queries import logs as log_q
from growbuddy.domain.logs import LogType
from growbuddy.journal import logs as svc

router = APIRouter(prefix="/logs", tags=["logs"], dependencies=[Depends(require_token)])


@router.post("", status_code=201)
async def create_log(body: LogCreate, db: DbDep, response: Response) -> Log:
    record, created = await svc.create_log(
        db,
        now_ms(),
        log_id=str(body.id),
        log_type=body.type,
        plant_ids=[str(p) for p in body.plant_ids],
        ts=body.ts,
        comment=body.comment,
        photo_ids=[str(p) for p in body.photo_ids],
        data=body.data,
    )
    if not created:
        response.status_code = 200
    return Log.of(record)


@router.get("")
async def list_logs(
    db: DbDep,
    limit: Limit = 100,
    before: int | None = None,
    type: LogType | None = None,
    from_ts: int | None = Query(default=None, alias="from"),
    to_ts: int | None = Query(default=None, alias="to"),
) -> LogPage:
    records = await log_q.list_logs(
        db,
        limit=limit + 1,
        before=before,
        log_type=type.value if type else None,
        from_ts=from_ts,
        to_ts=to_ts,
    )
    items, next_before = split_page(records, limit, lambda r: r.ts)
    return LogPage(items=[Log.of(r) for r in items], next_before=next_before)


@router.get("/{log_id}")
async def get_log(log_id: str, db: DbDep) -> Log:
    return Log.of(await svc.get_log(db, log_id))


@router.patch("/{log_id}")
async def patch_log(log_id: str, body: LogPatch, db: DbDep) -> Log:
    return Log.of(await svc.update_log(db, log_id, body.changes(), now_ms()))


@router.delete("/{log_id}", status_code=204)
async def delete_log(log_id: str, db: DbDep) -> Response:
    await svc.delete_log(db, log_id)
    return Response(status_code=204)
