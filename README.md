# GrowBuddy

A self-hosted grow monitor and grow journal for a Raspberry Pi and an Android
phone.

- **Environment:** continuous temperature, humidity and VPD from any number of
  cheap BLE hygrometers (grow tent, drying chamber, …), each with its own
  ranges and push alerts, plus a live camera with an hourly timelapse.
- **Journal:** plants, strains (414-entry catalog), feedings, training,
  milestones and photos. Phase and age are worked out from the milestones.
- **On your phone:** dashboard dials, history charts, a feeding schedule, a
  notification widget and a home screen widget. It works offline: entries made
  without a connection are sent later.
- **Languages:** English and Danish, app and notifications. Adding a language
  is one translated file each ([docs/LOCALIZATION.md](docs/LOCALIZATION.md)).

Your data stays on your own hardware. Remote access goes through your own
Tailscale network, and notifications come from an ntfy server on the Pi.
No cloud services and no open ports.

> **Legal note:** growing cannabis is illegal in many countries and regions.
> GrowBuddy is a monitoring and journaling tool. Check your local laws; using
> it is your responsibility.

## Getting started

You need a Raspberry Pi 4 (or 3) with its official power supply, Xiaomi
LYWSD03MMC hygrometers flashed to BTHome, optionally a USB webcam, and an
Android phone. **[docs/INSTALL.md](docs/INSTALL.md)** walks through
everything: flashing the sensors, the Pi, Tailscale, the one-command
installer, ntfy, the app and backups.

The short version, on a Pi running Raspberry Pi OS Lite with Tailscale:

```sh
sudo apt install -y git
git clone https://github.com/DrOffensive/GrowBuddy.git
sudo bash GrowBuddy/server/deploy/install.sh
```

Then install the APK from [Releases](https://github.com/DrOffensive/GrowBuddy/releases)
and enter the server URL and token that the installer prints.

## Components

| Part | Location | Runs on | Stack |
|---|---|---|---|
| Server API | [`server/`](server/) | Raspberry Pi 4 B / 3 B | Python 3.11+, FastAPI, SQLite (WAL), bleak, OpenCV, ntfy |
| Android app | [`android/`](android/) | Phone (Android 8+) | Kotlin, Jetpack Compose, Material 3 |
| Daily backup | [`backup/`](backup/) | A Linux computer | rsync over SSH + systemd user timer |

## Documentation

- [docs/INSTALL.md](docs/INSTALL.md): installation and setup guide
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): system design, process model, networking
- [docs/LOCALIZATION.md](docs/LOCALIZATION.md): languages and how to add a translation
- [docs/DOMAIN.md](docs/DOMAIN.md): phase/age rules, alarm levels, VPD, log types
- [docs/API.md](docs/API.md): HTTP API contract (`/api/v1`)
- [docs/DATA_MODEL.md](docs/DATA_MODEL.md): SQLite schema and retention
- [docs/HARDWARE.md](docs/HARDWARE.md): BTHome decoding, webcam, Pi
- [server/README.md](server/README.md): server development and deployment
- [android/README.md](android/README.md): building the app
- [backup/README.md](backup/README.md): backup and restore
- [CHANGELOG.md](CHANGELOG.md): what changed in each version

## Disclaimer

GrowBuddy is an independent hobby project. It is not affiliated with, endorsed
by or sponsored by any of the nutrient brands, breeders, seed shops or other
companies named in the app or in this repository. Product and brand names are
trademarks of their owners and are used only to identify their products.

The built-in feeding schedules are suggestions only. Always check the nutrient
maker's official feeding chart and the health of your plants first.

## Licence

GrowBuddy is free software under the [GNU General Public License v3.0 or
later](LICENSE). The strain catalog in `server/src/growbuddy/seed/strains.json`
was collected from public retailer listings; strain names, descriptions,
images and breeder logos belong to their respective owners.
