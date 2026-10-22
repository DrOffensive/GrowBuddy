"""Pure BTHome v2 payload decoder (no I/O). See docs/HARDWARE.md.

Input is the raw service data stored under UUID 0xFCD2: the device info byte
followed by ``object_id`` + value pairs, little-endian.
"""

from __future__ import annotations

from dataclasses import dataclass

BTHOME_SERVICE_UUID = "0000fcd2-0000-1000-8000-00805f9b34fb"

_FLAG_ENCRYPTED = 0x01
_FLAG_TRIGGER_BASED = 0x04
_VERSION_SHIFT = 5


@dataclass(frozen=True, slots=True)
class _ObjectSpec:
    field: str | None  # None = known size but not used; skipped
    size: int
    signed: bool = False
    factor: float = 1.0


# Objects the Mijia/pvvx firmware sends, plus common ones whose size we know so
# that their presence doesn't stop parsing. Unknown ids end parsing (length unknown).
_OBJECTS: dict[int, _ObjectSpec] = {
    0x00: _ObjectSpec("packet_id", 1),
    0x01: _ObjectSpec("battery_pct", 1),
    0x02: _ObjectSpec("temperature_c", 2, signed=True, factor=0.01),
    0x03: _ObjectSpec("humidity_pct", 2, factor=0.01),
    0x04: _ObjectSpec(None, 3),  # pressure
    0x05: _ObjectSpec(None, 3),  # illuminance
    0x0C: _ObjectSpec("voltage_v", 2, factor=0.001),
    0x10: _ObjectSpec(None, 1),  # power (binary)
    0x11: _ObjectSpec(None, 1),  # opening (binary)
    0x2E: _ObjectSpec("humidity_pct", 1),
    0x45: _ObjectSpec("temperature_c", 2, signed=True, factor=0.1),
}


class BTHomeError(ValueError):
    """Payload is not a decodable BTHome v2 frame."""


class BTHomeEncryptedError(BTHomeError):
    """Payload is encrypted; decryption is not supported (sensors run unencrypted)."""


@dataclass(frozen=True, slots=True)
class BTHomeReading:
    packet_id: int | None = None
    battery_pct: int | None = None
    temperature_c: float | None = None
    humidity_pct: float | None = None
    voltage_v: float | None = None
    trigger_based: bool = False

    @property
    def has_measurement(self) -> bool:
        return self.temperature_c is not None or self.humidity_pct is not None


def parse(payload: bytes) -> BTHomeReading:
    """Decode a BTHome v2 service-data payload.

    Raises BTHomeError for empty, non-v2 or encrypted payloads. Truncated
    values and unknown object ids end parsing; everything decoded up to that
    point is returned.
    """
    if not payload:
        raise BTHomeError("empty payload")
    info = payload[0]
    version = info >> _VERSION_SHIFT
    if version != 2:
        raise BTHomeError(f"unsupported BTHome version {version} (device info 0x{info:02X})")
    if info & _FLAG_ENCRYPTED:
        raise BTHomeEncryptedError("encrypted BTHome payload")

    values: dict[str, float | int] = {}
    index = 1
    while index < len(payload):
        spec = _OBJECTS.get(payload[index])
        if spec is None:
            break
        start, end = index + 1, index + 1 + spec.size
        if end > len(payload):
            break
        if spec.field is not None and spec.field not in values:
            raw = int.from_bytes(payload[start:end], "little", signed=spec.signed)
            values[spec.field] = raw if spec.factor == 1.0 else round(raw * spec.factor, 3)
        index = end

    return BTHomeReading(
        packet_id=_int_or_none(values.get("packet_id")),
        battery_pct=_int_or_none(values.get("battery_pct")),
        temperature_c=_float_or_none(values.get("temperature_c")),
        humidity_pct=_float_or_none(values.get("humidity_pct")),
        voltage_v=_float_or_none(values.get("voltage_v")),
        trigger_based=bool(info & _FLAG_TRIGGER_BASED),
    )


def _int_or_none(value: float | int | None) -> int | None:
    return None if value is None else int(value)


def _float_or_none(value: float | int | None) -> float | None:
    return None if value is None else float(value)
