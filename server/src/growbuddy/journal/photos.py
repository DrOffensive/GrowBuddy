"""Phone photo service: decode, downscale, re-encode (drops EXIF incl. GPS), thumbnail,
file layout ``photos/YYYY/MM/<id>.jpg`` + ``<id>_thumb.jpg`` under data_dir."""

from __future__ import annotations

import asyncio
import contextlib
import sqlite3
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

import cv2
import numpy as np

from growbuddy.clock import now_ms
from growbuddy.config import PhotoSettings
from growbuddy.db.connection import Database
from growbuddy.db.queries import photos as photo_q
from growbuddy.errors import AppError, Conflict, Invalid, NotFound

MAX_UPLOAD_BYTES = 25 * 1024 * 1024


@dataclass(frozen=True, slots=True)
class ProcessedImage:
    jpeg: bytes
    thumb: bytes
    width: int
    height: int


def _fit(img: np.ndarray, max_edge: int) -> np.ndarray:
    h, w = img.shape[:2]
    scale = max_edge / max(h, w)
    if scale >= 1:
        return img
    return cv2.resize(img, (round(w * scale), round(h * scale)), interpolation=cv2.INTER_AREA)


def _encode(img: np.ndarray, quality: int) -> bytes:
    ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, quality])
    if not ok:
        raise RuntimeError("JPEG encoding failed")
    return buf.tobytes()


def process_image(data: bytes, settings: PhotoSettings) -> ProcessedImage:
    """Blocking (OpenCV); run in a thread. EXIF orientation is applied by imdecode."""
    img = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        raise Invalid("File is not a decodable image", code="invalid_image")
    full = _fit(img, settings.max_edge_px)
    h, w = full.shape[:2]
    return ProcessedImage(
        jpeg=_encode(full, settings.jpeg_quality),
        thumb=_encode(_fit(full, settings.thumb_edge_px), settings.jpeg_quality),
        width=w,
        height=h,
    )


def _relative_paths(photo_id: str, ts: int) -> tuple[str, str]:
    day = datetime.fromtimestamp(ts / 1000, tz=UTC)
    base = f"photos/{day:%Y}/{day:%m}/{photo_id}"
    return f"{base}.jpg", f"{base}_thumb.jpg"


def _write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".part")
    tmp.write_bytes(data)
    tmp.replace(path)


async def get_photo(db: Database, photo_id: str) -> sqlite3.Row:
    row = await photo_q.get_photo(db, photo_id)
    if row is None:
        raise NotFound(f"Photo {photo_id} not found")
    return row


async def store_photo(
    db: Database,
    data_dir: Path,
    settings: PhotoSettings,
    *,
    photo_id: str,
    data: bytes,
    ts: int,
) -> tuple[sqlite3.Row, bool]:
    """Store an upload; an existing id returns that photo unchanged. Returns (row, created)."""
    existing = await photo_q.get_photo(db, photo_id)
    if existing is not None:
        return existing, False
    if len(data) > MAX_UPLOAD_BYTES:
        raise AppError("Photo exceeds 25 MB", code="too_large", status=413)
    image = await asyncio.to_thread(process_image, data, settings)
    rel, rel_thumb = _relative_paths(photo_id, ts)
    await asyncio.to_thread(_write, data_dir / rel, image.jpeg)
    await asyncio.to_thread(_write, data_dir / rel_thumb, image.thumb)
    await photo_q.insert_photo(
        db, photo_id, ts, rel, rel_thumb, image.width, image.height, len(image.jpeg), now_ms()
    )
    return await get_photo(db, photo_id), True


async def delete_photo(db: Database, data_dir: Path, photo_id: str) -> None:
    row = await get_photo(db, photo_id)
    if await photo_q.ids_in_use(db, [photo_id]):
        raise Conflict("Photo is used by a log entry or plant", code="photo_in_use")
    await photo_q.delete_photo(db, photo_id)
    for rel in (row["path"], row["thumb_path"]):
        with contextlib.suppress(FileNotFoundError):
            (data_dir / rel).unlink()
