# Architecture

## Overview

```
 Mijia hygrometers               Raspberry Pi ("growbuddy")                       Phone
 (tent, drying, …)   ─BLE─▶ ┌──────────────────────────────────┐  HTTP (LAN or ┌─────────────┐
                            │ growbuddy (1 process, :8000)     │  Tailscale)   │ Android app │
 USB webcam ──V4L2────────▶ │  ├─ FastAPI /api/v1              │◀─────────────▶│ (Compose)   │
                            │  ├─ BLE scanner                  │               └─────────────┘
                            │  ├─ Camera (stream + hourly)     │                      ▲
                            │  ├─ Alert evaluator ─────────────┼──▶ ntfy (:2586) ─────┘ push
                            │  └─ Rollup / retention / snapshot│
                            │ SQLite (WAL) + photos/snapshots  │◀── rsync/SSH pull ── Laptop
                            └──────────────────────────────────┘     (daily, Linux) 
```

GrowBuddy has two halves that share one server and one database:

1. **Environment monitoring:** any number of BTHome sensors (e.g. grow tent,
   drying chamber), found and adopted from the app. Each has its own ranges
   and alerts. Plus a camera timelapse.
2. **Grow journal:** plants, strains, log entries (feeding, training,
   milestones, photos), with phase and age derived from milestones. Rules are
   in [DOMAIN.md](DOMAIN.md).

The two halves meet through **locations**: each plant can be assigned to the
sensor of the space it's in. Log entries record that sensor's reading, the
dashboard groups plants by sensor, and phase templates can pre-fill a
sensor's ranges.

## Server process model

The server is **one process**: uvicorn with exactly **one worker**. The API and
all background jobs run in a single asyncio event loop. Background tasks are
started and stopped in FastAPI's `lifespan` handler.

- **BLE:** only one scanner should own the Bluetooth adapter. A second
  worker would store every reading twice. The scanner listens for adverts
  through bleak's callback, which replaces 0.1.6's 10 s D-Bus polling loop.
  It decodes **every** BTHome advert it hears. Readings from adopted MACs
  are persisted. All other devices only update an in-memory "discovered"
  table that `GET /sensors/discovered` reads. The adopted-MAC set is cached
  in memory and reloaded when sensors are adopted or retired. A supervisor
  restarts the scanner with backoff (5 s → 5 min) when BlueZ errors. A
  watchdog restarts it when adopted sensors exist but no BTHome advert has
  arrived for `ble.watchdog_s`.
- **Camera:** a V4L2 device should have one owner. All captures go through
  the camera service (`camera/service.py`), which shares one capture loop:
  viewers of the MJPEG stream, the hourly timelapse frame and on-demand
  captures all read the newest frame from it. Consumers hold a *lease*. The
  device is opened (and warm-up frames discarded) when the first lease
  appears, and closed `camera.idle_close_s` after the last one ends. Open and
  read failures are logged as events and retried with backoff (5 s → 5 min).
  Timelapse frames are taken on interval-aligned slots (hourly means on the
  hour). A failed slot is retried after 30 s, 1, 2 and 5 min (never past the
  next slot), and only then recorded as `snapshot_missed`. `camera/sources.py` holds the device code: OpenCV V4L2 with MJPEG
  passthrough when the driver allows it, or a simulator.
- **SQLite:** one writer avoids `database is locked` errors. With WAL mode,
  the API's reads never block that writer.

Any long blocking work, such as OpenCV capture/encode, thumbnail creation or
`VACUUM INTO`, runs in `asyncio.to_thread`.

## Server layout (`server/src/growbuddy/`)

| Module | Responsibility |
|---|---|
| `main.py` | App factory, `lifespan` (starts/stops services), router mounting |
| `config.py` | Typed settings (pydantic-settings): TOML file + `GROWBUDDY_*` env overrides |
| `api/` | Routers (`routes/` has one module per resource group in API.md), auth dependency, request/response schemas, error rendering, pagination. Thin layer that calls the services |
| `journal/` | Service layer for plants, log entries and photos: validation, idempotent creates, sensor snapshots, image processing |
| `overview.py` | Builds the dashboard response (`/environment/current`) |
| `errors.py`, `clock.py` | `AppError` hierarchy raised below the API layer (rendered by `api/errors.py`), and `now_ms()` |
| `domain/` | **Pure** grow logic: phase/age derivation, VPD, range evaluation. No I/O, fully unit-tested |
| `sensors/` | BTHome v2 parser (pure) + bleak passive scanner |
| `camera/` | Capture service that owns the V4L2 device: stream fan-out, hourly scheduler, storage |
| `alerts/` | `rules.py` (pure state machine + messages), `evaluator.py` (observations, transitions, outbox), `ntfy.py` (JSON publisher), `settings.py` (runtime-editable alert settings) |
| `db/` | Connection management, numbered SQL migrations, seeding. `db/queries/` holds SQL per table group (sensors, plants, logs, photos) |
| `seed/` | Reference JSON (strains, nutrient lines, feeding schedule, phase ranges) |
| `services/` | Background jobs and shared services: `housekeeping.py` (schedules hourly rollup and daily retention), `rollup.py`, `retention.py`, `series.py` (`/readings` time series), `events.py` (event log), `snapshot.py` (nightly `VACUUM INTO` copy for the laptop backup) |

Dependency direction: `api → overview/journal/alerts/camera/sensors/services → domain, db`.
Nothing imports from `api`, `domain` imports nothing from the project, and
`db` imports only `domain`. Services raise `errors.AppError` subclasses, never
HTTP exceptions.

## Data flow

1. **Climate:** each hygrometer advertises every few seconds. The scanner
   decodes the BTHome payload and drops repeated packet ids. For adopted
   sensors it writes a row to `readings`. Unadopted devices only show up in
   discovery. Hourly aggregates
   (`readings_hourly`) back the long-range charts.
2. **Timelapse:** once an hour the camera service saves a JPEG plus a
   thumbnail under `snapshots/YYYY/MM/DD/` and a row in `snapshots`.
3. **Journal:** the app posts log entries and uploads phone photos (multipart).
   Photos are stored under `photos/YYYY/MM/` and referenced by id. The server
   attaches the nearest reading from each affected plant's location sensor.
4. **Alerts:** every minute the evaluator checks each active sensor that has
   `alerts_enabled`: its latest reading against its own ranges, plus offline
   and low-battery conditions, plus camera failure. Alert state is kept per
   (sensor, metric). Full rules are in DOMAIN.md › Alerts. A state change (normal → warning → critical →
   normal) must persist for a configurable debounce time, and repeat
   notifications are rate-limited. Changes are written to `alerts` /
   `events` and published to ntfy.
5. **Backup:** each night the Pi writes a consistent DB copy (`VACUUM INTO`) to
   `backups/`. The laptop pulls those copies plus `snapshots/` and `photos/`.
   See [backup/README.md](../backup/README.md).

## Networking

| Service | Port | Who uses it |
|---|---|---|
| growbuddy API | 8000 | Android app |
| ntfy | 2586 | growbuddy (publish), ntfy Android app (subscribe) |
| SSH | 22 | Laptop backup pull |

Remote access goes through **Tailscale** on the Pi, the phone and the laptop.
The app holds one server URL, either the MagicDNS name (`http://growbuddy:8000`,
the Pi's hostname by default; `deploy/install.sh --host` sets the name used for
ntfy) or `https://growbuddy.<tailnet>.ts.net` through `tailscale serve`. That URL
works both at home and away. Release builds allow plain HTTP to any host, since
the hostname is the user's choice and Tailscale encrypts the traffic; the token
is only sent to the configured host. Nothing is port-forwarded, and there's no cloud
fallback (this replaces 0.1.6's GitHub sync).

## Security model

Every `/api/v1` route except `/health` requires a static bearer token, set in
the server config and entered once in the app. ntfy runs with `deny-all`
default access: growbuddy publishes with a write token, and the phone
subscribes as the read-only user `phone` (password created by `install.sh`). Secrets live only in `/etc/growbuddy/` on the Pi
and in app DataStore. They never go in the repo or in assets built into the APK.

## Android app

Single-activity Compose app using MVVM: `ui (screen + ViewModel) → data/remote
(Retrofit) + data/sync (outbox)`. Phase, age, VPD and alarm levels always come
from the server.

**Offline:**
- **Reading:** every API GET response is kept in OkHttp's HTTP disk cache,
  stored as immediately stale, so it is always refetched while online. When
  the server is unreachable, the last saved copy is served, and the app shows
  "Offline – showing saved data". Images use Coil's own disk cache.
- **Writing:** creating a log entry or a plant, and uploading photos, are
  queued in a file-backed outbox when the server is unreachable. A WorkManager
  job replays them in order once it is reachable. Client-generated UUIDs make
  every replay idempotent. Entries the server rejects are kept and shown in
  Settings › Sync.
- **Edits, deletes and plant thumbnails** need a connection.

**Notifications:** alerts are published to ntfy on the Pi, and the ntfy Android
app delivers them. When the GrowBuddy app has registered as a UnifiedPush
client (Settings › Notifications), the server publishes to that app's private
`up…` topic instead. ntfy then hands the alert to GrowBuddy, which posts it
with its own leaf icon. If that publish fails, the alert goes to the shared
`growbuddy` topic, so it is never lost. Tapping a notification opens the Alerts
screen (`growbuddy://alerts`).

**Notification widget:** with the widget switched on (Settings › Notification
widget), the app registers its push endpoint with `environment: true`, and the
server (`services/environment_push.py`) also publishes a compact environment
snapshot there every 5 minutes and right after an alert changes. The app shows
it as a silent, minimised notification (no status bar icon) with the dashboard
dials rendered to bitmaps (`data/widget/`, sharing `drawDialFace` with the
dashboard) and ‹ › to step through the chosen sensors. Opening the app or
tapping an arrow also fetches `/environment/current`. The 4×2 home screen
widget draws the same snapshot (and asks for the pushes while it's placed),
plus a Quick log button that opens the log wizard (`growbuddy://log`).
