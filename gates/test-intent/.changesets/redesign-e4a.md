---
schema: test-intent.v1
task: redesign-e4a
author-role: builder
author-session: redesign-e4a-builder
---

# Test-intent entries

E4a retains the scheduler's mode probe, loops, ordering, ingestion stops, settings and commit
ownership. It exposes separate sparse parent/chunk pages and cursors, one-unit dense and NER
entry points, and explicit provider acquisition outcomes. The extracted dense kernel serves both
existing scheduler paths. Individual fallback publication stays between native calls; combined
publication stays bundled. Existing preservation assertions, including the #1095 fixtures, stay
in place; the superseded NER acquisition oracle is accounted for below.

The new fixture uses real isolated Lucene writers, fake encoders and a scheduler-local monotonic
clock. It uses no sleeps, begins retries at 2, closes its own runtimes and restores prior singleton
metrics. It touches no owner's application, dev stack, dependency tree or GPU. The sparse
before-state adapter invokes the existing mixed operation when the new parent/chunk entry point
is absent, exposing the wrong selected population by assertion rather than a compile failure.
No separate known-good implementation of these repairs exists.

The preservation selectors pin reference pooled vectors through both existing scheduler callers,
parent/chunk routing, stored sparse postings, combined classification retention, exact resume
offsets and pooling after publication. Individual callers retain their pre-existing ordinary/windowed
artifact policy; single-pass success remains gated to combined callers. Existing checks cover E1 accounting, E2 payloads, E3a
stops/routing/identity/slice validation/fallback/authority, E3b pages/retention/caps/slot release,
E3c admission/cancellation/deadlines/pacing/storage attribution/backoff, BGE-M3 unified ownership,
and disambiguation gates. The individual retained-window assertion is a repair: the base's individual caller uses
whole-document batching, while its final artifact and routing policy remain preserved.
The individual native unit also preserves its pre-existing ingestion cancellation policy: a
newly pending ingestion request does not cancel a native call already running in that caller.
A one-unit turn also retains a refused batch's fallback route; its next turn starts one per-document
fallback, without repeating that batch. The existing NER lazy-acquisition oracle is superseded by
D5a, with its original escalation assertions transferred to genuine row input failures.

E4b's scheduler cutover, ledger, busy dense/NER, deletions and settings remain out of scope.
The isolated running-app smoke on 2026-11-05 used `:modules:ui:runHeadless -Pmode=mcp-lite`
with an OS-assigned loopback port and private data, home, ORT cache and native temporary paths
under the session temporary directory. Existing model files in `/home/elias/dev/justsearch/models`
were read-only; GPU inference was disabled and ORT used two CPU threads. Startup queried GPU
availability. No shared dev stack or owner's running application was used.

The corpus had one 112,851-byte parent plus four 650-byte files, alongside the app's five built-in
help documents. The real combined path classified the long parent into windows once, retained
window progress across successive cycles (`windowDocsResuming=1` twice), then pooled and published
it (`windowDocsDone=1`). Status reached 100% dense and sparse coverage with chunk embeddings
ready. Keyword, SPLADE and vector queries returned HTTP 200; the traces reported no vector block
or hybrid fallback, and SPLADE retrieval executed. A scoped SPLADE query returned the long parent
after its dense publication. After shutdown, reopening the committed Lucene index confirmed its
dense and sparse COMPLETED states, a 768-dimensional vector with norm 1.000000034, a 4,059-byte
stored sparse payload and 296 FeatureField postings. The smoke probe exited 0. The launcher's
300-second guard ended the app (exit 124); ordered shutdown was recorded and its private API port
was confirmed closed. Matched-load fairness and latency acceptance remains E4b.

Manual validation: all 17 repair cases failed on the base by assertion and passed on the candidate;
the new preservation cases passed on both. The final focused run executed 265 tests with zero
failures or skips, including the unchanged 29 cycle-budget and 12 long-document #1095 cases.
The four required module suites were run: their six mount/watcher/Engine Forget environment
failures reproduced on the base. Configured skips are not counted as passes. The pre-push command
runs the existing integration selections and static checks; real-drive cases remain opt-in.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentLaneDecompositionTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "E4-D4a: parent-only and chunk-only sparse units retain independent keyset positions through adversarial ID order, continuing lower-ID parent arrivals, reader refresh and deferred suffixes. E4-D5a: current dense (batch, windows, single-pass and per-document fallback), NER and BGE-M3 providers return explicit acquisition unavailability without row retry charges or fallback fan-out; holds survive idle cycles, peers continue, controlled expiry and provider replacement recover. Both existing scheduler dense callers use the retained-route/window kernel; the individual caller now retains window routes and partial pooling across cycles while preserving its ordinary/windowed artifact policy. All retries start above zero and all singleton metrics touched are restored. Bounded dense turns retain a failed-batch fallback route so the following turn performs one per-document fallback instead of repeating the failed batch; NER turns publish one document.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e4a request",
          "quote": "E4a (operations, old scheduler kept): D4a, D5a (idle callers; incl. BGE acquisition), and dense-kernel equivalence: both existing dense paths (combined and individual) run the common kernel before E4a merges."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e4a request",
          "quote": "Sparse parents and chunks get independent opportunities and cursors under adversarial doc-ID order, continuing parent arrivals, reader refresh and deferred suffixes."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e4a request",
          "quote": "Systemic acquisition failure (SessionTemporarilyUnavailableException with the provider current, retries starting above zero) backs off dense and NER across busy and idle service without fallback fan-out or retry charge; peers continue; expiry and provider replacement restore."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e4a request",
          "quote": "extract the dense kernel (single-pass classification, retained routes, resumable windows, pooling) from the combined op into a common dense unit; expose bounded lane operations; separate sparse parent and sparse chunk selectors and cursors (chunk-only selection); explicit acquisition outcomes; E4-D4 and E4-D5."
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-e4a.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentLaneDecompositionTest.java#sparseLanesKeepIndependentCursorsThroughArrivalsRefreshAndDeferredSuffixes",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentLaneDecompositionTest.java#acquisitionFailureHoldsProviderWithoutRetryChargeAndPeersContinue",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentLaneDecompositionTest.java#individualDenseUsesTheSameRetainedRoutesAndWindowKernel",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentLaneDecompositionTest.java#boundedDenseUnitsRetainTheNextNativeOperation",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentLaneDecompositionTest.java#boundedNerUnitPublishesOneDocumentAndKeepsItsCursor"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/NerLazyCreationRetryTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e4a request",
          "quote": "Systemic acquisition failure (SessionTemporarilyUnavailableException with the provider current, retries starting above zero) backs off dense and NER across busy and idle service without fallback fan-out or retry charge; peers continue; expiry and provider replacement restore."
        }
      ],
      "conflictSearch": "Decision records searched for acquisition/session retry conflicts: none. The prior M3a lazy-creation test charges document retries on SessionTemporarilyUnavailableException; E4-D5a explicitly supersedes that expectation. Its retry-to-FAILED assertions are retained for a genuine row input failure."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/NerLazyCreationRetryTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "A lazy NER acquisition failure with a still-current provider and nonzero document retries publishes no retry or terminal/empty status and leaves the ticket deferred. Genuine invalid-row input still increments retries to FAILED using every original escalation assertion, and never publishes completed entity artifacts. The genuine-row check is preservation, run in the separate preservation block.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e4a request",
          "quote": "Systemic acquisition failure (SessionTemporarilyUnavailableException with the provider current, retries starting above zero) backs off dense and NER across busy and idle service without fallback fan-out or retry charge; peers continue; expiry and provider replacement restore."
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-e4a.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/NerLazyCreationRetryTest.java#acquisitionFailuresLeaveNonzeroRetriesPendingWithoutPublication"
      ]
    }
  ]
}
```

## Verification commands

The orchestrator commits the candidate first. Run sequentially in the background. The evidence
runner writes these files; the builder does not write evidence JSON.

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-e4a.json \
  --before 507f8793eda8a34083625b1360d4a0e713573e13 \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentLaneDecompositionTest.java#sparseLanesKeepIndependentCursorsThroughArrivalsRefreshAndDeferredSuffixes \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentLaneDecompositionTest.java#acquisitionFailureHoldsProviderWithoutRetryChargeAndPeersContinue \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentLaneDecompositionTest.java#individualDenseUsesTheSameRetainedRoutesAndWindowKernel \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentLaneDecompositionTest.java#boundedDenseUnitsRetainTheNextNativeOperation \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentLaneDecompositionTest.java#boundedNerUnitPublishesOneDocumentAndKeepsItsCursor \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/NerLazyCreationRetryTest.java#acquisitionFailuresLeaveNonzeroRetriesPendingWithoutPublication
```

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-e4a-preservation.json \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentLaneDecompositionTest.java#bothDenseCallersPublishReferencePoolingAndPreserveSparsePayload \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentLaneDecompositionTest.java#combinedDenseRetainsClassificationAndWindowOffsetsThroughIdleCycles \
  --check modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#acceptedPrefixReceiptsAlreadyHoldOnTheBase \
  --check modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#individualSparseStorageBackoffAlreadyHoldsOnTheBase \
  --check modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#rowFailurePublishesHealthyPeersChargesOnceAndSetsNoBackoff \
  --check modules/worker-core/src/test/java/io/justsearch/indexerworker/splade/BackgroundInferenceCancellationTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillResourceAdmissionTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentInternalStopTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentUnitBoundaryTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentLongDocumentTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentHardStopRepairTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAuthorityRaceTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentRevisionRaceTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentStorageDispositionTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentMapperDispositionTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentQuarantineProgressTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentExclusionPublicationTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentFailureAccountingTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentReceiptPreservationTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BulkHoldKeepsQueryOnGpuTest.java \
  --check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentPublicationReceiptTest.java \
  --check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SparseRmwPreservationTest.java \
  --check modules/ort-common/src/test/java/io/justsearch/ort/SessionAcquisitionRequestTest.java \
  --check modules/app-engine/src/test/java/io/justsearch/app/engine/ExternalPressureHoldsBackfillTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentCycleBudgetTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentRmwIntegrationTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BgeM3BackfillOpsTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/SpladeBackfillOpsTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/NerBackfillOpsTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EmbeddingBackfillOpsTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationBackfillOpsTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/NerLazyCreationRetryTest.java#rowFailuresFollowTheExistingDocumentRetryPolicy \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentLaneDecompositionTest.java#individualNativeUnitKeepsItsExistingIngestionCancellationPolicy
```

Run the same preservation selectors manually on the base shared clone in its own build folders.
The repair selectors transplant only the new test source; they need no production overlay.

## Acceptance records
