"""Nightly consistent DB snapshot via VACUUM INTO into data_dir/backups.

Written as ``growbuddy-YYYYMMDD.db.part``, verified with ``PRAGMA quick_check``
and then renamed, so the laptop's rsync pull never sees a partial file. Only
the newest ``backup.keep_local`` snapshots are kept on the Pi.
"""

from __future__ import annotations

import asyncio
import contextlib
import sqlite3
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from growbuddy.config import BackupSettings
from growbuddy.db.connection import Database
from growbuddy.services.events import Level, record_event

PREFIX = "growbuddy-"
SUFFIX = ".db"


@dataclass(frozen=True, slots=True)
class BackupFile:
    name: str
    ts: int  # modification time, epoch ms
    size_bytes: int


def backup_dir(data_dir: Path) -> Path:
    return data_dir / "backups"


def list_backups(data_dir: Path) -> list[BackupFile]:
    """Completed snapshots, newest first."""
    directory = backup_dir(data_dir)
    if not directory.is_dir():
        return []
    files = []
    for path in directory.glob(f"{PREFIX}*{SUFFIX}"):
        stat = path.stat()
        files.append(BackupFile(path.name, int(stat.st_mtime * 1000), stat.st_size))
    return sorted(files, key=lambda f: f.name, reverse=True)


def _vacuum_into(db_path: Path, target: Path) -> None:
    """Blocking: consistent copy on a short-lived connection, then verify and publish."""
    tmp = target.with_name(target.name + ".part")
    tmp.unlink(missing_ok=True)
    source = sqlite3.connect(db_path)
    try:
        source.execute("VACUUM INTO ?", (str(tmp),))
    finally:
        source.close()
    check = sqlite3.connect(f"file:{tmp}?mode=ro", uri=True)
    try:
        result = check.execute("PRAGMA quick_check").fetchone()[0]
    finally:
        check.close()
    if result != "ok":
        tmp.unlink(missing_ok=True)
        raise RuntimeError(f"snapshot failed quick_check: {result}")
    tmp.replace(target)


def _prune(data_dir: Path, keep: int) -> list[str]:
    removed = []
    for old in list_backups(data_dir)[keep:]:
        with contextlib.suppress(FileNotFoundError):
            (backup_dir(data_dir) / old.name).unlink()
        removed.append(old.name)
    return removed


async def run_snapshot(
    db: Database, data_dir: Path, settings: BackupSettings, now: datetime | None = None
) -> BackupFile:
    """Write today's snapshot (local date; replaces an existing one for the same day)."""
    day = (now or datetime.now().astimezone()).strftime("%Y%m%d")
    target = backup_dir(data_dir) / f"{PREFIX}{day}{SUFFIX}"
    target.parent.mkdir(parents=True, exist_ok=True)
    await asyncio.to_thread(_vacuum_into, db.path, target)
    removed = await asyncio.to_thread(_prune, data_dir, settings.keep_local)
    stat = target.stat()
    result = BackupFile(target.name, int(stat.st_mtime * 1000), stat.st_size)
    await record_event(
        db, Level.INFO, "backup", "snapshot_done",
        f"DB snapshot {result.name} written ({result.size_bytes // 1024} KiB)",
        {"name": result.name, "size_bytes": result.size_bytes, "pruned": removed},
    )  # fmt: skip
    return result
