from __future__ import annotations

import asyncio
import time
from pathlib import Path
from typing import Any, ClassVar

import cv2
import numpy as np
import pytest
from fastapi.testclient import TestClient

from growbuddy.camera import service as service_mod
from growbuddy.camera.service import CameraService, CameraState, CameraUnavailable, next_slot
from growbuddy.camera.sources import CameraError
from growbuddy.camera.storage import SnapshotKind, relative_paths
from growbuddy.clock import now_ms
from growbuddy.config import CameraSettings, Settings
from growbuddy.db.connection import Database
from growbuddy.main import create_app

from .conftest import TOKEN


def jpeg(width: int = 640, height: int = 480, value: int = 100) -> bytes:
    ok, buf = cv2.imencode(".jpg", np.full((height, width, 3), value, np.uint8))
    assert ok
    return buf.tobytes()


class FakeSource:
    """Counts lifecycle calls; frames are tiny JPEGs. ``fail_opens`` fails the first N opens."""

    instances: ClassVar[list[FakeSource]] = []
    fail_opens = 0

    def __init__(self, settings: CameraSettings) -> None:
        self.opened = self.closed = self.reads = 0
        FakeSource.instances.append(self)

    def open(self) -> None:
        if FakeSource.fail_opens > 0:
            FakeSource.fail_opens -= 1
            raise CameraError("cannot open /dev/video0")
        self.opened += 1

    def read(self) -> bytes:
        self.reads += 1
        time.sleep(0.005)
        return jpeg(value=self.reads % 255)

    def close(self) -> None:
        self.closed += 1


@pytest.fixture(autouse=True)
def _reset_fake() -> None:
    FakeSource.instances = []
    FakeSource.fail_opens = 0


def make(db: Database, tmp_path: Path, **overrides: Any) -> CameraService:
    settings = CameraSettings(
        **{"warmup_frames": 2, "idle_close_s": 0.05, "interval_s": 3600, **overrides}
    )
    return CameraService(db, settings, tmp_path, source_factory=FakeSource)


async def wait_until(predicate: Any, attempts: int = 200) -> None:
    for _ in range(attempts):
        if predicate():
            return
        await asyncio.sleep(0.01)
    raise AssertionError("condition not reached")


def test_next_slot_aligns_to_interval() -> None:
    hour = 3_600_000
    assert next_slot(5 * hour, hour) == 6 * hour
    assert next_slot(5 * hour + 1, hour) == 6 * hour
    assert next_slot(5 * hour - 1, hour) == 5 * hour


def test_snapshot_paths() -> None:
    rel, thumb = relative_paths(1_792_281_600_000, SnapshotKind.SCHEDULED)
    assert rel == "snapshots/2026/10/18/1792281600000_scheduled.jpg"
    assert thumb == "snapshots/2026/10/18/1792281600000_scheduled_thumb.jpg"


async def test_device_opens_on_demand_and_closes_when_idle(db: Database, tmp_path: Path) -> None:
    cam = make(db, tmp_path)
    await cam.start()
    await asyncio.sleep(0.05)
    assert FakeSource.instances == [] and cam.status().state is CameraState.IDLE

    frame = await cam.fresh_frame()
    assert frame.jpeg[:2] == b"\xff\xd8"
    (src,) = FakeSource.instances
    assert src.opened == 1 and src.reads >= 3  # 2 warmup frames discarded
    await wait_until(lambda: src.closed == 1)
    assert cam.status().state is CameraState.IDLE
    await cam.stop()


async def test_capture_stores_snapshot_with_thumbnail(db: Database, tmp_path: Path) -> None:
    cam = make(db, tmp_path)
    await cam.start()
    row = await cam.capture(SnapshotKind.MANUAL)
    await cam.stop()
    assert (row["kind"], row["width"], row["height"]) == ("manual", 640, 480)
    assert (tmp_path / row["path"]).read_bytes()[:2] == b"\xff\xd8"
    thumb = cv2.imread(str(tmp_path / row["thumb_path"]))
    assert thumb.shape[:2] == (300, 400)
    assert cam.status().last_snapshot_ts == row["ts"]


async def test_stream_viewers_share_one_device(db: Database, tmp_path: Path) -> None:
    cam = make(db, tmp_path, max_viewers=2)
    await cam.start()

    async def take(n: int) -> list[int]:
        seqs = []
        async for f in cam.frames(cam.max_viewers):
            seqs.append(f.seq)
            if len(seqs) == n:
                break
        return seqs

    a, b = await asyncio.gather(take(3), take(3))
    await cam.stop()
    assert a == sorted(set(a)) and b == sorted(set(b))  # strictly increasing, no repeats
    assert len(FakeSource.instances) == 1


async def test_viewer_limit(db: Database, tmp_path: Path) -> None:
    cam = make(db, tmp_path, max_viewers=1)
    await cam.start()
    first = cam.frames(1)
    await anext(first)
    with pytest.raises(CameraUnavailable) as exc:
        await anext(cam.frames(1))
    assert exc.value.code == "too_many_viewers"
    await first.aclose()
    await cam.stop()


async def test_open_failure_is_recorded_and_retried(
    db: Database, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(service_mod, "_BACKOFF_START_S", 0.01)
    FakeSource.fail_opens = 2
    cam = make(db, tmp_path)
    await cam.start()
    frame = await cam.fresh_frame()
    await cam.stop()
    assert frame.seq >= 1
    codes = [r["code"] for r in await db.fetchall("SELECT code FROM events ORDER BY id")]
    assert codes == ["camera_failed", "camera_recovered"]  # same error only recorded once


async def test_frame_timeout_reports_error(
    db: Database, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(service_mod, "FRAME_TIMEOUT_S", 0.2)
    monkeypatch.setattr(service_mod, "_BACKOFF_START_S", 10)
    FakeSource.fail_opens = 1
    cam = make(db, tmp_path)
    await cam.start()
    with pytest.raises(CameraUnavailable, match="cannot open"):
        await cam.fresh_frame()
    assert cam.status().state is CameraState.FAILED
    await cam.stop()


async def test_scheduled_capture(db: Database, tmp_path: Path) -> None:
    cam = make(db, tmp_path, interval_s=1)
    await cam.start()
    for _ in range(300):
        if await db.fetchval("SELECT COUNT(*) FROM snapshots"):
            break
        await asyncio.sleep(0.01)
    await cam.stop()
    row = await db.fetchone("SELECT * FROM snapshots")
    assert row is not None and row["kind"] == "scheduled"
    assert row["ts"] % 1000 < 900  # captured just after the aligned slot


async def test_scheduled_snapshot_is_retried(
    db: Database, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(service_mod, "FRAME_TIMEOUT_S", 0.2)
    monkeypatch.setattr(service_mod, "_BACKOFF_START_S", 0.1)
    monkeypatch.setattr(service_mod, "_SNAPSHOT_RETRY_S", (0.3,))
    FakeSource.fail_opens = 2  # fills the first attempt's frame timeout
    cam = make(db, tmp_path)
    await cam.start()
    await cam._scheduled_capture(now_ms() + 10_000)
    await cam.stop()
    assert await db.fetchval("SELECT COUNT(*) FROM snapshots WHERE kind = 'scheduled'") == 1
    codes = [r["code"] for r in await db.fetchall("SELECT code FROM events ORDER BY id")]
    assert codes == ["camera_failed", "camera_recovered", "snapshot_retried"]


async def test_scheduled_snapshot_gives_up_before_the_next_slot(
    db: Database, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(service_mod, "FRAME_TIMEOUT_S", 0.1)
    monkeypatch.setattr(service_mod, "_BACKOFF_START_S", 0.05)
    monkeypatch.setattr(service_mod, "_SNAPSHOT_RETRY_S", (0.1, 60.0))
    FakeSource.fail_opens = 1000
    cam = make(db, tmp_path)
    await cam.start()
    await cam._scheduled_capture(now_ms() + 1_000)  # the 60 s retry would pass the next slot
    await cam.stop()
    assert await db.fetchval("SELECT COUNT(*) FROM snapshots") == 0
    row = await db.fetchone("SELECT message FROM events WHERE code = 'snapshot_missed'")
    assert row is not None and "after 2 attempt(s)" in row["message"]


async def test_disabled_camera(db: Database, tmp_path: Path) -> None:
    cam = make(db, tmp_path, enabled=False)
    await cam.start()
    assert cam.status().state is CameraState.DISABLED
    with pytest.raises(CameraUnavailable) as exc:
        await cam.fresh_frame()
    assert exc.value.code == "camera_disabled"


# ------------------------------------------------------------------- API


@pytest.fixture
def cam_client(tmp_path: Path) -> Any:
    settings = Settings(
        server={"api_token": TOKEN, "data_dir": tmp_path},
        ble={"enabled": False},
        camera={
            "simulate": True,
            "stream_fps": 30,
            "warmup_frames": 1,
            "width": 320,
            "height": 240,
        },
        ntfy={"enabled": False},
    )
    with TestClient(create_app(settings)) as c:
        c.headers["Authorization"] = f"Bearer {TOKEN}"
        yield c


def test_camera_api(cam_client: TestClient) -> None:
    frame = cam_client.get("/api/v1/camera/frame")
    assert frame.status_code == 200 and frame.content[:2] == b"\xff\xd8"
    assert frame.headers["cache-control"] == "no-store"

    shot = cam_client.post("/api/v1/camera/capture")
    assert shot.status_code == 201
    meta = shot.json()
    assert (meta["kind"], meta["width"], meta["height"]) == ("manual", 320, 240)

    page = cam_client.get("/api/v1/snapshots").json()
    assert [s["id"] for s in page["items"]] == [meta["id"]] and page["next_before"] is None
    assert cam_client.get("/api/v1/snapshots?kind=scheduled").json()["items"] == []
    image = cam_client.get(f"/api/v1/snapshots/{meta['id']}/image?thumb=1")
    assert image.status_code == 200 and "immutable" in image.headers["cache-control"]
    assert cam_client.get("/api/v1/snapshots/999/image").status_code == 404

    status = cam_client.get("/api/v1/status").json()["camera"]
    assert status["last_snapshot_ts"] == meta["ts"] and status["next_snapshot_ts"] > meta["ts"]


def test_mjpeg_stream(cam_client: TestClient) -> None:
    r = cam_client.get("/api/v1/camera/stream?frames=3")
    assert r.status_code == 200
    assert r.headers["content-type"] == "multipart/x-mixed-replace; boundary=frame"
    parts = r.content.split(b"--frame\r\n")[1:]
    assert len(parts) == 3
    for part in parts:
        head, body = part.split(b"\r\n\r\n", 1)
        assert b"Content-Type: image/jpeg" in head
        assert body[:2] == b"\xff\xd8" and body.endswith(b"\xff\xd9\r\n")


def test_camera_disabled_returns_503(client: TestClient, auth: dict[str, str]) -> None:
    for method, path in [
        ("GET", "/camera/frame"),
        ("GET", "/camera/stream"),
        ("POST", "/camera/capture"),
    ]:
        r = client.request(method, f"/api/v1{path}", headers=auth)
        assert r.status_code == 503, path
        assert r.json()["error"]["code"] == "camera_disabled"
