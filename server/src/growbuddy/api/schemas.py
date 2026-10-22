"""Pydantic request/response models matching docs/API.md."""

from __future__ import annotations

import json
import sqlite3
from typing import Annotated, Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from growbuddy.db.queries.logs import LogRecord
from growbuddy.domain.environment import AlarmLevel, Metric, Range
from growbuddy.domain.logs import LogType
from growbuddy.journal.plants import PlantView
from growbuddy.overview import SensorOverview
from growbuddy.sensors.processor import SeenDevice, normalize_mac
from growbuddy.services.ranges import RangeMode, RangeSource


class _Model(BaseModel):
    model_config = ConfigDict(extra="forbid")


# ---------------------------------------------------------------- sensors


class LiveValues(_Model):
    temperature_c: float | None
    humidity_pct: float | None
    battery_pct: int | None
    voltage_v: float | None


class DiscoveredSensor(_Model):
    mac: str
    name: str | None
    rssi: int | None
    last_seen: int
    encrypted: bool
    values: LiveValues | None

    @classmethod
    def of(cls, d: SeenDevice) -> DiscoveredSensor:
        r = d.reading
        values = (
            LiveValues(
                temperature_c=r.temperature_c,
                humidity_pct=r.humidity_pct,
                battery_pct=r.battery_pct,
                voltage_v=r.voltage_v,
            )
            if r
            else None
        )
        return cls(
            mac=d.mac,
            name=d.name,
            rssi=d.rssi,
            last_seen=d.last_seen_ms,
            encrypted=d.encrypted,
            values=values,
        )


class Sensor(_Model):
    id: int
    mac: str
    name: str
    status: str
    alerts_enabled: bool
    adopted_ts: int
    last_seen: int | None
    battery_pct: int | None
    rssi: int | None
    range_mode: RangeMode

    @classmethod
    def of(cls, row: sqlite3.Row) -> Sensor:
        return cls(
            id=row["id"],
            mac=row["mac"],
            name=row["name"],
            status=row["status"],
            alerts_enabled=bool(row["alerts_enabled"]),
            adopted_ts=row["adopted_ts"],
            last_seen=row["last_seen"],
            battery_pct=row["last_battery"],
            rssi=row["last_rssi"],
            range_mode=RangeMode(row["range_mode"]),
        )


class RangeSourceModel(_Model):
    """Auto mode: the plant whose phase drives the ranges."""

    plant_id: str
    nickname: str
    phase: str

    @classmethod
    def of(cls, source: RangeSource | None) -> RangeSourceModel | None:
        if source is None:
            return None
        return cls(plant_id=source.plant_id, nickname=source.nickname, phase=source.phase.value)


SensorName = Field(min_length=1, max_length=60)


class SensorAdopt(_Model):
    mac: str
    name: str = SensorName

    @field_validator("mac")
    @classmethod
    def _mac(cls, value: str) -> str:
        return normalize_mac(value)


class SensorPatch(_Model):
    name: str | None = Field(default=None, min_length=1, max_length=60)
    alerts_enabled: bool | None = None
    range_mode: RangeMode | None = None


class MetricRange(_Model):
    warn_min: float | None = None
    warn_max: float | None = None
    crit_min: float | None = None
    crit_max: float | None = None

    @model_validator(mode="after")
    def _ordered(self) -> MetricRange:
        def check(lo: float | None, hi: float | None, what: str) -> None:
            if lo is not None and hi is not None and lo > hi:
                raise ValueError(f"{what}_min must be <= {what}_max")

        check(self.warn_min, self.warn_max, "warn")
        check(self.crit_min, self.crit_max, "crit")
        # The crit band must contain the warn band, or a warning could never precede critical.
        check(self.crit_min, self.warn_min, "crit_min/warn")
        check(self.warn_max, self.crit_max, "warn_max/crit")
        return self

    def to_domain(self) -> Range:
        return Range(self.warn_min, self.warn_max, self.crit_min, self.crit_max)

    @classmethod
    def of(cls, r: Range) -> MetricRange:
        return cls(
            warn_min=r.warn_min, warn_max=r.warn_max, crit_min=r.crit_min, crit_max=r.crit_max
        )


class SensorRanges(_Model):
    temperature: MetricRange = MetricRange()
    humidity: MetricRange = MetricRange()
    vpd: MetricRange = MetricRange()

    def to_domain(self) -> dict[Metric, Range]:
        return {m: getattr(self, m.value).to_domain() for m in Metric}

    @classmethod
    def of(cls, ranges: dict[Metric, Range]) -> SensorRanges:
        return cls(**{m.value: MetricRange.of(r) for m, r in ranges.items()})


class SensorDetail(Sensor):
    ranges: SensorRanges  # effective ranges (what alerts use)
    range_source: RangeSourceModel | None


class PhasePresetRequest(_Model):
    phases: list[str] = Field(min_length=1)
    apply: bool = False


# ----------------------------------------------------------------- plants


def _forbid_null(model: BaseModel, *fields: str) -> None:
    for name in fields:
        if name in model.model_fields_set and getattr(model, name) is None:
            raise ValueError(f"{name} cannot be null")


class StrainSummary(_Model):
    id: int
    name: str
    vendor: str | None
    category: str | None
    flowering_days: str | None


class Age(_Model):
    week: int
    day: int


class Plant(_Model):
    id: str
    nickname: str
    strain_id: int | None
    strain: StrainSummary | None
    is_autoflower: bool
    sensor_id: int | None
    thumbnail_photo_id: str | None
    archived: bool
    created_ts: int
    milestones: dict[str, int]
    phase: str
    age: Age | None

    @classmethod
    def of(cls, view: PlantView) -> Plant:
        r = view.row
        strain = (
            StrainSummary(
                id=r["strain_id"],
                name=r["strain_name"],
                vendor=r["strain_vendor"],
                category=r["strain_category"],
                flowering_days=r["strain_flowering_days"],
            )
            if r["strain_id"] is not None
            else None
        )
        return cls(
            id=r["id"],
            nickname=r["nickname"],
            strain_id=r["strain_id"],
            strain=strain,
            is_autoflower=bool(r["is_autoflower"]),
            sensor_id=r["sensor_id"],
            thumbnail_photo_id=r["thumbnail_photo_id"],
            archived=bool(r["archived"]),
            created_ts=r["created_ts"],
            milestones={
                m.value: ts for m, ts in sorted(view.milestones.items(), key=lambda i: i[1])
            },
            phase=view.phase.value,
            age=Age(week=view.age.week, day=view.age.day) if view.age else None,
        )


Nickname = Field(min_length=1, max_length=60)


class PlantCreate(_Model):
    id: UUID
    nickname: str = Nickname
    strain_id: int | None = None
    is_autoflower: bool = False
    sensor_id: int | None = None
    thumbnail_photo_id: UUID | None = None


class PlantPatch(_Model):
    nickname: str | None = Field(default=None, min_length=1, max_length=60)
    strain_id: int | None = None
    is_autoflower: bool | None = None
    sensor_id: int | None = None
    thumbnail_photo_id: UUID | None = None
    archived: bool | None = None

    @model_validator(mode="after")
    def _non_nullable(self) -> PlantPatch:
        _forbid_null(self, "nickname", "is_autoflower", "archived")
        return self

    def changes(self) -> dict[str, Any]:
        out = self.model_dump(exclude_unset=True)
        if out.get("thumbnail_photo_id") is not None:
            out["thumbnail_photo_id"] = str(out["thumbnail_photo_id"])
        return out


# ------------------------------------------------------------------- logs


class SnapshotEntry(_Model):
    sensor_id: int
    sensor_name: str
    ts: int
    temperature_c: float | None
    humidity_pct: float | None
    vpd_kpa: float | None


class Log(_Model):
    id: str
    ts: int
    type: LogType
    plant_ids: list[str]
    photo_ids: list[str]
    comment: str | None
    data: dict[str, Any]
    sensor_snapshot: list[SnapshotEntry]
    created_ts: int
    updated_ts: int

    @classmethod
    def of(cls, rec: LogRecord) -> Log:
        return cls(
            id=rec.id,
            ts=rec.ts,
            type=LogType(rec.type),
            plant_ids=rec.plant_ids,
            photo_ids=rec.photo_ids,
            comment=rec.comment,
            data=rec.data,
            sensor_snapshot=[SnapshotEntry(**s) for s in rec.sensor_snapshot],
            created_ts=rec.created_ts,
            updated_ts=rec.updated_ts,
        )


class LogPage(_Model):
    items: list[Log]
    next_before: int | None


Comment = Field(default=None, max_length=4000)
MAX_LOG_PHOTOS = 5


class LogCreate(_Model):
    id: UUID
    ts: int | None = Field(default=None, ge=0)
    type: LogType
    plant_ids: list[UUID] = Field(min_length=1, max_length=100)
    comment: str | None = Comment
    photo_ids: list[UUID] = Field(default=[], max_length=MAX_LOG_PHOTOS)
    data: dict[str, Any] = {}


class LogPatch(_Model):
    ts: int | None = Field(default=None, ge=0)
    comment: str | None = Comment
    data: dict[str, Any] | None = None
    plant_ids: list[UUID] | None = Field(default=None, min_length=1, max_length=100)
    photo_ids: list[UUID] | None = Field(default=None, max_length=MAX_LOG_PHOTOS)

    @model_validator(mode="after")
    def _non_nullable(self) -> LogPatch:
        _forbid_null(self, "ts", "data", "plant_ids", "photo_ids")
        return self

    def changes(self) -> dict[str, Any]:
        out = self.model_dump(exclude_unset=True)
        for key in ("plant_ids", "photo_ids"):
            if key in out:
                out[key] = [str(i) for i in out[key]]
        return out


# ----------------------------------------------------------------- photos


class Photo(_Model):
    id: str
    ts: int
    width: int
    height: int
    size_bytes: int

    @classmethod
    def of(cls, row: sqlite3.Row) -> Photo:
        return cls(
            id=row["id"],
            ts=row["ts"],
            width=row["width"],
            height=row["height"],
            size_bytes=row["size_bytes"],
        )


# ----------------------------------------------------------------- camera


class Snapshot(_Model):
    id: int
    ts: int
    kind: str
    width: int
    height: int
    size_bytes: int

    @classmethod
    def of(cls, row: sqlite3.Row) -> Snapshot:
        return cls(
            id=row["id"],
            ts=row["ts"],
            kind=row["kind"],
            width=row["width"],
            height=row["height"],
            size_bytes=row["size_bytes"],
        )


class SnapshotPage(_Model):
    items: list[Snapshot]
    next_before: int | None


# ------------------------------------------------------------ environment


class Reading(_Model):
    ts: int
    temperature_c: float | None
    humidity_pct: float | None
    vpd_kpa: float | None


class Levels(_Model):
    temperature: AlarmLevel | None
    humidity: AlarmLevel | None
    vpd: AlarmLevel | None


class SensorEnvironment(_Model):
    sensor: Sensor
    online: bool
    reading: Reading | None
    ranges: SensorRanges
    range_source: RangeSourceModel | None
    levels: Levels
    plants: list[Plant]

    @classmethod
    def of(cls, o: SensorOverview) -> SensorEnvironment:
        r = o.reading
        return cls(
            sensor=Sensor.of(o.sensor),
            online=o.online,
            reading=Reading(
                ts=r.ts,
                temperature_c=r.temperature_c,
                humidity_pct=r.humidity_pct,
                vpd_kpa=r.vpd_kpa,
            )
            if r
            else None,
            ranges=SensorRanges.of(o.ranges),
            range_source=RangeSourceModel.of(o.range_source),
            levels=Levels(**{m.value: level for m, level in o.levels.items()}),
            plants=[Plant.of(p) for p in o.plants],
        )


class SeriesPoint(_Model):
    ts: int
    temperature_c: float | None
    humidity_pct: float | None
    vpd_kpa: float | None
    temp_min: float | None = None
    temp_max: float | None = None
    hum_min: float | None = None
    hum_max: float | None = None
    samples: int


class ReadingSeries(_Model):
    sensor_id: int
    resolution: str  # raw | hour
    bucket_ms: int  # 0 = individual readings
    from_ts: int
    to_ts: int
    points: list[SeriesPoint]


class Alert(_Model):
    id: int
    sensor_id: int | None
    sensor_name: str | None
    metric: str
    level: str
    started_ts: int
    ended_ts: int | None
    value: float | None
    band_min: float | None
    band_max: float | None
    notified_ts: int | None

    @classmethod
    def of(cls, row: sqlite3.Row) -> Alert:
        return cls(**{name: row[name] for name in cls.model_fields})


class AlertPage(_Model):
    items: list[Alert]
    next_before: int | None


class AlertSettingsBody(_Model):
    debounce_s: int = Field(ge=0)
    warning_debounce_s: int = Field(default=1800, ge=0)  # PUT: optional (apps up to 2.2.0)
    language: str = Field(default="en", max_length=20)  # PUT: optional (apps up to 2.3.0)
    repeat_after_s: int = Field(ge=60)
    offline_after_s: int = Field(ge=30)
    battery_low_pct: int = Field(ge=0, le=100)
    notify_warning: bool
    notify_critical: bool
    notify_resolved: bool


class NotificationLanguage(_Model):
    code: str
    name: str


class Event(_Model):
    id: int
    ts: int
    level: str
    source: str
    code: str
    message: str
    data: dict[str, Any] | None

    @classmethod
    def of(cls, row: sqlite3.Row) -> Event:
        return cls(
            id=row["id"],
            ts=row["ts"],
            level=row["level"],
            source=row["source"],
            code=row["code"],
            message=row["message"],
            data=json.loads(row["data"]) if row["data"] else None,
        )


class EventPage(_Model):
    items: list[Event]
    next_before: int | None


class EnvironmentCurrent(_Model):
    ts: int
    sensors: list[SensorEnvironment]
    unlocated_plants: list[Plant]


# -------------------------------------------------------------- reference


class Strain(_Model):
    id: int
    name: str
    vendor: str | None
    category: str | None
    shop_category: str | None
    thc: str | None
    cbd: str | None
    flowering_days: str | None
    yield_indoor: str | None
    height: str | None
    url: str | None
    image_url: str | None
    description: str | None
    custom: bool  # added by the user (editable and deletable in the app)

    @classmethod
    def of(cls, row: sqlite3.Row) -> Strain:
        return cls(**{name: row[name] for name in cls.model_fields})


class StrainPage(_Model):
    items: list[Strain]
    total: int


_Text = Field(default=None, max_length=200)


class StrainCreate(_Model):
    name: str = Field(min_length=1, max_length=200)
    vendor: str | None = _Text
    category: str | None = _Text
    shop_category: str | None = _Text
    thc: str | None = _Text
    cbd: str | None = _Text
    flowering_days: str | None = _Text
    yield_indoor: str | None = _Text
    height: str | None = _Text
    url: str | None = Field(default=None, max_length=2000)
    image_url: str | None = Field(default=None, max_length=2000)
    description: str | None = Field(default=None, max_length=10000)


class StrainPatch(StrainCreate):
    name: str | None = Field(default=None, min_length=1, max_length=200)  # type: ignore[assignment]

    @model_validator(mode="after")
    def _name_not_null(self) -> StrainPatch:
        _forbid_null(self, "name")
        return self


class NutrientProduct(_Model):
    id: int
    name: str
    description: str | None
    unit: str


class NutrientLine(_Model):
    id: int
    brand: str
    description: str | None
    products: list[NutrientProduct]


class FeedingScheduleSummary(_Model):
    id: int
    profile_key: str
    display_name: str
    substrate: str | None
    custom: bool
    weeks: int


class FeedingWeek(_Model):
    week: int
    phase: str
    nutrients: dict[str, float]
    ph: dict[str, float] | None
    ec: dict[str, float] | None
    environment: dict[str, Any] | None
    notes: str | None

    @classmethod
    def of(cls, row: sqlite3.Row) -> FeedingWeek:
        def load(column: str) -> Any:
            return json.loads(row[column]) if row[column] else None

        return cls(
            week=row["week"],
            phase=row["phase"],
            nutrients=load("nutrients") or {},
            ph=load("ph"),
            ec=load("ec"),
            environment=load("environment"),
            notes=row["notes"],
        )


class FeedingSchedule(_Model):
    id: int
    profile_key: str
    display_name: str
    substrate: str | None
    custom: bool
    weeks: list[FeedingWeek]


class ScheduleTarget(_Model):
    min: float | None = Field(default=None, ge=0)
    max: float | None = Field(default=None, ge=0)
    target: float | None = Field(default=None, ge=0)


class ScheduleRange(_Model):
    min: float | None = None
    max: float | None = None


class ScheduleEnvironmentBody(_Model):
    temp_day: ScheduleRange | None = None
    temp_night: ScheduleRange | None = None
    humidity: ScheduleRange | None = None
    light_hours: int | None = Field(default=None, ge=0, le=24)


class FeedingWeekBody(_Model):
    """One week of a custom schedule; its number is its position in ``weeks``."""

    phase: Literal["Veg", "Flower", "Flush"]
    nutrients: dict[
        Annotated[str, Field(min_length=1, max_length=100)], Annotated[float, Field(ge=0)]
    ] = Field(default_factory=dict, max_length=30)
    ph: ScheduleTarget | None = None
    ec: ScheduleTarget | None = None
    environment: ScheduleEnvironmentBody | None = None
    notes: str | None = Field(default=None, max_length=2000)


class FeedingScheduleBody(_Model):
    display_name: str = Field(min_length=1, max_length=100)
    substrate: str | None = Field(default=None, max_length=50)
    weeks: list[FeedingWeekBody] = Field(min_length=1, max_length=52)


class PhaseRangeBody(_Model):
    enforce_limits: bool
    temp_min: float | None = None
    temp_max: float | None = None
    humidity_min: float | None = Field(default=None, ge=0, le=100)
    humidity_max: float | None = Field(default=None, ge=0, le=100)
    vpd_min: float | None = Field(default=None, ge=0)
    vpd_max: float | None = Field(default=None, ge=0)

    @model_validator(mode="after")
    def _ordered(self) -> PhaseRangeBody:
        for what in ("temp", "humidity", "vpd"):
            lo, hi = getattr(self, f"{what}_min"), getattr(self, f"{what}_max")
            if lo is not None and hi is not None and lo > hi:
                raise ValueError(f"{what}_min must be <= {what}_max")
        return self


class PhaseRange(PhaseRangeBody):
    phase: str

    @classmethod
    def of(cls, row: sqlite3.Row) -> PhaseRange:
        return cls(
            phase=row["phase"],
            enforce_limits=bool(row["enforce_limits"]),
            **{
                k: row[k]
                for k in (
                    "temp_min",
                    "temp_max",
                    "humidity_min",
                    "humidity_max",
                    "vpd_min",
                    "vpd_max",
                )
            },
        )


class PushEndpointBody(BaseModel):
    """The app's UnifiedPush endpoint (an ``up…`` topic URL on ntfy), or null."""

    endpoint: str | None = Field(default=None, max_length=512)
    # also send environment snapshots (notification widget)
    environment: bool = False
