"""Log types and per-type data payload models. Rules: docs/DOMAIN.md."""

from __future__ import annotations

from enum import StrEnum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from growbuddy.domain.phase import Milestone


class LogType(StrEnum):
    GERMINATION = "Germination"
    SPROUT = "Sprout"
    INSPECTION = "Inspection"
    LIGHT_ADJUST = "LightAdjust"
    HUMIDITY_ADJUST = "HumidityAdjust"
    TEMPERATURE_ADJUST = "TemperatureAdjust"
    FEEDING = "Feeding"
    TOPPING = "Topping"
    LST = "LST"
    PRUNING = "Pruning"
    FLOWER_FLIP = "FlowerFlip"
    HARVEST = "Harvest"
    TRIM = "Trim"
    CURE = "Cure"
    TERMINATED_EARLY = "TerminatedEarly"

    @property
    def is_milestone(self) -> bool:
        return self.value in MILESTONE_VALUES


MILESTONE_VALUES = frozenset(m.value for m in Milestone)


class _Data(BaseModel):
    model_config = ConfigDict(extra="forbid")


class EmptyData(_Data):
    pass


class NutrientDose(_Data):
    brand: str
    product: str
    amount: float = Field(ge=0)
    unit: str = "ml/L"


class FeedingData(_Data):
    ph: float | None = Field(default=None, ge=0, le=14)
    ec: float | None = Field(default=None, ge=0)
    volume_l: float | None = Field(default=None, ge=0)
    nutrients: list[NutrientDose] = []


class LightAdjustData(_Data):
    light_height_cm: float = Field(ge=0)


class HumidityAdjustData(_Data):
    humidity_target_pct: float = Field(ge=0, le=100)


class TemperatureAdjustData(_Data):
    temperature_target_c: float


DATA_MODELS: dict[LogType, type[_Data]] = {
    LogType.FEEDING: FeedingData,
    LogType.LIGHT_ADJUST: LightAdjustData,
    LogType.HUMIDITY_ADJUST: HumidityAdjustData,
    LogType.TEMPERATURE_ADJUST: TemperatureAdjustData,
}


def validate_log_data(log_type: LogType, data: dict[str, Any] | None) -> dict[str, Any]:
    """Validate and normalize ``data`` for ``log_type``; raises pydantic.ValidationError."""
    model = DATA_MODELS.get(log_type, EmptyData)
    return model.model_validate(data or {}).model_dump(exclude_none=True)
