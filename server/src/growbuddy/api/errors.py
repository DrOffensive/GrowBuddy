"""Uniform error body for every failure: {"error": {"code", "message"}} (docs/API.md)."""

from __future__ import annotations

from http import HTTPStatus

from fastapi import FastAPI, Request
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from growbuddy.errors import AppError


def _body(code: str, message: str, details: object = None) -> dict[str, object]:
    error: dict[str, object] = {"code": code, "message": message}
    if details is not None:
        error["details"] = details
    return {"error": error}


def _code_for(status: int) -> str:
    try:
        return HTTPStatus(status).phrase.lower().replace(" ", "_")
    except ValueError:
        return "error"


def install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(AppError)
    async def _app_error(_: Request, exc: AppError) -> JSONResponse:
        return JSONResponse(_body(exc.code, exc.message), status_code=exc.status)

    @app.exception_handler(StarletteHTTPException)
    async def _http_error(_: Request, exc: StarletteHTTPException) -> JSONResponse:
        return JSONResponse(
            _body(_code_for(exc.status_code), str(exc.detail)),
            status_code=exc.status_code,
            headers=exc.headers,
        )

    @app.exception_handler(RequestValidationError)
    async def _validation_error(_: Request, exc: RequestValidationError) -> JSONResponse:
        return JSONResponse(
            _body("validation_error", "Request validation failed", jsonable_encoder(exc.errors())),
            status_code=422,
        )
