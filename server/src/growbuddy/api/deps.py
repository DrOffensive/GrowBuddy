"""Bearer-token auth and shared request dependencies."""

from __future__ import annotations

import secrets
from typing import Annotated

from fastapi import Depends, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from growbuddy.alerts.evaluator import AlertEvaluator
from growbuddy.alerts.ntfy import NtfyNotifier
from growbuddy.camera.service import CameraService
from growbuddy.config import Settings
from growbuddy.db.connection import Database
from growbuddy.errors import Unauthorized
from growbuddy.sensors.scanner import SensorHub
from growbuddy.services.environment_push import EnvironmentPusher
from growbuddy.services.housekeeping import Housekeeping

_bearer = HTTPBearer(auto_error=False)


def get_settings(request: Request) -> Settings:
    settings: Settings = request.app.state.settings
    return settings


def get_db(request: Request) -> Database:
    db: Database = request.app.state.db
    return db


def get_hub(request: Request) -> SensorHub:
    hub: SensorHub = request.app.state.sensors
    return hub


def get_camera(request: Request) -> CameraService:
    camera: CameraService = request.app.state.camera
    return camera


def get_alerts(request: Request) -> AlertEvaluator:
    alerts: AlertEvaluator = request.app.state.alerts
    return alerts


def get_notifier(request: Request) -> NtfyNotifier | None:
    notifier: NtfyNotifier | None = request.app.state.notifier
    return notifier


def get_environment_pusher(request: Request) -> EnvironmentPusher:
    pusher: EnvironmentPusher = request.app.state.environment_pusher
    return pusher


def get_housekeeping(request: Request) -> Housekeeping:
    housekeeping: Housekeeping = request.app.state.housekeeping
    return housekeeping


def require_token(
    settings: Annotated[Settings, Depends(get_settings)],
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer)],
) -> None:
    expected = settings.server.api_token.encode()
    if credentials is None or not secrets.compare_digest(
        credentials.credentials.encode(), expected
    ):
        raise Unauthorized("Missing or invalid bearer token")


SettingsDep = Annotated[Settings, Depends(get_settings)]
DbDep = Annotated[Database, Depends(get_db)]
HubDep = Annotated[SensorHub, Depends(get_hub)]
CameraDep = Annotated[CameraService, Depends(get_camera)]
AlertsDep = Annotated[AlertEvaluator, Depends(get_alerts)]
HousekeepingDep = Annotated[Housekeeping, Depends(get_housekeeping)]
NotifierDep = Annotated[NtfyNotifier | None, Depends(get_notifier)]
EnvironmentPusherDep = Annotated[EnvironmentPusher, Depends(get_environment_pusher)]
