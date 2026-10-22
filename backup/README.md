# GrowBuddy: daily backup (Linux laptop or desktop)

The laptop **pulls** from the Pi. It isn't always on, so a push from the Pi
would often fail. A systemd *user* timer with `Persistent=true` runs the pull
at 04:00, or at the next boot/wake if the laptop was off at that time. If the
Pi can't be reached, the service retries every 15 minutes.

## What gets backed up

| Source on Pi | Laptop destination | Method |
|---|---|---|
| `/var/lib/growbuddy/backups/growbuddy-YYYYMMDD.db` | `~/GrowBuddyBackup/db/` | rsync. Newest DB copy is checked with `PRAGMA integrity_check` |
| `/var/lib/growbuddy/snapshots/` (timelapse) | `~/GrowBuddyBackup/snapshots/` | rsync **without** `--delete`, so the laptop keeps images after the Pi prunes them |
| `/var/lib/growbuddy/photos/` (journal photos) | `~/GrowBuddyBackup/photos/` | rsync, as above |

The live DB file is never copied directly. The Pi writes a consistent copy
every night at `backup.snapshot_time` (03:00) with `VACUUM INTO` (server
`services/snapshot.py`), and only those copies are pulled. A copy is written
as `*.part`, checked with `PRAGMA quick_check` and then renamed. The script
skips `*.part`, so it never pulls a half-written file. To take a snapshot on
demand, use `POST /api/v1/backups`.

DB rotation on the laptop: the newest 30 copies are kept, plus every
first-of-month copy indefinitely.

## Setup

On the Pi, create a read-only backup user (group read access to
`/var/lib/growbuddy`, no password, key login only):

```sh
sudo useradd -m -s /bin/bash -G growbuddy growbuddy-backup
# then put "restrict <laptop key>" in ~growbuddy-backup/.ssh/authorized_keys
```

On the laptop:

```sh
ssh-keygen -t ed25519 -f ~/.ssh/growbuddy_backup -N ''
# ~/.ssh/config: an alias the script uses as PI_HOST
#   Host growbuddy-backup
#       HostName growbuddy           # the Pi's Tailscale name, so it also runs away from home
#       User growbuddy-backup
#       IdentityFile ~/.ssh/growbuddy_backup
#       IdentitiesOnly yes

install -Dm755 growbuddy-backup.sh ~/.local/bin/growbuddy-backup.sh
install -Dm644 growbuddy-backup.conf.example ~/.config/growbuddy-backup.conf   # then edit
install -Dm644 systemd/growbuddy-backup.{service,timer} -t ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now growbuddy-backup.timer
```

To check it:

```sh
systemctl --user start growbuddy-backup.service   # run now
journalctl --user -u growbuddy-backup -e
systemctl --user list-timers growbuddy-backup.timer
cat ~/GrowBuddyBackup/LAST_SUCCESS
```

## Restore

```sh
sudo systemctl stop growbuddy
scp ~/GrowBuddyBackup/db/growbuddy-YYYYMMDD.db growbuddy:/tmp/
# on the Pi:
sudo -u growbuddy cp /tmp/growbuddy-YYYYMMDD.db /var/lib/growbuddy/growbuddy.db
sudo rm -f /var/lib/growbuddy/growbuddy.db-wal /var/lib/growbuddy/growbuddy.db-shm
rsync -a ~/GrowBuddyBackup/{snapshots,photos} growbuddy:/var/lib/growbuddy/   # from laptop (then chown growbuddy on the Pi)
sudo systemctl start growbuddy
```
