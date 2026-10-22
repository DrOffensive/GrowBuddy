"""Effective alert settings: config defaults overlaid with runtime edits from the DB."""

from __future__ import annotations

from typing import Any

from growbuddy.config import AlertSettings
from growbuddy.db.connection import Database
from growbuddy.db.queries.settings import get_setting, put_setting

SETTINGS_KEY = "alerts.settings"
EDITABLE = frozenset(AlertSettings.model_fields) - {"evaluate_every_s"}


async def load_alert_settings(db: Database, defaults: AlertSettings) -> AlertSettings:
    stored: dict[str, Any] = await get_setting(db, SETTINGS_KEY, {})
    overrides = {k: v for k, v in stored.items() if k in EDITABLE}
    return AlertSettings.model_validate({**defaults.model_dump(), **overrides})


async def save_alert_settings(db: Database, values: AlertSettings) -> None:
    await put_setting(db, SETTINGS_KEY, values.model_dump(include=set(EDITABLE)))
