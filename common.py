"""Shared helpers for phone-hands CLIs."""
import os, shutil, subprocess, sys

ADB = os.environ.get("ADB") or shutil.which("adb") or "adb"
SERIAL = os.environ.get("PHONE_SERIAL", "")
LOCAL_PORT = int(os.environ.get("PHONE_HANDS_PORT", "18765"))
REMOTE_PORT = 8765
PKG = "com.phonehands.hid"


def adb(*args, check=True, capture=True, timeout=60):
    cmd = [ADB, "-s", SERIAL, *args]
    r = subprocess.run(cmd, capture_output=capture, text=True, timeout=timeout)
    if check and r.returncode != 0:
        sys.exit(f"adb failed: {' '.join(cmd)}\n{r.stdout}{r.stderr}")
    return r


def ensure_device():
    if not SERIAL:
        sys.exit("set PHONE_SERIAL to your phone's adb serial (see `adb devices`)")
    r = subprocess.run([ADB, "devices"], capture_output=True, text=True)
    for line in r.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[0] == SERIAL:
            if parts[1] == "device":
                return
            sys.exit(f"device {SERIAL} is '{parts[1]}' (re-authorize / reconnect)")
    if ":" in SERIAL:
        subprocess.run([ADB, "connect", SERIAL], capture_output=True, text=True, timeout=20)
        r = subprocess.run([ADB, "-s", SERIAL, "get-state"], capture_output=True, text=True)
        if r.stdout.strip() == "device":
            return
    sys.exit(f"phone {SERIAL} not connected via adb. Set PHONE_SERIAL or run: {ADB} connect <host:port>")


def ensure_forward():
    ensure_device()
    want = f"tcp:{LOCAL_PORT} tcp:{REMOTE_PORT}"
    r = subprocess.run([ADB, "forward", "--list"], capture_output=True, text=True)
    if any(l.startswith(SERIAL + " ") and l.endswith(want) for l in r.stdout.splitlines()):
        return
    adb("forward", f"tcp:{LOCAL_PORT}", f"tcp:{REMOTE_PORT}")
