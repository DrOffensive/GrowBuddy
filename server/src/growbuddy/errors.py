"""Client-visible failures raised anywhere below the API layer.

``api/errors.py`` renders them as {"error": {"code", "message"}} with ``status``.
"""

from __future__ import annotations


class AppError(Exception):
    status = 400
    code = "bad_request"

    def __init__(self, message: str, code: str | None = None, status: int | None = None) -> None:
        super().__init__(message)
        self.message = message
        if code is not None:
            self.code = code
        if status is not None:
            self.status = status


class NotFound(AppError):
    status = 404
    code = "not_found"


class Conflict(AppError):
    status = 409
    code = "conflict"


class Invalid(AppError):
    status = 422
    code = "invalid"


class Unauthorized(AppError):
    status = 401
    code = "unauthorized"
