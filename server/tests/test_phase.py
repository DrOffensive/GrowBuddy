from __future__ import annotations

import pytest

from growbuddy.domain.phase import DAY_MS, Milestone, Phase, PlantAge, derive_age, derive_phase

T0 = 1_750_000_000_000  # arbitrary epoch ms


def d(days: float) -> int:
    return T0 + int(days * DAY_MS)


M = Milestone


def test_no_milestones() -> None:
    assert derive_phase({}, T0) is Phase.PRE_GERMINATION
    assert derive_age({}, T0) is None


def test_germinating() -> None:
    ms = {M.GERMINATION: d(0)}
    assert derive_phase(ms, d(2)) is Phase.GERMINATING
    assert derive_age(ms, d(2)) == PlantAge(1, 3)


@pytest.mark.parametrize(
    ("days", "phase"),
    [
        (0, Phase.SEEDLING),
        (14, Phase.SEEDLING),
        (14.5, Phase.EARLY_VEGETATION),
        (28, Phase.EARLY_VEGETATION),
        (29, Phase.LATE_VEGETATION),
    ],
)
def test_veg_timeline(days: float, phase: Phase) -> None:
    ms = {M.GERMINATION: d(-3), M.SPROUT: d(0)}
    assert derive_phase(ms, d(days)) is phase


def test_age_counts_from_sprout_through_flower() -> None:
    ms = {M.SPROUT: d(0), M.FLOWER_FLIP: d(30)}
    assert derive_age(ms, d(45)) == PlantAge(week=7, day=4)
    assert str(derive_age(ms, d(0))) == "Week 1, Day 1"


def test_compressed_veg_when_flip_within_28_days() -> None:
    ms = {M.SPROUT: d(0), M.FLOWER_FLIP: d(20)}
    assert derive_phase(ms, d(9)) is Phase.SEEDLING
    assert derive_phase(ms, d(10)) is Phase.EARLY_VEGETATION
    assert derive_phase(ms, d(19)) is Phase.EARLY_VEGETATION


@pytest.mark.parametrize(
    ("days", "phase"),
    [
        (14, Phase.EARLY_FLOWER),
        (15, Phase.MID_FLOWER),
        (42, Phase.MID_FLOWER),
        (43, Phase.LATE_FLOWER),
    ],
)
def test_flower_without_harvest(days: float, phase: Phase) -> None:
    ms = {M.SPROUT: d(-40), M.FLOWER_FLIP: d(0)}
    assert derive_phase(ms, d(days)) is phase


def test_flower_thirds_when_evaluated_before_known_harvest() -> None:
    ms = {M.SPROUT: d(-40), M.FLOWER_FLIP: d(0), M.HARVEST: d(60)}
    assert derive_phase(ms, d(19)) is Phase.EARLY_FLOWER
    assert derive_phase(ms, d(20)) is Phase.MID_FLOWER
    assert derive_phase(ms, d(40)) is Phase.LATE_FLOWER
    assert derive_phase(ms, d(60)) is Phase.DRYING


def test_milestones_after_evaluation_time_are_ignored() -> None:
    ms = {M.GERMINATION: d(0), M.SPROUT: d(3)}
    assert derive_phase(ms, d(1)) is Phase.GERMINATING


def test_drying_age_counts_from_harvest() -> None:
    ms = {M.SPROUT: d(-100), M.HARVEST: d(0)}
    assert derive_phase(ms, d(5)) is Phase.DRYING
    assert derive_age(ms, d(5)) == PlantAge(1, 6)


def test_curing_then_complete() -> None:
    ms = {M.SPROUT: d(-110), M.HARVEST: d(-10), M.TRIM: d(0)}
    assert derive_phase(ms, d(27.9)) is Phase.CURING
    assert derive_age(ms, d(27.9)) == PlantAge(4, 7)
    assert derive_phase(ms, d(28)) is Phase.COMPLETE
    assert derive_age(ms, d(28)) is None


def test_terminated_early_wins() -> None:
    ms = {M.SPROUT: d(0), M.FLOWER_FLIP: d(30), M.TERMINATED_EARLY: d(35)}
    assert derive_phase(ms, d(36)) is Phase.TERMINATED_EARLY
    assert derive_age(ms, d(36)) is None
    assert derive_phase(ms, d(34)) is Phase.EARLY_FLOWER
