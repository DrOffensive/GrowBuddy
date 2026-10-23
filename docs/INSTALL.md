# Installing GrowBuddy

This guide takes you from an empty SD card to the dials and alerts on your
phone. Plan for about an hour, most of it spent waiting for downloads.

Here's how the parts fit together:

```
 BLE hygrometers ──BTHome──▶ Raspberry Pi ◀──Tailscale (encrypted)──▶ Android phone
 USB webcam ─────────USB──▶  GrowBuddy server (:8000)                 GrowBuddy app
                             ntfy push server (:2586)                 ntfy app (delivers alerts)
```

Everything runs on your own Pi. Nothing is exposed to the internet: the phone
reaches the Pi through your private Tailscale network, at home and away.
Notifications come from an ntfy server on the Pi itself, so you don't need an
ntfy.sh account.

> **Legal note:** growing cannabis is illegal in many countries and regions.
> GrowBuddy is a monitoring and journaling tool. Check your local laws; using
> it is your responsibility.

## 1. What you need

| | Notes |
|---|---|
| **Raspberry Pi 4 or newer** (2 GB or more) | It needs onboard Bluetooth and Wi-Fi or Ethernet |
| **Official Pi power supply** (5.1 V / 3 A for the Pi 4) | Phone chargers cause under-voltage, which hangs Bluetooth and powers down the webcam |
| microSD card, 16 GB or more | 32 GB gives room for a year of hourly timelapse frames |
| **USB webcam** (optional) | Any UVC webcam with MJPEG, for example a Logitech C270 |
| **BLE hygrometers** | Xiaomi Mijia LYWSD03MMC (or any other device that sends **unencrypted BTHome v2**), one per area: tent, drying, … |
| Android phone | Android 8 or newer |
| A computer | For flashing the SD card and the sensors (needs Chrome or Edge for Web Bluetooth) |

## 2. Flash the sensors to BTHome

The Xiaomi firmware encrypts its broadcasts. The open-source
[pvvx firmware](https://github.com/pvvx/ATC_MiThermometer) broadcasts
readings openly as BTHome, which GrowBuddy reads. Do this once per sensor:

1. Put a battery in the sensor. In Chrome or Edge, open the
   [Telink flasher](https://pvvx.github.io/ATC_MiThermometer/TelinkMiFlasher.html).
2. Click **Connect** and pick the sensor (`LYWSD03MMC`). Stock sensors need
   **Do Activation** first.
3. Under **Custom Firmware**, choose the latest `ATC_v*.bin` and click
   **Start Flashing**. Wait for the sensor to restart.
4. Reconnect. Under the settings, set **Advertising type** to **BTHome v2**,
   make sure **encryption is off**, and click **Send Config**.

You'll adopt the sensors in the app later (step 8). You don't need to note
their MAC addresses.

## 3. Prepare the Raspberry Pi

1. Install [Raspberry Pi Imager](https://www.raspberrypi.com/software/) on your
   computer and choose **Raspberry Pi OS Lite (64-bit)**.
2. In the customisation settings (the gear icon or **Edit settings**):
   - **Hostname:** `growbuddy`. This guide assumes that name. If you pick
     another one, use it wherever you see `growbuddy` below.
   - **Username and password:** for example `grow`.
   - **Wi-Fi:** your network and country (skip this if you use Ethernet).
   - **Services:** enable SSH (with a public key if you have one).
3. Write the card, put it in the Pi, plug in the webcam and power it up. Give
   it a few minutes for the first boot.
4. From your computer, log in: `ssh grow@growbuddy.local`.

## 4. Tailscale (remote access)

Tailscale puts the Pi and your phone on one private, encrypted network, so
the app works on your home Wi-Fi and on mobile data alike, without opening
any ports on your router. The free Personal plan is enough.

1. Create an account at [tailscale.com](https://tailscale.com) (sign in with
   Google, Microsoft, GitHub or Apple).
2. On the Pi:
   ```sh
   curl -fsSL https://tailscale.com/install.sh | sh
   sudo tailscale up
   ```
   Open the link it prints, sign in, and approve the Pi.
3. In the [admin console](https://login.tailscale.com/admin/machines):
   - Open the Pi's **⋯** menu and choose **Disable key expiry**. Otherwise
     the Pi drops off your tailnet after six months.
   - Check the Pi's name in the list. It's normally `growbuddy`. If Tailscale
     added a suffix (`growbuddy-1`), use that name in the steps below and pass
     it to the installer as `--host`.
   - Under **DNS**, make sure **MagicDNS** is enabled. It's on by default for
     new tailnets.
4. On your phone, install **Tailscale** from the Play Store, sign in with the
   same account and switch it on.

## 5. Install GrowBuddy on the Pi

On the Pi:

```sh
sudo apt install -y git
git clone https://github.com/DrOffensive/GrowBuddy.git
sudo bash GrowBuddy/server/deploy/install.sh
```

This takes a few minutes. The installer:

- installs Bluetooth, the ntfy server and [uv](https://docs.astral.sh/uv/),
  and creates a `growbuddy` system user;
- copies the server to `/opt/growbuddy/server` and its Python dependencies
  into a virtual environment;
- writes `/etc/growbuddy/config.toml` with a fresh API token and finds the webcam;
- creates the ntfy users `growbuddy` (sends) and `phone` (receives) with
  random passwords;
- switches Bluetooth on and Wi-Fi power saving off, and starts everything as
  services that start on boot.

At the end it prints what you need for the phone:

```
  GrowBuddy app   Server URL   http://growbuddy:8000
                  API token    3pV0…

  ntfy app        Server       http://growbuddy:2586
                  Username     phone
                  Password     Xk2…
                  Topic        growbuddy
```

To see this again later, run `sudo /opt/growbuddy/server/deploy/install.sh --info`.

If the Pi's Tailscale name isn't `growbuddy`, run
`sudo bash GrowBuddy/server/deploy/install.sh --host <name>`. Re-running is
always safe.

**Check it:** on your phone, with Tailscale on, open
`http://growbuddy:8000/api/v1/health` in the browser. It should show
`{"status":"ok", …}`.

Settings such as the camera resolution, timelapse interval and retention live
in `/etc/growbuddy/config.toml`. Every key is explained in
[`server/config.example.toml`](../server/config.example.toml). After you
change it, run `sudo systemctl restart growbuddy`. Alert thresholds and
notification settings are changed in the app.

## 6. ntfy (notifications)

GrowBuddy sends alerts (too hot, too dry, sensor offline, low battery)
through **ntfy**, an open-source push service. Its server now runs on your Pi,
and the ntfy Android app keeps a connection to it, so you don't need an
account on ntfy.sh or Google services.

1. Install **ntfy** from the [Play Store](https://play.google.com/store/apps/details?id=io.heckel.ntfy)
   or [F-Droid](https://f-droid.org/packages/io.heckel.ntfy/).
2. In ntfy, open **Settings**:
   - **Default server:** `http://growbuddy:2586`. This matters, because
     GrowBuddy's own notifications are only accepted from your Pi's ntfy.
   - **Manage users → Add user:** service URL `http://growbuddy:2586`,
     username `phone`, and the password from the installer.
3. Back on the main screen, tap **+** and subscribe to the topic `growbuddy`
   (on the default server you just set).
4. Allow ntfy to run in the background: in Android's settings for the ntfy
   app, set battery usage to **Unrestricted**. Otherwise Android may delay
   alerts.

ntfy shows a permanent "listening" notification. You can hide it in ntfy's
notification settings; delivery keeps working.

## 7. The GrowBuddy app

1. Download `GrowBuddy-<version>.apk` from the
   [Releases page](https://github.com/DrOffensive/GrowBuddy/releases) on your phone
   and open it. Android asks you to allow installs from your browser or file
   manager once.
2. Open GrowBuddy and enter the **Server URL** and **API token** from the
   installer, then tap **Connect**.
3. **Settings → Notifications → Turn on**. If Android asks which app should
   deliver push messages, choose **ntfy**. Alerts now arrive as GrowBuddy
   notifications with the leaf icon. Tap **Send test notification** to check.
4. Optional: **Settings → Language** switches between English and Danish
   (Android 13 and newer; older phones follow the phone's language).
   Notifications follow the same language.
5. Optional: in Settings, turn on the **notification widget** (live dials in
   a quiet notification), or add the 4×2 **home screen widget** from your
   launcher.

## 8. Sensors and plants

1. **Settings → Sensors.** Sensors within Bluetooth range of the Pi (about
   10 m, less through walls) show up under **Nearby sensors** within a minute.
   Tap one, give it a name such as "Grow tent", and add it. To tell two
   identical sensors apart, breathe on one (its humidity jumps) or hold it next
   to the Pi (its signal rises).
2. Each sensor gets warning and critical ranges for temperature, humidity and
   VPD. In **auto** mode they follow the growth phase of the oldest active
   plant at that sensor. You can also set them by hand (**manual**).
3. **Plants → Create Plant:** a name, the strain (from the catalog, or add
   your own with **+** on the Strains page) and the location (sensor). Log milestones such as germination,
   flowering or harvest with the **Grow log** wizard. The phase and age are
   worked out from them.

That's it. The dashboard shows the live camera and dials, and the Pi keeps a
timelapse frame every hour.

## 9. Backups (recommended)

Everything lives on the Pi's SD card (`/var/lib/growbuddy`), and SD cards
wear out. The Pi writes a consistent database copy every night (at 03:00), but
it keeps those on the same card. To copy them, the timelapse and your photos
to another computer every day, set up the pull backup in
[backup/README.md](../backup/README.md) (Linux, systemd). Any other tool that
copies `/var/lib/growbuddy/backups/`, `snapshots/` and `photos/` works too.
Never copy the live `growbuddy.db` while the server is running.

## Updating

On the Pi:

```sh
cd GrowBuddy && git pull && sudo bash server/deploy/install.sh
```

Your data, config and tokens are kept. For the app, install the new APK from
the Releases page over the old one. The [CHANGELOG](../CHANGELOG.md) says what
changed.

## Troubleshooting

| Problem | What to check |
|---|---|
| App can't connect | Is Tailscale on (on the phone)? Does `http://growbuddy:8000/api/v1/health` open in the phone's browser? Is the URL `http://`, not `https://`? |
| Name doesn't resolve | Use the Tailscale name (`growbuddy`), not `growbuddy.local`: `.local` names don't resolve while Tailscale's VPN is on. Android's **Private DNS** setting can also override MagicDNS; set it to Automatic or Off, or use the Pi's `100.x.y.z` Tailscale address |
| Server not running | `sudo systemctl status growbuddy`, `journalctl -u growbuddy -e` |
| No sensors found | `rfkill list` (Bluetooth must not be blocked). Is the sensor on BTHome v2 without encryption? Is it within range? Settings in the app shows the Bluetooth state |
| Sensors stop after a while, webcam disappears | Under-voltage: `vcgencmd get_throttled` should print `throttled=0x0`. Use the official power supply |
| "Push endpoint must be an up… topic" when turning on notifications | ntfy's **Default server** isn't `http://growbuddy:2586`. Set it, then turn notifications on again |
| No alerts at all | ntfy logged in as `phone`? Battery usage Unrestricted? In GrowBuddy, Settings → Send test notification |
| Forgot the token or password | `sudo /opt/growbuddy/server/deploy/install.sh --info`. To set a new ntfy password: `sudo -u ntfy ntfy user change-pass phone` |
| No camera image | `ls /dev/v4l/by-id/`, then set `camera.device` in `/etc/growbuddy/config.toml` and restart |
