# Installed D1 proof additions — 2026-10-01

Status: implemented, statically checked and locally compiled/tested in
`codex/lane-f-proofs`, based on `56df784ad05dc1d7be81d959a992ee3949272392`;
uncommitted. The orchestrator granted the focused Gradle checks on 2026-10-01;
their results are recorded below. Installed runs remain orchestrator-owned.
No Engine/dev stack, installed scenario, commit or push was performed.

The requested closure review is absent from this base checkout. The full review
was read from the sibling `lane-f-pr1-verify` checkout at the requested path. Its
four proof assignments were checked against this branch's code; unrelated D1
closure items remain outside this assignment.

## Implemented proof mapping

- D1-4 / §16 reconfigure: lifecycle tests
  `preparedInPlaceQueryCrashBootsAAndFailsTheOperation` and
  `committedInPlaceQueryCrashBootsBAndCompletesTheOperation` exercise distinct
  installed IN_PLACE reranker candidates. The existing postcommit point is
  `settings-after-file-replace-before-publication`. The new point
  `settings-after-prepare-before-file-replace` follows completed physical
  component preparation, exact settings serialization and config preparation;
  it precedes commit admission/file replacement. `settings-mid-compose` fires
  inside preparation and therefore does not prove this boundary. The production
  barrier registry admits the new point only for reconfigure in explicitly
  enabled supervisor harness mode. The unit regression checks the guard,
  wrong-kind rejection, marker identity and successor one-shot behavior.
  Both fixtures verify process identity before killing their own Engine,
  exact successor identity, unchanged generation and settings bytes, applied
  encoder identity, a real reranker query and the durable SQLite outcome.
  Precommit expects the code's exact `ENGINE_RESTARTED_DURING_APPLY` reason;
  postcommit expects COMPLETE and committed B. Evidence marker:
  `QUERY_RECONFIGURE_CRASH_PASS`.
- §16 delayed retry: the existing ordinary BESIDE/IN_PLACE lifecycle test now
  performs A→B/k1→C/k2 with three different ONNX byte identities, delayed k1
  replay, and a new key with B's stale witness. It requires k1's exact recorded
  response and HTTP 409 `VERSION_CONFLICT`, respectively. Each probe separately
  verifies C's path, applied encoder version, READY state, settings witness,
  settings bytes and generation. It then restores A and retains the original
  malformed-candidate/refusal-replay and availability assertions. Evidence:
  `QUERY_RECONFIGURE_DELAYED_RETRY_PASS` and `QUERY_RECONFIGURE_ROUND_PASS`.
- D1-2 / §16 recovery: the existing AI-tagged native mixed pointer-crash
  lifecycle scenario now records all four readiness component states and
  stateSince epochs before death and through reachable successor observations.
  During death it records transport unavailability and the last reachable
  envelope, rather than inventing a component observation from a dead JVM.
  Successor epochs must follow observed death and differ from the old epochs.
  READY components report both epoch-based and observed time-to-ready; optional
  non-READY states have null readiness times. Existing exact-hit TEXT and VECTOR
  checks, including dense-retrieval `executed`, bind this trace to real recovery.
  Evidence: `COMPONENT_DEATH_RECOVERY_PASS` plus the fixture's
  `component-recovery-proof.json`.
- D1-9 / §16 generation transition: the parser-gap scenario requires restored-A
  READY state and a real VECTOR query with dense retrieval executed while B
  remains on disk in AWAITING_ACCEPTANCE. It reports and enforces
  `refusalToReadyUpperBoundMs <= reconfigureBudgetMs`, retaining the receipt in
  `gap-restoration-budget-proof.json` and stdout marker
  `MODEL_LIVE_AB_GAP_RESTORATION_BUDGET_PASS`.

## Budget judgment and measurement boundary

The requested `reconfigure.*budget|RECONFIGURE_BUDGET|reconfigureBudget` search
finds no separate production knob. `EngineRoot.java` registers the encoder
ComponentSpec with `Duration.ofMinutes(2)`; the canonical readiness contract
projects that spec as `encoders.deadlineMs`. Use that live 120,000 ms lifecycle
deadline as the restoration acceptance budget rather than another fixture
constant. This is the explicit interpretation of the design's unnamed
reconfigure budget; the test does not claim RELOADING restoration is separately
timed out by the monitor.

`KnowledgeServer.holdInPlaceCandidateForGapDecision` publishes
AWAITING_ACCEPTANCE after synchronous A restoration. Starting a stopwatch at
that state would give a wrong-reason pass. The new stopwatch starts immediately
before releasing the held green-drained decision and stops after A's successful
semantic query. This conservative upper bound includes the final refusal fence,
A restoration and proof-observation overhead, so passing it proves the actual
refusal-to-semantic-readiness interval is within the same budget. It is explicitly
reported as an upper bound, not as an exact production refusal timestamp.

## Static evidence and remaining verification

All five touched `.mjs` files pass `node --check`: query-reconfigure (fixture
and tests), real-writer-recovery, native-projection-scenario, bulk-fault-scenario.
`git diff --check` passes. The retained
[Node receipt](installed-proofs-node-2026-10-01.txt) records 24 tests, zero
failures/skips, including three refutations: old-key reapplication, fabricated
current-witness replay, and admission of a stale witness. Command:

```powershell
node --test scripts/supervisor-conformance/query-reconfigure.test.mjs scripts/supervisor-conformance/bulk-fault-settlement.test.mjs scripts/supervisor-conformance/bulk-fault-semantic.test.mjs scripts/supervisor-conformance/bulk-fault-citation.test.mjs scripts/supervisor-conformance/installed-api-port-sandbox.test.mjs
```

These are harness-level checks, not successful installed proof receipts. The
orchestrator must compile/format and run the barrier guard test, then installed
tests against the rebuilt distribution. Run each new/extended AI lifecycle
method separately with `-PincludeAiTests=true`; the two-round query test has a
26-minute ceiling, crash cuts 14 minutes each, and the existing native/gap
methods 7 minutes each. Each task remains under the 30-minute task budget.
Model-availability assumptions must be reported as skips rather than passes.
Preserve stdout, XML and fixture JSONs before reruns overwrite task results.
Full D1 closure, default-task proof and hosted CI remain orchestrator-owned.

## Granted local Gradle verification

All commands ran sequentially under the explicit Gradle grant. Reports were
independently reread after the commands completed.

| Check | Result | Accessible evidence |
|---|---|---|
| `:modules:system-tests:compileIntegrationTestJava` | PASS; 48 actionable tasks, 30 executed / 18 up-to-date | Compiled integration classes under `modules/system-tests/build/classes/java/integrationTest`; subsequent PMD run also confirmed the compile task up-to-date |
| Three affected modules' `spotlessApply` | PASS; 10 actionable tasks, 6 executed / 4 up-to-date | `tmp/gradle-grant-home/spotless.log` |
| `:modules:ui:test --tests io.justsearch.ui.OperationFaultBarrierTest` | PASS; **9 tests**, 0 failures/errors/skips | `modules/ui/build/test-results/test/TEST-io.justsearch.ui.OperationFaultBarrierTest.xml`; `tmp/gradle-grant-home/barrier-tests-final.log` |
| `:modules:app-services:test --tests *SettingsCommitCoordinatorTest*` | PASS; **54 tests**, 0 failures/errors/skips | `modules/app-services/build/test-results/test/TEST-io.justsearch.app.services.settings.SettingsCommitCoordinatorTest.xml`; `tmp/gradle-grant-home/coordinator-tests.log` |
| `:modules:ui:pmdMain` | PASS; **0 violations / 0 errors** | `modules/ui/build/reports/pmd/main.xml` |
| `:modules:ui:pmdTest` | PASS after one owned qualifier fix; **0 violations / 0 errors** | `modules/ui/build/reports/pmd/test.xml`; `tmp/gradle-grant-home/pmd-test-final.log` |
| `:modules:app-services:pmdMain` | PASS; **0 violations / 0 errors** | `modules/app-services/build/reports/pmd/main.xml` |
| `:modules:system-tests:pmdIntegrationTest` | PASS; **0 violations / 0 errors** | `modules/system-tests/build/reports/pmd/integrationTest.xml` |

All four requested PMD task names exist; none was omitted. The first PMD run
reported one `UnnecessaryFullyQualifiedName` violation in the newly added test.
Replacing `new java.util.HashMap` with the already imported `new HashMap` fixed
it; `ui:pmdTest` passed on rerun. A final focused UI test invocation was
UP-TO-DATE because this qualifier-only change produced unchanged bytecode;
the nine-test receipt remains applicable. Its log is
`tmp/gradle-grant-home/barrier-tests-verified.log`.

The sandbox requires writable copies of Gradle/dependency JARs and writable
temporary directories. The wrapper's download failed with denied network
access; cached Gradle 9.6.1 and the existing dependency cache were copied into
`tmp/gradle-grant-home`, with installed JDK 21/25 paths declared only in that
private Gradle home's properties. Successful invocations used its
`distribution/bin/gradle.bat` with `--gradle-user-home <worktree>/tmp/gradle-grant-home
--offline --no-daemon`; TEMP/TMP pointed at its `test-temp` directory.

The first UI test invocation also failed in the unrelated npm install step;
the existing `-PskipWebBuild=true` option was used for focused UI Java tests and
PMD. No web-build proof is claimed. The initial nine-test setup failure from
the read-only user Temp path was preserved at
`tmp/gradle-grant-home/barrier-tests-temp-denied.xml` before rerunning.
Compiler/deprecation and sandbox WMI warnings were reported, not suppressed.

Keep these local reports/logs until the orchestrator captures installed evidence
and reconciles closure. The private Gradle home is regenerable scratch and can
be removed after evidence capture; it must not be committed. Installed scenario
execution and final D1 closure remain unverified in this worktree.
