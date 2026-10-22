"""/api/v1 system routes: /health, /status."""

from __future__ import annotations

import shutil
import time
from dataclasses import asdict

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel

from growbuddy import __version__
from growbuddy.api.deps import AlertsDep, CameraDep, DbDep, HubDep, SettingsDep, require_token
from growbuddy.services.snapshot import list_backups

public = APIRouter(tags=["system"])
router = APIRouter(tags=["system"], dependencies=[Depends(require_token)])


class Health(BaseModel):
    status: str
    version: str


class BleStatus(BaseModel):
    source: str
    state: str
    error: str | None
    last_advert_ts: int | None
    adopted: int
    discovered: int
    dropped_readings: int


class CameraStatus(BaseModel):
    state: str
    error: str | None
    viewers: int
    last_frame_ts: int | None
    last_snapshot_ts: int | None
    next_snapshot_ts: int | None


class AlertsStatus(BaseModel):
    active_alerts: int
    outbox: int
    last_notify_error: str | None


class Status(BaseModel):
    version: str
    uptime_s: int
    schema_version: int
    db_size_bytes: int
    disk_free_bytes: int
    ble: BleStatus
    camera: CameraStatus
    alerts: AlertsStatus
    last_backup: str | None
    last_backup_ts: int | None


@public.get("/health")
async def health() -> Health:
    return Health(status="ok", version=__version__)


@router.get("/status")
async def status(
    request: Request,
    settings: SettingsDep,
    db: DbDep,
    hub: HubDep,
    camera: CameraDep,
    alerts: AlertsDep,
) -> Status:
    db_path = settings.server.db_path
    backups = list_backups(settings.server.data_dir)
    db_size = sum(
        p.stat().st_size for p in (db_path, db_path.with_name(db_path.name + "-wal")) if p.exists()
    )
    return Status(
        version=__version__,
        uptime_s=int(time.monotonic() - request.app.state.started_monotonic),
        schema_version=await db.schema_version(),
        db_size_bytes=db_size,
        disk_free_bytes=shutil.disk_usage(settings.server.data_dir).free,
        ble=BleStatus(**asdict(hub.status())),
        camera=CameraStatus(**asdict(camera.status())),
        alerts=AlertsStatus(**asdict(alerts.status())),
        last_backup=backups[0].name if backups else None,
        last_backup_ts=backups[0].ts if backups else None,
    )
