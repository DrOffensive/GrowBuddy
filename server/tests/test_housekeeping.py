from __future__ import annotations

from datetime import datetime, time, timedelta, timezone
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from growbuddy.clock import now_ms
from growbuddy.config import RetentionSettings
from growbuddy.db.connection import Database
from growbuddy.db.queries import sensors as sensor_q
from growbuddy.db.queries.settings import get_setting
from growbuddy.services import retention as retention_mod
from growbuddy.services.housekeeping import next_daily, next_hourly
from growbuddy.services.retention import DAY_MS, ORPHAN_PHOTO_GRACE_MS, run_retention
from growbuddy.services.rollup import WATERMARK_KEY, run_rollup, watermark
from growbuddy.services.series import Resolution, reading_series

H = 3_600_000
T0 = 1_792_000_000_000 - (1_792_000_000_000 % H)  # an hour boundary
MAC = "A4:C1:38:12:34:56"


async def add_readings(db: Database, sensor_id: int, rows: list[tuple[int, float, float]]) -> None:
    await db.executemany(
        "INSERT INTO readings (sensor_id, ts, temperature_c, humidity_pct) VALUES (?, ?, ?, ?)",
        [(sensor_id, ts, t, h) for ts, t, h in rows],
    )


async def hourly(db: Database) -> list[tuple[int, int, int, float, float, float]]:
    rows = await db.fetchall(
        "SELECT sensor_id, hour_ts, samples, temp_min, temp_avg, temp_max "
        "FROM readings_hourly ORDER BY sensor_id, hour_ts"
    )
    return [tuple(r) for r in rows]  # type: ignore[misc]


# -------------------------------------------------------------- scheduling


def test_next_daily_and_hourly() -> None:
    tz = timezone(timedelta(hours=2))
    now = datetime(2026, 10, 18, 1, 15, tzinfo=tz)
    assert next_daily(now, time(2, 30)) == datetime(2026, 10, 18, 2, 30, tzinfo=tz)
    assert next_daily(now, time(1, 15)) == datetime(2026, 10, 19, 1, 15, tzinfo=tz)
    assert next_hourly(now, 2) == datetime(2026, 10, 18, 2, 2, tzinfo=tz)
    assert next_hourly(now, 20) == datetime(2026, 10, 18, 1, 20, tzinfo=tz)


# ------------------------------------------------------------------ rollup


async def test_rollup_complete_hours_only(db: Database) -> None:
    a = await sensor_q.adopt_sensor(db, MAC, "Tent", 0)
    b = await sensor_q.adopt_sensor(db, "A4:C1:38:00:00:02", "Drying", 0)
    await add_readings(
        db, a, [(T0 + 10, 20.0, 50.0), (T0 + 20, 22.0, 52.0), (T0 + H + 5, 25.0, 60.0)]
    )
    await add_readings(db, b, [(T0 + 30, 18.0, 58.0), (T0 + 2 * H + 1, 17.0, 57.0)])

    assert await run_rollup(db, T0 + 2 * H + 500) == T0 + 2 * H
    assert await hourly(db) == [
        (a, T0, 2, 20.0, 21.0, 22.0),
        (a, T0 + H, 1, 25.0, 25.0, 25.0),
        (b, T0, 1, 18.0, 18.0, 18.0),
    ]  # hour T0+2H is still open
    assert await get_setting(db, WATERMARK_KEY) == T0 + 2 * H


async def test_rollup_recounts_last_hour_for_late_readings(db: Database) -> None:
    a = await sensor_q.adopt_sensor(db, MAC, "Tent", 0)
    await add_readings(db, a, [(T0 + 10, 20.0, 50.0)])
    await run_rollup(db, T0 + H + 1)
    await add_readings(db, a, [(T0 + 50, 30.0, 50.0)])  # arrived late, for the rolled hour
    await run_rollup(db, T0 + H + 2)
    assert await hourly(db) == [(a, T0, 2, 20.0, 25.0, 30.0)]


async def test_rollup_without_readings(db: Database) -> None:
    assert await run_rollup(db, T0) is None
    assert await watermark(db) is None


# --------------------------------------------------------------- retention


async def test_retention(db: Database, tmp_path: Path) -> None:
    now = T0 + 200 * DAY_MS
    a = await sensor_q.adopt_sensor(db, MAC, "Tent", 0)
    old, recent = now - 100 * DAY_MS, now - 10 * DAY_MS
    await add_readings(db, a, [(old, 20.0, 50.0), (recent, 21.0, 51.0)])

    for name, ts in (("old", now - 400 * DAY_MS), ("new", now - DAY_MS)):
        for suffix in ("", "_thumb"):
            (tmp_path / f"snap_{name}{suffix}.jpg").write_bytes(b"x")
        await db.execute(
            "INSERT INTO snapshots (ts, path, thumb_path, width, height, size_bytes, kind) "
            "VALUES (?, ?, ?, 1, 1, 1, 'scheduled')",
            (ts, f"snap_{name}.jpg", f"snap_{name}_thumb.jpg"),
        )
    photos = {
        "orphan_old": now - ORPHAN_PHOTO_GRACE_MS - 1,
        "orphan_new": now - DAY_MS,
        "used_old": 0,
    }
    for pid, created in photos.items():
        (tmp_path / f"{pid}.jpg").write_bytes(b"x")
        (tmp_path / f"{pid}_t.jpg").write_bytes(b"x")
        await db.execute(
            "INSERT INTO photos (id, ts, path, thumb_path, width, height, size_bytes, created_ts) "
            "VALUES (?, 0, ?, ?, 1, 1, 1, ?)",
            (pid, f"{pid}.jpg", f"{pid}_t.jpg", created),
        )
    await db.execute(
        "INSERT INTO plants (id, nickname, created_ts, thumbnail_photo_id) "
        "VALUES ('p', 'P', 0, 'used_old')"
    )
    await db.execute(
        "INSERT INTO events (ts, level, source, code, message) VALUES (?, 'info', 'x', 'old', 'm')",
        (now - 200 * DAY_MS,),
    )

    result = await run_retention(db, tmp_path, RetentionSettings(), now)

    assert (result.readings, result.snapshots, result.events, result.orphan_photos) == (1, 1, 1, 1)
    assert [r["ts"] for r in await db.fetchall("SELECT ts FROM readings")] == [recent]
    assert len(await hourly(db)) == 2  # rollups of the deleted reading survive
    assert (
        not (tmp_path / "snap_old.jpg").exists() and not (tmp_path / "snap_old_thumb.jpg").exists()
    )
    assert (tmp_path / "snap_new.jpg").exists()
    remaining = {r["id"] for r in await db.fetchall("SELECT id FROM photos")}
    assert remaining == {"orphan_new", "used_old"}
    assert not (tmp_path / "orphan_old.jpg").exists()
    (event,) = await db.fetchall("SELECT code, message FROM events")
    assert event["code"] == "retention_done" and "1 readings" in event["message"]


async def test_retention_keeps_readings_not_yet_rolled_up(
    db: Database, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    a = await sensor_q.adopt_sensor(db, MAC, "Tent", 0)
    now = T0 + 200 * DAY_MS
    await add_readings(db, a, [(now - 100 * DAY_MS, 20.0, 50.0), (now - 50 * DAY_MS, 21.0, 51.0)])
    stale_watermark = T0 + 120 * DAY_MS  # rollup stuck between the two readings

    async def stuck_rollup(_db: Database, _now: int) -> int:
        return stale_watermark

    monkeypatch.setattr(retention_mod, "run_rollup", stuck_rollup)
    result = await run_retention(db, tmp_path, RetentionSettings(readings_days=1), now)
    assert result.readings == 1
    assert [r["ts"] for r in await db.fetchall("SELECT ts FROM readings")] == [now - 50 * DAY_MS]


# ------------------------------------------------------------------ series


async def test_raw_series_and_bucketing(db: Database) -> None:
    a = await sensor_q.adopt_sensor(db, MAC, "Tent", 0)
    await add_readings(db, a, [(T0 + i * 10_000, 20.0 + (i % 2), 50.0) for i in range(100)])

    raw = await reading_series(db, a, T0, T0 + H, Resolution.RAW, max_points=1000)
    assert raw.bucket_ms == 0 and len(raw.points) == 100
    first = raw.points[0]
    assert (first.temperature_c, first.samples, first.temp_min) == (20.0, 1, None)
    assert first.vpd_kpa == pytest.approx(1.169, abs=1e-3)

    bucketed = await reading_series(db, a, T0, T0 + 999_999, Resolution.RAW, max_points=10)
    assert bucketed.bucket_ms == 100_000
    assert len(bucketed.points) == 10 and bucketed.points[0].samples == 10
    assert (bucketed.points[0].temp_min, bucketed.points[0].temp_max) == (20.0, 21.0)
    assert bucketed.points[0].temperature_c == 20.5


async def test_hourly_series_combines_rollups_and_live_hour(db: Database) -> None:
    a = await sensor_q.adopt_sensor(db, MAC, "Tent", 0)
    await add_readings(
        db, a, [(T0 + 1, 20.0, 50.0), (T0 + H + 1, 22.0, 50.0), (T0 + 2 * H + 1, 24.0, 50.0)]
    )
    await run_rollup(db, T0 + 2 * H + 10)  # rolls T0 and T0+H
    series = await reading_series(db, a, T0, T0 + 3 * H, Resolution.HOUR, 2000)
    assert series.resolution is Resolution.HOUR and series.bucket_ms == H
    assert [(p.ts, p.temperature_c) for p in series.points] == [
        (T0, 20.0), (T0 + H, 22.0), (T0 + 2 * H, 24.0),
    ]  # fmt: skip


async def test_auto_resolution(db: Database) -> None:
    a = await sensor_q.adopt_sensor(db, MAC, "Tent", 0)
    day = await reading_series(db, a, T0, T0 + 24 * H, Resolution.AUTO, 2000)
    week = await reading_series(db, a, T0, T0 + 7 * 24 * H, Resolution.AUTO, 2000)
    assert (day.resolution, week.resolution) == (Resolution.RAW, Resolution.HOUR)


def test_readings_api(client: TestClient, auth: dict[str, str]) -> None:
    sensor = client.post("/api/v1/sensors", json={"mac": MAC, "name": "Tent"}, headers=auth).json()
    db: Database = client.app.state.db  # type: ignore[attr-defined]
    assert client.portal is not None
    ts = now_ms() - 60_000
    client.portal.call(add_readings, db, sensor["id"], [(ts, 23.0, 55.0)])

    body = client.get(f"/api/v1/readings?sensor_id={sensor['id']}", headers=auth).json()
    assert body["resolution"] == "raw" and body["to_ts"] - body["from_ts"] == 24 * H
    assert [(p["ts"], p["temperature_c"]) for p in body["points"]] == [(ts, 23.0)]

    client.delete(f"/api/v1/sensors/{sensor['id']}", headers=auth)  # history stays readable
    week = client.get(
        f"/api/v1/readings?sensor_id={sensor['id']}&from={ts - 7 * 24 * H}&to={ts + 1}",
        headers=auth,
    ).json()
    assert week["resolution"] == "hour" and week["points"][0]["temperature_c"] == 23.0

    assert client.get("/api/v1/readings?sensor_id=99", headers=auth).status_code == 404
    bad = client.get(f"/api/v1/readings?sensor_id={sensor['id']}&from=10&to=5", headers=auth)
    assert bad.status_code == 422 and bad.json()["error"]["code"] == "invalid_range"
