# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

GrowBuddy 2 is a self-hosted grow monitor + grow journal, rebuilt from scratch to replace GrowBuddy 0.1.6, and published as open source (GPL-3.0-or-later). It has three independent parts:

- `server/`: Python 3.11+ FastAPI on a Raspberry Pi (developed on a Pi 4 Model B, 2 GB RAM, Raspberry Pi OS Lite trixie; a Pi 4 or newer is required). Multi-sensor BLE BTHome ingestion (bleak), USB webcam stream + hourly timelapse (OpenCV), grow journal, per-sensor alert evaluation → ntfy push, SQLite (WAL).
- `android/`: Kotlin + Jetpack Compose client (AGP 9, compileSdk 37, minSdk 26) with manual DI via `AppContainer`, Retrofit/OkHttp/kotlinx.serialization and Coil. See `android/README.md` for status and structure.
- `backup/`: bash/rsync script run by a systemd **user** timer on a Linux laptop/desktop. It **pulls** nightly DB snapshots plus `snapshots/` and `photos/` from the Pi over SSH.

Remote access is Tailscale only (no port forwarding, no cloud fallback). The app and the backup address the Pi by its Tailscale MagicDNS name (`growbuddy` in the docs; each user picks their own).

Status: the server is feature-complete against `docs/API.md` and tested. Users install it on the Pi with `server/deploy/install.sh` (guide: `docs/INSTALL.md`); developers push a checkout to a Pi with `server/scripts/deploy.sh [user@host]` (default host from the git-ignored `server/.deploy-host`). The Android app is feature-complete, including offline support; `android/README.md` has the per-screen status.

## Commands

Server (from `server/`, uses uv):
```sh
uv sync
GROWBUDDY_CONFIG=config.toml uv run uvicorn --factory growbuddy.main:create_app --reload   # config.toml from config.example.toml
uv run pytest
uv run pytest tests/test_phase.py::test_name
uv run ruff check . && uv run ruff format .
uv run mypy src
```
To run without hardware, set `ble.simulate = true` (two fake BTHome sensors fed through the real parser/ingest path) and `camera.simulate = true` (generated frames), and `ntfy.enabled = false`. This is useful for Android development. Schema check: `sqlite3 :memory: < src/growbuddy/db/migrations/0001_initial.sql`.

Android (from `android/`; the toolchain lives in `~/Android`, and the system JDK 27 is too new for AGP):
```sh
export JAVA_HOME=~/Android/jdk-21 ANDROID_HOME=~/Android/Sdk
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug   # lint must stay at zero issues
./gradlew :app:testDebugUnitTest --tests 'com.growbuddy.app.ContractTest'
./gradlew :app:installRelease                                        # the phone runs the signed release build (key: android/README.md)
```
Develop the app against `server/scripts/dev_server.sh` (simulated sensors and camera on the LAN). A deployed Pi is production (it tracks a live grow), so deploy only tested changes and never reset its data.

Backup: `bash -n backup/growbuddy-backup.sh`, `systemd-analyze --user verify backup/systemd/*`.

## Source-of-truth documents

Read the relevant one before changing behaviour, and update it in the same change:
- `CHANGELOG.md`: add user-visible changes under a new version heading (one version for server, app and backup).
- `docs/DOMAIN.md`: phase/age derivation, sensors/locations, per-sensor warn/crit ranges, phase-template presets (intersection = warn, union + margin = crit), VPD formula, log types and their `data` payloads. Phase/age/VPD rules are carried over exactly from 0.1.6, so keep them unless asked to change them.
- `docs/API.md`: the contract between server and app. Breaking changes go to `/api/v2`.
- `docs/DATA_MODEL.md` + `server/src/growbuddy/db/migrations/`: the schema.
- `docs/HARDWARE.md`: BTHome object table, sensor MAC, webcam notes.

## Architecture rules that span files

- **One server process, one uvicorn worker.** The API and all background tasks (BLE scanner, camera, alert evaluator, rollup/retention, nightly snapshot) share one asyncio loop, started and stopped in `main.py`'s `lifespan`. Extra workers would double-record readings and fight over the webcam and the SQLite writer.
- **The camera service is the only code that opens the V4L2 device.** The stream, hourly frames and manual captures all go through it. Consumers use `CameraService.lease()` / `fresh_frame()` / `frames()`. The device opens on the first lease and closes `idle_close_s` after the last one. Tests inject a fake `FrameSource` via `source_factory`.
- **Blocking work** (OpenCV, image resizing, `VACUUM INTO`) runs in `asyncio.to_thread`.
- **Layering:** `api → overview/journal/alerts/camera/sensors/services → domain, db`. Nothing imports `api`, and `db` imports only `domain`. Services raise `growbuddy.errors.AppError` subclasses (`NotFound`, `Conflict`, `Invalid`), which `api/errors.py` renders. Use `clock.now_ms()` for time. `domain/`, `sensors/bthome.py` and `sensors/processor.py` are pure (no I/O), so they're unit-tested on plain values and bytes. Route handlers stay thin. SQL lives in `db/queries/<group>.py`.
- **Sensors are data, not config.** Any number of BTHome sensors, adopted via the API/app (`sensors` table). `sensors/processor.py` (pure) decodes every advert, dedups by packet id, and keeps unadopted devices in an in-memory discovered list. `sensors/scanner.py` (`SensorHub`) runs the source (BlueZ under a backoff + watchdog supervisor, or the simulator), and stores readings through a queue and a single writer task. Call `hub.reload_adopted()` after adopting or retiring a sensor. Plants have an optional location (`plants.sensor_id`). Ranges come from `services/ranges.effective_ranges`: `auto` mode (the default) uses the phase template of the oldest active plant at the sensor, and `manual` uses `sensor_ranges`. Everything that evaluates or shows ranges (overview, alert evaluator, sensor routes) must go through it, never read `sensor_ranges` directly. `phase_ranges` are templates only.
- **Derived values are never stored:** phase, age, milestones (the `plant_milestones` view = earliest log of each milestone type), VPD and alarm levels are all computed on the server. The Android app displays them and doesn't re-implement `DOMAIN.md`.
- **Client-generated UUIDs** for plants, logs and photos. A repeat create with an existing id returns the existing resource unchanged (200 instead of 201). The app's offline queue relies on this. PATCH bodies use pydantic `exclude_unset`, so only the fields sent change, and explicit `null` clears nullable fields.
- **Photos** are decoded and re-encoded with OpenCV in `asyncio.to_thread` (downscaled, EXIF/GPS stripped, thumbnail made), and served with `Cache-Control: immutable`. Log entries reference them by id.
- **Timestamps** are integer epoch **milliseconds UTC** everywhere (DB, API, app models). Only the Android UI converts to local time.
- **Schema:** never edit an applied migration. Dev databases have applied `0001`–`0003`, so every schema change is a new numbered file (tracked via `PRAGMA user_version`). Write it so it upgrades existing data sensibly (see `0002`).
- **Reference data** comes from `server/src/growbuddy/seed/*.json` (format: `seed/README.md`). Strains and phase ranges are seeded into empty tables and editable through the API afterwards. Nutrient lines and built-in feeding schedules are synced on every startup (new seed entries reach existing installs). `custom = 1` schedules and strains are the user's: seeding never touches them, and only they can be deleted (built-in schedules are read-only; the app duplicates them).
- **Alerts:** `alerts/rules.step` is the pure debounce state machine, and `AlertEvaluator.tick(now)` drives it (tests call `tick` directly with a fake notifier). Active alerts are re-read from the DB every tick, with no in-memory alert state besides pending transitions and the notification outbox. Effective settings come from `alerts.settings.load_alert_settings` (config defaults plus DB overrides). Use it, not `settings.alerts`, wherever runtime values matter.
- **Housekeeping:** `services/housekeeping.py` schedules the rollup (hh:02 and at startup), retention (daily at `retention.run_time`) and the nightly DB snapshot (`backup.snapshot_time`, written as `.part`, `quick_check`ed, then renamed). The daily jobs catch up after startup. Retention must call the rollup first and never delete raw readings at or after `rollup.watermark`. Unused photos are removed 7 days after upload (`photos.created_ts`, not the taken time `ts`).
- **Images live on disk** (`snapshots/YYYY/MM/DD/`, `photos/YYYY/MM/`, each with a thumbnail). DB rows store paths relative to `data_dir`.
- **Backups:** only ever copy the nightly `VACUUM INTO` files, never the live `.db`/`-wal`. Image mirroring uses no `--delete`.
- **Config:** TOML at `$GROWBUDDY_CONFIG` + `GROWBUDDY_<SECTION>__<KEY>` env overrides. `config.example.toml` documents every key. Runtime-editable alert settings live in the DB `settings` table.
- **Nothing site-specific in the repo:** no hostnames, IPs, MACs or personal names in code, defaults or docs (use `growbuddy` as the example host). Per-install values come from `install.sh` (`--host`) into `/etc/growbuddy/config.toml`, or from the app's setup.
- **Secrets** (API token, ntfy tokens, the ntfy phone password) live only in `/etc/growbuddy/config.toml` and app DataStore. Never put them in the repo, seed files or APK resources.

- **Android look:** follows the 0.1.6 Unity app (screenshots supplied by the user): white logo splash, 0.1.6 sprites in `res/drawable-nodpi/`, Roboto Condensed text, Roboto Mono numbers, colours sampled from 0.1.6 in `ui/theme/Palette`, a five-icon bottom bar (Strains, Grow log, Home centre, Plants, Settings; Sensors live under Settings), and `ui/common/Dial.kt` (a port of 0.1.6 `DialDisplay.cs`). Keep new screens in this style; on wide (unfolded) screens, use two columns or grids instead of stretching.
- **Android offline:** reads fall back to the OkHttp cache in `ApiProvider` (its `offline` flag drives the offline bar). Creates of plants, logs and photos queue in `data/sync/Outbox` on a `network` error and are replayed by `SyncWorker`/`OutboxSync`, which relies on the server's idempotent client-UUID creates. Queued logs carry their own `ts` (fixed at save time). Edits and deletes stay online-only.
- **Localization:** all visible app text lives in `res/values/strings.xml` (English) with translations in `res/values-<code>/strings.xml` (Danish: `values-da`). Composables use `stringResource`, other code `appString`/`appPlural` (`ui/common/AppText.kt`); enums keep a `labelRes` with a computed `label`. Every new string gets a Danish translation in the same change (`LocalizationTest` + lint `MissingTranslation`). Server notification texts come from `server/src/growbuddy/locales/<code>.json` in `alerts.language`. Guide: `docs/LOCALIZATION.md`.
- **Android confirmations:** every destructive action (delete, overwrite saved data, discard unsaved input, disconnect) goes through `ui/common/ConfirmDialog` first. Keep that for new actions.
- **Android notifications:** alerts are delivered by the ntfy app as UnifiedPush distributor to `data/push/GrowBuddyPushService`, which posts them with `ic_stat_leaf`. The server publishes to the endpoint registered via `PUT /push-endpoint` (an `up…` topic on `ntfy.public_url`) and falls back to the shared topic.
- **Android notification widget:** `data/widget/EnvironmentWidget` draws the dashboard dials (shared `drawDialFace`) into a minimised notification; the server pushes `environment` snapshots (`services/environment_push.py`) to the app's push topic while `PUT /push-endpoint` has `environment: true`. The 4×2 home screen widget (`data/widget/HomeWidgetProvider`) draws the same snapshot and adds a quick-log button (`growbuddy://log`).
- **Android token scope:** the shared OkHttp client only attaches the API token to requests for the configured server host and port (third-party strain images must never get it).
- **Android:** DTOs in `data/remote/Dto.kt` mirror `docs/API.md` (snake_case via `ApiJson`'s naming strategy). When the API changes, update the DTOs and refresh `app/src/test/resources/fixtures/` from a running dev server, so `ContractTest` keeps checking against real output. The app displays server-derived values (phase, age, levels) and must not re-implement `docs/DOMAIN.md`.

## Legacy reference

`legacy/` (git-ignored except its README) holds the 0.1.6 .NET logger and the Unity app. Use it only to check old behaviour. Never import from it or copy its structure. `legacy/unity/GrowBuddy/Assets/StreamingAssets/config.json` contains a secret, so don't copy or quote it.
