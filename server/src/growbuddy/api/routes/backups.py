"""/api/v1 backup routes: nightly DB snapshots pulled by the laptop."""

from __future__ import annotations

from dataclasses import asdict

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from growbuddy.api.deps import DbDep, SettingsDep, require_token
from growbuddy.services.snapshot import list_backups, run_snapshot

router = APIRouter(prefix="/backups", tags=["backups"], dependencies=[Depends(require_token)])


class Backup(BaseModel):
    name: str
    ts: int
    size_bytes: int


@router.get("")
async def backups(settings: SettingsDep) -> list[Backup]:
    return [Backup(**asdict(b)) for b in list_backups(settings.server.data_dir)]


@router.post("", status_code=201)
async def snapshot_now(db: DbDep, settings: SettingsDep) -> Backup:
    """Write today's snapshot now (e.g. before an upgrade); replaces today's file."""
    result = await run_snapshot(db, settings.server.data_dir, settings.backup)
    return Backup(**asdict(result))
