from __future__ import annotations

import json
from pathlib import Path

import pytest

from growbuddy.db.connection import Database, load_migrations
from growbuddy.db.seed import seed_reference_data


async def test_migrations_applied(db: Database) -> None:
    assert await db.schema_version() == len(load_migrations())
    assert await db.fetchval("PRAGMA journal_mode") == "wal"
    assert await db.fetchval("PRAGMA foreign_keys") == 1


async def test_reopen_does_not_reapply(tmp_path: Path) -> None:
    for _ in range(2):
        database = Database(tmp_path / "x.db")
        await database.open()
        assert await database.schema_version() == len(load_migrations())
        await database.close()


async def test_seed_populates_reference_tables(db: Database) -> None:
    seeded = await seed_reference_data(db)
    assert set(seeded) == {"strains", "nutrient_lines", "feeding_schedules", "phase_ranges"}
    assert await db.fetchval("SELECT COUNT(*) FROM strains") > 400
    assert await db.fetchval("SELECT COUNT(*) FROM nutrient_products") == 90
    assert await db.fetchval("SELECT COUNT(*) FROM feeding_schedule_weeks") == 150
    assert await db.fetchval("SELECT COUNT(*) FROM phase_ranges") == 11
    week1 = await db.fetchone("SELECT * FROM feeding_schedule_weeks WHERE week = 1")
    assert week1 is not None
    assert json.loads(week1["environment"])["light_hours"] == 18
    seedling = await db.fetchone("SELECT * FROM phase_ranges WHERE phase = 'Seedling'")
    assert seedling is not None
    assert (seedling["temp_min"], seedling["vpd_max"], seedling["enforce_limits"]) == (20.0, 0.9, 1)


async def test_seed_is_idempotent_and_preserves_edits(db: Database) -> None:
    await seed_reference_data(db)
    await db.execute("UPDATE phase_ranges SET temp_min = 19 WHERE phase = 'Seedling'")
    assert await seed_reference_data(db) == {}
    assert await db.fetchval("SELECT temp_min FROM phase_ranges WHERE phase = 'Seedling'") == 19


async def test_transaction_rolls_back_on_error(db: Database) -> None:
    with pytest.raises(RuntimeError):
        async with db.transaction() as conn:
            await conn.execute("INSERT INTO settings (key, value) VALUES ('a', '1')")
            raise RuntimeError("boom")
    assert await db.fetchval("SELECT COUNT(*) FROM settings") == 0


async def test_milestone_view_uses_earliest_log(db: Database) -> None:
    async with db.transaction() as conn:
        await conn.execute(
            "INSERT INTO plants (id, nickname, created_ts) VALUES ('p1', 'Plant', 0)"
        )
        for log_id, ts, log_type in [
            ("a", 300, "Sprout"),
            ("b", 100, "Sprout"),
            ("c", 50, "Feeding"),
        ]:
            await conn.execute(
                "INSERT INTO logs (id, ts, type, created_ts, updated_ts) VALUES (?, ?, ?, 0, 0)",
                (log_id, ts, log_type),
            )
            await conn.execute(
                "INSERT INTO log_plants (log_id, plant_id) VALUES (?, 'p1')", (log_id,)
            )
    rows = await db.fetchall("SELECT type, ts FROM plant_milestones WHERE plant_id = 'p1'")
    assert [(r["type"], r["ts"]) for r in rows] == [("Sprout", 100)]
