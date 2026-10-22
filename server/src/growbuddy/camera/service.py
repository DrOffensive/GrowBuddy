"""Camera service: sole owner of the V4L2 device (scheduled, on-demand, stream).

Demand-driven: the device is opened while anyone holds a lease (stream viewers,
pending captures) and closed after ``idle_close_s`` without leases. One capture
loop publishes the newest frame; every consumer reads from it.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import sqlite3
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path

from growbuddy.camera.sources import CameraError, FrameSource, make_source
from growbuddy.camera.storage import SnapshotKind, store_snapshot
from growbuddy.clock import now_ms
from growbuddy.config import CameraSettings
from growbuddy.db.connection import Database
from growbuddy.db.queries import snapshots as snapshot_q
from growbuddy.errors import AppError
from growbuddy.services.events import Level, record_event

log = logging.getLogger(__name__)

FRAME_TIMEOUT_S = 15.0
_BACKOFF_START_S = 5.0
_BACKOFF_MAX_S = 300.0
# Waits before retrying a failed scheduled snapshot. A USB webcam on a weak power
# supply sometimes doesn't come back from the reset it gets on every open.
_SNAPSHOT_RETRY_S = (30.0, 60.0, 120.0, 300.0)


class CameraUnavailable(AppError):
    status = 503
    code = "camera_unavailable"


class CameraState(StrEnum):
    DISABLED = "disabled"
    IDLE = "idle"  # device closed, nobody needs it
    STARTING = "starting"
    ACTIVE = "active"  # device open, frames flowing
    FAILED = "failed"  # last open/read failed; retrying while demand exists


@dataclass(frozen=True, slots=True)
class Frame:
    jpeg: bytes
    ts: int
    seq: int


@dataclass(frozen=True, slots=True)
class CameraStatus:
    state: CameraState
    error: str | None
    viewers: int
    last_frame_ts: int | None
    last_snapshot_ts: int | None
    next_snapshot_ts: int | None


def next_slot(now: int, interval_ms: int) -> int:
    """Next interval-aligned timestamp strictly after ``now`` (hourly -> on the hour)."""
    return (now // interval_ms + 1) * interval_ms


class CameraService:
    def __init__(
        self,
        db: Database,
        settings: CameraSettings,
        data_dir: Path,
        source_factory: Callable[[CameraSettings], FrameSource] = make_source,
    ) -> None:
        self._db = db
        self._s = settings
        self._data_dir = data_dir
        self._source_factory = source_factory
        self.enabled = settings.enabled or settings.simulate
        self._state = CameraState.IDLE if self.enabled else CameraState.DISABLED
        self._error: str | None = None
        self._leases = 0
        self._viewers = 0
        self._demand = asyncio.Event()
        self._frame: Frame | None = None
        self._seq = 0
        self._new_frame = asyncio.Condition()
        self._tasks: list[asyncio.Task[None]] = []
        self._last_snapshot_ts: int | None = None
        self._next_snapshot_ts: int | None = None

    # ------------------------------------------------------------ lifecycle

    async def start(self) -> None:
        if not self.enabled:
            return
        self._last_snapshot_ts = await snapshot_q.latest_snapshot_ts(self._db)
        self._tasks = [
            asyncio.create_task(self._capture_loop(), name="camera-capture"),
            asyncio.create_task(self._schedule_loop(), name="camera-schedule"),
        ]
        log.info("Camera service started (%s)", "simulated" if self._s.simulate else self._s.device)

    async def stop(self) -> None:
        for task in self._tasks:
            task.cancel()
        for task in self._tasks:
            with contextlib.suppress(asyncio.CancelledError):
                await task
        self._tasks.clear()

    def status(self) -> CameraStatus:
        return CameraStatus(
            state=self._state,
            error=self._error,
            viewers=self._viewers,
            last_frame_ts=self._frame.ts if self._frame else None,
            last_snapshot_ts=self._last_snapshot_ts,
            next_snapshot_ts=self._next_snapshot_ts,
        )

    # ------------------------------------------------------------ consumers

    def _require_enabled(self) -> None:
        if not self.enabled:
            raise CameraUnavailable("Camera is disabled", code="camera_disabled")

    @asynccontextmanager
    async def lease(self) -> AsyncIterator[None]:
        """Keep the device open for the duration of the block."""
        self._require_enabled()
        self._leases += 1
        self._demand.set()
        try:
            yield
        finally:
            self._leases -= 1

    async def next_frame(self, after_seq: int = 0, after_ts: int = 0) -> Frame:
        """Wait for a frame newer than ``after_seq`` and captured at/after ``after_ts``.
        Caller must hold a lease."""

        def ready() -> bool:
            f = self._frame
            return f is not None and f.seq > after_seq and f.ts >= after_ts

        try:
            async with asyncio.timeout(FRAME_TIMEOUT_S), self._new_frame:
                await self._new_frame.wait_for(ready)
        except TimeoutError as exc:
            detail = f": {self._error}" if self._error else ""
            raise CameraUnavailable(f"No frame from camera{detail}") from exc
        assert self._frame is not None
        return self._frame

    async def fresh_frame(self) -> Frame:
        """A frame captured after this call started (opens the device if needed)."""
        requested = now_ms()
        async with self.lease():
            return await self.next_frame(after_ts=requested)

    @property
    def max_viewers(self) -> int:
        return self._s.max_viewers

    async def frames(self, max_viewers: int) -> AsyncIterator[Frame]:
        """Endless stream of new frames for one viewer."""
        self._require_enabled()
        if self._viewers >= max_viewers:
            raise CameraUnavailable(
                f"Too many stream viewers (max {max_viewers})", code="too_many_viewers"
            )
        self._viewers += 1
        try:
            async with self.lease():
                seq = 0
                while True:
                    frame = await self.next_frame(after_seq=seq)
                    seq = frame.seq
                    yield frame
        finally:
            self._viewers -= 1

    async def capture(self, kind: SnapshotKind) -> sqlite3.Row:
        """Store a fresh frame as a snapshot."""
        frame = await self.fresh_frame()
        row = await store_snapshot(
            self._db, self._data_dir, frame.jpeg, frame.ts, kind,
            self._s.thumb_edge_px, self._s.jpeg_quality,
        )  # fmt: skip
        self._last_snapshot_ts = frame.ts
        return row

    # ------------------------------------------------------------ capture loop

    async def _publish(self, jpeg: bytes) -> None:
        async with self._new_frame:
            self._seq += 1
            self._frame = Frame(jpeg, now_ms(), self._seq)
            self._new_frame.notify_all()

    async def _capture_loop(self) -> None:
        backoff = _BACKOFF_START_S
        while True:
            await self._demand.wait()
            source = self._source_factory(self._s)
            self._state = CameraState.STARTING
            try:
                await asyncio.to_thread(source.open)
                for _ in range(self._s.warmup_frames):
                    await asyncio.to_thread(source.read)
                if self._error is not None:
                    await record_event(
                        self._db, Level.INFO, "camera", "camera_recovered", "Camera working again"
                    )
                self._state, self._error, backoff = CameraState.ACTIVE, None, _BACKOFF_START_S
                await self._stream_while_needed(source)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                message = f"{type(exc).__name__}: {exc}"
                if self._error != message:
                    await record_event(
                        self._db, Level.ERROR, "camera", "camera_failed",
                        f"Camera failed, retrying in {backoff:.0f}s: {message}",
                    )  # fmt: skip
                self._state, self._error = CameraState.FAILED, message
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, _BACKOFF_MAX_S)
            finally:
                with contextlib.suppress(Exception):
                    await asyncio.to_thread(source.close)
                if self._state is not CameraState.FAILED:
                    self._state = CameraState.IDLE
                if self._leases == 0:
                    self._demand.clear()

    async def _stream_while_needed(self, source: FrameSource) -> None:
        idle_since: float | None = None
        loop = asyncio.get_running_loop()
        while True:
            if self._leases == 0:
                idle_since = idle_since if idle_since is not None else loop.time()
                if loop.time() - idle_since >= self._s.idle_close_s:
                    return
            else:
                idle_since = None
            jpeg = await asyncio.to_thread(source.read)
            if not jpeg:
                raise CameraError("empty frame")
            await self._publish(jpeg)

    # ------------------------------------------------------------ timelapse

    async def _schedule_loop(self) -> None:
        interval_ms = self._s.interval_s * 1000
        while True:
            self._next_snapshot_ts = next_slot(now_ms(), interval_ms)
            await asyncio.sleep(max(0.0, (self._next_snapshot_ts - now_ms()) / 1000))
            await self._scheduled_capture(self._next_snapshot_ts + interval_ms)

    async def _scheduled_capture(self, next_slot_ts: int) -> None:
        """Take the slot's snapshot, retrying with waits until the next slot is due."""
        error: Exception | None = None
        attempts = 0
        for wait_s in (0.0, *_SNAPSHOT_RETRY_S):
            if now_ms() + wait_s * 1000 >= next_slot_ts:
                break
            await asyncio.sleep(wait_s)
            attempts += 1
            try:
                await self.capture(SnapshotKind.SCHEDULED)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                error = exc
                continue
            if attempts > 1:
                await record_event(
                    self._db, Level.INFO, "camera", "snapshot_retried",
                    f"Scheduled snapshot taken on attempt {attempts}",
                )  # fmt: skip
            return
        await record_event(
            self._db, Level.WARNING, "camera", "snapshot_missed",
            f"Scheduled snapshot failed after {attempts} attempt(s): {error}",
        )  # fmt: skip
