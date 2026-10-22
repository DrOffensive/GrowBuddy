"""Sensor hub: advert sources (BlueZ via bleak, or a simulator) feeding the processor;
stores readings for adopted sensors and keeps unadopted ones in the discovered list.

Advert callbacks run on the event loop and only do in-memory work; readings to
store go through a bounded queue to a single writer task.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import math
import random
import struct
from collections.abc import Callable, Coroutine, Mapping
from dataclasses import dataclass
from enum import StrEnum
from typing import Any

from bleak import BleakScanner
from bleak.backends.device import BLEDevice
from bleak.backends.scanner import AdvertisementData

from growbuddy.clock import now_ms
from growbuddy.config import BleSettings
from growbuddy.db.connection import Database
from growbuddy.db.queries import sensors as queries
from growbuddy.sensors.bthome import BTHOME_SERVICE_UUID
from growbuddy.sensors.processor import AdvertProcessor, ReadingRecord, SeenDevice
from growbuddy.services.events import Level, record_event

log = logging.getLogger(__name__)

Clock = Callable[[], int]
_QUEUE_SIZE = 1000
_BACKOFF_START_S = 5.0
_BACKOFF_MAX_S = 300.0
_WATCHDOG_TICK_S = 30.0
# A wedged controller can leave BlueZ calls unanswered forever; without a limit the
# supervisor would hang there and never retry.
_START_TIMEOUT_S = 30.0
_STOP_TIMEOUT_S = 10.0


class SourceKind(StrEnum):
    NONE = "none"
    BLUEZ = "bluez"
    SIMULATED = "simulated"


class SourceState(StrEnum):
    STOPPED = "stopped"
    STARTING = "starting"
    RUNNING = "running"
    FAILED = "failed"


@dataclass(frozen=True, slots=True)
class HubStatus:
    source: SourceKind
    state: SourceState
    error: str | None
    last_advert_ts: int | None
    adopted: int
    discovered: int
    dropped_readings: int


class SensorHub:
    def __init__(self, db: Database, settings: BleSettings, clock: Clock = now_ms) -> None:
        self._db = db
        self._settings = settings
        self._clock = clock
        self.processor = AdvertProcessor(discovery_ttl_ms=settings.discovery_ttl_s * 1000)
        self._queue: asyncio.Queue[ReadingRecord] = asyncio.Queue(maxsize=_QUEUE_SIZE)
        self._tasks: list[asyncio.Task[None]] = []
        self._state = SourceState.STOPPED
        self._error: str | None = None
        self._dropped = 0
        if settings.simulate:
            self.source = SourceKind.SIMULATED
        elif settings.enabled:
            self.source = SourceKind.BLUEZ
        else:
            self.source = SourceKind.NONE

    # ------------------------------------------------------------ lifecycle

    async def start(self) -> None:
        await self.reload_adopted()
        self._tasks.append(asyncio.create_task(self._write_loop(), name="sensor-writer"))
        if self.source is SourceKind.BLUEZ:
            self._tasks.append(asyncio.create_task(self._run_bluez(), name="ble-scanner"))
        elif self.source is SourceKind.SIMULATED:
            self._tasks.append(asyncio.create_task(self._run_simulator(), name="ble-simulator"))
        log.info("Sensor hub started (source=%s)", self.source)

    async def stop(self) -> None:
        for task in self._tasks:
            task.cancel()
        for task in self._tasks:
            with contextlib.suppress(asyncio.CancelledError):
                await task
        self._tasks.clear()
        await self._flush()
        self._state = SourceState.STOPPED

    async def reload_adopted(self) -> None:
        """Refresh the adopted-MAC cache; call after adopting or retiring a sensor."""
        self.processor.set_adopted(await queries.adopted_macs(self._db))

    # ------------------------------------------------------------ queries

    def discovered(self, within_s: int | None = None) -> list[SeenDevice]:
        within_ms = None if within_s is None else within_s * 1000
        return self.processor.discovered(self._clock(), within_ms)

    def status(self) -> HubStatus:
        now = self._clock()
        return HubStatus(
            source=self.source,
            state=self._state,
            error=self._error,
            last_advert_ts=self.processor.last_bthome_ms,
            adopted=self.processor.adopted_count,
            discovered=len(self.processor.discovered(now)),
            dropped_readings=self._dropped,
        )

    # ------------------------------------------------------------ ingest

    def handle_advert(
        self, mac: str, service_data: Mapping[str, bytes], rssi: int | None, name: str | None
    ) -> None:
        record = self.processor.handle(mac, service_data, rssi, name, self._clock())
        if record is None:
            return
        try:
            self._queue.put_nowait(record)
        except asyncio.QueueFull:
            self._dropped += 1
            if self._dropped % 100 == 1:
                log.warning("Reading queue full; dropped %d readings so far", self._dropped)

    async def _store(self, rec: ReadingRecord) -> None:
        r = rec.reading
        await queries.insert_reading(
            self._db, rec.sensor_id, rec.ts, r.temperature_c, r.humidity_pct, r.battery_pct,
            r.voltage_v, rec.rssi,
        )  # fmt: skip

    async def _write_loop(self) -> None:
        while True:
            rec = await self._queue.get()
            try:
                await self._store(rec)
            except Exception:
                log.exception("Failed to store reading from %s", rec.mac)

    async def _flush(self) -> None:
        while not self._queue.empty():
            rec = self._queue.get_nowait()
            with contextlib.suppress(Exception):
                await self._store(rec)

    # ------------------------------------------------------------ BlueZ source

    def _on_bleak_advert(self, device: BLEDevice, adv: AdvertisementData) -> None:
        if BTHOME_SERVICE_UUID in adv.service_data:
            self.handle_advert(device.address, adv.service_data, adv.rssi, adv.local_name)

    async def _run_bluez(self) -> None:
        """Supervise the scanner: restart with backoff on errors or when adverts stall."""
        backoff = _BACKOFF_START_S
        while True:
            self._state = SourceState.STARTING
            scanner = BleakScanner(detection_callback=self._on_bleak_advert)
            try:
                await _bounded(scanner.start(), _START_TIMEOUT_S, "start scanning")
                started = self._clock()
                await record_event(self._db, Level.INFO, "ble", "scanner_started", "BLE scanning")
                self._state, self._error, backoff = SourceState.RUNNING, None, _BACKOFF_START_S
                await self._watch(started)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                message = f"{type(exc).__name__}: {exc}"
                if self._error != message:
                    await record_event(
                        self._db, Level.ERROR, "ble", "scanner_failed",
                        f"BLE scanner failed, retrying in {backoff:.0f}s: {message}",
                    )  # fmt: skip
                self._state, self._error = SourceState.FAILED, message
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, _BACKOFF_MAX_S)
            finally:
                with contextlib.suppress(Exception):
                    await _bounded(scanner.stop(), _STOP_TIMEOUT_S, "stop scanning")

    async def _watch(self, started_ms: int) -> None:
        """Return (to trigger a restart) when adopted sensors exist but no BTHome
        advert has arrived for ``watchdog_s``."""
        limit_ms = self._settings.watchdog_s * 1000
        while True:
            await asyncio.sleep(_WATCHDOG_TICK_S)
            if not self.processor.adopted_count:
                continue
            last = max(self.processor.last_bthome_ms or 0, started_ms)
            if self._clock() - last > limit_ms:
                await record_event(
                    self._db, Level.WARNING, "ble", "scanner_stalled",
                    f"No BTHome adverts for {self._settings.watchdog_s}s; restarting scanner",
                )  # fmt: skip
                return

    # ------------------------------------------------------------ simulator

    async def _run_simulator(self) -> None:
        """Two fake sensors (tent-like and drying-like) sending real BTHome frames."""
        devices = [
            ("02:00:00:00:00:01", "SIM tent", 24.0, 60.0, -62),
            ("02:00:00:00:00:02", "SIM drying", 18.0, 58.0, -74),
        ]
        rng = random.Random()
        packet = 0
        self._state = SourceState.RUNNING
        while True:
            packet = (packet + 1) % 256
            phase = (self._clock() / 3_600_000) * 2 * math.pi  # one cycle per hour
            for i, (mac, name, temp, hum, rssi) in enumerate(devices):
                t = temp + 1.5 * math.sin(phase + i) + rng.uniform(-0.1, 0.1)
                h = hum + 5.0 * math.cos(phase + i) + rng.uniform(-0.3, 0.3)
                payload = simulated_frame(packet, 90 - i * 25, t, h, 2.95)
                self.handle_advert(
                    mac, {BTHOME_SERVICE_UUID: payload}, rssi + rng.randint(-3, 3), name
                )
            await asyncio.sleep(5)


async def _bounded(call: Coroutine[Any, Any, None], timeout_s: float, what: str) -> None:
    """Awaits a BlueZ call, turning no answer within ``timeout_s`` into a normal failure."""
    try:
        await asyncio.wait_for(call, timeout_s)
    except TimeoutError:
        raise RuntimeError(f"BlueZ didn't answer '{what}' within {timeout_s:.0f}s") from None


def simulated_frame(packet: int, battery: int, temp_c: float, hum_pct: float, volt: float) -> bytes:
    """BTHome v2 frame as sent by pvvx firmware (unencrypted)."""
    return (
        bytes([0x40, 0x00, packet, 0x01, battery])
        + b"\x02" + struct.pack("<h", round(temp_c * 100))
        + b"\x03" + struct.pack("<H", round(hum_pct * 100))
        + b"\x0c" + struct.pack("<H", round(volt * 1000))
    )  # fmt: skip
