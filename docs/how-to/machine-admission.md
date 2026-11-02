---
title: Bound a search-quality scoring window
type: how-to
status: stable
---

# Bound a search-quality scoring window

On Linux with Python 3.11+, run a **short scoring command** from the repository root:

```bash
python3 scripts/dev/admission.py exclusive --purpose 'search-quality scoring' --session eval-p3 \
  --drain-timeout 120 --timeout 600 -- your-scoring-command
python3 scripts/dev/admission.py status
```

The gate closes to new heavy work while existing heavy work drains. A drain timeout
returns **75**, lists the holders' purposes, PIDs and start times, and reopens the gate.
The scoring budget starts when the work lock is acquired, includes cleanup, defaults
to 600 seconds and cannot exceed 600. Deadline expiry returns **124**; the tool reserves
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
python3 scripts/dev/admission.py heavy --purpose 'background build' -- your-build-command
```

A long job can participate cooperatively if its caller supplies commands that pause
and resume **that job's own work**. A pause hook must wait until work has actually
stopped before returning; a resume hook runs after shared work admission is reacquired.
Hooks are argument strings parsed with Python's `shlex`, without an implicit shell.
For example, for an engine controlled by its own indexing APIs:

```bash
python3 scripts/dev/admission.py pausable --purpose 'background index rebuild' \
  --pause-hook 'curl --fail --silent -X POST http://127.0.0.1:PORT/YOUR_PAUSE_ENDPOINT' \
  --resume-hook 'curl --fail --silent -X POST http://127.0.0.1:PORT/YOUR_RESUME_ENDPOINT' \
  -- your-index-controller
```

Use the actual endpoints and acknowledgement behavior of the job you own. The tool
checks the gate every 50 ms while working, calls the pause hook, releases its shared
work lock, and waits for the gate. It resumes after every exclusive exit, including
SIGKILL. Hook failure terminates its command and returns 73. Admission never signals
or inspects another participant's processes, so separate agent PID namespaces work.

Lock files default to `${JS_ADMISSION_DIR:-/tmp/js-linux-port/admission}` and are opened
read-only for flock. Pre-create the directory and its `gate.lock` and `work.lock` files
if a sandbox cannot create them; every participant must use the same directory.
Status uses locks on unique diagnostic records to establish liveness and prune dead
records, regardless of PID namespace. It reports purpose, session (`--session` or
`JS_ADMISSION_SESSION`), start time, mode and pausable state. Records never arbitrate
the gate or work lock. Do not delete live lock files. `JS_ADMISSION_TOKEN` is inherited
by nested commands to avoid reacquiring admission; do not set it manually.

Gradle launches through `scripts/lib/tool-invoker.cjs`, dev-runner engine startup
through readiness, and jseval backend startup participate automatically. Idle stacks
hold no admission. Python callers can use `heavy(purpose, env)` from `admission.py`
as a context manager and pass the yielded environment to nested launchers.
The candidate machine wrapper in `tools/machine/gradle-slot.sh` acquires admission
before its worktree lock and Gradle slots; installing it is a separate machine change.

Process checks: `python3 -m unittest discover -s scripts/dev -p test_admission.py -v`.
Run `test-admission-cross-session.py actor DIR` and `... driver DIR` in separate sandbox
sessions to check A1/A2/A5/A7 across PID namespaces. After installDist and frontend
npm setup, `python3 scripts/dev/test-machine-admission-integration.py` exercises real
Gradle calls and a private dev stack with `JUSTSEARCH_GPU_ENABLED=false`, fresh data,
state and ports. It prints heavy status during startup, the ready receipt, released
admission and successful exclusive entry while that stack idles. All these fixtures
use their own admission directory.
