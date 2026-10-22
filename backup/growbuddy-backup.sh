#!/usr/bin/env bash
# Pull GrowBuddy backups from the Pi to this laptop. Safe to run repeatedly.
#   - DB: nightly snapshots (VACUUM INTO copies) from <data_dir>/backups/
#   - Images: <data_dir>/snapshots/ (timelapse) and <data_dir>/photos/ (journal)
#     mirrored WITHOUT --delete (laptop keeps images the Pi has pruned)
# Config: ~/.config/growbuddy-backup.conf (see growbuddy-backup.conf.example)
set -euo pipefail

CONF="${GROWBUDDY_BACKUP_CONF:-$HOME/.config/growbuddy-backup.conf}"
# shellcheck source=/dev/null
[[ -f "$CONF" ]] && source "$CONF"

: "${PI_HOST:?set PI_HOST in $CONF}"            # e.g. growbuddy-backup (~/.ssh/config alias)
: "${PI_DATA_DIR:=/var/lib/growbuddy}"
: "${DEST:=$HOME/GrowBuddyBackup}"
: "${KEEP_DAILY:=30}"                           # newest N DB copies kept; 1st-of-month kept forever

log() { printf '%s %s\n' "$(date -Is)" "$*"; }

if ! ssh -o BatchMode=yes -o ConnectTimeout=10 "$PI_HOST" true 2>/dev/null; then
    log "Pi unreachable ($PI_HOST); exiting non-zero so the timer retries"
    exit 75
fi

mkdir -p "$DEST/db" "$DEST/snapshots" "$DEST/photos"

log "Pulling DB snapshots"
rsync -a --partial --exclude "*.part" "$PI_HOST:$PI_DATA_DIR/backups/" "$DEST/db/"

log "Pulling images"
rsync -a --partial "$PI_HOST:$PI_DATA_DIR/snapshots/" "$DEST/snapshots/"
rsync -a --partial "$PI_HOST:$PI_DATA_DIR/photos/" "$DEST/photos/"

latest="$(ls -1 "$DEST"/db/growbuddy-*.db 2>/dev/null | sort | tail -n1 || true)"
if [[ -z "$latest" ]]; then
    log "No DB snapshot found on Pi yet"
    exit 1
fi
result="$(sqlite3 "file:$latest?mode=ro" 'PRAGMA integrity_check;')"
if [[ "$result" != "ok" ]]; then
    log "INTEGRITY CHECK FAILED for $latest: $result"
    exit 1
fi
log "Verified $latest"

# Rotation: keep newest KEEP_DAILY plus every growbuddy-YYYYMM01.db
# (grep exits 1 when nothing is left to delete; that's not an error)
ls -1 "$DEST"/db/growbuddy-*.db | sort | head -n -"$KEEP_DAILY" \
    | { grep -v -E 'growbuddy-[0-9]{6}01\.db$' || true; } | xargs -r rm -f --

date -Is > "$DEST/LAST_SUCCESS"
log "Backup complete"
