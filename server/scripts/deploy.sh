#!/usr/bin/env bash
# Copies the server from this checkout to a Pi and installs or updates it there
# (deploy/install.sh). For developers; on the Pi itself, run deploy/install.sh directly.
#   scripts/deploy.sh [user@host]      default: the contents of server/.deploy-host (git-ignored)
set -euo pipefail
cd "$(dirname "$0")/.."
HOST=${1:-$(cat .deploy-host 2> /dev/null || true)}
[[ -n $HOST ]] || { echo "usage: $0 user@host (or put user@host in server/.deploy-host)" >&2; exit 2; }
ssh "$HOST" 'sudo install -d /opt/growbuddy/server && sudo chown -R "$USER" /opt/growbuddy/server'
rsync -a --delete \
    --exclude .venv --exclude .dev --exclude .deploy-host --exclude data --exclude snapshots \
    --exclude config.toml --exclude __pycache__ --exclude .pytest_cache --exclude .mypy_cache \
    --exclude .ruff_cache \
    ./ "$HOST":/opt/growbuddy/server/
ssh "$HOST" 'sudo bash /opt/growbuddy/server/deploy/install.sh'
