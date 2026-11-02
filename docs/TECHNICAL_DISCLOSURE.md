# Technical Disclosure: System and Method for Operating a Separate Computer by an Automated Agent Using a General-Purpose Mobile Phone as a Human-Interface-Device Emulator and as a Camera-Based Visual Sensor of the Computer's Display

| | |
|---|---|
| **Document type** | Defensive publication (technical disclosure intended as prior art) |
| **Project** | Helpful Potato (internal software name: "Phone Hands"; Android package `com.phonehands.hid`) |
| **Author** | TravelerHacker |
| **First public disclosure** | 2026-11-01 |
| **Location** | https://github.com/TravelerHacker/helpful-potato |
| **Version of record** | Git tag `v1.0.0` of the above repository, with its commit history |
| **Keywords** | AI agent, computer-use agent, LLM agent, robotic process automation, smartphone, Android, Bluetooth HID, BluetoothHidDevice, USB HID, keyboard emulation, mouse emulation, CH9329, UART-to-HID bridge, USB OTG, scrcpy, camera capture, screen observation, visual feedback loop, closed-loop control, KVM, out-of-band control, air-gapped computer, retired phone reuse |

---

## 1. Statement of Purpose

1.1. This document, together with the source code, build scripts, tests, and documentation in the repository identified above, is published to place the described subject matter in the public domain of prior art, so that it is available to patent examiners and to the public and so that no party can obtain a valid patent claim covering it. It is a defensive publication.

1.2. The repository constitutes a complete, working implementation of the primary embodiments. The illustrations and informal tone used in the repository's `README.md` are presentation only and do not limit or qualify this disclosure.

1.3. The first public disclosure date of this subject matter is **2026-11-01**, evidenced by the public Git commit history and the dated GitHub release `v1.0.0` of the repository.

1.4. Throughout this document, items are labelled **[Implemented]** when they are present and functioning in the published source code, **[Implemented, not hardware-verified]** when the code is present but has not been exercised against the physical device it targets, and **[Alternative]** when they are contemplated variations that are disclosed here but not implemented in the published code. All alternatives are described in sufficient detail to be made and used by a person of ordinary skill in the art of mobile software, embedded USB/Bluetooth devices, and software agents, without undue experimentation. No performance or test results are claimed beyond those stated.

1.5. The software license of the repository (PolyForm Noncommercial 1.0.0) governs use of the copyrighted code. It does not restrict the status of this document or the repository as a publication.

## 2. Field

2.1. The disclosure relates to computer automation, and more specifically to enabling a software agent, including an artificial-intelligence agent based on a large language model or a vision-language model, to operate a separate target computer through that computer's ordinary physical input interfaces (keyboard and pointing device) while observing that computer's display with a camera, using a commodity mobile phone as both the input-emulation device and the camera, and without installing software on the target computer.

## 3. Background

3.1. Software agents that operate graphical user interfaces ("computer-use agents") usually run on, or install a component on, the computer they control: for example a screenshot API, an accessibility API, a remote-desktop server, or a virtual-machine framebuffer. This is not possible or not desirable when the target computer is locked down, owned by someone else, air-gapped, in a pre-boot state (BIOS/UEFI, disk-unlock screen), running an unsupported operating system, or must remain unmodified for security, compliance, or evidentiary reasons.

3.2. Hardware KVM-over-IP devices and dedicated HID-injection devices exist, but they require purchasing special hardware, typically an HDMI capture path, and they are not designed as tools for an automated agent.

3.3. Smartphones contain a camera, a Bluetooth radio, a USB port capable of host mode, a general-purpose processor, and a network/debug channel. Large numbers of such phones are retired and unused.

3.4. There is a need for an inexpensive, non-invasive way to give an automated agent "hands" (keyboard and mouse input) and "eyes" (a view of the screen) on an arbitrary computer, using hardware that people already own.

## 4. Summary

4.1. A mobile phone is positioned so that its camera views the display of a target computer. The phone runs an application that presents itself to the target computer as a standard human interface device (HID) keyboard and mouse, either directly over Bluetooth using the operating system's HID Device profile, or indirectly over USB through a serial-to-USB-HID bridge connected to the phone's USB port operating in host mode.

4.2. A host computer, on which the agent runs, communicates with the phone over a debug/control channel (in the implemented embodiment, Android Debug Bridge (adb) over USB or TCP/IP, with a TCP port forward to a loopback-only HTTP API in the phone application). The host issues high-level input commands (type text, press key combination, move/click/drag/scroll the pointer, move to an absolute screen position) which the phone converts to HID reports and delivers to the target.

4.3. The host also requests still images of the target's display captured by the phone's camera, which the agent analyses to determine the state of the target's user interface and to decide the next input action. This forms a closed observe-decide-act loop in which the only interfaces to the target computer are its standard input ports/radio and the light emitted by its display.

4.4. The phone visibly indicates on its own screen and in its notification area whenever the camera is in use, with a heartbeat-based watchdog and an independent operating-system camera-availability monitor.

## 5. Brief Description of the Drawings

5.1. Fig. 1 (`docs/img/fig1-setup.jpg`): illustrative view of a phone on a stand facing a monitor and connected to the computer. Fig. 2 (`docs/img/fig2-old-phone.jpg`): illustrative depiction of reusing a retired phone. Fig. 3 (`docs/img/fig3-camera.jpg`): illustrative depiction of the camera-on indicator. Fig. 4 (`docs/img/fig4-bluetooth.jpg`): illustrative depiction of the wireless (Bluetooth) embodiment. The figures are stylised illustrations; the architecture diagram in `README.md` (section "Architecture") shows the components and data paths described below.

## 6. Components and Reference Numerals

| Ref. | Component | Implementation in the repository |
|---|---|---|
| 10 | Host computer running the agent | Any Linux/macOS machine with Python 3.9+, adb, scrcpy 2.5+, ffmpeg |
| 12 | Agent | Any program, script, or AI model that invokes the host tools (not included; the tools are agent-agnostic) |
| 14 | Input command tool ("hands") | `hid.py` |
| 16 | Image capture tool ("eyes") | `look.py` |
| 18 | Shared host helpers | `common.py` (device selection, adb invocation, port forward) |
| 20 | Control channel | adb over USB or TCP/IP; `adb forward tcp:18765 tcp:8765` |
| 30 | Mobile phone | Unrooted Android phone, Android 12 (API 31) or later |
| 32 | Phone application | `app/` (Java; package `com.phonehands.hid`) |
| 34 | Foreground service and HTTP API server | `HidService.java`, bound to `127.0.0.1:8765` |
| 36 | Output backend interface | `HidBackend.java` |
| 38 | Bluetooth HID backend | `HidService.BtBackend` using `android.bluetooth.BluetoothHidDevice` |
| 40 | Serial-bridge HID backend | `Ch9329Backend.java` using usb-serial-for-android 3.10.0 |
| 42 | Bridge protocol encoder | `Ch9329.java` (pure Java), tests in `app/test/Ch9329Test.java` |
| 44 | Key mapping | `Keys.java` (US QWERTY to HID usage IDs) |
| 46 | Camera-in-use indicator | `CameraIndicator.java` |
| 48 | User interface / permission and intent handling | `MainActivity.java` |
| 50 | Boot start | `BootReceiver.java` |
| 52 | Phone camera | Rear camera (id 0) by default |
| 60 | USB-serial adapter | CH340/CH341/CH9102, CP210x, FTDI, PL2303 |
| 62 | Serial-to-USB-HID bridge | WCH CH9329 |
| 70 | Target computer | Any computer accepting a Bluetooth or USB keyboard/mouse |
| 72 | Target display | Monitor or built-in screen of the target computer |

## 7. Detailed Description

### 7.1 Physical arrangement [Implemented]

7.1.1. The phone 30 is held by a stand so that its rear camera 52 views the target display 72. The phone is connected to the host 10 by USB cable, or over a network using adb wireless debugging, or through any IP tunnel that carries adb.

7.1.2. In the Bluetooth embodiment the phone is paired with the target 70 as a Bluetooth keyboard/mouse and no cable connects phone and target. In the USB embodiment the phone's USB port is placed in host (OTG) mode and connected via an OTG adapter to a USB-serial adapter 60 whose TX/RX/GND lines connect to a CH9329 bridge 62, whose USB device side is plugged into the target 70.

7.1.3. Nothing is installed on or configured in software on the target 70 beyond accepting a Bluetooth pairing (Bluetooth embodiment) or nothing at all (USB embodiment).

### 7.2 Host-to-phone control channel [Implemented]

7.2.1. `common.py` selects the phone by the `PHONE_SERIAL` environment variable, verifies the adb state, automatically issues `adb connect` for `host:port` serials, and ensures a TCP forward from host port `PHONE_HANDS_PORT` (default 18765) to phone loopback port 8765.

7.2.2. `hid.py` sends each command as an HTTP POST with a JSON body to `http://127.0.0.1:<port>/<endpoint>`. If the API is unreachable it launches the phone application with `adb shell am start -n com.phonehands.hid/.MainActivity`, waits, and retries once. It prints the JSON response and returns a non-zero exit status on failure, which makes it directly usable as a tool by an agent.

7.2.3. The phone's HTTP server (34) is a minimal HTTP/1.1 implementation on a `ServerSocket` bound only to `127.0.0.1` (backlog 16), one thread per connection, 15-second socket timeout. Parameters are accepted from the query string, a JSON body, an `application/x-www-form-urlencoded` body, or, for text, a raw body. Responses are JSON with an `ok` field. Precondition failures (for example, no connected Bluetooth host) map to HTTP 409; other errors to HTTP 400. All HID-emitting operations are serialised on a single-threaded executor so that reports from concurrent requests do not interleave.

7.2.4. Endpoints: `/status`, `/discoverable` (`duration`), `/connect` (`address`), `/disconnect`, `/register`, `/type` (`text`, `delay`), `/key` (`combo`, `hold`, `gap`), `/move` (`dx`, `dy`, `delay`), `/moveto` (`fx`,`fy` as fractions or `x`,`y` in 0..4095), `/click` (`button`, `count`, `hold`), `/down` and `/up` (`button`), `/scroll` (`amount`, `delay`), `/release`, `/backend` (`name`, `baud`, `addr`, `waitAck`, `reopen`), `/chinfo`, `/camera/on` and `/camera/heartbeat` (`by`), `/camera/off`, `/camera`.

### 7.3 Command-to-report translation [Implemented]

7.3.1. **Text.** Each character is mapped by `Keys.ascii` to a HID keyboard usage ID and a modifier mask for a US QWERTY layout (letters 0x04–0x1D, digits 0x1E–0x27, Enter 0x28, Tab 0x2B, Space 0x2C, punctuation 0x2D–0x38, with Left Shift 0x02 where needed). Unmappable characters are skipped and reported back. Each character is emitted as a press report followed by a release report with a configurable delay (default 12 ms).

7.3.2. **Key combinations.** A specification such as `ctrl+alt+del` or a whitespace/comma-separated sequence such as `ctrl+a ctrl+c` is parsed by `Keys.parseCombo` into a modifier mask (Left Ctrl 0x01, Left Shift 0x02, Left Alt 0x04, Left GUI 0x08, Right Ctrl 0x10, Right Shift 0x20, Right Alt 0x40, Right GUI 0x80) and up to six usage IDs, using a named-key table (function keys F1–F24, navigation keys, keypad, media keys, etc.) or a literal `0x..` usage. When modifiers are present the emitter sends a modifier-only report, waits, sends modifiers plus keys, holds (default 40 ms), sends modifier-only again, then an all-released report, then waits a gap (default 40 ms). This staged sequence improves recognition of shortcuts by target operating systems.

7.3.3. **Relative pointer.** A relative move (dx, dy) is decomposed into successive reports with each component clamped to −127..+127, with an inter-report delay (default 8 ms). Scroll amounts are decomposed in the same way. Clicks send a button-down report and a button-up report with a hold time; double clicks repeat. A held-button state is maintained so that `/down`, `/move`, `/up` implement dragging.

7.3.4. **Absolute pointer.** Where the backend supports it (CH9329), a target position is expressed as a fraction of the screen (0.0–1.0) and scaled to 0..4095, or given directly in that range. The Bluetooth backend reports that absolute positioning is unsupported.

7.3.5. **Release.** `/release` emits all-released keyboard and mouse reports, used for recovery after errors.

### 7.4 Backend selection [Implemented]

7.4.1. The `HidBackend` interface defines `keyboard(8-byte report)`, `mouseRel(buttons, dx, dy, wheel)`, `mouseAbs(buttons, x, y, wheel)`, `supportsAbsolute()`, `ready()`, and `status()`. Mode `auto` (default) selects the CH9329 backend whenever its USB-serial adapter is open, otherwise Bluetooth; modes `bluetooth` and `ch9329` force a backend. The mode and baud rate are persisted.

### 7.5 Bluetooth HID backend [Implemented]

7.5.1. The service obtains the `BluetoothProfile.HID_DEVICE` proxy and calls `BluetoothHidDevice.registerApp` with SDP settings (name "Phone Hands", description "Keyboard + Mouse", provider "PhoneHands", subclass `SUBCLASS1_COMBO`) and the following HID report descriptor:

- **Report ID 1 – keyboard:** Usage Page Generic Desktop, Usage Keyboard, Application collection; 8 one-bit modifier inputs (usages 0xE0–0xE7); one reserved byte; 5 one-bit LED outputs (Num, Caps, Scroll, Compose, Kana) plus 3 bits padding; 6 one-byte key-array inputs (usages 0x00–0xFF).
- **Report ID 2 – mouse:** Usage Page Generic Desktop, Usage Mouse, Pointer physical collection; 3 one-bit buttons plus 5 bits padding; X, Y and Wheel as signed 8-bit relative inputs (−127..+127).

The exact descriptor bytes are in `HidService.DESCRIPTOR`.

7.5.2. Keyboard reports are sent with `sendReport(host, 1, [mods, 0, k1..k6])`; mouse reports with `sendReport(host, 2, [buttons, dx, dy, wheel])`. `onGetReport` is answered with zeroed reports.

7.5.3. Pairing: the phone requests discoverability through `BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE` (launched by the host through `adb shell am start ... --ez discoverable true --ei duration N`, because background activity launches are restricted), and the target initiates pairing. The service records the last connected host address and, when the HID application is re-registered (after restart or Bluetooth toggle), reconnects to it if still bonded. A partial wake lock is held while a host is connected. The service runs as a foreground service of type `connectedDevice`, is sticky, and is started at boot.

### 7.6 CH9329 serial-to-HID backend [Implemented, not hardware-verified]

7.6.1. The phone enumerates USB-serial adapters with the usb-serial-for-android prober (USB vendor/product IDs for CH340/CH341/CH9102, CP210x, FTDI FT232R/FT230X/FT231X and PL2303 are declared in `res/xml/usb_device_filter.xml` so that Android offers to open the application when such an adapter is attached), requests USB permission, opens the first port at a configurable baud rate (default 9600), 8 data bits, 1 stop bit, no parity, and handles attach/detach broadcasts.

7.6.2. **Frame format.** Every command frame is:

```
HEAD1  HEAD2  ADDR  CMD  LEN  DATA[LEN]  SUM
0x57   0xAB   0x00  ...  n    ...        (sum of all preceding bytes) & 0xFF
```

| CMD | Meaning | DATA |
|---|---|---|
| 0x01 | Get chip info | none |
| 0x02 | General keyboard | 8 bytes: modifiers, 0x00, key1..key6 |
| 0x03 | Media keyboard | (defined, not used) |
| 0x04 | Absolute mouse | 7 bytes: 0x02, buttons, X low, X high, Y low, Y high, wheel; X/Y in 0..4095 |
| 0x05 | Relative mouse | 5 bytes: 0x01, buttons, dX, dY, wheel (signed 8-bit, −127..+127) |

Reply frames use the same structure with CMD = request CMD | 0x80 on success or | 0xC0 on error. A reply scanner (`Ch9329.findReply`) locates a valid frame by header and checksum within a receive buffer that may contain noise. The get-info reply (CMD 0x81) carries the chip version byte, a USB-enumeration status byte (1 = enumerated by the target), and an LED-status byte (bit0 Num Lock, bit1 Caps Lock, bit2 Scroll Lock).

7.6.3. **Example frames** (verified by `app/test/Ch9329Test.java`):

| Action | Frame |
|---|---|
| Get info | `57 AB 00 01 00 03` |
| Key "a" down | `57 AB 00 02 08 00 00 04 00 00 00 00 00 10` |
| All keys up | `57 AB 00 02 08 00 00 00 00 00 00 00 00 0C` |
| Ctrl+Alt+Delete | `57 AB 00 02 08 05 00 4C 00 00 00 00 00 5D` |
| Relative move +10, −10 | `57 AB 00 05 05 01 00 0A F6 00 0D` |
| Absolute move to center (2048, 2048) | `57 AB 00 04 07 02 00 00 08 00 08 00 1F` |

7.6.4. After each frame the backend optionally waits (up to ~60 ms; 150 ms for get-info) for a reply and counts acknowledged, error, and missing replies for diagnostics; waiting can be disabled. The target therefore receives a genuine USB HID keyboard and mouse, which works before any operating system Bluetooth stack is available (firmware setup, boot loaders, full-disk-encryption prompts).

### 7.7 Visual sensing [Implemented]

7.7.1. `look.py` acquires an exclusive file lock (one camera session at a time), signals `/camera/on` to the phone and sends `/camera/heartbeat` every 5 s, and runs scrcpy in camera mode headlessly:

```
scrcpy -s <serial> --video-source=camera --camera-id=<id> --camera-size=<WxH> \
       --no-audio --no-window --no-playback --no-control \
       --record=<tmp>.mkv --record-format=mkv --time-limit=<seconds>
```

It then extracts the last frame of the clip with ffmpeg (`-sseof -0.4`, falling back to the first decodable frame), optionally rotates it (`transpose=1`, `transpose=2`, or `hflip,vflip`), writes a JPEG, signals `/camera/off`, and prints the file path. Using the last frame of a short clip allows auto-exposure and autofocus to settle. A retry is made if the clip is empty. The scrcpy process is given the same adb binary as the rest of the tools to avoid restarting the adb server.

7.7.2. For diagnosing the phone itself, `look.py --screen` captures the phone's own display with `adb exec-out screencap -p`.

### 7.8 Camera-in-use indicator [Implemented]

7.8.1. The phone shows a high-importance ongoing notification ("Camera ON - remote viewing"), a red full-width overlay banner below the status bar (window type `TYPE_APPLICATION_OVERLAY`, non-focusable, non-touchable; requires the `SYSTEM_ALERT_WINDOW` app-op), and a red label in the application whenever either (a) a client has called `/camera/on` and the most recent heartbeat is less than 15 s old, or (b) Android's `CameraManager.AvailabilityCallback` reports any camera as unavailable (in use), which covers sessions whose client crashed and use of the camera by other software. An explicit "on" state is also cleared when the camera, having been seen in use, has been free for 4 s.

### 7.9 Closed-loop operation by an agent (method) [Implemented as tools; agent not included]

7.9.1. The method comprises:

1. Establishing the control channel (7.2) and an output path (7.5 or 7.6) between the phone and the target.
2. **Observe:** the agent invokes the image capture tool (7.7) and obtains an image of the target display.
3. **Interpret:** the agent analyses the image, for example with a vision-language model, an OCR engine, template matching, or a combination, to determine UI state: visible windows, text, dialogs, focused field, and pointer location.
4. **Decide:** the agent selects the next action toward its goal (type text, press a shortcut, move the pointer, click, scroll, drag).
5. **Act:** the agent invokes the input command tool (7.3), which causes the phone to emit HID reports to the target.
6. **Verify:** the agent captures another image to confirm the effect of the action; if the pointer or result differs from what was intended (for example due to pointer acceleration in relative mode), it issues a corrective action.
7. Repeating steps 2–6 until the goal is reached or the agent stops.

7.9.2. Because relative pointer movement is subject to the target's acceleration curve, the implemented guidance is iterative correction using successive images, disabling acceleration on the target, or using absolute positioning (CH9329). Homing the pointer by a large relative move toward a screen corner to reach a known position is a further technique.

7.9.3. Example tool sequence (all commands exist in the repository): `look.py` → `hid.py key win+r` → `look.py` → `hid.py type 'notepad\n'` → `look.py` → `hid.py type '...'` → `look.py`.

## 8. Embodiments

The following embodiments are disclosed. Each may be combined with any other compatible embodiment and with any of the alternatives in section 9.

1. **[Implemented]** A system comprising a host computer executing an agent; an unrooted mobile phone connected to the host by a debug channel; an application on the phone exposing a local API to the host through a port forward over the debug channel; the phone registering as a Bluetooth HID combination keyboard/mouse with a target computer; and the phone's camera capturing the target computer's display for the agent.
2. **[Implemented, not hardware-verified]** The system of embodiment 1 wherein, instead of or in addition to Bluetooth, the phone's USB port operates in host mode and drives a USB-serial adapter connected to a serial-to-USB-HID bridge chip (CH9329) whose USB side is connected to the target computer, the phone encoding keyboard, relative-mouse and absolute-mouse commands in the bridge's framed serial protocol.
3. **[Implemented]** The system of embodiment 1 or 2 with automatic backend selection that prefers the wired bridge when present and falls back to Bluetooth.
4. **[Implemented]** The system of any preceding embodiment wherein each still image is obtained by recording a short camera clip through a screen/camera mirroring tool and extracting its last frame, so that exposure and focus settle.
5. **[Implemented]** The system of any preceding embodiment with a camera-in-use indicator driven by both a client heartbeat with timeout and an operating-system camera-availability callback.
6. **[Implemented]** The method of section 7.9: repeatedly capturing an image of a target display by a phone camera, interpreting it by an agent, and causing the phone to emit HID input to the target according to the agent's decision, and verifying the result by a subsequent image.
7. **[Implemented]** Use of a retired or secondary mobile phone, held on a stand, as the sole hardware (apart from optional cables) needed to give an agent keyboard, mouse and visual access to a computer on which no software is installed.
8. **[Implemented]** The system wherein the host-to-phone debug channel runs over a wireless network or an IP tunnel (adb over TCP/IP), so that the agent may be located remotely from both the phone and the target.

## 9. Alternative Embodiments [Alternative]

The following variations are expressly disclosed. They are not implemented in the published code unless stated. Each is a direct substitution or addition within the architecture described in section 7.

### 9.1 Input (HID) transports

9.1.1. **USB device-mode HID from the phone itself.** On phones where the kernel exposes the Linux USB gadget framework (ConfigFS/FunctionFS `hid` function), typically requiring root or vendor support, the phone's own USB port is configured as a USB HID keyboard/mouse connected directly to the target, with no bridge chip. The same `HidBackend` interface writes the 8-byte keyboard and 4-byte mouse reports to `/dev/hidgN`.

9.1.2. **Android Open Accessory (AOA) HID.** Where the roles are reversed (the agent-side device is USB host and the controlled device is Android), AOA 2.0 HID registration messages are used to inject HID input; and, more generally, any accessory protocol by which one USB peer registers a HID descriptor with the other.

9.1.3. **Bluetooth Low Energy HID over GATT (HOGP).** The phone advertises as a BLE HID peripheral (HID Service 0x1812 with Report Map, Report, Protocol Mode characteristics, plus Battery and Device Information services) using the platform's BLE peripheral/GATT server APIs, instead of or in addition to Bluetooth Classic HID. This supports targets that only accept BLE keyboards and phones that lack the Classic HID Device profile.

9.1.4. **Microcontroller bridges.** In place of the CH9329: an ESP32-S2/S3, RP2040/RP2350, ATmega32U4, SAMD21, STM32, nRF52840 or any microcontroller with native USB device support, flashed with firmware that receives commands from the phone and presents a USB HID keyboard/mouse (optionally absolute pointer, touch digitizer, consumer control or composite device) to the target. The link from phone to microcontroller may be USB-serial (CDC-ACM), UART through a USB-serial adapter, Bluetooth Classic SPP, BLE GATT, Wi-Fi (TCP, UDP, HTTP, WebSocket, MQTT), or USB with the phone as host; the command format may be the CH9329 frame format (allowing reuse of the same encoder), raw HID reports, or a textual protocol.

9.1.5. **Other UART/I²C/SPI-to-HID bridge chips** functionally similar to the CH9329 (for example other WCH devices such as the CH9328/CH9350 family or equivalent parts from other vendors), and pre-assembled "USB-to-USB HID" cables containing a USB-serial chip and a HID bridge.

9.1.6. **Wireless dongles.** A USB dongle plugged into the target that receives commands from the phone over Bluetooth, BLE, Wi-Fi, or a proprietary 2.4 GHz link and emits USB HID.

9.1.7. **Other HID device classes.** Absolute pointer over Bluetooth by a digitizer/touchscreen or absolute-mouse report descriptor; multi-touch digitizer; game controller; consumer control (media keys, which the CH9329 command 0x03 provides); system control (power, sleep); keyboard LED feedback used as a side channel to confirm keystroke receipt.

9.1.8. **Non-US layouts.** Mapping text to usage IDs per target keyboard layout (e.g. using a configurable layout table or by detecting the layout from LED/visual feedback), and alternative text entry using operating-system Unicode input methods (for example Alt+numpad codes on Windows, Ctrl+Shift+U on some Linux desktops).

9.1.9. **Other control-channel transports** between host and phone in place of adb: Wi-Fi/LAN socket with authentication (TLS, pre-shared key, mutual certificates), WebSocket, gRPC, MQTT, Bluetooth, USB tethering, a cloud relay, or a messaging service; and running the agent itself on the phone, so that no separate host is required.

### 9.2 Visual sensing ("eyes")

9.2.1. **Camera access methods.** Instead of scrcpy: the phone application capturing frames itself with Camera2/CameraX and serving JPEG or a video stream over the same API; RTSP/RTMP/WebRTC/MJPEG streaming; or any "phone as webcam" application (including Android's built-in USB webcam mode on Android 14+), exposing the phone camera to the host as a UVC/V4L2/DirectShow/AVFoundation webcam.

9.2.2. **Other cameras.** An iPhone or iPad used as the camera (e.g. via Continuity Camera or a third-party webcam application) while a separate device or bridge provides the HID input; a USB/IP webcam, a security camera, smart glasses or a head-mounted camera; or a second, separate phone dedicated to vision while the first provides input.

9.2.3. **Display capture without a camera.** An HDMI/DisplayPort capture device read by the phone or host, used together with the phone's HID output, in place of or in addition to the camera.

9.2.4. **Image processing.** Perspective correction (detecting the screen quadrilateral and warping to a rectangle, using detected corners or fiducial markers shown on screen or affixed to the bezel); de-glare and moiré reduction; exposure locking; multi-frame averaging; region-of-interest cropping and zoom; change detection between frames to determine when the UI has settled; continuous streaming instead of discrete stills; on-phone processing to reduce transmitted data.

9.2.5. **Interpretation.** OCR, UI-element detection models, vision-language models, template matching, cursor detection, and combinations, running on the host, the phone, or a remote service.

### 9.3 Closed-loop pointer calibration

9.3.1. Learning the mapping between pointer commands and on-screen displacement by issuing known relative moves, locating the pointer in successive camera images, and fitting a model (gain, acceleration curve, per-axis scale) that the agent then inverts to reach targets in fewer steps.

9.3.2. Mapping camera-image coordinates to target-screen coordinates by homography from detected screen corners or displayed calibration patterns, so that an agent can select a point in the camera image and the system computes the absolute (CH9329/digitizer) or relative command that reaches it.

9.3.3. Homing the pointer by over-travel toward a screen corner before relative moves; and, with absolute positioning, verifying with a captured image and refining.

### 9.4 Topologies and use

9.4.1. **Multiple targets.** One phone paired with several Bluetooth hosts and switching between them (`/connect`), or driving several bridges through a USB hub, with one or more cameras, so that one agent operates several computers; and several phones operated by one or more agents.

9.4.2. **Remote operation.** The host and agent located anywhere on a network, reaching the phone through wireless debugging, a VPN, or another tunnel.

9.4.3. **Human-in-the-loop.** A human supervising, approving, or taking over actions; the same tools used manually.

9.4.4. **Device reuse.** Repurposing retired, obsolete, or otherwise unused phones and tablets, including devices with damaged screens, as agent input/vision appliances; powering them through a powered OTG hub so that charging and USB host mode operate together.

9.4.5. **Applications.** Operating air-gapped, kiosk, industrial-control, medical, legacy, or locked-down computers; pre-boot and firmware configuration; installing operating systems; testing and quality assurance of software on real hardware; accessibility (operating a computer on behalf of a person with limited mobility by voice or other input); remote IT support; and evaluation of computer-use agents on physical machines.

9.4.6. **Other phone platforms and form factors.** Any mobile operating system, tablet, single-board computer with a camera and Bluetooth/USB (e.g. Raspberry Pi-class devices), or wearable that offers equivalent HID-emulation and camera capabilities.

## 10. Implementation Status and Limitations (as of 2026-11-01)

- Bluetooth HID backend, HTTP API, host CLIs, camera capture, and camera indicator: implemented and used on one Google Pixel phone.
- CH9329 backend: implemented; frame encoder and reply parser unit-tested on the host JVM (`app/test/run.sh`, 15 tests); not yet exercised with physical CH9329 hardware.
- Typing: US-ASCII on a US keyboard layout only.
- Bluetooth backend: relative pointer only.
- Image capture: approximately 6 seconds per still frame in the implemented scrcpy-based method.
- The API has no authentication; it is reachable only through the phone's loopback interface (via adb forward).
- Everything in section 9 is disclosed but not implemented.

## 11. Reproduction

Build and installation steps are in [`docs/INSTALL.md`](INSTALL.md). The application is built without Gradle by `app/build.sh` (aapt2, javac, d8, zipalign, apksigner) and requires only a JDK 17+ and Android SDK build-tools and platform packages. The host tools require only the Python 3 standard library, adb, scrcpy and ffmpeg.

## 12. Publication Record

- Repository: https://github.com/TravelerHacker/helpful-potato
- First public disclosure: 2026-11-01 (Git commit history and release `v1.0.0`)
- Author: TravelerHacker
- Citation metadata: [`CITATION.cff`](../CITATION.cff)
