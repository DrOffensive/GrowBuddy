#!/usr/bin/env bash
# Installs or updates GrowBuddy on a Raspberry Pi (Raspberry Pi OS / Debian, as root).
#
#   sudo server/deploy/install.sh [--host NAME]   install or update, then print the setup details
#   sudo server/deploy/install.sh --info          only print the setup details again
#
# NAME is how phones reach the Pi: its Tailscale MagicDNS name (default: this
# machine's hostname, or the name chosen on an earlier run). Safe to re-run:
# existing config, tokens and data are kept. Run it from a git checkout or from
# /opt/growbuddy/server (where scripts/deploy.sh puts it); the code is copied there.
set -euo pipefail
SRC=$(cd "$(dirname "$0")/.." && pwd)
APP=/opt/growbuddy
CONF=/etc/growbuddy/config.toml
PHONE_CREDS=/etc/growbuddy/ntfy-phone.txt   # root-only; lets --info show the phone login again
export DEBIAN_FRONTEND=noninteractive

host=${GROWBUDDY_HOST:-}
info_only=false
while (($#)); do
    case $1 in
        --host) host=${2:?--host needs a name}; shift 2 ;;
        --info) info_only=true; shift ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done
[[ $EUID -eq 0 ]] || { echo "Run as root: sudo $0" >&2; exit 1; }

conf_value() {  # conf_value KEY: first `KEY = "value"` in the config
    sed -n "s/^$1 = \"\([^\"]*\)\".*/\1/p" "$CONF" | head -1
}

print_info() {
    local h phone_user=phone phone_pass
    h=$(conf_value public_url | sed -E 's|^https?://||; s|:[0-9]+/?$||')
    phone_pass=$(cat "$PHONE_CREDS" 2> /dev/null || echo "(not stored; set one with: sudo -u ntfy ntfy user change-pass phone)")
    cat << EOF

GrowBuddy $(sed -n 's/^version = "\(.*\)"/\1/p' "$APP/server/pyproject.toml") is running on this Pi.

  GrowBuddy app   Server URL   http://$h:8000
                  API token    $(conf_value api_token)

  ntfy app        Server       http://$h:2586
                  Username     $phone_user
                  Password     $phone_pass
                  Topic        $(conf_value topic)

Phones reach the Pi through Tailscale as "$h". If its MagicDNS name differs
(see the Tailscale admin console), re-run with --host <name>.
Show this again: sudo $APP/server/deploy/install.sh --info
EOF
}

if $info_only; then
    [[ -f $CONF ]] || { echo "GrowBuddy isn't installed yet." >&2; exit 1; }
    print_info
    exit 0
fi

# --- the code lives in /opt/growbuddy/server
if [[ $SRC != "$APP/server" ]]; then
    command -v rsync > /dev/null || apt-get install -y -q rsync > /dev/null
    install -d "$APP/server"
    rsync -a --delete \
        --exclude .venv --exclude .dev --exclude data --exclude snapshots --exclude config.toml \
        --exclude __pycache__ --exclude .pytest_cache --exclude .mypy_cache --exclude .ruff_cache \
        "$SRC"/ "$APP/server/"
fi
cd "$APP/server"

# --- the name phones use: --host, else the one already configured, else the hostname
if [[ -z $host && -f $CONF ]]; then
    host=$(conf_value public_url | sed -E 's|^https?://||; s|:[0-9]+/?$||')
fi
host=${host:-$(hostname -s)}
[[ $host =~ ^[A-Za-z0-9.-]+$ ]] || { echo "invalid host name: $host" >&2; exit 2; }

# --- base packages, service user, uv
apt-get install -y -q bluez sqlite3 v4l-utils curl > /dev/null
id growbuddy &> /dev/null || useradd -r -d "$APP" -s /usr/sbin/nologin -G video,bluetooth growbuddy
# Fresh Raspberry Pi OS images ship Bluetooth soft-blocked; the sensors need it.
for r in /sys/class/rfkill/rfkill*; do
    if [[ $(cat "$r/type" 2> /dev/null) == bluetooth ]]; then echo 0 > "$r/soft"; fi
done
# Wi-Fi power saving makes the Pi drop off the network for a minute at a time.
if [[ -d /etc/NetworkManager/conf.d ]]; then
    printf '[connection]\nwifi.powersave = 2\n' > /etc/NetworkManager/conf.d/90-growbuddy-wifi-powersave.conf
fi
if ! command -v uv > /dev/null; then
    curl -LsSf https://astral.sh/uv/install.sh | env UV_INSTALL_DIR=/usr/local/bin INSTALLER_NO_MODIFY_PATH=1 sh
fi

# --- ntfy (push notifications), users and tokens on first install
if ! command -v ntfy > /dev/null; then
    install -d /etc/apt/keyrings
    curl -fsSL -o /etc/apt/keyrings/ntfy.gpg https://archive.ntfy.sh/apt/keyring.gpg
    echo "deb [signed-by=/etc/apt/keyrings/ntfy.gpg] https://archive.ntfy.sh/apt stable main" \
        > /etc/apt/sources.list.d/ntfy.list
    apt-get update -q > /dev/null
    apt-get install -y -q ntfy > /dev/null
fi
sed "s|^base-url: .*|base-url: \"http://$host:2586\"|" deploy/ntfy-server.yml > /etc/ntfy/server.yml
chmod 644 /etc/ntfy/server.yml
systemctl enable -q ntfy
systemctl restart ntfy
# On a fresh install ntfy creates its user database at startup; wait for it before adding users.
for _ in $(seq 30); do [[ -f /var/lib/ntfy/user.db ]] && break; sleep 1; done
add_ntfy_user() {  # add_ntfy_user USER ACCESS PASSWORD: creates the user on first run
    local user=$1 access=$2 password=$3
    if ! sudo -u ntfy ntfy user list 2>&1 | grep -q "^user $user "; then
        sudo -u ntfy NTFY_PASSWORD="$password" ntfy user add "$user" > /dev/null
        sudo -u ntfy ntfy access "$user" growbuddy "$access" > /dev/null
    fi
}

# --- config (secrets live only here)
if [[ ! -f $CONF ]]; then
    install -d -m750 -g growbuddy /etc/growbuddy
    add_ntfy_user growbuddy write-only "$(head -c 24 /dev/urandom | base64)"
    # Assigned first so a failing token add stops the script instead of writing an empty token.
    ntfy_token=$(sudo -u ntfy ntfy token add growbuddy 2>&1 | grep -o 'tk_[A-Za-z0-9]*')
    sed -e "s|^api_token = .*|api_token = \"$(head -c 32 /dev/urandom | base64 | tr -d '/+=')\"|" \
        -e "s|^token = \"tk_change-me\".*|token = \"$ntfy_token\"|" \
        -e "s|^public_url = .*|public_url = \"http://$host:2586\"|" \
        -e "s|^width = 1920|width = 1280|" -e "s|^height = 1080|height = 720|" \
        config.example.toml > "$CONF"
    by_id=$(ls /dev/v4l/by-id/*-video-index0 2> /dev/null | head -1 || true)
    [[ -n $by_id ]] && sed -i "s|^device = .*|device = \"$by_id\"|" "$CONF"
    chown root:growbuddy "$CONF"
    chmod 640 "$CONF"
    # The phone's read-only login for the ntfy app: short enough to type.
    phone_pass=$(head -c 12 /dev/urandom | base64 | tr -d '/+=')
    add_ntfy_user phone read-only "$phone_pass"
    (umask 077 && echo "$phone_pass" > "$PHONE_CREDS")
elif grep -q '^public_url = ' "$CONF"; then
    sed -i "s|^public_url = .*|public_url = \"http://$host:2586\"|" "$CONF"
else  # configs written before public_url existed (2.0.x): add it to [ntfy]
    sed -i "/^\[ntfy\]/a public_url = \"http://$host:2586\"" "$CONF"
fi
# App push (UnifiedPush): alerts go to private up… topics that the ntfy app hands to GrowBuddy
sudo -u ntfy ntfy access growbuddy 'up*' write-only > /dev/null
sudo -u ntfy ntfy access phone 'up*' read-only > /dev/null

# --- app
chown -R growbuddy:growbuddy "$APP"
sudo -u growbuddy env UV_PYTHON_PREFERENCE=only-system UV_PYTHON_DOWNLOADS=never \
    uv sync --no-dev --frozen --quiet
install -m644 deploy/growbuddy.service /etc/systemd/system/growbuddy.service
systemctl daemon-reload
systemctl enable -q growbuddy
systemctl restart growbuddy
for _ in $(seq 60); do
    if curl -sf http://127.0.0.1:8000/api/v1/health > /dev/null; then
        print_info
        exit 0
    fi
    sleep 2
done
journalctl -u growbuddy -n 30 --no-pager
exit 1
