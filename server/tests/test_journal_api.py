from __future__ import annotations

import uuid
from typing import Any

import cv2
import numpy as np
import pytest
from fastapi.testclient import TestClient

from growbuddy.clock import now_ms
from growbuddy.db.connection import Database
from growbuddy.db.queries import sensors as sensor_q

DAY = 86_400_000
API = "/api/v1"


def uid() -> str:
    return str(uuid.uuid4())


def run(client: TestClient, fn: Any, *args: Any) -> Any:
    """Call an async DB helper on the app's event loop."""
    assert client.portal is not None
    return client.portal.call(fn, *args)


def app_db(client: TestClient) -> Database:
    db: Database = client.app.state.db  # type: ignore[attr-defined]
    return db


def jpeg(width: int = 3000, height: int = 2000) -> bytes:
    img = np.zeros((height, width, 3), np.uint8)
    img[:, : width // 2] = (40, 160, 60)
    ok, buf = cv2.imencode(".jpg", img)
    assert ok
    return buf.tobytes()


class Api:
    def __init__(self, client: TestClient, auth: dict[str, str]) -> None:
        self.c, self.h = client, auth

    def get(self, path: str, **kw: Any) -> Any:
        return self.c.get(API + path, headers=self.h, **kw)

    def post(self, path: str, **kw: Any) -> Any:
        return self.c.post(API + path, headers=self.h, **kw)

    def patch(self, path: str, **kw: Any) -> Any:
        return self.c.patch(API + path, headers=self.h, **kw)

    def delete(self, path: str) -> Any:
        return self.c.delete(API + path, headers=self.h)

    def plant(self, **fields: Any) -> dict[str, Any]:
        r = self.post("/plants", json={"id": uid(), "nickname": "Plant", **fields})
        assert r.status_code == 201, r.text
        return dict(r.json())

    def log(self, plant_ids: list[str], type: str = "Inspection", **fields: Any) -> dict[str, Any]:
        r = self.post("/logs", json={"id": uid(), "type": type, "plant_ids": plant_ids, **fields})
        assert r.status_code == 201, r.text
        return dict(r.json())

    def sensor(self, mac: str = "A4:C1:38:12:34:56", name: str = "Tent") -> int:
        r = self.post("/sensors", json={"mac": mac, "name": name})
        assert r.status_code == 201, r.text
        return int(r.json()["id"])

    def reading(self, sensor_id: int, ts: int, temp: float, hum: float) -> None:
        run(self.c, sensor_q.insert_reading, app_db(self.c), sensor_id, ts, temp, hum, 80, 2.9, -60)


@pytest.fixture
def api(client: TestClient, auth: dict[str, str]) -> Api:
    return Api(client, auth)


# ----------------------------------------------------------------- plants


def test_create_plant_is_idempotent(api: Api) -> None:
    plant_id = uid()
    first = api.post("/plants", json={"id": plant_id, "nickname": "Dosi", "strain_id": 1})
    assert first.status_code == 201
    body = first.json()
    assert body["phase"] == "PreGermination" and body["age"] is None and body["milestones"] == {}
    assert body["strain"]["id"] == 1 and body["strain"]["name"]
    again = api.post("/plants", json={"id": plant_id.upper(), "nickname": "Other"})
    assert again.status_code == 200 and again.json() == body
    assert [p["id"] for p in api.get("/plants").json()] == [plant_id]


@pytest.mark.parametrize(
    ("fields", "code"),
    [({"strain_id": 99999}, "unknown_strain"), ({"sensor_id": 7}, "unknown_sensor"),
     ({"thumbnail_photo_id": str(uuid.uuid4())}, "unknown_photo")],
)  # fmt: skip
def test_create_plant_validates_references(api: Api, fields: dict[str, Any], code: str) -> None:
    r = api.post("/plants", json={"id": uid(), "nickname": "X", **fields})
    assert r.status_code == 422 and r.json()["error"]["code"] == code


def test_plant_cannot_be_located_at_retired_sensor(api: Api) -> None:
    sensor_id = api.sensor()
    api.delete(f"/sensors/{sensor_id}")
    r = api.post("/plants", json={"id": uid(), "nickname": "X", "sensor_id": sensor_id})
    assert r.json()["error"]["code"] == "unknown_sensor"


def test_patch_plant(api: Api) -> None:
    sensor_id = api.sensor()
    plant = api.plant(strain_id=1)
    body = api.patch(
        f"/plants/{plant['id']}",
        json={"nickname": "Renamed", "sensor_id": sensor_id, "strain_id": None},
    ).json()
    assert (body["nickname"], body["sensor_id"], body["strain"]) == ("Renamed", sensor_id, None)
    assert api.patch(f"/plants/{plant['id']}", json={"nickname": None}).status_code == 422
    assert api.patch(f"/plants/{plant['id']}", json={"phase": "Drying"}).status_code == 422


def test_archived_plants_hidden_by_default(api: Api) -> None:
    plant = api.plant()
    api.patch(f"/plants/{plant['id']}", json={"archived": True})
    assert api.get("/plants").json() == []
    assert len(api.get("/plants?include_archived=true").json()) == 1


def test_retiring_sensor_clears_plant_location(api: Api) -> None:
    sensor_id = api.sensor()
    plant = api.plant(sensor_id=sensor_id)
    api.delete(f"/sensors/{sensor_id}")
    assert api.get(f"/plants/{plant['id']}").json()["sensor_id"] is None


def test_delete_plant_removes_only_its_own_logs(api: Api) -> None:
    a, b = api.plant(), api.plant()
    own = api.log([a["id"]])
    shared = api.log([a["id"], b["id"]])
    assert api.delete(f"/plants/{a['id']}").status_code == 204
    assert api.get(f"/logs/{own['id']}").status_code == 404
    assert api.get(f"/logs/{shared['id']}").json()["plant_ids"] == [b["id"]]
    assert api.delete(f"/plants/{a['id']}").status_code == 404


# ------------------------------------------------------------------- logs


def test_milestone_logs_drive_phase_and_age(api: Api) -> None:
    plant = api.plant()
    now = now_ms()
    api.log([plant["id"]], "Germination", ts=now - 25 * DAY)
    sprout = api.log([plant["id"]], "Sprout", ts=now - 20 * DAY)
    body = api.get(f"/plants/{plant['id']}").json()
    assert body["phase"] == "EarlyVegetation"
    assert body["age"] == {"week": 3, "day": 7}
    assert list(body["milestones"]) == ["Germination", "Sprout"]

    api.delete(f"/logs/{sprout['id']}")
    assert api.get(f"/plants/{plant['id']}").json()["phase"] == "Germinating"


def test_log_create_is_idempotent_and_validates(api: Api) -> None:
    plant = api.plant()
    log_id = uid()
    body = {
        "id": log_id,
        "type": "Feeding",
        "plant_ids": [plant["id"]],
        "data": {"ph": 6.1, "ec": 1.2},
    }
    first = api.post("/logs", json=body)
    assert first.status_code == 201
    assert first.json()["data"] == {"ph": 6.1, "ec": 1.2, "nutrients": []}
    assert api.post("/logs", json=body).status_code == 200

    bad = api.post("/logs", json={**body, "id": uid(), "data": {"ph": 20}})
    assert bad.status_code == 422 and bad.json()["error"]["code"] == "invalid_data"
    unknown = api.post("/logs", json={**body, "id": uid(), "plant_ids": [uid()]})
    assert unknown.json()["error"]["code"] == "unknown_plant"
    assert api.post("/logs", json={**body, "id": uid(), "plant_ids": []}).status_code == 422
    assert api.post("/logs", json={**body, "id": uid(), "type": "AutoLog"}).status_code == 422


def test_sensor_snapshot_uses_plant_location(api: Api) -> None:
    sensor_id = api.sensor()
    located = api.plant(sensor_id=sensor_id)
    unlocated = api.plant()
    ts = now_ms() - DAY
    api.reading(sensor_id, ts - 120_000, 24.0, 60.0)
    api.reading(sensor_id, ts + 30_000, 25.0, 55.0)  # nearest
    log = api.log([located["id"], unlocated["id"]], ts=ts)
    (snap,) = log["sensor_snapshot"]
    assert snap["sensor_id"] == sensor_id and snap["sensor_name"] == "Tent"
    assert (snap["ts"], snap["temperature_c"]) == (ts + 30_000, 25.0)
    assert snap["vpd_kpa"] == pytest.approx(1.425, abs=1e-3)

    moved = api.patch(f"/logs/{log['id']}", json={"ts": ts - 3 * DAY}).json()
    assert moved["sensor_snapshot"] == []  # no reading within ±10 min any more


def test_patch_log(api: Api) -> None:
    a, b = api.plant(), api.plant()
    log = api.log([a["id"]], "LightAdjust", data={"light_height_cm": 40}, comment="raised")
    body = api.patch(
        f"/logs/{log['id']}",
        json={"plant_ids": [b["id"], a["id"]], "comment": None, "data": {"light_height_cm": 45}},
    ).json()
    assert body["plant_ids"] == sorted([a["id"], b["id"]])
    assert body["comment"] is None and body["data"] == {"light_height_cm": 45}
    assert body["updated_ts"] >= log["updated_ts"]
    assert api.patch(f"/logs/{log['id']}", json={"data": {"bogus": 1}}).status_code == 422
    assert api.patch(f"/logs/{log['id']}", json={"type": "Feeding"}).status_code == 422
    assert api.patch(f"/logs/{log['id']}", json={"plant_ids": None}).status_code == 422


def test_log_pagination_and_filters(api: Api) -> None:
    plant, other = api.plant(), api.plant()
    for i in range(1, 6):
        api.log([plant["id"]], "Feeding" if i % 2 else "Inspection", ts=i * 1000)
    api.log([other["id"]], ts=9000)

    page = api.get("/logs?limit=2").json()
    assert [i["ts"] for i in page["items"]] == [9000, 5000] and page["next_before"] == 5000
    page = api.get("/logs?limit=2&before=5000").json()
    assert [i["ts"] for i in page["items"]] == [4000, 3000]
    page = api.get("/logs?limit=10&before=3000").json()
    assert [i["ts"] for i in page["items"]] == [2000, 1000] and page["next_before"] is None

    feeding = api.get("/logs?type=Feeding").json()["items"]
    assert [i["ts"] for i in feeding] == [5000, 3000, 1000]
    window = api.get("/logs?from=2000&to=4000").json()["items"]
    assert [i["ts"] for i in window] == [4000, 3000, 2000]
    own = api.get(f"/plants/{plant['id']}/logs?limit=3").json()
    assert [i["ts"] for i in own["items"]] == [5000, 4000, 3000] and own["next_before"] == 3000
    assert api.get("/logs?limit=0").status_code == 422


# ----------------------------------------------------------------- photos


def upload(api: Api, photo_id: str, data: bytes) -> Any:
    return api.post("/photos", data={"id": photo_id}, files={"file": ("p.jpg", data, "image/jpeg")})


def test_photo_upload_downscales_and_serves(api: Api) -> None:
    photo_id = uid()
    r = upload(api, photo_id, jpeg())
    assert r.status_code == 201, r.text
    meta = r.json()
    assert (meta["width"], meta["height"]) == (2560, 1707)
    assert upload(api, photo_id, jpeg(10, 10)).status_code == 200

    full = api.get(f"/photos/{photo_id}")
    assert full.headers["content-type"] == "image/jpeg"
    assert "immutable" in full.headers["cache-control"]
    img = cv2.imdecode(np.frombuffer(full.content, np.uint8), cv2.IMREAD_COLOR)
    assert img.shape[:2] == (1707, 2560)
    thumb = cv2.imdecode(np.frombuffer(api.get(f"/photos/{photo_id}?thumb=1").content, np.uint8), 1)
    assert max(thumb.shape[:2]) == 400


def test_photo_rejects_non_images(api: Api) -> None:
    r = upload(api, uid(), b"not an image")
    assert r.status_code == 422 and r.json()["error"]["code"] == "invalid_image"


def test_photo_delete_rules(api: Api, settings: Any) -> None:
    used, free = uid(), uid()
    upload(api, used, jpeg(100, 100))
    upload(api, free, jpeg(100, 100))
    plant = api.plant()
    log = api.log([plant["id"]], photo_ids=[used])
    assert log["photo_ids"] == [used]
    conflict = api.delete(f"/photos/{used}")
    assert conflict.status_code == 409 and conflict.json()["error"]["code"] == "photo_in_use"

    files = list((settings.server.data_dir / "photos").rglob(f"{free}*.jpg"))
    assert len(files) == 2
    assert api.delete(f"/photos/{free}").status_code == 204
    assert not any(f.exists() for f in files)
    assert api.get(f"/photos/{free}").status_code == 404


# ------------------------------------------------------------ environment


def test_environment_current(api: Api) -> None:
    tent = api.sensor()
    drying = api.sensor("A4:C1:38:00:00:02", "Drying")
    api.post(
        f"/sensors/{tent}/ranges/from-phases", json={"phases": ["LateVegetation"], "apply": True}
    )
    api.reading(tent, now_ms() - 5000, 29.0, 60.0)  # temp above warn_max 28, below crit 30
    plant = api.plant(sensor_id=tent)
    loose = api.plant()

    body = api.get("/environment/current").json()
    by_name = {s["sensor"]["name"]: s for s in body["sensors"]}
    t = by_name["Tent"]
    assert t["online"] is True
    assert t["reading"]["temperature_c"] == 29.0 and t["reading"]["vpd_kpa"] == pytest.approx(
        1.603, abs=1e-3
    )
    assert t["levels"] == {"temperature": "warning", "humidity": "normal", "vpd": "critical"}
    assert [p["id"] for p in t["plants"]] == [plant["id"]]

    d = by_name["Drying"]
    assert d["sensor"]["id"] == drying
    assert d["online"] is False and d["reading"] is None
    assert d["levels"] == {"temperature": None, "humidity": None, "vpd": None}
    assert [p["id"] for p in body["unlocated_plants"]] == [loose["id"]]


def test_log_photo_limit(api: Api) -> None:
    plant = api.plant()
    ids = [uid() for _ in range(6)]
    for photo_id in ids:
        assert upload(api, photo_id, jpeg(20, 20)).status_code == 201
    too_many = api.post(
        "/logs",
        json={"id": uid(), "type": "Inspection", "plant_ids": [plant["id"]], "photo_ids": ids},
    )
    assert too_many.status_code == 422
    log = api.log([plant["id"]], photo_ids=ids[:5])
    assert len(log["photo_ids"]) == 5
    assert api.patch(f"/logs/{log['id']}", json={"photo_ids": ids}).status_code == 422
