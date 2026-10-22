"""Environment snapshots for the app's notification widget."""

from __future__ import annotations

import json
from pathlib import Path

import httpx
from fastapi.testclient import TestClient

from growbuddy.alerts.ntfy import NtfyNotifier
from growbuddy.clock import now_ms
from growbuddy.config import NtfySettings, Settings
from growbuddy.db.connection import Database
from growbuddy.db.queries import sensors as sensor_q
from growbuddy.db.queries.settings import put_setting
from growbuddy.domain.environment import Metric, Range
from growbuddy.main import create_app
from growbuddy.services.environment_push import (
    PUSH_ENVIRONMENT_KEY,
    EnvironmentPusher,
    environment_snapshot,
)

from .conftest import TOKEN


def settings_for(tmp_path: Path) -> Settings:
    return Settings(
        server={"api_token": TOKEN, "data_dir": tmp_path},
        ble={"enabled": False},
        camera={"enabled": False},
    )


async def tent(db: Database) -> int:
    now = now_ms()
    sid = await sensor_q.adopt_sensor(db, "A4:C1:38:12:34:56", "Tent", now - 60_000)
    await sensor_q.put_ranges(
        db,
        sid,
        {
            Metric.TEMPERATURE: Range(20, 26, 18, 28),
            Metric.HUMIDITY: Range(40, 50, 35, 60),
            Metric.VPD: Range(),
        },
    )
    await sensor_q.set_range_mode(db, sid, "manual")
    await sensor_q.insert_reading(db, sid, now - 5_000, 23.5, 55.0, 100, 2.9, -60)
    return sid


async def test_snapshot_holds_what_the_widget_draws(db: Database, tmp_path: Path) -> None:
    sid = await tent(db)
    snapshot = await environment_snapshot(db, settings_for(tmp_path), now_ms())
    assert snapshot["type"] == "environment"
    (sensor,) = snapshot["sensors"]
    assert (sensor["id"], sensor["name"]) == (sid, "Tent")
    assert sensor["reading"]["temperature_c"] == 23.5 and sensor["reading"]["vpd_kpa"] > 0
    assert sensor["ranges"]["humidity"] == {
        "warn_min": 40,
        "warn_max": 50,
        "crit_min": 35,
        "crit_max": 60,
    }
    assert sensor["levels"] == {"temperature": "normal", "humidity": "warning", "vpd": "normal"}
    assert len(json.dumps(snapshot)) < 1000  # well inside ntfy's 4 KB message limit


async def test_pushes_only_when_the_app_wants_snapshots(db: Database, tmp_path: Path) -> None:
    await tent(db)
    sent: list[httpx.Request] = []
    transport = httpx.MockTransport(lambda r: sent.append(r) or httpx.Response(200, json={}))
    notifier = NtfyNotifier(NtfySettings(token="tk_abc"), transport=transport)
    pusher = EnvironmentPusher(db, settings_for(tmp_path), notifier)

    assert await pusher.push_once() is False  # no app registered
    notifier.push_topic = "upAbC123"
    assert await pusher.push_once() is False  # registered, but the widget is off
    await put_setting(db, PUSH_ENVIRONMENT_KEY, True)
    assert await pusher.push_once() is True
    (req,) = sent
    assert req.url.path == "/upAbC123"
    assert json.loads(req.content)["sensors"][0]["name"] == "Tent"
    await notifier.close()


def test_registering_with_environment_pushes_right_away(tmp_path: Path) -> None:
    settings = settings_for(tmp_path)
    with TestClient(create_app(settings)) as client:
        pusher: EnvironmentPusher = client.app.state.environment_pusher  # type: ignore[attr-defined]
        kicked: list[bool] = []
        pusher.kick = lambda: kicked.append(True)  # type: ignore[method-assign]
        body = {"endpoint": "http://growbuddy:2586/upAbC123?up=1", "environment": True}
        put = client.put(
            "/api/v1/push-endpoint", json=body, headers={"Authorization": f"Bearer {TOKEN}"}
        )
        assert put.status_code == 200 and kicked == [True]
        got = client.get(
            "/api/v1/push-endpoint", headers={"Authorization": f"Bearer {TOKEN}"}
        ).json()
        assert got == body
