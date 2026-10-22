"""Effective alarm ranges per sensor (docs/DOMAIN.md, "Sensors, locations and ranges").

``manual``: the stored ``sensor_ranges``. ``auto``: the phase-template preset for the
current phase of the oldest active plant located at the sensor.
"""

from __future__ import annotations

import sqlite3
from collections.abc import Sequence
from dataclasses import dataclass
from enum import StrEnum

from growbuddy.db.connection import Database
from growbuddy.db.queries import sensors as sensor_q
from growbuddy.domain.environment import Metric, Range, preset_from_phases
from growbuddy.domain.phase import INACTIVE_PHASES, Phase, plant_start
from growbuddy.journal.plants import PlantView, list_plants


class RangeMode(StrEnum):
    AUTO = "auto"
    MANUAL = "manual"


@dataclass(frozen=True, slots=True)
class RangeSource:
    plant_id: str
    nickname: str
    phase: Phase


@dataclass(frozen=True, slots=True)
class EffectiveRanges:
    mode: RangeMode
    ranges: dict[Metric, Range]
    source: RangeSource | None  # auto mode: the plant whose phase drives the ranges


def driving_plant(plants: Sequence[PlantView]) -> PlantView | None:
    """Oldest active plant: earliest start (first milestone, else creation); ties by id."""
    active = [p for p in plants if not p.row["archived"] and p.phase not in INACTIVE_PHASES]
    if not active:
        return None
    return min(active, key=lambda p: (plant_start(p.milestones, p.row["created_ts"]), p.row["id"]))


async def effective_ranges(
    db: Database,
    sensor: sqlite3.Row,
    now: int,
    plants: Sequence[PlantView] | None = None,
) -> EffectiveRanges:
    """``plants``: the plants located at this sensor, if the caller already has them."""
    if RangeMode(sensor["range_mode"]) is RangeMode.MANUAL:
        return EffectiveRanges(RangeMode.MANUAL, await sensor_q.get_ranges(db, sensor["id"]), None)
    located = plants if plants is not None else await list_plants(db, now, sensor_id=sensor["id"])
    plant = driving_plant(located)
    if plant is None:
        return EffectiveRanges(RangeMode.AUTO, {m: Range() for m in Metric}, None)
    template = (await sensor_q.phase_templates(db)).get(plant.phase.value)
    ranges = preset_from_phases([template]) if template else {m: Range() for m in Metric}
    source = RangeSource(plant.row["id"], plant.row["nickname"], plant.phase)
    return EffectiveRanges(RangeMode.AUTO, ranges, source)
