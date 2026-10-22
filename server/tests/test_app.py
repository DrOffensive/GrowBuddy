from __future__ import annotations

from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from growbuddy import __version__
from growbuddy.config import Settings


def test_health_is_public(client: TestClient) -> None:
    response = client.get("/api/v1/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "version": __version__}


@pytest.mark.parametrize("headers", [{}, {"Authorization": "Bearer wrong-token-xxxxxxxx"}])
def test_status_requires_token(client: TestClient, headers: dict[str, str]) -> None:
    response = client.get("/api/v1/status", headers=headers)
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "unauthorized"


def test_status(client: TestClient, auth: dict[str, str]) -> None:
    body = client.get("/api/v1/status", headers=auth).json()
    assert body["schema_version"] >= 1
    assert body["db_size_bytes"] > 0
    assert body["disk_free_bytes"] > 0


def test_unknown_route_uses_error_shape(client: TestClient) -> None:
    response = client.get("/api/v1/nope")
    assert response.status_code == 404
    assert response.json()["error"]["code"] == "not_found"


def test_settings_from_toml_and_env(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    config = tmp_path / "config.toml"
    config.write_text(
        '[server]\napi_token = "from-toml-0123456789"\ndata_dir = "/tmp/x"\n'
        "[camera]\ninterval_s = 60\n"
    )
    monkeypatch.setenv("GROWBUDDY_CONFIG", str(config))
    monkeypatch.setenv("GROWBUDDY_CAMERA__INTERVAL_S", "120")
    settings = Settings()  # type: ignore[call-arg]
    assert settings.server.api_token == "from-toml-0123456789"
    assert settings.camera.interval_s == 120  # env beats TOML
    assert settings.alerts.debounce_s == 600  # default


def test_example_config_is_valid(monkeypatch: pytest.MonkeyPatch) -> None:
    example = Path(__file__).parents[1] / "config.example.toml"
    monkeypatch.setenv("GROWBUDDY_CONFIG", str(example))
    monkeypatch.setenv("GROWBUDDY_SERVER__API_TOKEN", "override-0123456789")
    settings = Settings()  # type: ignore[call-arg]
    assert settings.camera.interval_s == 3600
    assert settings.ble.discovery_ttl_s == 300
