#!/usr/bin/env python3
"""Grab a fresh still frame from the phone's camera (headless scrcpy + ffmpeg).

  look.py                       -> ./frames/look-<ts>.jpg (prints path)
  look.py -o /tmp/screen.jpg    -> explicit path
  look.py --camera-id 1         -> front camera
  look.py --rotate cw|ccw|180   -> rotate the saved image
  look.py --screen              -> phone screenshot instead (debug the app UI)
  look.py --list-cameras
"""
import argparse, contextlib, fcntl, os, subprocess, sys, tempfile, threading, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import ADB, SERIAL, adb, ensure_device

SCRCPY = os.environ.get("SCRCPY", "scrcpy")
HERE = os.path.dirname(os.path.abspath(__file__))
ROT = {"cw": "transpose=1", "ccw": "transpose=2", "180": "hflip,vflip"}


def scrcpy_env():
    # Force scrcpy to use the SDK adb (its bundled adb would restart the adb server and drop the phone).
    return dict(os.environ, ADB=ADB)


@contextlib.contextmanager
def camera_indicator(by="look.py"):
    """Tell the phone app to show the 'Camera ON' banner+notification while capturing.
    Heartbeats every 5 s (the app auto-clears after ~15 s without one); always turns it off."""
    from hid import call
    stop = threading.Event()
    def beat():
        while not stop.wait(5):
            call("/camera/heartbeat", {"by": by}, timeout=5, autostart=False)
    r = call("/camera/on", {"by": by}, timeout=10)
    if not r.get("ok"):
        print(f"warning: camera indicator not confirmed: {r}", file=sys.stderr)
    t = threading.Thread(target=beat, daemon=True); t.start()
    try:
        yield
    finally:
        stop.set()
        call("/camera/off", timeout=10, autostart=False)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("-o", "--out")
    ap.add_argument("--camera-id", default=os.environ.get("LOOK_CAMERA", "0"), help="0=back (default), 1=front")
    ap.add_argument("--size", default="1920x1080", help="camera capture size (default 1920x1080)")
    ap.add_argument("--seconds", type=float, default=2.5, help="clip length; last frame is kept so exposure/focus settle")
    ap.add_argument("--rotate", choices=list(ROT))
    ap.add_argument("--quality", type=int, default=2, help="ffmpeg -q:v (2=best)")
    ap.add_argument("--screen", action="store_true", help="phone screencap instead of camera")
    ap.add_argument("--list-cameras", action="store_true")
    a = ap.parse_args()
    ensure_device()

    if a.list_cameras:
        subprocess.run([SCRCPY, "-s", SERIAL, "--list-cameras"], env=scrcpy_env())
        return
    out = a.out or os.path.join(HERE, "frames", time.strftime("look-%Y%m%d-%H%M%S.jpg"))
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)

    if a.screen:
        r = subprocess.run([ADB, "-s", SERIAL, "exec-out", "screencap", "-p"], capture_output=True, timeout=30)
        if not r.stdout.startswith(b"\x89PNG"):
            sys.exit("screencap failed")
        src = tempfile.NamedTemporaryFile(suffix=".png", delete=False).name
        open(src, "wb").write(r.stdout)
        clip = None
    else:
        lock = open("/tmp/phone-hands-look.lock", "w")
        fcntl.flock(lock, fcntl.LOCK_EX)  # one scrcpy camera session at a time
        clip = tempfile.NamedTemporaryFile(suffix=".mkv", delete=False).name
        os.unlink(clip)
        cmd = [SCRCPY, "-s", SERIAL, "--video-source=camera", f"--camera-id={a.camera_id}",
               f"--camera-size={a.size}", "--no-audio", "--no-window", "--no-playback", "--no-control",
               f"--record={clip}", "--record-format=mkv", f"--time-limit={max(1, round(a.seconds))}"]
        ok = False
        with camera_indicator():
            for attempt in range(2):
                r = subprocess.run(cmd, capture_output=True, text=True, env=scrcpy_env(), timeout=60)
                if os.path.exists(clip) and os.path.getsize(clip) > 1000:
                    ok = True
                    break
                time.sleep(1)
        if not ok:
            sys.exit("scrcpy camera capture failed:\n" + r.stdout[-2000:] + r.stderr[-2000:])
        src = clip

    vf = ["-vf", ROT[a.rotate]] if a.rotate else []
    seek = ["-sseof", "-0.4"] if clip else []
    r = subprocess.run(["ffmpeg", "-loglevel", "error", "-y", *seek, "-i", src, *vf, "-frames:v", "1",
                        "-update", "1", "-q:v", str(a.quality), out], capture_output=True, text=True)
    if r.returncode != 0 or not os.path.exists(out):
        # fall back to first decodable frame
        r = subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", src, *vf, "-frames:v", "1",
                            "-q:v", str(a.quality), out], capture_output=True, text=True)
    os.unlink(src)
    if not os.path.exists(out) or os.path.getsize(out) == 0:
        sys.exit("ffmpeg frame extraction failed: " + r.stderr)
    print(os.path.abspath(out))


if __name__ == "__main__":
    main()
