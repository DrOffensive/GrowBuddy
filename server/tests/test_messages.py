"""Notification texts per language (growbuddy/locales)."""

from __future__ import annotations

import json
import re
from importlib import resources

import pytest
from fastapi.testclient import TestClient

from growbuddy.alerts.messages import available_languages, messages
from growbuddy.alerts.rules import describe, describe_resolved
from growbuddy.domain.environment import AlarmLevel

API = "/api/v1"
W, C = AlarmLevel.WARNING, AlarmLevel.CRITICAL


def _catalog(code: str) -> dict[str, str]:
    text = resources.files("growbuddy.locales").joinpath(f"{code}.json").read_text("utf-8")
    return json.loads(text)  # type: ignore[no-any-return]


def test_danish_texts() -> None:
    da = messages("da")
    title, message = describe("Telt", "temperature", W, 29.4, (22.0, 28.0), da)
    assert title == "Telt: temperatur for høj"
    assert message == "Temperatur 29,4 °C er uden for advarselsområdet (22,0 °C til 28,0 °C)."
    _, low = describe("Telt", "humidity", C, 41.0, (45.0, None), da)
    assert low == "Luftfugtighed 41,0% er uden for det kritiske område (min. 45,0%)."
    assert describe_resolved("Telt", "vpd", da) == ("Telt: VPD er normal igen", "Løst.")
    assert describe("Telt", "battery_low", W, 12, None, da)[1] == "Batteriet er på 12 %."


def test_unknown_language_is_english() -> None:
    assert describe("Tent", "sensor_offline", C, None, None, messages("xx"))[0] == (
        "Tent: sensor offline"
    )


@pytest.mark.parametrize("code", sorted(available_languages()))
def test_catalogs_are_complete_and_well_formed(code: str) -> None:
    english, catalog = _catalog("en"), _catalog(code)
    assert set(catalog) == set(english), "keys must match en.json"
    for key, text in catalog.items():
        placeholders = set(re.findall(r"{(\w+)}", text))
        assert placeholders == set(re.findall(r"{(\w+)}", english[key])), key


def test_notification_language_api(client: TestClient, auth: dict[str, str]) -> None:
    langs = client.get(f"{API}/notification-languages", headers=auth).json()
    assert {"code": "da", "name": "Dansk"} in langs and {"code": "en", "name": "English"} in langs
    current = client.get(f"{API}/alert-settings", headers=auth).json()
    assert current["language"] == "en"
    saved = client.put(f"{API}/alert-settings", json={**current, "language": "da"}, headers=auth)
    assert saved.json()["language"] == "da"
    bad = client.put(f"{API}/alert-settings", json={**current, "language": "xx"}, headers=auth)
    assert bad.status_code == 422 and bad.json()["error"]["code"] == "unknown_language"
    older = {k: v for k, v in current.items() if k != "language"}  # app 2.3.0
    assert client.put(f"{API}/alert-settings", json=older, headers=auth).json()["language"] == "da"
