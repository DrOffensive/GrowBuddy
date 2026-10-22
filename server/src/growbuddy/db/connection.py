"""Connection setup (pragmas) and migration runner (PRAGMA user_version).

Two connections: one writer, serialized by an asyncio lock and used only
through ``transaction()``, and one reader for queries. With WAL, reads see the
last committed state and never block on, or observe, an open write.
"""

from __future__ import annotations

import asyncio
import re
import sqlite3
from collections.abc import AsyncIterator, Iterable, Sequence
from contextlib import asynccontextmanager
from importlib import resources
from pathlib import Path
from typing import Any

import aiosqlite

_PRAGMAS = (
    "PRAGMA journal_mode = WAL",
    "PRAGMA synchronous = NORMAL",
    "PRAGMA foreign_keys = ON",
    "PRAGMA busy_timeout = 5000",
)
_MIGRATION_NAME = re.compile(r"^(\d{4})_[\w-]+\.sql$")

Params = Sequence[Any] | dict[str, Any]


def load_migrations() -> list[tuple[int, str]]:
    """Return ``(version, sql)`` for every bundled migration, in order."""
    found = []
    for entry in resources.files("growbuddy.db.migrations").iterdir():
        match = _MIGRATION_NAME.match(entry.name)
        if match:
            found.append((int(match.group(1)), entry.read_text(encoding="utf-8")))
    found.sort()
    versions = [v for v, _ in found]
    if versions != list(range(1, len(versions) + 1)):
        raise RuntimeError(f"Migrations must be numbered 0001.. without gaps, got {versions}")
    return found


async def _connect(path: Path) -> aiosqlite.Connection:
    # isolation_level=None: no implicit transactions; transaction() issues BEGIN/COMMIT itself.
    conn = await aiosqlite.connect(path, isolation_level=None)
    conn.row_factory = sqlite3.Row
    for pragma in _PRAGMAS:
        await conn.execute(pragma)
    return conn


class Database:
    def __init__(self, path: Path) -> None:
        self.path = path
        self._writer: aiosqlite.Connection | None = None
        self._reader: aiosqlite.Connection | None = None
        self._write_lock = asyncio.Lock()

    async def open(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._writer = await _connect(self.path)
        await self._migrate(self._writer)
        self._reader = await _connect(self.path)

    async def close(self) -> None:
        for conn in (self._reader, self._writer):
            if conn is not None:
                await conn.close()
        self._reader = self._writer = None

    @staticmethod
    async def _migrate(conn: aiosqlite.Connection) -> None:
        async with conn.execute("PRAGMA user_version") as cur:
            row = await cur.fetchone()
        current = int(row[0]) if row else 0
        for version, sql in load_migrations():
            if version <= current:
                continue
            # executescript commits any open transaction first; the script runs atomically.
            await conn.executescript(f"BEGIN;\n{sql}\nPRAGMA user_version = {version};\nCOMMIT;")

    async def schema_version(self) -> int:
        row = await self.fetchone("PRAGMA user_version")
        return int(row[0]) if row else 0

    @asynccontextmanager
    async def transaction(self) -> AsyncIterator[aiosqlite.Connection]:
        """Exclusive write transaction; commits on success, rolls back on error."""
        if self._writer is None:
            raise RuntimeError("Database is not open")
        async with self._write_lock:
            await self._writer.execute("BEGIN IMMEDIATE")
            try:
                yield self._writer
            except BaseException:
                await self._writer.execute("ROLLBACK")
                raise
            await self._writer.execute("COMMIT")

    async def execute(self, sql: str, params: Params = ()) -> int:
        """Run one write statement in its own transaction; returns lastrowid."""
        async with self.transaction() as conn, conn.execute(sql, params) as cur:
            return cur.lastrowid or 0

    async def executemany(self, sql: str, rows: Iterable[Params]) -> None:
        async with self.transaction() as conn:
            await conn.executemany(sql, rows)

    async def fetchall(self, sql: str, params: Params = ()) -> list[sqlite3.Row]:
        async with self._read().execute(sql, params) as cur:
            return list(await cur.fetchall())

    async def fetchone(self, sql: str, params: Params = ()) -> sqlite3.Row | None:
        async with self._read().execute(sql, params) as cur:
            row: sqlite3.Row | None = await cur.fetchone()
            return row

    async def fetchval(self, sql: str, params: Params = ()) -> Any:
        row = await self.fetchone(sql, params)
        return None if row is None else row[0]

    def _read(self) -> aiosqlite.Connection:
        if self._reader is None:
            raise RuntimeError("Database is not open")
        return self._reader
