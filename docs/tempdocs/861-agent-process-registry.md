---
status: rev 2 — all seven phases implemented (Phases 1-5 merged, Phases 6+7 in PR #559); closure record at §9
created: 2026-08-25
updated: 2026-08-25
revision: rev 2 — 15 review findings folded in; §6.2/§6.3/§6.4 corrected as text before any worker brief; Phase 5's own review (F-1..F-8) folded into §7.1; Phases 6+7 as-landed + closure outcome record added by W6
author: agent session (Opus 5, 1M context)
charter: agent-spawned background processes outlive their spawner with no adjudicable ownership claim — and the one reaper that existed was never wired
phases:
  - "Phase 1 (W1) — shared grammar + reader: MERGED (scripts/dev/lib/process-record.cjs)"
  - "Phase 2 (W2) — record shape, identity verification, pruning: MERGED #549 (agent-spawn-record.cjs, process-identity.cjs)"
  - "Phase 3 (W3) — producers (ui_shot.py, serve-worktree-fe.cjs, otlp-sink-ensure.mjs): MERGED #554"
  - "Phase 4 (W4) — the reaper and the §6.3 matrix: MERGED #552 (scripts/dev/lib/agent-spawn-reaper.cjs)"
  - "Phase 5 (W5) — reap occasions and hook bodies: PR #558, independent review APPROVE-WITH-FIXES (F-1..F-8), fixes folded in — see §7.1 as-landed"
  - "Phases 6+7 (W6) — the file->manifest gate and the ui-shot-cleanup teardown sweep: PR #559, landed together per the interlock — see §7.1 Phase 6+7 as-landed and §9's closure record"
---

# 861 — Agent-spawned process ownership and reaping

> **Opening framing was revised by the evidence.** The charter assumed "no ownership record exists".
> A record did exist for the live leaked process found while chartering — and the only consumer of
> such records has never been wired into the hook manifest (§3c), while an always-loaded rules file
> states that it is. The corrected framing is §5.1's third one: a process's lifetime is bound to an
> owner that can die silently, and nothing renegotiates the binding.

> **Rev 2 (adversarial review).** Verdict: approve with amendments. The architecture held at source —
> sibling scope over widening `foreign/`, the two-tier verdict, reading≠reaping, plural reap
> occasions, and the §6.7 exclusion argument were each confirmed; the headline claim re-verified
> cleanly (38 hook files, 37 catalog entries, exactly one orphan).
>
> The findings cluster at one seam: **three answers this tempdoc had already reached in §5 were
> dropped on the way into §6.** §5.5 named owner-activity staleness as the ownership unit, and §6.3
> reduced it to a bare lease expiry (A1). §5.6 flagged pid reuse and identity evidence, and §6.2
> asserted the evidence was "already available" when it is projected away (A2). §5.7 risk 6 asked for
> genuinely different producer flavors, and §6.5 shipped two of the same flavor while §6.2's
> `ownerless-singleton` mode had zero producers (A6). That is a real and repeatable failure mode —
> a design section restating its own theorization more loosely than the theorization stated it — and
> it is recorded here rather than quietly fixed, because the mechanism (not the three instances) is
> the transferable lesson. Amendments are folded in below and marked `[A<n>]` at the point of change.

## 1. The bug-class

Agents spawn long-lived processes — Vite dev servers, measurement drivers, poll loops — with **no
ownership record**. When the spawning agent dies (session end, the ~60-minute background-task kill,
a crash, a compaction-orphaned turn), the process survives with nothing that can find it, attribute
it, or reap it. On Windows, in a repo whose worktrees share `node_modules` through junctions, a
surviving process holds files in the **main checkout**, so the damage is not confined to the dead
agent's worktree.

Documented incidents (three-plus, this arc):

1. **Eight leaked Vite servers** — six of them from a single measurement harness run — held main's
   `node_modules` through worktree junctions until `lightningcss.node` was locked and `vite`
   vanished from main (`'vite' is not recognized`; recovery required `npm ci`).
2. **`TaskStop` does not kill child bash loops** (2026-07-22 certification campaign) — caused
   concurrent-driver corruption. The surviving pattern was a detached `Start-Process` driver plus
   self-terminating (<590 s) polls. Recorded in `.claude/rules/agent-lessons.md`.
3. **2026-08-25: an orphaned Vite (port 5175, parent PID dead)** from a *completed* agent's
   worktree blocked `remove-worktree` twice — the script found and `taskkill`ed one holder and
   still failed; manual `Stop-Process` was needed.

The current mitigation is **prose** — "launch as a bare main process", "kill and confirm" in
`.claude/rules/agent-lessons.md`. Prose is the ~70% tier, and it has demonstrably not held: all
three incidents post-date the prose.

## 2. Live evidence gathered while chartering (2026-08-25)

Not a reconstruction — this is the machine's state at charter time, captured read-only.

**A fourth instance of the bug-class was live during chartering.** `Get-CimInstance Win32_Process`
showed a Vite on port 5174 whose **parent PID was dead**:

| | PID | Parent | Parent state | Root served | Registered? |
|---|---|---|---|---|---|
| Orphan | 41064 | 9444 | **DEAD** | `F:\justsearch-public\modules\ui-web` (**MAIN**) | record exists, stale |
| Dev-runner's Vite | 36624 | 30840 → 31276 → 36360 → 31212 (`dev-runner.cjs`) | alive | main | yes (`tmp/dev-runner/active.json`) |

The orphan is serving out of the **main checkout's** `node_modules` — i.e. it is holding exactly
the files incident 1 lost.

**The orphan's record exists and did not help.** `scripts/jseval/tmp/ui-shot-server.json` holds:

```json
{ "pid": 41064, "port": 5174, "root": "F:\\justsearch-public\\modules\\ui-web",
  "stderr_log": "tmp\\ui-shot-vite-5174.log",
  "provenance": { "branch": "main", "head": "cb95fc3b" },
  "started_at": 1787617325.4663043 }
```

`provenance.head` is `cb95fc3b`; main is at `2a99dcd6`. The record is from an **earlier session**
that ended without its reaper running. So the failure here is not "no record was written" — a
record was written and **no reap point consumed it**. That distinction shapes the design: the
missing half is reaping, at least as much as recording.

**Eighteen worktrees are registered** (`git worktree list`), most from completed work. Every one is
a potential host for a process holding main's files through a junction.

## 2-bis. The recurrence ledger — this is not a three-incident anecdote

The conditions store (`docs/observations.md`) has been recording this bug-class for **two months**,
across six distinct anchors. Counts are the store's own `seen:` fields:

| Condition | seen | first → last | What it records |
|---|---|---|---|
| `obs:remove-worktree` | **14** | 2026-06-21 → 2026-08-07 | The most-recurred condition in the store. Orphaned worktree dirs, held-handle removal failures, the holder-scan self-match |
| `obs:ui-shot-cleanup` | 3 | 2026-06-16 → 2026-07-16 | **The unwired reaper (§3c) — logged three times and never actioned**, plus two consequence reports |
| `obs:ui-shot` | 1 | 2026-08-06 | ui-shot auto-serve leaks its Vite child; two survived one session |
| `obs:serve-worktree-fe` | 1 | 2026-08-07 | `TaskStop` orphans the child Vite; blocks worktree removal |
| `obs:default-index` | 1 | 2026-06-22 | Orphaned dev-runner/Worker processes hold `index/default.index.lock` |
| `obs:vite-config` | 2 | 2026-08-18 | Vite proxy never propagates client disconnect (socket leak — adjacent, not this class) |

Three things in that ledger change the shape of the problem as the charter framed it.

**(a) The root cause was known on 2026-06-16.** `obs:ui-shot-cleanup`'s first entry is verbatim the
§3c finding: *"ui-shot-cleanup.mjs exists on disk but is not wired in `.claude/settings.local.json`
(hooks-reference.md documents it as a SessionEnd hook)"*. It was re-observed twice more. The
knowledge existed; nothing converted it into a change. That is a fact about the *tier* of the
remedy, not about anyone's attention — an inbox note is not a mechanism.

**(b) The leak breaks builds while the owning agent is still alive.** The 2026-07-15 entry:
the port-5174 Vite *"holds handles under `modules/ui-web`, so `:modules:ui:installWebDependencies`
fails with npm exit -4048 (libuv UV_EPERM on Windows) — looks like a build defect, is a live file
lock… `ui-shot-cleanup` only fires at SessionEnd, so **ANY capture-then-build session hits this**"*.
The 2026-07-16 entry names the locked file: `node_modules/lightningcss-win32-x64-msvc/*.node`.

This is decisive for the design. A remedy keyed on *owner death* — however well built — does not
address the most frequent observed harm, which happens mid-session with the owner alive and healthy.
The note's own proposed remedy is a reap point the charter did not list: **before a build**.

**(c) Failed teardown corrupts the worktree.** `obs:serve-worktree-fe`: when the held handle makes
removal fail, *"remove-worktree then guts the tree `.git`-link-first, leaving the known no-`.git`
shell"*. So the cost of a leak is not only "removal is blocked" (incident 3) but a half-deleted
worktree in an unrecoverable-by-git state. This raises the stakes on ordering inside teardown and is
a defect worth fixing in the same pass.

**(d) The class is wider than Vite.** `obs:default-index` records orphaned dev-runner/Worker JVMs
holding the Lucene index lock. Any design keyed to "FE dev servers" is scoped to the loudest
instance rather than the class.

## 3. What already exists (explore-before-implementing)

### 3a. The dev-runner run registry — the shape to extend

**The dev-runner's own run state** (`scripts/dev/dev-runner.cjs:57-68`) lives under
`<mainRepoRoot>/tmp/dev-runner/`: `active.json` (the shared backend lease — `runId`,
`holder.agentSessionId`, `lease.{durationSec,renewedAt,expiresAt,sequence}`), `runs/<runId>/run.json`,
`sessions/` (per-session activity stamps), `op-leases.json`, `interference-events.ndjson`. Writes go
through `writeJsonAtomic` (temp-file + rename, `dev-runner.cjs:117-129`).

**The foreign-run register** (tempdoc 844 D3) is the closer precedent: a directory
`<mainRepoRoot>/tmp/dev-runner/foreign/` holding **one JSON file per producer process**, named
`<producer>-<pid>.json` (`scripts/jseval/jseval/run_register.py:73-80`), written atomically
(`run_register.py:163-180`) and deliberately placed *beside*, never inside, the dev-runner's own
enumerated children so it is structurally invisible to lease/admission logic
(`scripts/dev/justsearch-dev-mcp/server.mjs:844-857`). Record fields (`run_register.py:98-133`):
`schemaVersion`, `producer`, `recordId`, `pid`, `ports.api`, `repoRoot`, `dataDir`, `workload`,
`inferenceRequested`, `gpuBound`, `sessionId` (read from `tmp/agent-telemetry/current-session-id`),
`startedAt`. Registration is best-effort: a failed write never fails the run, it just leaves the
reader with "not registered, only observed".

The reader `probeForeignRuns()` (`server.mjs:981-1097`) is the shape worth lifting wholesale:

- **State vocabulary** (`schemas.mjs:654`): `live | unreachable | stale | unreadable`, computed at
  `server.mjs:1040` as `portAnswered ? 'live' : (pidAlive ? 'unreachable' : 'stale')`. PID liveness
  is `process.kill(pid, 0)` (`server.mjs:132-135`).
- **`identityStale`** (`server.mjs:1055`) — the port answers but the record's pid is dead: something
  is up, but this record may no longer describe it.
- **`source: 'registered' | 'observed'`** (`schemas.mjs:647-656`) — registered entries carry full
  identity; observed entries carry only port/kind/probePath/attribution, because nothing else is
  known. Registered beats observed: a port already explained by a record is never re-emitted as
  observed (`server.mjs:1088`).
- **Tri-state at the top**: `foreignRuns` is `null` (didn't probe / probe threw), `[]` (probed,
  found nothing), or populated (`schemas.mjs:667-671`) — never a confident empty on failure.

Two limits matter for 861. First, it covers **backends only** — currently a single producer
(`jseval`'s eval backend). Second, **there is no sweep or GC for `foreign/` at all**: stale records
are reported and never deleted, deliberately — "deleting another lifecycle's state on a read is
exactly the kind of confident guess §12.2 forbids" (`server.mjs:967-970`). So the one existing
registry of this shape has the same missing half as §2's evidence: recording without reaping.

`scripts/dev/serve-worktree-fe.cjs:55-73` already *reads* dev-runner state (`detectBackendPort`,
resolving the **main** repo root from a worktree via `mainRepoRoot()`, :43-53) to borrow a running
backend. It establishes two precedents 861 needs: a non-backend helper consulting the registry, and
a worktree-resident script resolving main's state root.

It is **also a leak source**, and instructively so. It spawns Vite in the *foreground*
(`stdio: 'inherit'`, exits when the child exits, :115-121) — which looks safe and is not, because
agents run it as a background task: `TaskStop` kills the tracked parent and the `node vite.js`
grandchild survives (`obs:serve-worktree-fe`, 2026-08-07). Foreground spawning is no defence when
the whole invocation is what gets killed. This is incident 2's mechanism (`TaskStop` does not reach
children) reaching a second helper, and it means **the leak class is not "detached spawns"** —
scoping a remedy to detachment alone would miss this one entirely.

### 3b. The ui-shot proto-registry — a single-slot record, and why six leaked

`scripts/jseval/jseval/ui_shot.py` is the closest existing thing to a spawn registry, and its
limits explain incident 1 directly:

- `_SERVER_INFO_PATH = Path("tmp/ui-shot-server.json")` (`ui_shot.py:64`) is a **relative** path
  resolved against CWD, holding **exactly one** record. Start a second server and the first
  record is overwritten — the process stays alive, its record does not. *n* servers, one
  reapable. This is the mechanism behind "six from one measurement harness".
- `_start_vite_server` (`ui_shot.py:309-410`) spawns **`DETACHED_PROCESS | CREATE_NO_WINDOW`**
  deliberately (615 §28 U1) so the server survives its parent. Survival is the feature; the
  absence of a reaper is the defect.
- The record is good as far as it goes: `pid`, `port`, `root`, `stderr_log`, `provenance`
  (branch + head), `started_at` (`ui_shot.py:401-408`). It has **no** `sessionId` and no
  worktree/owner field — so a record cannot be attributed to the agent that made it.
- Liveness is already two-factor: `_pid_alive` (`ui_shot.py:154`) plus `_process_cmdline`
  provenance (`ui_shot.py:181`), because "a port that merely RESPONDS may be a foreign process
  that took our recorded port after our vite died" (615 §34/§35). This is the right check and
  should be lifted, not re-derived.

### 3c. The one existing reap point is **not wired** — verified root cause

`scripts/agent-analytics/hooks/ui-shot-cleanup.mjs` exists, is written as a SessionEnd hook, and is
listed in the always-loaded `.claude/rules/hooks-reference.md:46` under **"Transparent (no action
needed)"** — i.e. the project's own rules file tells every agent it is running. It is not.

Verified against every authority in the chain:

| Authority | Contains `ui-shot-cleanup`? |
|---|---|
| `governance/agent-hooks.v1.json` — `hooks` catalog (the manifest is the authority) | **no** |
| `governance/agent-hooks.v1.json` — `bindings.SessionEnd` (binds only `dispatch`, `compact-restore`) | **no** |
| `.claude/settings.local.json` — the live, generated wiring Claude Code actually reads | **no** |
| `.claude/settings.json` — public template | **no** |
| `docs/reference/contributing/tier-register.md` | **no** |
| `.claude/rules/hooks-reference.md:46` — always-loaded prose | **yes** |

Since `.claude/settings.local.json`'s hooks block is *generated* from the manifest
(`governance/agent-hooks.v1.json:5`; `scripts/codegen/gen-agent-hooks-wiring.mjs`), a hook absent from
the manifest can never be wired. So **the only reaper of leaked Vite servers in this repository has
never run.** Every ui-shot Vite leaks by construction unless something else happens to kill it —
which is the complete, primary-source explanation of §2's live orphan (record written, no consumer)
and of incident 1's eight survivors.

This is also a `retire-with-a-sweep` residue case of the exact kind tempdoc 742 describes: an inert
artifact that an always-loaded rules file still presents as live, i.e. **false authority**. It is
worse than a missing feature, because it caused agents (and this charter's first draft) to reason as
though a reaper existed.

A second, structural finding falls out: `scripts/governance/gates/hook-integrity/enforcer.mjs`
checks manifest→wiring→load→bite, but has no check in the **file→manifest** direction — no
"orphaned hook file" rule. That is why a hook file could sit in `scripts/agent-analytics/hooks/`
for the project's whole public history without anything noticing it was never registered.

### 3c-bis. What the unwired reaper would have gotten wrong anyway

Wiring it as-is would not have been enough. It reads `tmp/ui-shot-server.json`, kills the PID with
`taskkill /F /T`, and unlinks the file. Three independent gaps:

1. **Single-slot + guessed location.** It probes two hardcoded candidates (`<root>/tmp/…` and
   `<root>/scripts/jseval/tmp/…`) because the producer's path is CWD-relative. It reaps at most
   one server per candidate.
2. **SessionEnd is the wrong sole trigger.** It does not fire in subagents
   (`parent-hooks-dont-fire-in-subagents`), and a session killed at the 60-minute ceiling or
   crashed never reaches it. Incident 2 is the same defect one layer down: `TaskStop` does not
   reach child bash loops. Any remedy scheduled in the spawner's last moments inherits this.
3. **It does not re-verify provenance before killing.** `ui_shot.py` checks cmdline before
   *reusing* a PID, but the cleanup hook `taskkill /F /T`s the recorded PID blind. A recycled PID
   is a live foot-gun in the one component whose job is killing things — related in kind to the
   `remove-worktree` self-match incident (§3d).

### 3d. `remove-worktree.cjs` — a holder scan with no registry behind it

`scripts/dev/remove-worktree.cjs` already does the hardest part of teardown: `removeJunctions`
unlinks junctions **link-only** so deletion never recurses into main's real `node_modules`
(:49-82); `deleteTree` retries and falls back to a `\\?\` long-path .NET delete (:227-259); and
`reportHolders`/`filterHolders` (:181-224) scans the WMI process table for processes whose command
line names the worktree path, excluding its own PID, its ancestor chain, and its own invocation
signature (tempdoc 746 item 5, after an agent killed its own process chain mid-run).

Its stated blind spot is exactly the registry's job:

> `no holder found by command line for <p>; a process whose CWD is inside it (but doesn't name it
> on the command line) will not show up here.` (:219-223)

Incident 3 is that blind spot firing. A registry recording `cwd`/`root` per spawn converts the
holder scan from a command-line heuristic into a lookup — and, importantly, the registry can name
a **main-checkout** process spawned *by* a worktree session, which a path-substring scan can never
find.

### 3e. Steering precedent

`scripts/dev/run-gh.mjs` and `scripts/dev/run-py.mjs` are wrapper helpers agents are pushed toward
by the non-blocking `exec-substrate-hint` (PreToolUse/Bash), registered in
`governance/agent-hooks.v1.json` and gate-enforced by `hook-integrity` (wiring, load, bite). This
is the working template for "agents will not remember prose, so steer at the moment of relevance".

### 3f. The dev-runner already solves this — for itself

This is the most consequential finding of the survey, and it reframes the problem. The dev-runner
does **not** rely on anyone else reaping it. It runs a 10-second `setInterval` renewal loop
(`dev-runner.cjs:2079-2152`) that reads its owning session's activity stamp; when the owner has been
stale beyond a grace window it calls `stopRun({ disposition: 'reaped_abandoned' })` and **exits
itself** (:2114-2127). It distinguishes an external graceful stop (a `graceful-shutdown.json` marker,
:2162/:2306) from a crash (`writeSelfExitStopReport`, :739/:2171). It also prunes its own history
(`pruneHistoricRuns`, :378-424 — 14-day / 200-run retention), though only at `start` time (:1506).

So the heaviest, most valuable process in the system already carries a self-expiry supervisor bound
to owner liveness. The leaking class — Vite servers, drivers, poll loops — is precisely the class
that got the *detachment* without the *supervision*. The incident-2 pattern that empirically held
(a detached driver plus self-terminating <590 s polls) is the same mechanism arrived at by hand.

### 3g. The one deliberately immortal daemon

`scripts/agent-analytics/hooks/otlp-sink-ensure.mjs:118-143` spawns `otlp-sink.py` with
`detached: true` + `child.unref()`, and explicitly has **no** SessionEnd kill (:17-20: "killing it
would drop capture for every other live session"). Its liveness is a bare port probe (:87-102) and
re-spawn is idempotent. It has no PID record anywhere.

This is not a leak — it is a shared singleton whose whole point is outliving any one session. Any
rule of the form "every detached spawn must be reapable" has to have a first-class way to express
*intentionally ownerless*, or it will either mis-reap the sink or be quietly exempted into
irrelevance.

### 3h. `run-watcher.mjs` — the heartbeat/verdict template, explicitly not a supervisor

`scripts/dev/run-watcher.mjs` owns one child, touches a heartbeat file every 10 s, appends
`events.ndjson`, and writes `verdict.json` on exit; a separate `check` mode computes
`DONE-OK | DONE-FAILED | PROGRESSING | STALLED-OR-DEAD | NO-RUN` from the state directory alone
(`computeVerdict`, :108-142) with no process introspection. It self-describes as "not a task
scheduler, not a process supervisor" and carries a 90-day retirement condition (:5-15). A template to
imitate, not a substrate to extend.

## 4. Design constraints carried into theorize

- **Two-tier verdict, never a completeness claim.** A process spawned outside any helper is
  invisible to a registry by construction. The output must stay *registered* vs *observed*, in the
  dev-runner's idiom — a registry that implies completeness is worse than none, because it licenses
  "nothing leaked".
- **Recording without reaping is the observed failure.** §2 shows a written record that no reap
  point consumed. Reap points are the deliverable, not a follow-up.
- **Do not weaken detachment.** `DETACHED_PROCESS` is deliberate (615 §28 U1); the fix is a reaper,
  not re-parenting.
- **Kill paths need provenance re-verification.** PID reuse plus `taskkill /F /T` is how a reaper
  becomes an incident (§3c gap 3, and the 746 item 5 self-match precedent).
- **Junction semantics are already solved** in `remove-worktree.cjs`; the registry supplies
  *diagnosis*, it must not re-implement *deletion*.

## 5. Theorization

Not a design. This section maps the space, questions the charter's own framing, and records the
ideas worth carrying into design even where they will not be chosen.

### 5.1 Three framings, and why the third is the real one

**Framing A — "there is no registry."** The charter's framing: agents spawn processes with no
ownership record, so build one. It is the obvious reading, and §2 falsifies it as *the* reading: the
live orphan **had** a record (`scripts/jseval/tmp/ui-shot-server.json`, pid 41064, port 5174), and
the record did not save it.

**Framing B — "there is no reaper."** Better-supported. `foreign/` records are never swept by
anything (`server.mjs:967-970`, by deliberate policy), and the sole reaper of the sole other record
type was **never wired** (§3c) — and even wired, it would fire on the one event that does not happen
when things go wrong. Framing B is where the concrete bug lives, and §3c is its proof. But Framing B
still treats reaping as a *janitorial* act performed by a third party who happens to run, which
leaves "who is authorized to kill this?" unanswered.

**Framing C — "process lifetime is bound to an owner that can die silently, and nothing renegotiates
the binding."** This is the framing the evidence actually supports, and §3f is why: the dev-runner
faces the identical hazard and solves it *without* a third-party janitor, by making the process
itself responsible for noticing that its owner is gone. Under Framing C the question is not "who
finds the orphan" but "why is a process allowed to exist whose owner is unverifiable".

Framing C also explains why prose failed. "Kill and confirm" asks the *dying* party to act, and the
dying party is the one that, by construction, may not get a turn. Any remedy that runs in the
spawner's own last moments — SessionEnd hooks, `finally` blocks, `TaskStop` — inherits that defect.
Incident 2 is the same shape: `TaskStop` does not reach child bash loops, so the surviving pattern
was self-termination.

### 5.2 Solution families

**Family 1 — external reaper over a registry (the charter's sketch).** A registry that helpers write;
sweeps at `remove-worktree`, session-closeout, `world-state`. *Strength*: no new long-lived
processes, cheap, matches the repo's register+gate idiom, and directly fixes incident 3 (the holder
scan gets a lookup). *Weakness*: only reaps when someone happens to sweep, and the sweeper needs
authority to kill (§5.4). *Note*: sweeps can be attached to many cheap triggers, which is a real
answer to Framing B's "fires at the wrong time" — the fix for one-trigger-that-doesn't-fire is
several triggers that do.

**Family 2 — self-expiry / supervised lifetime.** The spawned process carries a deadline or a lease
it must see renewed, and exits on its own when the owner stops renewing. *Strength*: this is the one
mechanism with two independent successes in this repo — `dev-runner`'s abandonment self-reap (§3f)
and incident 2's surviving `<590 s` self-terminating polls. It needs no sweeper and no authority to
kill anyone else. *Weakness*: third-party binaries (Vite) cannot renew a lease, so it needs either a
thin supervisor process per spawn (which is itself a process, though *ours*, and therefore trivially
self-terminating) or a watchdog thread inside a wrapper. Cost: one extra process per server, or a
`--deadline` argument and a timer.

**Family 3 — fix identity, spawn fewer.** §3b's single-slot CWD-relative record has a property worth
naming: **the same defect causes both over-spawning and under-reaping.** Because the record's path is
CWD-relative and single-slot, a second harness invocation neither finds the first server (so it
spawns another) nor preserves its record (so the first becomes unreapable). Six leaked servers from
one harness is exactly what that produces. Making the record absolute, keyed by *worktree root*, and
one-file-per-server fixes reuse and reaping in a single move. This may be the highest
value-per-line change in the whole space, and it is nearly free.

**Family 4 — blast-radius reduction (independent of ownership).** The reason a leaked Vite is a
*repo* emergency and not a nuisance is the junction: `ui_shot._ensure_node_modules_junction`
(`ui_shot.py:265-295`) creates a Windows junction (`mklink /J`) from a worktree's
`modules/ui-web/node_modules` into the **main checkout's** `node_modules` whenever the worktree has
none — so a worktree process holds main's `lightningcss.node` open. (Claude Code also creates
junctions natively at worktree creation; `remove-worktree.removeJunctions` exists because of both.)
`prepare-worktree.cjs:81` gives a properly-prepared worktree a *real* `npm ci` install, so the
junction is a **convenience fallback for unprepared worktrees** that converts "FE deps missing" into
"holds main's files hostage". Directions: refuse to junction and fail with the `prepare-worktree`
remedy (loud, cheap, costs an unprepared-worktree convenience); or junction but record the resolved
target so lock diagnosis is a lookup. Worth noting this family reduces *severity* without touching
*ownership* — it is orthogonal, and it is the only family that would have prevented incident 1's
`npm ci` recovery even if the reaper had failed.

**Family 5 — structural gate on spawn sites.** The repo's characteristic move: a register plus a
gate that fails the build on an unregistered referencer (the `execution-surface` gate over
`SearchTrace` is the model). Analogue: every site in `scripts/` that spawns detached
(`DETACHED_PROCESS`, `detached: true`, `Start-Process`, `subprocess.Popen(..., start_new_session)`)
must appear in a spawn-site register declaring its ownership mode. *Strength*: converts a ~70% prose
rule into a ~100% gate, which is precisely what CLAUDE.md's own `before-appending-to-rules` says a
load-bearing must-rule needs. *Weakness*: covers only spawn sites *in the repo's scripts* — an
agent's ad-hoc `run_in_background` bash is invisible to it. §3g's otlp-sink also requires the
register to have a first-class `ownerless-singleton` mode, or the gate will be evaded rather than
satisfied.

**Family 6 — steer, don't legislate.** The `exec-substrate-hint` template (advisory PreToolUse/Bash
hook, per-session marker de-dup at `tmp/agent-telemetry/exec-substrate-nudged-<sessionId>.json`,
registered in `governance/agent-hooks.v1.json`, wiring gate-enforced by `hook-integrity`) is the
proven way to move agents onto a wrapper. This family cannot *guarantee* anything; it raises the
fraction of spawns that land inside a covered helper, which is what makes the other families' cover
grow over time. It is a multiplier on families 1/2/5, not an alternative to them.

These are not exclusive. The natural composition is 3 (identity) + 2 (self-expiry) as the mechanism,
1 (sweep at several points) as the safety net, 5 (gate) to stop regression, 6 (hint) to grow cover,
and 4 (blast radius) as a separable decision.

### 5.3 Projection or fork? — the `foreign/` register question

CLAUDE.md requires this to be asked explicitly before authoring a new representation of something
already modelled. The candidate here is `tmp/dev-runner/foreign/`.

*For extending it*: identical shape (one JSON file per process, atomic write, pid/repoRoot/sessionId/
startedAt), identical reader semantics worth inheriting (`source`, four-state vocabulary,
`identityStale`, tri-state null/empty/populated), already surfaced through `quick_health`, already
gitignored, already resolved relative to the **main** repo root — which solves the "records must not
scatter per-worktree" problem for free.

*Against*: `foreign/` means "a **backend** started outside the dev-runner", and its reader probes
`/api/status` and `/health` to decide `live`. A Vite is not a backend; a poll loop has no port at
all. Widening `workload` until it means "any process" is how a register becomes a second authority
that drifts — the exact failure the projection-vs-fork rule exists to prevent.

*Third option*: a **sibling directory under the same root** with the same record grammar and a shared
reader — e.g. `tmp/dev-runner/agent-spawns/` — deliberately placed beside, not inside, the
dev-runner's enumerated children, exactly as `foreign/` itself was (`server.mjs:844-852`). This
inherits the shape and the state root without overloading `foreign/`'s meaning, and it repeats a
placement decision this repo already made once, for the same reason. Liveness generalizes cleanly if
the record declares its own probe (`{kind: 'port', port}` / `{kind: 'pid-only'}`) instead of the
reader hardcoding backend endpoints.

Design should decide between "widen `foreign/`" and "sibling with shared grammar", and say why.

### 5.4 The registry's real product is *authority*, not *discovery*

`remove-worktree.cjs` can already find holders (`filterHolders`, :181-224). It deliberately refuses
to kill them: "an unconditional auto-kill risks a legitimate process (an open editor, another
agent's session)" (:206-213). That refusal is correct *given a command-line substring heuristic* —
and it is why incident 3 needed a human `Stop-Process`.

So the interesting claim is: **a registry does not primarily make leaked processes findable — it
makes killing them defensible.** A process is safe to auto-kill when four things hold together:
it is *registered* (we started it), *ours* (provenance re-verified against the live command line),
its *owner is verifiably gone*, and its *identity still matches* (pid + OS creation time, not pid
alone). Nothing weaker licenses an automatic kill; the `remove-worktree` self-match incident (746
item 5, an agent killing its own process chain) and §3c gap 3 (`ui-shot-cleanup` blindly
`taskkill /F /T`-ing a possibly-recycled pid) are the two ways this goes wrong in opposite
directions — killing too much, and killing the wrong thing.

Corollary: the **observed** tier must never be auto-killed, only reported with a ready-to-run kill
line — which is exactly the existing `remove-worktree` policy, so the two tiers map onto two
already-established behaviours rather than inventing a policy.

### 5.5 What is the unit of ownership?

Four candidates, with the evidence for each:

- **PID ancestry** — what §2's orphan detection used (parent 9444 dead). Cheap, but wrong for
  *deliberately* detached processes: detachment is the feature (615 §28 U1), and a dead parent is
  normal for the otlp-sink. Diagnostic signal, not an ownership model.
- **Agent session id** — already plumbed everywhere (`--session-id`, `holder.agentSessionId`,
  `tmp/agent-telemetry/current-session-id`, observation shards) and it survives subagents, which
  inherit the parent's id. The natural *attribution* key. But session liveness is not directly
  observable, which is presumably why the dev-runner does not use it directly...
- **Lease / TTL over a session activity stamp** — ...it uses `sessions/` activity stamps plus a
  grace window instead (§3f). Passive expiry sidesteps the unobservability of session liveness. This
  is the repo's existing answer and should be the default assumption.
- **Heartbeat via the hook layer** — a speculative but attractive idea: hooks fire on essentially
  every agent action, so a PreToolUse hook could refresh the activity stamp for the current session,
  making "the owner is alive" a cheap by-product of the agent doing anything. `sessions/` may
  already be exactly this; design should check before inventing a second heartbeat.

### 5.6 Hidden assumptions worth challenging

- *"Processes leak because the agent dies."* Not only. A long-lived session accumulates spawns it
  has forgotten (the six-from-one-harness case leaked while the agent was **alive**). A model keyed
  purely on owner-death misses the whole over-spawning half — which Family 3 addresses instead.
- *"The pid identifies the process."* On Windows pids recycle, and the record already outlives the
  process by hours (§2: a record from an earlier session, `head cb95fc3b`, still on disk at
  `2a99dcd6`). Identity should be pid + OS process creation time; `Win32_Process.CreationDate` is
  already available in the CIM query `remove-worktree` runs.
- *"Reaping is the goal."* The goal is that main's `node_modules` is never held hostage and a
  worktree can always be removed. Family 4 achieves part of that with no reaper at all.
- *"A registry tells you what is running."* It cannot, ever. Cover is bounded by helper adoption.
  Any output that reads as a completeness claim is worse than none, because it licenses "nothing
  leaked" — the tri-state `null | [] | [...]` discipline (`schemas.mjs:667-671`) exists for exactly
  this and must be carried over verbatim in spirit.
- *"Detachment is the problem."* It is deliberate and load-bearing (615 §28 U1: a detached `.cmd`
  shim dies immediately on Windows; `node.exe` detaches cleanly with captured stderr). The fix is a
  deadline, not re-parenting.

### 5.7 Risks a design must answer

1. **Mis-kill.** A sweep that kills the dev stack another session leased, an editor, or a human's
   deliberately-started server. Mitigation: the four-condition rule of §5.4 plus never auto-killing
   the observed tier.
2. **The reaper becomes the incident.** Two precedents already exist (746 item 5; §3c gap 3). A kill
   path deserves its own adverse-precondition test — `green-masked-destructive` applies directly:
   test the "record points at a recycled pid" branch, not only the happy path.
3. **Records scattering.** ui-shot's CWD-relative path is the cautionary tale; the registry must
   resolve the **main** repo root (`serve-worktree-fe.cjs:43-53` shows how) so worktree-resident
   producers write to one place.
4. **Concurrency.** Multiple sessions writing one registry. Solved by construction with
   one-file-per-record plus atomic temp+rename — the pattern `foreign/`, the dev-runner, and the
   observation shards all already use. No locking needed.
5. **Unbounded record growth.** `foreign/` has no GC. Whatever 861 adds should not repeat that;
   `pruneAgentEvidence` (`files.mjs:147-200`) and `pruneHistoricRuns` are both existing templates.
6. **Substrate with no consumers.** `substrate-without-consumer-flavors` is a named handle here. A
   registry with one producer is a fork of ui-shot's info file with extra ceremony. Landing with at
   least two genuinely different producers (a port-bearing server and a portless driver/loop) is
   what tests the grammar.
7. **Scope inflation.** The honest core is small: fix identity, add a deadline, sweep at teardown.
   A full process-supervision framework is not warranted by three incidents and would collide with
   `run-watcher`'s deliberate narrowness (§3h).

### 5.8 A candidate invariant: *detached needs a deadline*

> **Superseded during design.** The predicate "detached" turned out to be wrong: `serve-worktree-fe`
> spawns in the foreground and leaks anyway (§3a). The corrected statement is §6.10's — *outlives its
> spawner*, however it was spawned. Kept here as written because the correction is the useful part.

If this tempdoc points at a broader principle, it is this: **a process deliberately outliving its
spawner must carry either a self-expiry or a registered claim that a third party can adjudicate;
survival without one of the two is a leak by construction.** The corollary is the interesting part —
*the remedy may not live in the dying party's last moments*, because the dying party is exactly the
one that may not get a turn. That single sentence explains all three incidents, and it explains why
`TaskStop`, SessionEnd hooks, and `finally`-block cleanup all failed while self-termination held.

It is small enough to state as a rule and structural enough to gate (Family 5), which is the
combination CLAUDE.md's `before-appending-to-rules` asks for. Whether it earns a rule line or stays
as a gate plus a hint is a design decision, not a theorization one — but note the existing prose
already occupies that slot and did not hold, which is evidence about the *tier*, not the wording.

### 5.9 Deliberately left open for design

- Widen `foreign/` vs. a sibling register with shared grammar (§5.3).
- Self-expiry supervisor vs. multi-trigger sweep as the primary mechanism — or both, and which one
  is allowed to be best-effort.
- Whether the junction fallback (`_ensure_node_modules_junction`) should be refused outright
  (Family 4) — a severable decision with its own risk/benefit, and arguably its own tempdoc.
- Whether the spawn-site gate is in scope now or is the follow-up that stops regression later.
- How `otlp-sink`'s intentional ownerlessness is expressed rather than exempted (§3g).

## 6. Design

### 6.0 What the evidence forces

Three findings from §2-bis and §3c constrain the design more than the charter's sketch did:

1. **The reaper was never wired** (§3c). So the first-order defect is not "no registry exists" —
   it is a consumer that does not run, plus a rules file asserting that it does.
2. **The dominant observed harm happens with the owner alive** (§2-bis b: capture-then-build EPERM).
   Any design keyed solely on owner death does not address the most frequent case.
3. **The leak class is not "detached spawns"** (§3a: `serve-worktree-fe` spawns in the foreground and
   still leaks, because `TaskStop` kills the tracked parent and the grandchild survives). A remedy
   whose predicate is detachment has the wrong predicate.

So the design needs an ownership record (for authority), *two* reap occasions with *different*
justifications (abandonment and conflict), and a predicate based on **producer adoption**, not on how
the process was spawned.

### 6.1 One grammar, two scopes — the projection answer

`tmp/dev-runner/foreign/` already implements a process-record register (§3a) that is right in every
respect except scope: it means "a **backend** started outside the dev-runner", and its reader decides
liveness by probing backend endpoints. Widening `workload` until it means "any process" would make it
a second authority that drifts — the fork this repo's projection-vs-fork rule exists to prevent.

The design instead separates the **grammar** from the **scope**:

- A single shared *process-record grammar* and a single shared *reader*, extracted from what
  `probeForeignRuns` already does: one file per process, atomic temp+rename, `source:
  registered | observed`, the `live | unreachable | stale | unreadable` vocabulary, `identityStale`,
  and the tri-state `null | [] | [...]` that refuses a confident empty on failure.
- Two *scopes* as sibling directories under the same main-checkout state root: the existing
  `foreign/` (backends outside the dev-runner) and a new one for agent-spawned helper processes,
  placed beside — never inside — the dev-runner's enumerated children, repeating the placement
  decision 844 already made for the same reason.

The record declares its own liveness probe rather than the reader hardcoding endpoints. Today only a
port probe is implemented, because today every producer has a port; a record kind for portless
processes is additive when a portless producer actually arrives (`obs:default-index` suggests one
will), and is deliberately not built ahead of it.

**Reading never deletes.** 844 §12.2 is inherited verbatim in spirit: *"A dev tool must not report
state it did not verify, and must not report success it did not confirm."* A stale record is
*reported* by the reader, never removed by it. Reaping is a separate, explicit **write** path with
its own authority test (§6.3). This resolves the apparent tension between 844's no-delete-on-read
policy and 861's need to reap: they are different operations, and conflating them is what would have
made 861 a fork of 844's policy rather than an extension of it.

### 6.1-bis [A14] Why the external reaper, when self-expiry has the better track record

This choice deserves stating, because §5.2 gave Family 2 (self-expiry) the stronger evidence — it is
the only family with **two independent in-repo successes** (the dev-runner's abandonment self-reap,
§3f; incident 2's self-terminating polls) — and the design nonetheless picks Family 1 (an external
reaper over a register) as the primary mechanism. Choosing the option with the weaker empirical
record needs an argument, not silence.

The argument is that Family 2's successes share a property this problem lacks: **in both cases the
long-lived process was ours, so it could be taught to check a clock.** The dev-runner runs its own
renewal loop; the campaign's poll loops were written to self-terminate. Vite is third-party code. It
cannot renew a lease, and it cannot be taught to notice that an agent session ended.

That leaves only two ways to give Vite a deadline, and both are worse than the register:

- **A wrapper/supervisor process per spawn** — which is another long-lived process, spawned by an
  agent, that can itself be orphaned. It answers the leak question with a smaller instance of the
  leak question. (It is *ours*, so it could self-terminate reliably — but then a supervisor dying
  correctly leaves the Vite it supervised running, which is the original problem again.)
- **A hard absolute deadline inside the helper** — kill the server at T+N regardless. This breaks
  the legitimate long session, and re-creates the 735 G6 incident [A1] in a form with no owner
  signal to consult at all.

So the register is chosen not because it is the stronger mechanism in the abstract, but because it
is the only one available for processes we do not control. The cost is real and worth naming
up front, since it is the same cost §6.9 records: **lease-on-use tracks "used", not "needed".** A
server a session started and then ignored for an hour keeps a fresh-enough claim only while
something touches it; conversely a genuinely idle-but-wanted server can lapse. The activity-stamp
split [A1] is what keeps that from becoming a mis-kill, and the conflict occasion — not the lease —
is what covers the forgotten-but-live case.

Where a process **is** ours, Family 2 remains the better answer, and the design does not displace it:
the dev-runner keeps its self-reap, and any future first-party long-lived process should carry a
deadline rather than rely on this register.

### 6.2 What a record must carry, and why

Beyond the fields `foreign/` already has (producer, pid, repoRoot, sessionId, startedAt, workload):

- **Identity that survives pid reuse** — pid **plus** the OS process creation time. A record routinely
  outlives its process by hours (§2's record was written at a different `HEAD` than the one on disk
  now), and the reaper's whole job is killing by pid.

  **[A2] Correction: this evidence is not "already available".** Rev 1 claimed `remove-worktree`
  already collects the process table this would read from. It collects a table, but
  `getProcessTable` projects with `Select-Object ProcessId,ParentProcessId,Name,CommandLine`
  (`remove-worktree.cjs:119-120`) — **`CreationDate` is projected away**. The implementation must add
  it to that projection, normalize it in PowerShell via `.ToFileTimeUtc()` so the comparison is
  integer-to-integer rather than across locale-dependent CIM datetime string formats, and compare
  for **exact equality**. No tolerance window: a tolerance is a second identity bug waiting, since
  pid reuse on a busy Windows host can land inside any window loose enough to absorb clock jitter.

- **A command-line fingerprint** — so a kill path can re-verify "this is still our process" the way
  ui-shot's *reuse* path already does (`ui_shot.py:181`) and its *cleanup* path does not (§3c-bis).

  **[A2] The conjunction is mandatory.** Identity verification is `pid` **AND** `creationTime` **AND**
  `fingerprint`, never any subset. This matters because the fingerprint alone is a substring match on
  a command line containing `vite` — safe only as the third term of a conjunction whose second term
  is an exact creation-time equality, and dangerously permissive on its own.

- **[A2] An evidence-unavailable rule, stated as a rule.** If the creation time is absent,
  unparseable, or the process table is unavailable, the verdict is **REFUSE and report** — never
  "proceed because nothing contradicted us". This is not a new invention: `classifyActivity` already
  distinguishes unknown from stale, returning `{ known: false, generalStale: false }` when there is
  no activity record (`ownership-verdict.cjs:83-85`), so an absent signal cannot masquerade as a
  permissive one. It needs saying explicitly because every *other* helper in this area fails silently
  to empty — `getProcessTable` returns `[]` on any failure by design (`remove-worktree.cjs:125-131`),
  and a reaper that reads that `[]` as "no conflicting evidence" re-ships exactly the `:25` defect in
  a component whose job is killing things.

- **[A8] Schema versioning is per-scope, not global.** `foreign/`'s record schema version stays at
  `1`; the fields above are **additive and optional** there, so an existing producer
  (`run_register.py`) keeps writing valid records with no coordinated change. The agent-spawns scope
  carries its **own** version constant, versioned independently from day one. Two scopes sharing one
  grammar must not share one version number, or every change to either forces a lockstep bump.

- **[A9] The scope honors `JUSTSEARCH_DEV_RUNNER_STATE_ROOT`.** `dev-runner.cjs:57-58` and
  `resolveForeignRegisterDir` (`server.mjs:869`) both resolve their state root through this override
  so an isolated dev-runner gets an isolated register. A new scope that hardcodes the default root
  would read the wrong directory under an isolated runner and report a **confident empty** — which
  would be the third occurrence of that same bug shape in this tempdoc's evidence.
- **Resource roots the process holds, resolved through junctions.** This is the field the charter's
  sketch was missing and the incident record demands: a Vite serving a worktree may hold the **main**
  checkout's `node_modules` because the worktree's copy is a junction into it
  (`ui_shot._ensure_node_modules_junction`, `ui_shot.py:265-295`). Recording the *resolved* target
  turns "what is locking `lightningcss-win32-x64-msvc/*.node`" from a process-table hunt into a
  lookup, and it is the only way a path-based holder scan can ever find a **main-checkout** process
  spawned by a **worktree** session.
- **A lease with an expiry**, in the dev-runner's existing idiom (duration / renewed-at /
  expires-at). Refreshed by the producer both when it starts a process and when it *reuses* one —
  "lease-on-use". An actively used server keeps extending its own claim; an abandoned one lapses. No
  supervisor process, no renewal daemon, and no dependence on the unobservable question "is that
  agent session still alive".
- **An ownership mode** — `session-owned` versus `ownerless-singleton`. The latter exists so the
  OTel sink (§3g), whose whole purpose is outliving every session, is *declared* rather than
  *exempted*. An exemption is invisible and rots; a declared mode is legible to every reader.

### 6.3 Authority to kill

§5.4's conclusion, made concrete: the register's product is not discovery — `remove-worktree` can
already find holders and correctly refuses to kill them (:206-213). The register's product is a
defensible answer to *may I kill this?*

**[A1] A lapsed lease is not, by itself, a licence to kill.** Rev 1's matrix had
"registered, lease lapsed → reap". That reproduces a bug this repository already shipped, diagnosed,
and fixed. `dev-runner.cjs:2102-2109` carries the record verbatim: *"Proven gap 2026-07-14: a 1k-doc
enrichment wait with a quiet owner session was reaped at ~10 min (disposition `reaped_abandoned`)
while the stack was doing exactly what the owner started it for."* A quiet owner is not an absent
owner. The dev-runner's fix was twofold — take the owner's **activity stamp** as the signal rather
than lease arithmetic alone, and treat a **declared hold** as intent
(`abandonedThresholdMs = max(default + grace, declaredHoldMs + grace)`, :2110-2113). §5.5 of this
tempdoc had already named activity-stamp staleness as the right ownership unit; §6.3 rev 1 lost it.

**[A15]** This is therefore the **third** precedent in this repo of a reaper becoming the incident,
alongside `remove-worktree`'s self-match (tempdoc 746 item 5) and `ui-shot-cleanup`'s blind
`taskkill` (§3c-bis). Three independent instances in one subsystem is not bad luck; it is the base
rate for this kind of code, and it is why Phase 4 carries the review weight it does.

The corrected matrix — the lapsed column is **split**, `ownerless-singleton` is a dimension rather
than a footnote [A5], and identity-verification failure is an explicit cell [A5]:

| Situation | Abandonment sweep | Conflict (build or teardown needs a path it holds) |
|---|---|---|
| Registered, **same session** | reap | reap |
| Registered, other session, **lease live** | leave | report, refuse to proceed |
| Registered, other session, **lease lapsed but owner activity fresh or within a declared hold** | **leave — contention, not garbage** | report, refuse to proceed |
| Registered, other session, **lease lapsed AND owner activity stale** | reap | reap |
| Registered, `ownerless-singleton` (any owner state) | **never reap** | report only |
| Registered, **identity verification fails or is unavailable** | **refuse; retain record, mark failed-verify** | refuse; report |
| Registered, the **dev-runner's own active run** | **never reap** | report only |
| **Observed** only (not registered) | report with a ready-to-run kill line | report with a ready-to-run kill line |

**Reading the Conflict column [A4].** It covers two occasions with different capabilities, so the
cell values are a *ceiling*, not an instruction. **Worktree teardown** may act up to the ceiling —
`remove-worktree` is an executing process that can kill, and refusing to proceed is its whole
contribution. **Before-a-build is advisory only and downgrades every `reap` in that column to
`report`** — it never kills, per the decision below. A worker implementing this table literally must
apply that downgrade; stating it as a footnote rather than a second table keeps one matrix as the
authority, but the downgrade is not optional.

Owner-activity staleness is read through the existing `classifyActivity`
(`ownership-verdict.cjs:82-92`), not a second implementation of the same judgement — the tri-state it
already returns (`known` / `generalStale` / `devStale`) is exactly the distinction the split column
needs, and `known: false` must be treated as *leave*, never as *stale*.

Three rules carry the matrix. **A session may always reap its own registered spawns** — which makes
the mid-session build case (§2-bis b) unambiguous, since the session asking to build is the same one
that started the Vite. **Another session's spawn is contention until its owner is demonstrably
silent**, mirroring the dev-stack's existing `OWNER_CONFLICT` model rather than inventing a second
one. **Absent evidence never licenses a kill**: identity-verify failure refuses, and the record is
**retained** with a failed-verify marker rather than deleted, so the diagnostic trail survives the
refusal (paired with the pruning in §7.1 Phase 2 [A10], which is what stops retention from becoming
unbounded growth).

Every kill re-verifies identity (pid AND creation time AND fingerprint, §6.2) immediately before
acting. The adverse branches are required tests, not happy-path afterthoughts
(`green-masked-destructive`).

**[A4] No kill path runs from a PreToolUse hook.** Rev 1's matrix implied the build-conflict occasion
could reap, while §6.4 described it as advisory — a contradiction a worker would have had to resolve
by guessing. It is resolved as **advisory only**: the before-a-build occasion reports and prints the
remedy, and never kills. Stated plainly because it is uncomfortable: **the most frequently observed
harm (§2-bis b) is deliberately left at the advisory tier.** The justification is proportionality —
that failure is a *mystifying error message*, not data loss, and its whole cost is the time spent
diagnosing an `EPERM`/`-4048` that has no obvious cause. Naming the holder converts it into a
one-line fix, which is the entire fix needed. A hook that kills processes as a side effect of an
agent typing `gradlew` is a much larger hazard than the one it removes.

The **observed tier is never auto-killed**, only reported with a ready-to-run kill line. That is
already `remove-worktree`'s stated policy, so the two tiers map onto two behaviours the repo has
already chosen, rather than a new policy.

### 6.4 Reap occasions

Not one trigger. The failure of the current design is a single trigger that does not fire when
things go wrong; the fix is several cheap, idempotent ones, each justified independently:

- **Session start** — sweep lapsed records. This is the principle made concrete: the remedy for a
  session that died without a turn runs in the *next* session's opening, not in the dead one's last
  moments. It is the only trigger that works for a crash, a 60-minute task kill, or a power loss.
- **Session end** — best-effort reap of this session's own spawns. The fast path when it works; no
  longer the only path. Wired through the manifest this time, and gate-checked.
- **Worktree teardown** — `remove-worktree` consults the register before unlinking junctions, reaps
  what it is authorized to, and **refuses to proceed while an unreapable holder remains**. Refusing
  is the fix for §2-bis (c): today a held handle can leave a half-deleted, `.git`-less worktree
  shell, which is worse than a clean refusal.
- **Before a build** — **advisory only; it never kills [A4]**, in the `exec-substrate-hint` idiom
  (including its per-session marker de-dup). A registered spawn holding paths under the tree a
  `gradlew`/`npm` invocation is about to write turns a mystifying `EPERM`/`-4048` into a named cause
  and a one-line remedy. This is the trigger the 2026-07-15 observation itself asked for, and the
  only occasion covering the owner-alive case — at advisory tier by deliberate choice, per §6.3.
- **Orientation** — `world-state.mjs` gains a read-only section listing registered and observed
  processes with their verdicts. It already has the right contract for this ("degrade to
  unavailable, never crash") and already has per-section gather/verdict structure. It never kills.
- **Session closeout** — the skill gains a step that runs the sweep and reports, so a human-visible
  checklist covers what automation missed.

### 6.5 Producer adoption is the cover, and the cover is honest

The register only ever describes what a covered helper told it. **Three** producers adopt it in this
work: the ui-shot auto-serve, `serve-worktree-fe` — the two helpers with recorded leak incidents —
and, per [A6], the OTel sink. The jseval eval backend keeps its existing registration, restated in
the shared grammar.

**[A6] `otlp-sink-ensure.mjs` writes an `ownerless-singleton` record.** Rev 1 introduced that
ownership mode with **zero producers** — a declared-but-unexercised mode, which is the same
substrate-without-consumers defect the design elsewhere warns against. Worse, leaving the sink
unregistered puts it in the **observed** tier, where the sweep prints a ready-to-run kill line next
to a daemon whose entire purpose is outliving every session (§3g) — the design would have been
actively inviting the mis-kill it was written to prevent. Registering it fixes both: the mode gains
its first real producer, and the sink becomes legible as intentionally ownerless rather than
suspicious.

This also partially discharges §5.7 risk 6. Rev 1's two producers were the *same flavor* — a
port-bearing dev server started by a helper script, which is one consumer wearing two hats. The sink
is a genuinely different flavor: a detached long-lived singleton, spawned from a hook rather than a
CLI helper, with no owning session at all. That is a real test of whether the grammar generalizes,
which two Vite servers were never going to provide.

Being honest about what remains uncovered: a **portless** producer is still not exercised (the sink
has a port; `obs:default-index`'s JVM case is not in scope), so the portless probe kind stays
unbuilt. An agent's ad-hoc `run_in_background` bash remains outside every helper and therefore
outside the registered tier permanently — which is exactly why the two-tier verdict is structural and
not a phase.

Cover grows the way it grows elsewhere here: an advisory hook steering agents toward the covered
helper, in the `exec-substrate-hint` pattern (per-session marker de-dup, registered in the manifest,
wiring gate-enforced). A hint cannot guarantee anything; it raises the fraction of spawns that land
inside a covered helper, which is what makes the other mechanisms' reach grow over time.

### 6.6 What this displaces — deletions that belong to this tempdoc

Per `retire-with-a-sweep`, the residue is this work's responsibility, not a follow-up's:

| Artifact | Disposition |
|---|---|
| `ui_shot.py`'s `_SERVER_INFO_PATH` single-slot, CWD-relative record (`:64`) and its read/write sites (`:316-324`, `:401-409`, `:436-443`) | **Deleted**, replaced by register writes. This is the defect that caused both over-spawning and under-reaping (§5.2 Family 3) |
| The stale `scripts/jseval/tmp/ui-shot-server.json` on disk, and the `tmp/ui-shot-vite-*.log` convention | Migrated to the register's own location; stale file removed |
| `scripts/agent-analytics/hooks/ui-shot-cleanup.mjs` | **Deleted**, superseded by the registered reaper. Its two hardcoded candidate paths and its blind `taskkill /F /T` are precisely the parts being fixed; keeping it as a fallback would preserve both defects |
| `.claude/rules/hooks-reference.md:46` — `ui-shot-cleanup` in the "Transparent (no action needed)" list | **Corrected in the same change.** This line is the false authority (§3c); leaving it while deleting the file would invert the problem rather than fix it |
| **[A11]** `.claude/skills/ui-check/SKILL.md` §Worktree auto-serve (~:124) — *"persisted in `tmp/ui-shot-server.json`; the `ui-shot-cleanup` SessionEnd hook kills it"* | **Corrected.** The highest-relevance false-authority site of all: this is the skill an agent loads *immediately before* doing the UI work that spawns the leak, and it asserts both the file being deleted and the reaper that never ran |
| **[A11]** `.claude/skills/ui-check/SKILL.md` step-registry table (~:166) — row listing `ui-shot-cleanup.mjs` as "server cleanup" | **Corrected** in the same edit; a table row is exactly the form of residue tempdoc 742 describes, since it reads as an inventory of live parts |
| `docs/observations.md` `obs:ui-shot-cleanup` (seen 3) | Resolved by this work. The store's own rule is *"delete a condition when its fix lands… deletion is always a human act; automation only proposes"* (`docs/observations.md:56-59`), so the PR **proposes** the deletion and names the landing commit; the owner confirms at triage. Where a condition survives, it gains a `probe:` command (the store supports one, `:53`) so its liveness stops depending on someone remembering |
| `probeForeignRuns` / `run_register.py`'s bespoke envelope | Not orphaned — **generalized** into the shared grammar and reader, with `foreign/` as one scope |

### 6.7 One gate, and one deliberately not built

**Built: the `hook-integrity` gate gains a file→manifest direction.** Today it verifies
manifest→wiring→load→bite, so a hook file absent from the manifest is invisible to it — which is how
the reaper sat inert for the repository's whole public history while an always-loaded rules file
advertised it. An orphaned-hook-file check is small, and it is the gate that matches the documented
defect. Per CLAUDE.md's own tiering, a load-bearing must-rule belongs in a gate (~100%) rather than
in more prose (~70%) — and here we have direct evidence about the tier, since the prose remedy was
observed to fail three times.

**Not built: a spawn-site register with a gate over detached spawns** (§5.2 Family 5). The reason is
substantive, not a deferral: **its predicate would be wrong.** `serve-worktree-fe` spawns in the
foreground and leaks anyway (§3a), so a gate keyed on `DETACHED_PROCESS`/`detached: true` would pass
a known leak source while imposing ceremony on the OTel sink, which is intentionally immortal. The
correct predicate is producer adoption, which has no cheap static form. If a future incident comes
from a *newly added* spawn site that adoption missed, that is the trigger to revisit — with a
predicate chosen from that incident, not from this one.

### 6.8 Owner decision, deliberately not taken by an agent

**Should `ui_shot._ensure_node_modules_junction` keep junctioning an unprepared worktree into the
main checkout's `node_modules`?** This is the single change that would most reduce *severity*
independent of ownership: without the junction, a leaked worktree Vite can only lock its own
worktree's files, and incident 1's `npm ci` recovery of the main checkout could not happen at all.
`prepare-worktree.cjs:81` already gives a properly-prepared worktree a real install, so the junction
is a convenience fallback for unprepared worktrees that converts "FE deps missing" into "holds the
main checkout's files hostage".

- **Option A (recommended for this tempdoc):** keep the junction, record its resolved target in the
  register (§6.2) so lock diagnosis is a lookup. No behaviour change, full diagnostic gain.
- **Option B:** refuse to junction and fail loudly with the `prepare-worktree` remedy. Removes the
  blast radius at the cost of a convenience an unprepared worktree currently enjoys.

A is assumed unless the owner chooses B; B is a behaviour change with a real usability cost and is
not an agent's call to make silently. It is severable into its own tempdoc if chosen — take the next
free number from `node scripts/agent-analytics/world-state.mjs` at that time rather than a number
quoted here, since parallel worktrees claim numbers continuously (it moved twice while this tempdoc
was being written).

### 6.9 Honest limits

- The register describes **what covered helpers declared**, never what is running. The two-tier
  verdict is permanent, not transitional.
- The reader never claims a confident empty: "did not look" and "looked and found nothing" stay
  distinguishable (`null` vs `[]`), per 844 §12.2.
- Lease-on-use extends a claim while a server is *used*, which is not the same as *needed*. A server
  a session started and forgot within a long session keeps its lease until it lapses; the build-time
  conflict trigger, not the lease, is what covers that case.
- Nothing here reaches an agent's ad-hoc background bash, and nothing here should be read as
  implying it does.
- **[A13] Incident 2 is not addressed by this work.** The `TaskStop`-orphaned child bash loops and
  portless measurement drivers of the 2026-07-22 certification campaign remain uncovered: they have
  no port, so the only probe kind this work implements cannot see them, and they are spawned by
  ad-hoc agent bash rather than by a covered helper, so nothing writes them a record. This tempdoc
  cites incident 2 as *evidence for the principle* (§6.10) — self-termination held where external
  cleanup failed — not as a case it closes. Saying so matters because incident 2 is one of the three
  incidents in the charter, and a reader could reasonably assume all three are covered. Two are.
  **Revisit trigger:** a portless producer that a helper actually owns — the `obs:default-index` JVM
  case is the likeliest first one. At that point the record's `probe` field takes a `pid-only` kind
  and the matrix applies unchanged; until then, building that kind would be speculation.

### 6.10 Reach: the principle, and where it already applies

**Principle — *the remedy cannot live in the dying party's last moments*.** More fully: a process
that outlives its spawner must carry either a self-expiry or a claim a third party can adjudicate;
and the mechanism that enforces it must be scheduled somewhere other than the spawner's own
shutdown, because the spawner is precisely the party that may not get a turn.

This is not a new invention here — it is the shape the system already arrived at twice, by hand:
the dev-runner's abandonment self-reap (§3f) and the certification campaign's self-terminating polls
(incident 2). Naming it explains, in one sentence, why `TaskStop` cleanup, SessionEnd hooks, and
`finally`-block teardown all failed while those two held.

Where it already applies in this system:

| Site | Status against the principle |
|---|---|
| `dev-runner.cjs` abandonment self-reap | **Conforms** — the reference implementation |
| Bench telemetry with fixed `-c`/`-DurationSec` bounds | Conforms via the deadline half, without a register |
| jseval eval backend | **Partial** — registers a claim, but the claim has no expiry and nothing adjudicates it |
| OTel sink | **Declared exception** — intentionally ownerless; the design gives it a name rather than an exemption |
| ui-shot Vite, `serve-worktree-fe` child | **Violates** — this tempdoc's subject |
| `run-watcher` children | Heartbeat exists; adjudication is a human reading a verdict file |

**A second, wider shape — *an assertion no mechanism consumes is false authority*.** §3c is an
instance: an always-loaded rules file told every agent a reaper was running; none was. This is the
same failure as tempdoc 742's inert gates and residue, and the same as a register with no gate behind
it. It generalizes past hooks to any document asserting an active mechanism. The file→manifest gate
(§6.7) closes it for hooks specifically; nothing closes it in general, and this design does not build
that.

**Evidence the principle is earning its keep:** `obs:remove-worktree`, `obs:ui-shot*`, and
`obs:serve-worktree-fe` stop accruing *new* entries; worktree removals stop failing on held handles;
the capture-then-build `EPERM`/`-4048` class disappears. All three are observable in the conditions
store's own `seen:`/`last:` fields without new instrumentation.

**Retirement condition:** if, after a season of use, the *registered* tier is reaping essentially
nothing that a plain "scan for known process signatures under this repo and report" would not have
caught, then the register is ceremony and should collapse to the scan, keeping only the observed
tier and the reap occasions. Concretely: if the sweep's own log shows registered-tier reaps that a
signature scan would also have found, in effectively every case, delete the register. The register
earns its place only by the two things a scan cannot do — attributing a **main-checkout** process to
the **worktree** session that spawned it, and supplying the authority to kill without a human
deciding each time.

## 7. Plan

Scope: everything in §6, in one PR. Teardown (§6.6) rides along with the work that makes it dead —
it is not a follow-up. No implementation has been done; this tempdoc is chartered through plan only.

**Not user-visible.** This is dev infrastructure end to end: no UI surface, no RAIL step, no
presentation authority. So no browser validation and no measured UX audit apply. The closure
requirement that *does* apply is `independent-review-required`: the reviewer must not be the
implementer.

### 7.1 Ordering and phases

Phases are ordered so nothing is deleted before its replacement exists, and so each phase is
independently verifiable.

**Phase 1 — Shared process-record grammar and reader.**
Extract what `probeForeignRuns` / `readForeignRegister` / `resolveForeignRegisterDir` already do in
`scripts/dev/justsearch-dev-mcp/server.mjs` into one shared module: record envelope, atomic
temp+rename write, bounded symlink-refusing read, `source: registered | observed`, the
`live | unreachable | stale | unreadable` vocabulary, `identityStale`, and the tri-state
`null | [] | [...]`. Re-point `foreign/` at it with **no behaviour change**.
*Acceptance:* the existing dev-MCP/foreign-run tests pass unchanged; `quick_health`'s `foreignRuns`
payload is byte-identical for a fixture register. **[A7]** Plus a **negative fixture**: an
agent-spawn-shaped record placed in `foreign/` must resolve to `state: 'unreadable'`, not be
silently accepted. This is the test that proves the two scopes stayed distinct rather than
collapsing into one permissive envelope — record *validation* belongs per-scope, not in the shared
envelope, or the sibling-scope decision (§6.1) is undone in the implementation while looking correct
in the design. This phase ships no new capability on purpose — it is the projection step, and its
whole value is that the diff to `foreign/`'s observable behaviour is empty.

**Phase 2 — The agent-spawns scope, and identity.**
Add the sibling register directory under the same main-checkout state root, beside (never inside)
the dev-runner's enumerated children. Add the fields §6.2 names that `foreign/` lacks: OS process
creation time, command-line fingerprint, resolved resource roots, lease (duration / renewed-at /
expires-at), ownership mode. Implement identity verification (`pid + creationTime + fingerprint`) as
a single function every kill path must call.
**[A10]** Record pruning lands **here**, with the grammar — not bolted on later. `foreign/` has no GC
at all (§3a), and §6.3's failed-verify retention deliberately adds records that are never
auto-deleted; retention without pruning is how that becomes unbounded growth.
`pruneAgentEvidence` (`files.mjs:147-200`) and `pruneHistoricRuns` (`dev-runner.cjs:378-424`) are the
two in-repo templates.

*Acceptance:* **[A2] three adverse tests**, all of which must refuse rather than proceed:
(i) **recycled pid** — pid alive, creation time differs → verify `false`;
(ii) **unreadable creation time** — field absent or unparseable → REFUSE, not "proceed, nothing
contradicted us";
(iii) **process table unavailable** — the enumeration itself fails and returns `[]` → REFUSE, since
`getProcessTable` fails silently to empty by design (`remove-worktree.cjs:125-131`) and an empty
table is *no evidence*, not *exculpatory evidence*.
These three are the tests that matter (`green-masked-destructive`); a happy-path-only suite does not
close this phase.

**Phase 3 — Producers.**
`ui_shot.py`: write a register record instead of `tmp/ui-shot-server.json`; refresh the lease on the
reuse path as well as the start path; record the resolved `node_modules` target when
`_ensure_node_modules_junction` created a junction. `serve-worktree-fe.cjs`: write a record on start,
remove it on clean exit. `otlp-sink-ensure.mjs` [A6]: write an `ownerless-singleton` record.

**[A3] Recording `child.pid` would have produced a register full of dead pids.**
`serve-worktree-fe.cjs:115-120` spawns `npx.cmd` with `shell: isWin`, so Node launches
`cmd.exe /d /s /c "npx.cmd vite …"` — `child.pid` is the **`cmd.exe` shim**, and the surviving Vite is
two or three generations below it. The live process table captured in §2 shows the shape exactly:
`cmd.exe → npm-cli node → cmd.exe → node vite.js`. The process that survives `TaskStop` and holds the
file locks is the great-grandchild; the pid the spawning code has in hand is a shim that exits
almost immediately. So rev 1's own exemplar producer — the one chosen *because* it has a recorded
leak — would have been unreapable as designed, and the register would have filled with records whose
pid was dead while the leak they described ran on.

**Decided: resolve the listener pid from the port after the readiness gate, and record that.** The
alternative — restructuring the spawn to avoid the shell shim so `child.pid` is the real process — is
the deeper fix, but it is a behaviour change to a spawn shape that two tempdocs (615 §30, 618 §11d)
converged on after repeated Windows-specific failures, and re-opening it here would put this
tempdoc's riskiest dependency on someone else's hard-won lesson. Port-to-pid resolution is
additive, needs no change to how anything is launched, and is already the identity ui-shot's reuse
gate relies on. Note the deeper fix as a follow-up rather than a rejection.

*Acceptance:* starting each producer creates exactly one record; starting a second server creates a
second record without destroying the first (the direct regression test for the six-leak, §3b); a
reuse refreshes rather than duplicates. **[A3] Per-producer:** kill the recorded parent, then assert
the record still resolves to the **surviving listener** — the test that would have caught this
finding, and the one that distinguishes "we recorded a pid" from "we recorded the process that
actually leaks".

**Phase 4 — The reaper and its authority test.**
One module implementing §6.3's matrix: same-session always reapable; other-session-with-live-lease
is contention (report, never kill); lapsed lease reapable; `ownerless-singleton` never reaped;
observed tier never killed, only reported with a ready-to-run kill line.
*Acceptance:* a table-driven test covering **every cell** of §6.3's matrix, plus explicit
never-reaped assertions for the dev-runner's own active run and the OTel sink. This is the riskiest
code in the change — the two existing precedents are both reapers that became incidents (§3c-bis,
tempdoc 746 item 5).

**Phase 4 as landed (PR #552) — what Phase 5 must know.** The independent review returned
APPROVE-WITH-FIXES; two of its findings changed the API Phase 5 consumes, so they belong here
rather than only in the PR:

- **Occasions are named, and capability is not a caller argument** (review F2). The reaper exports a
  frozen `OCCASIONS` map binding each of §6.4's six occasions to its capability, and
  `reapEligible({occasion: 'before-a-build', …})` derives the rest. [A4] was previously only
  half-enforced: `occasion` and `capability` were independent arguments, so
  `{occasion: CONFLICT, capability: EXECUTE}` handed the before-a-build surface a kill list — the
  exact pairing [A4] forbids, reachable by a Phase 5 author picking the pair that looked right.
  **Phase 5 wires by occasion name; an unknown name throws rather than defaulting.**
- **The sweep carries the marking obligation** (review F7). §6.3's identity-failure cell says
  "refuse; retain record, mark failed-verify" in *both* columns, but a projection refusal never
  reaches `executeReap`, which is what marks on the kill path. So refusals carry `markPending` and
  the result exposes a `markPending` bucket; the sweep, orientation and before-a-build occasions
  discharge it with one `markRefusals(buckets.markPending, {dir})` call. Skipping it loses the
  diagnostic trail exactly where the matrix promised one.

Three smaller corrections landed with them: a positively-gone holder (identity `MISMATCH`) no longer
blocks a teardown for the 7-day prune window, while an *unreadable* verdict still does (F3); the
no-declared-hold grace window is reported as `grace-window` rather than mislabelled `declared-hold`
with a claim of intent nobody expressed (F5); and a phantom `record.devRunnerRunId` arm — read by the
never-reap guard, written nowhere, unexpressible by W2's validator — was dropped in favour of the
verified pid-list arm (F4, the `slice-execution` phantom-ID class).

**Phase 5 — Reap occasions.** Each is small; together they are the deliverable.
- SessionStart sweep (async) — reaps the previous dead session's leaks.
- SessionEnd own-spawn reap — registered in `governance/agent-hooks.v1.json` this time, with the
  wiring regenerated by `scripts/codegen/gen-agent-hooks-wiring.mjs` and a tier-register row added.
- `remove-worktree.cjs` — consult the register before `removeJunctions`; reap what is authorized;
  **refuse to proceed while an unreapable holder remains**, rather than proceeding into the
  half-deleted `.git`-less shell §2-bis (c) documents.
- Pre-build advisory hint (PreToolUse/Bash) in the `exec-substrate-hint` idiom, including its
  per-session marker de-dup: warn when a registered spawn holds paths under the tree a
  `gradlew`/`npm` invocation is about to write. This is the only occasion covering the owner-alive
  case, which is the most frequently observed harm.
- `world-state.mjs` — a read-only section listing registered and observed processes with verdicts,
  matching its existing per-section gather/verdict structure and its "degrade to unavailable, never
  crash" contract. It never kills.
- `session-closeout` skill — a step that runs the sweep and reports.

**Phase 6 — The gate.**
Add the file→manifest direction to `scripts/governance/gates/hook-integrity/enforcer.mjs`: a hook
file present in `scripts/agent-analytics/hooks/` but absent from the manifest fails the gate.
*Acceptance:* the gate fails on a crafted orphaned-hook fixture and passes on the real tree **after**
Phase 7's deletion — which means Phase 6 and Phase 7 must land together or the gate goes red on
`ui-shot-cleanup.mjs` itself. That interlock is deliberate: the gate's first act is to catch the
defect that motivated it.

**Phase 7 — Teardown (§6.6).**
Delete `ui-shot-cleanup.mjs`; delete `ui_shot.py`'s `_SERVER_INFO_PATH` and its read/write sites;
remove the stale on-disk `scripts/jseval/tmp/ui-shot-server.json`; correct
`.claude/rules/hooks-reference.md:46`; propose the `obs:ui-shot-cleanup` deletion in the PR body.
Then sweep for residue: `git grep -n "ui-shot-server\|ui-shot-cleanup"` must return only intended
hits. Per `retire-with-a-sweep`, "a follow-up PR will clean it up" is the predictable evasion here.

### 7.2 Validation

Split by what can be self-verified versus what needs a live stack, per `slice-execution.md`.

*Implementing agent verifies (auto):* `./gradlew.bat build -x test` green; the Node test suites for
`scripts/dev` and `scripts/agent-analytics/hooks`; the jseval python tests; the governance kernel run
in full, not a hand-picked subset (`subset-isnt-the-suite`); and `node scripts/ci/check-tempdoc-numbers.mjs`.

*Needs a live window (documented as a runnable smoke, not silently skipped):*

1. Start a ui-shot capture; assert exactly one record appears with a resolved `node_modules` target.
2. Start a **second** server; assert two records — the six-leak regression, and the one check the old
   design could not pass.
3. Kill the parent to simulate `TaskStop`; run the SessionStart sweep; assert the orphan is reaped
   and its record removed.
4. Leave a running server and attempt `remove-worktree` on its worktree; assert it **refuses** with a
   named holder and a kill line, and that the worktree is left intact — the §2-bis (c) regression.
5. With a server holding main's `node_modules`, invoke a build; assert the pre-build hint names the
   holder before the `EPERM`/`-4048` would occur.

The dev stack is a shared, leased resource; these five run inside a window the orchestrator holds,
and lease acquisition/takeover stays main-loop (never delegated).

*Closure:* an independent reviewer (≠ implementer) walks the diff with the §6.3 matrix and the
recycled-pid branch specifically in hand.

### 7.3 Delegation

Per the model-routing rules, and noting parent hooks do not fire inside subagents:

The reviewer's worker split is **adopted** as written:

| Worker | Scope | Model | Notes |
|---|---|---|---|
| **W1** | Phase 1 — extract the shared grammar + reader; re-point `foreign/` at it | sonnet | Self-verifying: existing tests stay green, plus the [A7] negative fixture |
| **W2** | Phase 2 — record shape, identity verification, pruning [A10] | **opus** | Brief must name all three [A2] adverse tests explicitly; this is the module every kill path depends on |
| **W3** | Phase 3 — the three producers | sonnet | Brief must mandate Edit/Write or node UTF-8 scripts — never PowerShell `Get-/Set-Content` (`utf8-bulk-edits`) — and the orchestrator checks the diff adds no unintended non-ASCII. Must carry [A3]'s port-to-pid decision and its per-producer acceptance test |
| **W4** | Phase 4 — the reaper and the §6.3 matrix | **opus** | **Gated: not briefed until A1/A2/A4 are amended into §6.2/§6.3/§6.4 as text** — done in this rev. A worker implements the matrix literally, so a matrix that still said "lapsed → reap" would ship the 735 G6 bug by construction |
| **W5** | Phase 5 — reap occasions and hook bodies | sonnet | Orchestrator runs `gen-agent-hooks-wiring.mjs` itself; a subagent gets no PostToolUse regen hint |
| **W6** | Phases 6+7 — the gate and the teardown sweep | sonnet | One bundle: they interlock (§7.1), so splitting them across agents risks a red gate |

Never delegated: the live smoke (§7.2), dev-stack lease acquisition and contention decisions, and
merge/publish. Every brief is self-contained, pins an explicit `model`, and requires primary-source
`file:line` for load-bearing claims — subagent findings are a starting point, not a result.

**The W4 gate is the transferable lesson of this review.** Three of the amendments (A1, A2, A4) were
errors in *design prose* that would have become errors in *code* precisely because a worker
implements the matrix as written rather than re-deriving it. That is why rev 2 corrects the text
before dispatch instead of noting the corrections in a brief: the tempdoc is the contract, and a
brief that contradicts it leaves two authorities.

### 7.3-bis Cross-work ordering (tempdoc 860)

860's P4 and 861's phases 6+7 both edit `governance/agent-hooks.v1.json`. Ordering, adopted:

1. **860's P4 lands first.**
2. **861's phases 6+7 land last within 861** (they already must, for the gate/teardown interlock).
3. Whoever lands second **re-runs `node scripts/codegen/gen-agent-hooks-wiring.mjs --emit-local-example`**
   and never hand-merges `.claude/settings.local.json`. That file is generated output
   (`agent-hooks.v1.json:5`); hand-merging a generated artifact produces a wiring that matches
   neither manifest — which, in this specific subsystem, is indistinguishable from the §3c defect
   this whole tempdoc exists to fix.

### 7.4 Risks specific to executing this plan

- **The reaper kills something wanted.** Mitigated by §6.3's corrected matrix — the split lapsed
  column [A1], the identity-verify-failure cell [A5], and never auto-killing the observed tier. This
  is the failure mode with **three** prior instances in this repo (§6.3 [A15]), so Phase 4's review
  is the one that must not be perfunctory.
- **[A3] Recording the wrong pid.** A register full of shim pids would look healthy and reap nothing.
  The per-producer "kill the parent, assert the record still resolves to the listener" test is the
  only thing that distinguishes the two states from the outside.
- **`remove-worktree`'s new refusal is a behaviour change.** It will block teardowns that previously
  proceeded (into corruption). The message must carry the holder and a ready-to-run kill line, and
  the existing `remove-worktree` tests must be re-read for cases that assumed unconditional
  progress.
- **Phase 6/7 interlock.** Landing the gate without the deletion turns the build red on the very
  artifact being removed.
- **Cross-worktree contention.** Eighteen worktrees exist (§2); a sweep that misjudges another live
  session's spawn is contention, not garbage. This is why "other session, lease live" is *report and
  leave*, never reap.
- **Scope creep into supervision.** The plan deliberately builds no per-spawn supervisor, no
  portless probe kind, and no spawn-site gate (§6.7). If implementation starts wanting one, that is
  a signal to stop and re-read §6.5, not to widen.

### 7.5 Mechanical appendix — the surfaces an implementer touches

Collected by source survey so the implementer does not re-derive them.

**Where the shared module goes, and how both worlds consume it.** Write it as **`.cjs` under
`scripts/dev/lib/`**. That directory already holds exactly this kind of shared dev-stack logic
(`ownership-verdict.cjs`, `resolve-jdk.cjs`), and the cross-format interop is an established pattern
rather than a new one: `server.mjs:125-130` pulls both of those `.cjs` modules into an ESM module via
`createRequire(import.meta.url)`. That single choice serves all three consumers — `remove-worktree.cjs`
and `dev-runner.cjs` `require()` it directly, `server.mjs` and any
`scripts/agent-analytics/hooks/*.mjs` reach it through `createRequire`.

**The extraction is low-tangle.** In `scripts/dev/justsearch-dev-mcp/server.mjs`: `probeForeignRuns`
:981-1097, `readForeignRegister` :894-950, `resolveForeignRegisterDir` :868-874, `httpGetStatusCode`
:290-318, `_pidAlive` :132-135, and the `FOREIGN_*` constants :842/:854/:857/:878/:881-882. The
cluster depends only on `node:fs/promises`, `node:path`, one module-local port constant, and each
other — and `probeForeignRuns` already takes its probe/reader/pid-check as injectable parameters, so
it is written for extraction. Surfacing: `quick_health` calls it at :2478-2483 and assembles it at
:2589-2590; `ForeignRunSchema` is `schemas.mjs:647`, wired into `QuickHealthOutputSchema` at :670-671.

**Atomic writes are not where §6.1 implied.** `server.mjs` has *no* atomic-write helper; the real
`writeJsonAtomic` lives at `dev-runner.cjs:117` (used at :754, :1087, :1969, :1972, :2136, :2404), and
`run_register.py:163-180` has a third, independent one. Unifying the JS pair is part of Phase 1;
the Python one cannot be unified (below).

**Adding a hook, end to end** (all four steps, plus regen):
1. Catalog entry in `governance/agent-hooks.v1.json` under `hooks.<hookId>` — `{ "file", "role" }`.
   Every hook 861 adds is `role: "advisory"`, so **no `bite` block is required** (`bite` is mandatory
   only for `role: "blocking"`, enforced at `enforcer.mjs:217`).
2. Binding under `bindings.<Event>[].hooks[]`.
3. The hook file itself under the declared `hookDir` (`agent-hooks.v1.json:6`).
4. Regen: `node scripts/codegen/gen-agent-hooks-wiring.mjs` writes `.claude/settings.local.json`'s
   hooks block (:226-227) — never hand-edited. Add `--emit-local-example` to refresh the committed
   maintainer seed (:197-200), and decide explicitly whether each new hook ships in the public
   template or belongs in `PUBLIC_EXCLUDED_HOOKS` (:63-65).
5. Tier-register row in `docs/reference/contributing/tier-register.md` with a `hook:<file>.mjs`
   marker in the "Resolves to" column (grammar at :133-134); the gate checks this sync at
   `enforcer.mjs:233-247`.
6. `node scripts/governance/run.mjs --gate hook-integrity --mode gate`.

**Where the new gate check slots in.** `enforceHookIntegrity` (`enforcer.mjs:108`) runs five phases:
wiring :144-156, cwd-invariant + live-wiring :158-187, load :189-212, bite :214-231, tier-register
sync :233-247. The file→manifest check becomes **phase 6**, after :247 and before the return at :249.
There is no existing on-disk hook-file lister in the enforcer — `readdirSync` appears nowhere in it —
so the closest template is `discover()` in `scripts/agent-analytics/run-all-tests.mjs:31-40`, same
pattern with a different filter. The check excludes `*.test.mjs` siblings — and **only** those.

**[A12]** Rev 1 added a second caveat here, that the check must respect the `"wiring": "opt-in"`
escape. That was a category error: an opt-in hook **is** in the catalog, and the orphan check asks
whether a file on disk appears in the catalog at all. `opt-in` exempts a catalog entry from the
*live-wiring* check, which is a different phase entirely (`enforcer.mjs:165-166,181`). Carrying the
caveat into the implementation would have created a needless exemption path — and an exemption path
in an orphan check is precisely how the next orphan hides.

**Producer call sites.** `register_backend` is `run_register.py:136`, called from `backend.py:292`;
`unregister_backend` is `run_register.py:188`, called from `backend.py:627`.

### 7.6 A finding that changes the validation plan: these tests run in CI nowhere

The survey turned up a coverage fact the plan has to account for rather than assume around. Of the
suites this work will add to:

| Suite | Runs in CI? |
|---|---|
| `scripts/agent-analytics/**/*.test.mjs` | **Yes** — auto-discovered by `run-all-tests.mjs:31-40`, run as a CI job step (`ci.yml:118-119`) |
| `scripts/dev/*.test.mjs` (incl. `remove-worktree.test.mjs`) | **No** — manual `node --test` only |
| `scripts/governance/gates/hook-integrity/enforcer.test.mjs` + `truth-table.test.mjs` | **No** — the gate's own tests are manual-only |
| `scripts/jseval/tests/**` | **No, by explicit decision** — `ci.yml:87-100` runs only `test_release.py`, and the adjacent comment states the other 132 files run in CI nowhere |

So under the current wiring, **the two most safety-critical tests in this change — the reaper's
authority matrix and the recycled-pid identity branch — would run in CI nowhere**, in a change whose
central risk is a kill path. That is not acceptable for this particular work, and it is a plan
decision rather than an implementation detail. Two options, to be settled before Phase 4 starts:

- **Preferred:** site the reaper and the record grammar's tests under `scripts/agent-analytics/`
  where auto-discovery already covers them, even though the modules themselves live in
  `scripts/dev/lib/`. Test location and module location need not match, and this needs no CI edit.
- **Alternative:** extend CI to run `scripts/dev/*.test.mjs` and the `hook-integrity` gate tests.
  Broader benefit, but it widens this PR into CI configuration — which the model-routing rules flag
  as risky-to-delegate, and which would pull unrelated existing failures into scope.

This is logged as an out-of-scope-adjacent finding in its own right: the `hook-integrity` gate — the
mechanism guarding the hook layer's integrity — has tests that no automated run executes, which is a
close cousin of §3c's defect one level up.

## 8. Rev 2 amendment ledger

Traceability for the adversarial review's 15 findings. `[A<n>]` markers appear at each point of
change in the sections above.

| # | Blocking | Amendment | Landed in |
|---|---|---|---|
| A1 | **yes** | Split the lapsed-lease column: reap only when the lease has lapsed **and** owner activity is stale; lapsed-with-live-owner is contention. Reproduces 735 G6 otherwise (`dev-runner.cjs:2102-2113`) | §6.3 |
| A2 | **yes** | `CreationDate` is projected away (`remove-worktree.cjs:119-120`) — add to projection, `.ToFileTimeUtc()`, exact equality; mandatory pid AND creationTime AND fingerprint; evidence-unavailable = REFUSE; three adverse tests | §6.2, §7.1 Ph2 |
| A3 | **yes** | `serve-worktree-fe`'s `child.pid` is the `cmd.exe` shim — resolve the listener pid from the port after readiness and record that; note the spawn-shape fix as a follow-up | §7.1 Ph3, §7.4 |
| A4 | **yes** | The before-a-build occasion is **advisory only**, no kill from a PreToolUse hook; the most-frequent harm sits at advisory tier deliberately | §6.3, §6.4 |
| A5 | **yes** | `ownerless-singleton` becomes a matrix dimension; add the identity-verify-failure cell (refuse, retain record with failed-verify marker) and the dev-runner's-own-active-run never-reap row | §6.3 |
| A6 | **yes** | `otlp-sink-ensure.mjs` becomes the third producer, writing an `ownerless-singleton` record — the mode had zero producers and the sink sat in the observed tier beside a printed kill line | §6.5, §7.1 Ph3 |
| A7 | | Phase 1 acceptance gains the negative fixture (agent-spawn-shaped record in `foreign/` → `unreadable`); validation is per-scope, not in the shared envelope | §7.1 Ph1 |
| A8 | | `foreign/` schema version stays 1, new fields additive-optional; the agent-spawns scope carries its own version constant | §6.2 |
| A9 | | The new scope honors `JUSTSEARCH_DEV_RUNNER_STATE_ROOT` | §6.2 |
| A10 | | Pruning lands in Phase 2 with the record grammar (`pruneAgentEvidence` as template), pairing with A5's retention | §7.1 Ph2 |
| A11 | | `.claude/skills/ui-check/SKILL.md` (~:124, ~:166) added to the disposition table — the highest-relevance false-authority site | §6.6 |
| A12 | | Drop the `wiring: "opt-in"` caveat from the orphan check (category error); keep only the `*.test.mjs` exclusion | §7.5 |
| A13 | | State plainly that incident 2 (portless `TaskStop`-orphaned drivers) is **not** addressed, with the revisit trigger | §6.9 |
| A14 | | Argue Family 1 over Family 2 explicitly: Vite cannot renew a lease, a wrapper is another process to leak, and lease-on-use tracks "used" not "needed" | §6.1-bis |
| A15 | | Cite `dev-runner.cjs:2102-2113` as the third reaper-became-the-incident precedent | §6.3, §7.4 |

**What the review says about this tempdoc's own method.** The architecture survived adversarial
review intact; the defects were concentrated at the theorize→design seam, where §6 restated §5's own
conclusions more loosely than §5 had stated them (A1, A2, A6 each have their correct answer already
written in §5.5, §5.6, and §5.7 respectively). The lesson is not "check the design against the
sources" — that was done — but **check the design against the tempdoc's own earlier section**, which
is the cheaper and more reliable check, and the one that was skipped.

### 7.1 Phase 5 — as landed (PR #558)

Each of §6.4's six occasions is wired through one shared assembly,
`scripts/dev/lib/agent-spawn-sweep.cjs`: `session-start` (`agent-spawn-sweep-hint.mjs`,
SessionStart/async), `session-end` (`agent-spawn-session-end-reap.mjs`, SessionEnd,
`ownSessionOnly`), `worktree-teardown` (`remove-worktree.cjs`'s pre-junction-unlink consult),
`before-a-build` (`agent-spawn-build-hint.mjs`, PreToolUse/Bash, structurally advisory),
`orientation` (`world-state.mjs`'s read-only "Agent spawns" section), and `session-closeout`
(`scripts/dev/agent-spawn-sweep.cjs`, the runnable CLI the skill's checklist step names). None
of the six occasions gained a kill path this phase did not already have from Phase 4 — capability
is bound to the occasion name in the reaper's frozen `OCCASIONS` map, never chosen by the caller.

**Independent review returned APPROVE-WITH-FIXES.** The kill funnel stayed single (zero
`executeReap` callers outside the shared assembly), the TOCTOU discipline held (a sweep's own
process-table snapshot can never reach the kill — `executeReap` always re-reads fresh), [A1] was
genuinely consulted (the lapsed-lease-alone column stays split, never collapsed back to "lapsed
→ reap"), both required test mutations were present, and `branch-safety.md`'s growth was net
additive with the destructive-git command table intact. Eight findings, clustering into one
broken remedy loop (a record could get stuck failed-verify with nothing ever aging it out, no
way for a caller to attribute its own spawn, and no fix visible from either surface):

| # | Sev | Finding | Fix | Evidence |
|---|---|---|---|---|
| F-1 | **HIGH** | `pruneAgentSpawnRecords` had ZERO production callers — the "7-day retention bound" the reaper's own comments and F3's `blocksProceed` carve-out lean on did not exist. A single transient `IDENTITY_REFUSE` (one bad process-table read) marked a record failed-verify and blocked `remove-worktree` on that tree FOREVER. | `runAgentSpawnSweep` now runs `pruneAgentSpawnRecords` BEFORE the register read, defaulting to `true` on `session-start` and force-enabled on the CLI regardless of occasion. | `861-w5-agent-spawn-sweep.test.mjs` — "F-1 RED/GREEN" (a transient-refuse record blocks teardown with `prune:false`, and the wired session-start sweep clears it) + "F-1 control"; `861-w5-session-hooks.test.mjs` — the SessionStart kill-switch test now discriminates on this pruning (enabled deletes the aged record, disabled doesn't) |
| F-2a | MEDIUM | `--session-id` was optional and absent from the documented invocation, so a caller's OWN live spawn classified `CONTENTION` instead of `SAME_SESSION`-reap. | `resolveCallerSessionId` (env-first: `CLAUDE_CODE_SESSION_ID` → `JUSTSEARCH_AGENT_SESSION_ID` → `tmp/agent-telemetry/current-session-id`, `--session-id` as override) resolves the caller inside `remove-worktree.cjs` and the CLI. | `861-w5-agent-spawn-sweep.test.mjs` — the `resolveCallerSessionId` chain unit tests + the CLI end-to-end test; `861-w5-remove-worktree-teardown.test.mjs` — "F-2a" (no `--session-id` flag, `CLAUDE_CODE_SESSION_ID` alone reaps the caller's own live spawn instead of refusing) |
| F-2b | MEDIUM | The teardown refusal printed a bare `taskkill` line as the fix — contradicting the very rule this tempdoc adds to `branch-safety.md` ("never hand-`taskkill` one"). | `describeEntry` now leads with `resolve via sweep: node scripts/dev/agent-spawn-sweep.cjs …` for every registered entry; the raw kill line stays as an explicit, clearly-labeled last resort. | `861-w5-agent-spawn-sweep.test.mjs` — "F-2b" (sweep line precedes the kill line; absent for the observed tier, which has no register row to sweep); `861-w5-remove-worktree-teardown.test.mjs` — the refusal test asserts the sweep line appears before `taskkill` |
| F-3 | MEDIUM | The CLI read `CLAUDE_SESSION_ID`, a variable nothing in this repo sets. | Same `resolveCallerSessionId` chain as F-2a. | The CLI end-to-end test (`861-w5-agent-spawn-sweep.test.mjs`) drives the documented invocation (`--occasion session-start`, no `--session-id`) with only `CLAUDE_CODE_SESSION_ID` set and asserts a same-session reap |
| F-4 | MEDIUM | `recordHoldsPath` asks "does the queried path sit at/under the record's held root" — inert for §6.2's headline case (a worktree's Vite holding the MAIN checkout's `node_modules`, a root NARROWER than and nested INSIDE the tree a build/teardown queries with a tree ROOT, never the specific deep path). | `holdsWithin` adds the reverse direction; `recordHoldsTree` (both directions) replaces the bare `recordHoldsPath` pre-filter in `findBuildHolders` and `consultAgentSpawnsForTeardown`. | `861-w5-agent-spawn-sweep.test.mjs` — "F-4" (demonstrates `recordHoldsPath` returning `false` on the exact fixture `recordHoldsTree` returns `true` for, through both the predicate directly and the full occasion wiring) |
| F-5 | LOW | The build hint's per-session dedup filtered already-nudged holders AFTER `findBuildHolders` had already spent a process-table read on them. | `findBuildHolders` gained a `recordFilter` parameter applied before the process-table read; the hook passes its `alreadyNudged` check there. | `861-w5-agent-spawn-sweep.test.mjs` — "F-5" (a spied `readTable` proves zero calls when `recordFilter` excludes every candidate, exactly one when it admits one) |
| F-6 | LOW (docs) | The record-side session/producer attribution this phase's occasions all key on inherits Phase 3's own known limit, undocumented here. | See the paragraph immediately below. | — |
| F-7a | LOW | The build-hint test's "child still alive" check was labeled as if it proved the structural [A4] guarantee, when that guarantee is asserted directly (on `entry.disposition`) at the projection layer. | Re-labeled as a subprocess-contract pin, with the structural guarantee's actual location named in the same comment. | `hooks/861-w5-agent-spawn-build-hint.test.mjs` |
| F-7b | LOW | The `JUSTSEARCH_DISABLE_HOOKS=1` test asserted only "exit 0, empty stdout" for both hooks — true whether or not the kill switch did anything, since an enabled hook against an empty register is equally silent. | SessionStart's kill-switch check now discriminates on F-1's pruning (enabled deletes an aged fixture, disabled doesn't); SessionEnd's stays a labeled non-discriminating smoke check, since it has no default-on side effect to discriminate against. | `861-w5-session-hooks.test.mjs` |
| F-8 | LOW | `remove-worktree.cjs` passed its OWN tree as `mainRepoRoot` — a worktree-local copy of the script would consult its own (nonexistent or stale) register instead of the shared main-checkout one. | Uses the already-exported `resolveMainRepoRoot(repoRoot)` (861 [A9]'s override-aware resolver) instead of the raw `repoRoot`. | `861-w5-agent-spawn-sweep.test.mjs` — smoke-checks the re-export; the fix itself is structural (a worktree-local invocation is not part of the automated suite, since it requires a second git worktree as a fixture) |

**F-6, in full — the known limit this phase inherits, not one it introduces.** Every occasion in
this phase attributes a record to a session (or not) purely by reading `record.sessionId` — a
field Phase 3's producers populate via the SAME env-first, file-fallback chain `resolveCallerSessionId`
now formalizes (`serve-worktree-fe.cjs`'s pre-existing `resolveSessionId`, `note-observation.mjs`'s
chain). When that resolution fails at PRODUCER time (no env var set, no pointer file written yet —
plausible for the very first tool call of a session, before `export-session-env.mjs`'s SessionStart
hook has run), the record is written with no `sessionId` at all. This phase's occasions all fail
SAFE on that gap: an unattributed record can never match `SAME_SESSION`, so it is never reaped on
ownership grounds alone — it falls through to the lapsed-lease/owner-activity branch like any other
session's record, and `session-end`'s `ownSessionOnly` filter (which requires a truthy
`sessionId === callerSessionId`) excludes it outright rather than guessing. The cost is availability,
not safety: a spawn born before its producer's session id resolved is swept only by the broader
`session-start`/`session-closeout` occasions, never by its own session's `session-end`. Not
addressed this phase — the fix (if one is warranted) belongs with Phase 3's producers, which own
the write site this limit originates from.

### 7.1 Phase 6+7 — as landed (PR #559)

Both phases land in one PR, as §7.1's ordering requires (the gate's own first run must not go red
on the file its sibling phase deletes).

**Phase 6 — the gate.** `enforceHookIntegrity` (`scripts/governance/gates/hook-integrity/enforcer.mjs`)
gains a sixth phase after tier-register sync: `readdirSync` on the manifest's `hookDir`, excluding
only `*.test.mjs` siblings, and a new verdict `verdictForOrphanHookFile` (`truth-table.mjs`) fails
when a file on disk has no `manifest.hooks` entry. [A12] is implemented as specified: the
`"wiring": "opt-in"` escape is not consulted here at all — an opt-in hook (`taskcreate-guard`) is
still in the catalog, so it is correctly never flagged, without any exemption branch existing for
this check to leak through.

**First-act evidence, on the real tree, not only a fixture.** Before deleting the file, the manifest
(`governance/agent-hooks.v1.json`) was already confirmed to have no `ui-shot-cleanup` entry (checked
directly, not inferred). Running `node scripts/governance/run.mjs --gate hook-integrity --mode gate`
against the real repo tree with `ui-shot-cleanup.mjs` still present produced exactly one finding:

```
hook-integrity/orphan-hook-file | hook file 'ui-shot-cleanup.mjs' exists on disk but is absent from
manifest.hooks, so it can never be wired or gate-checked. Add a catalog entry in
governance/agent-hooks.v1.json, or delete the file if it is dead code.
```

`governance: 1 gate evaluated, 1 fail, 1 findings` — the gate's first real act catches exactly the
defect that motivated it, with zero other hook files misflagged. After the Phase 7 deletion, the
same command against the same tree returns `governance: 1 gate evaluated, 0 fail, 0 findings`. Both
directions are also pinned as a permanent regression: `scripts/agent-analytics/861-w6-hook-integrity-orphan.test.mjs`
reproduces the pre-sweep shape as a fixture (a catalogued file, an orphan, and a `*.test.mjs` sibling
that must stay excluded) and a clean-tree fixture, asserting the fail/pass split and that the
`*.test.mjs` exclusion holds. It is sited under `scripts/agent-analytics/` rather than the gate's own
test directory specifically so `run-all-tests.mjs` auto-discovery puts it in CI — closing, for this
one test, the §7.6 finding that the gate's own `enforcer.test.mjs`/`truth-table.test.mjs` run in CI
nowhere (that broader gap is unchanged; §7.6's decision between the two options remains open and is
not re-litigated here). `truth-table.test.mjs` also gained direct unit coverage of
`verdictForOrphanHookFile`. `node scripts/agent-analytics/run-all-tests.mjs` is green (53/53 files)
and the full governance kernel (`node scripts/governance/run.mjs --mode gate`) shows `hook-integrity:
pass`; the run's other 7 failing gates (`npm-audit`, `ts-any`, `module-deps`, `dead-code`,
`dead-code-jvm`, `contract-projection`, `config-surface`) were checked against their SARIF output and
none reference any file this PR touches — they are pre-existing generated-input gaps
(`tmp/npm-audit-report.json missing`, `tmp/dead-code-jvm-report.json missing`, etc.) and unrelated
`ts-any`/`contract-projection` findings in `modules/ui-web` files this PR never opens.

**Phase 7 — the teardown sweep.** Disposition against §6.6's table:

| Artifact | Disposition |
|---|---|
| `scripts/agent-analytics/hooks/ui-shot-cleanup.mjs` | **Deleted.** |
| `.claude/rules/hooks-reference.md:46` (`ui-shot-cleanup` in "Transparent") | **Corrected** — the name is removed from the list; the always-loaded-budget ratchet argues against replacing it with an explanatory comment (that story lives here and in the PR body instead). |
| `.claude/skills/ui-check/SKILL.md` §Worktree auto-serve (~:126) and the Key-files table row (~:169) | **Already corrected before this phase** (landed with Phase 3/#554 — verified by `git log`), and now additionally accurate again: the paragraph is reworded from "is dead code that has never actually run" to "was deleted in 861 Phase 7" (the file no longer exists at all, so "dead code" would itself be stale), and the Key-files table row naming the now-nonexistent file is removed rather than re-labeled. [A11] is therefore satisfied both by Phase 3's earlier correction and by this phase's touch-up. |
| `ui_shot.py`'s `_SERVER_INFO_PATH` single-slot record and its dual-write with the `agent-spawns` register | **Kept, as an honest limit — not the disposition §6.6 originally specified.** §6.6 called for deleting `tmp/ui-shot-server.json` outright; Phase 3 (already merged, #554) instead chose to keep writing it and layer the register write alongside (`_register_agent_spawn`, `ui_shot.py:74-112`). Grepped before touching anything: `_start_vite_server`'s reuse gate (`ui_shot.py:376-385`) and `_resolve_ui_url` (`ui_shot.py:500-507`) both still read `_SERVER_INFO_PATH` to decide `_is_server_alive` before renewing the register lease — the reuse path's liveness check has no register-only equivalent today. Removing the write in this phase would break server reuse, which is out of this phase's authorized scope (Phase 3 is merged; re-opening its producer logic is not part of "the gate direction and the retirement sweep"). Left in place, named here rather than silently dropped, per Phase 7's own "honest limits" framing (§6.9's pattern) — a future pass migrating the reuse gate onto the register alone (dropping the second write and the stale-file convention for good) is the natural follow-up, not a silent scope-narrowing of this one. |
| `docs/observations.md`'s `obs:ui-shot-cleanup` (seen 3) | **Deletion proposed in the PR body**, per the store's own rule that "deletion is always a human act; automation only proposes" (`docs/observations.md:56-59`) — this tempdoc does not edit `observations.md` directly. The PR body names the landing commit for the owner's triage. |
| Repo-wide `git grep -n "ui-shot-server\\|ui-shot-cleanup"` | Run and every hit classified (full list in the PR body): the manifest/gate/test files above (intended, this phase's own work); `.claude/skills/ui-check/SKILL.md` and `docs/observations.md`/`docs/observations.d/**` (handled above); `gates/prose-tier-register/.changesets/861-agent-spawn-session-end-reap-rule.md`, `scripts/dev/lib/agent-spawn-reaper.cjs`, and `docs/tempdocs/{365,520,721,821}-*.md` (dated history / already-landed design rationale describing the past defect in past tense — left as-is per `tempdocs-are-dated-history`, since editing merged changesets and superseded tempdocs would rewrite history rather than retire a live artifact). No hit was left unclassified. |

## 9. Outcome record — the recurrence ledger this tempdoc set out to close

§2-bis's ledger named two conditions this charter exists to close. Recording their shard-level story
here (the conditions store itself folds separately — this is not an edit to `docs/observations.md`):

- **`obs:ui-shot-cleanup` (seen 3, 2026-06-16 → 2026-07-16).** Root-caused in §3c: the reaper was
  never in the hook manifest, so it never fired. Closed at the mechanism, not just the symptom — the
  file is deleted (Phase 7) *and* the hook-integrity gate now fails the build if any future hook file
  ships without a manifest row (Phase 6), so this specific false-authority shape (a file on disk, a
  rules file describing it as live, nothing wiring it) cannot recur silently for a *different* hook
  the way it did for this one. Deletion proposed in the PR body per the store's own human-act rule.
- **`obs:remove-worktree` (seen 14, 2026-06-21 → 2026-08-07).** Not this phase's mechanism —
  addressed upstream by Phases 2-5 (identity verification, the reaper's matrix, and
  `remove-worktree.cjs`'s pre-junction-unlink consult-and-refuse, landed in PRs #549/#552/#558).
  Phases 6+7 contribute only indirectly: the gate stops the *next* unwired reaper from joining this
  ledger the way `ui-shot-cleanup` did. Whether `obs:remove-worktree`'s count actually stops growing
  is an empirical claim about seasons of real worktree teardowns, not something this PR can prove by
  itself — §6.10's own retirement condition already names the right falsifier (do registered-tier
  reaps catch something a plain signature scan would not?) and the right instrument (the store's own
  `seen:`/`last:` fields), and that check is for a later pass, not for this closeout.

Both conditions are proposed for deletion/re-evaluation in the PR body rather than edited here;
per the store's rule, that determination is the owner's at triage, not an agent's to make unilaterally.
