# Legacy reference: GrowBuddy 0.1.6

Everything here except this README is git-ignored. The trees are large (a
Unity `Library/` folder) and contain a secret.

| Path | What it is |
|---|---|
| `v0.1.6/GrowBuddy_Logger/` | Unfinished .NET 10 replacement logger (BLE via BlueZ D-Bus, port 5000, no storage). It is **not** the backend the app actually used |
| `unity/GrowBuddy/` | Unity Android app. `Assets/Scripts/` holds the logic, `Assets/StreamingAssets/` the config and reference data |

The 0.1.6 app talked to Pi services that are **not** included here: a logger
on `:8110` (`/health`, `/plants`, `/logs`), a file server on `:8090`
(`sensor.json`, `strains.json`) and mjpg-streamer on `:8080`. Off the LAN it
fell back to a GitHub repo.

What v2 takes from here (already extracted):
- Sensor MAC and BTHome format → `docs/HARDWARE.md`
- Phase/age/VPD/alarm rules → `docs/DOMAIN.md`
- Strains, nutrient lines, feeding schedule, phase ranges → `server/src/growbuddy/seed/`
- Colour palette → `android/README.md`

This is reference only. Never import from it or copy its structure.
`Assets/StreamingAssets/config.json` contains a GitHub token, so never copy
that file anywhere.
