"""VPD, per-sensor range evaluation and phase-template presets. Rules: docs/DOMAIN.md."""

from __future__ import annotations

import math
from collections.abc import Iterable
from dataclasses import dataclass
from enum import StrEnum


class Metric(StrEnum):
    TEMPERATURE = "temperature"
    HUMIDITY = "humidity"
    VPD = "vpd"


class AlarmLevel(StrEnum):
    NORMAL = "normal"
    WARNING = "warning"
    CRITICAL = "critical"


# Widening applied to the union when building the crit band of a phase preset.
PRESET_CRIT_MARGIN: dict[Metric, float] = {
    Metric.TEMPERATURE: 2.0,
    Metric.HUMIDITY: 5.0,
    Metric.VPD: 0.2,
}
# Physical limits presets are clamped to.
_METRIC_LIMITS: dict[Metric, tuple[float | None, float | None]] = {
    Metric.TEMPERATURE: (None, None),
    Metric.HUMIDITY: (0.0, 100.0),
    Metric.VPD: (0.0, None),
}


def vpd_kpa(temperature_c: float, humidity_pct: float) -> float:
    """Air VPD in kPa (leaf temperature = air temperature)."""
    svp = 0.6108 * math.exp(17.27 * temperature_c / (temperature_c + 237.3))
    return (1.0 - humidity_pct / 100.0) * svp


@dataclass(frozen=True, slots=True)
class Range:
    warn_min: float | None = None
    warn_max: float | None = None
    crit_min: float | None = None
    crit_max: float | None = None

    @property
    def is_empty(self) -> bool:
        return all(v is None for v in (self.warn_min, self.warn_max, self.crit_min, self.crit_max))


def _below(value: float, bound: float | None) -> bool:
    return bound is not None and value < bound


def _above(value: float, bound: float | None) -> bool:
    return bound is not None and value > bound


def evaluate(value: float, rng: Range) -> AlarmLevel:
    if _below(value, rng.crit_min) or _above(value, rng.crit_max):
        return AlarmLevel.CRITICAL
    if _below(value, rng.warn_min) or _above(value, rng.warn_max):
        return AlarmLevel.WARNING
    return AlarmLevel.NORMAL


@dataclass(frozen=True, slots=True)
class PhaseTemplate:
    phase: str
    enforce_limits: bool
    temp_min: float | None = None
    temp_max: float | None = None
    humidity_min: float | None = None
    humidity_max: float | None = None
    vpd_min: float | None = None
    vpd_max: float | None = None

    def bounds(self, metric: Metric) -> tuple[float, float] | None:
        lo, hi = {
            Metric.TEMPERATURE: (self.temp_min, self.temp_max),
            Metric.HUMIDITY: (self.humidity_min, self.humidity_max),
            Metric.VPD: (self.vpd_min, self.vpd_max),
        }[metric]
        return None if lo is None or hi is None else (lo, hi)


def _clamp(metric: Metric, value: float) -> float:
    lo, hi = _METRIC_LIMITS[metric]
    if lo is not None:
        value = max(lo, value)
    if hi is not None:
        value = min(hi, value)
    return round(value, 2)


def preset_from_phases(templates: Iterable[PhaseTemplate]) -> dict[Metric, Range]:
    """Sensor ranges from phase templates: warn = intersection, crit = union + margin.

    Templates without ``enforce_limits`` are ignored. An empty intersection
    collapses warn to the midpoint of the conflicting bounds (0.1.6 rule).
    """
    enforced = [t for t in templates if t.enforce_limits]
    result: dict[Metric, Range] = {}
    for metric in Metric:
        pairs = [b for t in enforced if (b := t.bounds(metric)) is not None]
        if not pairs:
            result[metric] = Range()
            continue
        warn_lo = max(lo for lo, _ in pairs)
        warn_hi = min(hi for _, hi in pairs)
        if warn_lo > warn_hi:
            warn_lo = warn_hi = (warn_lo + warn_hi) / 2
        margin = PRESET_CRIT_MARGIN[metric]
        result[metric] = Range(
            warn_min=_clamp(metric, warn_lo),
            warn_max=_clamp(metric, warn_hi),
            crit_min=_clamp(metric, min(lo for lo, _ in pairs) - margin),
            crit_max=_clamp(metric, max(hi for _, hi in pairs) + margin),
        )
    return result
