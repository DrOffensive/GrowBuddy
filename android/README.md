# GrowBuddy 2: Android app

Native Kotlin + Jetpack Compose (Material 3) client for the GrowBuddy server
([docs/API.md](../docs/API.md)). It replaces the Unity app from 0.1.6.

## Toolchain and build

Built from the terminal; no IDE required (Android Studio can open `android/`
any time). The toolchain lives in your home directory:

| | Location |
|---|---|
| JDK 21 (Temurin) | `~/Android/jdk-21` (AGP needs 17–21; the system JDK is newer) |
| Android SDK | `~/Android/Sdk` (platform 37.0, build-tools, platform-tools) |
| Gradle | wrapper, 9.8.1 (checksum-pinned) |

```sh
cd android
export JAVA_HOME=~/Android/jdk-21 ANDROID_HOME=~/Android/Sdk
./gradlew :app:assembleDebug            # APK in app/build/outputs/apk/debug/
./gradlew :app:installDebug             # to a phone connected over USB (adb)
./gradlew :app:testDebugUnitTest        # JVM unit tests
./gradlew :app:testDebugUnitTest --tests 'com.growbuddy.app.ContractTest'
./gradlew :app:lintDebug                # must stay at zero issues
./gradlew :app:assembleRelease          # signed release APK in app/build/outputs/apk/release/
./gradlew :app:installRelease           # what runs on the phone
```

### Release build

The phone runs the **release** build (`com.growbuddy.app`, R8-shrunk, signed).
The signing key is kept outside the repo, in
`~/.config/growbuddy/growbuddy-release.jks`, with its passwords in
`~/.gradle/gradle.properties` (`GROWBUDDY_RELEASE_STORE_FILE`,
`GROWBUDDY_RELEASE_STORE_PASSWORD`, `GROWBUDDY_RELEASE_KEY_ALIAS`,
`GROWBUDDY_RELEASE_KEY_PASSWORD`). Without them, `assembleRelease` builds an
unsigned APK. **Keep a copy of the key somewhere safe**: an update must be
signed with the same key, or the app has to be uninstalled first, which
loses its settings.

**Publishing:** attach the signed release APK to the GitHub release as
`GrowBuddy-<version>.apk` (docs/INSTALL.md links there). Always sign with the
same key, or users can't update in place.

Debug and release share the package id but not the signing key, so switching
the phone between them needs an uninstall. Bump `versionCode`/`versionName`
in `app/build.gradle.kts` together with the server version and add a
`CHANGELOG.md` entry.

Stack versions are pinned in `gradle/libs.versions.toml`: AGP 9.4, Kotlin 2.4,
Compose BOM 2026.09, compileSdk/targetSdk 37, minSdk 26. AGP 9 compiles Kotlin
itself, so there's no `kotlin-android` plugin.

## Developing without the Pi

Run the server on the laptop with simulated sensors and camera, then connect
the debug app to it over the LAN:

```sh
server/scripts/dev_server.sh            # prints the server URL and API token to type into the app
```

Both debug and release builds allow cleartext HTTP to any host (see Networking).

## Screens and look (0.1.6 design)

The app follows the GrowBuddy 0.1.6 Unity app: the white logo splash, the 0.1.6
sprites (`res/drawable-nodpi/`, taken from the Unity project), Roboto Condensed
text, Roboto Mono numbers, and colours sampled from 0.1.6 screenshots
(`ui/theme/Theme.kt` › `Palette`).

Bottom bar, in 0.1.6 order: **Strains**, **Grow log**, **Home** (raised centre
tab), **Plants**, **Settings**. Sensors live under Settings.

| Screen | Content | Status |
|---|---|---|
| **Splash** | 0.1.6 logo on white (system splash on Android 12+, then the full logo) | ✓ |
| **Dashboard** | "Growcam feed" (MJPEG plus connection globe); tabs per sensor; temperature and humidity dials and the round VPD dial (`ui/common/Dial.kt`, a port of 0.1.6 `DialDisplay.cs`: red bands outside the target, spring needle) with RSSI and battery pills; a line saying where the ranges come from; Active Plants; version. Two columns when unfolded | ✓ |
| **Plants** | 0.1.6 cards (Name / Strain / Age / Phase plus photo or camera placeholder), adaptive grid; **Create Plant** (name, strain search, autoflower, location) | ✓ |
| **Plant detail** | Photo (camera or gallery), strain / type / age / phase, location chips, milestones, the plant's grow log; Add log, Edit, Archive, Terminate early, Delete. Two columns when unfolded | ✓ |
| **Log wizard** | 0.1.6 flow: grouped, coloured type grid, then plants, data (feeding: nutrient line, products and amounts, pH / EC / volume, "Fill from schedule week N"; light height / humidity / temperature targets), then comments, then the summary (type, plants, date/time with Now, details, comments) with **photo upload, max 5 per log** (camera, or multi-select gallery; the server enforces the limit too). After a Harvest, offers to move the plants to another sensor (drying) | ✓ |
| **Strains** | Catalog / Active tabs, filter sheet (category, vendor), search, vendor logos (Barney's Farm, FastBuds), detail sheet with image and shop link. **+** adds your own strain (marked "Mine"; Edit / Delete in its sheet; delete is refused while a plant uses it) | ✓ |
| **History** | Dashboard: tap the dials or "History ›". Sensor tabs; 24 h / 3 d / 7 d / 30 d / 90 d; temperature, humidity and VPD charts (`ui/common/TimeChart.kt`: warning band, dashed critical limits, min–max envelope for hourly data, gaps for missing data, milestone markers of the plants at the sensor, horizontal drag for exact values) | ✓ |
| **Feeding schedule** | Grow log header (calendar) or Settings. Week cards: nutrients (in each product's unit, from the best-matching nutrient line), pH and EC targets with ranges, day/night temperature, RH, light hours, notes. Chips switch between schedules; the choice is remembered (DataStore) and used by the log wizard. Weeks your growing plants are in are outlined and named (`ScheduleWeeks.kt`: Seedling–LateFlower only; past the end on the last week as "Name (week N)"). **+** = new schedule, **Duplicate** any schedule, **Edit** / **Delete** custom ones (built-in ones are read-only). Editor (`ScheduleEditorScreen`): name, substrate, collapsible week cards (phase, nutrients with product suggestions and "+ all <brand>", pH/EC target/min/max, day/night °C, RH, light hours, notes), "Add week" copies the previous one. Grid when unfolded | ✓ |
| **Alerts** | Dashboard banner when alerts are active, or Settings. Tabs: Active (level colour, value vs band, since), History (paged), Events (system log, level filter). Gear: alert settings (warning delay, critical delay, repeat, offline timeout, battery threshold, notify switches, test notification) | ✓ |
| **Timelapse** | Growcam card → "Timelapse ›". Full-size frame with timestamp, slider, play/pause at 2/5/10 fps (next frames preloaded, previous frame shown while loading), filmstrip, 24 h / 7 d / 30 d / All, "capture now" | ✓ |
| **Grow log** | Timeline grouped by day (paged), type colours, photos; entry detail with data, conditions at the time, full-screen photos, editable comment and time, delete | ✓ |
| **Settings** | Server status, Sensors (list, discovery, adopt, detail with auto/manual ranges, presets, retire), test notification, change server | ✓ |

## Architecture

```
AppContainer.kt      Manual DI: SettingsStore, ApiProvider, MjpegStream
data/remote/         Retrofit API, DTOs (mirror docs/API.md), ApiProvider, MjpegStream
data/settings/       DataStore (server URL + token)
data/sync/           Offline outbox (queued creates) + SyncWorker (WorkManager)
ui/<feature>/        Screen + its ViewModel in one file per screen
ui/common/           Formatting, shared components, polling helper
ui/theme/            Palette and Material 3 theme
```

- **Status:** setup, dashboard, sensors, settings, strains, plants, plant
  detail, the log wizard, the grow log, history charts, the feeding schedule
  and alerts and the timelapse viewer are done, and every screen is checked
  on the folded cover screen (904 px wide) as well as unfolded. Offline
  support is done: cached reading everywhere, plus queued plant, log and
  photo creation (see docs/ARCHITECTURE.md › Android app).
- **Writes:** the journal writes with client-generated UUIDs (the wizard keeps
  one id per wizard), so a retried save never creates duplicates. Plant and
  log PATCH bodies are `JsonObject`s, so an explicit `null` clears a field.
- **Polling:** screens poll the server with `ui/common/polling` +
  `stateIn(WhileSubscribed)`, so polling stops when a screen isn't visible.
  The MJPEG stream only runs while the dashboard is started, which lets the
  server release the camera.
- **Errors:** `apiCall {}` turns HTTP and network failures into
  `ApiException(code, message)`, using the server's error codes.
- **Contract tests:** `ContractTest` parses JSON captured from the real
  server (`src/test/resources/fixtures/`). Refresh the fixtures from a running
  dev server when `docs/API.md` changes.
- **Offline:** reads fall back to the HTTP cache (the offline bar shows it).
  Plant/log/photo creation is queued in the outbox when the network fails
  (`apiCall` error code `network`). Never queue PATCH/DELETE without conflict
  handling.
- Writes (plants, logs, photos) generate a UUID on the client and go into
  the outbox when offline, which WorkManager sends. The API treats a repeat
  create with the same id as a no-op, so retries are safe.
- Phase, age, VPD and alarm levels come **from the server**. Don't
  re-implement `docs/DOMAIN.md` in Kotlin.
- Timestamps are `Long` epoch ms UTC in all DTOs and entities. Convert to
  local time only in the UI.

## Networking

- One server URL, entered on first run, e.g. `http://growbuddy:8000` (Tailscale
  MagicDNS, which works at home and away) or `https://growbuddy.<tailnet>.ts.net`.
- `res/xml/network_security_config.xml` allows cleartext HTTP to any host: the
  hostname is each user's choice and Tailscale encrypts the traffic. The API
  token is only attached to requests for the configured host (`ApiProvider`).
- No secrets in resources or `BuildConfig`. The API token lives in DataStore.

## Notifications

Alerts reach the phone through the **ntfy Android app**, which keeps the
connection to ntfy on the Pi (subscribed to `http://<pi>:2586`, topic
`growbuddy`, as the read-only user `phone`).

With **Settings › Notifications › Turn on**, the app registers with ntfy as a
UnifiedPush distributor (`data/push/`, connector library). ntfy then hands
each alert to GrowBuddy, which shows it with its own leaf icon
(`res/drawable/ic_stat_leaf.xml`, channels "Critical alerts" and "Alerts"), so
alerts can't be mistaken for ntfy's own background notification. The endpoint
is stored on the server (`PUT /push-endpoint`), and it must be on the Pi: in
ntfy, set Settings › Default server to `http://<pi>:2586` before turning it
on. Tapping a notification opens the Alerts screen (`growbuddy://alerts`,
handled in `MainActivity`).

### Notification widget

Settings › Notification widget switches on a silent, minimised notification
(channel "Environment widget", no status bar icon) that shows the dashboard
dials for the chosen sensors, with ‹ › to switch between them
(`data/widget/EnvironmentWidget.kt`, layouts `res/layout/widget_*.xml`). The
dials are drawn by `ui/common/Dial.kt`'s `drawDialFace` into bitmaps
(`DialBitmaps`), on a dark card so they read in light and dark shades. Values
arrive as `environment` pushes from the server every 5 minutes and on alert
changes, so GrowBuddy notifications must be on. Opening the app or tapping an
arrow also refreshes them. Swiping isn't possible inside a notification
(swipes dismiss it), hence the arrows. Collapsed, One UI shows only the
title, so the title carries the values as well.

### Home screen widget

A 4×2 home screen widget (`data/widget/HomeWidgetProvider.kt`, layout
`res/layout/widget_home.xml`, `res/xml/home_widget_info.xml`) shows the
dashboard's dial triangle for one sensor, drawn as one transparent bitmap at
the widget's size (`DialBitmaps.renderCluster`: temperature and humidity
cards with the value under the dial's min label, the VPD disc overlapping
their bottom corners; the layout's arrows line up with the cards, the top
46 % of the height). RemoteViews only inflates whitelisted classes (no plain
`View`), so spacers are `FrameLayout`s. It cycles with ‹ › between the sensors ticked in Settings (all
sensors if none are; it keeps its own position, separate from the
notification's). It draws the same stored snapshot, so every push or refresh
updates both. While a home widget is placed, the app registers with
`environment: true` even if the notification widget is off (`onEnabled` /
`onDisabled` re-register). `updatePeriodMillis` (30 min) is only a fallback
fetch. The Quick log button opens `growbuddy://log`, which `MainActivity`
routes to the log wizard; tapping elsewhere opens the app.

## Languages

English (`res/values/strings.xml`) and Danish (`res/values-da/strings.xml`).
No visible text is hard-coded: composables use `stringResource`, other code
`appString()` (`ui/common/AppText.kt`, which unit tests back with
`TestStrings`, reading `values/strings.xml`). AGP's `generateLocaleConfig`
lists the `values-<code>` folders for Android's per-app language setting, and
Settings › Language (Android 13+) offers them through `LocaleManager`, also
setting the server's notification language. `LocalizationTest` checks every
translation against the English keys and placeholders. Adding a language:
[docs/LOCALIZATION.md](../docs/LOCALIZATION.md).

## Confirmations

Every destructive action goes through `ui/common/ConfirmDialog`
(`Confirmation(title, text, confirmLabel) { action }`): deleting, overwriting
saved data (a log entry's time, a plant photo), throwing away unsaved input
(leaving the log wizard, switching its nutrient line, removing a photo),
dismissing rejected sync items, changing server and turning off
notifications. Add one for any new action of that kind.

## Theme (from 0.1.6)

| Token | Hex | Use |
|---|---|---|
| Background | `#0D1A0F` | Screen background |
| Surface | `#1C2E1E` | Cards |
| SurfaceRaised | `#243328` | Inputs, inactive buttons |
| Divider | `#2E4030` | Separators, grid lines |
| TextPrimary | `#E8F0E9` | Main labels |
| TextSecondary | `#8CA98E` | Sub-labels, metadata |
| Green | `#4CAF6F` | Brand / normal |
| Warning | `#F5A623` | Alarm level warning |
| Alert | `#E53935` | Alarm level critical |
| Teal | `#4DD0E1` | Chart accent |

Dark theme by default. Fonts: Roboto for UI, Roboto Mono for numbers (pH, EC, readings).
