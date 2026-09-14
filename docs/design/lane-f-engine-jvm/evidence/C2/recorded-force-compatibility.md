# C2 d.3b.2a — selected force and whole-index compatibility, 2026-09-14

## Decision

RecordedRootPlan.Root.force means bypass unchanged-file extraction for that frozen root.
It does not establish whole-generation embedding provenance. Keep recorded admission free
of ECC transitions and remove the legacy submitBatch(force) transition too. Whole-index
legacy recovery and blue/green migration retain compatibility ownership. No extra completion
hook, durable flag, queue-depth gate or broad re-marking is introduced for selected ingestion.

At17d56daf0, WorkerIngestService:521-527 calls onForcedReindexRequested before queue acceptance
or switching refusal. A refused batch can flip compatibility without work. Moving the call
after admission or after the parent's receipts would still be unsafe: an accepted plan can
select one file or exclude other roots, so untouched old-model COMPLETED vectors remain.
EmbeddingCompatibilityController:359-401 certifies from pending==0 and any success evidence,
explicitly ignoring global queue depth. The two-read debounce is not provenance proof.

Reuse these existing owners, with the review corrections below before activation:

- EmbeddingRecoveryOps:48-105 re-marks all unknown-provenance COMPLETED/FAILED parents before
  entering legacy recovery. A selected ingest must not invoke this whole-index effect.
- KnowledgeServer:812-858 starts a new generation for a model mismatch under the configured
  blue_green_migrate policy. Its cutover:2686-2725 checks drained embedding work and the
  new generation's attestation. A selected force cannot substitute for that rebuild.
- ReindexHandler:16-22 explicitly defines core.reindex(force) as incremental unchanged-check
  bypass; core.bulk-reindex/core.rebuild-index owns full migration. Ambiguous reference text
  saying every blocked state waits for "forced reindex" must name those actual remedies.

These references were read against17d56daf0, including the live controller and production
overlay durability tests. Read-only exploration agreed on selected-force scope, but independent
review then refuted the existing recovery seam's visibility and coverage guarantees below.
This resolves the compatibility item raised by recorded-scan-admission.md and supersedes
its provisional parent-completion/confirmed-write candidate. Pending selected writes alone
cannot earn a global stamp. No locked lane decision is reopened.

## Per-item implementation and acceptance

Keep the newly found corrections in separate commits, all required before producer activation:

- **.2a.1 selected-force admission and provenance preservation:** remove the raw trigger,
  narrow its architecture exemption, correct force diagnostics, and preserve known OLD_FP
  while BLOCKED_MISMATCH forbids all embedding writes. Add fingerprintForCommit as a pure
  projection of existing controller state: mismatch returns its known stored fingerprint;
  all other states delegate to fingerprintToStamp. The latter remains the earned-current-model
  attestation gate. REBUILDING still withholds mixed provenance. KnowledgeServer's existing
  late-bound metadata supplier consumes this projection; no new store, flag or IO. This is
  safer than changing fingerprintToStamp's permission meaning or copying old metadata in the
  generic commit layer. Stronger1774 real-index tests exposed three dropped-old-fingerprint
  failures; the test must keep exact old metadata and blocked state across commit/reopen.
- **.2a.2 legacy visibility, coverage and strict certification:** independent review found
  re-marking refreshes only BEFORE writes, then permits REBUILDING while readers can still
  see old pending=0/completed>0. Use the existing commit plus blocking refresh before transition,
  exact updated/notFound accounting, enumeration-cap refusal and a strict post-write remaining
  COMPLETED/FAILED check. Normal and one-shot finalization must use countByFieldOrThrow: the
  current swallowing read can turn IOException into zero. Add real production-seam/on-demand
  visibility tests with no test-side barrier, partial coverage and both count-fault arms.
- **.2a.3 reachable remedy routing:** WorkerSnapshotTap currently ignores reindexRequiredReason
  and advertises core.reindex(force=true) for embedding mismatch/legacy. Route those to the
  existing full-generation rebuild action (legacy can recover automatically; an explicit retry
  must still be a whole-index action), preserve schema-specific behavior, and assert exact
  operation/default arguments by reason. Correct remaining canonical UI remedy descriptions.
  Read-only tracing also found the recovery inverse index and HealthSurface discard default
  arguments: preserve the existing invocation through that projection rather than duplicating
  mappings in the UI. Route rebuild_brake_exhausted to full rebuild, matching the existing
  readiness notice and status remedy; preserve other schema mappings. No owner input is needed.

The checks below apply to the relevant subcut; .2a as a whole stays open until all three pass.

1. Remove only the raw ECC trigger from WorkerIngestService.submitBatch; preserve existing
   ordinary force marking and the recorded immutable claim flag. Narrow the architecture
   trigger allowlist by removing WorkerIngestService and correct its unsafe rationale.
2. Correct controller comments and canonical reason-code remedies; no wire reason changes.
3. Prove real blocked legacy/mismatch controllers with old completed-vector evidence cannot
   be certified by selected recorded or ordinary force admission, including refusal. Use
   real SQLite admission and index metadata/commit persistence where applicable, distinguishing
   simulated document rewrites from actual extraction. Preserve existing whole-index rescue
   and migration-attestation tests. Live extraction/model proof remains at connected producer.
4. Demonstrate the regression/architecture gate fails when the raw trigger is restored;
   restore source byte-for-byte, run focused tests/PMD/format and docs/governance checks.
   Commit/push each correction before d.3b.2b bounded Engine producer changes.

Then d.3b.2b binds the bounded producer and proves actual producer plus delivery-task exit;
the existing flow timeout alone is not proof that its consumer callback exited. d.3b.3
prepares handlers; final C2 integrated/live/installed/hosted reconciliation remains required.

## .2a.1 verification (2026-09-14)

Implemented against17d56daf0 plus this per-item diff, Windows/Temurin25.0.2. Independent
review found the commit projection and production wiring sound after correcting one stale
legacy-recovery comment. Root installed the real-index regression with the SAME controller
wired into admission and the production metadata overlay; admitted primary rewrites are
simulated, not a claim of live extraction or model inference.

| Proof | Result and scope |
|---|---|
|1774 stronger pre-fix regression|12 cases; exactly three mismatch cases failed because commit lost OLD_FP. Assertions were preserved.|
|1775 integrated focused run|100 cases/12 suites, zero failures/errors/skips; all three test tasks executed, six PMD tasks and format passed.|
|1776 raw-trigger negative control|12 cases; six blocked-state regressions and the architecture rule failed; five other guards passed. Both production sources restored byte-for-byte.|
|1777 old-provenance-drop negative control|12 cases; exactly three mismatch OLD_FP assertions failed, three legacy cases and six guards passed. Controller restored byte-for-byte.|
|1779 final restored run|12 cases/two suites, zero failures/errors/skips; test task executed, four requested PMD tasks and format passed/reused for unchanged inputs.|
|1778 documentation/governance|Index/skill regeneration and checks, canonical links, module dependencies, runtime matrix, three gates and store recoverability passed.|

1775 exact command:

```powershell
./gradlew.bat -PtestParallelism=1 :modules:indexer-worker:test --tests *WorkerSelectedForceCompatibilityTest --tests *WorkerRecordedScanAdmissionTest --tests *WorkerIngestServiceTest --tests *InPlaceEmbeddingRebuildRecoveryTest --tests *EmbeddingFingerprintProductionWiringDurabilityTest --tests *EmbeddingFingerprintLegacyUnattestedVectorsMigrationTest --tests *EmbeddingCompatibilityBootOrderingTest --tests *GreenCutoverEmbeddingFpVerifyTest :modules:worker-services:test --tests *WorkerIngestServiceForceReindexWiringTest --tests *JobBatchExtractorForcedPathTest :modules:worker-core:test --tests *EmbeddingCompatibilityControllerTest :modules:worker-services:pmdMain :modules:worker-services:pmdTest :modules:worker-core:pmdMain :modules:worker-core:pmdTest :modules:indexer-worker:pmdMain :modules:indexer-worker:pmdTest spotlessCheck
```

1776/1777 each run `gradlew.bat -PtestParallelism=1 :modules:indexer-worker:test
--tests '*WorkerSelectedForceCompatibilityTest'` under the saved fault-injection drivers.
1779 runs that restored test selection plus worker-services/worker-core/indexer-worker
`pmdMain`, indexer-worker `pmdTest`, and `spotlessCheck`.

Accessible bulky evidence is under this worktree's `tmp/`:1774–1777/1779 logs, counts manifests
and copied XML;1776/1777 negative drivers and original bytes;1778 check outputs. Retain through
lane completion plus30 days (at least2026-10-14). Assertions and failure messages were reread.
Prior scan checkpoint17d56daf0 hosted CI34887604012 passed12/13 jobs; app-ui failed, with exact
failure diagnosis still to reconcile. This cut has no hosted/live claim yet.

.2a.1 local acceptance is satisfied. .2a.2 visibility/coverage/strict-count corrections and
.2a.3 actual recovery routing remain mandatory next, followed by .2b producer binding and
.3 prepared handlers. C2 remains open and merge stays at F.

## .2a.2 implementation details (2026-09-14)

Use the existing recovery entry point and commit/refresh operations; no new persistent marker,
writer or transition lock. Count parents strictly, re-mark exact batches, commit with the
existing VDU_RECOVERY attribution, refresh, then strictly require zero COMPLETED and FAILED
parents before enabling REBUILDING. A count, write, commit or refresh failure leaves the
controller blocked and logs the existing best-effort refusal. Enumeration at its bounded cap
refuses instead of treating a partial list as complete. Finalization uses the existing strict
count operation; any read failure resets the consecutive-zero streak, including shutdown.

Strengthen the real-index production-rescue regression by stopping background refresh,
disabling foreground refresh and removing its test-side post-rescue barrier. Probe both
normal and shutdown finalization. Mocked coverage/IO regressions complement, not substitute
for, that real visibility proof. The local proof below covers this subcut; final lane live/installed/hosted reconciliation remains owed.

## .2a.2 verification (2026-09-14)

Against40a9a14b9 plus this subcut, Windows/Temurin25.0.2. Independent first review found no
material defect in the safety chain or the strengthened tests. Final independent evidence reread passed, including exact source-byte restoration and reused-test attribution.
1780 did not compile because a new fixture returned long to IntSupplier; corrected the fixture,
without changing the acceptance assertions. That failed command is retained, not counted as proof.

| Proof | Result |
|---|---|
|1781 focused|36 cases/five suites executed, zero failures/errors/skips; PMD/format pass.|
|1783 barrier removal|14 cases/three suites, five expected failures: commit/refresh unit arms and both real-index finalization variants. The real reader still sees old COMPLETED parents, so strict coverage refuses; its XML includes that exact cause. Nine positive controls pass.|
|1784 coverage removal|11 cases, seven expected failures: four inexact batch results, unsafe cap acceptance, omitted COMPLETED coverage and residual FAILED coverage. Four positive controls pass. Cap mutant returns an empty truncated list rather than allocating millions of duplicate ids.|
|1785 swallowing count|13 cases, exactly three strict-finalizer regressions fail by incorrectly certifying; ten other lifecycle cases pass.|
|1786 missing streak reset|One case, expected failure: the first zero after a reader fault incorrectly certifies.|
|1787 final restored|131 cases/21 suites newly executed across indexer-worker and worker-services, zero failures/errors/skips.19 unchanged worker-core cases/one suite are UP-TO-DATE reuse:150 represented, not150 new. Six PMD tasks and format pass.|

Every fault-injection driver restores and verifies source bytes in finally; root reread the
captured assertion messages and the real-index cause.1783-initial stopped at the worker failure;
1783 repeated with --continue to run BOTH module selections, retaining initial evidence separately.
Existing IndexingLoopTest stubs now explicitly supply strict counts instead of passing through a
mock's default zero. Live embeddings are simulated in the persistence regression, as before.

1787 command:

```powershell
gradlew.bat -PtestParallelism=1 :modules:worker-services:test --tests *EmbeddingRecoveryOpsTest --tests *EmbeddingProviderLifecycleTest --tests *IndexingLoopTest :modules:worker-core:test --tests *EmbeddingCompatibilityControllerTest :modules:indexer-worker:test --tests *InPlaceEmbeddingRebuildRecoveryTest --tests *EmbeddingFingerprintLegacyUnattestedVectorsMigrationTest --tests *EmbeddingFingerprintProductionWiringDurabilityTest --tests *EmbeddingCompatibilityBootOrderingTest --tests *WorkerSelectedForceCompatibilityTest --tests *GreenCutoverEmbeddingFpVerifyTest :modules:worker-services:pmdMain :modules:worker-services:pmdTest :modules:worker-core:pmdMain :modules:worker-core:pmdTest :modules:indexer-worker:pmdMain :modules:indexer-worker:pmdTest spotlessCheck
```

Fault-injection commands are in `tmp/recovery-negative.py`; each label1783–1786 selects its
specified regression. Logs, copied XML and counts manifests are `tmp/<label>.txt`,
`tmp/<label>-xml/` and `tmp/<label>-counts.json`; original bytes are adjacent. Retention is the
same as .2a.1.1788 records documentation links/index, three governance gates and store checks.
Full-stage/live/installed/final hosted proof remains required; .2a.3 reachable remedy routing
is next, before producer activation. Hosted scan17d56daf0 failure is now diagnosed from downloaded
XML: only RecordedIngestionCoordinator.bindProducer is unreferenced, on all three attempts
(`tmp/1781-prior-hosted-xml`). No exemption or early merge is authorized by this result.
