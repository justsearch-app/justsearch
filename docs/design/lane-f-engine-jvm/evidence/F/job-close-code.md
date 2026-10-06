# Job-closure exit code (Spec F step 0, M-F1) - 2026-10-06

Task t-20261005-b6d536, Spec F §1.5 (CH8). Measures the exit code a process gets when
`JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` ends it, on the target machine.

Machine: Windows 11 Pro 10.0.26200, Temurin JDK 25.0.2+10, Node v24.12.0. Not elevated.

## Result

**`JOB_CLOSE_OBSERVED_CODE = 0`.** Every process ended by the closure of its kill-on-close job
reported exit code 0 (`GetExitCodeProcess`), the same value as a clean exit. This held for the
console child, its conhost and the holder's own conhost, whether the holder was killed hard or exited
in order, in 4 of 4 runs.

Consequences:

- The code cannot tell a job-closure death from a clean exit. The census and `codeName` must not
  read 0 as "exited cleanly" for a child whose owner had already died; OWNER_DIED stays decided by
  basis (an open start or an open intent), never by code (Spec B R6-R8), as §1.5 already says.
- A process killed with `taskkill /F` reports 1 (the holder rows below), so the supervisors' distinct
  terminate codes (`JS_CHILD_STOP` 0x4A530001, `JS_ENGINE_STOP` 0x4A530002) remain the only way to
  make a forced stop recognisable.

## Method

Source: `F:\agent-sandbox\design-b6d536\spike\JobCloseCode.java` (single-file source mode). Raw
logs: `spike\out\job-close-code-{kill,kill-2,kill-3,exit}.log`.

- The holder JVM creates a job with `CreateJobObjectW(NULL, NULL)` (non-inheritable), sets exactly
  `KILL_ON_JOB_CLOSE` (0x2000, the `WindowsEngineJob` flags of §1.2), assigns itself, and spawns
  `ping -n 60 127.0.0.1` through `ProcessBuilder` with output discarded. ping gets its own conhost.
- The driver JVM holds `SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION` handles to the holder,
  ping and both conhosts, then either runs `taskkill /F` on the holder (`kill`) or waits for the
  holder's own `System.exit(0)` (`exit`). It polls every 20 ms and reads `GetExitCodeProcess` and the
  exit time from `GetProcessTimes`.

## Observations

`kill` (first run; the two repeats gave the same codes, every job member 0 and the holder 1):

| Process | In a job | Exit code | Exit after the holder (ms) |
| --- | --- | --- | --- |
| holder java.exe (`taskkill /F`) | yes | 1 | 0.0 |
| PING.EXE | yes | 0 | 1.5 |
| the holder's own conhost | no | 0 | 2.7 |
| ping's conhost | yes | 0 | 1.8 |

Repeats: ping 2.4 / 2.5 ms, conhosts 3.0-3.9 ms after the holder; all codes 0.

`exit` (holder calls `System.exit(0)`):

| Process | In a job | Exit code | Exit after the holder (ms) |
| --- | --- | --- | --- |
| holder java.exe | yes | 0 | 0.0 |
| PING.EXE | yes | 0 | 2.5 |
| the holder's own conhost | no | 0 | 3.6 |
| ping's conhost | yes | 0 | 2.7 |

The orderly exit also closes the job and ends its members, which S1 had only inferred.

## Step 0 items not measured here

- **M-F2** (quit critical path with chat streaming and extraction active, index-close p95, ingest
  handoff within 0.5 s): **pending**. It needs the dev stack with chat streaming, which this slice
  does not start.
- **M-F3** (does `Instant` / `nanoTime` advance across sleep on the target laptop): **pending**. It
  needs the target laptop to sleep.
- Spike case D (libuv nesting): measured, see `libuv-nesting.md`.
