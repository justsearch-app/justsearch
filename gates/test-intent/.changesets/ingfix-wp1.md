---
schema: test-intent.v1
task: t-20261030-ingfix
author-role: builder
author-session: builder-ingfix-wp1-test-intent-repair-20261031
---

# Test-intent entries

Coverage is limited to the seven modified test files in this branch's diff from merge-base
`9eb36cc7923b530624f7ced8ea0bc678f0011b31` to candidate
`a48745a010` plus the uncommitted evidence-selection repairs, including the earlier candidates
`ca3f78b4066b143038561ee746a088a66ae9b82f` and `c43bedcbb26a0a0609e3d3fb931737b459003dd1`
and repair scenarios R1-R5. The R4 repair is committed in `c43bedcbb2`; the R5 repair is
committed in `a48745a010`.
The three isolated new test files previously reported exempt remain outside these entries.
No changes from main after the merge-base are covered.

All four Defect pin entries require generated evidence at
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
        "deferredPreservationNeverFallsThroughToIndividualEmbeddingIncludingBackoff: a completed-SPLADE parent earns an embedding outcome but preservation throws SessionTemporarilyUnavailableException. After that cycle and another cycle during backoff, no batch update has been published, embedding remains PENDING and preservation has been attempted only once. Both parameterized variants (encoder still present or retired during backoff) recover after the encoder is restored and backoff is cleared: embedding and SPLADE are COMPLETED (R2/P1/S9).",
        "The existing budgetTripWithNoProgress_stillLogsTheWarn assertion is retained. Its publication stub now reports one accepted update and the remaining updates rejected, so the repaired individual embedding fallback cannot give this zero-published-stage diagnostic fixture completion credit (S8/S10). This is an explicit fault-injection change, not a new expected warning. The shared scheduler fixture supplies an optional sparse encoder for the added scenarios.",
        "persistentlyFailingPreservationDoesNotPoisonHealthyDocumentsAcrossCycles: three fake-clock idle cycles each complete 160 healthy parents despite a persistently failing parent at the head, covering healthy peers in its slice, the next slice and the next batch. Each published outcome includes regenerated postings; the failing parent's state and postings stay unchanged with no retry charge. Exactly two preservation calls contain that parent each cycle (slice plus individual), proving it cannot reoccupy later batches in that idle cycle. No individual update is published and no ZERO stage advancement warning is emitted (R4/S1/P1). The fixture's combined batch size becomes configurable, retaining its existing 64-document default; only this check uses 128 to span multiple preservation slices.",
        "wholeBatchOfFailingPreservationDoesNotStarveFollowingHealthyParents: R5 (S1) A batch made only of documents whose preservation keeps failing does not stop the documents behind it from advancing in the same cycle. For R5, use runIdleCycle with a full 64-parent batch whose preservation fails both in the slice and individually, followed by 64 healthy parents. Parameterize zero or 64 healthy leading parents to exercise both the probe and tight-loop continuation gates. Healthy parents must complete embedding and NER in that cycle with preserved postings; withheld parents retain their original fields without retry charges and are attempted only in their slice and individually. Require return before the fake-clock cycle budget and no individual publication or ZERO stage advancement warning."
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S1 (K1) A library of 100 long documents (soak corpus slice, about 91 chunks each, AI on, GPU) keeps advancing after the first cycles: every combined cycle publishes at least one stage outcome; NER-completed and chunk-embedding-completed counts rise; no \"ZERO stage advancement\" warning after the initial cycles. Also as a fake-clock test: head of SPLADE-COMPLETED, embedding- and NER-pending long parents whose SPLADE encode cost exceeds the remaining cycle budget still advances each cycle.\n\nS2 (K1) The combined pass never encodes SPLADE for an already-COMPLETED parent and then discards it: preservation re-encoding happens only for parents that receive another stage's outcome in the same batch, and every such encode is written.\n\nS9 (D62) A systemic SPLADE failure (GPU arena out-of-memory, encoder unavailable) charges no per-document retries and starts a backoff that both the combined and the interleaved SPLADE paths respect; after the condition clears, the affected documents complete.\n\nS12 (K1, challenger 2) In a pending population larger than one batch, documents that need only SPLADE or only NER and sit beyond the embedding-pending head still advance within bounded cycles.\n\nR2 (P1, S9) When the combined pass defers a parent's update (for example during a systemic SPLADE failure and its backoff), the same idle cycle does not fall back to individual embedding writes that would replace the parent without its SPLADE postings.\n\nP1 A parent's completed SPLADE postings survive another stage's write in the combined pass (no silent drop, no reset to PENDING caused by that write).\n\nR4 (S1, P1) A document whose SPLADE preservation keeps failing does not stop the other documents of its slice and batch from advancing; only its own update is withheld, uncharged.\n\nR5 (S1) A batch made only of documents whose preservation keeps failing does not stop the documents behind it from advancing in the same cycle.",
          "label": "agent choice"
        },
        "reproduction": "Use 100 fake-clock long parents with completed SPLADE and pending embedding/NER; preservation encoding consumes three seconds per parent and windowed dense work consumes the cycle budget. Repeat four cycles and observe whether both completed counts advance and the zero-advancement warning stays absent. Separately place SPLADE-only and NER-only documents behind 200 embedding-pending parents and run two cycles. Finally inject either a GPU arena allocation failure or SessionTemporarilyUnavailableException during SPLADE, invoke both combined and interleaved paths during backoff, then clear the failure and retry. The assertions require bounded advancement, no per-document retry charge for systemic failures, shared backoff and recovery. For R2, seed one embedding-pending, SPLADE-COMPLETED parent with NER complete; make preservation throw SessionTemporarilyUnavailableException, run an idle cycle, optionally retire the encoder, and run another cycle during backoff. Assert no batch writes, embedding still PENDING and exactly one preservation call. Restore the encoder, clear backoff and run another cycle; embedding and SPLADE must be COMPLETED. Both encoder-retirement variants must execute. For R4, use a fake-clock runIdleCycle over three cycles with a persistent non-systemic preservation failure at the head and 160 fresh healthy embedding/NER-pending, SPLADE-COMPLETED parents each cycle. A combined batch holds 128 parents, spanning two 64-document preservation slices and leaving a later batch. Require every healthy parent to complete embedding and NER with regenerated postings in the same write; require the failing parent to keep its original postings, pending stages and absent retry counts. Count exactly one failed slice and one individual preservation attempt for that parent per cycle, proving it is excluded from later batches in that cycle; require no individual publication and no ZERO stage advancement warning. For R5, use runIdleCycle with a full 64-parent batch whose preservation fails both in the slice and individually, followed by 64 healthy parents. Parameterize zero or 64 healthy leading parents to exercise both the probe and tight-loop continuation gates. Healthy parents must complete embedding and NER in that cycle with preserved postings; withheld parents retain their original fields without retry charges and are attempted only in their slice and individually. Require return before the fake-clock cycle budget and no individual publication or ZERO stage advancement warning."
      },
      "reproduction": "Use 100 fake-clock long parents with completed SPLADE and pending embedding/NER; preservation encoding consumes three seconds per parent and windowed dense work consumes the cycle budget. Repeat four cycles and observe whether both completed counts advance and the zero-advancement warning stays absent. Separately place SPLADE-only and NER-only documents behind 200 embedding-pending parents and run two cycles. Finally inject either a GPU arena allocation failure or SessionTemporarilyUnavailableException during SPLADE, invoke both combined and interleaved paths during backoff, then clear the failure and retry. The assertions require bounded advancement, no per-document retry charge for systemic failures, shared backoff and recovery. For R2, seed one embedding-pending, SPLADE-COMPLETED parent with NER complete; make preservation throw SessionTemporarilyUnavailableException, run an idle cycle, optionally retire the encoder, and run another cycle during backoff. Assert no batch writes, embedding still PENDING and exactly one preservation call. Restore the encoder, clear backoff and run another cycle; embedding and SPLADE must be COMPLETED. Both encoder-retirement variants must execute. For R4, use a fake-clock runIdleCycle over three cycles with a persistent non-systemic preservation failure at the head and 160 fresh healthy embedding/NER-pending, SPLADE-COMPLETED parents each cycle. A combined batch holds 128 parents, spanning two 64-document preservation slices and leaving a later batch. Require every healthy parent to complete embedding and NER with regenerated postings in the same write; require the failing parent to keep its original postings, pending stages and absent retry counts. Count exactly one failed slice and one individual preservation attempt for that parent per cycle, proving it is excluded from later batches in that cycle; require no individual publication and no ZERO stage advancement warning. For R5, use runIdleCycle with a full 64-parent batch whose preservation fails both in the slice and individually, followed by 64 healthy parents. Parameterize zero or 64 healthy leading parents to exercise both the probe and tight-loop continuation gates. Healthy parents must complete embedding and NER in that cycle with preserved postings; withheld parents retain their original fields without retry charges and are attempted only in their slice and individually. Require return before the fake-clock cycle budget and no individual publication or ZERO stage advancement warning.",
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
          "choice": "R4 (S1, P1) A document whose SPLADE preservation keeps failing does not stop the other documents of its slice and batch from advancing; only its own update is withheld, uncharged.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "R5 (S1) A batch made only of documents whose preservation keeps failing does not stop the documents behind it from advancing in the same cycle.",
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
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java"
      ],
      "class": "Defect pin",
      "behaviour": [
        "embedBatchSizeMismatch_fallsBackToIndividualAttempts replaces embedBatchSizeMismatch_isNotProgress: a malformed two-document embedding result now invokes individual attempts, persists retry 1 for each unsuccessful document, leaves them PENDING and reports the published retry advancement (S8). The former no-write/no-retry expectation is intentionally superseded by S8.",
        "spladeBatchPartialResult_escalatesOnlyTheAttemptedSlice replaces spladeBatchPartialWrite_doesNotClobberAlreadyCompletedDocOnEscalation: a one-map result for two documents publishes no positional success and charges each attempted document retry 1 (S13). The former expectation that the first document becomes COMPLETED with retry 0 is intentionally superseded by S13.",
        "completedParentSplade_deadlineAfterEmbedding_preservesAndPublishes replaces completedParentSplade_stopBeforeSplade_preservesExistingRmwInputs: an earned embedding and required completed-parent SPLADE preservation publish together despite the deadline-inclusive stop flag (S1/S2/P1). The former aborted/no-write expectation is intentionally superseded; the context hard-stop supplier remains false, so this check does not authorize ignoring pending ingest, GPU yield, indexing hold or root removal (P3).",
        "completedParentSplade_preservationFollowsNerOutcome replaces completedParentSplade_stopBeforeNer_requiresNerOutcome: NER runs before preservation encoding and publishes COMPLETED_EMPTY with the preservation map (S1/S2/P1). The former no-NER/no-write expectation is intentionally superseded by the scenario requiring preservation only after another stage earns an outcome.",
        "partialWriteRetainsCompletedWindowsUntilTheirActualRetryWrite keeps all assertions and reuses the same WindowedEmbedProgress across a fresh retry context. The new context clears the prior batch publication receipts; the unpublished second document still reuses its completed window without a second encode and is credited only after actual publication (S10/P9/P12).",
        "completedWindowWithheld_thenRetryUsesCachedPooledVector keeps all assertions. Its first preservation attempt now throws SessionTemporarilyUnavailableException and the next succeeds; failure to obtain preservation, rather than an expired soft deadline alone, withholds the earned window vector. The cached vector remains available until successful publication, with no second dense encode (S1/S2/S9/P12). This is an explicit fault-injection change; the withheld-then-reused expected outcome stays unchanged.",
        "completedParentSplade_encoderFailure_withholdsEntireUpdate replaces completedParentSplade_encoderFailure_writesRetryOutcome: with the same completed-SPLADE, embedding-pending parent and non-systemic encoder exception, embedding now remains PENDING, SPLADE remains COMPLETED, no SPLADE retry count is added and no batch update is published (R1/P1). The former embedding-COMPLETED/retry-1 writer-input expectation is intentionally superseded by R1. The map fixture checks withholding and statuses; the separate real-index check verifies postings survive.",
        "For R4, completedParentSplade_encoderFailure_withholdsEntireUpdate now requires two preservation calls (the failed slice and the individual retry), instead of one. The same persistent encoder failure, pending embedding, completed SPLADE, absent retry count and no-publication assertions remain unchanged. This records the individual preservation fallback required by the repair; it does not weaken the withholding oracle."
      ],
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S1 (K1) A library of 100 long documents (soak corpus slice, about 91 chunks each, AI on, GPU) keeps advancing after the first cycles: every combined cycle publishes at least one stage outcome; NER-completed and chunk-embedding-completed counts rise; no \"ZERO stage advancement\" warning after the initial cycles. Also as a fake-clock test: head of SPLADE-COMPLETED, embedding- and NER-pending long parents whose SPLADE encode cost exceeds the remaining cycle budget still advances each cycle.\n\nS2 (K1) The combined pass never encodes SPLADE for an already-COMPLETED parent and then discards it: preservation re-encoding happens only for parents that receive another stage's outcome in the same batch, and every such encode is written.\n\nS8 (D61) A null or size-mismatched combined embedding slice result is isolated per document: healthy documents in that slice complete, a persistently failing document escalates through its retry count to FAILED; the slice is not repeated unchanged cycle after cycle.\n\nS13 (challenger 13) A null, short or oversized SPLADE result list never assigns results to the wrong document and never charges unattempted documents.\n\nP1 A parent's completed SPLADE postings survive another stage's write in the combined pass (no silent drop, no reset to PENDING caused by that write).\n\nR1 (P1) When re-encoding SPLADE for a SPLADE-COMPLETED parent that receives an embedding or NER outcome fails for a non-systemic reason, the parent's whole update is withheld: its existing SPLADE postings stay intact and SPLADE is not reset to PENDING.\n\nR4 (S1, P1) A document whose SPLADE preservation keeps failing does not stop the other documents of its slice and batch from advancing; only its own update is withheld, uncharged.",
          "label": "agent choice"
        },
        "reproduction": "For S8, return one embedding vector for two pending documents while individual inference returns unusable results: both attempted documents must receive retry 1 and remain PENDING, instead of silently repeating an unchanged slice. For S13, return one SPLADE map for two pending documents: neither positional result may be published; both attempted documents remain PENDING with retry 1. For S1/S2/P1, set only the deadline-inclusive stop supplier after a successful embedding result, with hard stop false: required completed-parent SPLADE preservation and the earned embedding must publish. In the NER-only variant the preservation encoder sets that stop supplier; NER must already have run and its COMPLETED_EMPTY outcome and preservation map must publish together. For R1, seed a completed-SPLADE, embedding-pending parent, allow the embedding result and throw RuntimeException from preservation encoding. Assert embedding remains PENDING, SPLADE remains COMPLETED, the retry count remains absent and no updateDocumentsBatch call occurs, rather than publishing the vector and a SPLADE retry without preservation. For R4, the same persistent singleton preservation failure must be tried once as a slice and once as the individual fallback before its update is withheld, retaining every previous state and publication assertion."
      },
      "reproduction": "For S8, return one embedding vector for two pending documents while individual inference returns unusable results: both attempted documents must receive retry 1 and remain PENDING, instead of silently repeating an unchanged slice. For S13, return one SPLADE map for two pending documents: neither positional result may be published; both attempted documents remain PENDING with retry 1. For S1/S2/P1, set only the deadline-inclusive stop supplier after a successful embedding result, with hard stop false: required completed-parent SPLADE preservation and the earned embedding must publish. In the NER-only variant the preservation encoder sets that stop supplier; NER must already have run and its COMPLETED_EMPTY outcome and preservation map must publish together. For R1, seed a completed-SPLADE, embedding-pending parent, allow the embedding result and throw RuntimeException from preservation encoding. Assert embedding remains PENDING, SPLADE remains COMPLETED, the retry count remains absent and no updateDocumentsBatch call occurs, rather than publishing the vector and a SPLADE retry without preservation. For R4, the same persistent singleton preservation failure must be tried once as a slice and once as the individual fallback before its update is withheld, retaining every previous state and publication assertion.",
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
          "choice": "R1 (P1) When re-encoding SPLADE for a SPLADE-COMPLETED parent that receives an embedding or NER outcome fails for a non-systemic reason, the parent's whole update is withheld: its existing SPLADE postings stay intact and SPLADE is not reset to PENDING.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "R4 (S1, P1) A document whose SPLADE preservation keeps failing does not stop the other documents of its slice and batch from advancing; only its own update is withheld, uncharged.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp1.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#embedBatchSizeMismatch_fallsBackToIndividualAttempts",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#spladeBatchPartialResult_escalatesOnlyTheAttemptedSlice",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedParentSplade_deadlineAfterEmbedding_preservesAndPublishes",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedParentSplade_preservationFollowsNerOutcome",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#completedParentSplade_encoderFailure_withholdsEntireUpdate"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentRmwIntegrationTest.java"
      ],
      "class": "Defect pin",
      "behaviour": [
        "failedPreservationWithholdsEntireUpdateAndKeepsOldPostings replaces failedRederivationResetsStatusAndRemovesOldPostingsWithRetry. The seed, successful embedding result, non-systemic preservation failure, real Lucene processing and refresh stay the same. The expected outcome changes from embedding COMPLETED, SPLADE PENDING, retry 1 and missing old postings to embedding PENDING, SPLADE COMPLETED, absent retry count and surviving searchable old postings (R1/P1). The seed-posting assertion remains; a post-refresh assertion now requires that posting to survive. No other check in this file changes."
      ],
      "defectSource": {
        "kind": "defect-source",
        "safeguard": "no data loss",
        "reproduction": "Index a real Lucene parent with embedding PENDING, SPLADE COMPLETED and an old sparse posting; assert the old posting is searchable. Return a successful embedding vector but throw IllegalStateException from SPLADE preservation. Process the combined pass and refresh the index. Assert the old posting is still searchable, embedding remains PENDING, SPLADE remains COMPLETED and no SPLADE retry count exists. The before-state expectation instead credited embedding, reset SPLADE to PENDING, charged retry 1 and removed the posting."
      },
      "reproduction": "Index a real Lucene parent with embedding PENDING, SPLADE COMPLETED and an old sparse posting; assert the old posting is searchable. Return a successful embedding vector but throw IllegalStateException from SPLADE preservation. Process the combined pass and refresh the index. Assert the old posting is still searchable, embedding remains PENDING, SPLADE remains COMPLETED and no SPLADE retry count exists. The before-state expectation instead credited embedding, reset SPLADE to PENDING, charged retry 1 and removed the posting.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "R1 (P1) When re-encoding SPLADE for a SPLADE-COMPLETED parent that receives an embedding or NER outcome fails for a non-systemic reason, the parent's whole update is withheld: its existing SPLADE postings stay intact and SPLADE is not reset to PENDING.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P1 A parent's completed SPLADE postings survive another stage's write in the combined pass (no silent drop, no reset to PENDING caused by that write).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp1.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentRmwIntegrationTest.java#failedPreservationWithholdsEntireUpdateAndKeepsOldPostings"
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
    }
  ]
}
```

## Acceptance records
