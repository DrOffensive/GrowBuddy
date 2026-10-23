from __future__ import annotations

import sqlite3
from datetime import datetime, timedelta
from pathlib import Path

from fastapi.testclient import TestClient

from growbuddy.config import BackupSettings
from growbuddy.db.connection import Database
from growbuddy.db.seed import seed_reference_data
from growbuddy.services.snapshot import list_backups, run_snapshot

API = "/api/v1"


# ----------------------------------------------------------------- backups


async def test_snapshot_is_consistent_and_rotated(db: Database, tmp_path: Path) -> None:
    await seed_reference_data(db)
    await db.execute("INSERT INTO settings (key, value) VALUES ('marker', '42')")
    start = datetime(2026, 10, 1, 3, 0).astimezone()
    for day in range(4):
        await run_snapshot(db, tmp_path, BackupSettings(keep_local=3), start + timedelta(days=day))

    names = [b.name for b in list_backups(tmp_path)]
    assert names == ["growbuddy-20261004.db", "growbuddy-20261003.db", "growbuddy-20261002.db"]
    assert not list((tmp_path / "backups").glob("*.part"))
    copy = sqlite3.connect(tmp_path / "backups" / names[0])
    try:
        assert copy.execute("SELECT value FROM settings WHERE key = 'marker'").fetchone() == ("42",)
        assert copy.execute("SELECT COUNT(*) FROM strains").fetchone()[0] > 400
        assert copy.execute("PRAGMA integrity_check").fetchone() == ("ok",)
    finally:
        copy.close()
    events = await db.fetchall("SELECT code FROM events WHERE code = 'snapshot_done'")
    assert len(events) == 4


async def test_same_day_snapshot_replaces_file(db: Database, tmp_path: Path) -> None:
    when = datetime(2026, 10, 1, 3, 0).astimezone()
    await run_snapshot(db, tmp_path, BackupSettings(), when)
    await run_snapshot(db, tmp_path, BackupSettings(), when + timedelta(hours=5))
    assert [b.name for b in list_backups(tmp_path)] == ["growbuddy-20261001.db"]


def test_backups_api(client: TestClient, auth: dict[str, str]) -> None:
    assert client.get(f"{API}/backups", headers=auth).json() == []
    created = client.post(f"{API}/backups", headers=auth)
    assert created.status_code == 201
    body = created.json()
    assert body["name"].startswith("growbuddy-") and body["size_bytes"] > 0
    assert client.get(f"{API}/backups", headers=auth).json() == [body]
    status = client.get(f"{API}/status", headers=auth).json()
    assert status["last_backup"] == body["name"] and status["last_backup_ts"] == body["ts"]


# ----------------------------------------------------------------- strains


def test_strain_search_and_facets(client: TestClient, auth: dict[str, str]) -> None:
    everything = client.get(f"{API}/strains", headers=auth).json()
    assert everything["total"] > 400 and len(everything["items"]) == everything["total"]

    dosi = client.get(f"{API}/strains?q=dos si", headers=auth).json()
    assert dosi["total"] >= 1 and all("dos si" in s["name"].lower() for s in dosi["items"])

    facets = client.get(f"{API}/strains/facets", headers=auth).json()
    assert set(facets) == {"category", "vendor", "shop_category", "flowering_days", "thc"}
    assert facets["category"] == ["Autoflower", "Feminized"]
    vendor = facets["vendor"][0]
    by_vendor = client.get(f"{API}/strains", params={"vendor": vendor}, headers=auth).json()
    assert by_vendor["total"] >= 1 and {s["vendor"] for s in by_vendor["items"]} == {vendor}

    autos = client.get(f"{API}/strains?category=Autoflower&limit=5&offset=5", headers=auth).json()
    assert len(autos["items"]) == 5 and autos["total"] > 100


def test_strain_create_and_patch(client: TestClient, auth: dict[str, str]) -> None:
    created = client.post(
        f"{API}/strains",
        json={"name": "House Kush", "vendor": "Own seeds", "flowering_days": "56"},
        headers=auth,
    )
    assert created.status_code == 201
    strain = created.json()
    assert client.get(f"{API}/strains/{strain['id']}", headers=auth).json() == strain

    dup = client.post(
        f"{API}/strains", json={"name": "House Kush", "vendor": "Own seeds"}, headers=auth
    )
    assert dup.status_code == 409 and dup.json()["error"]["code"] == "duplicate_strain"
    no_vendor = client.post(f"{API}/strains", json={"name": "House Kush"}, headers=auth)
    assert no_vendor.status_code == 201  # different (name, vendor)

    patched = client.patch(
        f"{API}/strains/{strain['id']}", json={"flowering_days": "60", "vendor": None}, headers=auth
    )
    assert patched.status_code == 409  # would collide with the vendor-less one
    patched = client.patch(
        f"{API}/strains/{strain['id']}", json={"flowering_days": "60"}, headers=auth
    ).json()
    assert patched["flowering_days"] == "60" and patched["vendor"] == "Own seeds"
    assert (
        client.patch(f"{API}/strains/{strain['id']}", json={"name": None}, headers=auth).status_code
        == 422
    )
    assert client.get(f"{API}/strains/99999", headers=auth).status_code == 404


# ------------------------------------------------------- nutrients/feeding


def test_nutrient_lines(client: TestClient, auth: dict[str, str]) -> None:
    lines = client.get(f"{API}/nutrient-lines", headers=auth).json()
    brands = [line["brand"] for line in lines]
    assert brands[:3] == ["Advanced Nutrients", "Athena Nutrients", "Biobizz"]
    assert len(brands) == 10
    by_brand = {line["brand"]: line for line in lines}
    assert len(by_brand["CANNA"]["products"]) == 15
    units = {p["name"]: p["unit"] for p in by_brand["BioTabs"]["products"]}
    assert units["BioTabs Tablets"] == "tabs/plant" and units["Orgatrex"] == "ml/L"


def test_feeding_schedules(client: TestClient, auth: dict[str, str]) -> None:
    summaries = client.get(f"{API}/feeding-schedules", headers=auth).json()
    assert len(summaries) == 15
    (summary,) = [s for s in summaries if s["profile_key"] == "biobizz"]
    assert summary["weeks"] == 10
    schedule = client.get(f"{API}/feeding-schedules/{summary['id']}", headers=auth).json()
    week1 = schedule["weeks"][0]
    assert week1["week"] == 1 and week1["phase"] == "Veg"
    assert week1["nutrients"]["BioGrow"] == 1.0
    assert week1["ph"] == {"min": 6.0, "max": 6.4, "target": 6.2}
    assert week1["environment"]["light_hours"] == 18
    assert client.get(f"{API}/feeding-schedules/99", headers=auth).status_code == 404


# ------------------------------------------------------------ phase ranges


def test_phase_ranges(client: TestClient, auth: dict[str, str]) -> None:
    ranges = client.get(f"{API}/phase-ranges", headers=auth).json()
    assert [r["phase"] for r in ranges][:3] == ["PreGermination", "Germinating", "Seedling"]
    assert ranges[-1]["phase"] == "Complete"
    seedling = next(r for r in ranges if r["phase"] == "Seedling")
    assert (seedling["temp_min"], seedling["vpd_max"], seedling["enforce_limits"]) == (
        20.0,
        0.9,
        True,
    )

    body = {**{k: v for k, v in seedling.items() if k != "phase"}, "temp_min": 21.0}
    updated = client.put(f"{API}/phase-ranges/Seedling", json=body, headers=auth).json()
    assert updated["temp_min"] == 21.0
    assert client.put(f"{API}/phase-ranges/Bloom", json=body, headers=auth).status_code == 404
    bad = client.put(f"{API}/phase-ranges/Seedling", json={**body, "temp_min": 30.0}, headers=auth)
    assert bad.status_code == 422

    sensor = client.post(
        f"{API}/sensors", json={"mac": "A4:C1:38:12:34:56", "name": "Tent"}, headers=auth
    ).json()
    preset = client.post(
        f"{API}/sensors/{sensor['id']}/ranges/from-phases",
        json={"phases": ["Seedling"]},
        headers=auth,
    ).json()
    assert preset["temperature"]["warn_min"] == 21.0  # presets use the edited template
