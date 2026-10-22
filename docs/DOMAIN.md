# Grow domain rules

These rules were carried over from GrowBuddy 0.1.6 (`Plant.cs`, `VPDRanges.cs`,
`LogWizard.cs`). In v2 the **server** computes everything here, and the app
only displays the results. Unit tests in `server/tests/` lock each rule in.

## Plants and milestones

A plant has a nickname, an optional strain (from the catalog), an `is_autoflower`
flag and an optional thumbnail photo.

**Milestones aren't stored separately.** A plant's milestone of type T is
the timestamp of its **earliest** log entry of type T. The milestone types are:

`Germination`, `Sprout`, `FlowerFlip`, `Harvest`, `Trim`, `TerminatedEarly`

(In 0.1.6 the app wrote milestones to the plant record itself, separately from
the log, and the two could drift apart. Deriving them from the log fixes that,
and deleting a log entry reverts its milestone.)

## Phase derivation

Phase is evaluated at time `t` (default: now). Only milestones at or before
`t` count. The look-ahead rules (flower thirds, compressed veg) may use later
milestones, which makes historical evaluation for chart phase bands correct.
In 0.1.6 the thirds rule could never trigger, because it never filtered by
`t`. Milestones are checked in this order, and the first match wins:

1. `TerminatedEarly` exists → **TerminatedEarly**
2. `Trim` exists → **Curing** if `t < trim + 28 d`, else **Complete**
3. `Harvest` exists → **Drying**
4. `FlowerFlip` exists, with `e = days since flip`:
   - If `Harvest` is after the flip (historical evaluation), the span
     `flip → harvest` is split into thirds: **EarlyFlower** / **MidFlower** / **LateFlower**
   - Otherwise: `e ≤ 14` **EarlyFlower**, `e ≤ 42` **MidFlower**, else **LateFlower**
5. `Sprout` exists, with `e = days since sprout`:
   - If `FlowerFlip` came less than 28 days after sprout, veg is compressed:
     `e < vegDays/2` **Seedling**, else **EarlyVegetation**
   - Otherwise: `e ≤ 14` **Seedling**, `e ≤ 28` **EarlyVegetation**, else **LateVegetation**
6. `Germination` exists → **Germinating**
7. None of the above → **PreGermination**

## Age (week/day)

Age is shown as "Week W, Day D", where `W = days/7 + 1` and `D = days%7 + 1`.
The API returns `{week, day}`. The app shows the short phases (Germinating,
Drying, Curing) as a day count instead: "Day N" with `N = days + 1`.
It counts from the most recent applicable anchor:

| Latest milestone | Counts from | Notes |
|---|---|---|
| TerminatedEarly | n/a | No age |
| Trim | Trim | Stops after 28 days (Complete) |
| Harvest | Harvest | |
| Sprout | Sprout | Covers veg **and** flower |
| Germination | Germination | |
| none | n/a | No age |

## Sensors, locations and ranges

There can be any number of sensors (e.g. grow tent, drying chamber). A sensor
starts as **discovered**: the scanner has heard its BTHome adverts in the last
few minutes, but nothing is stored for it. The user **adopts** it in the app
by giving it a name. Only adopted sensors get readings, alerts and ranges.
Removing a sensor sets it to `retired`, keeps its history and stops recording.

A plant has an optional **location**: the sensor of the space it's in
(`plants.sensor_id`). The location decides which sensor's reading a log
entry gets, and where the plant appears on the dashboard. Moving a plant,
for example from tent to drying chamber after Harvest, means changing its
location. When a Harvest entry is saved, the app offers to make that move.

### Range modes

Each sensor has a `range_mode`:

- **`auto`** (the default): the sensor's ranges are the phase-template preset
  (see Phase presets below) for the current phase of the **oldest active plant
  located at the sensor**. "Oldest" means the earliest start: the plant's
  first milestone (normally Germination), or its creation time if it has no
  milestones yet. Ties are broken by id. Archived plants and plants in
  `Complete` or `TerminatedEarly` don't count. The ranges are recomputed on
  every read, so they follow the plant through its phases, and a plant moved
  to the drying chamber after Harvest switches that sensor to the Drying
  template. With no eligible plant, or a phase whose template has
  `enforce_limits = false`, nothing is checked.
- **`manual`**: the stored ranges below. Saving ranges (`PUT
  /sensors/{id}/ranges`) or applying a preset switches the sensor to manual.
  Switching to manual with nothing stored seeds the stored ranges from the
  current automatic ones. Switching back to auto keeps the stored ranges for
  later.

### Ranges and alarm levels

Each adopted sensor has its effective ranges (from its mode). For each metric
(temperature °C, humidity %RH, VPD kPa) there are four optional bounds:
`warn_min`, `warn_max`, `crit_min` and `crit_max`. Evaluation per metric:

- **Critical:** the value is below `crit_min` or above `crit_max`.
- **Warning:** otherwise, the value is below `warn_min` or above `warn_max`.
- **Normal:** otherwise. A bound left empty isn't checked, and a sensor
  with no bounds never raises range alerts.

The warn band is the "target" band shown in the UI.

### Phase presets

`phase_ranges` (seeded from 0.1.6) holds a temperature, humidity and VPD
range for each phase. They are **templates only**: nothing evaluates them
directly. In the app, "Fill from phase(s)" copies them into a sensor's
ranges. For the selected phases, this keeps the 0.1.6 multi-plant semantics:

- **warn** = the *intersection* (highest min … lowest max). If it's empty,
  both bounds become the midpoint of the conflicting values (the 0.1.6
  `GetTightestRange` rule).
- **crit** = the *union* (lowest min … highest max), widened by a margin of
  2 °C, 5 %RH or 0.2 kPa. Without the margin, a single phase would have
  identical warn and crit bands, so a warning could never come before a
  critical alert.

Phases with `enforce_limits = false` are ignored by the preset. The preset
is computed by the server (`POST /sensors/{id}/ranges/from-phases`, and in
`auto` mode with the driving plant's single phase), so the app doesn't
contain this logic.

## Alerts

The evaluator (`alerts/`) runs every `alerts.evaluate_every_s` (60 s). It
observes one level per key (sensor, metric) for every active sensor that has
`alerts_enabled`:

| Metric | Level | Delay | Notes |
|---|---|---|---|
| `temperature`, `humidity`, `vpd` | warning / critical, from the sensor's ranges and latest reading | `warning_debounce_s` (1800) for a new warning (from normal); `debounce_s` (600) for critical, warning ↔ critical and back to normal | Skipped while the sensor is offline, since its values are stale |
| `sensor_offline` | critical when not seen for `offline_after_s` (300); the adoption time counts if never seen | immediate | |
| `battery_low` | warning at ≤ `battery_low_pct` (15) | immediate | Clears only above threshold + 5 % (hysteresis) |
| `camera` (system, no sensor) | warning while the camera service is `failed` | immediate | Only when the camera is enabled |

- **State machine:** a new level must persist for the delay before it takes
  effect, and flapping restarts the clock. Moving between warning and critical
  closes the old alert row and opens a new one. Each row is one episode at one
  level, and `started_ts` is when the condition began, not when it was
  detected.
- **Notifications:** sent when an alert opens (warning or critical, per the
  `notify_warning` / `notify_critical` settings), repeated as "Still: …" every
  `repeat_after_s` (2 h) while it stays active, and on resolve (per
  `notify_resolved`). ntfy priorities are critical 5, warning 4 and resolved
  3. Each transition is also written to `events`.
- **Delivery:** notifications go through an outbox that's retried every
  tick. Messages older than 1 h are dropped. A failure is recorded once as a
  `notify_failed` event.
- **Restarts and edits:** active alerts are re-read from the DB each tick, so
  a restart never re-notifies. Retiring a sensor or switching its alerts off
  closes its alerts without notifying.
- **Settings:** the delays, thresholds and notify switches above are
  runtime-editable (`PUT /alert-settings`), with config `[alerts]` as
  defaults. `/environment/current` uses the same `offline_after_s`.

## VPD

Leaf temperature is assumed equal to air temperature (no leaf offset, as in 0.1.6):

```
SVP (kPa) = 0.6108 × exp(17.27 × T / (T + 237.3))
VPD (kPa) = (1 − RH/100) × SVP
```

VPD is calculated when needed, never stored.

## Log entries

One log entry can apply to **several plants** (e.g. one feeding for the whole
tent). Every entry has: `ts` (defaults to now, can be backdated), `type`,
`comment`, attached photos (**at most 5**), and a type-specific `data` object:

| Type | `data` fields |
|---|---|
| `Feeding` | `ph`, `ec`, `volume_l`, `nutrients: [{brand, product, amount, unit}]` |
| `LightAdjust` | `light_height_cm` |
| `HumidityAdjust` | `humidity_target_pct` |
| `TemperatureAdjust` | `temperature_target_c` |
| `Germination`, `Sprout`, `FlowerFlip`, `Harvest`, `Trim`, `TerminatedEarly` | none (milestones) |
| `Inspection`, `Topping`, `LST`, `Pruning`, `Cure` | none |

When the server creates an entry, it attaches a `sensor_snapshot`: for each
distinct location among the entry's plants, the reading from that sensor
nearest to `ts` (within 10 min). Plants with no location get none. This replaces 0.1.6's separate
`AutoLog` entries, which are dropped in v2 because readings are now logged
continuously.

## Feeding schedules and nutrient lines

A **nutrient line** is a brand (BioBizz, CANNA) with products, each with a
unit (e.g. `ml/L`). A **feeding schedule** gives per-week nutrient doses
(product name → ml/L), pH/EC min/max/target, day/night temperature, humidity
and light hours, with phases Veg/Flower/Flush. **Built-in** schedules ship in
the seed files (BioBizz coco, 10 weeks, and more brands over time) and are
read-only. **Custom** schedules are made in the app, from scratch or by
duplicating another one. The app uses the chosen schedule to pre-fill a
Feeding entry and to show "this week's targets".

**Schedule week of a plant:** a plant is on a schedule only while it's
growing: phase Seedling, EarlyVegetation, LateVegetation, EarlyFlower,
MidFlower or LateFlower. Its schedule week is then its age week (counted from
Sprout). Germinating, drying, curing and finished plants aren't on a schedule;
their age counts from a different milestone. A plant past the schedule's last
week stays on the last week. Product names in a schedule are matched to a
nutrient line's products ignoring case and anything but letters
("BioGrow" = "Bio-Grow").
