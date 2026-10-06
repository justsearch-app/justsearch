# Engine job under Node's libuv job (Spec F step 0, spike case D) - 2026-10-06

Task t-20261005-b6d536, Spec F §1.4 case D (RV2 F3). S1 measured job nesting under a plain parent.
This reproduces the dev chain exactly: Node spawns the Engine-like holder JVM the way dev-runner
spawns the Engine, so libuv puts it in Node's job L, which allows silent breakaway.

Machine: Windows 11 Pro 10.0.26200, Temurin JDK 25.0.2+10, Node v24.12.0. Not elevated.

## Result: go for the dev containment path

| Case | Order in the holder | Killed | Expected | Observed |
| --- | --- | --- | --- | --- |
| D1 | job E first, then ping | the holder only | ping gone | ping and its conhost gone 4 ms after the kill was seen; Node alive |
| D2 | job E first, then ping | Node only | holder and ping gone | Node, holder, ping and both conhosts gone within 55 ms |
| D3 (control) | ping first, then job E | the holder only | ping alive | ping and its conhost **alive** at 3 s |

D1 and D2 hold, so Spec F Q13 (a runner-side job-owning launcher) is not needed: the Engine job E
contains the dev Engine's children although the Engine sits in libuv's job L. D3 shows the hole E
closes: a child created before E exists breaks away silently from L and outlives its parent.

## Facts recorded

- Node's job L has LimitFlags **0x3C00** (`BREAKAWAY_OK | SILENT_BREAKAWAY_OK | KILL_ON_JOB_CLOSE |
  DIE_ON_UNHANDLED_EXCEPTION`), read by the holder with `QueryInformationJobObject(NULL, 9)` before
  it created E.
- `AssignProcessToJobObject(E, GetCurrentProcess())` from inside L returned ok=1, err=0. Afterwards the
  holder's innermost job reads 0x2000 (E), so E nests under L.
- A child spawned after E (`ProcessBuilder`) is in E (`IsProcessInJob(ping, E)` = 1), and so is its
  conhost. Silent breakaway from L does not apply because E, the immediate job, forbids breakaway.
- A child spawned before E is in no job at all (D3: `inAnyJob=0` for ping and its conhost).
- **Correction to §1.4:** the holder JVM's own conhost is in **no** job (`inAnyJob=0` in D1, D2 and D3),
  not in L. It still ended 3-5 ms after the holder every time, because its only client was gone.
- When L closes (D2), the holder reports exit code 0 and Node exit code 1 (`taskkill /F`), consistent
  with `job-close-code.md`: a job closure gives code 0.

## Method

Sources: `F:\agent-sandbox\design-b6d536\spike\NestedLibuv.java` (driver and holder, single-file
source mode) and `spike\nested-libuv-node.cjs` (Node's `child_process.spawn`, `shell:false`, not
detached, `windowsHide:true`, as `spawnEngineChild` does). Raw logs:
`spike\out\libuv-nesting-{D1,D2,D3}.log`.

The driver JVM (itself in no job) starts Node, Node starts the holder, and the holder creates E with
exactly `KILL_ON_JOB_CLOSE` (0x2000) and spawns `ping -n 60 127.0.0.1`, before or after E per case.
The driver holds SYNCHRONIZE handles to every process, runs `taskkill /F` on the victim, polls every
50 ms for 3 s and reads exit codes. Survivors were killed by the driver afterwards.
