from __future__ import annotations

import pytest
from pydantic import ValidationError

from growbuddy.domain.logs import LogType, validate_log_data


def test_feeding_data_normalized() -> None:
    data = validate_log_data(
        LogType.FEEDING,
        {
            "ph": 6.2,
            "ec": 1.4,
            "volume_l": 2,
            "nutrients": [{"brand": "Biobizz", "product": "BioGrow", "amount": 2}],
        },
    )
    assert data == {
        "ph": 6.2,
        "ec": 1.4,
        "volume_l": 2.0,
        "nutrients": [{"brand": "Biobizz", "product": "BioGrow", "amount": 2.0, "unit": "ml/L"}],
    }


def test_partial_feeding_drops_unset_fields() -> None:
    assert validate_log_data(LogType.FEEDING, {"ph": 6.0}) == {"ph": 6.0, "nutrients": []}


@pytest.mark.parametrize(
    ("log_type", "data"),
    [
        (LogType.FEEDING, {"ph": 15}),
        (LogType.FEEDING, {"unknown": 1}),
        (LogType.LIGHT_ADJUST, {}),
        (LogType.HUMIDITY_ADJUST, {"humidity_target_pct": 120}),
        (LogType.TOPPING, {"anything": True}),
    ],
)
def test_invalid_data_rejected(log_type: LogType, data: dict[str, object]) -> None:
    with pytest.raises(ValidationError):
        validate_log_data(log_type, data)


def test_types_without_data_accept_none() -> None:
    assert validate_log_data(LogType.HARVEST, None) == {}


def test_milestone_flag() -> None:
    assert LogType.FLOWER_FLIP.is_milestone
    assert LogType.TERMINATED_EARLY.is_milestone
    assert not LogType.CURE.is_milestone
    assert not LogType.FEEDING.is_milestone
