# HTTP API: `/api/v1`

Draft contract. The app and server both follow this document. If an endpoint
changes, update this file in the same change. FastAPI also serves the
generated schema at `/api/v1/openapi.json`.

## Conventions

- Base path `/api/v1`. Breaking changes require `/api/v2`, never an edit in place.
- Auth: `Authorization: Bearer <token>` on every route except `GET /health`.
- Timestamps: integer **Unix epoch milliseconds, UTC** (`ts`).
- Units: °C, %RH, kPa (VPD), % battery, V, dBm (RSSI), litres, cm.
- JSON field names are `snake_case`.
- Errors use one shape: `{"error": {"code": "not_found", "message": "..."}}` plus a matching HTTP status.
- Lists use cursor pagination: `?limit=` (default 100, max 1000) and `?before=<ts>`.
  The response includes `next_before` when more data exists.
- **IDs:** plants, log entries and photos have **client-generated UUIDs**
  (`id` in the create body). Repeating a create with an existing `id` returns
  the existing resource with `200` instead of creating a duplicate. This makes
  the app's offline queue and retries safe.

## System
| Method | Path | Description |
|---|---|---|
| GET | `/health` | No auth: `{"status":"ok","version":"2.x.y"}` |
| GET | `/status` | `{version, uptime_s, schema_version, db_size_bytes, disk_free_bytes, ble{…}, camera{…}, alerts{active_alerts, outbox, last_notify_error}, last_backup, last_backup_ts}` |

## Environment
| Method | Path | Description |
|---|---|---|
| GET | `/environment/current` | `{ts, sensors: [{sensor, online, reading{ts, temperature_c, humidity_pct, vpd_kpa}, ranges, range_source, levels{temperature, humidity, vpd}, plants[]}], unlocated_plants[]}`. `ranges` are the effective ones. `online` means seen within `alerts.offline_after_s`. A level is `null` when there's no value. The dashboard's single call |
| GET | `/readings?sensor_id=&from=&to=&resolution=&max_points=` | Time series for one sensor (retired sensors included). Defaults to the last 24 h. `resolution` = `raw` \| `hour` \| `auto` (raw ≤ 48 h, else hourly). Response: `{sensor_id, resolution, bucket_ms, from_ts, to_ts, points: [{ts, temperature_c, humidity_pct, vpd_kpa, temp_min, temp_max, hum_min, hum_max, samples}]}`. Raw data above `max_points` (default 2000, max 10000) is averaged into buckets of `bucket_ms`, which is 0 for individual readings. Hourly points combine stored rollups with live aggregation of the current hour. For aggregated points, VPD is computed from the averaged temperature and humidity |

## Sensors
| Method | Path | Description |
|---|---|---|
| GET | `/sensors/discovered?within_s=` | BTHome devices heard recently (default 300 s) that aren't adopted: MAC, RSSI, last decoded values, last_seen. Lets the user tell devices apart. In-memory, nothing stored |
| GET | `/sensors?include_retired=` | Adopted sensors: id, MAC, name, status, last_seen, battery, rssi, alerts_enabled, `range_mode` (`auto` \| `manual`) |
| POST | `/sensors` | Adopt: `{mac, name}`. Recording starts immediately. Re-adopting a retired MAC reactivates it with its history |
| GET | `/sensors/{id}` | One sensor with its **effective** `ranges` and `range_source` (`{plant_id, nickname, phase}` of the plant driving auto ranges, or null) |
| PATCH | `/sensors/{id}` | `name`, `alerts_enabled`, `range_mode`. Switching to `manual` with nothing stored seeds from the current auto ranges (DOMAIN.md › Range modes) |
| DELETE | `/sensors/{id}` | Retire: stop recording and alerting. History is kept. Plants located there lose their location |
| GET | `/sensors/{id}/ranges` | Effective ranges: `{temperature, humidity, vpd}`, each `{warn_min, warn_max, crit_min, crit_max}` (nullable) |
| PUT | `/sensors/{id}/ranges` | Replace the manual ranges, and switch the sensor to `manual` |
| POST | `/sensors/{id}/ranges/from-phases` | `{phases: [...], apply: bool}`. Computes a preset from phase templates (DOMAIN.md). Returns it, and when `apply` is true stores it and switches to `manual` |

## Camera
| Method | Path | Description |
|---|---|---|
| GET | `/camera/stream?frames=` | MJPEG live view (`multipart/x-mixed-replace; boundary=frame`), sent with `Cache-Control: no-store`. The optional `frames` parameter ends the stream after N frames (debugging) |
| GET | `/camera/frame` | One fresh JPEG (preview / widget) |
| POST | `/camera/capture` | Store a manual snapshot now. Returns `{id, ts, kind, width, height, size_bytes}` |
| GET | `/snapshots?kind=&from=&to=&limit=&before=` | Snapshot metadata, newest first: `{items, next_before}`. `kind` = `scheduled` \| `manual` |
| GET | `/snapshots/{id}/image?thumb=1` | JPEG, sent with `Cache-Control: immutable` |

The camera routes return `503`: `camera_disabled` when the camera is off,
`too_many_viewers` above `camera.max_viewers`, or `camera_unavailable` (with
the device error) when no frame arrives within 15 s.

## Plants
| Method | Path | Description |
|---|---|---|
| GET | `/plants?include_archived=&sensor_id=` | List. Each plant: `id, nickname, strain_id, strain{id, name, vendor, category, flowering_days}, is_autoflower, sensor_id, thumbnail_photo_id, archived, created_ts`, plus derived `milestones{type: ts}`, `phase`, `age{week, day}` (or null) |
| POST | `/plants` | `{id, nickname, strain_id?, is_autoflower, sensor_id?, thumbnail_photo_id?}`. `sensor_id` is the location |
| GET | `/plants/{id}` | One plant (derived fields included) |
| PATCH | `/plants/{id}` | Only the fields sent are changed. `null` clears `strain_id`, `sensor_id` and `thumbnail_photo_id`. Also used to move a plant (`sensor_id`) and to archive it |
| DELETE | `/plants/{id}` | Delete. Log entries linked only to this plant are deleted too |
| GET | `/plants/{id}/logs?limit=&before=` | This plant's log entries, newest first: `{items, next_before}` |

## Log entries
| Method | Path | Description |
|---|---|---|
| POST | `/logs` | `{id, ts?, type, plant_ids[], comment?, photo_ids[] (max 5), data{}}`. Validated per type (DOMAIN.md, error code `invalid_data`). The server adds `sensor_snapshot: [{sensor_id, sensor_name, ts, temperature_c, humidity_pct, vpd_kpa}]` |
| GET | `/logs?type=&from=&to=&limit=&before=` | All entries, newest first: `{items, next_before}` |
| GET | `/logs/{id}` | One entry |
| PATCH | `/logs/{id}` | Edit `ts`, `comment`, `data`, `plant_ids`, `photo_ids`. The `type` is fixed (delete and re-create to change it). `sensor_snapshot` is recomputed when `ts` or the plants change |
| DELETE | `/logs/{id}` | Delete. Milestones are re-derived automatically |

## Photos
| Method | Path | Description |
|---|---|---|
| POST | `/photos` | `multipart/form-data`: `id`, `file` (JPEG), `taken_ts?`. Returns metadata. The server downscales to a maximum of 2560 px and makes a thumbnail |
| GET | `/photos/{id}?thumb=1` | JPEG, sent with `Cache-Control: immutable` |
| GET | `/photos/{id}/meta` | Metadata only |
| DELETE | `/photos/{id}` | `409 photo_in_use` while a log entry or plant references it |

## Reference data
| Method | Path | Description |
|---|---|---|
| GET | `/strains?q=&category=&vendor=&shop_category=&flowering_days=&thc=&limit=&offset=` | Strain catalog (seeded, 414 entries), sorted by name: `{items, total}`. `q` matches name or vendor (case-insensitive substring). The other filters are exact facet values. `limit` defaults to 500 (max 1000) |
| GET | `/strains/facets` | `{category, vendor, shop_category, flowering_days, thc}`: the distinct values of each, for the filter UI |
| GET | `/strains/{id}` | `{id, name, vendor, category, shop_category, thc, cbd, flowering_days, yield_indoor, height, url, image_url, description, custom}`. `custom` = added by the user (every strain in lists carries it too) |
| POST | `/strains` | Add a custom strain (`custom: true`). `409 duplicate_strain` if (name, vendor) exists |
| PATCH | `/strains/{id}` | Correct an entry (only the fields sent change) |
| DELETE | `/strains/{id}` | Custom strains only: `204`; `409 builtin_strain` for catalog entries, `409 strain_in_use` while a plant uses it |
| GET | `/nutrient-lines` | `[{id, brand, description, products: [{id, name, description, unit}]}]` |
| GET | `/feeding-schedules` | `[{id, profile_key, display_name, substrate, custom, weeks}]` (`weeks` = count). Built-in schedules (`custom: false`) come from the seed files and are read-only |
| GET | `/feeding-schedules/{id}` | `{…, weeks: [{week, phase, nutrients{product: amount}, ph{min, max, target}, ec{…}, environment{temp_day{min, max}, temp_night{…}, humidity{…}, light_hours}, notes}]}`. Absent values are omitted or null |
| POST | `/feeding-schedules` | Create a custom schedule: `{display_name, substrate?, weeks: [{phase: Veg\|Flower\|Flush, nutrients?, ph?, ec?, environment?, notes?}]}` (1–52 weeks, numbered by position; amounts ≥ 0, `light_hours` 0–24). `201` with the schedule |
| PUT | `/feeding-schedules/{id}` | Replace a custom schedule (same body). `409 builtin_schedule` for built-in ones |
| DELETE | `/feeding-schedules/{id}` | Delete a custom schedule: `204`, or `409 builtin_schedule` |
| GET | `/phase-ranges` | Per-phase temp/RH/VPD **templates** + `enforce_limits` (used by `from-phases` presets), in growth order |
| PUT | `/phase-ranges/{phase}` | Replace a template: `{enforce_limits, temp_min, temp_max, humidity_min, humidity_max, vpd_min, vpd_max}` |

## Alerts and events
| Method | Path | Description |
|---|---|---|
| GET | `/alerts/active?sensor_id=` | Active alerts: `[{id, sensor_id, sensor_name, metric, level, started_ts, ended_ts, value, band_min, band_max, notified_ts}]`. `sensor_id` is null for system alerts (camera) |
| GET | `/alerts?sensor_id=&from=&to=&limit=&before=` | Alert history by `started_ts`, newest first: `{items, next_before}` |
| GET · PUT | `/alert-settings` | `{debounce_s, warning_debounce_s, repeat_after_s, offline_after_s, battery_low_pct, notify_warning, notify_critical, notify_resolved, language}`. PUT replaces all fields, except that a missing `warning_debounce_s` or `language` keeps its value (older apps). `language` must be one of `/notification-languages`, else `422 unknown_language`. Per-sensor on/off is `PATCH /sensors/{id}`. Rules: DOMAIN.md › Alerts |
| GET | `/notification-languages` | `[{code, name}]`: the languages notification texts exist for (`growbuddy/locales/<code>.json`), e.g. `{code: "da", name: "Dansk"}`. docs/LOCALIZATION.md |
| POST | `/alerts/test` | Sends a test notification. `204`, or `409 ntfy_disabled` / `502 notify_failed` |
| GET | `/push-endpoint` | The app's UnifiedPush endpoint: `{endpoint, environment}` (endpoint null when not registered) |
| PUT | `/push-endpoint` | `{endpoint, environment?}`: an `up…` topic URL on `ntfy.public_url`, else `422 invalid_push_endpoint`; `409 ntfy_disabled`. Alerts then go to that topic as `{type: "alert", title, message, priority, tags, click}` (and fall back to the shared topic if publishing there fails). With `environment: true` the topic also gets `{type: "environment", ts, sensors: [{id, name, online, reading, ranges, levels}]}` every `ntfy.environment_push_s` and right after an alert changes (the app's notification and home screen widgets) |
| DELETE | `/push-endpoint` | Unregister; alerts go to the shared topic again. `204` |
| GET | `/events?level=&source=&limit=&before=` | System event log, newest first: `{items: [{id, ts, level, source, code, message, data}], next_before}`. Sources: `ble`, `camera`, `alerts`, `system` |

## Backup
| Method | Path | Description |
|---|---|---|
| GET | `/backups` | Nightly DB snapshots on the Pi, newest first: `[{name, ts, size_bytes}]` |
| POST | `/backups` | Write today's snapshot now (e.g. before an upgrade). Replaces today's file |
