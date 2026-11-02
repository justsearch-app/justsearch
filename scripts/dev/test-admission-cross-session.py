#!/usr/bin/env python3
"""A1/A2/A5/A7 across PID namespaces, without signalling foreign processes.

Start `python3 scripts/dev/test-admission-cross-session.py actor <empty-temp-dir>`
in one sandbox session, then `... driver <same-dir>` in another. Each side owns
and cleans only its own children. Both sides have a 45-second fixture deadline.
The driver refuses to pass unless /proc/self/ns/pid differs between sessions.
"""
import json
import os
from pathlib import Path
import sys
import time
from test_admission import AdmissionTests, PYTHON, SLEEP


def run(role, root):
    fixture = AdmissionTests()
    fixture.setUp()
    fixture.root = root
    root.mkdir(parents=True, exist_ok=True)
    fixture.env["JS_ADMISSION_DIR"] = str(root / "admission")
    wait = lambda condition: fixture.wait_for(condition, timeout=15)
    try:
        if role == "actor":
            (root / "actor-namespace").write_text(os.readlink("/proc/self/ns/pid"))
            heavy = fixture.start("heavy", [PYTHON, "-c", f"""from pathlib import Path
import time
until=time.monotonic()+45
while not Path({str(root / 'release-heavy')!r}).exists() and time.monotonic()<until: time.sleep(.01)
"""], purpose="foreign build")
            wait(lambda: fixture.row("heavy"))
            (root / "actor-heavy").touch()
            assert heavy.wait(timeout=45) == 0
            background = fixture.start("pausable", purpose="foreign index controller")
            wait(lambda: fixture.row("pausable"))
            (root / "actor-pausable").touch()
            wait(lambda: (root / "finish").exists())
            background.terminate()
            assert background.wait(timeout=2) == 143
            (root / "actor-done").touch()
            print("PASS actor: own heavy and cooperative job cleaned up", flush=True)
        elif role == "driver":
            wait(lambda: (root / "actor-heavy").exists())
            foreign = (root / "actor-namespace").read_text()
            local = os.readlink("/proc/self/ns/pid")
            assert foreign != local, f"different PID namespaces required: {foreign} == {local}"
            print(f"namespaces: actor={foreign}, driver={local}", flush=True)
            row = fixture.row("heavy")
            assert row and row["purpose"] == "foreign build" and row["session"] and row["start"]
            holder = fixture.start("exclusive", options=["--drain-timeout", ".4"])
            wait(lambda: fixture.row("exclusive-draining"))
            marker = root / "heavy-entered"
            waiter = fixture.start("heavy", [PYTHON, "-c", f"from pathlib import Path; Path({str(marker)!r}).touch()"])
            time.sleep(.1)
            assert not marker.exists()
            assert holder.wait(timeout=2) == 75
            assert waiter.wait(timeout=2) == 0
            assert fixture.row("heavy")["purpose"] == "foreign build"
            print("PASS A1/A7: foreign holder visible, drain refused, gate reopened", flush=True)
            (root / "release-heavy").touch()
            wait(lambda: (root / "actor-pausable").exists())
            holder = fixture.start("exclusive", SLEEP)
            wait(lambda: fixture.row("exclusive"))
            row = fixture.row("pausable")
            assert row and row["purpose"] == "foreign index controller" and row["state"] == "paused"
            beat = root / "beat"
            frozen = beat.read_text()
            time.sleep(.15)
            assert beat.read_text() == frozen
            waiter = fixture.start("heavy", [PYTHON, "-c", "pass"])
            started = time.monotonic()
            holder.kill()
            assert holder.wait(timeout=2) == -9
            fixture.wait_for(lambda: beat.read_text() != frozen, timeout=2)
            assert waiter.wait(timeout=2) == 0
            elapsed = time.monotonic() - started
            assert elapsed < 2, elapsed
            print(f"PASS A2/A5/A7: SIGKILL -> foreign resume and heavy entry in {elapsed:.3f}s", flush=True)
            (root / "finish").touch()
            wait(lambda: (root / "actor-done").exists())
        else:
            raise ValueError("expected actor or driver")
    finally:
        (root / "release-heavy").touch()
        (root / "finish").touch()
        fixture.tearDown()


if __name__ == "__main__":
    run(sys.argv[1], Path(sys.argv[2]).resolve())
