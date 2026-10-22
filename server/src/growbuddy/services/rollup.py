"""Hourly rollups of readings into readings_hourly.

A watermark (settings ``rollup.watermark``, exclusive end of the last rolled
range) tracks progress. Each run re-does the last rolled hour so readings that
arrived late (queue, clock skew) are still counted.
"""

from __future__ import annotations

import logging

from growbuddy.db.connection import Database
from growbuddy.db.queries import readings as reading_q
from growbuddy.db.queries.readings import HOUR_MS
from growbuddy.db.queries.settings import get_setting, put_setting

log = logging.getLogger(__name__)

WATERMARK_KEY = "rollup.watermark"


async def watermark(db: Database) -> int | None:
    value = await get_setting(db, WATERMARK_KEY)
    return None if value is None else int(value)


async def run_rollup(db: Database, now: int) -> int | None:
    """Roll up all complete hours before ``now``. Returns the new watermark."""
    current_hour = (now // HOUR_MS) * HOUR_MS
    mark = await watermark(db)
    if mark is not None:
        start = max(0, mark - HOUR_MS)
    else:
        oldest = await reading_q.oldest_reading_ts(db)
        if oldest is None:
            return None
        start = (oldest // HOUR_MS) * HOUR_MS
    if start >= current_hour:
        return mark
    await reading_q.rollup_range(db, start, current_hour)
    await put_setting(db, WATERMARK_KEY, current_hour)
    log.debug("Rolled up readings %d..%d", start, current_hour)
    return current_hour
