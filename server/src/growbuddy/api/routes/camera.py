"""/api/v1 camera routes: /camera/*, /snapshots."""

from __future__ import annotations

from collections.abc import AsyncGenerator, AsyncIterator

from fastapi import APIRouter, Depends, Query, Response
from fastapi.responses import FileResponse, StreamingResponse

from growbuddy.api.deps import CameraDep, DbDep, SettingsDep, require_token
from growbuddy.api.pagination import Limit, split_page
from growbuddy.api.schemas import Snapshot, SnapshotPage
from growbuddy.camera.service import CameraService
from growbuddy.camera.storage import SnapshotKind
from growbuddy.db.queries import snapshots as snapshot_q
from growbuddy.errors import NotFound

router = APIRouter(tags=["camera"], dependencies=[Depends(require_token)])

BOUNDARY = "frame"
_NO_CACHE = {"Cache-Control": "no-store"}
_IMMUTABLE = {"Cache-Control": "private, max-age=31536000, immutable"}


async def mjpeg_parts(camera: CameraService, max_frames: int | None) -> AsyncGenerator[bytes]:
    count = 0
    async for frame in camera.frames(camera.max_viewers):
        header = f"--{BOUNDARY}\r\nContent-Type: image/jpeg\r\nContent-Length: {len(frame.jpeg)}"
        yield f"{header}\r\n\r\n".encode() + frame.jpeg + b"\r\n"
        count += 1
        if max_frames is not None and count >= max_frames:
            return


@router.get("/camera/stream")
async def stream(
    camera: CameraDep, frames: int | None = Query(default=None, ge=1)
) -> StreamingResponse:
    """MJPEG live view. ``frames`` ends the stream after N frames (debugging/tests)."""
    parts = mjpeg_parts(camera, frames)
    first = await anext(parts)  # surface 503s before the 200 headers go out
    return StreamingResponse(
        _prepend(first, parts),
        media_type=f"multipart/x-mixed-replace; boundary={BOUNDARY}",
        headers=_NO_CACHE,
    )


async def _prepend(first: bytes, rest: AsyncGenerator[bytes]) -> AsyncIterator[bytes]:
    # Close the frame generator promptly on disconnect so the viewer slot and lease are freed.
    try:
        yield first
        async for part in rest:
            yield part
    finally:
        await rest.aclose()


@router.get("/camera/frame", response_class=Response)
async def frame(camera: CameraDep) -> Response:
    current = await camera.fresh_frame()
    return Response(current.jpeg, media_type="image/jpeg", headers=_NO_CACHE)


@router.post("/camera/capture", status_code=201)
async def capture(camera: CameraDep) -> Snapshot:
    return Snapshot.of(await camera.capture(SnapshotKind.MANUAL))


@router.get("/snapshots")
async def list_snapshots(
    db: DbDep,
    limit: Limit = 100,
    before: int | None = None,
    kind: SnapshotKind | None = None,
    from_ts: int | None = Query(default=None, alias="from"),
    to_ts: int | None = Query(default=None, alias="to"),
) -> SnapshotPage:
    rows = await snapshot_q.list_snapshots(
        db, limit=limit + 1, before=before, from_ts=from_ts, to_ts=to_ts,
        kind=kind.value if kind else None,
    )  # fmt: skip
    items, next_before = split_page(rows, limit, lambda r: int(r["ts"]))
    return SnapshotPage(items=[Snapshot.of(r) for r in items], next_before=next_before)


@router.get("/snapshots/{snapshot_id}/image", response_class=FileResponse)
async def snapshot_image(
    snapshot_id: int, db: DbDep, settings: SettingsDep, thumb: bool = False
) -> FileResponse:
    row = await snapshot_q.get_snapshot(db, snapshot_id)
    if row is None:
        raise NotFound(f"Snapshot {snapshot_id} not found")
    path = settings.server.data_dir / (row["thumb_path"] if thumb else row["path"])
    return FileResponse(path, media_type="image/jpeg", headers=_IMMUTABLE)
