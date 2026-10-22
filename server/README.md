# GrowBuddy 2: server

FastAPI service for the Raspberry Pi. It ingests BTHome
readings, runs the camera (live stream + hourly timelapse), stores the grow
journal, evaluates alerts (pushed via ntfy) and writes nightly DB snapshots.

## Development (laptop)

Requires [uv](https://docs.astral.sh/uv/).

```sh
uv sync                                   # .venv with deps + dev group
cp config.example.toml config.toml        # set data_dir = "./data", ntfy.enabled = false,
                                          # ble.simulate = true, camera.simulate = true
GROWBUDDY_CONFIG=config.toml uv run uvicorn --factory growbuddy.main:create_app --reload
uv run pytest
uv run pytest tests/test_phase.py::test_name     # single test
uv run ruff check . && uv run ruff format .
uv run mypy src
```

For Android development, `scripts/dev_server.sh` runs the server on the LAN
with simulated sensors and camera (state in `.dev/`), and prints the URL and
token for the app.

On first start the server applies migrations and seeds the reference tables
(strains, nutrient lines, feeding schedule, phase ranges) from
`src/growbuddy/seed/`.

## Deployment (Pi)

Users install from a checkout on the Pi, as described in
[docs/INSTALL.md](../docs/INSTALL.md):
`sudo bash server/deploy/install.sh [--host NAME]`. The script is safe to re-run. It:

- copies `server/` to `/opt/growbuddy/server` (unless it already runs from there);
- installs bluez, uv and ntfy (apt repo; `deploy/ntfy-server.yml` with
  `base-url` set to `--host`, default the hostname or the previously configured
  name) and creates the `growbuddy` user;
- on first install, writes `/etc/growbuddy/config.toml` with a new API token,
  an ntfy write token, `ntfy.public_url`, the webcam's `/dev/v4l/by-id/` path and
  720p, and creates the read-only ntfy user `phone` (its password is kept in
  `/etc/growbuddy/ntfy-phone.txt`, root only);
- runs `uv sync --no-dev --frozen` with the system Python, installs
  `deploy/growbuddy.service`, waits for `/health` and prints the app and ntfy
  setup details (again any time with `--info`).

Logs: `journalctl -u growbuddy -f`.

**From a development machine:** `scripts/deploy.sh user@host` rsyncs this
checkout to the Pi and runs the installer there (needs SSH with passwordless
sudo). Put `user@host` in `server/.deploy-host` (git-ignored) to make it the default.

**Import from GrowBuddy 0.1.6 (one-off):**
`uv run python scripts/import_v016.py <0.1.6 growbuddy.db> "<nickname>" --server http://growbuddy:8000 --token <api token>`
copies one plant with its milestones, feedings and photo. It can be re-run safely.

**Backup user:** see [backup/README.md](../backup/README.md).
