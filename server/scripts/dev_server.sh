#!/usr/bin/env bash
# Run the server on this machine with simulated sensors and camera, reachable from a
# phone on the same LAN. Data lives in server/.dev/ (git-ignored); delete it to reset.
set -euo pipefail
cd "$(dirname "$0")/.."
DEV=.dev
mkdir -p "$DEV"
if [[ ! -f "$DEV/config.toml" ]]; then
    token=$(python3 -c "import secrets; print(secrets.token_urlsafe(24))")
    cat > "$DEV/config.toml" <<EOF
[server]
api_token = "$token"
data_dir = "$PWD/$DEV/data"

[ble]
simulate = true

[camera]
simulate = true

[ntfy]
enabled = false
EOF
fi
token=$(sed -n 's/^api_token = "\(.*\)"/\1/p' "$DEV/config.toml")
ip=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for (i = 1; i < NF; i++) if ($i == "src") print $(i + 1)}')
echo "GrowBuddy dev server (simulated hardware)"
echo "  Server URL: http://${ip:-<this-machine>}:8000"
echo "  API token:  $token"
echo
if [[ -x .venv/bin/uvicorn ]]; then run=(.venv/bin/uvicorn); else run=(uv run uvicorn); fi
GROWBUDDY_CONFIG="$DEV/config.toml" exec "${run[@]}" --factory growbuddy.main:create_app --host 0.0.0.0 --port 8000
