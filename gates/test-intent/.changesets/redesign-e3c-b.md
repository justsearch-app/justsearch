---
schema: test-intent.v1
task: redesign-e3c-b
author-role: builder
author-session: redesign-e3c-b-builder
---

# Test-intent entries

E3c-b implements E3c-4 and E3c-6. The new fixture opens a private real Lucene runtime,
uses fake encoders and injects an IOException on the second ordered IndexWriter mutation.
It starts pending retry counts at 2, observes real publication receipts, and advances a
scheduler-local monotonic clock without sleeps. The busy-service pacing interval is zero
in this isolated fixture so pacing cannot conceal a missing backoff. Ordinary successful
completion retains its existing retry reset; unaccepted rows retain their prior count.

A writer-level cancellation exposes an additional sparse/BGE classification defect on the base:
it reaches the outer failure return after the receipt is recorded. That return now preserves
the cancellation signal, so it never starts storage backoff; native cancellation controls stay
in preservation.

Already-correct Deliver details move to preservation: exact accepted-prefix/unattempted-suffix
receipts, unchanged suffix retry counts, and individual/interleaved SPLADE/BGE-M3 storage
backoff. The new preservation controls also pin a genuine row failure between healthy peers:
peers publish, the bad row is charged once, and the stage remains eligible on the same clock.

The existing checks named below preserve E3c-a admission, internal cancellation and active
pacing; Q1-Q4 deadlines/holds/prior progress; P1 long-parent completion, routing, bounded
retention and keysets; D126 ownership credit; E1 dispositions and timing; E2 row isolation;
stale-source/stage fencing and row-local sparse artifact withholding. The original sparse
inference-backoff checks pass on the base; their private instrumentation is adapted here to
the generalized stage state. No separate known-good implementation of the new behavior is
available. Real-app fault injection remains unavailable as named in the assignment; the
orchestrator owns the ordinary short-backlog smoke check. No shared dev stack is started.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Real Lucene publication faults on the second mutation preserve exact accepted-prefix receipts and nonzero suffix retry counts. Dense parent, dense chunk and NER stages back off across individual and combined idle service; combined writes hold every stage represented in unaccepted rows, with busy sparse service sharing the same hold. Accepted-only and unrepresented stages remain eligible. Each provider replacement clears only its stage hold. Per-document fallback stops before another native call or retry write after systemic publication failure. Controlled expiry revisits pending rows behind independent cursors. Accepted dense owners release retained slots on a later write failure, unaccepted complete owners remain recoverable without recomputing windows, and retained ownership stays bounded while independent NER advances. All changed fixtures restore singleton metrics after each test. Publication cancellation retains the accepted prefix and leaves the suffix pending without a retry charge or systemic backoff. Interrupted writer failures, wrapped InterruptedIOException, ClosedByInterruptException and FileLockInterruptionException, and ordinary publication failures observed during shutdown, indexing hold, source invalidation, provider retirement or authority revocation leave all seven stage/service variants immediately eligible. An AlreadyClosedException without a hard stop remains systemic and recovers only after controlled expiry.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e3c request",
          "quote": "A systemic failure of a write backs off every stage represented in that write's unaccepted rows. Other stages stay eligible; their own writes may then fail and back off on their own (shared infrastructure), but they are not pre-emptively held."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e3c request",
          "quote": "An accepted dense owner releases its retained slot even when a later row of the same write fails; unaccepted complete owners stay recoverable. A later genuine row failure charges exactly one retry."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e3c request",
          "quote": "One backoff per stage, shared by idle, individual, combined and interleaved service; controlled expiry (the existing SPLADE scheduler backoff schedule, generalised per stage); reset when that stage's provider changes. In memory only; nothing implies crash-persistent backoff."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e3c request",
          "quote": "Row-local failures (E2: one bad row between healthy peers), stale or retired rejections and cancellation are never systemic and set no backoff."
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-e3c-b.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#individualPublicationFailureHoldsItsStageAcrossServicePathsAndRecovers",
        "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#providerReplacementResetsOnlyThatStagesHold",
        "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#combinedFailureAttributesUnacceptedStagesAndReleasesAcceptedDenseOwners",
        "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#combinedPublicationHoldsAllAndOnlyStagesInUnacceptedRows",
        "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#aStageRepresentedOnlyInTheAcceptedPrefixRemainsEligible",
        "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#systemicPublicationInPerDocumentFallbackStopsBeforeAnotherNativeCall",
        "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#expiredStageEventuallyWrapsItsCursorWhileTheIndependentStageKeepsAdvancing",
        "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#publicationCancellationNeverBacksOffOrChargesTheUnacceptedSuffix",
        "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#interruptedWriterCancellationDoesNotBecomeStorageBackoff",
        "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#wrappedInterruptedIoWithoutThreadFlagNeverBacksOff",
        "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#publicationFailureDuringHardStopNeverBacksOff",
        "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#closedWriterWithoutHardStopRemainsSystemic"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The sparse-hold fixture invokes the existing failure-recording seam to establish a capped hold beyond the entire modeled cycle instead of writing its retired private infinite deadline field. The recovery fixture advances its existing controlled monotonic clock instead of zeroing the old wall deadline, then reads the per-stage failure count instead of the retired SPLADE-only field. Its initial failure injections, no-retry/no-reencode assertions, shared-path checks, successful recovery and zero failure-count oracle stay intact. The fixture now saves and restores its prior singleton backfill mode, completion, timing and disposition counters after every test. No existing oracle or selection is removed."
    }
  ]
}
```

## Verification commands

The orchestrator commits the candidate first. Run these sequentially in the background.
The evidence runner writes the JSON; the builder does not write either evidence file.

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-e3c-b.json \
  --before 95a23814feb0971171ed39237f459361e5dc68e4 \
  --check modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#individualPublicationFailureHoldsItsStageAcrossServicePathsAndRecovers \
  --check modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#providerReplacementResetsOnlyThatStagesHold \
  --check modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#combinedFailureAttributesUnacceptedStagesAndReleasesAcceptedDenseOwners \
  --check modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#combinedPublicationHoldsAllAndOnlyStagesInUnacceptedRows \
  --check modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#aStageRepresentedOnlyInTheAcceptedPrefixRemainsEligible \
  --check modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#systemicPublicationInPerDocumentFallbackStopsBeforeAnotherNativeCall \
  --check modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#expiredStageEventuallyWrapsItsCursorWhileTheIndependentStageKeepsAdvancing \
  --check modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#publicationCancellationNeverBacksOffOrChargesTheUnacceptedSuffix \
  --check modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#interruptedWriterCancellationDoesNotBecomeStorageBackoff \
  --check modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#wrappedInterruptedIoWithoutThreadFlagNeverBacksOff \
  --check modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#publicationFailureDuringHardStopNeverBacksOff \
  --check modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentStorageBackoffTest.java#closedWriterWithoutHardStopRemainsSystemic
```

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-e3c-b-preservation.json \
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
  --check modules/app-engine/src/test/java/io/justsearch/app/engine/ExternalPressureHoldsBackfillTest.java
```

The preservation runner has no `--before`: that flag requires assertion failures. Run the
same preservation selectors manually in a shared clone of the base, using its own build
folders; use the base's original SlowBatch fixture there. Repair selectors transplant only
the new test source and need no compatibility patch or production overlay.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1a6fe-6dd3-7310-a8a6-85f926f07b37",
  "verdict": "accept",
  "digest": "sha256:28ae1f171673e8fafcaf80fe2546a41fab79c77eb3dcb681324b8df0ee511bc2",
  "note": "Codex verifier-2 on 743b5bdb88: entry 1 New accept, entry 2 Adaptation accept. E3c-4, E3c-6 and classification rows 1-6 pass in all seven variants; 12 repair selectors fail before by assertion and pass after; E3c-a, Q1-Q4, P1-P6 preserved. A cycle-wide one-retry bound for chunk units is E4, not E3c-b."
}
```
