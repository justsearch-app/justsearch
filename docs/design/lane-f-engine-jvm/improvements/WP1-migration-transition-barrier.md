# WP1: deterministic barrier for the migration and cutover lifecycle

Type: test infrastructure with a production-inert seam plus a state recheck after a held
before-SWITCHING point. The latter guards a real monitor race, so the dated design §0 and
§7.4 lines record it. Record the enabling proof in the handoff and `stages/D1.md` §0.1.

## Problem

Installed and integration proofs of Flow A (MIGRATING to SWITCHING to pointer promotion to
activation) keep racing the Worker's free-running cutover monitor
(`KnowledgeServer.startMigrationCutoverMonitorBestEffort` →
`KnowledgeServerMigrationOps.runMigrationCutoverLoop`, which polls about once a second).

Recorded cases:
- `tmp/3360` raced migration promotion.
- `tmp/3367` reached SWITCHING before the pause was acquired.
- Hosted `36059941295`: a manual pointer crash cut raced automatic cutover.

Current workarounds:
- `IndexGenerationManager.setMigrationPaused` (`worker-core`, `:879`) is a production feature
  flag with **no acknowledgement** that in-flight cutover work has stopped.
- The uncommitted `KnowledgeServer.disableAutomaticMigrationCutoverForTests()` turns the whole
  monitor off. It cannot express "stop *here*", and it removes the very concurrency the proof
  needs.
- Timing margins get widened or fixtures reordered.

Every invalidated run costs an installed or integrated rerun, and more installed rounds are
still ahead (D1-8 crash cuts 3 to 6, D1-9 gap, cancel and abandon, D1-14 refusal branches).

## What already exists (reuse, don't reinvent)

`OperationFaultBarrier` (`modules/ui/src/main/java/io/justsearch/ui/OperationFaultBarrier.java`):
- **Gate:** `JUSTSEARCH_SUPERVISOR_HARNESS=1` plus `JUSTSEARCH_OPERATION_FAULT_{POINT,KEY,KIND}`.
  Selecting a point without the harness gate throws.
- **Protocol:** it writes `runtime/operation-fault-reached.json` (atomic move from
  `.pending`), then blocks until `runtime/operation-fault-release` appears (180 s deadline),
  or calls `Runtime.halt(1)` for self-exit cuts (`JUSTSEARCH_OPERATION_FAULT_SELF_EXIT`).
- **Proof that it is off:** callers compare against a `NO_FAULT_HOOK` sentinel by reference.
- **Clients:** `scripts/supervisor-conformance/bulk-fault-scenario.mjs` and
  `real-writer-recovery.mjs`.
- **Covers** Head-side operation phases and installer cuts only.

## Design

1. **Extract the handshake** (reached/pending/release files, deadline, self-exit) into one
   small class, `HarnessBarrierProtocol`, in the lowest module that both `ui` and
   `indexer-worker` already depend on. Check with
   `node scripts/architecture/module-deps.mjs` and don't add a new module edge.
   `OperationFaultBarrier` keeps its point set and key/kind matching and delegates the file
   protocol. This is one protocol owner; don't create a second copy.
2. **Add `MigrationTransitionBarrier`** beside `KnowledgeServerMigrationOps`: a
   `Consumer<MigrationTransition>` hook with a `NO_HOOK` sentinel.
   - **Gate:** the same `JUSTSEARCH_SUPERVISOR_HARNESS=1` plus
     `JUSTSEARCH_MIGRATION_BARRIER_POINT`, and optionally `..._SELF_EXIT`.
   - **Named points,** placed at the real transitions (verify each call site before
     editing):
     - `migration-green-drained`: Green's accepted queue has drained and the producer is paused;
     - `migration-before-switching`;
     - `migration-switching-entered`;
     - `migration-before-pointer-commit`;
     - `migration-after-pointer-commit`;
     - `migration-before-live-activation`: the D1-8 re-point;
     - `migration-after-live-activation`.
   - **Cross-process mode** (installed fixtures) uses the shared file protocol and reports
     the point and generation ids in `reached`.
   - **In-process mode** (JUnit): a checked hook injected before start, backed by a latch.
     `release()` continues ordinary promotion; `cancel()` throws `InterruptedException`
     through the monitor and unwinds before pointer commitment. It is never selected from
     the environment.
3. **Retire the workarounds in the same change:**
   - delete `disableAutomaticMigrationCutoverForTests` and `automaticMigrationCutoverEnabled`
     (uncommitted WIP; migrate `DocumentIdentityBootImportTest` to cancel an exact
     `migration-before-pointer-commit` hold, then close and manually promote);
   - replace fixture uses of `setMigrationPaused` *as a test barrier*. The production
     pause feature stays.
4. **Client side:** extend the existing `scripts/supervisor-conformance/*.mjs` barrier client
   (the one that already waits for `operation-fault-reached.json`) so it takes a barrier
   family. There is no second client.

## Implementation plan

| # | Item | R/I | Acceptance |
|---|---|---|---|
| 1.1 | Extract `HarnessBarrierProtocol`; `OperationFaultBarrier` delegates | R | `OperationFaultBarrierTest` unchanged and green; no module-deps change |
| 1.2 | `MigrationTransitionBarrier` with the seven points, env gate and in-process variant | R | unit tests: selecting a point without the harness gate throws; the default is the `NO_HOOK` sentinel; the in-process latch holds and releases |
| 1.3 | Call sites in the cutover loop, pointer commit and live activation | R | a **holding test**: externally read `state.json` over at least 3 normal poll intervals while `migration-before-pointer-commit` holds the monitor; active pointer stays A until release or checked cancellation |
| 1.4 | Migrate `DocumentIdentityBootImportTest`; delete the kill switch | R | the class passes 20 consecutive runs locally (`--tests`, `cleanTest --no-build-cache`); hosted search-worker job green |
| 1.5 | Fixture clients: the installed A/B watcher and crash-cut scenarios use named points instead of pause flags or sleeps | R | the next installed D1-8/9/14 round passes without timing changes. Record first-try pass or fail per scenario in the handoff |
| 1.6 | Register the new runtime reached-file variant if its name differs | R | `check-runtime-manifest-closure` green |
| 1.7 | Convert the older sleep-based fixtures outside Flow A | I | only when touched |

**Verification:** focused module tests, then `spotlessCheck pmdAll --continue`, then the
integrated gate at the batch boundary, then hosted.

**Estimate:** about 1 session. **Re-plan trigger:** if a point cannot be placed without
restructuring the cutover loop's locking, stop and record why before changing lock order.

## 2026-09-25 source correction

Independent review of `KnowledgeServer` and `IndexGenerationManager` found that the true
pre-pointer point holds `runtimeSwapLock`, publication write lock and generation
`STATE_CONTROL`. Thus `DocumentIdentityBootImportTest` cannot call `close()` or another
manager's `promoteBuildingGenerationToActive()` while that hook is held. The checked
cancel outcome first unwinds all promotion guards, after which the existing manual pointer
crash cut remains valid. One monitor thread runs the loop, so a held callback cannot permit
three further monitor polls; three external state-file observations prove pointer stability.
This is a correction to the package's proposed test mechanism, not an acceptance waiver.
The held before-SWITCHING callback carries its observed source/building pair into
the final mutation fence; the transition runs only if MIGRATING and both identities
still match, so a replaced candidate cannot inherit a stale monitor decision.
