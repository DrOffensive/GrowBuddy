"""Schedules background housekeeping: hourly rollup, daily retention and the nightly
DB snapshot.

Times are local (the Pi's timezone). Daily jobs missed while the server was
down are caught up shortly after startup.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
from collections.abc import Awaitable, Callable
from datetime import datetime, time, timedelta
from pathlib import Path

from growbuddy.clock import now_ms
from growbuddy.config import BackupSettings, RetentionSettings
from growbuddy.db.connection import Database
from growbuddy.db.queries.settings import get_setting, put_setting
from growbuddy.services.events import Level, record_event
from growbuddy.services.retention import run_retention
from growbuddy.services.rollup import run_rollup
from growbuddy.services.snapshot import list_backups, run_snapshot

log = logging.getLogger(__name__)

ROLLUP_MINUTE = 2  # hh:02, after the hour has closed
LAST_RETENTION_KEY = "retention.last_run"
_CATCH_UP_AFTER_MS = 26 * 3_600_000


def next_daily(now: datetime, at: time) -> datetime:
    """Next local occurrence of ``at`` strictly after ``now`` (``now`` is tz-aware)."""
    candidate = now.replace(hour=at.hour, minute=at.minute, second=0, microsecond=0)
    return candidate if candidate > now else candidate + timedelta(days=1)


def next_hourly(now: datetime, minute: int) -> datetime:
    candidate = now.replace(minute=minute, second=0, microsecond=0)
    return candidate if candidate > now else candidate + timedelta(hours=1)


async def _sleep_until(when: datetime) -> None:
    await asyncio.sleep(max(0.0, (when - datetime.now().astimezone()).total_seconds()))


class Housekeeping:
    def __init__(
        self,
        db: Database,
        data_dir: Path,
        retention: RetentionSettings,
        backup: BackupSettings,
    ) -> None:
        self._db = db
        self._data_dir = data_dir
        self._retention = retention
        self._backup = backup
        self._tasks: list[asyncio.Task[None]] = []

    async def start(self) -> None:
        self._tasks = [
            asyncio.create_task(self._rollup_loop(), name="rollup"),
            asyncio.create_task(self._retention_loop(), name="retention"),
            asyncio.create_task(self._snapshot_loop(), name="db-snapshot"),
        ]

    async def stop(self) -> None:
        for task in self._tasks:
            task.cancel()
        for task in self._tasks:
            with contextlib.suppress(asyncio.CancelledError):
                await task
        self._tasks.clear()

    async def _guarded(self, name: str, job: Callable[[], Awaitable[object]]) -> None:
        try:
            await job()
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            log.exception("%s failed", name)
            await record_event(
                self._db, Level.ERROR, "system", f"{name}_failed", f"{name} failed: {exc}"
            )

    async def rollup_now(self) -> None:
        await run_rollup(self._db, now_ms())

    async def retention_now(self) -> None:
        now = now_ms()
        await run_retention(self._db, self._data_dir, self._retention, now)
        await put_setting(self._db, LAST_RETENTION_KEY, now)

    async def snapshot_now(self) -> None:
        await run_snapshot(self._db, self._data_dir, self._backup)

    async def _rollup_loop(self) -> None:
        await self._guarded("rollup", self.rollup_now)  # catch up after downtime
        while True:
            await _sleep_until(next_hourly(datetime.now().astimezone(), ROLLUP_MINUTE))
            await self._guarded("rollup", self.rollup_now)

    async def _retention_loop(self) -> None:
        last = await get_setting(self._db, LAST_RETENTION_KEY)
        if last is None or now_ms() - int(last) > _CATCH_UP_AFTER_MS:
            await asyncio.sleep(30)  # let startup settle; rollup catch-up runs first
            await self._guarded("retention", self.retention_now)
        while True:
            await _sleep_until(next_daily(datetime.now().astimezone(), self._retention.run_time))
            await self._guarded("retention", self.retention_now)

    async def _snapshot_loop(self) -> None:
        backups = list_backups(self._data_dir)
        if not backups or now_ms() - backups[0].ts > _CATCH_UP_AFTER_MS:
            await asyncio.sleep(60)  # after the retention catch-up
            await self._guarded("snapshot", self.snapshot_now)
        while True:
            await _sleep_until(next_daily(datetime.now().astimezone(), self._backup.snapshot_time))
            await self._guarded("snapshot", self.snapshot_now)
