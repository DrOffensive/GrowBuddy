"""/api/v1 photo routes (multipart upload, JPEG download)."""

from __future__ import annotations

from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, File, Form, Response, UploadFile
from fastapi.responses import FileResponse

from growbuddy.api.deps import DbDep, SettingsDep, require_token
from growbuddy.api.schemas import Photo
from growbuddy.clock import now_ms
from growbuddy.journal import photos as svc

router = APIRouter(prefix="/photos", tags=["photos"], dependencies=[Depends(require_token)])

# Content for a photo id never changes, so clients may cache forever.
_IMMUTABLE = {"Cache-Control": "private, max-age=31536000, immutable"}


@router.post("", status_code=201)
async def upload_photo(
    db: DbDep,
    settings: SettingsDep,
    response: Response,
    id: Annotated[UUID, Form()],
    file: Annotated[UploadFile, File()],
    taken_ts: Annotated[int | None, Form(ge=0)] = None,
) -> Photo:
    data = await file.read(svc.MAX_UPLOAD_BYTES + 1)
    row, created = await svc.store_photo(
        db,
        settings.server.data_dir,
        settings.photos,
        photo_id=str(id),
        data=data,
        ts=taken_ts if taken_ts is not None else now_ms(),
    )
    if not created:
        response.status_code = 200
    return Photo.of(row)


@router.get("/{photo_id}/meta")
async def photo_meta(photo_id: str, db: DbDep) -> Photo:
    return Photo.of(await svc.get_photo(db, photo_id))


@router.get("/{photo_id}", response_class=FileResponse)
async def get_photo(
    photo_id: str, db: DbDep, settings: SettingsDep, thumb: bool = False
) -> FileResponse:
    row = await svc.get_photo(db, photo_id)
    path = settings.server.data_dir / (row["thumb_path"] if thumb else row["path"])
    return FileResponse(path, media_type="image/jpeg", headers=_IMMUTABLE)


@router.delete("/{photo_id}", status_code=204)
async def delete_photo(photo_id: str, db: DbDep, settings: SettingsDep) -> Response:
    await svc.delete_photo(db, settings.server.data_dir, photo_id)
    return Response(status_code=204)
