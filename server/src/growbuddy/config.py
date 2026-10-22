"""Typed settings loaded from TOML (GROWBUDDY_CONFIG) with GROWBUDDY_* env overrides.

Precedence (highest first): explicit init kwargs, environment variables
(``GROWBUDDY_<SECTION>__<KEY>``), the TOML file named by ``GROWBUDDY_CONFIG``,
then the defaults below. ``config.example.toml`` documents every key.
"""

from __future__ import annotations

import os
from datetime import time
from pathlib import Path

from pydantic import BaseModel, Field
from pydantic_settings import (
    BaseSettings,
    PydanticBaseSettingsSource,
    SettingsConfigDict,
    TomlConfigSettingsSource,
)

CONFIG_ENV_VAR = "GROWBUDDY_CONFIG"


class ServerSettings(BaseModel):
    host: str = "0.0.0.0"
    port: int = 8000
    api_token: str = Field(min_length=16)
    data_dir: Path = Path("/var/lib/growbuddy")

    @property
    def db_path(self) -> Path:
        return self.data_dir / "growbuddy.db"


class BleSettings(BaseModel):
    enabled: bool = True
    simulate: bool = False  # fake tent + drying sensors for development; overrides enabled
    discovery_ttl_s: int = 300
    watchdog_s: int = 300  # restart the scanner if adopted sensors exist but adverts stop


class CameraSettings(BaseModel):
    enabled: bool = True
    simulate: bool = False  # generated frames for development; overrides enabled
    device: str = "/dev/video0"
    width: int = 1920
    height: int = 1080
    stream_fps: int = Field(default=10, ge=1, le=30)
    interval_s: int = Field(default=3600, ge=1)
    jpeg_quality: int = Field(default=88, ge=1, le=100)
    warmup_frames: int = 5
    thumb_edge_px: int = 400
    idle_close_s: float = 10  # close the device this long after the last viewer/capture
    max_viewers: int = 4


class PhotoSettings(BaseModel):
    max_edge_px: int = 2560
    thumb_edge_px: int = 400
    jpeg_quality: int = Field(default=88, ge=1, le=100)


class AlertSettings(BaseModel):
    """Defaults; the effective values are runtime-editable (PUT /alert-settings)."""

    evaluate_every_s: int = Field(default=60, ge=5)
    debounce_s: int = Field(default=600, ge=0)  # critical, back to normal, warning <-> critical
    warning_debounce_s: int = Field(default=1800, ge=0)  # normal -> warning
    repeat_after_s: int = Field(default=7200, ge=60)
    offline_after_s: int = Field(default=300, ge=30)
    battery_low_pct: int = Field(default=15, ge=0, le=100)
    notify_warning: bool = True
    notify_critical: bool = True
    notify_resolved: bool = True
    language: str = "en"  # notification texts: growbuddy/locales/<language>.json


class NtfySettings(BaseModel):
    enabled: bool = True
    url: str = "http://127.0.0.1:2586"
    topic: str = "growbuddy"
    token: str = ""
    click_url: str = "growbuddy://alerts"
    # ntfy as phones reach it; app push endpoints (UnifiedPush) must be on this URL
    public_url: str = "http://growbuddy:2586"
    environment_push_s: int = Field(default=300, ge=30)  # notification widget refresh


class RetentionSettings(BaseModel):
    readings_days: int = Field(default=90, ge=1)
    snapshots_days: int = Field(default=365, ge=1)
    events_days: int = Field(default=180, ge=1)
    run_time: time = time(2, 30)  # local; before the 03:00 backup snapshot


class BackupSettings(BaseModel):
    snapshot_time: time = time(3, 0)
    keep_local: int = 7


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="GROWBUDDY_",
        env_nested_delimiter="__",
        extra="ignore",  # GROWBUDDY_CONFIG itself is not a setting
    )

    server: ServerSettings
    ble: BleSettings = BleSettings()
    camera: CameraSettings = CameraSettings()
    photos: PhotoSettings = PhotoSettings()
    alerts: AlertSettings = AlertSettings()
    ntfy: NtfySettings = NtfySettings()
    retention: RetentionSettings = RetentionSettings()
    backup: BackupSettings = BackupSettings()

    @classmethod
    def settings_customise_sources(
        cls,
        settings_cls: type[BaseSettings],
        init_settings: PydanticBaseSettingsSource,
        env_settings: PydanticBaseSettingsSource,
        dotenv_settings: PydanticBaseSettingsSource,
        file_secret_settings: PydanticBaseSettingsSource,
    ) -> tuple[PydanticBaseSettingsSource, ...]:
        sources: list[PydanticBaseSettingsSource] = [init_settings, env_settings]
        toml_file = os.environ.get(CONFIG_ENV_VAR)
        if toml_file:
            if not Path(toml_file).is_file():
                raise FileNotFoundError(f"{CONFIG_ENV_VAR}={toml_file} does not exist")
            sources.append(TomlConfigSettingsSource(settings_cls, toml_file=toml_file))
        return tuple(sources)
