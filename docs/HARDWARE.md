# Hardware notes

## Raspberry Pi

- Developed on a Raspberry Pi 4 Model B (2 GB RAM, onboard Bluetooth
  CYW43455 on the UART, `hci0`, shared with Wi-Fi) running Raspberry Pi OS Lite
  (Debian 13 trixie, 64-bit) on Wi-Fi. A Pi 3 Model B (1 GB) also works.
- Referred to by name: the hostname (`growbuddy` in [INSTALL.md](INSTALL.md))
  via Tailscale MagicDNS, or `<hostname>.local` via mDNS on the LAN only. The
  LAN address comes from DHCP.
- Runs: growbuddy (:8000), ntfy (:2586), sshd, tailscaled. Nothing else is
  installed on top of the OS.
- **Power:** use the official 5.1 V / 3 A USB-C supply. A fast-charging phone
  charger is not enough: Pis report under-voltage on one
  (`vcgencmd get_throttled` = `0x50005`), which also powers the webcam down and
  hangs the Bluetooth chip. With the official supply it reads `0x0`.
- **Fresh image setup:** the image is configured on first boot by cloud-init
  (`user-data`, `network-config` on the boot partition). It ships with Bluetooth
  soft-blocked (rfkill) and Wi-Fi power saving on; `deploy/install.sh` unblocks
  Bluetooth and turns power saving off (it made the Pi drop off Wi-Fi for a
  minute at a time).
- Avoid re-encoding video: the camera passes MJPEG through, and only thumbnails
  and the occasional snapshot are encoded.

## Mijia hygrometers: BTHome v2

Sensors aren't configured on the server. They are found and adopted in the app
(`GET /sensors/discovered`, then `POST /sensors`). Any BTHome v2 device
within range of the Pi works.

Tested with the Xiaomi Mijia **LYWSD03MMC** on the
[pvvx firmware](https://github.com/pvvx/ATC_MiThermometer), advertising
BTHome v2 **unencrypted** (device info byte `0x40`). Flashing steps are in
[INSTALL.md](INSTALL.md#2-flash-the-sensors-to-bthome). Other devices that send
unencrypted BTHome v2 temperature/humidity should work too.

The sensors only broadcast and never need a connection. bleak runs a normal
BlueZ discovery scan, and the server never connects to a device. To tell two identical sensors apart in discovery, breathe on one
(its humidity jumps) or move it close to the Pi (its RSSI rises).

**Range:** every sensor (e.g. a drying chamber) needs to be within BLE range of the Pi (about
10 m, less through walls). If it's too far away, a second Pi or an ESPHome
Bluetooth proxy can relay adverts. That isn't part of v2 yet.

- Service data is under UUID **`0xFCD2`** (`0000fcd2-0000-1000-8000-00805f9b34fb`).
- Byte 0 is the device info byte: bit 0 = encrypted, bit 2 = trigger-based,
  bits 5–7 = version (2). Payloads whose version isn't 2 are rejected.
  Encrypted payloads are logged and skipped. The sensors run unencrypted, and
  bindkey support can be added later if needed.
- The remaining bytes are `object_id` + value pairs, little-endian:

| Object id | Meaning | Type | Factor |
|---|---|---|---|
| `0x00` | Packet id | uint8 | 1 (dedup) |
| `0x01` | Battery | uint8 | 1 % |
| `0x02` | Temperature | sint16 | 0.01 °C |
| `0x03` | Humidity | uint16 | 0.01 % |
| `0x0C` | Voltage | uint16 | 0.001 V |

- Stop parsing at an **unknown object id**, because its length is unknown.
  (0.1.6 did the same, but it also didn't recognize `0x00` and `0x0C`, so it
  lost the battery and voltage values.)
- **Dedup:** the same measurement is repeated across several adverts with the
  same packet id. Store a reading only when the packet id changes for that MAC.
- RSSI comes from the advertisement, not the payload. The app shows it as
  signal strength, mapping −100 dBm (dead) to −30 dBm (perfect).

## USB webcam

- V4L2 device, configurable. Prefer the stable `/dev/v4l/by-id/...` path over `/dev/video0`.
- Request MJPEG from the camera (`CAP_PROP_FOURCC = MJPG`). The live stream
  then relays the camera's own JPEG frames, which keeps CPU low on the Pi.
- Owned exclusively by the camera service (ARCHITECTURE.md). It replaces
  mjpg-streamer from 0.1.6.
- Cheap webcams give dark or stale first frames after opening, so
  `warmup_frames` are discarded before a stored snapshot.
- Timelapse: one frame per hour (`camera.interval_s = 3600`).
