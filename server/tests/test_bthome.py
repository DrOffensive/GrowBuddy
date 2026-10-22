from __future__ import annotations

import struct

import pytest

from growbuddy.sensors.bthome import BTHomeEncryptedError, BTHomeError, parse


def frame(*objects: bytes, info: int = 0x40) -> bytes:
    return bytes([info]) + b"".join(objects)


PACKET = lambda n: bytes([0x00, n])  # noqa: E731
BATTERY = lambda pct: bytes([0x01, pct])  # noqa: E731
TEMP = lambda c: b"\x02" + struct.pack("<h", round(c * 100))  # noqa: E731
HUMIDITY = lambda pct: b"\x03" + struct.pack("<H", round(pct * 100))  # noqa: E731
VOLTAGE = lambda v: b"\x0c" + struct.pack("<H", round(v * 1000))  # noqa: E731


def test_parses_full_pvvx_frame() -> None:
    reading = parse(frame(PACKET(17), BATTERY(87), TEMP(23.45), HUMIDITY(55.6), VOLTAGE(2.951)))
    assert reading.packet_id == 17
    assert reading.battery_pct == 87
    assert reading.temperature_c == pytest.approx(23.45)
    assert reading.humidity_pct == pytest.approx(55.6)
    assert reading.voltage_v == pytest.approx(2.951)
    assert reading.has_measurement
    assert not reading.trigger_based


def test_negative_temperature() -> None:
    assert parse(frame(TEMP(-4.2))).temperature_c == pytest.approx(-4.2)


def test_legacy_hex_capture() -> None:
    # Shape logged by the 0.1.6 logger: 40-00-xx-01-xx-02-xxxx-03-xxxx
    reading = parse(bytes.fromhex("40 00 a3 01 64 02 ca 08 03 bf 13".replace(" ", "")))
    assert reading.packet_id == 0xA3
    assert reading.battery_pct == 100
    assert reading.temperature_c == pytest.approx(22.50)
    assert reading.humidity_pct == pytest.approx(50.55)


def test_unknown_object_stops_parsing_but_keeps_earlier_values() -> None:
    reading = parse(frame(TEMP(21.0), bytes([0xF0, 0x01, 0x02]), HUMIDITY(40)))
    assert reading.temperature_c == pytest.approx(21.0)
    assert reading.humidity_pct is None


def test_known_unused_objects_are_skipped() -> None:
    reading = parse(frame(bytes([0x10, 0x01]), TEMP(20.0)))
    assert reading.temperature_c == pytest.approx(20.0)


def test_truncated_value_is_ignored() -> None:
    reading = parse(frame(BATTERY(50), b"\x02\x10"))
    assert reading.battery_pct == 50
    assert reading.temperature_c is None
    assert not reading.has_measurement


def test_trigger_flag() -> None:
    assert parse(frame(TEMP(20), info=0x44)).trigger_based


@pytest.mark.parametrize("payload", [b"", bytes([0x20, 0x02, 0x00, 0x00]), bytes([0x00])])
def test_rejects_empty_and_non_v2(payload: bytes) -> None:
    with pytest.raises(BTHomeError):
        parse(payload)


def test_rejects_encrypted() -> None:
    with pytest.raises(BTHomeEncryptedError):
        parse(frame(TEMP(20), info=0x41))
