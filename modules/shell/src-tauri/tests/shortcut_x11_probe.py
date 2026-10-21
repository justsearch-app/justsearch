"""Private Xvfb for the shell's native regression checks; no window manager.

Advertise EWMH and record activation messages without granting activation. This
tests the request, not a desktop's focus-stealing policy. Never use the caller's
display, start Xwayland, or remove X sockets/locks.
"""
import ctypes as c
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time


def connection():
    x = c.CDLL("libX11.so.6")
    signatures = {
        "XOpenDisplay": (c.c_void_p, [c.c_char_p]),
        "XDefaultRootWindow": (c.c_ulong, [c.c_void_p]),
        "XInternAtom": (c.c_ulong, [c.c_void_p, c.c_char_p, c.c_int]),
        "XCreateSimpleWindow": (c.c_ulong, [c.c_void_p, c.c_ulong, c.c_int, c.c_int,
                                           c.c_uint, c.c_uint, c.c_uint, c.c_ulong, c.c_ulong]),
        "XChangeProperty": (c.c_int, [c.c_void_p, c.c_ulong, c.c_ulong, c.c_ulong,
                                      c.c_int, c.c_int, c.c_void_p, c.c_int]),
        "XSelectInput": (c.c_int, [c.c_void_p, c.c_ulong, c.c_long]),
        "XSync": (c.c_int, [c.c_void_p, c.c_int]),
        "XPending": (c.c_int, [c.c_void_p]),
        "XNextEvent": (c.c_int, [c.c_void_p, c.c_void_p]),
        "XCloseDisplay": (c.c_int, [c.c_void_p]),
        "XKeysymToKeycode": (c.c_ubyte, [c.c_void_p, c.c_ulong]),
        "XGrabKey": (c.c_int, [c.c_void_p, c.c_int, c.c_uint, c.c_ulong,
                               c.c_int, c.c_int, c.c_int]),
    }
    for name, (result, arguments) in signatures.items():
        fn = getattr(x, name)
        fn.restype, fn.argtypes = result, arguments
    d = x.XOpenDisplay(None)
    if not d:
        raise RuntimeError("private Xvfb connection unavailable")
    return x, d, x.XDefaultRootWindow(d)


def grab():
    x, d, root = connection()
    errors = []
    callback_type = c.CFUNCTYPE(c.c_int, c.c_void_p, c.c_void_p)
    handler = callback_type(lambda _d, _e: errors.append(True) or 0)
    x.XSetErrorHandler.argtypes = [callback_type]
    x.XSetErrorHandler(handler)
    key = x.XKeysymToKeycode(d, 0x20)
    for ignored in [0, 2, 16, 18]:  # none, CapsLock, NumLock, both
        x.XGrabKey(d, key, 1 | 8 | ignored, root, 0, 1, 1)
    x.XSync(d, 0)
    x.XCloseDisplay(d)
    print("chord refused" if errors else "chord available", flush=True)
    return 1 if errors else 0


def run(test, executable):
    # The caller sets TMPDIR inside the checkout. TemporaryDirectory owns only
    # this directory; Xvfb alone owns its socket and lock.
    with tempfile.TemporaryDirectory(prefix="shortcut-x11-") as temporary:
        root = Path(temporary)
        env = dict(os.environ)
        env.pop("DISPLAY", None)
        env.pop("WAYLAND_DISPLAY", None)
        env.update(CUDA_VISIBLE_DEVICES="-1", XDG_SESSION_TYPE="x11", GDK_BACKEND="x11",
                   WEBKIT_DISABLE_COMPOSITING_MODE="1", LIBGL_ALWAYS_SOFTWARE="1",
                   JUSTSEARCH_SHORTCUT_PROBE=test, JUSTSEARCH_SHORTCUT_PROBE_DIR=str(root),
                   DBUS_SESSION_BUS_ADDRESS="unix:path=" + str(root / "no-session-bus"))
        for key, directory in [("HOME", "home"), ("XDG_CONFIG_HOME", "config"),
                               ("XDG_DATA_HOME", "data"), ("XDG_CACHE_HOME", "cache"),
                               ("XDG_RUNTIME_DIR", "run")]:
            path = root / directory
            path.mkdir(mode=0o700)
            env[key] = str(path)
        xvfb = None
        child = None
        d = None
        try:
            for number in range(191, 291):
                if Path(f"/tmp/.X11-unix/X{number}").exists() or Path(f"/tmp/.X{number}-lock").exists():
                    continue
                candidate = subprocess.Popen(["Xvfb", f":{number}", "-screen", "0", "1280x800x24",
                                              "-nolisten", "tcp"], env=env,
                                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                for _ in range(100):
                    if candidate.poll() is not None or Path(f"/tmp/.X11-unix/X{number}").exists():
                        break
                    time.sleep(0.01)
                lock = Path(f"/tmp/.X{number}-lock")
                if (candidate.poll() is None and Path(f"/tmp/.X11-unix/X{number}").exists()
                        and lock.exists() and lock.read_text().strip() == str(candidate.pid)):
                    xvfb = candidate
                    env["DISPLAY"] = f":{number}"
                    break
                if candidate.poll() is None:
                    candidate.terminate()
                candidate.wait(timeout=5)
            if xvfb is None:
                raise RuntimeError("no private Xvfb display available")
            os.environ["DISPLAY"] = env["DISPLAY"]
            x, d, window = connection()
            atom = lambda name: x.XInternAtom(d, name.encode(), 0)
            check = x.XCreateSimpleWindow(d, window, -10, -10, 1, 1, 0, 0, 0)
            active, supporting = atom("_NET_ACTIVE_WINDOW"), atom("_NET_SUPPORTING_WM_CHECK")
            for target in [window, check]:
                x.XChangeProperty(d, target, supporting, 33, 32, 0, (c.c_ulong * 1)(check), 1)
            x.XChangeProperty(d, window, atom("_NET_SUPPORTED"), 4, 32, 0,
                              (c.c_ulong * 2)(active, supporting), 2)
            x.XSelectInput(d, window, 1 << 19)  # observe; do not redirect maps or act as a WM
            x.XSync(d, 0)
            child = subprocess.Popen([executable, "--exact", test, "--nocapture"], env=env)
            deadline = time.monotonic() + 45
            count = 0
            while child.poll() is None:
                if time.monotonic() > deadline:
                    raise TimeoutError("native shortcut probe exceeded 45 seconds")
                while x.XPending(d):
                    event = (c.c_char * 192)()
                    x.XNextEvent(d, event)
                    if c.c_int.from_buffer(event).value == 33 and c.c_ulong.from_buffer(event, 40).value == active:
                        count += 1
                        (root / "activations.tmp").write_text(str(count))
                        os.replace(root / "activations.tmp", root / "activations")
                time.sleep(0.005)
            return child.returncode
        finally:
            if child is not None and child.poll() is None:
                child.kill()
                child.wait(timeout=5)
            if d is not None:
                x.XCloseDisplay(d)
            if xvfb is not None:
                xvfb.terminate()
                xvfb.wait(timeout=5)


if __name__ == "__main__":
    sys.exit(grab() if sys.argv[1] == "grab" else run(sys.argv[1], sys.argv[2]))
