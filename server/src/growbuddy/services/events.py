"""Event log writer used by all services."""

from __future__ import annotations

import json
import logging
from enum import StrEnum
from typing import Any

from growbuddy.clock import now_ms
from growbuddy.db.connection import Database

log = logging.getLogger(__name__)


class Level(StrEnum):
    DEBUG = "debug"
    INFO = "info"
    WARNING = "warning"
    ERROR = "error"


_LOG_LEVELS = {
    Level.DEBUG: logging.DEBUG,
    Level.INFO: logging.INFO,
    Level.WARNING: logging.WARNING,
    Level.ERROR: logging.ERROR,
}


async def record_event(
    db: Database,
    level: Level,
    source: str,
    code: str,
    message: str,
    data: dict[str, Any] | None = None,
) -> None:
    """Write to the ``events`` table and the process log. Never raises."""
    log.log(_LOG_LEVELS[level], "[%s] %s: %s", source, code, message)
    try:
        await db.execute(
            "INSERT INTO events (ts, level, source, code, message, data) VALUES (?, ?, ?, ?, ?, ?)",
            (
                now_ms(),
                level.value,
                source,
                code,
                message,
                json.dumps(data) if data else None,
            ),
        )
    except Exception:
        log.exception("Failed to record event %s/%s", source, code)
