---
schema: test-intent.v1
task: t-20261030-ingfix
author-role: builder
author-session: builder-ingfix-wp1-closing-repairs-20261031
---

# Test-intent entries

Coverage includes the seven previously modified test files and the closing blank-chunk regression in this branch's diff from merge-base
`9eb36cc7923b530624f7ced8ea0bc678f0011b31` to candidate
`a48745a010` plus the uncommitted evidence-selection repairs, including the earlier candidates
`ca3f78b4066b143038561ee746a088a66ae9b82f` and `c43bedcbb26a0a0609e3d3fb931737b459003dd1`
and repair scenarios R1-R6 and the closing S14 repair. The R4 repair is committed in `c43bedcbb2`; the R5 repair is
committed in `a48745a010`.
The three isolated new test files previously reported exempt remain outside these entries.
No changes from main after the merge-base are covered.

All listed Defect pin entries require generated evidence at
`gates/test-intent/evidence/ingfix-wp1.json` and assertion fail-before evidence for every
listed `newChecks` id using `--before <ref>` against its unrepaired before-state.
For the repair checks (the deferred-preservation scheduler check, the combined and real-index
preservation-failure checks, and the fallback-outage check), `ca3f78b406` is the pre-repair
candidate; the seven earlier checks require a before-state preceding their original repairs.
The R4 scheduler check requires `109b53d9c4` as its before-state.
The R5 scheduler check requires `c43bedcbb2` as its before-state; both leading-population variants must execute.
Both variants of each parameterized scheduler check and all eight fallback-outage variants
must execute. Fixture-only changes retain their existing preservation checks and are described
explicitly; they are not claims of newly failing-before behavior.

The original changeset-only update ran no builds, product tests or evidence generation.
It reported `test-intent/base-unresolved` and `spawnSync git EPERM`. Per the R4 brief,
the orchestrator runs gate validation after the builder returns; independent acceptance
remains outstanding and no acceptance record is written here.

R4 builder observations: the two changed checks fail against `109b53d9c4` in a shared clone
under `$TMPDIR/before`, using that clone's build folders. The multi-cycle check fails because
`healthy-1-0` remains `PENDING` instead of `COMPLETED`; the singleton withholding check
observes only one preservation call instead of the required slice plus individual retry.
The same two checks pass with the repair. The full `io.justsearch.indexerworker.loop.*`
worker-services suite passes (300 tests, 0 failures, 0 errors, 8 skipped).
`node scripts/dev/java-prepush.mjs` completes
compilation and PMD but fails the unrelated UI integration check
`RuntimeReloadProducersHttpIntegrationTest#receiptForARootWhoseVolumeReturnsIsAppliedWithoutAWatchCall`:
`precondition: the person's add recorded the root's volume ==> expected: not <null>`.
Running that exact UI check in the `109b53d9c4` shared clone produces the identical failure.
All Gradle calls use `js-gradle-slot`, `-PskipWebBuild=true` and a headless environment.
The initial before/after attempts found an ambiguous Mockito overload in the new fixture;
that was corrected before the assertion-failure and success runs reported above.

R5 builder observations: both `wholeBatchOfFailingPreservationDoesNotStarveFollowingHealthyParents`
variants fail against `c43bedcbb2` in a shared clone under `$TMPDIR/before`, using that
clone's build folders: with zero leading healthy parents, `healthy-64` remains `PENDING`;
with 64 leading healthy parents, `healthy-128` remains `PENDING`. Both expected `COMPLETED`.
With the repair, both variants and the unchanged R4 regression pass. The fresh
`io.justsearch.indexerworker.loop.*` run passes: 546 tests, 0 failures, 0 errors, 8 skipped.
The R5 fixture and the production change use no GPU or shared dev stack; checks use the
shared Gradle slots and cache. `node scripts/dev/java-prepush.mjs` passes compilation
and PMD, then exits 1 because `RuntimeReloadProducersHttpIntegrationTest#receiptForARootWhoseVolumeReturnsIsAppliedWithoutAWatchCall`
fails with `precondition: the person's add recorded the root's volume ==> expected: not <null>`.
The full UI integration task reports 74 tests, 1 failed, 5 skipped. Running that exact
UI check against `c43bedcbb2` in the shared clone also exits 1 with the identical assertion,
establishing that failure before this repair. Independent gate validation remains for the
orchestrator; no acceptance record is written here.

Evidence-selection repair observations (2026-10-31): parameterized checks now retain the
method display name in every invocation, and listed single-case checks use their method names.
The two checks whose first failures are Mockito verifications wrap those verifications in
`assertDoesNotThrow`, preserving their expectations while exposing JUnit assertion failures.
A fresh shared clone at `9eb36cc7923b530624f7ced8ea0bc678f0011b31`, overlaid with all seven
candidate test files, compiles without a compatibility patch. Its filtered worker-services run
executes all 13 `newChecks` ids (23 cases); every case fails with `AssertionFailedError`.
The evidence runner's XML selector and failure classifier confirm all 13 assertion fail-before
outcomes. The same headless, filtered `js-gradle-slot ./gradlew :modules:worker-services:test`
run with `--rerun -PskipWebBuild=true --console=plain` passes all 23 cases on the candidate.
The evidence runner's XML selector confirms that all 13 ids match, including every parameterized
variant, with equal case counts before and after. `git diff --check` passes. These runs touch
only the shared Gradle slots/cache and each checkout's own build/test resources; no GPU or shared
dev stack is used. The original evidence file remains stale until the orchestrator regenerates
it; use `--before 9eb36cc7923b530624f7ced8ea0bc678f0011b31` without `--before-patch` for WP1.

Closing repair scope (2026-10-31): R6 narrows P1 and R1/R2/R4/R5 to cycles with a
configured and loaded sparse encoder; an available encoder in transient-failure backoff
still withholds updates. With no encoder loaded, the existing writer reset lane publishes
the other stage. The individual embedding publication lane is unchanged.
The new R6 and blank-chunk S14 checks use `bf21114de8` as their before-state; the three
rewritten preservation oracles use `9eb36cc79`. The revised retired-encoder variant of
`deferredPreservationNeverFallsThroughToIndividualEmbeddingIncludingBackoff` is an R6
expectation change; its available-encoder variant retains R2/S9. No method-level display
names are added, and the new parameterized check includes the method display name and
arguments. The orchestrator will regenerate the evidence file; it is not changed here.

Closing before-state observations: shared clones under `$TMPDIR/before-closing` at
`bf21114de8` and `$TMPDIR/before-original` at `9eb36cc79` use their own build folders.
The filtered bf21114de8 run executes 10 cases: the new absent-encoder R6 check fails
with embedding PENDING instead of COMPLETED; all six blank-chunk outage variants fail
with retry 1 instead of 0, and the two ready-provider controls pass. The unchanged
`BackfillProbeCommitPersistenceTest#abruptExitBeforeFinalCommitRestoresLastCommittedArtifactsAndRetries`
fails with five writing batches instead of six. The filtered 9eb36cc79 run executes the
three rewritten preservation checks (four cases including both leading-population variants);
all four fail with AssertionFailedError on publication or embedding-status assertions.
No exact-call-count failure is needed for those rewritten checks.

The revised retired-encoder backoff variant fails on bf21114de8 with embedding still
PENDING; its available-encoder control passes. The unchanged abrupt-exit persistence
check passes on 9eb36cc79. The first known-good run exposed an over-specific absent-counter
assertion in the new R6 regression: the baseline stores retry 0. The fixture now seeds retry
0 and requires it to remain 0, retaining the publication, reset and postings assertions.

Closing candidate observations: the headless, slot-wrapped worker-services test run with
`--tests 'io.justsearch.indexerworker.loop.*'` and
`--tests 'io.justsearch.adapters.lucene.runtime.BackfillProbeCommitPersistenceTest'`
passes: 563 tests, 0 failures, 0 errors, 8 skipped. All eight blank-chunk readiness variants,
the R6 real-index regression with retry seeded at 0, the revised backoff variants, the
three rewritten preservation oracles, and all eight unchanged persistence tests pass.
The loop checks use their own temporary Lucene indexes and only the shared Gradle slots/cache;
no GPU or shared dev stack is used. The final bf21114de8 rerun, with the R6 retry seed fixed at 0, again executes 10 cases: 8 intended assertion failures and both ready-provider controls passing. The final 9eb36cc79 known-good run passes both the new R6 regression and the unchanged abrupt-exit persistence check. `env -u DISPLAY -u WAYLAND_DISPLAY node scripts/dev/java-prepush.mjs` completes compilation and PMD, then exits 1: UI integration reports 74 tests, 2 failures, 5 skipped. `RuntimeReloadProducersHttpIntegrationTest#receiptForARootWhoseVolumeReturnsIsAppliedWithoutAWatchCall` fails with `precondition: the person's add recorded the root's volume ==> expected: not <null>`; `IndexingRulesBootstrapIntegrationTest#fullMainHoldsFreshBatteryAndTimesOutUnknownWithDurablyQueuedWork` fails with `fresh BATTERY before timeout starts with the persisted queue held ==> expected: <true> but was: <false>`. The headless, slot-wrapped run of those two exact UI checks against bf21114de8 in `$TMPDIR/before-closing` exits 1: the root-volume check fails with the identical missing-volume precondition, while the battery bootstrap check passes. The battery discrepancy between the candidate's full UI suite and the isolated before-state comparison remains unresolved; the builder returns it to the orchestrator under the closing assignment's scope. No UI implementation or test is changed. The evidence file remains for the orchestrator to regenerate, and no acceptance record is written here.

Closing narrowing (2026-10-31, on de19043fd2): the earlier withholding/isolation
expectations and observations above are historical and superseded for non-systemic
preservation failures by R1/R4/R5 narrowed. A failed preservation slice publishes every
parent's earned embedding/NER outcome through the baseline reset lane, SPLADE PENDING
with baseline retry accounting, including healthy peers in that failed slice. Successful
preservation, lazy selection, systemic-failure backoff/no-charge/no-individual-fallback,
and R3/R6/R7 remain unchanged. Individual preservation retries and preservation-failure
per-cycle exclusion/continuation machinery are removed. Each changed oracle is named
and justified in its current entry below. The new slow-failure check reproduces the
verifier's 150 ms sparse-call case in both leading-population variants. Checks passing
9eb36cc79 are preservation checks in entry prose rather than newChecks. The evidence
file remains unchanged for the orchestrator to regenerate; no acceptance is recorded.

Narrowing comparison observations: the shared clone at de19043fd2 executes both
slowFailedPreservationDoesNotStarveFollowingHealthyParents variants; both fail with
AssertionFailedError because healthy-64 / healthy-128 remain PENDING instead of
COMPLETED. The shared clone at 9eb36cc79 executes seven selected cases: both slow
variants and the rewritten unit and real-index reset checks pass. The R4 check fails
because the baseline charges an unattempted later slice and publishes no regenerated
postings there; both whole-batch R5 variants fail because baseline retries the reset
parents in the same cycle until SPLADE is FAILED rather than retaining retry 1/PENDING.
R4 and whole-batch R5 therefore remain newChecks; the slow regression and the two
reset checks are preservation checks described in prose. Each clone has its own build
folder. One initial filtered candidate launch also completed successfully; its output
briefly overlapped the comparison log, so the comparison results above were checked
against each clone's own JUnit XML and the Gradle slot completion records.

Narrowing candidate loop run: the slot-wrapped, headless worker-services test task
with `--tests 'io.justsearch.indexerworker.loop.*'` passes 557 cases with 0 failures,
0 errors and 8 existing skips. The combined Gradle invocation exits 1 solely because
the separately selected adapters-lucene test task cannot find BackfillProbeCommitPersistenceTest;
that adapter-package test actually lives in worker-services. Its corrected owning-module
run is separate. The changed preservation tests all execute without skips in the loop run.

Narrowing rewritten-oracle before-state run: all five selected cases for the four
rewritten checks fail with AssertionFailedError on de19043fd2. The unit check finds
no published embedding; the real-index check finds the old posting still present;
the R4 check and both whole-batch R5 variants find embedding PENDING instead of
COMPLETED. All pass in the candidate loop run. Each new/rewritten check therefore
has assertion fail-before evidence on the actual starting candidate.

Narrowing pre-push observation: `env -u DISPLAY -u WAYLAND_DISPLAY node
scripts/dev/java-prepush.mjs --unreferenced` exits 1 after compilation and PMD pass.
All three selected UnreferencedCodeTest cases pass. The UI integration task runs
74 cases, with 1 failure and 5 skips: RuntimeReloadProducersHttpIntegrationTest#receiptForARootWhoseVolumeReturnsIsAppliedWithoutAWatchCall
fails with `precondition: the person's add recorded the root's volume ==> expected:
not <null>`. No other failure is reported, and no UI code or expectation is changed.

Final narrowing checks: the corrected headless, slot-wrapped worker-services task
with `--tests 'io.justsearch.adapters.lucene.runtime.BackfillProbeCommitPersistenceTest'`
passes all 8 cases, with no failures, errors or skips. The exact UI root-volume-return
check on the de19043fd2 shared clone fails with the identical missing-volume
precondition (`expected: not <null>`), establishing the pre-push blocker before this
narrowing. Compilation, PMD and the unreferenced-code check pass; the full pre-push
command remains nonzero because of that unchanged UI failure. `git diff --check`
passes. `.agents/preflight` is absent. All Gradle calls were headless, slot-wrapped
and used `-PskipWebBuild=true`; comparisons used their own build folders. Shared
resources touched: Gradle slots and cache only. No GPU or shared dev stack was used.
The implementation and tests are left uncommitted; the evidence file is unchanged
for the orchestrator, and no acceptance record is added.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java"
      ],
      "class": "Defect pin",
      "behaviour": [
        "costlyCompletedSpladePreservationCannotStarveNerOrDiscardEarnedVectors: four fake-clock cycles each publish new NER and embedding completions despite expensive completed-parent SPLADE preservation, and emit no ZERO stage advancement warning (S1/S2). This check covers the fake-clock scenario, not the GPU soak corpus.",
        "stageOnlyDocumentsBeyondEmbeddingHeadAdvanceWithinTwoCycles: a SPLADE-only and a NER-only document behind 200 embedding-pending documents complete their applicable stages within two cycles (S12).",
        "systemicSpladeFailureBacksOffBothPathsWithoutDocumentRetries: parameterized arena-OOM and unavailable-encoder failures leave retries untouched, suppress calls in both paths during backoff, and complete after recovery with the consecutive-failure count reset (S9).",
        "deferredPreservationNeverFallsThroughToIndividualEmbeddingIncludingBackoff: both variants initially withhold the parent after a systemic failure, leaving embedding PENDING and publishing no update. With the encoder still present, the following backoff cycle also withholds it and recovery later publishes preserved postings (R2/P1/S9). With the encoder retired, that following cycle publishes the embedding through the combined path (R6); the real-index absent-encoder check separately verifies the baseline SPLADE reset lane. This intentionally supersedes the earlier R2 expectation of withholding even when no encoder is loaded.",
        "The existing budgetTripWithNoProgress_stillLogsTheWarn assertion is retained. Its publication stub now reports one accepted update and the remaining updates rejected, so the repaired individual embedding fallback cannot give this zero-published-stage diagnostic fixture completion credit (S8/S10). This is an explicit fault-injection change, not a new expected warning. The shared scheduler fixture supplies an optional sparse encoder for the added scenarios.",
        "persistentlyFailingPreservationDoesNotPoisonHealthyDocumentsAcrossCycles: the failing preservation slice, healthy peers included, publishes earned embedding/NER outcomes with SPLADE PENDING and retry 1 through the baseline reset lane. Across three fresh rounds of earned outcomes, other slices and later batches publish regenerated postings, with no individual preservation retry. This replaces withholding only the poison parent and preserving all healthy peers individually (R4 narrowed). The fake writer models the reset lane locally; the real-index regression checks its actual postings behavior.",
        "wholeBatchOfFailingPreservationDoesNotStarveFollowingHealthyParents: both leading-population variants retain the per-cycle publication exclusion behavior alongside the narrowed reset expectation. Both zero- and 64-leading-healthy variants publish all earned embedding/NER outcomes in one cycle. Healthy batches retain regenerated postings; the failing batch resets SPLADE to PENDING with baseline retry 1. No individual preservation encode or individual embedding write is allowed (R5 narrowed).",
        "slowFailedPreservationDoesNotStarveFollowingHealthyParents: preservation check against 9eb36cc79, excluded from newChecks. Equivalent to the verifier slow-preservation probe: each sparse call advances the fake clock 150 ms, with 64 failing parents followed by 64 healthy parents and zero or 64 leading healthy parents. Healthy parents publish earned embedding/NER outcomes in the same cycle with preserved postings, and the cycle stays within budget without ZERO stage advancement. The separate whole-batch check covers failed-slice reset and retry outcomes. Requires assertion failure on de19043fd2 and success after narrowing and on 9eb36cc79 (R5/S1)."
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S1 (K1) A library of 100 long documents (soak corpus slice, about 91 chunks each, AI on, GPU) keeps advancing after the first cycles: every combined cycle publishes at least one stage outcome; NER-completed and chunk-embedding-completed counts rise; no \"ZERO stage advancement\" warning after the initial cycles. Also as a fake-clock test: head of SPLADE-COMPLETED, embedding- and NER-pending long parents whose SPLADE encode cost exceeds the remaining cycle budget still advances each cycle.\n\nS2 (K1) The combined pass never encodes SPLADE for an already-COMPLETED parent and then discards it: preservation re-encoding happens only for parents that receive another stage's outcome in the same batch, and every such encode is written.\n\nS9 (D62) A systemic SPLADE failure (GPU arena out-of-memory, encoder unavailable) charges no per-document retries and starts a backoff that both the combined and the interleaved SPLADE paths respect; after the condition clears, the affected documents complete.\n\nS12 (K1, challenger 2) In a pending population larger than one batch, documents that need only SPLADE or only NER and sit beyond the embedding-pending head still advance within bounded cycles.\n\nR2 (P1, S9) When the combined pass defers a parent's update (for example during a systemic SPLADE failure and its backoff), the same idle cycle does not fall back to individual embedding writes that would replace the parent without its SPLADE postings.\n\nP1 A parent's completed SPLADE postings survive another stage's write in the combined pass (no silent drop, no reset to PENDING caused by that write).\n\nR1/R4/R5 narrowed: a non-systemic preservation failure publishes the parent's earned outcome through the baseline reset lane (SPLADE back to PENDING) instead of withholding it.",
          "label": "agent choice"
        },
        "reproduction": "Use 100 fake-clock long parents with completed SPLADE and pending embedding/NER; preservation encoding consumes three seconds per parent and windowed dense work consumes the cycle budget. Repeat four cycles and observe whether both completed counts advance and the zero-advancement warning stays absent. Separately place SPLADE-only and NER-only documents behind 200 embedding-pending parents and run two cycles. Finally inject either a GPU arena allocation failure or SessionTemporarilyUnavailableException during SPLADE, invoke both combined and interleaved paths during backoff, then clear the failure and retry. The assertions require bounded advancement, no per-document retry charge for systemic failures, shared backoff and recovery. For R2/R6, seed one embedding-pending, SPLADE-COMPLETED parent with NER complete; make preservation throw SessionTemporarilyUnavailableException and assert no publication in the first cycle. In the next backoff cycle, require continued withholding when the encoder is present, but completed embedding publication when it is retired. Restore the encoder, clear backoff and run another cycle; embedding and SPLADE must be COMPLETED. Both encoder-retirement variants execute; the real-index R6 check verifies SPLADE resets when no encoder is available. For R4 narrowed, use three fake-clock cycles with fresh earned work for a poison parent and 160 healthy parents, and a 128-parent combined batch spanning two preservation slices. A non-systemic failure resets the entire failing slice with earned outcomes and retry 1; other slices/batches preserve regenerated postings. For R5 narrowed, use 64 failing parents followed by 64 healthy parents with zero or 64 leading healthy parents. Run both 1 ms and 150 ms sparse-call variants and require every parent to publish earned stages in one cycle, with baseline reset for the failing slice and preserved postings for healthy batches. No individual preservation retries, ZERO stage advancement warning or individual embedding writes. The 150 ms variants reproduce the slow failure on de19043fd2; the baseline 9eb36cc79 is a preservation control."
      },
      "reproduction": "Use 100 fake-clock long parents with completed SPLADE and pending embedding/NER; preservation encoding consumes three seconds per parent and windowed dense work consumes the cycle budget. Repeat four cycles and observe whether both completed counts advance and the zero-advancement warning stays absent. Separately place SPLADE-only and NER-only documents behind 200 embedding-pending parents and run two cycles. Finally inject either a GPU arena allocation failure or SessionTemporarilyUnavailableException during SPLADE, invoke both combined and interleaved paths during backoff, then clear the failure and retry. The assertions require bounded advancement, no per-document retry charge for systemic failures, shared backoff and recovery. For R2/R6, seed one embedding-pending, SPLADE-COMPLETED parent with NER complete; make preservation throw SessionTemporarilyUnavailableException and assert no publication in the first cycle. In the next backoff cycle, require continued withholding when the encoder is present, but completed embedding publication when it is retired. Restore the encoder, clear backoff and run another cycle; embedding and SPLADE must be COMPLETED. Both encoder-retirement variants execute; the real-index R6 check verifies SPLADE resets when no encoder is available. For R4 narrowed, use three fake-clock cycles with fresh earned work for a poison parent and 160 healthy parents, and a 128-parent combined batch spanning two preservation slices. A non-systemic failure resets the entire failing slice with earned outcomes and retry 1; other slices/batches preserve regenerated postings. For R5 narrowed, use 64 failing parents followed by 64 healthy parents with zero or 64 leading healthy parents. Run both 1 ms and 150 ms sparse-call variants and require every parent to publish earned stages in one cycle, with baseline reset for the failing slice and preserved postings for healthy batches. No individual preservation retries, ZERO stage advancement warning or individual embedding writes. The 150 ms variants reproduce the slow failure on de19043fd2; the baseline 9eb36cc79 is a preservation control.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S1 (K1) A library of 100 long documents (soak corpus slice, about 91 chunks each, AI on, GPU) keeps advancing after the first cycles: every combined cycle publishes at least one stage outcome; NER-completed and chunk-embedding-completed counts rise; no \"ZERO stage advancement\" warning after the initial cycles. Also as a fake-clock test: head of SPLADE-COMPLETED, embedding- and NER-pending long parents whose SPLADE encode cost exceeds the remaining cycle budget still advances each cycle.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S2 (K1) The combined pass never encodes SPLADE for an already-COMPLETED parent and then discards it: preservation re-encoding happens only for parents that receive another stage's outcome in the same batch, and every such encode is written.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S9 (D62) A systemic SPLADE failure (GPU arena out-of-memory, encoder unavailable) charges no per-document retries and starts a backoff that both the combined and the interleaved SPLADE paths respect; after the condition clears, the affected documents complete.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S12 (K1, challenger 2) In a pending population larger than one batch, documents that need only SPLADE or only NER and sit beyond the embedding-pending head still advance within bounded cycles.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S8 (D61) A null or size-mismatched combined embedding slice result is isolated per document: healthy documents in that slice complete, a persistently failing document escalates through its retry count to FAILED; the slice is not repeated unchanged cycle after cycle.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S10 (D125) Enrichment \"completed\" metrics count only documents whose update was published; encodes withheld, rejected or lost to a failed write are not counted, and the interrupted-batch log line no longer claims everything enriched was written when it was not.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "R2 (P1, S9) When the combined pass defers a parent's update (for example during a systemic SPLADE failure and its backoff), the same idle cycle does not fall back to individual embedding writes that would replace the parent without its SPLADE postings.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P1 A parent's completed SPLADE postings survive another stage's write in the combined pass (no silent drop, no reset to PENDING caused by that write).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "R6 (P1 narrowed) Without a configured and loaded sparse encoder, a SPLADE-COMPLETED parent's other-stage update is published through the baseline reset lane (SPLADE back to PENDING), never withheld.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "R1/R4/R5 narrowed: a non-systemic preservation failure publishes the parent's earned outcome through the baseline reset lane (SPLADE back to PENDING) instead of withholding it.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp1.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java#costlyCompletedSpladePreservationCannotStarveNerOrDiscardEarnedVectors",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java#stageOnlyDocumentsBeyondEmbeddingHeadAdvanceWithinTwoCycles",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java#systemicSpladeFailureBacksOffBothPathsWithoutDocumentRetries",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java#deferredPreservationNeverFallsThroughToIndividualEmbeddingIncludingBackoff",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java#persistentlyFailingPreservationDoesNotPoisonHealthyDocumentsAcrossCycles",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java#wholeBatchOfFailingPreservationDoesNotStarveFollowingHealthyParents"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerTightLoopTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Only advancingButNeverDrainingPopulation_budgetReturnsControl changes its synthetic query/content encoding: each completed batch produces fresh document identifiers and matching content instead of continually returning the same identifiers. The precondition remains an inexhaustible stream of successfully advancing pending documents. The successful SPLADE result, pending-stage metadata, 100 ms modeled write cost, watchdog, at-least-4000-ms budget assertion and more-than-ten-writes assertion are unchanged. Refreshing fixture identities keeps prior publication suppression from ending this budget-only containment check early. No production state, test selection or other test oracle changes.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P3 Pending ingest, GPU yield, indexing hold and root removal still stop a combined batch at its sub-batch checkpoints, and the cycle still returns to the job-queue poll.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentRmwIntegrationTest.java"
      ],
      "class": "Defect pin",
      "behaviour": [
        "embedBatchSizeMismatch_fallsBackToIndividualAttempts replaces embedBatchSizeMismatch_isNotProgress: a malformed two-document embedding result now invokes individual attempts, persists retry 1 for each unsuccessful document, leaves them PENDING and reports the published retry advancement (S8). The former no-write/no-retry expectation is intentionally superseded by S8.",
        "spladeBatchPartialResult_escalatesOnlyTheAttemptedSlice replaces spladeBatchPartialWrite_doesNotClobberAlreadyCompletedDocOnEscalation: a one-map result for two documents publishes no positional success and charges each attempted document retry 1 (S13). The former expectation that the first document becomes COMPLETED with retry 0 is intentionally superseded by S13.",
        "completedParentSplade_deadlineAfterEmbedding_preservesAndPublishes replaces completedParentSplade_stopBeforeSplade_preservesExistingRmwInputs: an earned embedding and required completed-parent SPLADE preservation publish together despite the deadline-inclusive stop flag (S1/S2/P1). The former aborted/no-write expectation is intentionally superseded; the context hard-stop supplier remains false, so this check does not authorize ignoring pending ingest, GPU yield, indexing hold or root removal (P3).",
        "completedParentSplade_preservationFollowsNerOutcome replaces completedParentSplade_stopBeforeNer_requiresNerOutcome: NER runs before preservation encoding and publishes COMPLETED_EMPTY with the preservation map (S1/S2/P1). The former no-NER/no-write expectation is intentionally superseded by the scenario requiring preservation only after another stage earns an outcome.",
        "partialWriteRetainsCompletedWindowsUntilTheirActualRetryWrite keeps all assertions and reuses the same WindowedEmbedProgress across a fresh retry context. The new context clears the prior batch publication receipts; the unpublished second document still reuses its completed window without a second encode and is credited only after actual publication (S10/P9/P12).",
        "completedWindowWithheld_thenRetryUsesCachedPooledVector keeps all assertions. Its first preservation attempt now throws SessionTemporarilyUnavailableException and the next succeeds; failure to obtain preservation, rather than an expired soft deadline alone, withholds the earned window vector. The cached vector remains available until successful publication, with no second dense encode (S1/S2/S9/P12). This is an explicit fault-injection change; the withheld-then-reused expected outcome stays unchanged.",
        "completedParentSplade_encoderFailure_publishesEarnedOutcomeThroughResetLane replaces completedParentSplade_encoderFailure_withholdsEntireUpdate: preservation check against 9eb36cc79, excluded from newChecks. A non-systemic preservation exception now publishes the earned embedding with retry 1 and no sparse artifact supplied; the local fake writer models SPLADE PENDING and removed postings. Only one preservation encode is allowed, with no individual retry. This intentionally reverses withholding, unchanged original postings and absent retry expectations because R1/R4/R5 are narrowed. The real-index regression proves the writer reset behavior.",
        "failedPreservationPublishesEmbeddingAndResetsSplade replaces failedPreservationWithholdsEntireUpdateAndKeepsOldPostings: preservation check against 9eb36cc79, excluded from newChecks. The same real Lucene seed, successful embedding, non-systemic preservation failure and refresh now require embedding COMPLETED, SPLADE PENDING, retry 1 and removed old postings. The seed-posting assertion remains. Withholding the embedding and retaining original postings/no retry is intentionally superseded by the narrowed baseline reset decision (R1). The absent-encoder R6 check and all other checks remain unchanged.",
        "absentSpladeEncoderPublishesEmbeddingAndResetsCompletedSplade (preservation check, not a defect pin: it keeps the before-state behaviour, passes on 9eb36cc792 and after, and is run as a supplementary --check in the evidence): seed a real Lucene parent with embedding PENDING, SPLADE COMPLETED and searchable old sparse postings, then run the combined pass with ready embedding and no sparse encoder. Seed SPLADE retry count 0 and require embedding COMPLETED, SPLADE PENDING, retry count still 0 and removed old postings through the unchanged writer reset lane (R6). This new check must fail on bf21114de8. Existing real-index checks remain unchanged."
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S1 (K1) A library of 100 long documents (soak corpus slice, about 91 chunks each, AI on, GPU) keeps advancing after the first cycles: every combined cycle publishes at least one stage outcome; NER-completed and chunk-embedding-completed counts rise; no \"ZERO stage advancement\" warning after the initial cycles. Also as a fake-clock test: head of SPLADE-COMPLETED, embedding- and NER-pending long parents whose SPLADE encode cost exceeds the remaining cycle budget still advances each cycle.\n\nS2 (K1) The combined pass never encodes SPLADE for an already-COMPLETED parent and then discards it: preservation re-encoding happens only for parents that receive another stage's outcome in the same batch, and every such encode is written.\n\nS8 (D61) A null or size-mismatched combined embedding slice result is isolated per document: healthy documents in that slice complete, a persistently failing document escalates through its retry count to FAILED; the slice is not repeated unchanged cycle after cycle.\n\nS13 (challenger 13) A null, short or oversized SPLADE result list never assigns results to the wrong document and never charges unattempted documents.\n\nP1 A parent's completed SPLADE postings survive another stage's write in the combined pass (no silent drop, no reset to PENDING caused by that write).\n\nR1/R4/R5 narrowed: a non-systemic preservation failure publishes the parent's earned outcome through the baseline reset lane (SPLADE back to PENDING) instead of withholding it.",
          "label": "agent choice"
        },
        "reproduction": "For S8, return one embedding vector for two pending documents while individual inference returns unusable results: both attempted documents must receive retry 1 and remain PENDING, instead of silently repeating an unchanged slice. For S13, return one SPLADE map for two pending documents: neither positional result may be published; both attempted documents remain PENDING with retry 1. For S1/S2/P1, set only the deadline-inclusive stop supplier after a successful embedding result, with hard stop false: required completed-parent SPLADE preservation and the earned embedding must publish. In the NER-only variant the preservation encoder sets that stop supplier; NER must already have run and its COMPLETED_EMPTY outcome and preservation map must publish together. For R1 narrowed, seed a completed-SPLADE, embedding-pending parent, return its embedding and throw a non-systemic exception from preservation. Require publication with embedding COMPLETED, SPLADE PENDING, retry 1 and no old sparse artifact; no individual re-encode. This retains the 9eb36cc79 baseline behavior and is a preservation check, not a newChecks defect pin. Real-index preservation checks: Index a real Lucene parent with embedding PENDING, SPLADE COMPLETED and a searchable old sparse posting. Return its embedding but throw a non-systemic exception from preservation. Process and refresh; require embedding COMPLETED, SPLADE PENDING, retry 1 and no old posting, through the baseline reset lane (R1 narrowed). This is a preservation check on 9eb36cc79. absentSpladeEncoderPublishesEmbeddingAndResetsCompletedSplade (preservation check, not a defect pin: it keeps the before-state behaviour, passes on 9eb36cc792 and after, and is run as a supplementary --check in the evidence): seed a real Lucene parent with embedding PENDING, SPLADE COMPLETED and searchable old sparse postings, then run the combined pass with ready embedding and no sparse encoder. Seed SPLADE retry count 0 and require embedding COMPLETED, SPLADE PENDING, retry count still 0 and removed old postings through the unchanged writer reset lane (R6). This new check must fail on bf21114de8. Existing real-index checks remain unchanged."
      },
      "reproduction": "For S8, return one embedding vector for two pending documents while individual inference returns unusable results: both attempted documents must receive retry 1 and remain PENDING, instead of silently repeating an unchanged slice. For S13, return one SPLADE map for two pending documents: neither positional result may be published; both attempted documents remain PENDING with retry 1. For S1/S2/P1, set only the deadline-inclusive stop supplier after a successful embedding result, with hard stop false: required completed-parent SPLADE preservation and the earned embedding must publish. In the NER-only variant the preservation encoder sets that stop supplier; NER must already have run and its COMPLETED_EMPTY outcome and preservation map must publish together. For R1 narrowed, seed a completed-SPLADE, embedding-pending parent, return its embedding and throw a non-systemic exception from preservation. Require publication with embedding COMPLETED, SPLADE PENDING, retry 1 and no old sparse artifact; no individual re-encode. This retains the 9eb36cc79 baseline behavior and is a preservation check, not a newChecks defect pin. Real-index preservation checks: Index a real Lucene parent with embedding PENDING, SPLADE COMPLETED and a searchable old sparse posting. Return its embedding but throw a non-systemic exception from preservation. Process and refresh; require embedding COMPLETED, SPLADE PENDING, retry 1 and no old posting, through the baseline reset lane (R1 narrowed). This is a preservation check on 9eb36cc79. absentSpladeEncoderPublishesEmbeddingAndResetsCompletedSplade (preservation check, not a defect pin: it keeps the before-state behaviour, passes on 9eb36cc792 and after, and is run as a supplementary --check in the evidence): seed a real Lucene parent with embedding PENDING, SPLADE COMPLETED and searchable old sparse postings, then run the combined pass with ready embedding and no sparse encoder. Seed SPLADE retry count 0 and require embedding COMPLETED, SPLADE PENDING, retry count still 0 and removed old postings through the unchanged writer reset lane (R6). This new check must fail on bf21114de8. Existing real-index checks remain unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S1 (K1) A library of 100 long documents (soak corpus slice, about 91 chunks each, AI on, GPU) keeps advancing after the first cycles: every combined cycle publishes at least one stage outcome; NER-completed and chunk-embedding-completed counts rise; no \"ZERO stage advancement\" warning after the initial cycles. Also as a fake-clock test: head of SPLADE-COMPLETED, embedding- and NER-pending long parents whose SPLADE encode cost exceeds the remaining cycle budget still advances each cycle.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S2 (K1) The combined pass never encodes SPLADE for an already-COMPLETED parent and then discards it: preservation re-encoding happens only for parents that receive another stage's outcome in the same batch, and every such encode is written.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S8 (D61) A null or size-mismatched combined embedding slice result is isolated per document: healthy documents in that slice complete, a persistently failing document escalates through its retry count to FAILED; the slice is not repeated unchanged cycle after cycle.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S13 (challenger 13) A null, short or oversized SPLADE result list never assigns results to the wrong document and never charges unattempted documents.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P1 A parent's completed SPLADE postings survive another stage's write in the combined pass (no silent drop, no reset to PENDING caused by that write).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P3 Pending ingest, GPU yield, indexing hold and root removal still stop a combined batch at its sub-batch checkpoints, and the cycle still returns to the job-queue poll.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S9 (D62) A systemic SPLADE failure (GPU arena out-of-memory, encoder unavailable) charges no per-document retries and starts a backoff that both the combined and the interleaved SPLADE paths respect; after the condition clears, the affected documents complete.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S10 (D125) Enrichment \"completed\" metrics count only documents whose update was published; encodes withheld, rejected or lost to a failed write are not counted, and the interrupted-batch log line no longer claims everything enriched was written when it was not.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P9 (challenger 24) Conditional publication still rejects stale results (content revision, document identity, chunk-parent revision, exclusions) without stale artifacts, retry changes or completion credit.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P12 (challenger 23) Resumed window accumulation still never publishes a partial or mixed-revision vector, including across rejection, content change and restart.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "R1/R4/R5 narrowed: a non-systemic preservation failure publishes the parent's earned outcome through the baseline reset lane (SPLADE back to PENDING) instead of withholding it.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "R6 (P1 narrowed) Without a configured and loaded sparse encoder, a SPLADE-COMPLETED parent's other-stage update is published through the baseline reset lane (SPLADE back to PENDING), never withheld.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp1.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#embedBatchSizeMismatch_fallsBackToIndividualAttempts",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#spladeBatchPartialResult_escalatesOnlyTheAttemptedSlice",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedParentSplade_deadlineAfterEmbedding_preservesAndPublishes",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedParentSplade_preservationFollowsNerOutcome"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EmbeddingBackfillOpsTest.java"
      ],
      "class": "Defect pin",
      "behaviour": [
        "fallbackOutageDoesNotChargeDocumentsAndRecovers is a new parameterized check. It covers both parent and chunk individual fallback, with four outages during the first document's inference: provider retires (isAvailable becomes false), provider is replaced, writes become forbidden, or inference throws SessionTemporarilyUnavailableException. It requires no updateDocumentIf calls during the outage (therefore no persisted retry charge), no inference for the later document, and completion updates for both documents after recovery (R3/S14/S4/P11). The MethodSource supplies all eight combinations.",
        "The existing ready-provider fixture gains only a lenient outer BeforeEach stub for isAvailable() == true. Existing document inputs, injected inference failures, publication checks, expected statuses/counts, assertions and selection stay unchanged; the new outage check overrides readiness explicitly. This fixture instrumentation supports the existing ready-provider precondition and is not itself a new fail-before claim."
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "R3 (S14) When the embedding provider retires or becomes unready during per-document fallback, no document is charged a retry; after the provider recovers, the documents complete.",
          "label": "agent choice"
        },
        "reproduction": "Select first and later pending documents for parent embedding or chunk embedding. Return an empty batch result to reach individual fallback. During the first individual call, make the provider unavailable, replace the selected provider, forbid writes, or throw SessionTemporarilyUnavailableException. Assert no conditional update is published and no individual inference is attempted for the later document. Restore the original available provider and write permission, return usable individual vectors and run the same path again. Assert two documents processed and a COMPLETED conditional update for each. All eight parent/chunk and outage combinations must execute."
      },
      "reproduction": "Select first and later pending documents for parent embedding or chunk embedding. Return an empty batch result to reach individual fallback. During the first individual call, make the provider unavailable, replace the selected provider, forbid writes, or throw SessionTemporarilyUnavailableException. Assert no conditional update is published and no individual inference is attempted for the later document. Restore the original available provider and write permission, return usable individual vectors and run the same path again. Assert two documents processed and a COMPLETED conditional update for each. All eight parent/chunk and outage combinations must execute.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "R3 (S14) When the embedding provider retires or becomes unready during per-document fallback, no document is charged a retry; after the provider recovers, the documents complete.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S14 (challenger 8) An embedding or SPLADE provider that becomes unavailable between selection and inference is not charged as a bad-document attempt.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S4 (D49) While the embedding provider is unavailable, still starting, or embedding writes are forbidden, chunk-embedding backfill does not run and spends no chunk retries; once the provider is ready, those chunks are embedded.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P11 (challenger 18) A quiet finite backlog with unusable dense results still reaches the individual fallback, without double-charging an attempt.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp1.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EmbeddingBackfillOpsTest.java#fallbackOutageDoesNotChargeDocumentsAndRecovers"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPublicationBenchmarkTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Make the existing embedding-provider mocks explicitly report isAvailable() == true, matching the ready-provider precondition of their existing inference/publication checks after the readiness checks added for S4/S14. EnrichmentPreservationTest adds the stub only in oneOversizedParentDoesNotPullFollowingParentsIntoTheInferenceBatch; EnrichmentPublicationBenchmarkTest adds it in fixedBatchPublication. Document contents, IDs, vectors, failures injected into inference, publication boundary, expected statuses and counts, assertions, tags and selection remain unchanged. These fixtures exercise ready-provider behavior; they do not bypass the readiness condition in production or assert that an unavailable provider should run.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S4 (D49) While the embedding provider is unavailable, still starting, or embedding writes are forbidden, chunk-embedding backfill does not run and spends no chunk retries; once the provider is ready, those chunks are embedded.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S14 (challenger 8) An embedding or SPLADE provider that becomes unavailable between selection and inference is not charged as a bad-document attempt.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BlankChunkReadinessIntegrationTest.java"
      ],
      "class": "Defect pin",
      "behaviour": [
        "Select a real Lucene whitespace-only chunk with retry 0 while the embedding provider is ready. During the real getEnrichmentInputs snapshot read, retire the provider, replace its identity, or forbid writes. In both the combined and individual chunk paths require retry 0, PENDING status and no embedding inference. Ready-provider controls still charge retry 1 (P6). All eight named parameterized variants execute; the six outage variants must fail on bf21114de8 and pass after this repair, while the two controls pass before and after."
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S14 (challenger 8) An embedding or SPLADE provider that becomes unavailable between selection and inference is not charged as a bad-document attempt.",
          "label": "agent choice"
        },
        "reproduction": "Select a real Lucene whitespace-only chunk with retry 0 while the embedding provider is ready. During the real getEnrichmentInputs snapshot read, retire the provider, replace its identity, or forbid writes. In both the combined and individual chunk paths require retry 0, PENDING status and no embedding inference. Ready-provider controls still charge retry 1 (P6). All eight named parameterized variants execute; the six outage variants must fail on bf21114de8 and pass after this repair, while the two controls pass before and after."
      },
      "reproduction": "Select a real Lucene whitespace-only chunk with retry 0 while the embedding provider is ready. During the real getEnrichmentInputs snapshot read, retire the provider, replace its identity, or forbid writes. In both the combined and individual chunk paths require retry 0, PENDING status and no embedding inference. Ready-provider controls still charge retry 1 (P6). All eight named parameterized variants execute; the six outage variants must fail on bf21114de8 and pass after this repair, while the two controls pass before and after.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S14 (challenger 8) An embedding or SPLADE provider that becomes unavailable between selection and inference is not charged as a bad-document attempt.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S4 (D49) While the embedding provider is unavailable, still starting, or embedding writes are forbidden, chunk-embedding backfill does not run and spends no chunk retries; once the provider is ready, those chunks are embedded.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp1.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BlankChunkReadinessIntegrationTest.java#blankChunkChargesRetryOnlyWhileSelectedProviderRemainsReady"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1911b-5c9e-7682-87da-c912bf17a8a2",
  "verdict": "accept",
  "digest": "sha256:23e051d7c02952e9aa5f2d1835ccec4c523d890ba449f3e6f5d50eb53fa92a47",
  "note": "Transcribed by the orchestrator from verifier session 01a1911b-5c9e-7682-87da-c912bf17a8a2 (accept entries 1-6, overall accept; reasons in its evidence intent-acceptance.md); the verifier sandbox does not write records."
}
```
