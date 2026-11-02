# Helpful Potato: Installation and Usage Guide

This guide covers building, installing, and operating Helpful Potato (internal name "Phone Hands"). It describes only commands, options, and endpoints that exist in this repository. For the system design, see [TECHNICAL_DISCLOSURE.md](TECHNICAL_DISCLOSURE.md).

## Contents

1. [Overview](#1-overview)
2. [Requirements](#2-requirements)
3. [Prepare the phone (developer options and debugging)](#3-prepare-the-phone)
4. [Connect the host to the phone over adb](#4-connect-the-host-to-the-phone-over-adb)
5. [Build the app](#5-build-the-app)
6. [Install the app and grant permissions](#6-install-the-app-and-grant-permissions)
7. [Output path A: Bluetooth HID pairing](#7-output-path-a-bluetooth-hid-pairing)
8. [Output path B: CH9329 USB cable](#8-output-path-b-ch9329-usb-cable)
9. [Position the camera](#9-position-the-camera)
10. [Using hid.py (keyboard and mouse)](#10-using-hidpy-keyboard-and-mouse)
11. [Using look.py (camera frames)](#11-using-lookpy-camera-frames)
12. [Calling the HTTP API directly](#12-calling-the-http-api-directly)
13. [Example observe/act session](#13-example-observeact-session)
14. [Troubleshooting](#14-troubleshooting)
15. [Security](#15-security)

## 1. Overview

There are three machines involved:

| Role | Description |
|---|---|
| **Host** | The computer running the agent (or you) and the scripts `hid.py` and `look.py`. Talks to the phone over adb. |
| **Phone** | An unrooted Android phone running the Phone Hands HID app. It acts as the keyboard and mouse for the target, and its rear camera looks at the target's screen. |
| **Target** | The computer being operated. Nothing is installed on it. It sees an ordinary Bluetooth keyboard and mouse, or an ordinary USB keyboard and mouse (CH9329). |

The host and the target can be the same physical computer, but normally they are different.

## 2. Requirements

### 2.1 Host

| Item | Notes |
|---|---|
| Linux or macOS | `look.py` uses `fcntl` file locking, which is not available on native Windows Python. On Windows use WSL. |
| Python 3.9 or newer | Standard library only; no pip packages are needed. |
| `adb` (Android platform-tools) | On `PATH`, or set `ADB=/path/to/adb`. |
| `scrcpy` 2.5 or newer | Needed for `look.py` camera capture. It must support `--video-source=camera` and `--no-window`. On `PATH`, or set `SCRCPY=/path/to/scrcpy`. |
| `ffmpeg` | On `PATH`. Used by `look.py` to extract a still frame. |
| JDK 17 or newer | Only to build the app. Provides `javac` and `keytool`. Set `JAVA_HOME` if it is not on `PATH`. |
| Android SDK | Only to build the app. Needs a **build-tools** package (provides `aapt2`, `d8`, `zipalign`, `apksigner`) and a **platform** package (provides `android.jar`). |

`app/build.sh` defaults to the SDK at `$HOME/android-sdk`, build-tools `37.0.0` and platform `android-37.0`. Override with environment variables:

```bash
export ANDROID_SDK=/path/to/android-sdk      # or ANDROID_HOME
export BUILD_TOOLS_VERSION=37.0.0            # a directory name under $ANDROID_SDK/build-tools/
export PLATFORM=android-37.0                 # a directory name under $ANDROID_SDK/platforms/
export JAVA_HOME=/path/to/jdk                # optional
```

If you use the Android command-line tools, install the components with `sdkmanager`, for example:

```bash
sdkmanager "platform-tools" "build-tools;37.0.0" "platforms;android-37.0"
```

Any reasonably recent build-tools/platform pair works if you set the two variables above to match what you installed. The platform must be API 31 or newer (the app uses API 31+ classes).

### 2.2 Phone

| Item | Notes |
|---|---|
| Android 12 (API 31) or newer | The app's `minSdkVersion` is 31. Android 12+ is also what scrcpy needs for camera capture. |
| Bluetooth with the HID Device profile | Needed for the Bluetooth output path. Most modern phones have it; a few vendors disable it. Developed and tested on a Google Pixel. |
| USB host (OTG) support | Needed only for the CH9329 output path. |
| A stand or mount | To hold the phone so its rear camera sees the target's screen. |

The phone does **not** need to be rooted.

### 2.3 Optional: CH9329 hardware

See [section 8](#8-output-path-b-ch9329-usb-cable).

## 3. Prepare the phone

1. **Enable developer options.** Open *Settings → About phone* and tap *Build number* seven times. Enter your PIN if asked.
2. **Enable USB debugging.** Open *Settings → System → Developer options* (location varies by vendor) and turn on *USB debugging*.
3. **Optional: enable Wireless debugging** in the same menu if you want to connect without a cable. Use it only on a network you trust.
4. Turn on **Bluetooth** if you will use the Bluetooth output path.
5. Recommended: in *Settings → Apps → Phone Hands HID → Battery* choose *Unrestricted* (after the app is installed) so the background service is not stopped.

## 4. Connect the host to the phone over adb

### 4.1 USB

Connect the phone to the host with a USB cable, then:

```bash
adb devices
```

Accept the *Allow USB debugging?* prompt on the phone. The phone should be listed with state `device`. Its first column is the serial.

### 4.2 Wireless debugging

On the phone, open *Developer options → Wireless debugging → Pair device with pairing code*. Then on the host:

```bash
adb pair <phone-address>:<pairing-port>      # enter the 6-digit code shown on the phone
adb connect <phone-address>:<debug-port>     # the address:port shown on the Wireless debugging screen
adb devices
```

A tunnel or VPN to the phone also works; the serial is then whatever `host:port` you connect to.

### 4.3 Tell the scripts which phone to use

All scripts require `PHONE_SERIAL`:

```bash
export PHONE_SERIAL=<serial from adb devices>      # e.g. a USB serial, or <phone-address>:<debug-port>
```

If `PHONE_SERIAL` contains `:` and the device is not listed, the scripts run `adb connect $PHONE_SERIAL` once automatically.

Other optional variables:

| Variable | Default | Meaning |
|---|---|---|
| `ADB` | `adb` on `PATH` | adb binary |
| `SCRCPY` | `scrcpy` | scrcpy binary |
| `PHONE_HANDS_PORT` | `18765` | host port used for `adb forward` to the phone's port 8765 |
| `LOOK_CAMERA` | `0` | default camera id for `look.py` |

## 5. Build the app

From the repository root:

```bash
app/build.sh
```

This runs `aapt2` (resources), `javac` (Java 17), `d8` (dex, min API 31), `zipalign` and `apksigner`, with no Gradle. Output:

```
app/build/phone-hands-hid.apk
```

On first build it creates a throwaway signing key at `app/debug.keystore` (password `android`). To use your own key, set `KEYSTORE=/path/to/key.keystore` (the script signs with alias `debug` and password `android`, so a custom keystore must match those or you must edit the script). Never commit a keystore; `.gitignore` excludes them.

Optional unit tests for the CH9329 frame encoder (host JVM only, no phone needed):

```bash
app/test/run.sh          # prints "ALL 15 TESTS PASSED" on success
```

## 6. Install the app and grant permissions

```bash
adb -s $PHONE_SERIAL install -r app/build/phone-hands-hid.apk

# Runtime permissions (Bluetooth and notifications)
for p in BLUETOOTH_CONNECT BLUETOOTH_ADVERTISE BLUETOOTH_SCAN POST_NOTIFICATIONS; do
  adb -s $PHONE_SERIAL shell pm grant com.phonehands.hid android.permission.$p
done

# "Display over other apps", used for the red CAMERA ON banner
adb -s $PHONE_SERIAL shell appops set com.phonehands.hid SYSTEM_ALERT_WINDOW allow

# Start the app (this starts the foreground service and the local API)
adb -s $PHONE_SERIAL shell am start -n com.phonehands.hid/.MainActivity
```

Instead of `pm grant` you can open the app and accept the permission prompts. The overlay permission can also be granted in *Settings → Apps → Phone Hands HID → Display over other apps*.

After launch the phone shows a persistent notification *Phone Hands HID: Bluetooth keyboard/mouse ready; API on 127.0.0.1:8765*. The service also starts automatically after the phone reboots.

Check from the host:

```bash
./hid.py status
```

`hid.py` sets up `adb forward tcp:18765 tcp:8765` automatically and, if the API is unreachable, launches the app with `am start` and retries once.

Note: reinstalling with `install -r` restarts the app and drops any Bluetooth connection. Run `./hid.py status` afterwards and reconnect if needed.

## 7. Output path A: Bluetooth HID pairing

The app registers itself with Android's `BluetoothHidDevice` profile as a combined keyboard and mouse named "Phone Hands". `./hid.py status` should show `"registered": true`. If it does not, run `./hid.py register` or tap *Re-register HID app* in the app.

### 7.1 Make the phone discoverable

```bash
./hid.py discoverable                 # default 300 seconds
./hid.py discoverable --duration 120
```

A system dialog appears on the phone. **Someone must tap Allow.** You can also tap *Make discoverable (5 min)* in the app.

### 7.2 Pair from the target computer

- **Windows:** *Settings → Bluetooth & devices → Add device → Bluetooth*, select the phone.
- **macOS:** *System Settings → Bluetooth*, click *Connect* next to the phone.
- **Linux (BlueZ):**
  ```
  bluetoothctl
  scan on
  pair <phone-bluetooth-address>
  trust <phone-bluetooth-address>
  connect <phone-bluetooth-address>
  ```

Confirm the pairing code on both devices if asked.

### 7.3 Confirm the connection

```bash
./hid.py status
```

Look for `"connectionState": "connected"` and a `host` entry. If the target is bonded but not connected, connect from the phone side using the address listed under `bonded`:

```bash
./hid.py connect <target-bluetooth-address>
./hid.py disconnect          # drop the current host
```

The app remembers the last connected host and reconnects to it automatically when it re-registers (for example after a restart).

### 7.4 Test

```bash
./hid.py type 'hello'
```

Text should appear in whatever field has focus on the target.

## 8. Output path B: CH9329 USB cable

Status: **implemented and unit-tested at the protocol level; not yet verified on physical CH9329 hardware.**

The CH9329 is a WCH chip that converts serial (UART) commands into USB keyboard and mouse input. The target sees a standard USB HID keyboard and mouse. Unlike Bluetooth it needs no pairing, works in BIOS/UEFI and pre-boot screens, and supports absolute mouse positioning.

### 8.1 Parts

- A CH9329 module whose USB side plugs into the **target**. Some products combine a USB-serial chip and a CH9329 in one cable with a USB plug at each end; those need no wiring.
- If the module exposes only UART pins: a USB-to-serial (USB-TTL) adapter using a chip supported by the app: CH340, CH341, CH9102, CP210x, FTDI (FT232R, FT230X, FT231X), or Prolific PL2303.
- A USB OTG adapter for the phone (for example USB-C male to USB-A female).

### 8.2 Wiring (separate module + USB-TTL adapter)

| USB-TTL adapter | CH9329 module |
|---|---|
| TX | RX |
| RX | TX |
| GND | GND |

- Power the CH9329 module from the target's USB port (its USB side). Do not also connect the adapter's VCC pin unless the module's documentation says to; feeding power from two sources can damage hardware.
- Match logic levels (3.3 V or 5 V) to what your module accepts; check its documentation or jumper.
- Keep the CH9329 in its default serial-protocol (command) mode. The app expects the chip's default address `0x00`.

Signal path: **Phone USB-C → OTG adapter → USB-TTL adapter → (TX/RX/GND) → CH9329 → USB → Target**.

### 8.3 Use it

1. Plug the assembly into the phone. Android asks whether to open Phone Hands HID for the USB device; tick *Always* and tap *OK*. (If the app was already running it may instead ask for USB permission; tap *OK*.)
2. Check from the host:
   ```bash
   ./hid.py backend        # shows activeBackend, backendMode, ready, backends.ch9329.ready
   ./hid.py chinfo         # queries the chip: chipVersion, usbEnumerated, numLock/capsLock/scrollLock
   ```
   `usbEnumerated: true` means the target has accepted the CH9329 as a USB device.
3. Baud rate: the app opens the adapter at **9600 8N1** by default (the CH9329 factory default). If your module was configured for another rate:
   ```bash
   ./hid.py backend ch9329 --baud 115200
   ```
   The setting is remembered.
4. Backend selection:
   ```bash
   ./hid.py backend auto        # default: CH9329 when its adapter is open, otherwise Bluetooth
   ./hid.py backend ch9329      # force CH9329
   ./hid.py backend bluetooth   # force Bluetooth
   ./hid.py backend --reopen    # close and reopen the USB-serial adapter
   ./hid.py backend --no-ack    # don't wait for chip replies (faster; less diagnostics)
   ```
5. Absolute positioning (CH9329 only):
   ```bash
   ./hid.py moveto 0.5 0.5           # center of the screen (fractions 0..1)
   ./hid.py moveto 4095 0 --raw      # top-right corner in raw chip units 0..4095
   ```

Power note: while the phone's USB-C port is in OTG host mode it usually cannot charge. Use a powered OTG hub or splitter for long sessions.

## 9. Position the camera

1. Mount the phone so the **rear camera** faces the target's monitor and the whole screen is in frame.
2. Reduce glare: avoid lights reflecting off the screen; a slight angle often helps.
3. Take a test frame and check it:
   ```bash
   ./look.py -o /tmp/test.jpg
   ```
4. If the image is sideways or upside down (common when the phone is in portrait or mounted inverted), rotate the saved image:
   ```bash
   ./look.py -o /tmp/test.jpg --rotate cw     # 90° clockwise
   ./look.py -o /tmp/test.jpg --rotate ccw    # 90° counter-clockwise
   ./look.py -o /tmp/test.jpg --rotate 180
   ```
   Use whichever value makes the screen appear upright, and keep using it.
5. If text is too small to read, move the phone closer, or try another resolution supported by your camera:
   ```bash
   ./look.py --list-cameras            # lists camera ids and supported sizes
   ./look.py --camera-id 0 --size 1280x720
   ```

While the camera is in use the phone shows a high-priority *Camera ON - remote viewing* notification and a red banner (the banner requires the overlay permission from section 6).

## 10. Using hid.py (keyboard and mouse)

Every command prints a JSON response and exits non-zero on error.

### 10.1 Status and setup

```bash
./hid.py status                       # backend, registration, connection, bonded devices, camera state, recent log
./hid.py register                     # re-register the Bluetooth HID app
./hid.py discoverable [--duration N]
./hid.py connect <target-bluetooth-address>
./hid.py disconnect
./hid.py backend [auto|bluetooth|ch9329] [--baud N] [--reopen] [--no-ack]
./hid.py chinfo
```

### 10.2 Typing

```bash
./hid.py type 'Hello, World!\n'       # \n = Enter, \t = Tab
./hid.py type 'C:\new' --raw          # don't interpret \n / \t escapes
echo 'from stdin' | ./hid.py type -   # '-' reads text from stdin (escapes not interpreted)
./hid.py type 'slow' --delay 30       # ms per key press and per release (default 12)
```

Typing uses a **US QWERTY** layout and US-ASCII characters. Characters that cannot be mapped are skipped and reported in the response field `skipped`. If the target uses another keyboard layout, symbols will come out wrong.

### 10.3 Keys and shortcuts

```bash
./hid.py key enter
./hid.py key win+r
./hid.py key ctrl+alt+del
./hid.py key "ctrl+a ctrl+c" alt+tab        # a space-separated sequence
./hid.py key f5 esc up down pageup home end delete backspace tab space printscreen menu
./hid.py key ctrl+shift+esc --hold 60 --gap 80   # ms held / ms between steps (defaults 40 / 40)
```

Modifiers: `ctrl`, `shift`, `alt` (`option`), `win` (`cmd`, `gui`, `meta`, `super`), and right-hand variants `rctrl`, `rshift`, `ralt` (`altgr`), `rwin`. Keys: letters, digits, punctuation, `f1`–`f24`, arrows, `enter`, `esc`, `tab`, `space`, `backspace`, `delete`, `insert`, `home`, `end`, `pageup`, `pagedown`, `capslock`, `numlock`, `scrolllock`, `printscreen`, `pause`, `menu`, `kp0`–`kp9`, `kpenter`, `mute`, `volumeup`, `volumedown`, or a raw HID usage like `0x2c`. Up to six non-modifier keys per combination.

### 10.4 Mouse

```bash
./hid.py move 300 -120            # relative, in mouse counts (sent in steps of at most ±127)
./hid.py move 300 0 --delay 15    # ms between steps (default 8)
./hid.py moveto 0.25 0.75         # absolute, fractions of the screen (CH9329 only)
./hid.py click                    # left click
./hid.py click --button right
./hid.py click --double
./hid.py click --count 3
./hid.py down                     # press and hold (default left)
./hid.py up
./hid.py drag 300 0               # down, relative move, up
./hid.py scroll 5                 # positive = up
./hid.py scroll -5                # negative = down
./hid.py release                  # release all keys and mouse buttons
```

Relative mouse movement is subject to the target's pointer acceleration, so `move 500 0` does not move exactly 500 pixels. Take a frame with `look.py`, see where the pointer landed, and correct. On Windows, turning off *Enhance pointer precision* makes movement more predictable. The Bluetooth path has no absolute positioning; a common technique is to first move far toward a corner (for example `move -3000 -3000`) to reach a known position, then move relative to it.

## 11. Using look.py (camera frames)

```bash
./look.py                              # saves frames/look-<timestamp>.jpg and prints its path
./look.py -o /tmp/screen.jpg           # explicit output path
./look.py --rotate cw|ccw|180
./look.py --camera-id 1                # 0 = back (default), 1 = front, or as listed
./look.py --size 1280x720              # camera capture size (default 1920x1080)
./look.py --seconds 3                  # clip length before the last frame is kept (default 2.5)
./look.py --quality 5                  # JPEG quality as ffmpeg -q:v (2 = best, default)
./look.py --screen -o /tmp/ui.jpg      # screenshot of the phone's own screen (for debugging the app UI)
./look.py --list-cameras
```

How it works: `look.py` turns on the phone's camera indicator, records a short headless clip with `scrcpy --video-source=camera`, extracts the last frame with `ffmpeg` (so exposure and focus have settled), turns the indicator off, and prints the absolute path. Each frame takes about 6 seconds. A lock file (`/tmp/phone-hands-look.lock`) prevents two captures from running at once. The `frames/` directory is git-ignored.

## 12. Calling the HTTP API directly

The app serves a small HTTP API on the phone at `127.0.0.1:8765` (loopback only). After `hid.py` has created the forward (or after `adb -s $PHONE_SERIAL forward tcp:18765 tcp:8765`), the host can call it directly. Parameters may be passed in the query string, as a JSON body, or form-encoded.

```bash
curl -s http://127.0.0.1:18765/status
curl -s -X POST -H 'Content-Type: application/json' -d '{"text":"hi\n"}' http://127.0.0.1:18765/type
curl -s 'http://127.0.0.1:18765/key?combo=ctrl%2Bl'
curl -s 'http://127.0.0.1:18765/moveto?fx=0.5&fy=0.5'
curl -s 'http://127.0.0.1:18765/backend?addr=0'          # CH9329 chip address (default 0)
curl -s 'http://127.0.0.1:18765/camera/on?by=my-client'  # then /camera/heartbeat at least every 15 s, then /camera/off
```

Endpoints: `/status`, `/discoverable`, `/connect`, `/disconnect`, `/register`, `/type`, `/key`, `/move`, `/moveto`, `/click`, `/down`, `/up`, `/scroll`, `/release`, `/backend`, `/chinfo`, `/camera/on`, `/camera/heartbeat`, `/camera/off`, `/camera`. See the README for parameters. HTTP 400 means a bad request; HTTP 409 means the output path is not ready (for example "bluetooth: no connected host").

## 13. Example observe/act session

A minimal loop as an agent (or a person) would run it. Each step is: look, decide, act, look again.

```bash
export PHONE_SERIAL=<serial>
./hid.py status                          # confirm "ready": true
./look.py -o /tmp/s0.jpg --rotate cw     # observe: what is on the target screen?
./hid.py key win+r                       # act: open the Windows Run dialog
./look.py -o /tmp/s1.jpg --rotate cw     # observe: did the dialog open?
./hid.py type 'notepad\n'                # act
./look.py -o /tmp/s2.jpg --rotate cw     # observe: is Notepad focused?
./hid.py type 'Typed by Helpful Potato.\n'
./look.py -o /tmp/s3.jpg --rotate cw     # verify the result
```

An AI agent runs the same commands as tools: it reads each image (for example with a vision-capable model), decides the next keyboard or mouse action, and issues it with `hid.py`.

## 14. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `set PHONE_SERIAL to your phone's adb serial` | Export `PHONE_SERIAL` (section 4.3). |
| `device ... is 'unauthorized'` | Accept the USB debugging prompt on the phone; if none appears, revoke authorizations in Developer options and reconnect. |
| `phone ... not connected via adb` | Check the cable or `adb connect <address>:<port>`; wireless debugging ports change after toggling. |
| `API unreachable` | The service is not running. `hid.py` already tries `am start` once; open the app manually and check that permissions were granted. Set battery usage to *Unrestricted*. |
| HTTP 409 `bluetooth: no connected host` | The phone is not connected to a target. Run `./hid.py status`, then `./hid.py connect <address>` or pair again. |
| `"registered": false` | Bluetooth is off, or the HID registration was lost. Turn Bluetooth on, then `./hid.py register`. |
| Windows shows the phone as *Paired* but it never works as a keyboard, or drops at once | Stale bond. On Windows *Remove device*; on the phone *Forget* the computer; toggle Bluetooth off and on on both; pair again while `registered` is `true`. |
| Typed symbols are wrong | The target is not using a US keyboard layout. Switch the target's layout to US, or type only characters that map identically. |
| Pointer lands in the wrong place | Pointer acceleration (Bluetooth / relative mode). Correct iteratively using frames, disable acceleration on the target, or use CH9329 with `moveto`. |
| Cannot type at BIOS, disk-unlock or some login screens over Bluetooth | The target's Bluetooth stack is not running yet. Use the CH9329 path. |
| `scrcpy camera capture failed` | Check scrcpy is 2.5+ (`scrcpy --version`), the phone is Android 12+, no other app holds the camera, and `./look.py --list-cameras` works. |
| scrcpy restarts adb and the phone disappears | Use one adb. `look.py` passes `ADB` to scrcpy; set `ADB` to the same adb binary you use elsewhere. |
| No red banner while capturing | Grant the overlay permission (section 6). `./hid.py status` → `camera.overlayPermission`. The notification still appears without it. |
| Images are blurry, dark or full of glare | Improve lighting, angle, and distance; try `--seconds 4` to let focus settle; try another `--size`. |
| CH9329: `no USB-serial adapter found` | The phone does not see a supported adapter: check the OTG adapter, cable, and that the phone supports USB host mode. |
| CH9329: `USB permission requested` | Tap *OK* on the phone's USB permission dialog, then `./hid.py backend --reopen`. |
| CH9329: `acksMissing` keeps increasing, `chinfo` reply is null | Wrong baud rate (try `--baud 115200`), TX/RX swapped, missing ground, or module not in protocol mode. |
| CH9329: `usbEnumerated: false` | The CH9329's USB side is not plugged into the target or not powered. |
| Phone battery drains in CH9329 mode | OTG host mode prevents charging; use a powered OTG hub. |

The app's own status screen and `./hid.py status` (`log` field) show the most recent internal events.

## 15. Security

**The API has no authentication.** Anyone who can reach it can type and click on the target computer.

- The app binds to `127.0.0.1` on the phone only. The intended access path is `adb forward` from a host you trust. Do not change the bind address, do not forward the port to `0.0.0.0` or a network interface, and do not expose it through a proxy or tunnel.
- adb access to the phone is equivalent to full control of the phone **and** of the target computer. Enable wireless debugging only on trusted networks and revoke adb authorizations you no longer use.
- An AI agent with these tools can do anything a person at the keyboard can do, including deleting files and sending messages. Supervise it, and do not use it on computers you are not authorized to operate.
- The camera sees whatever is in front of it. Point it only at the target screen and tell people nearby. Captured frames stay on the host under `frames/` (git-ignored); review them before sharing.
- `./hid.py status` output includes Bluetooth names and addresses of bonded devices. Treat it as private.
- Unpair the phone from target computers when you are finished.
