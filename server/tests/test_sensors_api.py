from __future__ import annotations

import time

import pytest
from fastapi.testclient import TestClient

from growbuddy.sensors.bthome import BTHOME_SERVICE_UUID
from growbuddy.sensors.scanner import SensorHub, simulated_frame

MAC = "A4:C1:38:12:34:56"


def inject(
    client: TestClient, mac: str = MAC, packet: int = 1, payload: bytes | None = None
) -> None:
    """Feed an advert into the running hub on the app's event loop."""
    hub: SensorHub = client.app.state.sensors  # type: ignore[attr-defined]
    data = {BTHOME_SERVICE_UUID: payload or simulated_frame(packet, 88, 23.4, 61.2, 2.95)}
    assert client.portal is not None
    client.portal.call(hub.handle_advert, mac, data, -58, "ATC_CA0B5A")


@pytest.fixture
def sensor(client: TestClient, auth: dict[str, str]) -> dict[str, object]:
    response = client.post(
        "/api/v1/sensors", json={"mac": MAC.lower(), "name": "Tent"}, headers=auth
    )
    assert response.status_code == 201, response.text
    body: dict[str, object] = response.json()
    return body


def test_requires_token(client: TestClient) -> None:
    assert client.get("/api/v1/sensors").status_code == 401


def test_discovery_lists_unadopted_with_live_values(
    client: TestClient, auth: dict[str, str]
) -> None:
    inject(client)
    (device,) = client.get("/api/v1/sensors/discovered", headers=auth).json()
    assert device["mac"] == MAC
    assert device["rssi"] == -58
    assert device["encrypted"] is False
    assert device["values"]["temperature_c"] == pytest.approx(23.4)


def test_adopt_then_record(
    client: TestClient, auth: dict[str, str], sensor: dict[str, object]
) -> None:
    assert sensor["mac"] == MAC and sensor["name"] == "Tent" and sensor["status"] == "active"
    assert sensor["ranges"] == {
        m: {"warn_min": None, "warn_max": None, "crit_min": None, "crit_max": None}
        for m in ("temperature", "humidity", "vpd")
    }
    inject(client)
    assert client.get("/api/v1/sensors/discovered", headers=auth).json() == []
    for _ in range(50):  # the writer task stores asynchronously
        detail = client.get(f"/api/v1/sensors/{sensor['id']}", headers=auth).json()
        if detail["last_seen"]:
            break
        time.sleep(0.02)
    assert detail["battery_pct"] == 88 and detail["rssi"] == -58


def test_adopt_validation(
    client: TestClient, auth: dict[str, str], sensor: dict[str, object]
) -> None:
    dup = client.post("/api/v1/sensors", json={"mac": MAC, "name": "Again"}, headers=auth)
    assert dup.status_code == 409 and dup.json()["error"]["code"] == "already_adopted"
    bad = client.post("/api/v1/sensors", json={"mac": "nope", "name": "X"}, headers=auth)
    assert bad.status_code == 422
    empty = client.post(
        "/api/v1/sensors", json={"mac": "A4:C1:38:00:00:01", "name": ""}, headers=auth
    )
    assert empty.status_code == 422


def test_adopt_encrypted_rejected(client: TestClient, auth: dict[str, str]) -> None:
    inject(client, mac="A4:C1:38:00:00:07", payload=b"\x41\x00\x01\x02")
    response = client.post(
        "/api/v1/sensors", json={"mac": "A4:C1:38:00:00:07", "name": "X"}, headers=auth
    )
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "encrypted_sensor"


def test_patch(client: TestClient, auth: dict[str, str], sensor: dict[str, object]) -> None:
    body = client.patch(
        f"/api/v1/sensors/{sensor['id']}",
        json={"name": "Grow tent", "alerts_enabled": False},
        headers=auth,
    ).json()
    assert body["name"] == "Grow tent" and body["alerts_enabled"] is False


def test_retire_and_readopt(
    client: TestClient, auth: dict[str, str], sensor: dict[str, object]
) -> None:
    assert client.delete(f"/api/v1/sensors/{sensor['id']}", headers=auth).status_code == 204
    assert client.get("/api/v1/sensors", headers=auth).json() == []
    (retired,) = client.get("/api/v1/sensors?include_retired=true", headers=auth).json()
    assert retired["status"] == "retired"
    inject(client)
    assert [d["mac"] for d in client.get("/api/v1/sensors/discovered", headers=auth).json()] == [
        MAC
    ]
    again = client.post("/api/v1/sensors", json={"mac": MAC, "name": "Drying"}, headers=auth)
    assert again.status_code == 201
    assert again.json()["id"] == sensor["id"] and again.json()["name"] == "Drying"


def test_unknown_sensor_404(client: TestClient, auth: dict[str, str]) -> None:
    response = client.get("/api/v1/sensors/999", headers=auth)
    assert response.status_code == 404 and response.json()["error"]["code"] == "not_found"


def test_put_and_get_ranges(
    client: TestClient, auth: dict[str, str], sensor: dict[str, object]
) -> None:
    url = f"/api/v1/sensors/{sensor['id']}/ranges"
    body = {"temperature": {"warn_min": 15, "warn_max": 20, "crit_min": 12, "crit_max": 23}}
    saved = client.put(url, json=body, headers=auth).json()
    assert saved["temperature"]["crit_max"] == 23
    assert saved["humidity"]["warn_min"] is None
    assert client.get(url, headers=auth).json() == saved


@pytest.mark.parametrize(
    "rng",
    [
        {"warn_min": 25, "warn_max": 20},
        {"warn_min": 15, "crit_min": 16},
        {"warn_max": 30, "crit_max": 28},
    ],
)
def test_put_ranges_rejects_inconsistent_bounds(
    client: TestClient, auth: dict[str, str], sensor: dict[str, object], rng: dict[str, float]
) -> None:
    response = client.put(
        f"/api/v1/sensors/{sensor['id']}/ranges", json={"humidity": rng}, headers=auth
    )
    assert response.status_code == 422


def test_preset_from_phases_preview_and_apply(
    client: TestClient, auth: dict[str, str], sensor: dict[str, object]
) -> None:
    url = f"/api/v1/sensors/{sensor['id']}/ranges"
    preview = client.post(f"{url}/from-phases", json={"phases": ["Drying"]}, headers=auth).json()
    assert preview["temperature"] == {
        "warn_min": 15,
        "warn_max": 20,
        "crit_min": 13,
        "crit_max": 22,
    }
    assert client.get(url, headers=auth).json()["temperature"]["warn_min"] is None
    applied = client.post(
        f"{url}/from-phases", json={"phases": ["Drying"], "apply": True}, headers=auth
    )
    assert client.get(url, headers=auth).json() == applied.json() == preview


def test_preset_unknown_phase(
    client: TestClient, auth: dict[str, str], sensor: dict[str, object]
) -> None:
    response = client.post(
        f"/api/v1/sensors/{sensor['id']}/ranges/from-phases",
        json={"phases": ["Bloom"]},
        headers=auth,
    )
    assert response.status_code == 422 and response.json()["error"]["code"] == "unknown_phase"


def test_status_reports_ble(client: TestClient, auth: dict[str, str]) -> None:
    ble = client.get("/api/v1/status", headers=auth).json()["ble"]
    assert ble["source"] == "none" and ble["adopted"] == 0
