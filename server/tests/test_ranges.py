from __future__ import annotations

import sqlite3
import uuid
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from growbuddy.alerts.evaluator import AlertEvaluator
from growbuddy.clock import now_ms
from growbuddy.config import AlertSettings
from growbuddy.db.connection import Database, load_migrations
from growbuddy.db.queries import sensors as sensor_q
from growbuddy.domain.phase import Milestone, Phase, plant_start

DAY = 86_400_000
API = "/api/v1"


def test_plant_start() -> None:
    assert plant_start({Milestone.SPROUT: 50, Milestone.GERMINATION: 20}, created_ms=10) == 20
    assert plant_start({}, created_ms=10) == 10


class Api:
    def __init__(self, client: TestClient, auth: dict[str, str]) -> None:
        self.c, self.h = client, auth

    def get(self, path: str) -> Any:
        return self.c.get(API + path, headers=self.h).json()

    def post(self, path: str, body: dict[str, Any]) -> Any:
        r = self.c.post(API + path, json=body, headers=self.h)
        assert r.status_code in (200, 201), r.text
        return r.json()

    def patch(self, path: str, body: dict[str, Any]) -> Any:
        r = self.c.patch(API + path, json=body, headers=self.h)
        assert r.status_code == 200, r.text
        return r.json()

    def plant(self, sensor_id: int | None, nickname: str) -> str:
        return str(
            self.post(
                "/plants", {"id": str(uuid.uuid4()), "nickname": nickname, "sensor_id": sensor_id}
            )["id"]
        )

    def log(self, plant_id: str, log_type: str, days_ago: float) -> None:
        self.post(
            "/logs",
            {
                "id": str(uuid.uuid4()),
                "type": log_type,
                "plant_ids": [plant_id],
                "ts": now_ms() - int(days_ago * DAY),
            },
        )


@pytest.fixture
def api(client: TestClient, auth: dict[str, str]) -> Api:
    return Api(client, auth)


def test_auto_ranges_follow_oldest_plant(api: Api) -> None:
    tent = api.post("/sensors", {"mac": "A4:C1:38:12:34:56", "name": "Tent"})["id"]
    detail = api.get(f"/sensors/{tent}")
    assert detail["range_mode"] == "auto" and detail["range_source"] is None
    assert detail["ranges"]["temperature"]["warn_min"] is None  # no plants yet

    old = api.plant(tent, "Old")
    young = api.plant(tent, "Young")
    api.log(old, "Germination", 40)
    api.log(old, "Sprout", 35)  # LateVegetation (> 28 days since sprout)
    api.log(young, "Sprout", 5)  # Seedling, but younger

    detail = api.get(f"/sensors/{tent}")
    assert detail["range_source"] == {
        "plant_id": old,
        "nickname": "Old",
        "phase": Phase.LATE_VEGETATION,
    }
    late_veg = api.get("/phase-ranges")
    template = next(p for p in late_veg if p["phase"] == "LateVegetation")
    assert detail["ranges"]["temperature"]["warn_min"] == template["temp_min"]
    assert detail["ranges"]["humidity"]["warn_max"] == template["humidity_max"]
    assert api.get(f"/sensors/{tent}/ranges") == detail["ranges"]

    api.log(old, "FlowerFlip", 1)  # phase moves on, ranges follow
    assert api.get(f"/sensors/{tent}")["range_source"]["phase"] == "EarlyFlower"

    api.patch(f"/plants/{old}", {"sensor_id": None})  # oldest leaves: next oldest drives
    assert api.get(f"/sensors/{tent}")["range_source"]["nickname"] == "Young"

    env = api.get("/environment/current")
    (sensor_env,) = env["sensors"]
    assert sensor_env["range_source"]["nickname"] == "Young"
    assert sensor_env["sensor"]["range_mode"] == "auto"


def test_inactive_and_archived_plants_do_not_drive(api: Api) -> None:
    tent = api.post("/sensors", {"mac": "A4:C1:38:12:34:56", "name": "Tent"})["id"]
    dead = api.plant(tent, "Dead")
    api.log(dead, "Germination", 30)
    api.log(dead, "TerminatedEarly", 2)
    archived = api.plant(tent, "Archived")
    api.log(archived, "Germination", 20)
    api.patch(f"/plants/{archived}", {"archived": True})
    assert api.get(f"/sensors/{tent}")["range_source"] is None
    fresh = api.plant(tent, "Fresh")  # no milestones: PreGermination, template not enforced
    detail = api.get(f"/sensors/{tent}")
    assert detail["range_source"]["plant_id"] == fresh
    assert detail["ranges"]["temperature"] == {
        "warn_min": None,
        "warn_max": None,
        "crit_min": None,
        "crit_max": None,
    }


def test_drying_chamber_switches_with_harvest(api: Api) -> None:
    dryer = api.post("/sensors", {"mac": "A4:C1:38:00:00:02", "name": "Drying"})["id"]
    plant = api.plant(None, "Mary")
    api.log(plant, "Sprout", 80)
    api.log(plant, "Harvest", 0.1)
    api.patch(f"/plants/{plant}", {"sensor_id": dryer})
    detail = api.get(f"/sensors/{dryer}")
    assert detail["range_source"]["phase"] == "Drying"
    drying = next(p for p in api.get("/phase-ranges") if p["phase"] == "Drying")
    assert detail["ranges"]["temperature"]["warn_max"] == drying["temp_max"]


def test_mode_switching(api: Api) -> None:
    tent = api.post("/sensors", {"mac": "A4:C1:38:12:34:56", "name": "Tent"})["id"]
    plant = api.plant(tent, "P")
    api.log(plant, "Sprout", 3)  # Seedling
    auto = api.get(f"/sensors/{tent}")["ranges"]

    manual = api.patch(f"/sensors/{tent}", {"range_mode": "manual"})
    assert manual["range_mode"] == "manual" and manual["range_source"] is None
    assert manual["ranges"] == auto  # seeded from the auto ranges

    r = api.c.put(
        f"{API}/sensors/{tent}/ranges",
        json={"temperature": {"warn_min": 10, "warn_max": 11}},
        headers=api.h,
    )
    assert r.status_code == 200
    assert api.get(f"/sensors/{tent}")["ranges"]["temperature"]["warn_min"] == 10

    back = api.patch(f"/sensors/{tent}", {"range_mode": "auto"})
    assert back["range_mode"] == "auto" and back["ranges"] == auto
    again = api.patch(f"/sensors/{tent}", {"range_mode": "manual"})
    assert again["ranges"]["temperature"]["warn_min"] == 10  # stored manual ranges are kept

    api.patch(f"/sensors/{tent}", {"range_mode": "auto"})
    api.post(f"/sensors/{tent}/ranges/from-phases", {"phases": ["Drying"], "apply": True})
    assert api.get(f"/sensors/{tent}")["range_mode"] == "manual"


def test_alerts_use_auto_ranges(api: Api) -> None:
    tent = api.post("/sensors", {"mac": "A4:C1:38:12:34:56", "name": "Tent"})["id"]
    plant = api.plant(tent, "P")
    api.log(plant, "Sprout", 3)  # Seedling: 20-25 °C, crit 18-27
    db: Database = api.c.app.state.db  # type: ignore[attr-defined]
    assert api.c.portal is not None
    now = now_ms()
    api.c.portal.call(sensor_q.insert_reading, db, tent, now, 26.0, 67.0, 90, 2.9, -60)
    assert api.get("/environment/current")["sensors"][0]["levels"]["temperature"] == "warning"

    evaluator = AlertEvaluator(db, AlertSettings(debounce_s=0, warning_debounce_s=0), None)
    api.c.portal.call(evaluator.tick, now)
    active = api.get("/alerts/active")
    # 26 °C is above Seedling's 25 °C warn max (VPD 1.11 kPa is also out of range).
    assert ("temperature", "warning") in [(a["metric"], a["level"]) for a in active]


async def test_migration_keeps_hand_set_ranges_manual(tmp_path: Path) -> None:
    path = tmp_path / "old.db"
    first = dict(load_migrations())[1]
    conn = sqlite3.connect(path)
    conn.executescript(first + "\nPRAGMA user_version = 1;")
    conn.execute(
        "INSERT INTO sensors (id, mac, name, adopted_ts) VALUES (1, 'AA:AA:AA:AA:AA:01', 'Set', 0)"
    )
    conn.execute(
        "INSERT INTO sensors (id, mac, name, adopted_ts) "
        "VALUES (2, 'AA:AA:AA:AA:AA:02', 'Unset', 0)"
    )
    conn.execute(
        "INSERT INTO sensor_ranges (sensor_id, metric, warn_min) VALUES (1, 'temperature', 20)"
    )
    conn.commit()
    conn.close()

    db = Database(path)
    await db.open()
    rows = await db.fetchall("SELECT id, range_mode FROM sensors ORDER BY id")
    await db.close()
    assert [(r["id"], r["range_mode"]) for r in rows] == [(1, "manual"), (2, "auto")]
