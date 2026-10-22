"""Environment snapshots for the app's notification widget.

While the app has asked for them (``PUT /push-endpoint`` with ``environment``),
a compact copy of ``/environment/current`` is published to the app's push topic
every ``ntfy.environment_push_s`` and right after an alert starts or ends.
"""

from __future__ import annotations

import asyncio
import contextlib
import dataclasses
import logging
from typing import Any

from growbuddy.alerts.ntfy import NotifyError, NtfyNotifier
from growbuddy.alerts.settings import load_alert_settings
from growbuddy.clock import now_ms
from growbuddy.config import Settings
from growbuddy.db.connection import Database
from growbuddy.db.queries.settings import get_setting
from growbuddy.overview import current_environment

log = logging.getLogger(__name__)

PUSH_ENVIRONMENT_KEY = "push_environment"  # settings table: the app wants snapshots


async def environment_snapshot(db: Database, settings: Settings, now: int) -> dict[str, Any]:
    """Only what the widget draws: reading, effective ranges and levels per sensor."""
    overview = await current_environment(db, await load_alert_settings(db, settings.alerts), now)
    return {
        "type": "environment",
        "ts": now,
        "sensors": [
            {
                "id": s.sensor["id"],
                "name": s.sensor["name"],
                "online": s.online,
                "reading": dataclasses.asdict(s.reading) if s.reading else None,
                "ranges": {m.value: dataclasses.asdict(r) for m, r in s.ranges.items()},
                "levels": {m.value: lvl.value if lvl else None for m, lvl in s.levels.items()},
            }
            for s in overview.sensors
        ],
    }


class EnvironmentPusher:
    def __init__(self, db: Database, settings: Settings, notifier: NtfyNotifier | None) -> None:
        self._db = db
        self._settings = settings
        self._notifier = notifier
        self._wake = asyncio.Event()
        self._task: asyncio.Task[None] | None = None

    def kick(self) -> None:
        """Push now (alert change, widget just switched on)."""
        self._wake.set()

    async def start(self) -> None:
        if self._notifier is not None:
            self._task = asyncio.create_task(self._loop(), name="environment-push")

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._task
            self._task = None

    async def push_once(self) -> bool:
        """Publish one snapshot if the app wants them; False when nothing was sent."""
        if self._notifier is None or self._notifier.push_topic is None:
            return False
        if not await get_setting(self._db, PUSH_ENVIRONMENT_KEY, False):
            return False
        snapshot = await environment_snapshot(self._db, self._settings, now_ms())
        await self._notifier.push_to_app(snapshot)
        return True

    async def _loop(self) -> None:
        interval = self._settings.ntfy.environment_push_s
        while True:
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(self._wake.wait(), timeout=interval)
            self._wake.clear()
            try:
                await self.push_once()
            except NotifyError as exc:
                log.debug("Environment push failed: %s", exc)
            except Exception:
                log.exception("Environment push failed")
