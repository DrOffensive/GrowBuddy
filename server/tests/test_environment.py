from __future__ import annotations

import pytest

from growbuddy.domain.environment import (
    AlarmLevel,
    Metric,
    PhaseTemplate,
    Range,
    evaluate,
    preset_from_phases,
    vpd_kpa,
)


def test_vpd_matches_legacy_formula() -> None:
    assert vpd_kpa(25.0, 60.0) == pytest.approx(1.2671, abs=1e-4)
    assert vpd_kpa(20.0, 100.0) == pytest.approx(0.0)


RNG = Range(warn_min=20, warn_max=26, crit_min=18, crit_max=28)


@pytest.mark.parametrize(
    ("value", "level"),
    [
        (23, AlarmLevel.NORMAL),
        (20, AlarmLevel.NORMAL),
        (19.9, AlarmLevel.WARNING),
        (27, AlarmLevel.WARNING),
        (17.9, AlarmLevel.CRITICAL),
        (28.1, AlarmLevel.CRITICAL),
    ],
)
def test_evaluate(value: float, level: AlarmLevel) -> None:
    assert evaluate(value, RNG) is level


def test_missing_bounds_are_not_checked() -> None:
    assert evaluate(99, Range(warn_min=20)) is AlarmLevel.NORMAL
    assert evaluate(99, Range()) is AlarmLevel.NORMAL
    assert evaluate(-5, Range(crit_min=0)) is AlarmLevel.CRITICAL
    assert Range().is_empty and not RNG.is_empty


SEEDLING = PhaseTemplate("Seedling", True, 20, 25, 65, 70, 0.6, 0.9)
EARLY_VEG = PhaseTemplate("EarlyVegetation", True, 22, 28, 60, 70, 0.8, 1.1)
LATE_FLOWER = PhaseTemplate("LateFlower", True, 18, 24, 35, 45, 1.3, 1.6)
CURING = PhaseTemplate("Curing", False, vpd_min=0.8, vpd_max=1.1)


def test_preset_single_phase_has_margin() -> None:
    preset = preset_from_phases([SEEDLING])
    assert preset[Metric.TEMPERATURE] == Range(20, 25, 18, 27)
    assert preset[Metric.HUMIDITY] == Range(65, 70, 60, 75)
    assert preset[Metric.VPD] == Range(0.6, 0.9, 0.4, 1.1)


def test_preset_multiple_phases_intersection_and_union() -> None:
    preset = preset_from_phases([SEEDLING, EARLY_VEG])
    assert preset[Metric.TEMPERATURE] == Range(22, 25, 18, 30)
    assert preset[Metric.HUMIDITY] == Range(65, 70, 55, 75)


def test_preset_empty_intersection_collapses_to_midpoint() -> None:
    preset = preset_from_phases([SEEDLING, LATE_FLOWER])
    hum = preset[Metric.HUMIDITY]  # (65..70) vs (35..45): lo=65 > hi=45 -> 55
    assert (hum.warn_min, hum.warn_max) == (55, 55)
    assert (hum.crit_min, hum.crit_max) == (30, 75)


def test_preset_ignores_unenforced_and_clamps() -> None:
    assert preset_from_phases([CURING])[Metric.VPD].is_empty
    wet = PhaseTemplate("X", True, humidity_min=90, humidity_max=98, vpd_min=0.1, vpd_max=0.3)
    preset = preset_from_phases([wet])
    assert preset[Metric.HUMIDITY].crit_max == 100
    assert preset[Metric.VPD].crit_min == 0
    assert preset[Metric.TEMPERATURE].is_empty
