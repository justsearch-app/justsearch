# D1 acceptance reconciliation (2026-09-30)

Read-only clause-by-clause reconciliation by Sol (gpt-6.1-sol) at 3b93e3662 for D1-2, D1-6, D1-7, D1-8, D1-9, D1-10, D1-11, D1-13. Verbatim. The closure plan derived from it is in handoff.md.

Reconciled against HEAD `3b93e3662`. No edits, Gradle runs, or backend starts.

Paths below are relative to the worktree. **V** means `tmp/lane-f-restoration-checkpoint-integrated-r1-xml/`; XML filenames are `TEST-<fully-qualified test class>.xml` under the indicated module. Counts mean **tests / failures / errors / skips**.

V’s [counts artifact](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/lane-f-restoration-checkpoint-integrated-r1-counts.json) records base revision `3ce0357dae4`; the ledger associates its dirty parser-gap tree with checkpoint `ef636fa3a`. This is not exact-HEAD evidence. I checked subsequent commits against the relevant owners: `1ac1407ef` materially changes migration source enumeration and collection admission; `8d512c84b` is formatting only. Unchanged projections, retirement mechanisms, and replay settlement checks remain reusable.

**D1-2**

| Clause | D1.md line | Class | Evidence path or missing piece |
|---|---:|---|---|
| “a table test asserts the schema-2 snapshot equals the projection of every component-state combination” | 296–297 | PROVED | V/app-services/`EngineLifecycleProjectionTest`: **4/0/0/0**; `all1296CombinationsPreserveComponentVectorAndUseTheOneAggregatePolicy` checks all 1,296 combinations. Projection owner unchanged since recorded base. |
| “`StatusRecordSchemaTest`, `LifecycleSnapshotTapTest`, `LifecycleContractTest` green” | 297–298 | PROVED | V/app-api/`StatusRecordSchemaTest` and nested XML: **21/0/0/0**; V/app-services/`LifecycleSnapshotTapTest`: **29/0/0/0**; V/ui/`LifecycleContractTest`: **10/0/0/0**, including schema-v2 health/status cases. |
| “a test asserts the manifest lifecycle and the snapshot agree for every combination” | 298–299 | PROVED | Same exhaustive projection test compares snapshot aggregate with `LifecycleProjection.derive`, the manifest’s projection. |
| “both hosts’ essential-ready rewritten and the conformance harness green on both adapters” | 299–300 | PROVED | `tmp/5364-d1-7-supervisor-dev.txt`, `tmp/5367-d1-7-supervisor-tauri-rebuilt.txt`: **18/18 each**, including `live-503-and-continuous-essential-stability`. SHA unrecorded; host predicate unchanged. Later `bb399e495` changes spawn GC flags. |
| “the health and status response schema regen (`regen-all --check`) green” | 300 | PROVED | `tmp/lane-f-resume-regen-check.log:10`: all **8 generated sets match**. No SHA recorded; relevant status schema sources unchanged afterward. |
| “`readinessNotice.ts`’s snapshot readers updated with the ui-web gates green” | 301 | PROVED | `tmp/lane-f-encoder-notice-{typecheck,unit,step-coverage}.log`: **491 files / 6,636 tests passed**, typecheck and coverage green. Associated checkpoint `a544cf437`; notice owner unchanged afterward. |

§16: **recovery workflow** — per-component `stateSince` and actual text/semantic readiness after death (5164).

Smallest closure: no additional primary-clause run; reuse these artifacts. The §16 death/readiness condition belongs in the shared installed recovery round.

**D1-6**

| Clause | D1.md line | Class | Evidence path or missing piece |
|---|---:|---|---|
| “the installed-tier restart-required test (D1-4)” | 487 | EXTERNAL | `tmp/5549-sandbox-port/output/installed-api-port-sandbox-result.json` records failure because Application Control blocked the unsigned installer. Signed successor/value proof is assigned to **E7**. V/app-services/`SettingsCommitCoordinatorTest` (**52/0/0/0**) supplies unit proof only. |
| “`RestartWorkerHandlerTest` and `InferenceHandlersWorkerRestartTest` rewritten” | 487–488 | PROVED | Superseding tests: V/ui/`RetiredWorkerRestartRouteTest` **2/0/0/0**, and `InferenceHandlersComponentRecoveryTest` **5/0/0/0**. They exercise permanent retirement and replacement recovery routing. Relevant owners unchanged since V. |
| “`git grep restart-worker` returns only labelled history” | 488–489 | PARTIAL | Current source inspection finds retired-contract/history references only; no named retained grep-result artifact found. |
| “`WholeProgramDeadCodeTest` green with a shrinking baseline” | 489 | PARTIAL | V/dead-code-audit/`WholeProgramDeadCodeTest`: **1/0/0/0**, `no_new_whole_program_dead_classes`. No shrink receipt; committed `archunit_store` has no shrink diff from the migration base. |
| “the surface catalog’s `surface-altitude` gate green” | 489–490 | PROVED | `tmp/5559-surface-altitude.log`: **1 gate, 0 failures, 0 findings**, `surface-altitude: pass`. SHA unrecorded; operation altitude unchanged afterward. |

§16: **no direct mapping**.

Smallest closure: retain a retirement grep receipt and reconcile the baseline-shrink obligation; then E7’s signed restart-required scenario. Rerunning a green dead-code test alone does not establish shrinkage.

**D1-7**

| Clause | D1.md line | Class | Evidence path or missing piece |
|---|---:|---|---|
| “an index open held past its deadline by the existing harness barrier turns `FAILED` with its reason, `api` stays `READY`” | 598–599 | STALE | `tmp/5532-integration-xml/TEST-io.justsearch.systemtests.supervision.EngineSupervisedRecoveryE2ETest.xml`: **2/0/0/0**, lock-release/exhaustion scenarios use the separate initial-open barrier. Later physical bootstrap/readiness changes: `ef636fa3a`, `1ac1407ef`. V/ui/`HeadlessAppInitialIndexDeadlineTest` **4/0/0/0** is newer unit proof. |
| “An exclusive root lock held through `FileIntruder` separately proves real OS open refusal” | 599–601 | STALE | Same installed XML and `tmp/5532-installed-index-recovery.log`: both named lock scenarios passed. Physical bootstrap changed afterward as above. |
| “A recovery after the lock releases restores `READY` with no restart and `recoveryAttempts` 1” | 601–602 | STALE | Same `lock-index-release` artifact; older `tmp/5509-installed-publication.log` also records the post-recovery VECTOR query. Same later bootstrap drift. |
| “the same lock held exhausts two attempts and escalates to one restart the supervisor counts (`lastExit.counted === true`, code 5) with `index` `READY` after” | 602–604 | STALE | Same `lock-index-exhaustion` artifact. Current installed successor proof needed after bootstrap/readiness changes. |
| “an optional component in the same state never escalates” | 604–605 | PROVED | V/app-services/`ComponentRecoveryMonitorTest`: **35/0/0/0**; `automaticBudgetIsTwoAndOptionalExhaustionDoesNotEscalate`. Generic monitor behavior unchanged after V. |
| “a manual recover during an automatic attempt answers 429” | 605 | PROVED | V/ui/`InferenceHandlersComponentRecoveryTest`: **5/0/0/0**; `busyRecoveryReturnsEngineLimitWithoutSchedulingAnotherAttempt`. |
| “both adapters green with the new case” | 605–606 | PROVED | `tmp/5364-d1-7-supervisor-dev.txt`, `tmp/5367-d1-7-supervisor-tauri-rebuilt.txt`: **18/18 each**, including `escalated-restart`. SHA unrecorded; exit-5 classification unchanged. |
| “`EngineExit` drift tests green” | 606 | PROVED | V/app-engine/`EngineExitTest`: **4/0/0/0**, including named-code classification and unknown-native-code TRANSIENT behavior. Owner unchanged. |

§16: **recovery workflow** (5164), **stuck component** (5165), **reconfigure** (5171).

Smallest closure: one installed standard-model release/exhaustion round through `EngineSupervisedRecoveryE2ETest.supervisedIndexRecoveryUsesTheRetainedStandardEmbedding`; it covers the deadline barrier, OS refusal, local recovery, escalation and VECTOR recovery together. Add the existing real-chat/native recovery scenarios for the §16 death/reconfigure boundary.

**D1-8**

| Clause | D1.md line | Class | Evidence path or missing piece |
|---|---:|---|---|
| “`EngineMigrationLifecycleTest.cutoverDoesNotChangeWhatThisProcessServesUntilItRestarts` inverted into `cutoverActivatesTheGenerationInProcess`: the live process counts Green’s documents after cutover with `restartCount` unchanged … and the manifest instance id unchanged” | 1643–1646 | PARTIAL | V/app-engine/`EngineMigrationLifecycleTest`: **4/0/0/0**, including live Green document count. Installed matrix records unchanged restart count, but exact manifest-instance assertion was not established. Enumeration/count proof is also stale after `1ac1407ef`. |
| “a search loop running across the swap sees answers from one generation or the other and never an error, asserted on every response” | 1646–1647 | PARTIAL | Installed semantic sampling exists; no complete retained per-response A/B-generation witness found for this exact Flow-A clause. |
| “the old runtime’s handles are released … and the retained-on-timeout branch is exercised with a held generation lease” | 1647–1649 | PARTIAL | V/app-engine/`EngineMigrationLifecycleTest` includes `live cutover retains Blue for a held view, then retires it after release`. Release/deletion is proved; an explicit held-lease **timeout-branch** witness remains missing. |
| “`EngineMigrationRestartDispatchTest` and `CutoverRestartEvidenceTest` rewritten to evidence-before-activation” | 1649–1650 | PROVED | V/app-engine/`EngineMigrationRestartDispatchTest` **2/0/0/0**; V/indexer-worker/`CutoverRestartEvidenceTest` **9/0/0/0**, including `nativeLiveCutoverPublishesWithoutRequestingRestart`. |
| “the E2E `migration` scenario asserts no exit and the promoted generation served” | 1650–1651 | PARTIAL | `tmp/lane-f-generation-transition-matrix-r3-xml/…EngineLifecycleE2ETest.xml`: **4/0/0/0**, with watcher/gap/two pointer-cut cases. These prove related outcomes; no retained exact `migration` scenario receipt was located. |
| “the on-disk generation count is 1 after step8, never above2 throughout a fresh transition” | 1651–1652 | PARTIAL | V/worker-core/`NativeGenerationPromotionTest` **5/0/0/0** and `IndexGenerationRetirementTest` **13/0/0/0** prove capacity/retirement pieces. Missing complete fresh-transition disk-count trace. |
| “a forced kill between steps 3 and 6 boots with Green active, the reindex row advanced to `COMPLETE` only after the exact writable B and replay settlement are witnessed” | 1652–1653 | PARTIAL | Installed matrix has both pointer cuts; V/app-engine/`RecordedBulkEngineRestartTest` **4/0/0/0** proves build-restart recovery. Missing one retained witness joining the exact cut, writable B, sealed queue and settlement-before-terminal ordering. |
| “fault/pause between each acquisition and swap step must prove no query ever pairs A’s encoder with Green or closes Blue beneath an acquired query” | 1620–1622 | PARTIAL | Held-view proof exists; no complete acquisition/swap fault matrix located. |

§16: **generation transition** (5166), **combined low-memory reindex, changing inputs, interruption** (5168).

Smallest closure: `EngineMigrationLifecycleTest` plus installed `migrationStartsLiveInTheInstalledEngine`, `issuedASearchCompletesAfterBesideBServes`, and the two low-memory pointer-cut scenarios. **Runs alone cannot close the missing count/order/fault witnesses; those assertions must first be present.**

**D1-9**

| Clause | D1.md line | Class | Evidence path or missing piece |
|---|---:|---|---|
| “an edit, a removal and an addition accepted during the rebuild are all reflected at activation and the removal is absent” | 1865–1866 | STALE | Installed matrix **4/0/0/0**, `watcherAddDeleteReplayDuringInPlaceBuildKeepsAAndPromotesB`; V/app-engine/`RecordedBulkEngineRestartTest` **4/0/0/0** also covers registered non-file update/delete/addition. File enumeration/admission changed in `1ac1407ef`. |
| “one failed-unsuperseded unit refuses with the gap named, the row `COMPLETE_WITH_GAPS` and the wire answer `running` with `phase = awaiting_acceptance`” | 1866–1868 | PROVED | V/app-engine/`RecordedBulkIngestionCoordinatorTest` **21/0/0/0**, unsuperseded-gap cases; `tmp/3477-gap-installed.txt` supplies wire evidence. Parser-gap R6 XML is **1/0/0/0**, `refusedInPlaceGapRestoresAThenApprovesAndPromotesB`. |
| “a unit captured at H1, changed to H2 and replayed at H2 is `superseded` and does not block” | 1868–1869 | PROVED | V/indexer-worker/`SwitchBufferStrictReplayTest` **37/0/0/0**, `capturedReplayRefreshesBeforeCertifyingPlannedH1AndIndexedH2`; coordinator parameterized settlement cases also assert superseded-event count. |
| “an unreadable count refuses” | 1869 | PROVED | V/indexer-worker/`CutoverRestartEvidenceTest` **9/0/0/0**, `unreadableFailedJobsCountRefusesCutoverAndKeepsBlue`. Refusal logic unaffected by the later enumeration change. |
| “after refusal the candidate is on disk and the semantic legs answer on A within the reconfigure budget” | 1869–1870 | PARTIAL | R6 retains B and restores A: **2 A VECTOR hits**, **299 hybrid probes answered**, no unexpected/API-outage samples. Exact restoration elapsed time against the configured reconfigure budget was not located. |
| “after cancel it is not and only the active generation remains” | 1870–1871 | PROVED | V/app-engine/`RecordedBulkEngineRestartTest` **4/0/0/0**, `cancellingAcceptedBulkRetiresItsCandidateAndReopensOnlyA`. |
| “a second distinct `startFreshMigration` while a candidate or predecessor occupies capacity is refused with the retained-state reason” | 1871–1873 | UNPROVED | Capacity tests exercise ordinary `startMigration`/recorded starts. No artifact exercises the exact fresh-start call, both occupied states and retained-state reason. |
| “the reconciler advances a `RUNNING` row with the pointer swapped only after an exact writable B, sealed queue and settled replay are witnessed” | 1874–1875 | PROVED | V/app-engine/`RecordedBulkIngestionCoordinatorTest` **21/0/0/0**: prepared/promoted-boot and `promotedBulkWaitsForReplaySettlementBeforeCompletingExactlyOnce` cases. |
| “one with a promoted pointer but no B writer or unsettled replay remains nonterminal for recovery” | 1876 | PROVED | Same coordinator artifact explicitly asserts nonterminal state without exact writable binding and while settlement is pending. |
| “recognized open-row preparation/binding damage fences boot” | 1877 | PROVED | V/worker-core/`RecordedGenerationBootTest` **29/0/0/0**; V/app-engine/`RecordedInstallerGenerationBootProjectionTest` **10/0/0/0**, damaged/unowned recorded-generation fencing cases. |
| “a pointer still on A resumes replay” | 1877 | PROVED | V/indexer-worker/`ProjectionCandidateReplayTest` **5/0/0/0**, `committedCandidateReplayReseedsAndRechecksAfterQueueAndWriterRestart`; recorded boot/restart suites support recovery. |
| “`JobQueueMigrationTest` green, including any versioned buffer extension required by955-5” | 1878 | PROVED | V/indexer-worker/`JobQueueMigrationTest` **26/0/0/0**, including `v19SwitchBufferRowsAcquireDurableOrderBeforeFurtherAdmissions`. Schema-migration logic unchanged afterward. |

§16: **generation transition** (5166), **combined low-memory interruption** (5168), **combined delayed retry** (5169), **resume conditions** (5170).

Smallest closure: rerun the watcher mutation and parser-gap installed scenarios; add exact fresh-start capacity/reason coverage to `NativeGenerationPromotionTest` or `IndexGenerationRetirementTest`, and record restoration timing. Reuse the proved settlement/fencing suites.

**D1-10**

| Clause | D1.md line | Class | Evidence path or missing piece |
|---|---:|---|---|
| “during a rebuild the served id is Blue and the pointer is Blue; after activation both are Green with no restart” | 1998–1999 | PROVED | V/worker-services/`MidMigrationCompatSurfaceTest` **3/0/0/0**, including `committedPointerDoesNotRelabelAnOldOpenServingRuntime` and post-cutover comparison; `tmp/3099-served-generation-installed-migration.txt` records Blue→Green and restart count 0. Current served-ID projection unchanged after V. |
| “`MidMigrationCompatSurfaceTest`, `StatusRecordSchemaTest` … green” | 1999–2000 | PROVED | Same **3/0/0/0** artifact; V/app-api schema suites total **21/0/0/0**. |
| “the status schema regen green” | 2000 | PROVED | `tmp/lane-f-resume-regen-check.log`: **8 sets match**; relevant schema sources unchanged. |

§16: **generation transition** (5166).

Smallest closure: no additional primary-clause run; the shared installed migration rerun can refresh the provenance.

**D1-11**

| Clause | D1.md line | Class | Evidence path or missing piece |
|---|---:|---|---|
| “an MCP-kind context is refused with the code” | 2024 | PROVED | V/app-services/`AcceptGapsHandlerTest` **2/0/0/0**, `mcpCannotPrepareGapAcceptanceAndMalformedReplayCannotExecute`. |
| “a stale hash is refused” | 2024 | PROVED | V/app-engine/`RecordedBulkIngestionCoordinatorTest` **21/0/0/0**, `candidateOnlyGapControlsPromotedReceiptAndFencedApprovalRejectsStaleHash`; asserts `GAP_LIST_STALE`. |
| “a webview acceptance activates and clears the phase” | 2024–2025 | PROVED | Handler’s `webviewApprovalFreezesExactListAndDispatchesOneRecordedDecision`; installed parser-gap R6 **1/0/0/0** proves exact approval, same-B promotion and terminal settlement. |
| “ui-web typecheck, unit tests and gates green” | 2025 | PROVED | `tmp/lane-f-encoder-notice-{typecheck,unit,step-coverage}.log`: **6,636 passing tests**, typecheck/coverage green. Older gap-specific suite is **6,601/0** at `tmp/3463-gap-ui-unit.txt`. |
| “the `/ui-check` step recorded” | 2026 | PARTIAL | `tmp/ui-shot-gap/library-gap-decision.{png,measure.json}` exists: **0 axe violations, 0 console errors, no overflow**. Capture SHA is unrecorded; later Library changes include `331a860bc` and `16ce5602c`. No current verification receipt found. |

§16: **no direct mapping**.

Smallest closure: one current `library-gap-decision` UI check; reuse handler/hash proof and the shared parser-gap installed round.

**D1-13**

| Clause | D1.md line | Class | Evidence path or missing piece |
|---|---:|---|---|
| “deterministic held-call proofs for GPU and CPU (a lease held across retire, the session provably open until release)” | 2345–2346 | PROVED | V/ort-common/`NativeSessionHandleTest$CpuSessionFailureRecovery` **9/0/0/0**, named held-CPU/GPU cases. Real GPU probe `tmp/3904-installed-gpu-lease.txt` records one `INSTALLED_GPU_LEASE_PASS`, inputs 2, RETIRED. Later `692097947` changes timeout handling, not successful held-lease release. |
| “concurrent CPU failure plus recreation with a held old lease” | 2346–2347 | PROVED | Same **9/0/0/0** XML, `cpuRecreationWaitsForHeldOldInstanceBeforeClosingIt`. Handle owner unchanged since current native proof. |
| “the real native stress with the stricter oracle” | 2347 | PROVED | V/ort-common/`NativeSessionHandleConcurrentStressTest`: **1/0/0/0**, ten-thread native mix with typed retirement refusal and closed-session safety assertions. |
| “a recompose and an ordered shutdown integration test asserting no lease issued after retirement begins” | 2347–2348 | PROVED | V/indexer-worker/`KnowledgeServerCloseCompletionTest`: **16/0/0/0**, `inPlaceBuildWaitsForIssuedNativeLeaseBeforeComposing` and `orderedShutdownRetainsRealNativeSessionUntilIssuedLeaseExits`. Later Help enumeration changes do not alter these lifetime paths. |
| “the timeout branch retains and a retry completes” | 2348–2349 | PROVED | Native **9/0/0/0** XML, `retirementTimeoutCountsHeldCpuAndGpuLeasesAndRetainsSessionsForRetry`. |
| “Installed initial A, candidate B, and restored A log-to-manifest correlation” | 2319–2320 | PROVED | Parser-gap R6 **1/0/0/0** and its fixture’s `installer-gap-citation-window.json`/current-run Engine log bind the A→B→A→B identities. |
| “Production and isolated child-process proof remain required” | 2326 | PARTIAL | V/app-engine/`EngineShutdownSequenceTest` **19/0/0/0**, including `isolatedProcessRunsJvmHookOnlyForConfirmedNativeQuiescence`; V/ui shutdown wiring **16/0/0/0**. Missing installed real-native unquiesced **controlled-exit** proof, rather than injected disposition/wiring proof. |

§16: **no direct mapping**.

Smallest closure: one installed held-native shutdown scenario exercising controlled hard termination; the primary lease/recreation/stress clauses need no rerun.

**Combined minimal run plan**

| Group | Minimal work |
|---|---|
| **Focused unit/integration — Gradle** | One grouped run of `EngineMigrationLifecycleTest`, `EngineMigrationRestartDispatchTest`, `CutoverRestartEvidenceTest`, `RecordedBulkEngineRestartTest`, `RecordedBulkIngestionCoordinatorTest`, `NativeGenerationPromotionTest`, `IndexGenerationRetirementTest`, `MidMigrationCompatSurfaceTest`, and `JobQueueMigrationTest`. First add the missing fresh-start/reason, timeout-branch, disk-count and exact settlement-order assertions. Retain grep and baseline-diff evidence separately. |
| **Installed standard-model scenarios** | One release/exhaustion recovery pair; then `migrationStartsLiveInTheInstalledEngine`, `issuedASearchCompletesAfterBesideBServes`, `watcherAddDeleteReplayDuringInPlaceBuildKeepsAAndPromotesB`, `refusedInPlaceGapRestoresAThenApprovesAndPromotesB`, and both `lowMemoryInPlaceChangingInputRecovers…` pointer cuts. Record restoration budget and transition counts. Add the held-native controlled-shutdown scenario. Run current `library-gap-decision` UI verification alongside this round. |
| **Hosted CI** | One exact-final-SHA CI run after the missing assertions/proofs are coherent. Retain job results and attribution; older green job subsets do not establish current full hosted acceptance. |
| **External** | **E7 signed Sandbox round:** restart-required setting completes, requests the uncounted restart, and the signed installed successor boots with the persisted value. |