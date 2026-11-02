#!/usr/bin/env python3
"""Host-side CLI for the Phone Hands HID app (Bluetooth HID or CH9329 USB backend).

Examples:
  hid.py status                     hid.py type "hello World!\n"
  hid.py key win+r                  hid.py key ctrl+alt+del
  hid.py key "ctrl+c ctrl+v"        hid.py move 300 -120
  hid.py click --double             hid.py click --button right
  hid.py drag 200 0                 hid.py scroll -5
  hid.py moveto 0.5 0.5 (CH9329 only, fractions of screen)
"""
import argparse, json, sys, time, urllib.error, urllib.request
sys.path.insert(0, __import__("os").path.dirname(__file__))
from common import LOCAL_PORT, PKG, adb, ensure_forward


def call(path, params=None, timeout=600, autostart=True):
    ensure_forward()
    url = f"http://127.0.0.1:{LOCAL_PORT}{path}"
    data = json.dumps(params or {}).encode()
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    for attempt in range(2):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            return json.loads(e.read() or b"{}") | {"http": e.code}
        except (urllib.error.URLError, ConnectionError, OSError, json.JSONDecodeError) as e:
            if attempt == 0 and autostart:
                # service not running: launch the app (which starts the foreground service)
                adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", check=False)
                time.sleep(3)
                continue
            return {"ok": False, "error": f"API unreachable: {e}"}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = ap.add_subparsers(dest="cmd", required=True)
    sp.add_parser("status", help="registration, backend, host, bonded devices, log")
    s = sp.add_parser("discoverable", help="ask the phone to become discoverable (tap Allow on phone)")
    s.add_argument("--duration", type=int, default=300)
    s = sp.add_parser("connect", help="connect to a bonded host by Bluetooth address")
    s.add_argument("address")
    sp.add_parser("disconnect")
    sp.add_parser("register", help="unregister + re-register the Bluetooth HID app")
    s = sp.add_parser("type", help="type ASCII text (US layout); '-' reads stdin; \\n = Enter, \\t = Tab")
    s.add_argument("text")
    s.add_argument("--delay", type=int, default=12, help="ms per key press/release (default 12)")
    s.add_argument("--raw", action="store_true", help="don't interpret \\n/\\t escapes")
    s = sp.add_parser("key", help="key combos, space-separated sequence: 'ctrl+alt+del', 'win+r', 'alt+tab', 'f5', 'enter'")
    s.add_argument("combo", nargs="+")
    s.add_argument("--hold", type=int, default=40)
    s.add_argument("--gap", type=int, default=40)
    s = sp.add_parser("move", help="relative mouse move (auto-chunked into ±127 steps)")
    s.add_argument("dx", type=int); s.add_argument("dy", type=int)
    s.add_argument("--delay", type=int, default=8)
    s = sp.add_parser("moveto", help="absolute move (CH9329 only): fractions 0..1, or --raw 0..4095")
    s.add_argument("x", type=float); s.add_argument("y", type=float)
    s.add_argument("--raw", action="store_true")
    s = sp.add_parser("click")
    s.add_argument("--button", default="left", choices=["left", "right", "middle"])
    s.add_argument("--double", action="store_true"); s.add_argument("--count", type=int)
    for n in ("down", "up"):
        s = sp.add_parser(n, help=f"mouse button {n} (for manual drags)")
        s.add_argument("--button", default="left", choices=["left", "right", "middle"])
    s = sp.add_parser("drag", help="button down, relative move, button up")
    s.add_argument("dx", type=int); s.add_argument("dy", type=int)
    s.add_argument("--button", default="left", choices=["left", "right", "middle"])
    s = sp.add_parser("scroll", help="wheel: positive = up, negative = down")
    s.add_argument("amount", type=int)
    sp.add_parser("release", help="release all keys and mouse buttons")
    s = sp.add_parser("backend", help="show/set backend: auto | bluetooth | ch9329")
    s.add_argument("name", nargs="?", choices=["auto", "bluetooth", "ch9329"])
    s.add_argument("--baud", type=int); s.add_argument("--reopen", action="store_true")
    s.add_argument("--no-ack", action="store_true", help="CH9329: don't wait for chip replies")
    sp.add_parser("chinfo", help="CH9329: query chip version / USB enumeration / LEDs")
    a = ap.parse_args()

    c = a.cmd
    if c == "status":
        r = call("/status")
    elif c == "discoverable":
        # Launch via adb (not blocked by background-activity rules); the app fires ACTION_REQUEST_DISCOVERABLE.
        adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", "--ez", "discoverable", "true",
            "--ei", "duration", str(a.duration))
        r = {"ok": True, "note": "Discoverable request shown on the phone; tap Allow. Then pair from the computer."}
    elif c == "connect":
        r = call("/connect", {"address": a.address})
    elif c in ("disconnect", "register", "release", "chinfo"):
        r = call("/" + c)
    elif c == "type":
        text = sys.stdin.read() if a.text == "-" else a.text
        if not a.raw and a.text != "-":
            text = text.replace("\\n", "\n").replace("\\t", "\t")
        r = call("/type", {"text": text, "delay": a.delay})
    elif c == "key":
        r = call("/key", {"combo": " ".join(a.combo), "hold": a.hold, "gap": a.gap})
    elif c == "move":
        r = call("/move", {"dx": a.dx, "dy": a.dy, "delay": a.delay})
    elif c == "moveto":
        r = call("/moveto", {"x": int(a.x), "y": int(a.y)} if a.raw else {"fx": a.x, "fy": a.y})
    elif c == "click":
        r = call("/click", {"button": a.button, "count": a.count or (2 if a.double else 1)})
    elif c in ("down", "up"):
        r = call("/" + c, {"button": a.button})
    elif c == "drag":
        r = call("/down", {"button": a.button})
        if r.get("ok"):
            time.sleep(0.05)
            r = call("/move", {"dx": a.dx, "dy": a.dy, "delay": 12})
            time.sleep(0.05)
            r2 = call("/up", {"button": a.button})
            r = {"ok": r.get("ok") and r2.get("ok"), "move": r, "up": r2}
    elif c == "scroll":
        r = call("/scroll", {"amount": a.amount})
    elif c == "backend":
        p = {}
        if a.name: p["name"] = a.name
        if a.baud: p["baud"] = a.baud
        if a.reopen: p["reopen"] = True
        if a.no_ack: p["waitAck"] = "false"
        r = call("/backend", p) if p else {k: v for k, v in call("/status").items() if k in ("ok", "activeBackend", "backendMode", "ready", "backends")}
    print(json.dumps(r, indent=2))
    sys.exit(0 if r.get("ok", True) else 1)


if __name__ == "__main__":
    main()
