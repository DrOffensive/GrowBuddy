"""Pure alert state machine and message formatting (no I/O)."""

from __future__ import annotations

from dataclasses import dataclass

from growbuddy.alerts.messages import Messages, messages
from growbuddy.domain.environment import AlarmLevel

BATTERY_HYSTERESIS_PCT = 5

UNITS = {"temperature": "°C", "humidity": "%", "vpd": "kPa"}


@dataclass(frozen=True, slots=True)
class Pending:
    level: AlarmLevel
    since: int


@dataclass(frozen=True, slots=True)
class Transition:
    level: AlarmLevel  # NORMAL = resolve the active alert
    since: int  # when the new condition started


def step(
    active: AlarmLevel | None,
    pending: Pending | None,
    observed: AlarmLevel,
    now: int,
    debounce_ms: int,
) -> tuple[Transition | None, Pending | None]:
    """Advance one key. A changed level must persist ``debounce_ms`` before it takes effect.

    Returns (transition or None, new pending state).
    """
    current = active or AlarmLevel.NORMAL
    if observed is current:
        return None, None
    if pending is None or pending.level is not observed:
        pending = Pending(observed, now)
    if now - pending.since >= debounce_ms:
        return Transition(observed, pending.since), None
    return None, pending


def delay_ms(
    active: AlarmLevel | None, observed: AlarmLevel, debounce_s: int, warning_debounce_s: int
) -> int:
    """How long ``observed`` must persist: ``warning_debounce_s`` for a new warning (from
    normal), ``debounce_s`` for everything else (critical, back to normal, warning <-> critical)."""
    current = active or AlarmLevel.NORMAL
    if current is AlarmLevel.NORMAL and observed is AlarmLevel.WARNING:
        return warning_debounce_s * 1000
    return debounce_s * 1000


def _fmt(value: float | None, metric: str, msg: Messages) -> str:
    if value is None:
        return "?"
    unit = UNITS.get(metric, "")
    digits = 2 if metric == "vpd" else 1
    return f"{msg.number(value, digits)}{' ' if unit and unit != '%' else ''}{unit}"


def describe(
    sensor_name: str,
    metric: str,
    level: AlarmLevel,
    value: float | None,
    band: tuple[float | None, float | None] | None,
    msg: Messages | None = None,
) -> tuple[str, str]:
    """(title, message) for an alert that is (still) active at ``level``."""
    msg = msg or messages()
    if metric == "sensor_offline":
        return msg("offline.title", sensor=sensor_name), msg("offline.message", sensor=sensor_name)
    if metric == "battery_low":
        return (
            msg("battery.title", sensor=sensor_name),
            msg("battery.message", value=f"{value:.0f}")
            if value is not None
            else msg("battery.message_unknown"),
        )
    if metric == "camera":
        return msg("camera.title"), msg("camera.message")
    lo, hi = band or (None, None)
    direction = "high" if hi is not None and value is not None and value > hi else "low"
    span = (
        msg("span.between", min=_fmt(lo, metric, msg), max=_fmt(hi, metric, msg))
        if lo is not None and hi is not None
        else (
            msg("span.min", min=_fmt(lo, metric, msg))
            if lo is not None
            else msg("span.max", max=_fmt(hi, metric, msg))
        )
    )
    name = msg(f"metric.{metric}")
    title = msg(
        "alert.title", sensor=sensor_name, metric=name, direction=msg(f"direction.{direction}")
    )
    message = msg(
        f"alert.message.{level.value}",
        Metric=name[:1].upper() + name[1:],
        value=_fmt(value, metric, msg),
        span=span,
    )
    return title, message


def describe_resolved(
    sensor_name: str, metric: str, msg: Messages | None = None
) -> tuple[str, str]:
    msg = msg or messages()
    keys = {
        "sensor_offline": "resolved.offline",
        "battery_low": "resolved.battery",
        "camera": "resolved.camera",
    }
    title = msg(
        keys.get(metric, "resolved.metric"), sensor=sensor_name, metric=msg(f"metric.{metric}")
    )
    return title, msg("resolved.message")
