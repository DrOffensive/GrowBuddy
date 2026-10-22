"""/api/v1 system event log."""

from __future__ import annotations

from fastapi import APIRouter, Depends

from growbuddy.api.deps import DbDep, require_token
from growbuddy.api.pagination import Limit, split_page
from growbuddy.api.schemas import Event, EventPage
from growbuddy.db.queries import events as event_q
from growbuddy.services.events import Level

router = APIRouter(tags=["events"], dependencies=[Depends(require_token)])


@router.get("/events")
async def list_events(
    db: DbDep,
    limit: Limit = 100,
    before: int | None = None,
    level: Level | None = None,
    source: str | None = None,
) -> EventPage:
    rows = await event_q.list_events(
        db, limit=limit + 1, before=before, level=level.value if level else None, source=source
    )
    items, next_before = split_page(rows, limit, lambda r: int(r["ts"]))
    return EventPage(items=[Event.of(r) for r in items], next_before=next_before)
