# Helpful Potato: Installation and Usage Guide

So you want to give a phone a job. Nice. This is the long version: building the app, getting it on the phone, pairing, pointing the camera, and letting an AI loose (carefully). Everything here is a command, flag or endpoint that actually exists in this repo; if you spot one that doesn't, that's a bug, please open an issue. Internally everything is still called "Phone Hands", so don't be surprised when you see that name. If you want the formal design write-up instead, that's [TECHNICAL_DISCLOSURE.md](TECHNICAL_DISCLOSURE.md).

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
16. [The agent loop (agent.py) and AI providers](#16-the-agent-loop-agentpy-and-ai-providers)

## 1. Overview

Three machines are in on this:

| Role | What it is |
|---|---|
| **Host** | The computer where the agent (or you) runs `hid.py` and `look.py`. It talks to the phone over adb. |
| **Phone** | An unrooted Android phone running the Phone Hands HID app. It's the keyboard and mouse for the target, and its rear camera watches the target's screen. |
| **Target** | The computer getting operated on. You install nothing on it. It just sees a regular Bluetooth keyboard and mouse, or a regular USB keyboard and mouse (CH9329). |

Host and target can be the same box if you really want, but usually they're two different computers.

## 2. Requirements

### 2.1 Host

| Item | Notes |
|---|---|
| Linux or macOS | `look.py` locks files with `fcntl`, which native Windows Python doesn't have. On Windows, use WSL. |
| Python 3.9 or newer | Standard library only, no pip installs. The exceptions are optional: `boto3` for the `aws-bedrock` brain and `google-auth` for `google-vertex` (section 16). |
| `adb` (Android platform-tools) | On `PATH`, or set `ADB=/path/to/adb`. |
| `scrcpy` 2.5 or newer | `look.py` needs it to grab camera frames. It has to support `--video-source=camera` and `--no-window`. On `PATH`, or set `SCRCPY=/path/to/scrcpy`. |
| `ffmpeg` | On `PATH`. `look.py` uses it to pull a still frame out of the clip. |
| JDK 17 or newer | Only for building the app. Gives you `javac` and `keytool`. Set `JAVA_HOME` if it's not on `PATH`. |
| Android SDK | Only for building the app. You need a **build-tools** package (`aapt2`, `d8`, `zipalign`, `apksigner`) and a **platform** package (`android.jar`). |

By default `app/build.sh` looks for the SDK at `$HOME/android-sdk`, with build-tools `37.0.0` and platform `android-37.0`. Point it elsewhere with environment variables:

```bash
export ANDROID_SDK=/path/to/android-sdk      # or ANDROID_HOME
export BUILD_TOOLS_VERSION=37.0.0            # a directory name under $ANDROID_SDK/build-tools/
export PLATFORM=android-37.0                 # a directory name under $ANDROID_SDK/platforms/
export JAVA_HOME=/path/to/jdk                # optional
```

If you're using the Android command-line tools, `sdkmanager` will fetch everything:

```bash
sdkmanager "platform-tools" "build-tools;37.0.0" "platforms;android-37.0"
```

You don't have to use those exact versions. Any reasonably recent build-tools/platform pair is fine as long as you set the two variables to match. The platform has to be API 31 or newer, because the app uses API 31+ classes.

### 2.2 Phone

| Item | Notes |
|---|---|
| Android 12 (API 31) or newer | The app's `minSdkVersion` is 31. scrcpy also needs Android 12+ for camera capture. |
| Bluetooth with the HID Device profile | For the Bluetooth output path. Most modern phones have it, though a few vendors switch it off. I developed and tested on a Google Pixel. |
| USB host (OTG) support | Only for the CH9329 output path. |
| A stand or mount | Something to hold the phone so the rear camera can see the target's screen. A pile of books counts. |

No root required.

### 2.3 Optional: CH9329 hardware

That's all in [section 8](#8-output-path-b-ch9329-usb-cable).

## 3. Prepare the phone

1. **Turn on developer options.** Go to *Settings → About phone* and tap *Build number* seven times (yes, really). Enter your PIN if it asks.
2. **Turn on USB debugging.** It's in *Settings → System → Developer options*, give or take, since every vendor hides it somewhere slightly different. Flip *USB debugging* on.
3. **Optional: Wireless debugging.** Same menu, if you'd rather skip the cable. Only on a network you trust.
4. Turn on **Bluetooth** if you're going the Bluetooth route.
5. Once the app is installed, go to *Settings → Apps → Phone Hands HID → Battery* and pick *Unrestricted*. Otherwise Android may decide your background service is lazy and kill it.

## 4. Connect the host to the phone over adb

### 4.1 USB

Plug the phone into the host and run:

```bash
adb devices
```

Tap Allow on the *Allow USB debugging?* prompt on the phone. You should see the phone listed as `device`, and the first column is its serial.

### 4.2 Wireless debugging

On the phone, open *Developer options → Wireless debugging → Pair device with pairing code*. Then on the host:

```bash
adb pair <phone-address>:<pairing-port>      # enter the 6-digit code shown on the phone
adb connect <phone-address>:<debug-port>     # the address:port shown on the Wireless debugging screen
adb devices
```

A tunnel or VPN to the phone works too. Your serial is then just whatever `host:port` you connected to.

### 4.3 Tell the scripts which phone to use

Every script wants `PHONE_SERIAL`:

```bash
export PHONE_SERIAL=<serial from adb devices>      # e.g. a USB serial, or <phone-address>:<debug-port>
```

If `PHONE_SERIAL` has a `:` in it and adb doesn't list the device, the scripts run `adb connect $PHONE_SERIAL` once for you.

A few more you can set if you need to:

| Variable | Default | Meaning |
|---|---|---|
| `ADB` | `adb` on `PATH` | adb binary |
| `SCRCPY` | `scrcpy` | scrcpy binary |
| `PHONE_HANDS_PORT` | `18765` | host port used for `adb forward` to the phone's port 8765 |
| `LOOK_CAMERA` | `0` | default camera id for `look.py` |

## 5. Build the app

From the repo root:

```bash
app/build.sh
```

No Gradle here. The script calls `aapt2` (resources), `javac` (Java 17), `d8` (dex, min API 31), `zipalign` and `apksigner` directly, and you end up with:

```
app/build/phone-hands-hid.apk
```

The first build makes a throwaway signing key at `app/debug.keystore` (password `android`). Want your own key? Set `KEYSTORE=/path/to/key.keystore`. The script signs with alias `debug` and password `android`, so your keystore has to match those, or you'll need to edit the script. Never commit a keystore; `.gitignore` already keeps them out.

There are also unit tests for the CH9329 frame encoder. They run on the host JVM, no phone needed:

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

Don't feel like typing `pm grant`? Open the app and accept the permission prompts instead. You can grant the overlay permission by hand too, under *Settings → Apps → Phone Hands HID → Display over other apps*.

Once it's running, the phone shows a persistent notification: *Phone Hands HID: Bluetooth keyboard/mouse ready; API on 127.0.0.1:8765*. The service comes back by itself after a reboot.

Check it from the host:

```bash
./hid.py status
```

`hid.py` sets up `adb forward tcp:18765 tcp:8765` for you. If the API doesn't answer, it launches the app with `am start` and tries once more.

One annoyance: reinstalling with `install -r` restarts the app, which drops any Bluetooth connection. Run `./hid.py status` afterwards and reconnect if you have to.

## 7. Output path A: Bluetooth HID pairing

The app registers with Android's `BluetoothHidDevice` profile as a combo keyboard + mouse called "Phone Hands". `./hid.py status` should show `"registered": true`. If it doesn't, run `./hid.py register` or tap *Re-register HID app* in the app.

### 7.1 Make the phone discoverable

```bash
./hid.py discoverable                 # default 300 seconds
./hid.py discoverable --duration 120
```

A system dialog pops up on the phone, and **a human has to tap Allow**. Android won't let software do that part. Tapping *Make discoverable (5 min)* in the app does the same thing.

### 7.2 Pair from the target computer

- **Windows:** *Settings → Bluetooth & devices → Add device → Bluetooth*, pick the phone.
- **macOS:** *System Settings → Bluetooth*, click *Connect* next to the phone.
- **Linux (BlueZ):**
  ```
  bluetoothctl
  scan on
  pair <phone-bluetooth-address>
  trust <phone-bluetooth-address>
  connect <phone-bluetooth-address>
  ```

If both sides show a pairing code, confirm it on both.

### 7.3 Confirm the connection

```bash
./hid.py status
```

You're looking for `"connectionState": "connected"` and a `host` entry. If the target shows up as bonded but isn't connected, connect from the phone's side using the address under `bonded`:

```bash
./hid.py connect <target-bluetooth-address>
./hid.py disconnect          # drop the current host
```

The app remembers the last host it was connected to and reconnects automatically whenever it re-registers, like after a restart.

### 7.4 Test

```bash
./hid.py type 'hello'
```

"hello" should show up in whatever has focus on the target. If it does, high five.

## 8. Output path B: CH9329 USB cable

Fair warning: this path is **implemented and unit-tested at the protocol level, but I haven't run it on a physical CH9329 yet.** If you have one, I'd love to hear how it goes.

The CH9329 is a little WCH chip that turns serial (UART) commands into USB keyboard and mouse input, so the target sees a completely standard USB HID keyboard and mouse. Compared to Bluetooth, you skip pairing, it works in BIOS/UEFI and pre-boot screens, and you get absolute mouse positioning.

### 8.1 Parts

- A CH9329 module, with its USB side plugged into the **target**. Some products put a USB-serial chip and a CH9329 into one cable with a USB plug on each end. Those need zero wiring.
- If your module only has UART pins, you'll also need a USB-to-serial (USB-TTL) adapter with a chip the app supports: CH340, CH341, CH9102, CP210x, FTDI (FT232R, FT230X, FT231X), or Prolific PL2303.
- A USB OTG adapter for the phone (say, USB-C male to USB-A female).

### 8.2 Wiring (separate module + USB-TTL adapter)

| USB-TTL adapter | CH9329 module |
|---|---|
| TX | RX |
| RX | TX |
| GND | GND |

TX goes to RX and RX goes to TX. Everybody gets this wrong once.

Power the CH9329 from the target's USB port (its USB side). Leave the adapter's VCC pin disconnected unless your module's docs tell you otherwise, because feeding it from two power sources can fry things. Match the logic level (3.3 V or 5 V) to what the module accepts; its docs or a jumper will tell you. Leave the CH9329 in its default serial-protocol (command) mode, since the app expects the chip's default address `0x00`.

The whole chain: **Phone USB-C → OTG adapter → USB-TTL adapter → (TX/RX/GND) → CH9329 → USB → Target**.

### 8.3 Use it

1. Plug the whole contraption into the phone. Android asks whether to open Phone Hands HID for the USB device; tick *Always* and tap *OK*. (If the app was already running, you may get a USB permission prompt instead. Tap *OK*.)
2. Check from the host:
   ```bash
   ./hid.py backend        # shows activeBackend, backendMode, ready, backends.ch9329.ready
   ./hid.py chinfo         # queries the chip: chipVersion, usbEnumerated, numLock/capsLock/scrollLock
   ```
   `usbEnumerated: true` means the target has accepted the CH9329 as a USB device.
3. Baud rate: the app opens the adapter at **9600 8N1** out of the box, which is the CH9329 factory default. If your module was set to something else:
   ```bash
   ./hid.py backend ch9329 --baud 115200
   ```
   It remembers the setting.
4. Picking a backend:
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

About power: while the phone's USB-C port is in OTG host mode it usually can't charge. For long sessions, use a powered OTG hub or splitter.

## 9. Position the camera

1. Mount the phone so the **rear camera** faces the target's monitor with the whole screen in frame.
2. Fight the glare. Keep lights from reflecting off the screen; tilting the phone a bit often helps.
3. Take a test shot and look at it:
   ```bash
   ./look.py -o /tmp/test.jpg
   ```
4. Sideways or upside down? Totally normal if the phone is in portrait or mounted flipped. Rotate the saved image:
   ```bash
   ./look.py -o /tmp/test.jpg --rotate cw     # 90° clockwise
   ./look.py -o /tmp/test.jpg --rotate ccw    # 90° counter-clockwise
   ./look.py -o /tmp/test.jpg --rotate 180
   ```
   Whichever one makes the screen upright, stick with it from now on.
5. Text too tiny to read? Move the phone closer, or try another resolution your camera supports:
   ```bash
   ./look.py --list-cameras            # lists camera ids and supported sizes
   ./look.py --camera-id 0 --size 1280x720
   ```

While Phone Hands is viewing the camera remotely, the phone shows a high-priority *Camera ON - remote viewing* notification and a red banner. The banner needs the overlay permission from section 6. (It is tied to an active remote-view session, not to mere camera use by other apps.)

## 10. Using hid.py (keyboard and mouse)

Every command prints a JSON response and exits non-zero if something went wrong.

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

Typing assumes a **US QWERTY** layout and US-ASCII characters. Anything it can't map gets skipped and listed in the response's `skipped` field. If the target uses a different keyboard layout, expect your symbols to come out scrambled.

### 10.3 Keys and shortcuts

```bash
./hid.py key enter
./hid.py key win+r
./hid.py key ctrl+alt+del
./hid.py key "ctrl+a ctrl+c" alt+tab        # a space-separated sequence
./hid.py key f5 esc up down pageup home end delete backspace tab space printscreen menu
./hid.py key ctrl+shift+esc --hold 60 --gap 80   # ms held / ms between steps (defaults 40 / 40)
```

Modifiers it knows: `ctrl`, `shift`, `alt` (`option`), `win` (`cmd`, `gui`, `meta`, `super`), plus the right-hand ones `rctrl`, `rshift`, `ralt` (`altgr`), `rwin`.

Keys it knows: letters, digits, punctuation, `f1`–`f24`, arrows, `enter`, `esc`, `tab`, `space`, `backspace`, `delete`, `insert`, `home`, `end`, `pageup`, `pagedown`, `capslock`, `numlock`, `scrolllock`, `printscreen`, `pause`, `menu`, `kp0`–`kp9`, `kpenter`, `mute`, `volumeup`, `volumedown`, or a raw HID usage like `0x2c`. You can hold up to six non-modifier keys in one combo.

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

Relative mouse movement goes through the target's pointer acceleration, so `move 500 0` won't move exactly 500 pixels. Grab a frame with `look.py`, see where the pointer ended up, and correct. On Windows, switching off *Enhance pointer precision* makes it a lot more predictable. Bluetooth has no absolute positioning, so the usual trick is to slam the pointer into a corner first (say `move -3000 -3000`) so you know where it is, then move relative from there.

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

Under the hood, `look.py` switches on the phone's camera indicator, records a short headless clip with `scrcpy --video-source=camera`, has `ffmpeg` pull out the last frame (by then exposure and focus have settled), switches the indicator off, and prints the absolute path. Figure about 6 seconds a frame. A lock file (`/tmp/phone-hands-look.lock`) keeps two captures from running at once, and the `frames/` directory is git-ignored.

## 12. Calling the HTTP API directly

The app runs a small HTTP API on the phone at `127.0.0.1:8765`, loopback only. Once `hid.py` has made the forward (or you've run `adb -s $PHONE_SERIAL forward tcp:18765 tcp:8765` yourself), you can hit it straight from the host. Parameters can go in the query string, a JSON body, or form-encoded.

```bash
curl -s http://127.0.0.1:18765/status
curl -s -X POST -H 'Content-Type: application/json' -d '{"text":"hi\n"}' http://127.0.0.1:18765/type
curl -s 'http://127.0.0.1:18765/key?combo=ctrl%2Bl'
curl -s 'http://127.0.0.1:18765/moveto?fx=0.5&fy=0.5'
curl -s 'http://127.0.0.1:18765/backend?addr=0'          # CH9329 chip address (default 0)
curl -s 'http://127.0.0.1:18765/camera/on?by=my-client'  # then /camera/heartbeat at least every 15 s, then /camera/off
```

The endpoints are `/status`, `/discoverable`, `/connect`, `/disconnect`, `/register`, `/type`, `/key`, `/move`, `/moveto`, `/click`, `/down`, `/up`, `/scroll`, `/release`, `/backend`, `/chinfo`, `/camera/on`, `/camera/heartbeat`, `/camera/off` and `/camera`. Their parameters are in the README. HTTP 400 means you sent a bad request. HTTP 409 means the output path isn't ready yet (for example "bluetooth: no connected host").

## 13. Example observe/act session

Here's the basic rhythm an agent (or a very patient human) follows: look, decide, act, look again.

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

An AI agent runs those same commands as tools. It reads each image (with a vision-capable model, say), figures out the next keyboard or mouse move, and sends it with `hid.py`. `agent.py` (section 16) automates exactly this loop with whichever model provider you like.

## 14. Troubleshooting

When the potato misbehaves:

| Symptom | Cause and fix |
|---|---|
| `set PHONE_SERIAL to your phone's adb serial` | Export `PHONE_SERIAL` (section 4.3). |
| `device ... is 'unauthorized'` | Accept the USB debugging prompt on the phone. No prompt? Revoke authorizations in Developer options and reconnect. |
| `phone ... not connected via adb` | Check the cable, or `adb connect <address>:<port>`. Wireless debugging ports change every time you toggle it. |
| `API unreachable` | The service isn't running. `hid.py` already tried `am start` once, so open the app by hand and check the permissions got granted. Set battery usage to *Unrestricted*. |
| HTTP 409 `bluetooth: no connected host` | The phone isn't connected to a target. Run `./hid.py status`, then `./hid.py connect <address>` or pair again. |
| `"registered": false` | Bluetooth is off, or the HID registration got lost. Turn Bluetooth on, then `./hid.py register`. |
| Windows shows the phone as *Paired* but it never works as a keyboard, or drops right away | Stale bond. *Remove device* on Windows, *Forget* the computer on the phone, toggle Bluetooth off and on on both, then pair again while `registered` is `true`. |
| Typed symbols come out wrong | The target isn't on a US keyboard layout. Switch it to US, or stick to characters that map the same. |
| Pointer lands in the wrong place | Pointer acceleration (Bluetooth / relative mode). Correct step by step using frames, turn off acceleration on the target, or use CH9329 with `moveto`. |
| Can't type at BIOS, disk-unlock or some login screens over Bluetooth | The target's Bluetooth stack isn't up yet. Use the CH9329 path. |
| `scrcpy camera capture failed` | Make sure scrcpy is 2.5+ (`scrcpy --version`), the phone is Android 12+, no other app is hogging the camera, and `./look.py --list-cameras` works. |
| scrcpy restarts adb and the phone vanishes | You've got two adbs fighting. `look.py` passes `ADB` to scrcpy, so set `ADB` to the same adb binary you use everywhere else. |
| No red banner while capturing | Grant the overlay permission (section 6) and check `./hid.py status` → `camera.overlayPermission`. The notification still shows up without it. |
| Images are blurry, dark or full of glare | Fiddle with lighting, angle and distance. Try `--seconds 4` to give focus more time, or another `--size`. |
| CH9329: `no USB-serial adapter found` | The phone can't see a supported adapter. Check the OTG adapter, the cable, and that the phone does USB host mode at all. |
| CH9329: `USB permission requested` | Tap *OK* on the phone's USB permission dialog, then `./hid.py backend --reopen`. |
| CH9329: `acksMissing` keeps climbing, `chinfo` reply is null | Wrong baud rate (try `--baud 115200`), TX/RX swapped, no ground wire, or the module isn't in protocol mode. |
| CH9329: `usbEnumerated: false` | The CH9329's USB side isn't plugged into the target, or isn't getting power. |
| Phone battery drains in CH9329 mode | OTG host mode stops charging. Use a powered OTG hub. |

The app's status screen and the `log` field in `./hid.py status` both show the latest internal events, which is usually the fastest way to figure out what's going on.

## 15. Security

**The API has no authentication.** Anyone who can reach it can type and click on the target computer. Please take this part seriously.

The app binds to `127.0.0.1` on the phone only, and the way in is supposed to be `adb forward` from a host you trust. Don't change the bind address, don't forward the port to `0.0.0.0` or a network interface, and don't expose it through a proxy or tunnel.

Having adb access to the phone means full control of the phone **and** of the target computer. Only use wireless debugging on networks you trust, and revoke old adb authorizations you don't use anymore.

An AI agent with these tools can do anything a person at the keyboard can, including deleting files and sending messages. Keep an eye on it, and don't use it on computers you're not authorized to operate.

The camera sees whatever's in front of it. Aim it only at the target screen and let people nearby know. Captured frames stay on the host under `frames/` (git-ignored); check them before you share any.

`./hid.py status` prints the Bluetooth names and addresses of bonded devices, so treat that output as private.

When you're finished, unpair the phone from the target computers.

## 16. The agent loop (agent.py) and AI providers

`agent.py` is section 13 on autopilot. Every step it:

1. grabs a frame with `look.py` (same options: `--rotate`, `--camera-id`, `--look-size`);
2. sends the frame, the goal, and the last `--history` actions and their results to a vision-language model;
3. pulls exactly one JSON action out of the reply;
4. runs it through the same HTTP API `hid.py` uses (or just prints it, with `--dry-run`);
5. goes around again until the model returns `done` or it hits `--max-steps` (default 20).

### 16.1 Quick start

```bash
export PHONE_SERIAL=<serial>
export OPENAI_API_KEY=<your key>          # or the variable(s) for your provider, see 16.3
./agent.py --list-providers               # providers and default models
./agent.py --provider openai --dry-run --max-steps 3 "Open the Start menu"   # no input is sent
./agent.py --provider openai --confirm "Open Notepad and type hello"          # y / n (skip) / q (quit) per step
./agent.py --provider openai "Open Notepad and type hello"                    # unattended (supervise it)
```

You can test the brain without a phone at all: `./agent.py --provider openai --image screenshot.jpg --dry-run "What would you click to open settings?"`. It reuses that one image every step and sends nothing to any computer. It does still make real model requests, and you'll get billed for them.

Exit codes: 0 means the model reported success, 1 means it gave up, ran out of steps, or you stopped it, and 2 means a configuration, phone or provider error (the message tells you which).

### 16.2 Options

| Option | Default | Meaning |
|---|---|---|
| `--provider NAME` | `$POTATO_PROVIDER` or `openai` | which brain (16.3) |
| `--model ID` | per provider, or `<PROVIDER>_MODEL` env var | model id; for Azure the deployment name |
| `--base-url URL` | provider default | API base URL; required for `openai-compatible` |
| `--max-tokens N` / `--timeout S` | 4096 / 120 | per request |
| `--max-steps N` | 20 | hard stop |
| `--history N` | 8 | recent actions included in each request |
| `--dry-run` | off | print actions instead of sending keyboard/mouse input |
| `--confirm` | off | ask before every action |
| `--settle S` | 0.5 | pause after each action before the next frame |
| `--log FILE` | none | append each step (action, result, raw reply) as JSON lines |
| `--image FILE` | camera | use a fixed image instead of `look.py` (testing) |
| `--save-frames DIR` | off | keep each step's frame (frames are otherwise deleted) |
| `--pointer auto\|absolute\|relative` | `auto` | `auto` asks `/status`: CH9329 → absolute, Bluetooth → relative |
| `--corners tlx,tly,trx,try,brx,bry,blx,bly` | whole frame | where the target screen's corners are in the frame (pixels) |
| `--screen-size WxH` | `1920x1080` | target resolution, used for relative moves |
| `--mouse-gain G` | 1.0 | relative mode: mouse counts per screen pixel |

### 16.3 Providers

Keys only ever come from environment variables, and nothing gets stored in the repo. Keep them in your shell profile or a secrets manager, not in a file you commit (`.env` is git-ignored just in case). Every request carries one camera frame, so you're paying vision-input prices on every single step.

| Provider | Required configuration | Notes |
|---|---|---|
| `openai` | `OPENAI_API_KEY` | Responses API `POST https://api.openai.com/v1/responses`, image as a base64 data URL, JSON output mode. `OPENAI_BASE_URL` overrides the endpoint (e.g. a proxy). |
| `anthropic` | `ANTHROPIC_API_KEY` | Messages API `POST https://api.anthropic.com/v1/messages`, `anthropic-version: 2023-06-01`, base64 image block. |
| `gemini` | `GEMINI_API_KEY` (or `GOOGLE_API_KEY`) from Google AI Studio | `generateContent` on `generativelanguage.googleapis.com/v1beta`, key in the `x-goog-api-key` header, `responseMimeType: application/json`. |
| `xai` | `XAI_API_KEY` | `POST https://api.x.ai/v1/chat/completions` (OpenAI-compatible). |
| `azure-openai` | `AZURE_OPENAI_ENDPOINT` (e.g. `https://<resource>.openai.azure.com`), `AZURE_OPENAI_API_KEY`, `AZURE_OPENAI_DEPLOYMENT` | `.../openai/deployments/<deployment>/chat/completions?api-version=...`; `AZURE_OPENAI_API_VERSION` defaults to `2024-10-21`. Deploy a vision-capable model first. |
| `aws-bedrock` | `pip install boto3`; AWS credentials by any standard method (`aws configure`, `AWS_PROFILE`, SSO, instance role, or `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`); `AWS_REGION` (default `us-east-1`) | Converse API with raw image bytes. Enable model access in the Bedrock console. Some models need a cross-region inference profile id (for example a `us.` or `global.` prefix); pass it with `--model`. IAM permission needed: `bedrock:InvokeModel`. |
| `google-vertex` | `GOOGLE_CLOUD_PROJECT`, `GOOGLE_CLOUD_LOCATION` (default `global`), and a token: `GOOGLE_VERTEX_ACCESS_TOKEN`, or `pip install google-auth` with Application Default Credentials (`gcloud auth application-default login`), or a logged-in `gcloud` CLI | Gemini `generateContent` on `aiplatform.googleapis.com`. Enable the Vertex AI API on the project. |
| `openrouter` | `OPENROUTER_API_KEY` | `https://openrouter.ai/api/v1/chat/completions`; any vision model slug via `--model`, e.g. `anthropic/claude-sonnet-5.5`. |
| `mistral` | `MISTRAL_API_KEY` | `https://api.mistral.ai/v1/chat/completions`; use a vision-capable model. |
| `ollama` | Ollama running locally; `ollama pull qwen3-vl` | Defaults to `http://localhost:11434/v1`; `OLLAMA_BASE_URL` or `--base-url` for another host. No key. |
| `openai-compatible` | `--base-url` (or `OPENAI_COMPATIBLE_BASE_URL`); optional `OPENAI_COMPATIBLE_API_KEY` | Any `/v1/chat/completions` server with image input: llama.cpp `llama-server` (with a multimodal projector, `--mmproj`), vLLM (`vllm serve <vision model>`), LM Studio (`http://localhost:1234/v1`), LocalAI. |

The default model ids (see `./agent.py --list-providers`) were current vision models when this release went out. Providers rename and retire models constantly, so override with `--model` or `<PROVIDER>_MODEL` (`OPENAI_MODEL`, `ANTHROPIC_MODEL`, `GEMINI_MODEL`, `XAI_MODEL`, `AWS_BEDROCK_MODEL`, `GOOGLE_VERTEX_MODEL`, `OPENROUTER_MODEL`, `MISTRAL_MODEL`, `OLLAMA_MODEL`, `OPENAI_COMPATIBLE_MODEL`). Small local models are sloppier about sticking to the JSON format. When a reply doesn't parse, the error goes back to the model and it gets another shot on the next step.

### 16.4 Action format

The model has to answer with a single JSON object, where `x`/`y` are pixel coordinates in the frame it was shown:

```json
{"reason": "open the Run dialog", "type": "key", "combo": "win+r"}
```

| `type` | Arguments | Executed as |
|---|---|---|
| `click` | `x`,`y` (optional), `button`, `count` | point (below), then `/click` |
| `moveto` | `x`,`y` | point |
| `move` | `dx`,`dy` (target pixels) | `/move` (small corrections) |
| `drag` | `x`,`y` (optional), `to_x`,`to_y` | point, `/down`, move, `/up` |
| `scroll` | `amount`, optional `x`,`y` | point, `/scroll` |
| `type` | `text` | `/type` (refused if it looks like a credential) |
| `key` | `combo` | `/key` |
| `wait` | `seconds` (≤ 10) | sleep |
| `done` | `success`, `summary` | stop |

### 16.5 Pointing and calibration

"Point" turns frame pixels into a fraction of the target screen. Without `--corners`, it pretends the whole frame is the screen, which is a rough guess at best. To do better:

1. Run `./look.py -o /tmp/cal.jpg --rotate <same as you use>` and open the picture in an image viewer.
2. Write down the pixel coordinates of the target screen's top-left, top-right, bottom-right and bottom-left corners.
3. Pass them in, like `--corners 212,96,1710,120,1690,968,230,940`. A perspective transform (homography) then maps any point in the frame onto the screen.

What happens next depends on the backend. With **CH9329 (absolute)**, the fraction goes straight to `/moveto`, so accuracy is all about your calibration.

With **Bluetooth (relative)** it's messier. The pointer gets shoved far into the top-left corner first (homing), then moved by `fraction × --screen-size × --mouse-gain` counts. Pointer acceleration throws this off, so the model is told to find the pointer in the next frame and fix it with `move`. For predictable results, turn off pointer acceleration on the target (Windows: *Enhance pointer precision* off; macOS/Linux: flat acceleration profile) and tune `--mouse-gain`. To measure it, run `./hid.py move -4000 -4000` then `./hid.py move 500 0`, see how many screen pixels the pointer actually moved, and use `gain = 500 / pixels_moved`.

### 16.6 Safety

Start every new task with `--dry-run`, then move up to `--confirm`, and keep `--max-steps` small until you trust it.

The system prompt forbids typing secrets, doing anything destructive or irreversible you didn't explicitly ask for, and following instructions that show up on screen. On top of that, `agent.py` refuses to type any text that contains the value of a provider key variable or looks like a common API key/private key.

If something needs a login, the model is told to stop with `done` and `success: false`. Type your credentials yourself.

Frames of your screen go to whichever provider you picked. For sensitive screens, use a local model (`ollama`, `openai-compatible`).

Hit Ctrl+C to stop. `agent.py` sends `/release` on the way out so no key or button gets stuck down.

### 16.7 Tests

```bash
python3 -m unittest discover -s tests -t .     # no network, no phone, no API keys needed
```

The tests mock all the HTTP (and boto3). For every provider they check the request URL, headers, body shape and image encoding, and that a sample response turns into an action. They also cover action parsing, coordinate mapping, and the dry-run and confirmation paths.
