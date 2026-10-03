# D1 reconfigure: remaining work and design (2026-09-30)

Read-only design investigation by Sol (gpt-6.1-sol) at f4ceee088. Verbatim output; the chosen mechanism is recorded in the handoff.

The remaining gap is **memory-aware ordinary reconfigure of query-only encoders**, principally the reranker. Generation-bound model changes already use Flow B; ordinary reconfigure currently builds its query replacement beside A unconditionally.

Read-only investigation at `f4ceee0`; HEAD advanced to `c0536256` during investigation, with the inspected runtime files unchanged. No edits, Gradle runs, or backend starts.

**1. Acceptance reconciliation**

Quotes below are from [D1.md](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/docs/design/lane-f-engine-jvm/stages/D1.md).

| Clause | Judgment against code and inspected evidence |
|---|---|
| **D1-4:424–426:** “a two-component reconfigure with the second compose made to fail” preserves A, closes the first candidate, and leaves revision unchanged | **DONE-with-evidence for the existing beside transaction; PARTIAL across both modes.** The installed rollback asserts candidate closure, unchanged settings/config and zero restarts: [EngineSupervisedRecoveryE2ETest.java:220](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/supervision/EngineSupervisedRecoveryE2ETest.java:220). Inspected passing [installed log:215](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/d1-4-two-owner-installed-r6.log:215). It does not retire A before preparing B. |
| **D1-4:427–429:** “a change touching no declared dependency leaves every applied version unchanged”; “a generation-bound key answers the refusal with the reindex pointer” | **DONE-with-evidence.** Regressions at [SettingsCommitCoordinatorTest.java:810](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/app-services/src/test/java/io/justsearch/app/services/settings/SettingsCommitCoordinatorTest.java:810) and [:221](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/app-services/src/test/java/io/justsearch/app/services/settings/SettingsCommitCoordinatorTest.java:221); inspected archived XML reports 52 cases, zero failures/errors. |
| **D1-4:429–430:** “restart-required key answers `COMPLETE` with `restartScheduled` and the successor boots with the value” | **PARTIAL.** Commit-before-restart and same-key non-repetition have unit proof at [SettingsCommitCoordinatorTest.java:981](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/app-services/src/test/java/io/justsearch/app/services/settings/SettingsCommitCoordinatorTest.java:981). Signed installed successor proof remains open; signing-provider blockage at **D1.md:573–574 (doc-only)**. |
| **D1-4:430–431:** “forced kill mid-compose” boots A and reconciles `FAILED/ENGINE_RESTARTED_DURING_APPLY` | **DONE-with-evidence for generative composition; PARTIAL for the new in-place query path.** Existing physical cut is recorded in the inspected [mid-compose artifact:7](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/3078-mid-compose-physical-standard.txt:7). |
| **D1-12:2067–2072:** “search on A while B embeds … into Green”; retirement waits for leases; independent runtime identities and serving-set latch | **PARTIAL as a complete acceptance bundle; substantial substrate already exists.** Installed BESIDE/held-search cases are wired at [EngineLifecycleE2ETest.java:72](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/supervision/EngineLifecycleE2ETest.java:72). Independent identity/latch regression: [EncoderSetTest.java:82](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/EncoderSetTest.java:82). Runtime fingerprint independence: [SsotCommitMetadataSourceSnapshotTest.java:69](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/commit/SsotCommitMetadataSourceSnapshotTest.java:69). Reconcile each exact clause/environment rather than infer full closure from these tests. |
| **D1-14:2523–2528:** four fake-line cases; override “forces in place”; installed run records “floor simulated by device-memory cap” | **DONE-with-evidence on the generation path; OPEN on ordinary reconfigure.** Four-mode regressions start at [KnowledgeServerDeviceMemoryLineTest.java:75](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerDeviceMemoryLineTest.java:75); inspected XML has 11 passing cases. Inspected installed matrix proves CUDA-A restoration and floor behavior: [matrix log:427](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/lane-f-generation-transition-matrix-r3.log:427). |
| **D1-16:3150:** “reconfigure beside and in place under the ceiling key” | **OPEN.** Still explicitly PENDING at [EngineLifecycleE2ETest.java:19](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/supervision/EngineLifecycleE2ETest.java:19). |

**2. Current request path**

`POST /api/settings/v2` dispatches `core.reconfigure` with settings, witness and operation key ([SettingsController.java:110](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/ui/src/main/java/io/justsearch/ui/api/SettingsController.java:110)). `ReconfigureHandler` delegates to the accepted settings owner ([ReconfigureHandler.java:131](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/app-services/src/main/java/io/justsearch/app/services/registry/operations/handlers/ReconfigureHandler.java:131)).

Then:

- **Generation-bound change:** refused **before component preparation**, with `GENERATION_BOUND_REQUIRES_REINDEX` and `core.bulk-reindex`; no automatic migration or restart ([SettingsCommitCoordinator.java:691](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/app-services/src/main/java/io/justsearch/app/services/settings/SettingsCommitCoordinator.java:691)).
- **Reranker/citation path change:** classified `component:encoders` ([config-apply.v1.json:874](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/governance/config-apply.v1.json:874), [:510](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/governance/config-apply.v1.json:510)). The fixed owner calls `KnowledgeServer.prepareQueryRoleSettings` ([EngineRoot.java:189](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/app-engine/src/main/java/io/justsearch/app/engine/EngineRoot.java:189)).
- That method preserves index-model ownership, freezes query identities, and **unconditionally calls `composeQueryRoles`**—without a device decision ([KnowledgeServer.java:4594](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java:4594), [:4621](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java:4621)).
- Successful preparation joins atomic settings replacement/publication; old query sessions retire after issued views drain ([SettingsCommitCoordinator.java:785](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/app-services/src/main/java/io/justsearch/app/services/settings/SettingsCommitCoordinator.java:785), [KnowledgeServer.java:4737](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java:4737)).

`COMPONENT_PREPARATION_REQUIRED` is the composer’s **missing-owner** refusal, not today’s normal query-model branch ([FixedSettingsComponentComposer.java:91](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/app-services/src/main/java/io/justsearch/app/services/settings/FixedSettingsComponentComposer.java:91)).

**3. Smallest design**

Reuse the **device rule, immutable serving views, publication locks, lease drain, prepared transaction and restoration pattern**. Do not call the generation-wide in-place routine unchanged: it requires Green and retires both index and query owners ([KnowledgeServer.java:3521](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java:3521), [:3588](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java:3588)).

Proposed changes:

- **`InferenceCompositionRoot`:** extract query-plan footprint calculation using the same selected variants/policies as query composition. Count every rebuilt query session; exclude retained index roles.
- **`KnowledgeServer.PreparedQueryRoleSettings`:** insert `DeviceMemoryLine.decision` before composition. BESIDE keeps today’s path. IN_PLACE publishes a borrowed degraded query successor plus `RELOADING`, releases its own captured A lease, drains issued A views, closes only A’s `QueryRoleSet`, then prepares B.
- Freeze exact A configuration/selection before retirement. Restore A on **every** precommit failure: B compose, later owner, validation, cancellation or settings replacement. Preparation must restore internally if it throws before returning—the composer has not yet registered that owner ([FixedSettingsComponentComposer.java:115](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/app-services/src/main/java/io/justsearch/app/services/settings/FixedSettingsComponentComposer.java:115)).
- **`DefaultWorkerAppServices`:** reuse `prepareQueryServingSuccessor`; retain producer ownership on A throughout preparation. Transfer only at final publication—the existing transfer is one-way ([DefaultWorkerAppServices.java:762](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/worker-services/src/main/java/io/justsearch/indexerworker/server/DefaultWorkerAppServices.java:762), [:1110](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/worker-services/src/main/java/io/justsearch/indexerworker/server/DefaultWorkerAppServices.java:1110)).
- Extend the **existing encoder recovery context** for failed query-only restoration; its present guard rejects half-retired ownership ([KnowledgeServer.java:2442](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java:2442)).
- Carry existing `ComposeEvidence` through composer/coordinator/result projection. Keep the current sole settings commitment point.

Estimate: **450–700 production lines; 400–700 regression/harness lines**. No new durable writer, migration, or transition coordinator.

**4. Required proof**

Extend the existing query-owner, device-line and settings-coordinator regressions for:

- BESIDE search during B preparation.
- IN_PLACE query degradation, lexical availability and uninterrupted producer/index ownership.
- Own-capture drain; held issued A request; retirement timeout restoring untouched A.
- B refusal and later-owner/validation/cancellation refusal restoring exact A.
- A restoration failure retaining both reasons and entering existing recovery.
- CPU fallback, unknown memory and insufficient source release refusing before retirement.
- Precommit kill → A/FAILED; postcommit kill → B/COMPLETE.

Replace the pending row with **one installed scenario containing BESIDE and forced-IN_PLACE rounds through settings reconfigure**, using a genuinely CUDA-backed reranker. Citation alone is CPU-only and cannot establish pressure ([InferenceCompositionRoot.java:200](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/InferenceCompositionRoot.java:200)).

Reuse the installed runner/sampler helpers ([EngineSupervisedRecoveryE2ETest.java:263](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/supervision/EngineSupervisedRecoveryE2ETest.java:263)). Force pressure **at launch** with `JUSTSEARCH_GPU_DEVICE_MEMORY_CEILING_MB=1`, as existing low-memory fixtures do ([real-writer-recovery.mjs:348](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/scripts/supervisor-conformance/real-writer-recovery.mjs:348)). Assert realized CUDA A/B/restored A, mode/reason/bytes, model queries, unchanged generation, zero API outage/restarts, and unchanged witness on refusal.

**5. Risks and doc contradictions**

- **Capture deadlock:** current preparation retains its own A view until retirement/abort; it must release that hold before waiting for A’s drain ([KnowledgeServer.java:4583](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java:4583), [:4786](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java:4786)).
- **False releasable memory:** existing generation estimation uses planned configuration, not realized CPU fallback. Query admission must conservatively account only for actually GPU-backed query ownership ([KnowledgeServer.java:3465](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java:3465)).
- **“Ceiling” is not a hard VRAM bound.** It clamps observations; admission uses free plus releasable source memory and ignores total. The truthful claim is operation under simulated memory pressure ([DeviceMemoryLine.java:13](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/core/src/main/java/io/justsearch/core/component/DeviceMemoryLine.java:13), [:33](F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/modules/core/src/main/java/io/justsearch/core/component/DeviceMemoryLine.java:33)).
- D1.md:2533’s historical “candidate path still refuses IN_PLACE” is superseded by the implemented generation branch. D1.md:4751–4753’s missing-owner claim is superseded by the fixed owner. Neither establishes memory-aware query reconfigure.

**6. Post-merge candidates**

**No functional reconfigure clause is safely deferrable:** ordinary settings and query-only installation reach this owner today. Low-memory refusal/restoration therefore affects existing users.

The **signing-provider-specific attestation** can be separated as release evidence; the underlying requested-restart/successor behavior cannot. Current handoff assigns signed Sandbox proof to **E7’s signed round**, not post-merge (**handoff.md:87, doc-only**). I found no basis for claiming an authorized post-merge deferral.