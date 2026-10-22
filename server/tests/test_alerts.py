from __future__ import annotations

import json

import httpx
import pytest
from fastapi.testclient import TestClient

from growbuddy.alerts.evaluator import AlertEvaluator
from growbuddy.alerts.ntfy import Notification, NotifyError, NtfyNotifier, push_topic
from growbuddy.alerts.rules import Pending, Transition, delay_ms, describe, step
from growbuddy.alerts.settings import save_alert_settings
from growbuddy.config import AlertSettings, NtfySettings, Settings
from growbuddy.db.connection import Database
from growbuddy.db.queries import sensors as sensor_q
from growbuddy.domain.environment import AlarmLevel, Metric, Range
from growbuddy.errors import Invalid
from growbuddy.main import create_app

N, W, C = AlarmLevel.NORMAL, AlarmLevel.WARNING, AlarmLevel.CRITICAL
MIN = 60_000
T0 = 1_792_000_000_000
DEFAULTS = AlertSettings(
    debounce_s=600,
    warning_debounce_s=1800,
    repeat_after_s=7200,
    offline_after_s=300,
    battery_low_pct=15,
)


# ------------------------------------------------------------ state machine


@pytest.mark.parametrize(
    ("active", "observed", "minutes"),
    [
        (None, W, 30),  # a new warning waits longer
        (N, W, 30),
        (None, C, 10),  # criticals don't
        (W, C, 10),
        (C, W, 10),  # nor does calming down from critical
        (W, N, 10),
        (C, N, 10),
    ],
)
def test_delay_depends_on_transition(
    active: AlarmLevel | None, observed: AlarmLevel, minutes: int
) -> None:
    assert delay_ms(active, observed, 600, 1800) == minutes * MIN


def test_step_requires_persistence() -> None:
    t, p = step(None, None, W, T0, 10 * MIN)
    assert t is None and p == Pending(W, T0)
    t, p = step(None, p, W, T0 + 9 * MIN, 10 * MIN)
    assert t is None and p == Pending(W, T0)
    t, p = step(None, p, W, T0 + 10 * MIN, 10 * MIN)
    assert t == Transition(W, T0) and p is None


def test_step_flapping_resets_pending() -> None:
    _, p = step(None, None, W, T0, 10 * MIN)
    t, p = step(None, p, N, T0 + 5 * MIN, 10 * MIN)  # back to normal: nothing pending
    assert t is None and p is None
    _, p = step(None, p, C, T0 + 6 * MIN, 10 * MIN)
    assert p == Pending(C, T0 + 6 * MIN)  # a different level restarts the clock


def test_step_escalation_resolution_and_immediate() -> None:
    assert step(W, None, C, T0, 0) == (Transition(C, T0), None)
    assert step(C, None, N, T0, 0) == (Transition(N, T0), None)
    assert step(W, None, W, T0, 0) == (None, None)


def test_describe() -> None:
    title, message = describe("Tent", "temperature", W, 29.44, (22.0, 28.0))
    assert title == "Tent: temperature high"
    assert message == "Temperature 29.4 °C is outside the warning range (22.0 °C to 28.0 °C)."
    _, low = describe("Dry", "humidity", C, 41.0, (45.0, None))
    assert low == "Humidity 41.0% is outside the critical range (min 45.0%)."
    assert describe("Tent", "sensor_offline", C, None, None)[0] == "Tent: sensor offline"


# --------------------------------------------------------------- evaluator


class FakeNotifier:
    def __init__(self) -> None:
        self.sent: list[Notification] = []
        self.fail = False

    async def send(self, notification: Notification) -> None:
        if self.fail:
            raise NotifyError("ntfy unreachable: connection refused")
        self.sent.append(notification)

    @property
    def titles(self) -> list[str]:
        return [n.title for n in self.sent]


class Rig:
    def __init__(self, db: Database, notifier: FakeNotifier, sensor_id: int) -> None:
        self.db, self.notifier, self.sid = db, notifier, sensor_id
        self.camera_failed = False
        self.ev = AlertEvaluator(db, DEFAULTS, notifier, camera_failed=lambda: self.camera_failed)

    async def reading(
        self, ts: int, temp: float = 24.0, hum: float = 60.0, battery: int = 80
    ) -> None:
        await sensor_q.insert_reading(self.db, self.sid, ts, temp, hum, battery, 2.9, -60)

    async def active(self) -> list[tuple[str, str]]:
        rows = await self.db.fetchall(
            "SELECT metric, level FROM alerts WHERE ended_ts IS NULL ORDER BY metric"
        )
        return [(r["metric"], r["level"]) for r in rows]


@pytest.fixture
async def rig(db: Database) -> Rig:
    sid = await sensor_q.adopt_sensor(db, "A4:C1:38:12:34:56", "Tent", T0 - 60 * MIN)
    await sensor_q.put_ranges(
        db,
        sid,
        {
            Metric.TEMPERATURE: Range(20, 28, 18, 30),
            Metric.HUMIDITY: Range(),
            Metric.VPD: Range(),
        },
    )
    await sensor_q.set_range_mode(db, sid, "manual")
    return Rig(db, FakeNotifier(), sid)


async def test_range_alert_lifecycle(rig: Rig) -> None:
    await rig.reading(T0, temp=29.0)
    await rig.ev.tick(T0)
    assert await rig.active() == [] and rig.notifier.sent == []

    await rig.reading(T0 + 10 * MIN, temp=29.2)
    await rig.ev.tick(T0 + 10 * MIN)
    assert await rig.active() == []  # warnings wait warning_debounce_s (30 min)
    await rig.reading(T0 + 30 * MIN, temp=29.2)
    await rig.ev.tick(T0 + 30 * MIN)
    assert await rig.active() == [("temperature", "warning")]
    (note,) = rig.notifier.sent
    assert (
        note.title == "Tent: temperature high" and note.priority == 4 and note.tags == ["warning"]
    )
    row = await rig.db.fetchone("SELECT * FROM alerts")
    assert row is not None and row["started_ts"] == T0  # condition start, not detection time
    assert (row["band_min"], row["band_max"]) == (20, 28)
    events = await rig.db.fetchall("SELECT code FROM events")
    assert [e["code"] for e in events] == ["temperature_warning"]

    await rig.reading(T0 + 31 * MIN, temp=24.0)
    await rig.ev.tick(T0 + 31 * MIN)
    await rig.reading(T0 + 40 * MIN, temp=24.0)
    await rig.ev.tick(T0 + 40 * MIN)
    assert await rig.active() == [("temperature", "warning")]  # recovery is debounced too
    await rig.reading(T0 + 41 * MIN, temp=24.0)
    await rig.ev.tick(T0 + 41 * MIN)
    assert await rig.active() == []
    assert rig.notifier.titles[-1] == "Tent: temperature back to normal"
    assert rig.notifier.sent[-1].priority == 3


async def test_flapping_never_alerts(rig: Rig) -> None:
    for i in range(6):  # 4-minute swings never last the delay
        await rig.reading(T0 + i * 4 * MIN, temp=29.0 if i % 2 == 0 else 24.0)
        await rig.ev.tick(T0 + i * 4 * MIN)
    assert await rig.active() == [] and rig.notifier.sent == []


async def test_escalation_and_repeat(rig: Rig) -> None:
    for minutes in (0, 30):
        await rig.reading(T0 + minutes * MIN, temp=29.0)
        await rig.ev.tick(T0 + minutes * MIN)
    for minutes in (31, 41):
        await rig.reading(T0 + minutes * MIN, temp=31.0)
        await rig.ev.tick(T0 + minutes * MIN)
    assert await rig.active() == [("temperature", "critical")]
    assert rig.notifier.sent[-1].priority == 5
    history = await rig.db.fetchall("SELECT level, ended_ts FROM alerts ORDER BY id")
    assert [(r["level"], r["ended_ts"] is not None) for r in history] == [
        ("warning", True),
        ("critical", False),
    ]

    await rig.reading(T0 + 41 * MIN + 119 * MIN, temp=31.5)
    await rig.ev.tick(T0 + 41 * MIN + 119 * MIN)
    assert len(rig.notifier.sent) == 2
    await rig.reading(T0 + 41 * MIN + 120 * MIN, temp=31.5)
    await rig.ev.tick(T0 + 41 * MIN + 120 * MIN)
    assert rig.notifier.titles[-1] == "Still: Tent: temperature high"


async def test_offline_is_immediate_and_suppresses_ranges(rig: Rig) -> None:
    await rig.reading(T0, temp=35.0)
    await rig.ev.tick(T0 + 6 * MIN)  # last seen 6 min ago > offline_after 5 min
    assert await rig.active() == [("sensor_offline", "critical")]
    assert rig.notifier.titles == ["Tent: sensor offline"]
    await rig.reading(T0 + 7 * MIN)
    await rig.ev.tick(T0 + 7 * MIN)
    assert await rig.active() == []
    assert rig.notifier.titles[-1] == "Tent: sensor back online"


async def test_never_seen_sensor_goes_offline_after_adoption(rig: Rig) -> None:
    await rig.ev.tick(T0)  # adopted 60 min before T0, no readings
    assert await rig.active() == [("sensor_offline", "critical")]


async def test_battery_hysteresis(rig: Rig) -> None:
    await rig.reading(T0, battery=15)
    await rig.ev.tick(T0)
    assert await rig.active() == [("battery_low", "warning")]
    await rig.reading(T0 + MIN, battery=19)
    await rig.ev.tick(T0 + MIN)
    assert await rig.active() == [("battery_low", "warning")]  # needs > 15 + 5
    await rig.reading(T0 + 2 * MIN, battery=21)
    await rig.ev.tick(T0 + 2 * MIN)
    assert await rig.active() == []


async def test_disabling_alerts_closes_silently(rig: Rig) -> None:
    await rig.reading(T0, battery=10)
    await rig.ev.tick(T0)
    await sensor_q.update_sensor(rig.db, rig.sid, None, alerts_enabled=False)
    await rig.ev.tick(T0 + MIN)
    assert await rig.active() == []
    assert rig.notifier.titles == ["Tent: battery low"]


async def test_failed_delivery_is_retried(rig: Rig) -> None:
    rig.notifier.fail = True
    await rig.reading(T0, battery=10)
    await rig.ev.tick(T0)
    await rig.ev.tick(T0 + MIN)
    assert rig.ev.status().outbox == 1 and rig.ev.status().last_notify_error
    codes = [r["code"] for r in await rig.db.fetchall("SELECT code FROM events")]
    assert codes.count("notify_failed") == 1
    rig.notifier.fail = False
    await rig.ev.tick(T0 + 2 * MIN)
    assert rig.notifier.titles == ["Tent: battery low"] and rig.ev.status().outbox == 0


async def test_restart_does_not_renotify(rig: Rig) -> None:
    await rig.reading(T0, battery=10)
    await rig.ev.tick(T0)
    fresh = AlertEvaluator(rig.db, DEFAULTS, rig.notifier)
    await rig.reading(T0 + MIN, battery=10)
    await fresh.tick(T0 + MIN)
    assert rig.notifier.titles == ["Tent: battery low"]


async def test_notify_toggles(rig: Rig) -> None:
    await save_alert_settings(rig.db, DEFAULTS.model_copy(update={"notify_warning": False}))
    await rig.reading(T0, battery=10)
    await rig.ev.tick(T0)
    assert await rig.active() == [("battery_low", "warning")]
    assert rig.notifier.sent == []


async def test_camera_failure_alert(rig: Rig) -> None:
    await rig.reading(T0)
    rig.camera_failed = True
    await rig.ev.tick(T0)
    assert ("camera", "warning") in await rig.active()
    rig.camera_failed = False
    await rig.ev.tick(T0 + MIN)
    assert ("camera", "warning") not in await rig.active()
    assert rig.notifier.titles == ["Camera not working", "Camera working"]


# -------------------------------------------------------------------- ntfy


async def test_ntfy_publishes_json_with_token() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json={"id": "x"})

    settings = NtfySettings(url="http://ntfy.test:2586/", topic="growbuddy", token="tk_abc")
    notifier = NtfyNotifier(settings, transport=httpx.MockTransport(handler))
    await notifier.send(Notification("Tent: temperature high", "29.4 °C", 4, ["warning"]))
    await notifier.close()
    (req,) = seen
    assert str(req.url) == "http://ntfy.test:2586"
    assert req.headers["authorization"] == "Bearer tk_abc"
    assert json.loads(req.content) == {
        "topic": "growbuddy",
        "title": "Tent: temperature high",
        "message": "29.4 °C",
        "priority": 4,
        "tags": ["warning"],
        "click": "growbuddy://alerts",
    }


async def test_ntfy_errors_raise() -> None:
    notifier = NtfyNotifier(
        NtfySettings(),
        transport=httpx.MockTransport(lambda r: httpx.Response(403, text="forbidden")),
    )
    with pytest.raises(NotifyError, match="403"):
        await notifier.send(Notification("t", "m", 3))
    await notifier.close()


async def test_app_push_topic_gets_the_alert_and_falls_back_to_the_shared_topic() -> None:
    seen: list[httpx.Request] = []
    push_down = False

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        if push_down and request.url.path != "/":
            return httpx.Response(500, text="boom")
        return httpx.Response(200, json={"id": "x"})

    notifier = NtfyNotifier(NtfySettings(token="tk_abc"), transport=httpx.MockTransport(handler))
    notifier.push_topic = "upAbC123"
    await notifier.send(Notification("Tent: humidity low", "37 %", 4, ["warning"]))
    (req,) = seen
    assert req.url.path == "/upAbC123" and req.headers["authorization"] == "Bearer tk_abc"
    assert json.loads(req.content) == {
        "type": "alert",
        "title": "Tent: humidity low",
        "message": "37 %",
        "priority": 4,
        "tags": ["warning"],
        "click": "growbuddy://alerts",
    }

    push_down, seen = True, []
    await notifier.send(Notification("t", "m", 3))
    assert [r.url.path for r in seen] == ["/upAbC123", "/"]  # retried on the shared topic
    assert json.loads(seen[1].content)["topic"] == "growbuddy"
    await notifier.close()


@pytest.mark.parametrize(
    ("endpoint", "topic"),
    [
        ("http://growbuddy:2586/upAbC_12-x?up=1", "upAbC_12-x"),
        ("http://growbuddy:2586/upZ", "upZ"),
        ("https://ntfy.sh/upAbC123?up=1", None),  # ntfy app still on its default server
        ("http://growbuddy:2586/growbuddy", None),  # not an up… topic
        ("http://growbuddy:2586/up/../x", None),
    ],
)
def test_push_topic_validation(endpoint: str, topic: str | None) -> None:
    settings = NtfySettings(public_url="http://growbuddy:2586")
    if topic is None:
        with pytest.raises(Invalid, match="Default server"):
            push_topic(settings, endpoint)
    else:
        assert push_topic(settings, endpoint) == topic


# --------------------------------------------------------------------- API


def test_alert_settings_api(client: TestClient, auth: dict[str, str]) -> None:
    current = client.get("/api/v1/alert-settings", headers=auth).json()
    assert current["debounce_s"] == 600 and current["notify_resolved"] is True
    updated = client.put(
        "/api/v1/alert-settings",
        json={**current, "debounce_s": 120, "notify_resolved": False},
        headers=auth,
    ).json()
    assert updated["debounce_s"] == 120 and updated["notify_resolved"] is False
    assert client.get("/api/v1/alert-settings", headers=auth).json() == updated
    bad = client.put("/api/v1/alert-settings", json={**current, "repeat_after_s": 5}, headers=auth)
    assert bad.status_code == 422


def test_alerts_and_events_api(client: TestClient, auth: dict[str, str]) -> None:
    sensor = client.post(
        "/api/v1/sensors", json={"mac": "A4:C1:38:12:34:56", "name": "Tent"}, headers=auth
    ).json()
    ev: AlertEvaluator = client.app.state.alerts  # type: ignore[attr-defined]
    assert client.portal is not None
    client.portal.call(ev.tick, sensor["adopted_ts"] + 10 * MIN)  # never seen -> offline

    (active,) = client.get("/api/v1/alerts/active", headers=auth).json()
    assert (active["sensor_name"], active["metric"], active["level"]) == (
        "Tent",
        "sensor_offline",
        "critical",
    )
    page = client.get(f"/api/v1/alerts?sensor_id={sensor['id']}", headers=auth).json()
    assert [a["id"] for a in page["items"]] == [active["id"]]
    assert client.get("/api/v1/status", headers=auth).json()["alerts"]["active_alerts"] == 1

    events = client.get("/api/v1/events?source=alerts", headers=auth).json()["items"]
    assert [e["code"] for e in events] == ["sensor_offline_critical"]
    assert client.get("/api/v1/events?level=bogus", headers=auth).status_code == 422

    test = client.post("/api/v1/alerts/test", headers=auth)
    assert test.status_code == 409 and test.json()["error"]["code"] == "ntfy_disabled"


def test_push_endpoint_api(settings: Settings, auth: dict[str, str]) -> None:
    settings.ntfy.enabled = True
    with TestClient(create_app(settings)) as client:
        notifier: NtfyNotifier = client.app.state.notifier  # type: ignore[attr-defined]
        url = "/api/v1/push-endpoint"
        assert client.get(url, headers=auth).json() == {"endpoint": None, "environment": False}
        bad = client.put(url, json={"endpoint": "https://ntfy.sh/up123"}, headers=auth)
        assert bad.status_code == 422 and bad.json()["error"]["code"] == "invalid_push_endpoint"

        endpoint = "http://growbuddy:2586/upAbC123?up=1"
        assert client.put(url, json={"endpoint": endpoint}, headers=auth).status_code == 200
        assert client.get(url, headers=auth).json() == {"endpoint": endpoint, "environment": False}
        assert notifier.push_topic == "upAbC123"

    with TestClient(create_app(settings)) as client:  # restored after a restart
        notifier = client.app.state.notifier  # type: ignore[attr-defined]
        assert notifier.push_topic == "upAbC123"
        assert client.delete(url, headers=auth).status_code == 204
        assert notifier.push_topic is None
        assert client.get(url, headers=auth).json() == {"endpoint": None, "environment": False}


def test_push_endpoint_needs_ntfy(client: TestClient, auth: dict[str, str]) -> None:
    put = client.put(
        "/api/v1/push-endpoint", json={"endpoint": "http://growbuddy:2586/up1"}, headers=auth
    )
    assert put.status_code == 409 and put.json()["error"]["code"] == "ntfy_disabled"
