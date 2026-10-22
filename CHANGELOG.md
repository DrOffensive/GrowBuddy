# Changelog

All notable changes to GrowBuddy are listed here, newest first. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions
follow [Semantic Versioning](https://semver.org/). One version number covers the
server, the Android app and the backup together.

## 2.4.0 - 2026-10-23

GrowBuddy in Danish, and room for more languages.

### Android app

- **Version:** 2.4.0 (build 240).
- **Danish:** the whole app is translated: every screen, dialog, widget and
  notification channel, phase and log type names, dates and numbers.
- **Settings › Language:** System default, English or Dansk (Android 13 and
  newer; older phones follow the phone's language). Choosing a language also
  switches the server's notifications to it.
- **Alert settings:** a notification language choice.
- **Translations are files:** each language is one `strings.xml`
  (`res/values-<code>/`); a new one shows up in the language list without
  code changes. See docs/LOCALIZATION.md.

### Server

- **Version:** reports 2.4.0, matching the app.
- **Notification languages:** alerts, "back to normal" and test notifications
  are written in `alerts.language` (English or Danish, with the language's
  decimal comma). The texts live in `growbuddy/locales/<code>.json`; adding a
  language is one file. `GET /notification-languages` lists them, and the
  alert settings carry `language`.
- VPD alerts now say "VPD" instead of "vpd".

## 2.3.0 - 2026-10-22

Your own feeding schedules and strains, a fixed week highlight, and calmer
warnings.

### Android app

- **Version:** 2.3.0 (build 230).
- **Feeding schedule:** pick between schedules with the chips at the top; the
  choice is remembered and the log wizard's "Fill from schedule" uses it.
  **+** creates a schedule, **Duplicate** copies any schedule (also a built-in
  one) to adjust, and your own schedules have **Edit** and **Delete**. The
  editor has a card per week: phase, nutrients (with product suggestions, or a
  whole brand at once), pH and EC, day/night temperature, humidity, light hours
  and notes; "Add week" starts from a copy of the previous week.
- **Fix:** the schedule outlined week 1 for a drying plant (its age restarts at
  the harvest), while a plant in week 11 of a 10-week schedule wasn't shown at
  all. Only growing plants (seedling to late flower) are placed on the
  schedule now, and a plant past its end is shown on the last week as
  "Name (week 11)". The log wizard follows the same rule.
- **Strains:** **+** on the Strains page adds your own strain (name, breeder,
  category, THC/CBD, flowering days, height, yield, notes). Your strains are
  marked "Mine" and can be edited or deleted.
- **Alert settings:** separate fields for the warning delay and the critical
  delay.

### Server

- **Version:** reports 2.3.0, matching the app.
- **Warning delay:** a new warning must now last 30 minutes before it alerts
  (`alerts.warning_debounce_s`, default 1800). Criticals, warning → critical and
  back to normal keep the 10-minute `debounce_s`. Saved alert settings get the
  new default.
- **Custom feeding schedules:** `POST`, `PUT` and `DELETE /feeding-schedules`;
  built-in schedules are read-only (`409 builtin_schedule`). Schedules and
  strains carry a `custom` flag (migration `0003`; strains added earlier
  through the API are marked custom).
- **Custom strains:** `DELETE /strains/{id}` for your own strains (`409
  builtin_strain` / `strain_in_use`).
- **Seed sync:** nutrient lines and built-in feeding schedules from the seed
  files are synced on every start, so new brands and schedules reach existing
  installs with an update. Custom schedules are never touched. The seed file
  format is described in `server/src/growbuddy/seed/README.md`.

## 2.2.0 - 2026-10-22

Ready for other people's grows: nothing in GrowBuddy is tied to one Pi any
more, and there's an installation guide.

### Setup and documentation

- **Installation guide:** [docs/INSTALL.md](docs/INSTALL.md) covers the whole
  setup: hardware, flashing the hygrometers to BTHome, the Pi, Tailscale,
  the installer, the ntfy app, the GrowBuddy app, sensors, backups, updating
  and troubleshooting.
- **Licence:** GrowBuddy is now free software under the GPL-3.0-or-later.
- **Docs:** the README and component docs describe a generic setup (example
  host `growbuddy`) instead of one particular Pi.

### Server

- **Version:** reports 2.2.0, matching the app.
- **Installer:** run it straight from a git checkout on the Pi:
  `sudo bash server/deploy/install.sh`. It copies the server to
  `/opt/growbuddy/server` itself. The name phones use to reach the Pi comes from
  `--host NAME`, or the name configured before, or the Pi's hostname. It goes
  into the ntfy server's `base-url` and `ntfy.public_url`, which were fixed
  before.
- **Setup details:** at the end the installer prints the app's server URL and
  API token and the ntfy server, user, password and topic. `install.sh --info`
  shows them again. On a new install, the ntfy user `phone` gets a random
  password that you can type in, so you don't need to set one by hand.
- **Developer deploy:** `scripts/deploy.sh` needs the target host as an
  argument or in `server/.deploy-host`; there's no built-in default any more.
- The default `ntfy.public_url` is now `http://growbuddy:2586`.

### Android app

- **Version:** 2.2.0 (build 220).
- **Any server name:** the release build connects over plain HTTP to any host,
  not only to one fixed host name. Tailscale encrypts the connection, and the API token is
  still only sent to the configured server.
- **Setup screen:** the server URL field shows an example and says where to
  find the URL.
- DataStore 1.3.0.

## 2.1.1 - 2026-10-19 (release)

The move to a Raspberry Pi 4, and sturdier Bluetooth recovery.

### Android app

- **Version:** 2.1.1 (build 211).
- **Dashboard alert icon:** 35 % smaller, with a smaller count badge; the tap
  area stays the same size.

### Server

- **Version:** reports 2.1.1, matching the app.
- **Pi 4:** GrowBuddy now runs on a Raspberry Pi 4 Model B (2 GB) with the
  official power supply, which ends the under-voltage problems. The Pi 3's data,
  settings, notification users and Tailscale identity were carried over, so the
  app, notifications and backup work unchanged.
- **Bluetooth recovery:** when the Bluetooth chip stops answering, the sensor
  scanner now gives up on the call after 30 seconds (10 s when stopping) and
  retries, instead of waiting forever. On the Pi 3 this left the tent sensor
  without readings for 13 hours.
- **Install script:** works on a brand-new Pi (it waits for ntfy's user
  database before adding users), switches Bluetooth on (fresh images ship it
  blocked) and turns Wi-Fi power saving off.

## 2.1.0 - 2026-10-18 (release)

Widgets: live dials in the notification shade and on the home screen.

### Android app

- **Version:** 2.1.0 (build 210).
- **Strains:** the tabs are now Active and Catalog, in that order, and the page
  opens on Active.
- **Growcam:** tapping the feed on the dashboard opens a full-screen landscape
  live stream. The X in the top right corner returns to the dashboard in its
  previous orientation.
- **Connect screen:** no help text and no preset server URL.
- **Alerts on the dashboard:** the yellow banner is replaced by an icon in the
  header's top right: a yellow warning sign, or a red error sign when any alert
  is critical, with the number of active alerts in a filled circle at its top
  left. Tapping it opens the Alerts screen. It's hidden when there are no alerts.
- **Notification widget:** a silent, minimised GrowBuddy notification (nothing
  in the status bar) shows the dashboard dials for the sensors chosen in
  Settings › Notification widget, with ‹ › to switch between them. Collapsed it
  shows the values in one line. The Pi pushes fresh values every 5 minutes and
  as soon as an alert changes; opening the app or tapping an arrow refreshes
  them too.
- **Home screen widget (4×2):** the dashboard's dial layout on a transparent
  background: temperature and humidity on compact cards (value under the
  dial's lowest mark), the larger round VPD dial between them overlapping
  both, the sensor name and time bottom left and a small quick-log button
  bottom right that opens the log wizard. It shows the sensors chosen in
  Settings (all of them if none are), with ‹ › to switch. It updates with the
  same pushes, which the Pi now also sends while the home widget is placed.
- **Alerts on the Home tab:** the number of active warnings (yellow, top left)
  and criticals (red, top right) is shown on the raised Home tab, on every
  screen.

### Server

- **Version:** reports 2.1.0, matching the app.
- **Environment push:** while one of the app's widgets is on, the server publishes a
  compact environment snapshot to the app's push endpoint every 5 minutes
  (`ntfy.environment_push_s`) and right after an alert starts or ends.

## 2.0.5 - 2026-10-18 (release)

The first release build of GrowBuddy 2.

### Android app

- **Release build:** the app is now a signed release build, package
  `com.growbuddy.app`, which replaces the development build
  (`com.growbuddy.app.debug`). The code is optimised and shrunk with R8, and
  "debug" no longer appears anywhere in the app.
- **About section:** a new "About" card at the bottom of Settings shows the app
  version and build number, the server version, when the app was installed, the
  phone and Android version, and "Developed by MarcWerk".
- **Dashboard:** no longer shows the version number at the bottom.

### Server

- **Version:** reports 2.0.5, matching the app.

## 2.0.0 - 2026-10-18

The complete rebuild of GrowBuddy 0.1.6: a new server, Android app and
backup. It replaces the 0.1.6 .NET logger, Python scripts and Unity app.

### Server (Raspberry Pi)

- **One service** (FastAPI, one process) with an SQLite database (WAL),
  replacing 0.1.6's separate API, BLE listener, mjpg-streamer, nginx, cron jobs
  and GitHub sync. The HTTP API is documented in `docs/API.md` (`/api/v1`).
- **Sensors:** any number of Xiaomi Mijia hygrometers on BTHome v2 (grow tent,
  drying chamber, …), found and added from the app. Every reading is stored,
  repeated packets are dropped, and hourly summaries back the long-range charts.
  The BLE scanner restarts by itself when BlueZ fails or adverts stop.
- **Ranges per sensor:** by default they follow the phase of the oldest active
  plant at that sensor and change by themselves as it grows; they can also be
  set by hand or filled from phase presets.
- **Alerts:** temperature, humidity and VPD outside the warning or critical range,
  sensor offline, low battery and camera failure. A change must last for the
  alert delay before it counts, repeats are limited, and alerts are pushed via a
  self-hosted ntfy server.
- **Camera:** live MJPEG stream (the camera's own JPEGs, no re-encoding) and an
  hourly timelapse. The webcam is opened only while it is needed. A failed hourly
  frame is retried within the hour.
- **Journal:** plants, log entries (milestones, feedings, training, adjustments,
  inspections) with up to 5 photos each, and phase and age derived from the
  milestones with the 0.1.6 rules. Strain catalog (414 entries, cleaned up),
  nutrient lines and a feeding schedule are included.
- **Housekeeping:** hourly rollups, daily retention (raw readings 90 days) and a
  nightly database snapshot for the backup.
- **Deployment:** `server/scripts/deploy.sh` installs or updates the Pi in one
  command (service, ntfy, config with generated secrets). Remote access is
  Tailscale only.
- **Import from 0.1.6:** `server/scripts/import_v016.py` copies a plant with its
  milestones, feedings and photo.

### Android app

- **0.1.6 look:** Kotlin and Jetpack Compose in the 0.1.6 style (logo splash,
  sprites, colours, Roboto fonts, dials), with a five-tab bar and a raised Home
  tab. Two-column layouts when the Fold is unfolded, compact cards on the cover
  screen.
- **Screens:** dashboard (live camera, dials per sensor, active plants),
  sensors (add, ranges, remove), strains, plants and plant detail, log wizard,
  grow log, history charts, feeding schedule, alerts and events, timelapse
  viewer, settings.
- **Age display:** "Week X, Day Y", and "Day N" for the short phases
  (germinating, drying, curing).
- **Offline:** everything already opened stays readable when the Pi can't be
  reached, and new plants, log entries and photos are queued and sent later.
- **Notifications:** alerts appear as GrowBuddy notifications with a cannabis-leaf
  icon (delivered through the ntfy app via UnifiedPush), so they can't be confused
  with ntfy's background notification. Tapping one opens the Alerts screen.
- **Confirmations:** every destructive action asks first. That covers deleting a
  plant, log entry or sensor; terminating a plant; changing a log entry's time;
  replacing a plant photo; discarding or clearing input in the log wizard;
  dismissing rejected changes; changing server; and turning off notifications.
- **API token field:** has a show/hide toggle.

### Backup (laptop)

- **Daily pull:** a systemd user timer copies the nightly database snapshot (checked
  with `integrity_check`), all journal photos and the timelapse to
  `~/GrowBuddyBackup/`, catching up after sleep or shutdown, over Tailscale.
  Images are never deleted on the laptop; database copies are kept for 30 days
  plus the 1st of every month.

### Migration from 0.1.6

- The Pi was cleaned down to the OS plus GrowBuddy, ntfy and Tailscale.
- The 0.1.6 database, captures and configuration are archived on the laptop in
  `~/GrowBuddyBackup/legacy-0.1.6/`.
- The running grow's plants were imported with `scripts/import_v016.py`.
