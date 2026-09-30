# D1 acceptance assertions (2026-09-30)

Verification base: `72a2cbbe2`, branch `codex/lane-f-d1-close`, plus the dirty
continuation below. The orchestrator committed the original assertions and
merged the lane head. This agent has not committed or pushed. The owner granted
focused Gradle execution and a minimal production fix only after reproducing the
Blue lifetime defect. No sub-agents, dev stack, or installed Engine run is
authorized. Temporary production mutations must restore the original bytes.

Governing inventory: [acceptance reconciliation](acceptance-reconciliation-2026-09-30.md).
This receipt records additions and verification limits; it does not close D1.

## Added regressions

| Clause | Test / proof owner | Witness |
|---|---|---|
| D1-9, D1.md 1871-1873 | `NativeGenerationPromotionTest` | Exact second `startFreshMigration` refusal for candidate and retained predecessor; retained-state reason, reopened state/pointer, exact unchanged directory names. |
| D1-8, 1651-1652 | `IndexGenerationRetirementTest` | Fresh transition disk trace `[1, 2, 2, 2, 1]`; each count at most two; exact surviving Green and absent Blue. |
| D1-8, 1647-1649 | `EngineMigrationLifecycleTest` | Issued Blue view exceeds the actual five-second retired-view deadline; timeout reports retained ownership, old runtime remains queryable, release completes retirement. |
| D1-8, 1646-1647 | `EngineMigrationLifecycleTest` | Search loop spans the real before-SWITCHING barrier and swap; every response equals the known complete, distinct A or B document-ID signature: paused Blue A+B+C or Green C. Async errors propagate. |
| D1-8, 1643-1646 | Installed `migration` fixture / `EngineSupervisedRecoveryE2ETest` | Actual pre-activation manifest PID/instance and supervisor incarnation equal promoted and settled snapshots. Compile-only in this assignment. |
| D1-8, 1652-1653 | `EngineNativePointerBootMutationTest` and `RecordedBulkIngestionCoordinatorTest` | Real recorded post-pointer cut, actual body exit and reopen; preterminal store oracle observes exact writable B, sealed queue revision, actual replay certificate and empty journal, then permits COMPLETE. Companion rejects wrong writer and unsettled replay. |
| D1-8, 1620-1622 | `KnowledgeServerCloseCompletionTest` | Actual issued-before-start query, nine real WP1 pauses, blocked acquisitions in publication write sections, and exact Blue runtime usability after Green publication. |
| D1-13, 2326 / 2345-2349 | `EngineLifecycleE2ETest` / installed gap fixture | Real restored-A native CPU lease retained through controlled lifecycle shutdown; unquiesced diagnostic and absent JVM-hook log distinguish hard stop; code 1 is counted TRANSIENT recovery to a new PID/instance. Compile-only in this assignment. |

After two substantive review rounds, the proof was narrowed to production-owned
barriers and physical witnesses: no reflected synthetic publication, invented
manifest identity, postterminal-only settlement claim, or count-only search
identity is accepted.

The preterminal oracle rejects publication writers and takes the existing
publication read lock without waiting. It reads the actual published runtime
and committed operation/queue rows through read-only SQLite connections with
zero busy timeout. It avoids contention with ordinary model initialization and
queue polling while preventing a premature completion from waiting into B.

## Independently confirmed source finding

The issued-before-start WP1 regression reproduced the Blue runtime lifetime
failure on `72a2cbbe2`: 25 tests, 9 failures, 0 errors, 0 skips. Every WP1 pause
case failed its issued Blue query with `SearcherManager not available (runtime
closed?)`, through `SearcherBridge.withSearcher` and `IndexCountOps`.
Evidence: `tmp/d1-granted-proof/blue-real-red-xml/` and `blue-baseline-temp.log`.

On the original implementation, `KnowledgeServer.captureServingView` increments
the exact view's holders. Live Green start retires the original A/A
view and publishes an A/B view; original cleanup intentionally leaves A's
runtime open. Final promotion captures and retires the A/B view. Its cleanup can
close A through `closeRetiredSource` after the temporary source lease exits,
while the original A/A view still holds an issued query. Cleanup checked only
its own view's holders. Current capture is at `KnowledgeServer.java:5854-5874`.

Independent refutation confirmed this ownership chain. The existing migration
held-view test acquires after Green is open, so it retains the later A/B view
and misses the earlier issued-query case. The new matrix queries the original
Blue runtime after publication and must not be weakened if it fails.

Implemented bounded fix: `closeRetiredSource` scans the existing published and
retired views under `servingViewMonitor` for identity-equal search or ingest
runtime references before beginning cleanup (`KnowledgeServer.java:7986-8009`).
Another view, including a zero-holder view whose cleanup has not finished,
retains the runtime through the existing cleanup-refusal/retry machinery.
`ServingLease.close` retries existing retired views after cleaning its captured
view (`:301-310`). No runtime map, persistent counter, marker, or new lifetime
mechanism was added: the existing view registry already owns the required facts.
An explicit shared runtime lease would duplicate that authority and broaden the
change. The matrix now also checks retained predecessor capacity/directory before
release and immediate completed retirement after release. Green: 25/0/0/0 in
`tmp/d1-granted-proof/blue-fixed-green-xml/` and `blue-fixed-green.log`.

## Verification status

The original ungranted pass stopped at its 170-minute grant timeout. The owner
subsequently granted focused builds in this continuation; no further grant
polling was used. All four touched modules compiled and passed Spotless.
Installed scenarios are compile-only, with no installed Engine launched.

Build environment: the sandbox's empty Gradle home first attempted a prohibited
network download. Cached wrapper, dependency, Gradle, and JDK artifacts were
copied read-only from the host cache into `tmp/d1-gradle-cache`. All successful
commands used that local `GRADLE_USER_HOME`, `--offline --no-daemon`, and
`TEMP`/`TMP` set to this worktree's `tmp/d1-sandbox-temp`. `AI_OFFLINE` was not
set. Real native runtime tests ran. Existing compiler, WMI, and SQLite warnings
were not suppressed. The initial 25/25 sandbox temp-directory AccessDenied
failures (`blue-baseline-xml/`) are environmental failures, not red proof.

Logs and copied JUnit XML are in `tmp/d1-granted-proof/`, retained locally for
14 days or through D1 review, whichever is later. Each XML receipt records its
timestamp and counts. The final class and mutation receipts are recorded below.

The first unmutated lifecycle run exposed an incorrect fixture expectation:
paused Blue contains A+B+C, not just A+B. The accepted lexical projection
commits C into A before Green promotion, as required by `D1.md:2627-2628` and
implemented in `DefaultWorkerAppServices.commitActiveLexicalProjectionForCutover`.
The fixture now freezes the known three IDs for paused Blue and the sole C ID
for Green; every full response must match one of those exact signatures. The
initial Blue A+B assertion is retained before migration. This is an approved
projection behavior, not a production defect or an arbitrary learned signature.

The first pointer-cut attempt failed before the cut because the new fixture
still expected recorded Green preparation to request a build restart. On this
lane head it opens live (`startMigration ... restartRequired=false`, then
`Green opened live; no Engine restart`). That attempt, retained as
`pointer-cut-attempt-xml/`, is not red proof. The fixture now uses the real
pre-SWITCHING and post-pointer barriers in its initial live owner, asserts no
restart request before the forced stop, then closes and reopens for settlement.
All physical writable/queue/replay and preterminal COMPLETE assertions remain.
Its first live mutation attempt exposed a fixture cleanup omission that masked
the pointer assertion (`pointer-cut-cleanup-attempt-xml/`). The failure path now
uses the same existing requested-restart drain as the normal stopped owner.
That masked result is excluded from red proof; the final mutation failed at
the exact committed B pointer comparison.

The publication-exclusion mutation also exposed a test-owner leak: a rejected
completed capture had returned a real lease that the failure path did not
close. Its first 9/4 run reported cleanup refusal rather than the acquisition
assertion (`query-capture-cleanup-attempt-xml/`), and is excluded from red proof.
The matrix now joins the capture executor after releasing the barrier and
closes any returned lease in `finally`, including assertion failure paths.

### Commands

Every command ran alone from the assigned worktree. Successful build environment:

```powershell
$env:GRADLE_USER_HOME = 'F:/justsearch-public/.claude/worktrees/lane-f-d1-close/tmp/d1-gradle-cache'
$env:TEMP = 'F:/justsearch-public/.claude/worktrees/lane-f-d1-close/tmp/d1-sandbox-temp'
$env:TMP = $env:TEMP
```

Compile and format (all passed):

```text
./gradlew.bat :modules:indexer-worker:compileJava :modules:indexer-worker:compileTestJava -PskipWebBuild=true --offline --no-daemon
./gradlew.bat :modules:worker-core:compileJava :modules:worker-core:compileTestJava -PskipWebBuild=true --offline --no-daemon
./gradlew.bat :modules:app-engine:compileJava :modules:app-engine:compileTestJava -PskipWebBuild=true --offline --no-daemon
./gradlew.bat :modules:system-tests:compileIntegrationTestJava -PskipWebBuild=true --offline --no-daemon
./gradlew.bat :modules:indexer-worker:spotlessApply --offline --no-daemon
./gradlew.bat :modules:worker-core:spotlessApply --offline --no-daemon
./gradlew.bat :modules:app-engine:spotlessApply --offline --no-daemon
./gradlew.bat :modules:system-tests:spotlessApply --offline --no-daemon
```

Full classes after restoring their production mutations:

```text
./gradlew.bat :modules:worker-core:test --tests io.justsearch.indexerworker.index.NativeGenerationPromotionTest -PskipWebBuild=true --offline --no-daemon --no-build-cache
./gradlew.bat :modules:worker-core:test --tests io.justsearch.indexerworker.index.IndexGenerationRetirementTest -PskipWebBuild=true --offline --no-daemon
./gradlew.bat :modules:indexer-worker:test --tests io.justsearch.indexerworker.server.KnowledgeServerCloseCompletionTest -PskipWebBuild=true --offline --no-daemon
./gradlew.bat :modules:app-engine:test --tests io.justsearch.app.engine.EngineMigrationLifecycleTest -PskipWebBuild=true --offline --no-daemon
./gradlew.bat :modules:app-engine:test --tests io.justsearch.app.engine.RecordedBulkIngestionCoordinatorTest -PskipWebBuild=true --offline --no-daemon
./gradlew.bat :modules:app-engine:test --tests io.justsearch.app.engine.EngineNativePointerBootMutationTest -PskipWebBuild=true --offline --no-daemon
```

The final NativeGenerationPromotionTest receipt is a fresh execution: an initial
restored invocation reused the earlier valid cache entry, so its one owned XML
output was removed and the same focused command rerun with `--no-build-cache`.
The cached receipt is retained separately as `native-restored-cache-xml/`.

### Final full-class green receipts

| Class | Tests | Failures | Errors | Skipped | XML / log receipt |
|---|---:|---:|---:|---:|---|
| NativeGenerationPromotionTest | 7 | 0 | 0 | 0 | `native-final-green` |
| IndexGenerationRetirementTest | 14 | 0 | 0 | 0 | `retirement-green` |
| KnowledgeServerCloseCompletionTest | 25 | 0 | 0 | 0 | `blue-final-green` |
| EngineMigrationLifecycleTest | 4 | 0 | 0 | 0 | `migration-green` |
| RecordedBulkIngestionCoordinatorTest | 22 | 0 | 0 | 0 | `bulk-green` |
| EngineNativePointerBootMutationTest | 2 | 0 | 0 | 0 | `pointer-green` |
| Total runnable Java tests | 74 | 0 | 0 | 0 | Copied result XML in the proof directory |

`EngineTestHarness` is a shared fixture, not an independently runnable test
class. Installed `EngineLifecycleE2ETest` and `EngineSupervisedRecoveryE2ETest`
compiled; neither was run and no runtime XML/counts are claimed for them.
Node syntax checks passed for `bulk-fault-scenario.mjs` and
`migration-restart-scenario.mjs`. The pure helper command
`node --test scripts/supervisor-conformance/bulk-fault-semantic.test.mjs scripts/supervisor-conformance/bulk-fault-citation.test.mjs`
passed 13 tests, 0 failures, 0 skips (`fixture-node-tests.log`).

Focused mutation commands (one execution per indicated mutation, then restored):

```text
./gradlew.bat :modules:worker-core:test --tests 'io.justsearch.indexerworker.index.NativeGenerationPromotionTest.freshMigration*' -PskipWebBuild=true --offline --no-daemon
./gradlew.bat :modules:worker-core:test --tests io.justsearch.indexerworker.index.IndexGenerationRetirementTest.freshTransitionNeverUsesMoreThanTwoDirectoriesAndRetiresToOne -PskipWebBuild=true --offline --no-daemon
./gradlew.bat :modules:indexer-worker:test --tests io.justsearch.indexerworker.server.KnowledgeServerCloseCompletionTest.migrationPauseMatrixDoesNotCloseBlueBeneathAnIssuedQuery -PskipWebBuild=true --offline --no-daemon
./gradlew.bat :modules:app-engine:test --tests io.justsearch.app.engine.EngineMigrationLifecycleTest.cutoverSwapsTheGenerationPointerAndPreservesSearch -PskipWebBuild=true --offline --no-daemon
./gradlew.bat :modules:app-engine:test --tests io.justsearch.app.engine.EngineMigrationLifecycleTest.cutoverActivatesTheGenerationInProcess -PskipWebBuild=true --offline --no-daemon
./gradlew.bat :modules:app-engine:test --tests io.justsearch.app.engine.RecordedBulkIngestionCoordinatorTest.promotedRecoveryRequiresWritableSealedAndReplaySettledBeforeCompletion -PskipWebBuild=true --offline --no-daemon
./gradlew.bat :modules:app-engine:test --tests io.justsearch.app.engine.EngineNativePointerBootMutationTest.recordedBulkPostPointerStopRecoversAfterWritableQueueAndReplayWitnesses -PskipWebBuild=true --offline --no-daemon
```

### Red demonstrations

Counts below are tests/failures/errors/skipped, copied from the result XML.
The listed failures were inspected for the intended assertion, not just a red
Gradle exit. Environmental and masked fixture attempts above are excluded.
Each receipt name denotes `<name>.log` and `<name>-xml/` under the proof directory.

| Clause / mutation | XML counts | Intended red / receipt |
|---|---|---|
| D1-9: bypass fresh-start and build-capacity refusals | 2/2/0/0 | Both second starts returned instead of throwing IOException; `fresh-refusal-red`. |
| D1-9: replace retained-state reasons | 2/2/0/0 | Both exact message assertions failed; `retained-reasons-red`. |
| D1-9: delete durable state while still refusing | 2/2/0/0 | Both reopened exact state/pointer comparisons failed; `refusal-pointer-red`. |
| D1-8b: suppress physical predecessor deletion | 1/1/0/0 | Final directory count was 2 instead of 1; `retirement-disk-red`. |
| D1-8a: return success at the held-view deadline | 1/1/0/0 | Five-second deadline failed to report retained ownership; `retirement-timeout-red`. |
| D1-8c: combine Blue's total with Green's IDs | 1/1/0/0 | Full-page response consistency failed inside the across-swap loop; `search-mixture-red`. |
| D1-8c: throw on Green search | 1/1/0/0 | Actual Green query failure propagated; `search-error-red`. |
| D1-8e: bypass writer and replay admission | 1/1/0/0 | Restarted B pointer finished COMPLETE while writer still named A; `writer-order-red`. |
| D1-8e: bypass replay guard only | 1/1/0/0 | Exact writable B and sealed settlement finished COMPLETE before replay settled; `replay-order-red`. |
| D1-8e: move post-pointer hook before pointer commit | 1/1/0/0 | Committed-generation witness named A instead of exact B; `pointer-cut-red`. |
| D1-8f / Blue lifetime: unchanged lane production | 25/9/0/0 | All nine issued-before-start Blue queries found their runtime closed; `blue-real-red`. |
| D1-8f: remove query capture's publication read lock | 9/4/0/0 | Capture completed or stayed RUNNABLE at all four write-held points; `query-capture-lock-red`. |

### Final sanity and closeout

All four final Spotless invocations passed (`*-final-format.log`). All temporary
production mutations restored their original bytes; the only production diff
is the authorized Blue lifetime fix in `KnowledgeServer`. Strict UTF-8 decoding
and BOM checks passed for all ten Java owners (nine assertion/fixture files and
that production owner). `git diff --check` passed. The added-line non-ASCII scan
`LC_ALL=C.UTF-8 git diff | grep -P '^\+.*[^\x00-\x7F]'` printed nothing.
Final diff statistics are retained in `tmp/d1-granted-proof/final-diff-stat.txt`.
Read-only world-state output is in `world-state-closeout.txt`; unrelated OTLP and
identity-refused UI-shot records were left alone. Shared registry cleanup was
omitted because it writes outside the assigned worktree. No commit, push,
sub-agent, dev stack, or installed Engine launch occurred. The worktree is dirty
by the owner's explicit no-commit instruction.

## Remaining expression limits

For D1-8f (`D1.md:1620-1622`), query capture takes the publication read lock and
then resolves the current view and increments its holder count under one
`servingViewMonitor` critical section (`KnowledgeServer.java:5854-5874`). There
is no barrier between read-lock acquisition, view selection, holder increment,
or the subsequent encoder/native-session acquisition performed by the selected
service. The existing WP1 hooks pause the nine live-open/drain/switch/pointer/
publication boundaries; they cannot insert a pause into those acquisition
substeps. The extra `migration-after-first-projection-replay` hook belongs to
recorded replay, not query acquisition (`MigrationTransitionBarrier.java:31-37`).

Individual swap assignments likewise share the publication write lock and the
serving-view monitor (`KnowledgeServer.java:7857-7891`). Before/after activation
hooks bracket that block; they cannot split runtime/model/view assignments.
The matrix proves existing issued Blue stays usable, new capture parks behind
the publication writer, and post-publication capture resolves Green. Flow A
intentionally retains one compatible `EncoderSet` owner, so this fixture cannot
distinguish encoder A from a different encoder B or prove the literal forbidden
different-model A/Green pairing. D1 itself assigns differing-model Flow B
witnesses to D1-12. No new hook was added.

Design decision input: decide whether the atomic-section ownership proof plus
publication-exclusion mutation closes the indivisible acquisition portions, or
authorize a separate acquisition seam and differing-model fixture in subsequent
work. Literal per-substep pauses and the differing-model pairing remain unproved
by this task's matrix.

Installed manifest identity and real native-session hard-stop scenarios cannot
have runtime red/green proof in this assignment because launching an installed
Engine is expressly prohibited. Their Java scenarios compile; that is not a
successful installed run. These and the precise D1-8f limits above remain inputs
to the owning acceptance/design decision. This receipt does not close D1.
