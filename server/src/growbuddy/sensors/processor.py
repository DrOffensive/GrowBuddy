"""Advertisement processing without I/O: decode, dedup, discovered-device table.

Fed by any advert source (BlueZ or simulator). Time is passed in, so the logic
is deterministic under test. Decides which adverts become stored readings;
the caller does the storing.
"""

from __future__ import annotations

import re
from collections.abc import Mapping
from dataclasses import dataclass, field

from growbuddy.sensors.bthome import (
    BTHOME_SERVICE_UUID,
    BTHomeEncryptedError,
    BTHomeError,
    BTHomeReading,
    parse,
)

_MAC = re.compile(r"^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
# Used only for frames without a packet id: store at most one reading per window.
NO_PACKET_ID_MIN_INTERVAL_MS = 10_000


def normalize_mac(mac: str) -> str:
    """Upper-case ``AA:BB:CC:DD:EE:FF``; raises ValueError for anything else."""
    value = mac.strip().upper().replace("-", ":")
    if not _MAC.match(value):
        raise ValueError(f"invalid MAC address: {mac!r}")
    return value


@dataclass(slots=True)
class SeenDevice:
    mac: str
    name: str | None
    rssi: int | None
    last_seen_ms: int
    encrypted: bool = False
    reading: BTHomeReading | None = None


@dataclass(frozen=True, slots=True)
class ReadingRecord:
    sensor_id: int
    mac: str
    ts: int
    reading: BTHomeReading
    rssi: int | None


@dataclass(slots=True)
class AdvertProcessor:
    discovery_ttl_ms: int
    _seen: dict[str, SeenDevice] = field(default_factory=dict)
    _adopted: dict[str, int] = field(default_factory=dict)  # mac -> sensor id
    _last_packet: dict[str, int] = field(default_factory=dict)
    _last_stored_ms: dict[str, int] = field(default_factory=dict)
    last_bthome_ms: int | None = None

    @property
    def adopted_count(self) -> int:
        return len(self._adopted)

    def set_adopted(self, adopted: Mapping[str, int]) -> None:
        self._adopted = {normalize_mac(mac): sid for mac, sid in adopted.items()}
        for mac in list(self._last_packet):
            if mac not in self._adopted:
                self._last_packet.pop(mac, None)
                self._last_stored_ms.pop(mac, None)

    def handle(
        self,
        mac: str,
        service_data: Mapping[str, bytes],
        rssi: int | None,
        name: str | None,
        now_ms: int,
    ) -> ReadingRecord | None:
        """Process one advert; returns a record to store, or None."""
        payload = service_data.get(BTHOME_SERVICE_UUID)
        if payload is None:
            return None
        mac = mac.upper()
        try:
            reading: BTHomeReading | None = parse(payload)
            encrypted = False
        except BTHomeEncryptedError:
            reading, encrypted = None, True
        except BTHomeError:
            return None

        self.last_bthome_ms = now_ms
        device = self._seen.get(mac)
        if device is None:
            device = self._seen[mac] = SeenDevice(mac, name, rssi, now_ms)
        device.name = name or device.name
        device.rssi = rssi
        device.last_seen_ms = now_ms
        device.encrypted = encrypted
        if reading is not None and reading.has_measurement:
            device.reading = reading

        sensor_id = self._adopted.get(mac)
        if sensor_id is None or reading is None or not reading.has_measurement:
            return None
        if not self._is_new(mac, reading, now_ms):
            return None
        return ReadingRecord(sensor_id, mac, now_ms, reading, rssi)

    def _is_new(self, mac: str, reading: BTHomeReading, now_ms: int) -> bool:
        if reading.packet_id is not None:
            if self._last_packet.get(mac) == reading.packet_id:
                return False
            self._last_packet[mac] = reading.packet_id
        else:
            last = self._last_stored_ms.get(mac)
            if last is not None and now_ms - last < NO_PACKET_ID_MIN_INTERVAL_MS:
                return False
        self._last_stored_ms[mac] = now_ms
        return True

    def discovered(self, now_ms: int, within_ms: int | None = None) -> list[SeenDevice]:
        """Unadopted BTHome devices seen recently, strongest signal first."""
        self._expire(now_ms)
        window = (
            self.discovery_ttl_ms if within_ms is None else min(within_ms, self.discovery_ttl_ms)
        )
        found = [
            d
            for d in self._seen.values()
            if d.mac not in self._adopted and now_ms - d.last_seen_ms <= window
        ]
        return sorted(found, key=lambda d: -(d.rssi if d.rssi is not None else -999))

    def seen(self, mac: str) -> SeenDevice | None:
        return self._seen.get(mac.upper())

    def _expire(self, now_ms: int) -> None:
        cutoff = now_ms - self.discovery_ttl_ms
        for mac in [m for m, d in self._seen.items() if d.last_seen_ms < cutoff]:
            del self._seen[mac]
