"""Key/value JSON settings (runtime state and runtime-editable settings)."""

from __future__ import annotations

import json
from typing import Any

from growbuddy.db.connection import Database


async def get_setting(db: Database, key: str, default: Any = None) -> Any:
    value = await db.fetchval("SELECT value FROM settings WHERE key = ?", (key,))
    return default if value is None else json.loads(value)


async def put_setting(db: Database, key: str, value: Any) -> None:
    await db.execute(
        """INSERT INTO settings (key, value) VALUES (?, ?)
           ON CONFLICT (key) DO UPDATE SET value = excluded.value""",
        (key, json.dumps(value)),
    )
