from __future__ import annotations

from collections.abc import AsyncIterator, Iterator
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from growbuddy.config import Settings
from growbuddy.db.connection import Database
from growbuddy.main import create_app

TOKEN = "test-token-0123456789"


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(
        server={"api_token": TOKEN, "data_dir": tmp_path},
        ble={"enabled": False},
        camera={"enabled": False},
        ntfy={"enabled": False},
    )


@pytest.fixture
async def db(tmp_path: Path) -> AsyncIterator[Database]:
    database = Database(tmp_path / "test.db")
    await database.open()
    yield database
    await database.close()


@pytest.fixture
def client(settings: Settings) -> Iterator[TestClient]:
    with TestClient(create_app(settings)) as c:
        yield c


@pytest.fixture
def auth() -> dict[str, str]:
    return {"Authorization": f"Bearer {TOKEN}"}
