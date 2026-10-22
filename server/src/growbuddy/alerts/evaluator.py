"""Evaluator loop: debounce, cooldown, alert state transitions, ntfy outbox.

Every tick observes a level per key (sensor_id, metric), advances the pure
state machine in ``rules.step`` and persists transitions to ``alerts``. Active
alerts are re-read from the DB each tick, so restarts never re-notify and
retiring a sensor (which closes its alerts) needs no extra coordination.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import sqlite3
from collections.abc import Callable
from dataclasses import dataclass

from growbuddy.alerts.messages import messages
from growbuddy.alerts.ntfy import Notification, Notifier, NotifyError
from growbuddy.alerts.rules import (
    BATTERY_HYSTERESIS_PCT,
    Pending,
    delay_ms,
    describe,
    describe_resolved,
    step,
)
from growbuddy.alerts.settings import load_alert_settings
from growbuddy.clock import now_ms
from growbuddy.config import AlertSettings
from growbuddy.db.connection import Database
from growbuddy.db.queries import alerts as alert_q
from growbuddy.db.queries import sensors as sensor_q
from growbuddy.domain.environment import AlarmLevel, Metric, Range, evaluate, vpd_kpa
from growbuddy.services.events import Level, record_event
from growbuddy.services.ranges import effective_ranges

log = logging.getLogger(__name__)

Key = tuple[int | None, str]
OUTBOX_MAX_AGE_MS = 3_600_000
_PRIORITY = {AlarmLevel.CRITICAL: 5, AlarmLevel.WARNING: 4, AlarmLevel.NORMAL: 3}
_TAGS = {
    AlarmLevel.CRITICAL: ["rotating_light"],
    AlarmLevel.WARNING: ["warning"],
    AlarmLevel.NORMAL: ["white_check_mark"],
}


@dataclass(frozen=True, slots=True)
class Observation:
    sensor_id: int | None
    sensor_name: str
    metric: str
    level: AlarmLevel
    value: float | None = None
    band: tuple[float | None, float | None] | None = None
    debounced: bool = True


@dataclass(frozen=True, slots=True)
class EvaluatorStatus:
    active_alerts: int
    outbox: int
    last_notify_error: str | None


def _band(rng: Range, level: AlarmLevel) -> tuple[float | None, float | None]:
    if level is AlarmLevel.CRITICAL:
        return rng.crit_min, rng.crit_max
    return rng.warn_min, rng.warn_max


class AlertEvaluator:
    def __init__(
        self,
        db: Database,
        defaults: AlertSettings,
        notifier: Notifier | None,
        camera_failed: Callable[[], bool] | None = None,
        on_change: Callable[[], None] | None = None,
    ) -> None:
        self._db = db
        self._on_change = on_change  # called after an alert starts, changes level or ends
        self._defaults = defaults
        self._notifier = notifier
        self._camera_failed = camera_failed
        self._pending: dict[Key, Pending] = {}
        self._outbox: list[Notification] = []
        self._last_error: str | None = None
        self._active_count = 0
        self._task: asyncio.Task[None] | None = None

    # ------------------------------------------------------------ lifecycle

    async def start(self) -> None:
        self._task = asyncio.create_task(self._loop(), name="alert-evaluator")

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._task
            self._task = None

    def status(self) -> EvaluatorStatus:
        return EvaluatorStatus(self._active_count, len(self._outbox), self._last_error)

    async def _loop(self) -> None:
        while True:
            try:
                await self.tick(now_ms())
            except asyncio.CancelledError:
                raise
            except Exception:
                log.exception("Alert evaluation failed")
            await asyncio.sleep(self._defaults.evaluate_every_s)

    # ------------------------------------------------------------ evaluation

    async def tick(self, now: int) -> None:
        settings = await load_alert_settings(self._db, self._defaults)
        active = {(r["sensor_id"], r["metric"]): r for r in await alert_q.active_alerts(self._db)}
        observations, silenced = await self._observe(settings, active, now)

        for key in silenced:  # sensor alerts switched off: close quietly
            if key in active:
                await alert_q.close_alert(self._db, active.pop(key)["id"], now)
            self._pending.pop(key, None)

        seen: set[Key] = set()
        for obs in observations:
            key = (obs.sensor_id, obs.metric)
            seen.add(key)
            row = active.get(key)
            current = AlarmLevel(row["level"]) if row else None
            debounce_ms = (
                delay_ms(current, obs.level, settings.debounce_s, settings.warning_debounce_s)
                if obs.debounced
                else 0
            )
            transition, pending = step(current, self._pending.get(key), obs.level, now, debounce_ms)
            if pending is None:
                self._pending.pop(key, None)
            else:
                self._pending[key] = pending
            if transition is not None:
                await self._apply(obs, row, transition.level, transition.since, now, settings)
                if self._on_change is not None:
                    self._on_change()
            elif row is not None:
                await self._maybe_repeat(obs, row, now, settings)

        for key in list(self._pending):
            if key not in seen:
                del self._pending[key]
        self._active_count = len(await alert_q.active_alerts(self._db))
        await self._flush(now)

    async def _observe(
        self, settings: AlertSettings, active: dict[Key, sqlite3.Row], now: int
    ) -> tuple[list[Observation], set[Key]]:
        observations: list[Observation] = []
        silenced: set[Key] = set()
        for sensor in await sensor_q.list_sensors(self._db):
            sid, name = sensor["id"], sensor["name"]
            if not sensor["alerts_enabled"]:
                silenced |= {k for k in active if k[0] == sid}
                continue
            ref = sensor["last_seen"] or sensor["adopted_ts"]
            offline = now - ref > settings.offline_after_s * 1000
            observations.append(
                Observation(sid, name, "sensor_offline",
                            AlarmLevel.CRITICAL if offline else AlarmLevel.NORMAL, debounced=False)
            )  # fmt: skip
            if offline:
                continue  # last values are stale; keep range/battery state as is

            battery = sensor["last_battery"]
            if battery is not None:
                # Once low, only clear a few percent above the threshold (no flapping).
                hysteresis = BATTERY_HYSTERESIS_PCT if (sid, "battery_low") in active else 0
                low = battery <= settings.battery_low_pct + hysteresis
                observations.append(
                    Observation(sid, name, "battery_low",
                                AlarmLevel.WARNING if low else AlarmLevel.NORMAL, float(battery),
                                debounced=False)
                )  # fmt: skip

            reading = await sensor_q.latest_reading(self._db, sid)
            if reading is None:
                continue
            ranges = (await effective_ranges(self._db, sensor, now)).ranges
            temp, hum = reading["temperature_c"], reading["humidity_pct"]
            values = {
                Metric.TEMPERATURE: temp,
                Metric.HUMIDITY: hum,
                Metric.VPD: vpd_kpa(temp, hum) if temp is not None and hum is not None else None,
            }
            for metric, value in values.items():
                if value is None:
                    continue
                level = evaluate(value, ranges[metric])
                band = _band(
                    ranges[metric], level if level is not AlarmLevel.NORMAL else AlarmLevel.WARNING
                )
                observations.append(
                    Observation(sid, name, metric.value, level, round(value, 3), band)
                )

        if self._camera_failed is not None:
            failed = self._camera_failed()
            observations.append(
                Observation(None, "Camera", "camera",
                            AlarmLevel.WARNING if failed else AlarmLevel.NORMAL, debounced=False)
            )  # fmt: skip
        return observations, silenced

    # ------------------------------------------------------------ transitions

    async def _apply(
        self,
        obs: Observation,
        row: sqlite3.Row | None,
        level: AlarmLevel,
        since: int,
        now: int,
        settings: AlertSettings,
    ) -> None:
        if row is not None:
            await alert_q.close_alert(self._db, row["id"], now)
        if level is AlarmLevel.NORMAL:
            log.info("Alert resolved: %s %s", obs.sensor_name, obs.metric)
            if settings.notify_resolved:
                title, message = describe_resolved(
                    obs.sensor_name, obs.metric, messages(settings.language)
                )
                self._enqueue(title, message, AlarmLevel.NORMAL, now)
            return
        alert_id = await alert_q.open_alert(
            self._db, obs.sensor_id, obs.metric, level.value, since, obs.value, obs.band
        )
        msg = messages(settings.language)
        title, message = describe(obs.sensor_name, obs.metric, level, obs.value, obs.band, msg)
        await record_event(
            self._db, Level.WARNING if level is AlarmLevel.WARNING else Level.ERROR, "alerts",
            f"{obs.metric}_{level.value}", f"{title}. {message}",
            {"alert_id": alert_id, "sensor_id": obs.sensor_id, "value": obs.value},
        )  # fmt: skip
        if self._wants(level, settings):
            self._enqueue(title, message, level, now)
            await alert_q.mark_notified(self._db, alert_id, now)

    async def _maybe_repeat(
        self, obs: Observation, row: sqlite3.Row, now: int, settings: AlertSettings
    ) -> None:
        if obs.value is not None and obs.value != row["value"]:
            await alert_q.update_value(self._db, row["id"], obs.value)
        level = AlarmLevel(row["level"])
        last = row["notified_ts"]
        if not self._wants(level, settings):
            return
        if last is None or now - last >= settings.repeat_after_s * 1000:
            msg = messages(settings.language)
            title, message = describe(obs.sensor_name, obs.metric, level, obs.value, obs.band, msg)
            self._enqueue(msg("repeat.title", title=title) if last is not None else title, message,
                          level, now)  # fmt: skip
            await alert_q.mark_notified(self._db, row["id"], now)

    @staticmethod
    def _wants(level: AlarmLevel, settings: AlertSettings) -> bool:
        return settings.notify_critical if level is AlarmLevel.CRITICAL else settings.notify_warning

    # ------------------------------------------------------------ delivery

    def _enqueue(self, title: str, message: str, level: AlarmLevel, now: int) -> None:
        if self._notifier is None:
            return
        self._outbox.append(Notification(title, message, _PRIORITY[level], _TAGS[level], now))

    async def _flush(self, now: int) -> None:
        if self._notifier is None:
            return
        self._outbox = [n for n in self._outbox if now - n.created_ts <= OUTBOX_MAX_AGE_MS]
        while self._outbox:
            try:
                await self._notifier.send(self._outbox[0])
            except NotifyError as exc:
                if self._last_error != str(exc):
                    await record_event(self._db, Level.WARNING, "alerts", "notify_failed", str(exc))
                self._last_error = str(exc)
                return  # retry on the next tick
            self._outbox.pop(0)
            self._last_error = None

    async def send_test(self) -> None:
        """Send a test notification now; raises NotifyError (or RuntimeError if disabled)."""
        if self._notifier is None:
            raise RuntimeError("ntfy is disabled")
        msg = messages((await load_alert_settings(self._db, self._defaults)).language)
        await self._notifier.send(
            Notification(msg("test.title"), msg("test.message"), 3, ["seedling"], now_ms())
        )
