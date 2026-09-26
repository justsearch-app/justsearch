# Lane F current-state analysis (2026-09-25)

Scope: branch `codex/lane-f-pr1` at `7f469d044` (194 commits, about 2,644 files ahead of
`origin/main`, 0 behind), plus uncommitted D1-9 and D1-11 gap-decision work in the
implementing worktree. Tags: **V** = verified in source; **D** = stated in
handoff or stage documents, not re-run.

## 1. Stage position

- **Accepted:** A, B, C1, C2.
- **E re-cut applied** (`fc5b444d6`): E runs seven merge-gate rows; the feature rows close on
  their D1 and D2 items.
- **D1 in progress. D2, E and F not started.**

## 2. D1 and D2 item status

| Item | Status | Evidence | What is open |
|---|---|---|---|
| D1-1 registry | accepted | V `core/component/ComponentSpec`, `ComponentHandle` | — |
| D1-2 readiness projections | implemented, unproven | V `ReadinessEnvelopeView.engineComponents` | hosted and parity proof |
| D1-3 config-apply register | accepted | V `governance/config-apply.v1.json` | — |
| D1-4 `core.reconfigure` | partial, active | V `ReconfigureHandler`; D handoff "implementation and production proof remain open" | two-component transaction, kill mid-compose, hosted |
| D1-5 generative compose | accepted | D hosted `35800678462` | — |
| D1-6 restart-required; retire `core.restart-worker` | **not started** | V `RESTART_WORKER` in `CoreOperationCatalog`, `RestartWorkerHandler` present | whole item (M) |
| D1-7 deadlines, local recovery, escalation | partial (only the `startDeadline` field) | V no recover route, no escalation | monitor, executor, route, escalation (M) |
| D1-8 Flow A live activation | partial | V `swapRuntime` path; D "do not claim D1-8 from this seam" | activation order, crash cuts 3 to 6 (L) |
| D1-9 journal, replay, gaps | partial, **heaviest WIP** | D handoff 2026-09-25 | no-file projection, gap, cancel and abandon, installed proof, hosted (L) |
| D1-10 served generation | implemented, unproven | D | hosted (S) |
| D1-11 `core.accept-gaps` | partial, active | V `RecordedGapAcceptancePlan`, `GAP_LIST_STALE` | installed approval, live A during the wait (S) |
| D1-12 encoder sets as generations | partial | V `EncoderSet` (+368 incl. test) | retirement leases, full Flow B (L) |
| D1-13 native quiescence | partial | V `NativeSessionHandle` +437/-82 | installed GPU proof, lease held across recompose (M) |
| D1-14 beside or in place | partial, active | V `DeviceMemoryLine`; D in-place crash cuts proven | gap and cancel backfill, floor-cap installed proof, A-recompose regressions (L) |
| D1-15 reason-code re-cut | **not started** | V none of the new codes present | S |
| D1-16 lifecycle harness for E | **not started** | V no `EngineLifecycleE2ETest` | M. Now central: the E re-cut moved feature rows onto D1 and D2 items, and this harness is what exercises them |
| D1-17 residue sweep | **not started** | V | S or M, after D1-6, D1-7 and D1-9 |
| D2-1 to D2-10 | **all not started** | V no component-map endpoint, profiles, `CURSOR_EXPIRED`, `indexAndReturn`, cursor pinning or MCP recovery harness | about 2 L, 4 M, 4 S |

**Observation.** Four early, independent D1 items (D1-6, D1-7, D1-15, D1-16) have not been
started. Meanwhile D1-4/8/9/12/13/14 have had many correction cycles. The handoff prose
reads as sequential progress; the source shows otherwise. WP3 re-sequences.

## 3. Defect classes found in D1 so far (from handoff and `evidence/D1`)

| Class | About how many | Example |
|---|---|---|
| Ordering and races at the activation boundary | 6 | a projection callback completing after the reconciliation row; force-reindex entering SWITCHING before baseline enumeration |
| Ownership and fencing gaps at process or generation boundaries | 5 | a scoped file row blocking predecessor retirement forever; a configured-but-missing A model reported `READY` |
| Schema and registry drift between code and generated or governance artifacts | 4 | `jobs.db` v21 in code vs v20 in the register (hosted `36039474659`); a nullability mismatch between schema generators; `ui-settings` register v1 vs code v4 (WP2) |
| Fixture and timing artifacts | at least 13 invalidated or re-timed runs | see §4 |

## 4. The cost of nondeterministic verification

At least 13 recorded runs were invalidated or re-timed, each costing an installed or
integrated rerun (up to about 12 minutes for the full gate; installed runs are longer):

- **Real races against the free-running cutover monitor** (the fix is WP1):
  - `tmp/3360` raced migration promotion;
  - `tmp/3367` reached SWITCHING before the pause was acquired, because `setMigrationPaused`
    is an unacknowledged persisted flag;
  - hosted `36059941295`: a manual pointer crash cut raced automatic cutover, fixed with an
    all-or-nothing test kill switch.
- **Fixture assumptions and resource contention** (not WP1's target):
  - a fixed `BESIDE` assumption where the runtime chose `IN_PLACE`;
  - load files starving the initial query;
  - timeouts widened;
  - JUnit timeouts under concurrent Gradle load.

`OperationFaultBarrier` (`modules/ui/.../OperationFaultBarrier.java`) is a good, provably-off,
env-gated, named-point file-handshake barrier. It covers Head-side operation phases and
installer cuts only. No equivalent exists for the Worker's MIGRATING, SWITCHING, pointer and
activation transitions: exactly the transitions that keep racing.

## 5. Release safety (summary; details in WP2)

- **The updater has no downgrade direction.** A manual older installer bypasses the
  compatibility check.
- **`ui/settings.json` v4 on an older build** is quarantined (`.corrupt-*`) and silently reset
  to defaults.
- **`operations.db`** is new and is orphaned on downgrade.
- **`jobs.db` and the index** are derived and refuse or rebuild safely.
- **The `ui-settings` register row says `currentVersion: 1` while the code writes 4.** It was
  already 1 against 2 on `main`. The updater embeds this register (`updater.rs`
  `include_str!`) to judge compatibility, and the release assets generator derives its table
  from it.
- **The dead-Engine signed round** (`upgrade-dead-engine-recovery`) has never run. It is the
  only recovery path for a Lane F regression (fix-forward), and design §16 lets stage F
  carry it as a named gap.

## 6. Inference host (summary; details in WP4)

- **Code tied to in-process encoders so far:** about 1.3k to 1.9k lines (`EncoderSet`,
  `NativeSessionHandle` leases, `DeviceMemoryLine`, and an encoder share of `KnowledgeServer`
  and `IndexGenerationManager`). The still-open parts of D1-12, D1-13 and D1-14 are three of
  D1's four largest remaining items.
- **A minimal host:** about 4 to 6 sessions (llama-server-style HTTP, health checks and warm
  adoption, plus the extraction pool's spawn and registry). It would put an IPC hop back on
  request-time calls (query embedding, SPLADE, rerank of 20, citation) that stage A just made
  in-process.
- **Verdict:** keep the host out of Lane F. None of design §5's triggers is evidenced. Contain
  the native machinery and measure the triggers at E (WP4).

## 7. Merge and drift

- Drift from `main`: 0 commits.
- The final squash will be about 2.6k files, so review rests on per-stage review records.
  WP5 adds a subsystem map for the final PR body so a reviewer can navigate it.
