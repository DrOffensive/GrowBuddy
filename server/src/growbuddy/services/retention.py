"""Daily retention pruning (runs after rollup).

Raw readings are only deleted for hours already rolled up. Snapshot and photo
files are removed together with their rows. Journal data (plants, logs, used
photos) and hourly rollups are kept forever.
"""

from __future__ import annotations

import asyncio
import contextlib
import sqlite3
from collections.abc import Awaitable, Callable
from dataclasses import asdict, dataclass
from pathlib import Path

from growbuddy.config import RetentionSettings
from growbuddy.db.connection import Database
from growbuddy.db.queries import maintenance as maint_q
from growbuddy.db.queries.readings import HOUR_MS
from growbuddy.services.events import Level, record_event
from growbuddy.services.rollup import run_rollup

DAY_MS = 86_400_000
ORPHAN_PHOTO_GRACE_MS = 7 * DAY_MS
_BATCH = 500


@dataclass(frozen=True, slots=True)
class RetentionResult:
    readings: int
    snapshots: int
    events: int
    orphan_photos: int


def _unlink(data_dir: Path, rel_paths: list[str]) -> None:
    for rel in rel_paths:
        with contextlib.suppress(FileNotFoundError):
            (data_dir / rel).unlink()


async def _purge_files(
    db: Database,
    data_dir: Path,
    table: str,
    rows_fn: Callable[[int], Awaitable[list[sqlite3.Row]]],
) -> int:
    """Delete rows returned in batches by ``rows_fn(limit)`` plus their files."""
    total = 0
    while True:
        rows = await rows_fn(_BATCH)
        if not rows:
            return total
        await maint_q.delete_rows(db, table, [r["id"] for r in rows])
        await asyncio.to_thread(
            _unlink, data_dir, [p for r in rows for p in (r["path"], r["thumb_path"])]
        )
        total += len(rows)


async def run_retention(
    db: Database, data_dir: Path, settings: RetentionSettings, now: int
) -> RetentionResult:
    rolled_until = await run_rollup(db, now) or 0
    readings_cutoff = min(
        ((now - settings.readings_days * DAY_MS) // HOUR_MS) * HOUR_MS, rolled_until
    )
    snapshot_cutoff = now - settings.snapshots_days * DAY_MS
    orphan_cutoff = now - ORPHAN_PHOTO_GRACE_MS

    result = RetentionResult(
        readings=await maint_q.delete_readings_before(db, readings_cutoff),
        snapshots=await _purge_files(
            db, data_dir, "snapshots", lambda n: maint_q.snapshots_before(db, snapshot_cutoff, n)
        ),
        events=await maint_q.delete_events_before(db, now - settings.events_days * DAY_MS),
        orphan_photos=await _purge_files(
            db, data_dir, "photos", lambda n: maint_q.orphan_photos(db, orphan_cutoff, n)
        ),
    )
    await maint_q.optimize(db)
    removed = ", ".join(f"{v} {k.replace('_', ' ')}" for k, v in asdict(result).items() if v)
    await record_event(
        db, Level.INFO, "system", "retention_done",
        f"Retention removed {removed}" if removed else "Retention: nothing to remove",
        asdict(result),
    )  # fmt: skip
    return result
