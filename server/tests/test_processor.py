from __future__ import annotations

import pytest

from growbuddy.sensors.bthome import BTHOME_SERVICE_UUID
from growbuddy.sensors.processor import (
    NO_PACKET_ID_MIN_INTERVAL_MS,
    AdvertProcessor,
    normalize_mac,
)
from growbuddy.sensors.scanner import simulated_frame

MAC = "A4:C1:38:12:34:56"
OTHER = "A4:C1:38:00:00:01"


def sd(packet: int = 1, temp: float = 22.0) -> dict[str, bytes]:
    return {BTHOME_SERVICE_UUID: simulated_frame(packet, 80, temp, 55.0, 2.9)}


@pytest.fixture
def proc() -> AdvertProcessor:
    p = AdvertProcessor(discovery_ttl_ms=300_000)
    p.set_adopted({MAC: 1})
    return p


def test_adopted_sensor_yields_record(proc: AdvertProcessor) -> None:
    rec = proc.handle(MAC.lower(), sd(), -60, "ATC_CA0B5A", now_ms=1000)
    assert rec is not None
    assert (rec.sensor_id, rec.mac, rec.ts, rec.rssi) == (1, MAC, 1000, -60)
    assert rec.reading.temperature_c == pytest.approx(22.0)


def test_repeated_packet_id_is_deduped(proc: AdvertProcessor) -> None:
    assert proc.handle(MAC, sd(packet=5), -60, None, 1000) is not None
    assert proc.handle(MAC, sd(packet=5), -61, None, 3000) is None
    assert proc.handle(MAC, sd(packet=6), -60, None, 5000) is not None


def test_frames_without_packet_id_are_throttled(proc: AdvertProcessor) -> None:
    frame = bytes.fromhex("4002ca0803bf13")  # temp + humidity only
    data = {BTHOME_SERVICE_UUID: frame}
    assert proc.handle(MAC, data, -60, None, 0) is not None
    assert proc.handle(MAC, data, -60, None, NO_PACKET_ID_MIN_INTERVAL_MS - 1) is None
    assert proc.handle(MAC, data, -60, None, NO_PACKET_ID_MIN_INTERVAL_MS) is not None


def test_unadopted_devices_are_discovered_not_stored(proc: AdvertProcessor) -> None:
    assert proc.handle(OTHER, sd(), -70, "ATC_000001", 1000) is None
    found = proc.discovered(now_ms=2000)
    assert [d.mac for d in found] == [OTHER]
    assert found[0].name == "ATC_000001"
    assert found[0].reading is not None
    assert found[0].reading.humidity_pct == pytest.approx(55.0)


def test_discovered_excludes_adopted_and_sorts_by_rssi(proc: AdvertProcessor) -> None:
    proc.handle(MAC, sd(), -50, None, 0)
    proc.handle(OTHER, sd(), -80, None, 0)
    proc.handle("A4:C1:38:00:00:02", sd(), -55, None, 0)
    assert [d.mac for d in proc.discovered(0)] == ["A4:C1:38:00:00:02", OTHER]


def test_discovered_expires_after_ttl_and_honours_window(proc: AdvertProcessor) -> None:
    proc.handle(OTHER, sd(), -70, None, 0)
    assert proc.discovered(now_ms=60_000, within_ms=30_000) == []
    assert len(proc.discovered(now_ms=300_000)) == 1
    assert proc.discovered(now_ms=300_001) == []
    assert proc.seen(OTHER) is None


def test_non_bthome_and_invalid_payloads_ignored(proc: AdvertProcessor) -> None:
    assert (
        proc.handle(OTHER, {"0000180f-0000-1000-8000-00805f9b34fb": b"\x01"}, -60, None, 0) is None
    )
    assert proc.handle(OTHER, {BTHOME_SERVICE_UUID: b"\x20\x02"}, -60, None, 0) is None
    assert proc.discovered(0) == []
    assert proc.last_bthome_ms is None


def test_encrypted_device_is_discovered_without_values(proc: AdvertProcessor) -> None:
    proc.handle(OTHER, {BTHOME_SERVICE_UUID: b"\x41\x00\x01\x02\x03"}, -60, None, 0)
    (device,) = proc.discovered(0)
    assert device.encrypted and device.reading is None


def test_readopt_resets_dedup_state(proc: AdvertProcessor) -> None:
    assert proc.handle(MAC, sd(packet=9), -60, None, 0) is not None
    proc.set_adopted({})
    assert proc.handle(MAC, sd(packet=9), -60, None, 1) is None
    proc.set_adopted({MAC: 1})
    assert proc.handle(MAC, sd(packet=9), -60, None, 2) is not None


@pytest.mark.parametrize(
    ("raw", "expected"),
    [("a4:c1:38:12:34:56", MAC), (" A4-C1-38-12-34-56 ", MAC)],
)
def test_normalize_mac(raw: str, expected: str) -> None:
    assert normalize_mac(raw) == expected


@pytest.mark.parametrize("raw", ["", "A4:C1:38:12:34", "zz:c1:38:12:34:56", "A4C138123456"])
def test_normalize_mac_rejects(raw: str) -> None:
    with pytest.raises(ValueError):
        normalize_mac(raw)
