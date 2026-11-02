---
schema: test-intent.v1
task: t-20261102-redesign
author-role: builder
author-session: redesign-e1-applied-outcome-continuation
---

# Test-intent entries

The five isolated new test files are exempt (EnrichmentReceiptPreservationTest is a preservation/repair check, not a New entry), and are explicitly documented below as New checks. Their fail-before checks are listed in the builder handoff for independent evidence generation at gates/test-intent/evidence/redesign-e1.json. Existing completion, selection, window resumption, artifact and commit-cadence oracles remain preservation checks.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/BackfillProbeCommitPersistenceTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerProviderGateTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/CombinedChunkPublicationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BackfillSnapshotTestSupport.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentFailureAccountingTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPublicationBenchmarkTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationReconciliationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EmbeddingBackfillOpsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/NerBackfillOpsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BgeM3BackfillOpsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/NerLazyCreationRetryTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "instrumentation"
      ],
      "whatMoved": "Mocks and real-writer spies observe publishEnrichmentBatch receipts instead of aggregate or boolean conditional updates. Single-item retry/fallback assertions and their fixture capture now observe the one-entry receipt publication. The fixture adapter translates its existing configured aggregate prefix into IDs. Inputs, faults, ID/status/retry predicates, assertions, tags, and commit/selection expectations are preserved; the blank/failing NER checks still reject completion publication. BackfillSchedulerSlowBatchTest's two no-individual-retry checks (persistentlyFailingPreservationDoesNotPoisonHealthyDocumentsAcrossCycles and the failed-preservation helper) keep their never-updateDocumentIf verification and add never-publishEnrichmentBatch with a one-entry update list and one-entry input map, the call EnrichmentBatch.update now makes; a combined batch publishes against the whole batch's inputs. Inputs, population, encoder faults and all other expectations are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "E1-2 Conditional batch publication returns per-document accepted IDs, including the accepted prefix when a later mutation in the batch fails (today: aggregate counts or an exception with no suffix disposition).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "E1-5 Writer-corrected outcome: when publication accepts a mutation but the field mapper drops an unusable vector and writes the paired stage status as FAILED (retry count unchanged), the receipt reports that applied outcome for that document and stage, and accounting records the disposition rejected-output (computed, accepted by the writer, stored FAILED, no retry charge), never published or completed. What is written stays exactly as on base (P2).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/status/StatusRecordSchemaTest.java",
        "modules/ui-web/src/api/__fixtures__/status-response-live.json"
      ],
      "class": "New",
      "behaviour": "Default serialized enrichment progress and the shared frontend status fixture expose an empty enrichmentOutcomes object. The fixture retains every pre-existing field and value.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "E1-4 Counters distinguish computed outputs, accepted publications, stage transitions (completed/empty/failed), rejected-stale and committed; a preservation rewrite is not counted as a new completion; a partial window advance is counted as advancement, not as a completed embedding.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "P4 Existing status/progress fields keep their meaning; additions are additive.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-e1.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/status/StatusRecordSchemaTest.java#additiveEnrichmentOutcomesAppearInStatusAndSharedFixture"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/metrics/EnrichmentAttemptTimingTest.java",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentPublicationReceiptTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentMapperDispositionTest.java"
      ],
      "class": "New",
      "behaviour": "E1-1..E1-5: timing survives zero completions; receipts name accepted/stale IDs and mapper-corrected FAILED statuses even on an applied prefix; selected-stage tickets partition cancellation, publication, empty completion, retry-charged failure, deferred work, stale rejection and rejected output. Computed, accepted, completed, failed and window-advance counters stay distinct. Writer-rejected output is computed and stored FAILED without completion/publication or retry charge in the outcome accounting; the counts that existed on base (embedProcessed, docsProcessed and the metrics they feed) keep their base inputs, so the two mapper-disposition checks expect the base value 1 there. Saved-state and commit assertions are preservation checks.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "E1-1 (D127) A stage attempt that completes zero documents (classification only, a failed batch, a batch whose outputs are all rejected stale, an isolation pass, an exception) records its elapsed time in stage timing; today OperationalMetrics drops zero-completion calls.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "E1-2 Conditional batch publication returns per-document accepted IDs, including the accepted prefix when a later mutation in the batch fails (today: aggregate counts or an exception with no suffix disposition).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "E1-3 Cancellation/disposition accounting: after a cancelled individual fallback inside a combined cycle, the cycle summary partitions every selected document-stage into exactly one of: published, completed-empty, failed (retry charged), deferred/pending (no charge), rejected-stale, cancelled; totals reconcile with the selection (today the skipped count is undercounted).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "E1-4 Counters distinguish computed outputs, accepted publications, stage transitions (completed/empty/failed), rejected-stale and committed; a preservation rewrite is not counted as a new completion; a partial window advance is counted as advancement, not as a completed embedding.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "E1-5 Writer-corrected outcome: when publication accepts a mutation but the field mapper drops an unusable vector and writes the paired stage status as FAILED (retry count unchanged), the receipt reports that applied outcome for that document and stage, and accounting records the disposition rejected-output (computed, accepted by the writer, stored FAILED, no retry charge), never published or completed. What is written stays exactly as on base (P2).",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "now proceed autonomusly with remaining design work and potentially some research. once done, you can proceed with the implementations of the remaining work you proposed and designed",
          "delegationLocation": "task t-20261102-redesign request",
          "delegationScope": "implement the redesigns and fixes the orchestrator proposed and designed after the ingestion defect list",
          "choice": "P1 (D126) Successful late-chunking parents and single-pass parents are counted in published parent vectors, chunk vectors counted separately (already true on base: preservation check).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-e1.json",
      "newChecks": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/metrics/EnrichmentAttemptTimingTest.java#zeroCompletionAttemptsRetainElapsedTimeAndCallCount",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentPublicationReceiptTest.java#mixedStaleBatchNamesOnlyAcceptedDocuments",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentPublicationReceiptTest.java#laterMutationFailureRetainsAcceptedPrefixAndUnattemptedSuffix",
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentPublicationReceiptTest.java#acceptedPrefixRetainsOnlyAppliedMapperCorrections",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#cancelledFallbackPartitionsEverySelectedStageOnce",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#preservationPublicationIsNotANewStageCompletion",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#partialWindowsAdvanceWithoutCompletingAnEmbedding",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#receiptSeparatesComputedAcceptedEmptyFailedAndStale",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#publicationFailureStillReportsTheAcceptedPrefix",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#individualNerReportsAcceptedEmptyAndRejectedComputedOutput",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#selectionCancellationIsTimedWithoutChangingTheRoutingSignal",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#failedPreservationStillReportsTheSpladeAttempt",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentMapperDispositionTest.java#acceptedMutationWhoseVectorIsDroppedIsNotACompletedEmbedding",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentMapperDispositionTest.java#individualFallbackReportsRejectedOutputWithoutChangingCommitOrRetry",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#computedOutputsSurviveProviderRetirementInBatchAndIndividualFallback",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#individualSpladeRetainsComputedPrefixWhenALaterSliceIsCancelled"
      ],
      "repaired": true
    }
  ]
}
```

## Independent reproduction

The builder did not run run-evidence, create the JSON evidence file, write an acceptance record, commit, or delegate. The exact --before, --before-patch, --overlay and --check lists for the independent verifier are:

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-e1.json \
  --before 344bdaf03c \
  --before-patch gates/test-intent/compatibility/redesign-e1-before.patch \
  --overlay modules/worker-core/src/test/java/io/justsearch/indexerworker/metrics/EnrichmentAttemptTimingTest.java \
  --overlay modules/app-api/src/test/java/io/justsearch/app/api/status/StatusRecordSchemaTest.java \
  --overlay modules/ui-web/src/api/__fixtures__/status-response-live.json \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentMapperDispositionTest.java \
  --overlay modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentPublicationReceiptTest.java \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java \
  --check modules/worker-core/src/test/java/io/justsearch/indexerworker/metrics/EnrichmentAttemptTimingTest.java#zeroCompletionAttemptsRetainElapsedTimeAndCallCount \
  --check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentPublicationReceiptTest.java#mixedStaleBatchNamesOnlyAcceptedDocuments \
  --check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentPublicationReceiptTest.java#laterMutationFailureRetainsAcceptedPrefixAndUnattemptedSuffix \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#cancelledFallbackPartitionsEverySelectedStageOnce \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#preservationPublicationIsNotANewStageCompletion \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#partialWindowsAdvanceWithoutCompletingAnEmbedding \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#receiptSeparatesComputedAcceptedEmptyFailedAndStale \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#publicationFailureStillReportsTheAcceptedPrefix \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#individualNerReportsAcceptedEmptyAndRejectedComputedOutput \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#selectionCancellationIsTimedWithoutChangingTheRoutingSignal \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#failedPreservationStillReportsTheSpladeAttempt \
  --check modules/app-api/src/test/java/io/justsearch/app/api/status/StatusRecordSchemaTest.java#additiveEnrichmentOutcomesAppearInStatusAndSharedFixture \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentMapperDispositionTest.java#acceptedMutationWhoseVectorIsDroppedIsNotACompletedEmbedding \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentMapperDispositionTest.java#individualFallbackReportsRejectedOutputWithoutChangingCommitOrRetry \
  --check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentPublicationReceiptTest.java#acceptedPrefixRetainsOnlyAppliedMapperCorrections \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#computedOutputsSurviveProviderRetirementInBatchAndIndividualFallback \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#individualSpladeRetainsComputedPrefixWhenALaterSliceIsCancelled
```

The compatibility patch adds signatures and neutral empty observations. Its new publication method delegates mutations to the original aggregate writer and reports empty ID/correction lists; it does not repair base behavior. The before tree is a shared clone at 344bdaf03c under the session TMPDIR, with this patch and the six overlays above. Each checkout builds into its own build directory.

The additional preservation check EnrichmentAttemptAccountingTest#parentPublicationCreditIncludesLateAndSinglePassParentsAlongsideChunks passes on base. It keeps publication credit for the late-chunking parent, ordinary parent and chunk, checks two parent VECTORs and one CHUNK_VECTOR, and checks the chunk/late-parent/ordinary-parent encoder order. It is deliberately excluded from newChecks because it passes before. The first probe used a two-vector stub for a one-document priority slice; the corrected fixture returns one vector per requested input while keeping these expected outcomes.

## Repair after verifier-1 (P2 and P4)

The verifier found two preservation breaks. The second argument of this changeset is a pair of repair checks in the isolated new file modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentReceiptPreservationTest.java. They run a real index under BackfillScheduler on a fixed clock, so no cycle budget ends a loop early:

- #documentRejectedStaleInTheProbeStaysExcludedForTheRestOfTheCycle (P2). While the probe batch is encoded, another writer changes NER_RETRY_COUNT of "conc"; its content is unchanged. The check expects "conc" encoded exactly once in the cycle, EMBEDDING_STATUS and NER_STATUS still PENDING, NER_RETRY_COUNT 1, and "a"/"b" completed.
- #individualPassWhoseOnlyOutputTheWriterStoredFailedKeepsTheModeGauge (P4). The only pending document encodes to a NaN vector, so the mapper stores FAILED with retry 0. The check expects backfillMode "individual" and enrichmentCompleted embed = 1, as on base.

Both pass on 344bdaf03c, with and without the compatibility patch: these are preservation checks against the base. Both fail by assertion on the branch head 632a399892 ("expected: <individual> but was: <idle>"; "expected: <1> but was: <2>", with calls embedBatch[content a, content b, content conc], embedBatch[content conc]), and pass on the repaired candidate. They are not in newChecks because they pass on the base. The independent run for them uses the branch head as the before-state:

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out <verifier-chosen path for the repair-round evidence> \
  --before 632a399892 \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentReceiptPreservationTest.java#documentRejectedStaleInTheProbeStaysExcludedForTheRestOfTheCycle \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentReceiptPreservationTest.java#individualPassWhoseOnlyOutputTheWriterStoredFailedKeepsTheModeGauge \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentReceiptPreservationTest.java
```

Four newChecks changed only in assertions that previously asserted the branch's altered legacy inputs. After the repair those inputs are the base values; every accounting/receipt assertion is unchanged:

- EnrichmentMapperDispositionTest#acceptedMutationWhoseVectorIsDroppedIsNotACompletedEmbedding: embedProcessed 0 → 1.
- EnrichmentMapperDispositionTest#individualFallbackReportsRejectedOutputWithoutChangingCommitOrRetry: docsProcessed 0 → 1.
- EnrichmentAttemptAccountingTest#receiptSeparatesComputedAcceptedEmptyFailedAndStale: spladeProcessed 1 → 0 (the base's complete-batch rule).
- EnrichmentAttemptAccountingTest#publicationFailureStillReportsTheAcceptedPrefix: publishedDocIds {accepted} → {} (base adds nothing when publication throws).

On 344bdaf03c with the compatibility patch and overlays, all four still fail by assertion in the check, after the base-valued assertions pass. The two mapper checks fail at assertNotNull(receipt) (:71) and at the REJECTED_OUTPUT count, expected 1 but was 0 (:118). The two accounting checks fail with expected <3> but was <0> and expected <1> but was <0>. The other eight EnrichmentAttemptAccountingTest newChecks also still fail by assertion there, and the P1 preservation check passes. The JSON evidence is now stale for these two files and must be regenerated with the original command below.

BackfillSchedulerSlowBatchTest's two no-individual-retry checks were vacuous on the branch head. EnrichmentBatch.update had stopped calling updateDocumentIf; it publishes one entry against a one-entry input map. They now also verify that call never happens. A mutation, a single-document batch.update in the combined SPLADE slice catch, makes persistentlyFailingPreservationDoesNotPoisonHealthyDocumentsAcrossCycles and all four parameterisations of the failed-preservation helper fail at that verify (NeverWantedButInvoked). The adapted file passes unchanged on 344bdaf03c with the patch (22 tests) and on the candidate.

Repair-round validation: the four-module command (worker-services, worker-core, adapters-lucene, indexer-worker) passes on the candidate with 0 failures: worker-services 3,298 tests (18 skipped), worker-core 590 (15), adapters-lucene 956 (3), indexer-worker 2,499 (31). java-prepush.mjs --unreferenced passes (BUILD SUCCESSFUL in 6m 9s). The verifier probes in tmp/verifier-probes were not modified or run, because they write digests to the verifier's own folder.

## Applied outcomes

The receipt carries an immutable per-document set of stage status fields corrected to FAILED by the mapper in an accepted mutation. This reuses the existing dropped-vector report during that same write and retains corrections for the accepted prefix on a later failure. No persisted field, write ordering or atomicity changed.

Accounting assigns REJECTED_OUTPUT, records computed output and stored failure, and excludes accepted-publication/completion and retry-charged failure. Individual updates consume the writer receipt rather than synthesizing one from a boolean. Raw writer acceptance still controls the existing commit and loop signals.

Receipts and writer corrections feed only the new outcome accounting. The counts and gauges that existed on base keep their base inputs: combined embedProcessed/spladeProcessed/nerProcessed use the complete-batch rule and field presence, individual docsProcessed is the raw accepted count (so the backfill mode gauge and enrichmentCompleted see what they saw on base), and the cycle's exclusion set (publishedDocIds) keeps the base members: all IDs on a complete write, the base content-and-unwritten-fields inference on a partial write, nothing when publication throws. A document rejected stale in the probe batch therefore stays excluded for the rest of the cycle exactly as on base. The real-writer checks preserve FAILED and retry 0; the individual fallback check also preserves the commit and routing signal.

Review also found computed outputs lost when a provider retires after returning aligned vectors, or a later individual SPLADE slice is cancelled. Computed tickets are now recorded at those return points before existing guards can withhold publication. No guard, fallback, selection, retry or write decision changed. The retirement fixture uses doAnswer so installing the second mode cannot execute the previous mock answer and retire the provider before selection.

Committed remains the existing writer commitCount/writerPendingDocs status, separate from accepted pre-commit publications. Preservation rewrites have computed/accepted observations without a new selected-stage completion; partial document windows have advancement without a computed whole-document embedding.

## Builder validation observations

- Before checks: all 17 named checks have assertion failures on 344bdaf03c with the compatibility patch/overlays, for their stated accounting/receipt/timing/status outcomes. The final retirement fixture recheck also fails by the computed-counter assertion (expected 2, actual null).
- Final focused candidate snapshot: PASS, 60 executed tests and no skips (52 worker-services, 1 worker-core, 3 adapters-lucene, 4 app-api). All 17 exact new check IDs passed, including both retirement modes and the cancelled SPLADE prefix. The P1 preservation check and the adapted legacy tests passed. The shared candidate clone contains the tracked diff and untracked source/test files, including the corrected mock fixture; all 39 changed non-Markdown files match the final source checkout byte for byte. It uses no build outputs from the source checkout.
- Clean baseline six-module run: worker-services has exactly four Linux filesystem failures; worker-core, adapters-lucene, indexer-worker, app-api and app-services pass. Indexer-worker reported 2,499 tests with no failures (42 existing skips on base, 39 on candidate).
- Final six-module candidate command: exit 1 solely for the four baseline Linux filesystem failures; their exception/assertion types and messages match the clean base exactly. Worker-services reports 3,296 tests, 4 failures and 24 existing skips. Worker-core (590 tests), adapters-lucene (956), indexer-worker (2,499), app-api (481) and app-services (5,029) have zero failures. Existing suite skips remain unverified; all 17 new named checks executed without skips. The corrected retirement fixture passes in both the focused snapshot and the full worker rerun. The final rerun reports BUILD FAILED in 5m 45s because those four environmental failures were not excluded or weakened.
- java-prepush.mjs --unreferenced: PASS after the final fixture update (BUILD SUCCESSFUL in 32s; 250 tasks). The earlier UnnecessaryFullyQualifiedName violation in WritePathOps was repaired, with no rule or check weakened.
- gen-wire-schema-types.mjs --check, check-wire-type-single-authority.mjs and git diff --check pass.
- .agents/preflight is absent. Frontend runtime/model/GPU checks were not run; no npm installation, shared dev stack or GPU inference was started.
- test-intent CLI flags 13 existing items plus four exempt isolated files. The entries classify all of them; the remaining gate failures are the intentionally absent verifier-generated evidence and independent acceptance. Neither is a builder-authored artifact.
- Shared resources touched: the two Gradle slots/caches and CPU/disk. Test indexes, services and child application processes belonged to these check executions.

The four baseline-identical Linux failures are LinuxRootMountInfoTest#missingDanglingAndAmbiguousUuidMappingsAreUnknown (NoSuchFileException: /dev/disk/by-uuid) and WatcherUnavailabilityScopeTest#aNewerUnavailableIncarnationCannotBeClearedByAnOlderReconciliation, #endedUnavailableIncarnationIsRecoverableOnlyByItsOwnReconciliation, #inaccessibleRootWhoseAbsenceIsUnknownIsStillRootScoped (the same expected-true/actual-false assertions). They were not skipped or weakened.

## Preservation observations

| Contract | Checks | Base | Candidate |
| --- | --- | --- | --- |
| P1 | EnrichmentAttemptAccountingTest#parentPublicationCreditIncludesLateAndSinglePassParentsAlongsideChunks | pass | pass |
| P2 | BackfillProbeCommitPersistenceTest (8 tests); CombinedChunkPublicationTest (2 tests) | pass, no skips | pass, no skips |
| P2/P3 | CombinedEnrichmentCycleBudgetTest (29 tests); CombinedEnrichmentFailureAccountingTest (19 tests) | pass, no skips | pass, no skips |
| P4 | Existing StatusRecordSchemaTest schema/serialization checks; old fixture fields retained in diff | pass | pass |
| P2 | EnrichmentReceiptPreservationTest#documentRejectedStaleInTheProbeStaysExcludedForTheRestOfTheCycle | pass (branch head 632a399892: assertion failure) | pass |
| P4 | EnrichmentReceiptPreservationTest#individualPassWhoseOnlyOutputTheWriterStoredFailedKeepsTheModeGauge | pass (branch head 632a399892: assertion failure) | pass |
| P2 | BackfillSchedulerSlowBatchTest (22 tests, adapted) | pass | pass |

## Tests added or rewritten

Tests added or rewritten: modules/worker-core/src/test/java/io/justsearch/indexerworker/metrics/EnrichmentAttemptTimingTest.java — E1-1 — durable

Tests added or rewritten: modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentPublicationReceiptTest.java — E1-2/E1-5 — durable

Tests added or rewritten: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java — E1-1..E1-4/P1, retirement and cancelled-prefix accounting — durable

Tests added or rewritten: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentMapperDispositionTest.java — E1-5 combined and individual applied-status receipt/accounting — durable

Tests added or rewritten: modules/app-api/src/test/java/io/justsearch/app/api/status/StatusRecordSchemaTest.java — E1-4/P4 additive status contract — durable

Tests added or rewritten: modules/ui-web/src/api/__fixtures__/status-response-live.json — E1-4/P4 additive shared fixture — durable

Tests added or rewritten: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/EnrichmentReceiptPreservationTest.java — P2/P4 exclusion set and mode gauge after receipts (repair checks against 632a399892, preservation against 344bdaf03c) — durable

Tests added or rewritten: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java — P2 no individual retry observed at the one-entry publication — durable

Tests added or rewritten: modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/BackfillProbeCommitPersistenceTest.java — P2 commit-cadence receipt instrumentation — durable

Tests added or rewritten: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerProviderGateTest.java — P2/P3 provider gating — durable

Tests added or rewritten: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/CombinedChunkPublicationTest.java — P1/P2 chunk publication — durable

Tests added or rewritten: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BackfillSnapshotTestSupport.java — P2 fixture receipt adaptation — durable

Tests added or rewritten: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentFailureAccountingTest.java — P2/P3 retry and failure preservation — durable

Tests added or rewritten: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationReconciliationTest.java — P2/P4 post-publication fault injection — durable

Tests added or rewritten: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPublicationBenchmarkTest.java — P2 publication instrumentation — durable

Tests added or rewritten: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EmbeddingBackfillOpsTest.java — P2/P3 fallback recovery and bounded content receipt instrumentation — durable

Tests added or rewritten: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/NerBackfillOpsTest.java — P2 retry and empty-completion receipt instrumentation — durable

Tests added or rewritten: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BgeM3BackfillOpsTest.java — P2 parent/chunk retry receipt instrumentation — durable

Tests added or rewritten: modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/NerLazyCreationRetryTest.java — P2 lazy-acquisition failure retry receipt instrumentation — durable

## Additional call-path files

- modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/FieldMapper.java
- modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/LuceneRuntimeTypes.java
- modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/EnrichmentAccounting.java
- modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/StageOutcome.java
- modules/worker-services/src/main/java/io/justsearch/indexerworker/services/IndexStatusOps.java
- modules/ipc-common/src/main/proto/indexing.proto
- modules/app-api/src/main/java/io/justsearch/app/api/status/EnrichmentProgressView.java
- modules/app-api/src/main/resources/schemas/status-response.schema.json
- modules/app-services/src/main/java/io/justsearch/app/services/worker/WorkerStatusMapper.java
- contracts/wire/status.proto
- modules/ui/src/main/java/io/justsearch/ui/api/StatusFieldClassification.java
- modules/ui-web/src/api/generated/schema-types/status-response.ts
- modules/ui-web/src/api/__fixtures__/status-response-live.json
- modules/app-api/src/test/java/io/justsearch/app/api/status/StatusRecordSchemaTest.java

## Per-check observations

All 17 methods below executed without skips and passed on the final candidate. The JSON evidence file remains for the independent verifier to generate using the exact command above.

| Check ID | 344bdaf03c + compatibility/overlays | Candidate |
| --- | --- | --- |
| modules/worker-core/src/test/java/io/justsearch/indexerworker/metrics/EnrichmentAttemptTimingTest.java#zeroCompletionAttemptsRetainElapsedTimeAndCallCount | assertion failure | pass |
| modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentPublicationReceiptTest.java#mixedStaleBatchNamesOnlyAcceptedDocuments | assertion failure | pass |
| modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentPublicationReceiptTest.java#laterMutationFailureRetainsAcceptedPrefixAndUnattemptedSuffix | assertion failure | pass |
| modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/EnrichmentPublicationReceiptTest.java#acceptedPrefixRetainsOnlyAppliedMapperCorrections | assertion failure | pass |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#cancelledFallbackPartitionsEverySelectedStageOnce | assertion failure | pass |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#preservationPublicationIsNotANewStageCompletion | assertion failure | pass |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#partialWindowsAdvanceWithoutCompletingAnEmbedding | assertion failure | pass |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#receiptSeparatesComputedAcceptedEmptyFailedAndStale | assertion failure | pass |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#publicationFailureStillReportsTheAcceptedPrefix | assertion failure | pass |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#individualNerReportsAcceptedEmptyAndRejectedComputedOutput | assertion failure | pass |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#selectionCancellationIsTimedWithoutChangingTheRoutingSignal | assertion failure | pass |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#failedPreservationStillReportsTheSpladeAttempt | assertion failure | pass |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentMapperDispositionTest.java#acceptedMutationWhoseVectorIsDroppedIsNotACompletedEmbedding | assertion failure | pass |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentMapperDispositionTest.java#individualFallbackReportsRejectedOutputWithoutChangingCommitOrRetry | assertion failure | pass |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#computedOutputsSurviveProviderRetirementInBatchAndIndividualFallback | assertion failure | pass |
| modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java#individualSpladeRetainsComputedPrefixWhenALaterSliceIsCancelled | assertion failure | pass |
| modules/app-api/src/test/java/io/justsearch/app/api/status/StatusRecordSchemaTest.java#additiveEnrichmentOutcomesAppearInStatusAndSharedFixture | assertion failure | pass |

## Builder commands

Final module acceptance command (also run on the clean shared baseline clone):

```bash
exec env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew -PskipWebBuild=true :modules:worker-services:test :modules:worker-core:test :modules:adapters-lucene:test :modules:indexer-worker:test :modules:app-api:test :modules:app-services:test --continue
exec env -u DISPLAY -u WAYLAND_DISPLAY node scripts/dev/java-prepush.mjs --unreferenced
node scripts/codegen/gen-wire-schema-types.mjs --check
node scripts/ci/check-wire-type-single-authority.mjs
node scripts/governance/gates/test-intent/cli.mjs --base 344bdaf03c
git diff --check
```

Focused final candidate snapshot (shared clone with the tracked diff applied and untracked source/test files copied):

```bash
exec env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew -PskipWebBuild=true :modules:worker-services:test --tests '*EnrichmentMapperDispositionTest' --tests '*EnrichmentAttemptAccountingTest' --tests '*EmbeddingBackfillOpsTest*' --tests '*NerBackfillOpsTest*' --tests '*BgeM3BackfillOpsTest*' --tests '*NerLazyCreationRetryTest' --tests '*BackfillSchedulerProviderGateTest' :modules:worker-core:test --tests '*EnrichmentAttemptTimingTest' :modules:adapters-lucene:test --tests '*EnrichmentPublicationReceiptTest' :modules:app-api:test --tests '*StatusRecordSchemaTest.additiveEnrichmentOutcomesAppearInStatusAndSharedFixture'
```

Baseline checks were grouped into the same four test tasks with the isolated enrichment classes and the additive status method selected. After the corrected retirement fixture, its exact method was selected again. The separate P1 preservation method was selected on the same baseline clone and passed. All Gradle invocations used -PskipWebBuild=true and unset DISPLAY/WAYLAND_DISPLAY; none used a shared running service or shared build output directory.

## Acceptance records
