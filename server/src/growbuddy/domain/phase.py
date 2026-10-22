"""Phase and age derivation from milestones. Rules: docs/DOMAIN.md.

Milestones map a milestone type to its timestamp (epoch ms UTC), i.e. the
earliest log entry of that type for the plant (DB view ``plant_milestones``).
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from enum import StrEnum

DAY_MS = 86_400_000
CURE_DAYS = 28


class Milestone(StrEnum):
    GERMINATION = "Germination"
    SPROUT = "Sprout"
    FLOWER_FLIP = "FlowerFlip"
    HARVEST = "Harvest"
    TRIM = "Trim"
    TERMINATED_EARLY = "TerminatedEarly"


class Phase(StrEnum):
    PRE_GERMINATION = "PreGermination"
    GERMINATING = "Germinating"
    SEEDLING = "Seedling"
    EARLY_VEGETATION = "EarlyVegetation"
    LATE_VEGETATION = "LateVegetation"
    EARLY_FLOWER = "EarlyFlower"
    MID_FLOWER = "MidFlower"
    LATE_FLOWER = "LateFlower"
    DRYING = "Drying"
    CURING = "Curing"
    COMPLETE = "Complete"
    TERMINATED_EARLY = "TerminatedEarly"


@dataclass(frozen=True, slots=True)
class PlantAge:
    week: int
    day: int

    def __str__(self) -> str:
        return f"Week {self.week}, Day {self.day}"


Milestones = Mapping[Milestone, int]


def _days(start_ms: int, end_ms: int) -> float:
    return (end_ms - start_ms) / DAY_MS


def _reached(milestones: Milestones, at_ms: int) -> dict[Milestone, int]:
    return {m: ts for m, ts in milestones.items() if ts <= at_ms}


def derive_phase(milestones: Milestones, at_ms: int) -> Phase:
    """Phase at ``at_ms``. Only milestones at or before ``at_ms`` count; the
    look-ahead rules (flower thirds, compressed veg) use all milestones."""
    reached = _reached(milestones, at_ms)

    if Milestone.TERMINATED_EARLY in reached:
        return Phase.TERMINATED_EARLY

    if (trim := reached.get(Milestone.TRIM)) is not None:
        return Phase.CURING if _days(trim, at_ms) < CURE_DAYS else Phase.COMPLETE

    if Milestone.HARVEST in reached:
        return Phase.DRYING

    if (flip := reached.get(Milestone.FLOWER_FLIP)) is not None:
        elapsed = _days(flip, at_ms)
        harvest = milestones.get(Milestone.HARVEST)
        if harvest is not None and harvest > flip:
            total = _days(flip, harvest)
            if elapsed < total / 3:
                return Phase.EARLY_FLOWER
            if elapsed < total * 2 / 3:
                return Phase.MID_FLOWER
            return Phase.LATE_FLOWER
        if elapsed <= 14:
            return Phase.EARLY_FLOWER
        if elapsed <= 42:
            return Phase.MID_FLOWER
        return Phase.LATE_FLOWER

    if (sprout := reached.get(Milestone.SPROUT)) is not None:
        elapsed = _days(sprout, at_ms)
        flip_any = milestones.get(Milestone.FLOWER_FLIP)
        if flip_any is not None and flip_any > sprout:
            veg_days = _days(sprout, flip_any)
            if veg_days < 28:
                return Phase.SEEDLING if elapsed < veg_days / 2 else Phase.EARLY_VEGETATION
        if elapsed <= 14:
            return Phase.SEEDLING
        if elapsed <= 28:
            return Phase.EARLY_VEGETATION
        return Phase.LATE_VEGETATION

    if Milestone.GERMINATION in reached:
        return Phase.GERMINATING

    return Phase.PRE_GERMINATION


def _age_from(anchor_ms: int, at_ms: int) -> PlantAge:
    days = max(0, (at_ms - anchor_ms) // DAY_MS)
    return PlantAge(week=days // 7 + 1, day=days % 7 + 1)


def derive_age(milestones: Milestones, at_ms: int) -> PlantAge | None:
    """Age as "Week W, Day D" from the latest applicable anchor; None if not applicable."""
    reached = _reached(milestones, at_ms)

    if Milestone.TERMINATED_EARLY in reached:
        return None
    if (trim := reached.get(Milestone.TRIM)) is not None:
        return None if _days(trim, at_ms) >= CURE_DAYS else _age_from(trim, at_ms)
    for anchor in (Milestone.HARVEST, Milestone.SPROUT, Milestone.GERMINATION):
        if (ts := reached.get(anchor)) is not None:
            return _age_from(ts, at_ms)
    return None


# Plants in these phases no longer drive a sensor's automatic ranges.
INACTIVE_PHASES = frozenset({Phase.TERMINATED_EARLY, Phase.COMPLETE})


def plant_start(milestones: Milestones, created_ms: int) -> int:
    """When a plant's grow began: its first milestone, else when it was created."""
    return min(milestones.values(), default=created_ms)
