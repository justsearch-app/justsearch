---
title: Bound a search-quality scoring window
type: how-to
status: stable
description: "Give a short search-quality scoring run the machine to itself: heavy work waits or pauses, and everything resumes when the window ends."
---

# Bound a search-quality scoring window

On Linux with Python 3.11+, run a **short scoring command** from the repository root:

```bash
python3 -I scripts/dev/admission.py exclusive --purpose 'search-quality scoring' --session eval-p3 \
  --drain-timeout 600 --timeout 600 -- your-scoring-command
python3 -I scripts/dev/admission.py status
```

The gate closes to new heavy work while existing heavy work drains. A drain timeout
returns **75**, lists the holders' purposes, PIDs and start times, and reopens the gate.
The gate never stays closed long: `--drain-timeout` is at most 600 seconds and
`--timeout` at most 600, so drain plus window is at most 20 minutes. Waiting for the
gate itself (behind another window) is bounded by `--gate-timeout` (default and
maximum 1200 seconds, every mode) and returns **75** with the window's holder listed.
The scoring budget starts when the work lock is acquired, includes cleanup, defaults
to 600 seconds. Deadline expiry returns **124**; the tool reserves
a short part of the budget for TERM, KILL and reaping the command's descendants.
The command starts in its own process group. An isolated child-subreaper guardian
also tracks descendants that create new groups or sessions, including nested heavy
wrappers, and verifies that all have exited and been reaped before releasing admission
on every command exit. Normal command exit codes are
preserved; SIGINT/SIGTERM return 130/143, and setup failures return 73.

SIGKILL of the **CLI holder** closes its lifetime pipe; the surviving guardian cleans
the entire command tree before releasing its kernel-held locks. This does not protect
against killing the guardian itself, namespace teardown, or machine failure. Killing
the guardian releases its locks without a cleanup guarantee. Processes stuck in
uninterruptible kernel sleep can delay cleanup beyond the budget: admission stays
closed until they are gone, rather than allowing concurrent heavy work.

Keep **index rebuilds in the background**, outside exclusive scoring windows. Ordinary
heavy commands can coexist:

```bash
python3 -I scripts/dev/admission.py heavy --purpose 'background build' -- your-build-command
```

A heavy or pausable command **inherits the work lock** (and its status record). If the
tool itself is killed, even with SIGKILL, the lock lasts as long as the command runs:
the next window drains and refuses, listing it, instead of overlapping it. A command's
leftover background processes that keep the inherited descriptor keep admission too
(a Gradle daemon does not: Java closes inherited descriptors).

A long job can participate cooperatively if its caller supplies commands that pause
and resume **that job's own work**. A pause hook must wait until work has actually
stopped before returning; a resume hook runs after shared work admission is reacquired.
Hooks are argument strings parsed with Python's `shlex`, without an implicit shell.
For example, for an engine controlled by its own indexing APIs:

```bash
python3 -I scripts/dev/admission.py pausable --purpose 'background index rebuild' \
  --pause-hook 'curl --fail --silent -X POST http://127.0.0.1:PORT/YOUR_PAUSE_ENDPOINT' \
  --resume-hook 'curl --fail --silent -X POST http://127.0.0.1:PORT/YOUR_RESUME_ENDPOINT' \
  -- your-index-controller
```

Use the actual endpoints and acknowledgement behavior of the job you own. The tool
checks the gate every 50 ms while working, calls the pause hook, releases its shared
work lock, and waits for the gate. When the window ends, including SIGKILL of the
exclusive tool, it retakes the work lock, reopens the gate and then calls the resume
hook. Each hook must finish within `--hook-timeout` (default 60, at most 120 seconds);
a failed or timed-out hook terminates the command and returns 73. A paused job stays
paused while the window holds the gate. Heavy work the job starts (Gradle, a jseval
launch) takes its own admission, so it waits during a window. Admission never signals
or inspects another participant's processes, so separate agent PID namespaces work.

Lock files default to `${JS_ADMISSION_DIR:-/tmp/js-linux-port/admission}` and are opened
read-only for flock; every participant must use the same directory. Status uses locks
on unique diagnostic records to establish liveness and prune dead records, regardless
of PID namespace. It reports purpose, session (`--session` or `JS_ADMISSION_SESSION`),
start time, mode and pausable state. Records never arbitrate the gate or work lock.
Do not delete live lock files. `JS_ADMISSION_TOKEN` is inherited by nested heavy
commands to avoid reacquiring admission; do not set it manually.

**Unavailable admission fails open.** If `heavy` cannot create the directory, the lock
files or its record (no write access, a read-only view of `/tmp`), it prints one
warning line and runs the command without admission. The integrations do the same
when `python3` or `admission.py` is missing; their own errors (for example a missing
`gradlew`'s ENOENT) are reported unchanged. Pre-created lock files do not help a
read-only view, because each participant also creates a record. `exclusive` and
`pausable` instead fail with 73.

Gradle launches through `scripts/lib/tool-invoker.cjs`, dev-runner engine startup
through readiness, and jseval backend startup participate automatically. Idle stacks
hold no admission. A dev-runner start released by an abnormal holder exit logs one
line and keeps its own result. Python callers can use `heavy(purpose, env)` from
`admission.py` as a context manager (it uses the admission directory of `env`) and
pass the yielded environment to nested launchers.
The machine Gradle wrapper does not take admission yet; that integration is a separate
change, so Gradle calls made through `js-gradle-slot` are not held back by an exclusive window.

Process checks, each with its own admission directory:
`python3 -m unittest discover -s scripts/dev -p test_admission.py -v`,
`node --test scripts/lib/tool-invoker.test.mjs` and
`node scripts/dev/test-dev-runner-machine-admission.mjs` (a real dev-runner start with
the fake engine: held until ready, released on boot failure, readiness timeout and a
stop during startup, and an idle stack never blocks a window). Run
`test-admission-cross-session.py actor DIR` and `... driver DIR` in separate sandbox
sessions to check A1/A2/A5/A7 across PID namespaces. After installDist and frontend
npm setup, `python3 scripts/dev/test-machine-admission-integration.py` exercises real
Gradle calls through `js-gradle-slot` (skipped where it is not installed) and a private
dev stack with `JUSTSEARCH_GPU_ENABLED=false`, fresh data, state and ports.
