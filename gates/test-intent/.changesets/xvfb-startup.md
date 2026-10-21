---
schema: test-intent.v1
task: xvfb-loop
author-role: builder
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# Private Xvfb startup infrastructure

The native Rust shortcut assertions and event observer are unchanged. This entry covers
their Python startup infrastructure; the added script self-tests under `scripts/sandbox/`
are outside the gate's product-test scope. The gate currently does not flag the Python
helper either; this explicit entry records the infrastructure assessment for review.

The new `test_shortcut_x11_probe.py` runs automatically in `.github/workflows/ci.yml`,
job **Public claims**, step **Test sandbox validation harness (Python)**, through
`python -m unittest discover -s scripts/sandbox -p "test_*.py" -v`.
It uses fake child executables with coredumps disabled and temporary coordination files.

Builder evidence (logs in this checkout's `tmp/xvfb-loop/`):

- `fake-before.log`: the new abort assertion fails on the resolved `origin/main`
  commit `56e27e059e1fdffe4673403792ff418f4a1fd4fe` in a shared temporary clone:
  **100 starts != 1**. The Mesa-present environment assertions also fail.
- `fake-after.log`: seven startup tests pass, including abort and other signals,
  Mesa present/absent with/without an inherited vendor, occupied displays and racing
  collisions, noncollision exits (including exit zero), and startup timeout.
- `real-start.log`: private real Xvfb startup and `XOpenDisplay` succeed in this sandbox;
  the harness stops its own child. No Rust build or crate test was run.

```json
{
  "entries": [
    {
      "items": ["modules/shell/src-tauri/tests/shortcut_x11_probe.py"],
      "class": "Incidental",
      "assessment": {
        "purpose": "Repair private Xvfb setup without changing the native shortcut tests' assertions, activation observation, inputs or child test selection.",
        "callersAndStateOwners": "search_shortcut.rs native_probe starts this Python helper. Each run owns its Xvfb PID and temporary profile; Xvfb owns its X11 socket and lock. Existing sockets and locks are neither removed nor adopted. Mesa selection is confined to the Xvfb child environment.",
        "indirectBehaviours": "Use the installed Mesa vendor JSON when present. Skip occupied displays and retry only collision failures. Fail immediately with the signal name after a signalled child, or with captured stderr after a noncollision exit or timeout. Preserve the existing 100-display search limit and native child cleanup.",
        "evidence": "tmp/xvfb-loop/fake-before.log records assertion failure 100 != 1 and missing Mesa selection at origin/main. tmp/xvfb-loop/fake-after.log records seven passing fake-process checks. tmp/xvfb-loop/real-start.log records successful real Xvfb startup and a real X11 connection. The new Python checks are discovered by the existing Public claims sandbox unittest step.",
        "unresolvedRisks": "The NVIDIA abort's dependency on sandbox device access remains unproven. The full native Rust shortcut behavior was not rerun, as instructed. Independent verification and acceptance remain pending."
      },
      "sources": [
        {
          "kind": "owner-words",
          "quote": "In the harness, start the Xvfb child with `__EGL_VENDOR_LIBRARY_FILENAMES=/usr/share/glvnd/egl_vendor.d/50_mesa.json`, so Xvfb's GLX/EGL init uses Mesa instead of the NVIDIA vendor library. Only set it when that file exists; leave other platforms alone.",
          "location": "task xvfb-loop request item 1"
        },
        {
          "kind": "owner-words",
          "quote": "Try the next display only when the previous Xvfb failed because that display is taken (its lock or socket exists, or it exited normally with an \"already active\" error). If an Xvfb dies by a signal, stop at once with a clear error naming the signal. Never start a second one.",
          "location": "task xvfb-loop request item 2"
        }
      ]
    }
  ]
}
```

## Acceptance records
