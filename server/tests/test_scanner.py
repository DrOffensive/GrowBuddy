from __future__ import annotations

import asyncio
from typing import Any

import pytest

from growbuddy.config import BleSettings
from growbuddy.db.connection import Database
from growbuddy.db.queries import sensors as queries
from growbuddy.sensors import scanner as scanner_mod
from growbuddy.sensors.bthome import BTHOME_SERVICE_UUID
from growbuddy.sensors.scanner import SensorHub, SourceKind, SourceState, simulated_frame

MAC = "A4:C1:38:12:34:56"


class FakeClock:
    def __init__(self, now: int = 1_000_000) -> None:
        self.now = now

    def __call__(self) -> int:
        return self.now


def advert(hub: SensorHub, packet: int, mac: str = MAC, temp: float = 21.5) -> None:
    hub.handle_advert(mac, {BTHOME_SERVICE_UUID: simulated_frame(packet, 77, temp, 48.0, 2.9)},
                      -66, "ATC")  # fmt: skip


async def test_hub_stores_readings_for_adopted_sensors(db: Database) -> None:
    sensor_id = await queries.adopt_sensor(db, MAC, "Tent", 0)
    clock = FakeClock()
    hub = SensorHub(db, BleSettings(enabled=False), clock)
    await hub.start()
    advert(hub, 1)
    clock.now += 5000
    advert(hub, 1)  # duplicate packet
    advert(hub, 2, temp=22.0)
    advert(hub, 1, mac="A4:C1:38:00:00:09")  # not adopted
    await hub.stop()  # flushes the queue

    rows = await db.fetchall("SELECT * FROM readings ORDER BY ts")
    assert [(r["sensor_id"], r["temperature_c"]) for r in rows] == [
        (sensor_id, 21.5),
        (sensor_id, 22.0),
    ]
    sensor = await queries.get_sensor(db, sensor_id)
    assert sensor is not None
    assert (sensor["last_seen"], sensor["last_battery"], sensor["last_rssi"]) == (
        1_005_000,
        77,
        -66,
    )
    assert [d.mac for d in hub.discovered()] == ["A4:C1:38:00:00:09"]


async def test_reload_adopted_picks_up_new_sensor(db: Database) -> None:
    hub = SensorHub(db, BleSettings(enabled=False))
    await hub.start()
    advert(hub, 1)
    await queries.adopt_sensor(db, MAC, "Tent", 0)
    await hub.reload_adopted()
    advert(hub, 2)
    await hub.stop()
    assert await db.fetchval("SELECT COUNT(*) FROM readings") == 1


async def test_simulator_produces_two_discoverable_sensors(db: Database) -> None:
    hub = SensorHub(db, BleSettings(simulate=True))
    assert hub.source is SourceKind.SIMULATED
    await hub.start()
    await asyncio.sleep(0.05)
    names = sorted(d.name or "" for d in hub.discovered())
    status = hub.status()
    await hub.stop()
    assert names == ["SIM drying", "SIM tent"]
    assert status.state is SourceState.RUNNING and status.discovered == 2


class FlakyScanner:
    """Fails on the first start, works afterwards."""

    starts = 0

    def __init__(self, detection_callback: Any) -> None:
        self.callback = detection_callback

    async def start(self) -> None:
        FlakyScanner.starts += 1
        if FlakyScanner.starts == 1:
            raise RuntimeError("org.bluez.Error.NotReady")

    async def stop(self) -> None:
        pass


async def test_bluez_source_retries_after_failure(
    db: Database, monkeypatch: pytest.MonkeyPatch
) -> None:
    FlakyScanner.starts = 0
    monkeypatch.setattr(scanner_mod, "BleakScanner", FlakyScanner)
    monkeypatch.setattr(scanner_mod, "_BACKOFF_START_S", 0.01)
    hub = SensorHub(db, BleSettings(enabled=True))
    await hub.start()
    for _ in range(100):
        if hub.status().state is SourceState.RUNNING:
            break
        await asyncio.sleep(0.01)
    status = hub.status()
    await hub.stop()
    assert status.state is SourceState.RUNNING and status.error is None
    codes = [r["code"] for r in await db.fetchall("SELECT code FROM events ORDER BY id")]
    assert codes == ["scanner_failed", "scanner_started"]


async def test_watchdog_restarts_stalled_scanner(
    db: Database, monkeypatch: pytest.MonkeyPatch
) -> None:
    FlakyScanner.starts = 1  # every start succeeds
    monkeypatch.setattr(scanner_mod, "BleakScanner", FlakyScanner)
    monkeypatch.setattr(scanner_mod, "_WATCHDOG_TICK_S", 0.01)
    await queries.adopt_sensor(db, MAC, "Tent", 0)
    clock = FakeClock()
    hub = SensorHub(db, BleSettings(enabled=True, watchdog_s=60), clock)
    await hub.start()
    await asyncio.sleep(0.05)
    clock.now += 61_000  # no adverts for longer than watchdog_s
    for _ in range(100):
        if FlakyScanner.starts >= 3:
            break
        await asyncio.sleep(0.01)
    await hub.stop()
    codes = [r["code"] for r in await db.fetchall("SELECT code FROM events ORDER BY id")]
    assert "scanner_stalled" in codes
    assert FlakyScanner.starts >= 3


class HangingScanner:
    """A wedged controller: BlueZ never answers the first start, nor any stop."""

    starts = 0

    def __init__(self, detection_callback: Any) -> None:
        self.callback = detection_callback

    async def start(self) -> None:
        HangingScanner.starts += 1
        if HangingScanner.starts == 1:
            await asyncio.Event().wait()

    async def stop(self) -> None:
        await asyncio.Event().wait()


async def test_unanswered_bluez_calls_time_out_and_retry(
    db: Database, monkeypatch: pytest.MonkeyPatch
) -> None:
    HangingScanner.starts = 0
    monkeypatch.setattr(scanner_mod, "BleakScanner", HangingScanner)
    monkeypatch.setattr(scanner_mod, "_BACKOFF_START_S", 0.01)
    monkeypatch.setattr(scanner_mod, "_START_TIMEOUT_S", 0.05)
    monkeypatch.setattr(scanner_mod, "_STOP_TIMEOUT_S", 0.05)
    hub = SensorHub(db, BleSettings(enabled=True))
    await hub.start()
    for _ in range(100):
        if hub.status().state is SourceState.RUNNING:
            break
        await asyncio.sleep(0.01)
    status = hub.status()
    await hub.stop()
    assert status.state is SourceState.RUNNING
    rows = await db.fetchall("SELECT code, message FROM events ORDER BY id")
    assert [r["code"] for r in rows] == ["scanner_failed", "scanner_started"]
    assert "didn't answer" in rows[0]["message"]


async def test_watchdog_restart_survives_a_hanging_stop(
    db: Database, monkeypatch: pytest.MonkeyPatch
) -> None:
    HangingScanner.starts = 1  # every start succeeds, every stop hangs
    monkeypatch.setattr(scanner_mod, "BleakScanner", HangingScanner)
    monkeypatch.setattr(scanner_mod, "_WATCHDOG_TICK_S", 0.01)
    monkeypatch.setattr(scanner_mod, "_STOP_TIMEOUT_S", 0.05)
    await queries.adopt_sensor(db, MAC, "Tent", 0)
    clock = FakeClock()
    hub = SensorHub(db, BleSettings(enabled=True, watchdog_s=60), clock)
    await hub.start()
    await asyncio.sleep(0.05)
    clock.now += 61_000  # stalled: restart, whose stop() never returns
    for _ in range(100):
        if HangingScanner.starts >= 3:
            break
        await asyncio.sleep(0.01)
    await hub.stop()
    assert HangingScanner.starts >= 3
