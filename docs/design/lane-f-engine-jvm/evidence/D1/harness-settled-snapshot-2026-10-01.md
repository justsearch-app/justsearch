# Installed harness: settled promotion snapshots (2026-10-01)

Scope: lane-f-harness2, branch `codex/lane-f-harness2`, base `67437bd35`.
Owner assignment: investigate captured-edit same-key replay failure, fix equivalent
early final snapshots, preserve strict replay checks, and run Node verification.
No product changes, Engine launches, Gradle execution, commits or publication.

## Diagnosis and source evidence

Root's retirement-race reading is confirmed. Evidence is under sibling worktree
`F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/`:

- Run log: `lane-f-installed-g4a.log`; XML: `lane-f-installed-g4a-xml/`.
- Fixture: `lane-f-takeover/writer-junit-9a1c243c-76a3-4aa0-98ab-48dca2e59731/`.
- Engine log: fixture `state/runs/782fa361-7198-4e85-813a-11d4eb76b055/logs/engine.log`.
  First incarnation log is in the same run's `incarnations/1/logs/engine.log`.
- `bulk-final.json` records `previous_generation=g-20261001-020516` and
  `updated_at_ms=1790820342082` (02:05:42.082 UTC).
- Engine log line 3241: serving-view retirement notification at 02:05:42.0834007 UTC.
  Line 3243: promotion logged at .0976047. Line 3244: predecessor commit timer
  stopped at .1031508; line 3245: predecessor directory marked for deletion at
  .1086498, on `engine-stream-background`.
- `bulk-after-retry.json` records the cleared predecessor pointer and
  `updated_at_ms=1790820342112` (.112 UTC). All other state and manifest fields match.
- Search requests preceding replay are logged at .1127363, .1732568, and .184256
  (lines 3247, 3253, 3259). Harness order is two promoted searches, stale-H1 search,
  then prepared same-key replay. Thus the retirement write precedes replay.
  There is no dedicated replay-request timestamp in this log; the search ordering
  supplies a lower bound. Mutation lease at line 3264 starts at .1943541.

Product ownership corroborates the logs: `KnowledgeServer.java:6189` onwards
checks serving-owner release and the committed terminal receipt before calling
`retirePreviousGeneration` at line 6222. `IndexGenerationManager.java:1602`
deletes the exact predecessor representation before clearing its pointer and
writing the new state timestamp. This is the retirement path, not replay.

## Implementation and acceptance

- `settledPromotedSnapshot` uses existing authoritative state: exact target,
  IDLE, no building generation, no previous generation. It returns the original
  snapshot without removing or normalizing any field. No fixed delay is added.
- Bulk and installer crash final snapshots, live model activation completion,
  both approved-gap promotion snapshots, and ordinary combined-maintenance
  terminal snapshots use this predicate. The committed-root-mutation variant
  retains its first-publication content check followed by its existing explicit
  predecessor-retirement wait (it does not compare replay snapshots). Final crash
  waits allow 180 seconds for the existing two-minute retirement retry; the bulk
  fixture retains its overall deadline.
- Full state, generation manifest and recorded-generation equality now applies
  to every bulk replay. Installer replay also checks these and the queue alongside
  its existing operation-row/settings checks. The captured H2/H1 searches remain.
- Reviewed `real-writer-recovery.mjs`: it dispatches bulk/installer scenarios to
  these helpers; its own final snapshot is supervisor state, not index promotion
  state. Model-binding snapshots verify encoder readiness and binding, not a
  terminal promotion/replay pair. No equivalent early-generation snapshot there.
- Regression proves COMPLETE/settled plus IDLE still rejects a retained predecessor,
  rejects wrong target/building/SWITCHING, and preserves all fields including
  timestamp and explicit null pointers for strict replay equality.

## Verification and remaining work

Local Node proof on the uncommitted patch over `67437bd35`:

```powershell
node --check scripts/supervisor-conformance/bulk-fault-scenario.mjs
node --check scripts/supervisor-conformance/bulk-fault-settlement.test.mjs
node --check scripts/supervisor-conformance/real-writer-recovery.mjs
node --test scripts/supervisor-conformance/bulk-fault-settlement.test.mjs scripts/supervisor-conformance/bulk-fault-semantic.test.mjs scripts/supervisor-conformance/bulk-fault-citation.test.mjs
git diff --check
```

All syntax checks passed; 16 tests passed, none failed. Node emitted its existing
experimental SQLite warning. Installed proof is pending root's authorized run;
this lane does not hold a Gradle grant. Suggested focused root command:

```powershell
./gradlew.bat :modules:system-tests:lifecycleIntegrationTest --tests "*EngineLifecycleE2ETest.capturedEditReplaysTheOriginalUnitAfterTheBuildingCheckpointCrash" --tests "*EngineLifecycleE2ETest.recordedLiveStart*" --tests "*EngineLifecycleE2ETest.refusedRecordedLiveStartUsesOneFreeRestartAndSettles" --tests "*EngineLifecycleE2ETest.semanticAvailabilitySamplesAnInstalled*" --tests "*EngineLifecycleE2ETest.refusedInPlaceGapRestoresAThenApprovesAndPromotesB" --rerun-tasks
```

Installed tests must pass before claiming live acceptance. Preserve their output
and fixture snapshots for the root's reconciliation. No commit was made.
