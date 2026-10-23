"""User-made reference data: custom feeding schedules and strains, built-in sync, 0003."""

from __future__ import annotations

import sqlite3
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from growbuddy.db import seed
from growbuddy.db.connection import Database, load_migrations

API = "/api/v1"

WEEK: dict[str, Any] = {
    "phase": "Veg",
    "nutrients": {"Bio-Grow": 2.0},
    "ph": {"min": 6.0, "max": 6.5, "target": 6.2},
    "ec": {"target": 1.2},
    "environment": {"temp_day": {"min": 22, "max": 28}, "light_hours": 18},
    "notes": "Easy start",
}


def _builtin(client: TestClient, auth: dict[str, str]) -> dict[str, Any]:
    (biobizz,) = [
        s
        for s in client.get(f"{API}/feeding-schedules", headers=auth).json()
        if s["profile_key"] == "biobizz"
    ]
    return biobizz  # type: ignore[no-any-return]


# ------------------------------------------------------- feeding schedules


def test_custom_schedule_lifecycle(client: TestClient, auth: dict[str, str]) -> None:
    body = {"display_name": "My soil", "substrate": "soil", "weeks": [WEEK, {"phase": "Flush"}]}
    created = client.post(f"{API}/feeding-schedules", json=body, headers=auth)
    assert created.status_code == 201
    schedule = created.json()
    assert schedule["custom"] and schedule["profile_key"].startswith("custom-")
    assert [(w["week"], w["phase"]) for w in schedule["weeks"]] == [(1, "Veg"), (2, "Flush")]
    first = schedule["weeks"][0]
    assert first["nutrients"] == {"Bio-Grow": 2.0} and first["ec"] == {"target": 1.2}
    assert first["environment"] == {"temp_day": {"min": 22, "max": 28}, "light_hours": 18}
    assert schedule["weeks"][1]["ph"] is None

    listed = client.get(f"{API}/feeding-schedules", headers=auth).json()
    assert {(s["display_name"], s["custom"], s["weeks"]) for s in listed} >= {("My soil", True, 2)}

    sid = schedule["id"]
    body["weeks"] = [WEEK]
    body["display_name"] = "My soil v2"
    replaced = client.put(f"{API}/feeding-schedules/{sid}", json=body, headers=auth).json()
    assert replaced["display_name"] == "My soil v2" and len(replaced["weeks"]) == 1
    assert replaced["profile_key"] == schedule["profile_key"]

    assert client.delete(f"{API}/feeding-schedules/{sid}", headers=auth).status_code == 204
    assert client.get(f"{API}/feeding-schedules/{sid}", headers=auth).status_code == 404


def test_builtin_schedules_are_read_only(client: TestClient, auth: dict[str, str]) -> None:
    sid = _builtin(client, auth)["id"]
    body = {"display_name": "x", "weeks": [WEEK]}
    for response in (
        client.put(f"{API}/feeding-schedules/{sid}", json=body, headers=auth),
        client.delete(f"{API}/feeding-schedules/{sid}", headers=auth),
    ):
        assert response.status_code == 409
        assert response.json()["error"]["code"] == "builtin_schedule"


@pytest.mark.parametrize(
    "change",
    [
        {"weeks": []},
        {"display_name": ""},
        {"weeks": [{**WEEK, "phase": "Bloom"}]},
        {"weeks": [{**WEEK, "nutrients": {"Bio-Grow": -1}}]},
        {"weeks": [{**WEEK, "environment": {"light_hours": 25}}]},
    ],
)
def test_schedule_validation(
    client: TestClient, auth: dict[str, str], change: dict[str, Any]
) -> None:
    body = {"display_name": "x", "weeks": [WEEK], **change}
    assert client.post(f"{API}/feeding-schedules", json=body, headers=auth).status_code == 422


async def test_builtin_schedules_follow_the_seed(
    db: Database, monkeypatch: pytest.MonkeyPatch
) -> None:
    await seed.seed_reference_data(db)
    original = seed._load

    def edited(name: str) -> Any:
        data = original(name)
        if name == "feeding_schedules.json":
            data["schedules"][0]["displayName"] = "BioBizz (renamed)"
            data["schedules"][0]["weekEntries"] = data["schedules"][0]["weekEntries"][:3]
            data["schedules"].append({"profileId": "new", "displayName": "New", "weekEntries": []})
        if name == "nutrient_lines.json":
            data["lines"][0]["products"].append({"name": "New product"})
        return data

    await db.execute(
        """INSERT INTO feeding_schedules (profile_key, display_name, custom)
           VALUES ('custom-mine', 'Mine', 1)"""
    )
    monkeypatch.setattr(seed, "_load", edited)
    assert await seed.seed_reference_data(db) == {"nutrient_lines": 1, "feeding_schedules": 2}
    rows = await db.fetchall(
        """SELECT s.profile_key, s.display_name, COUNT(w.week) AS weeks FROM feeding_schedules s
           LEFT JOIN feeding_schedule_weeks w ON w.schedule_id = s.id
           WHERE s.profile_key IN ('biobizz', 'custom-mine', 'new')
           GROUP BY s.id ORDER BY s.profile_key"""
    )
    assert [tuple(r) for r in rows] == [
        ("biobizz", "BioBizz (renamed)", 3),
        ("custom-mine", "Mine", 0),
        ("new", "New", 0),
    ]
    assert await seed.seed_reference_data(db) == {}  # unchanged seed: nothing rewritten


# ----------------------------------------------------------------- strains


def test_custom_strains(client: TestClient, auth: dict[str, str]) -> None:
    created = client.post(
        f"{API}/strains", json={"name": "Garden Kush", "category": "Autoflower"}, headers=auth
    ).json()
    assert created["custom"] is True
    catalog = client.get(f"{API}/strains?limit=1", headers=auth).json()["items"][0]
    assert catalog["custom"] is False

    blocked = client.delete(f"{API}/strains/{catalog['id']}", headers=auth)
    assert blocked.status_code == 409 and blocked.json()["error"]["code"] == "builtin_strain"

    plant = {"id": "00000000-0000-4000-8000-000000000001", "nickname": "P", "is_autoflower": True}
    client.post(f"{API}/plants", json={**plant, "strain_id": created["id"]}, headers=auth)
    in_use = client.delete(f"{API}/strains/{created['id']}", headers=auth)
    assert in_use.status_code == 409 and in_use.json()["error"]["code"] == "strain_in_use"

    client.patch(f"{API}/plants/{plant['id']}", json={"strain_id": None}, headers=auth)
    assert client.delete(f"{API}/strains/{created['id']}", headers=auth).status_code == 204
    assert client.get(f"{API}/strains/{created['id']}", headers=auth).status_code == 404


def test_migration_0003_marks_api_strains_custom(tmp_path: Path) -> None:
    conn = sqlite3.connect(tmp_path / "m.db")
    try:
        for version, sql in load_migrations():
            if version == 3:
                conn.execute("INSERT INTO strains (name, url) VALUES ('Seeded', 'https://x')")
                conn.execute("INSERT INTO strains (name) VALUES ('Mine')")
            conn.executescript(sql)
        rows = conn.execute("SELECT name, custom FROM strains ORDER BY name").fetchall()
        assert rows == [("Mine", 1), ("Seeded", 0)]
    finally:
        conn.close()


# ------------------------------------------------------------ alert settings


def test_alert_settings_warning_delay(client: TestClient, auth: dict[str, str]) -> None:
    current = client.get(f"{API}/alert-settings", headers=auth).json()
    assert (current["debounce_s"], current["warning_debounce_s"]) == (600, 1800)
    client.put(f"{API}/alert-settings", json={**current, "warning_debounce_s": 900}, headers=auth)
    # An app that doesn't know the field (2.2.0) keeps the stored value.
    older = {k: v for k, v in current.items() if k != "warning_debounce_s"}
    saved = client.put(f"{API}/alert-settings", json=older, headers=auth).json()
    assert saved["warning_debounce_s"] == 900
