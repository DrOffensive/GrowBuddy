"""App factory and lifespan: opens the DB, seeds reference data, starts/stops background
services, mounts /api/v1.

Run with the factory flag (one worker only, see docs/ARCHITECTURE.md):
    uvicorn --factory growbuddy.main:create_app --workers 1
"""

from __future__ import annotations

import logging
import time
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from growbuddy import __version__
from growbuddy.alerts.evaluator import AlertEvaluator
from growbuddy.alerts.ntfy import NtfyNotifier, load_push_topic
from growbuddy.api.errors import install_error_handlers
from growbuddy.api.routes import (
    alerts,
    backups,
    camera,
    environment,
    events,
    logs,
    photos,
    plants,
    reference,
    sensors,
    system,
)
from growbuddy.camera.service import CameraService, CameraState
from growbuddy.config import Settings
from growbuddy.db.connection import Database
from growbuddy.db.seed import seed_reference_data
from growbuddy.sensors.scanner import SensorHub
from growbuddy.services.environment_push import EnvironmentPusher
from growbuddy.services.housekeeping import Housekeeping

API_PREFIX = "/api/v1"

log = logging.getLogger("growbuddy")


def _configure_logging() -> None:
    # uvicorn only configures its own loggers; give ours a handler (journald adds timestamps).
    if not log.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(logging.Formatter("%(levelname)s %(name)s: %(message)s"))
        log.addHandler(handler)
        log.setLevel(logging.INFO)
        log.propagate = False


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings()  # type: ignore[call-arg]  # filled from TOML/env
    _configure_logging()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        db = Database(settings.server.db_path)
        await db.open()
        await seed_reference_data(db)
        hub = SensorHub(db, settings.ble)
        await hub.start()
        cam = CameraService(db, settings.camera, settings.server.data_dir)
        await cam.start()
        housekeeping = Housekeeping(
            db, settings.server.data_dir, settings.retention, settings.backup
        )
        await housekeeping.start()
        notifier = NtfyNotifier(settings.ntfy) if settings.ntfy.enabled else None
        if notifier is not None:
            notifier.push_topic = await load_push_topic(db, settings.ntfy)
        environment_pusher = EnvironmentPusher(db, settings, notifier)
        evaluator = AlertEvaluator(
            db,
            settings.alerts,
            notifier,
            on_change=environment_pusher.kick,
            camera_failed=(lambda: cam.status().state is CameraState.FAILED)
            if cam.enabled
            else None,
        )
        await evaluator.start()
        await environment_pusher.start()
        app.state.settings = settings
        app.state.db = db
        app.state.sensors = hub
        app.state.camera = cam
        app.state.alerts = evaluator
        app.state.notifier = notifier
        app.state.environment_pusher = environment_pusher
        app.state.housekeeping = housekeeping
        app.state.started_monotonic = time.monotonic()
        log.info("GrowBuddy %s started (data_dir=%s)", __version__, settings.server.data_dir)
        try:
            yield
        finally:
            await environment_pusher.stop()
            await evaluator.stop()
            if notifier is not None:
                await notifier.close()
            await housekeeping.stop()
            await cam.stop()
            await hub.stop()
            await db.close()
            log.info("GrowBuddy stopped")

    app = FastAPI(
        title="GrowBuddy",
        version=__version__,
        lifespan=lifespan,
        openapi_url=f"{API_PREFIX}/openapi.json",
        docs_url=f"{API_PREFIX}/docs",
        redoc_url=None,
    )
    install_error_handlers(app)
    app.include_router(system.public, prefix=API_PREFIX)
    app.include_router(system.router, prefix=API_PREFIX)
    for module in (
        sensors, environment, plants, logs, photos, camera, alerts, events, backups, reference,
    ):  # fmt: skip
        app.include_router(module.router, prefix=API_PREFIX)
    return app
