"""Snapshot file layout on disk and thumbnail generation.

``snapshots/YYYY/MM/DD/<ts>_<kind>.jpg`` (camera JPEG stored as-is) plus
``..._thumb.jpg``, relative to data_dir; metadata row in ``snapshots``.
"""

from __future__ import annotations

import asyncio
import sqlite3
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import StrEnum
from pathlib import Path

import cv2
import numpy as np

from growbuddy.camera.sources import CameraError, encode_jpeg
from growbuddy.db.connection import Database
from growbuddy.db.queries import snapshots as snapshot_q


class SnapshotKind(StrEnum):
    SCHEDULED = "scheduled"
    MANUAL = "manual"


@dataclass(frozen=True, slots=True)
class PreparedSnapshot:
    rel_path: str
    rel_thumb: str
    thumb: bytes
    width: int
    height: int


def relative_paths(ts: int, kind: SnapshotKind) -> tuple[str, str]:
    day = datetime.fromtimestamp(ts / 1000, tz=UTC)
    base = f"snapshots/{day:%Y}/{day:%m}/{day:%d}/{ts}_{kind.value}"
    return f"{base}.jpg", f"{base}_thumb.jpg"


def prepare(
    jpeg: bytes, ts: int, kind: SnapshotKind, thumb_edge: int, quality: int
) -> PreparedSnapshot:
    """Blocking (OpenCV): decode for size and thumbnail."""
    img = cv2.imdecode(np.frombuffer(jpeg, np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        raise CameraError("camera frame is not a decodable JPEG")
    h, w = img.shape[:2]
    scale = min(1.0, thumb_edge / max(h, w))
    thumb = cv2.resize(img, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA)
    rel, rel_thumb = relative_paths(ts, kind)
    return PreparedSnapshot(rel, rel_thumb, encode_jpeg(thumb, quality), w, h)


def _write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".part")
    tmp.write_bytes(data)
    tmp.replace(path)


async def store_snapshot(
    db: Database,
    data_dir: Path,
    jpeg: bytes,
    ts: int,
    kind: SnapshotKind,
    thumb_edge: int,
    quality: int,
) -> sqlite3.Row:
    prepared = await asyncio.to_thread(prepare, jpeg, ts, kind, thumb_edge, quality)
    await asyncio.to_thread(_write, data_dir / prepared.rel_path, jpeg)
    await asyncio.to_thread(_write, data_dir / prepared.rel_thumb, prepared.thumb)
    snapshot_id = await snapshot_q.insert_snapshot(
        db, ts, prepared.rel_path, prepared.rel_thumb, prepared.width, prepared.height,
        len(jpeg), kind.value,
    )  # fmt: skip
    row = await snapshot_q.get_snapshot(db, snapshot_id)
    assert row is not None
    return row
