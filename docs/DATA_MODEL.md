# Data model

The database is a single SQLite file, `<data_dir>/growbuddy.db`. The
authoritative schema is the migration files in
`server/src/growbuddy/db/migrations/`. This document explains the design
behind them.

## Rules

- **Timestamps** are `INTEGER` Unix epoch **milliseconds, UTC**. Never store
  local time or ISO strings.
- **Pragmas, set on every connection:** `journal_mode=WAL`,
  `synchronous=NORMAL`, `foreign_keys=ON`, `busy_timeout=5000`.
- **Migrations** are numbered files applied in order at startup and tracked
  with `PRAGMA user_version`. Never edit a migration once any database has
  applied it. `0001` and `0002` are applied in dev databases, so every schema
  change gets a new file.
- **IDs:** journal tables (`plants`, `logs`, `photos`) use client-generated
  UUID text keys, which makes retries idempotent. Server-owned tables use
  `INTEGER PRIMARY KEY`.
- **Binary data** stays out of the DB. Images live on disk, and their rows
  store a path relative to `data_dir`.
- **Derived values aren't stored:** phase, age, milestones, VPD and alarm
  level are all computed (DOMAIN.md).
- **Flexible per-type payloads** (`logs.data`, `feeding_schedule_weeks.*`) are
  JSON text, validated by Pydantic models at the API boundary.

## Tables

**Environment**

| Table | Purpose |
|---|---|
| `sensors` | **Adopted** BLE devices only: MAC, name, `status` (active/retired), `alerts_enabled`, `range_mode` (auto/manual, migration 0002), last_seen, last battery/RSSI. Discovered-but-unadopted devices exist only in memory |
| `sensor_ranges` | Per sensor and metric (temperature/humidity/vpd): the **manual** `warn_min/max`, `crit_min/max`. Auto ranges are computed, never stored |
| `readings` | Raw measurements: `ts, sensor_id, temperature_c, humidity_pct, battery_pct, voltage_v, rssi` |
| `readings_hourly` | Hourly min/avg/max per sensor (rollup job) |
| `snapshots` | Timelapse/manual camera frames: `ts, path, thumb_path, width, height, size_bytes, kind` |

**Journal**

| Table | Purpose |
|---|---|
| `plants` | `id, nickname, strain_id, is_autoflower, sensor_id (location), thumbnail_photo_id, archived, created_ts` |
| `logs` | `id, ts, type, comment, data (JSON), sensor_snapshot (JSON), created_ts, updated_ts` |
| `log_plants` | Many-to-many log ↔ plant |
| `photos` | Phone photos: `id, ts (taken), path, thumb_path, width, height, size_bytes, created_ts (uploaded)` |
| `log_photos` | Many-to-many log ↔ photo (ordered) |

**Reference** (from `seed/*.json`; format in `server/src/growbuddy/seed/README.md`). Strains and phase ranges are seeded into empty tables and editable afterwards. Nutrient lines and built-in feeding schedules are synced on every start: missing lines/products are added, built-in schedules (`custom = 0`) are inserted or updated by `profile_key`. `custom = 1` rows (migration `0003`) belong to the user and are never touched by seeding

| Table | Purpose |
|---|---|
| `strains` | Catalog: name, vendor, category, shop_category, thc, cbd, flowering_days, yield, height, url, image_url, description, `custom` (added by the user) |
| `nutrient_lines`, `nutrient_products` | Brands and their products with unit |
| `feeding_schedules`, `feeding_schedule_weeks` | Schedule header (`profile_key`, `custom`: built-in or made in the app, key `custom-…`) + per-week JSON (nutrients, pH, EC, environment, notes) |
| `phase_ranges` | Per-phase temp/RH/VPD min/max + `enforce_limits`. **Templates** for sensor range presets, never evaluated directly |

**System**

| Table | Purpose |
|---|---|
| `alerts` | Alert state history: `sensor_id, metric, level, started_ts, ended_ts, value, band_min, band_max, notified_ts` |
| `settings` | Key/value JSON: runtime-editable settings (alert settings) and housekeeping state (`rollup.watermark`, `retention.last_run`) |
| `events` | System event log: `ts, level, source, code, message, data (JSON)` |

## Retention

Defaults, all configurable:

| Data | Kept on the Pi |
|---|---|
| `readings` (raw) | 90 days (hourly rollups forever) |
| `snapshots` | 365 days (the laptop backup keeps everything) |
| `events` | 180 days |
| Journal (plants, logs, photos in use) | Forever |
| Photos not used by any log entry or plant | 7 days after upload (`created_ts`) |

**Rollup** (`services/rollup.py`) runs at hh:02 and at startup. It aggregates
complete hours up to the `rollup.watermark` setting, and re-does the last
rolled hour to include late readings.

**Retention** (`services/retention.py`) runs daily at `retention.run_time`
(default 02:30 local), and at startup if the last run was more than 26 h ago.
It runs the rollup first, and never deletes raw readings at or after the
watermark, so aggregates always exist before the raw rows they summarize are
deleted. Snapshot and photo files are removed with their rows. The run ends
with `PRAGMA optimize` and a `retention_done` event listing what was
removed.
