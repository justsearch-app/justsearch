---
schema: test-intent.v1
task: t-20261030-ingfix
author-role: builder
author-session: wp3-builder-ti4-20261101
---

# Test-intent entries

The CLI with `--base 1a9b83a3eb` reports three flagged files and the four
isolated new test sources below as `[exempt]`; those sources have no entries.
The backfill file appears in several entries, which the parser and coverage map
permit. Entries separate obsolete observations from each new whole oracle.
No whole backfill check qualifies as Adaptation: even the three retained guard/
pacing obligations have additional observations. Signature changes, top-level
method extraction, explicit mocks in place of MockitoExtension, and entity-page
input re-encoding are instrumentation within these classified rewrites, not a
warrant for their new assertions. The old mixed-type collection fixture is
replaced by the named real-interface coverage; it is not claimed byte-for-byte
or whole-oracle preservation.

Preservation (not repair `newChecks`): the original baseline
`DisambiguationBackfillOpsTest$ProcessBackfill#noopWhenServiceNull`,
`#noopWhenServiceUnavailable`, and `#processesAllDocsUnderForegroundLoad` pass on
1a9b83a3eb. Their candidate counterparts are `absentServiceDoesNotReadCorpus`,
`unavailableServiceDoesNotReadCorpus`, and `processesAllDocsUnderForegroundLoad`;
the original no-selection/foreground-progress obligations remain, but the added
no-interaction/acknowledgment observations are classified New below. The original
`#happyPathCollectsAndProcesses` and `#noopWhenNoCompletedDocs` also pass before;
passing does not justify keeping the displaced interface/early-return oracles.
`EntityClusterStoreTest#futureSchemaIsRefused`, and worker-services
`DisambiguationReconciliationTest#completedEmptyAdditionDoesNotTriggerAnotherPass`
and `#requestRetainsOneSnapshotWhenPublicationChangesDuringCapture` pass before
and after, and are preservation only. The replay check’s write,false parameter
also passes both; only its two publication parameters establish the repair.

Exempt-file fail-before evidence: in a shared $TMPDIR clone of 1a9b83a3eb with
the candidate tests copied and only the compile-only cursor accessor from
`gates/test-intent/compatibility/ingfix-wp3-before.patch`, each method below fails
by assertion and passes on the candidate. The extra `--check` list for recording
these in `gates/test-intent/evidence/ingfix-wp3.json` is in
`tmp/wp3-builder-ti4-report.txt`; full per-case XML and output are under
`tmp/wp3-ti4-*`. This prose records observed runs, not a claim that the existing
script-generated evidence has already been refreshed. The orchestrator must
regenerate that file and obtain fresh independent acceptance/audit; the existing
acceptance records below are retained unchanged.

- `modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/DisambiguationReadFailureTest.java#realReaderFailuresCannotLatchIncompleteWork`:
  readiness, completedCount, selection and fields all fail before by AssertionFailedError (C3).
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/disambiguation/DisambiguationSearchRecoveryTest.java#zeroNewFormReplayRepairsFiltersFacetLabelsCountsAndVariants`:
  fail-before AssertionFailedError, expected 2 hits, observed 1 (C5/C13).
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationReconciliationTest.java`:
  `#wholeCorpusAndSuccessfulQuiescence` (501 and 1001 fail; 0 and 500 pass),
  `#equalCountNerPublicationsInvalidateCompletedPass` (all six cases fail),
  `#failedPassRetriesWithoutCountChanges`,
  `#holdDuringPageDoesNotRetireUnseenSourcesAndResumes`,
  `#holdWhenClusteringStartsLeavesPageRetryable`,
  `#sharedOwnershipSurvivesUntilFinalSourceAndEmptyCorpusRetiresEverything`,
  `#mutationBetweenBatchesRestartsPhysicalCursor`,
  `#replacementServiceReconcilesUnchangedCountsAfterPartialPass`,
  `#legacySidecarIsRebuiltFromLiveSourcesAndSurvivesRestart`,
  `#canonicalAndRawSearchFiltersAndFacetsFollowRetirement`,
  `#excludedHeadsDoNotStarveEligibleTailAndTypesRemainSeparate`,
  `#publicResetRestartsAttachedServiceAndCleanupFailureCanBeRetried`, and
  `#openServiceRemainsAvailableAcrossRepeatedProfilingResets` each fail before
  by AssertionFailedError and pass after (D83–D87; C1/C2/C4/C6–C13/C16/C17).
  The hold-at-clustering check keeps the same never-verification and wraps it
  in assertDoesNotThrow so its violation is an assertion, as the evidence rule
  prescribes; it does not change the stimulus or required absence of normalization.

The fourth exempt file,
`modules/worker-core/src/test/java/io/justsearch/indexerworker/disambiguation/DisambiguationReconciliationTest.java`,
contains four candidate-only checks of new ownership/completion structure. They
pass on the candidate, use APIs absent at the base, and have **no fail-before
claim**. No ownership/completion adapter is invented. Boundary checks through the
old interface pin their associated repaired behavior as follows; these do not
claim to prove every new internal/performance assertion on the baseline:

| Candidate-only check | Assertion-failing boundary check | Source/limit |
| --- | --- | --- |
| `failedFinalPublicationRetriesRetirementWithZeroNewForms` | worker-core `DisambiguationServiceTest#replayRepairsPartialPersistenceAndFailedSnapshot`; worker-services `DisambiguationSearchRecoveryTest#zeroNewFormReplayRepairsFiltersFacetLabelsCountsAndVariants` | C5/D88 publication convergence; source retirement additionally covered by worker-services `DisambiguationReconciliationTest#sharedOwnershipSurvivesUntilFinalSourceAndEmptyCorpusRetiresEverything` (C6/C7). |
| `failedResetRestartsFromTheBeginningAndCanBeRetried` | worker-services `DisambiguationReconciliationTest#publicResetRestartsAttachedServiceAndCleanupFailureCanBeRetried` and `#openServiceRemainsAvailableAcrossRepeatedProfilingResets` | D86/C10; new cursor protocol remains candidate-only. |
| `establishedMultiPageLibraryThenOneAdditionHasBoundedWork` | worker-services `DisambiguationReconciliationTest#wholeCorpusAndSuccessfulQuiescence` and `#mutationBetweenBatchesRestartsPhysicalCursor` | D83/C1/C9 corpus convergence; normalization/load/rebuild bounds remain candidate-only. |
| `newMultiPageLibraryPublishesOnlyAtCompletion` | worker-services `DisambiguationReconciliationTest#wholeCorpusAndSuccessfulQuiescence` and `#replacementServiceReconcilesUnchangedCountsAfterPartialPass` | D83/C1/C12 complete traversal; publication timing/count remains candidate-only. |

For New entries without `repaired: true`, `analyze.mjs` calls
`validateEvidence` with `requireFailBefore: false`; `evidence.mjs` then requires
candidate execution/pass and unchanged source digests but does not require a
compilable/assertion-failing before-state. The six new-interface backfill checks
therefore make no assertion fail-before claim. Runner integration still needs an
orchestrator decision: `run-evidence.mjs` overlays every selected file and runs
one compilation per module on both revisions; selecting candidate-only files
in the same invocation would prevent the old-interface repair checks compiling.
The report supplies separate inventories; no single combined command is claimed
to produce acceptable repair evidence with this runner version.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/disambiguation/DisambiguationServiceTest.java"
      ],
      "class": "Defect pin",
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D88 (Replaying sidecar writes does not repair failed snapshot publication) Fail snapshot load after upsert, replay identical mentions and require snapshot convergence. C5. D85/D88 imply failure after some upserts, between entity types, or during final snapshot loading; replay identical input, including a replay creating zero new entries, and require persisted mappings and query snapshot to converge.",
          "label": "agent choice"
        },
        "reproduction": "Inject a SQLException on the final entity-cluster snapshot SELECT after four persisted writes, then replay identical mentions or an empty batch. On 09c246ae15 both publication cases fail the PERSON alias expansion assertion: expected john smith and JOHN SMITH, observed only john smith. The write,false parameter is supplementary preservation coverage and passes before and after; it does not establish a repair. The method-level check fails before by assertion in its publication,false and publication,true cases."
      },
      "reproduction": "Inject a SQLException on the final entity-cluster snapshot SELECT after four persisted writes, then replay identical mentions or an empty batch. On 09c246ae15 both publication cases fail the PERSON alias expansion assertion: expected john smith and JOHN SMITH, observed only john smith. The write,false parameter is supplementary preservation coverage and passes before and after; it does not establish a repair. The method-level check fails before by assertion in its publication,false and publication,true cases.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "S-D88 (Replaying sidecar writes does not repair failed snapshot publication) Fail snapshot load after upsert, replay identical mentions and require snapshot convergence. C5. D85/D88 imply failure after some upserts, between entity types, or during final snapshot loading; replay identical input, including a replay creating zero new entries, and require persisted mappings and query snapshot to converge.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp3.json",
      "newChecks": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/disambiguation/DisambiguationServiceTest.java#replayRepairsPartialPersistenceAndFailedSnapshot"
      ]
    },
    {
      "items": [
        "modules/worker-core/src/test/java/io/justsearch/indexerworker/disambiguation/EntityClusterStoreTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding",
        "split",
        "instrumentation"
      ],
      "whatMoved": "The same future-schema refusal and connection cleanup oracle now seeds CURRENT_SCHEMA_VERSION + 1 instead of literal version 2, because v2 is the current source-owned store schema. No refusal, cleanup or unavailable-store assertion changed. futureSchemaIsRefused moved unchanged to the outer class and its description became a comment so the evidence selector can find it. Supplementary preservation check: EntityClusterStoreTest.java#futureSchemaIsRefused passes on both the overlaid before-state and the candidate. It is not a newChecks repair.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with designing all of the smallers and critical fixes. then proceed with implementing them accordingly.",
          "delegationLocation": "task t-20261030-ingfix request",
          "delegationScope": "fix the defects in the owner-commissioned ingestion defect list, designed by the orchestrator",
          "choice": "P4 An existing sidecar from the baseline opens after upgrade; derived state is rebuilt from the index if its schema changes, without user action. C11. Existing version-1 databases contain mappings without source ownership; open one containing both live and obsolete forms, reconcile against the corpus, then restart and require live membership retained and obsolete membership absent.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationBackfillOpsTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261030-ingfix",
          "scenario": "C6",
          "quote": "D87’s “removing or replacing every supplying document” includes replacement with COMPLETED_EMPTY, deletion of the last entity-bearing document, and an entirely empty corpus; check obsolete membership disappears even when harvesting produces no mentions",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261030-ingfix",
          "scenario": "C17",
          "quote": "Empty input and already-known forms are legitimate successful outcomes, distinct from unreadable or interrupted work; check a settled corpus reaches quiescence while genuine failed work remains retryable",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. Searched docs/decisions/ and docs/ for disambiguation, entity clusters, empty corpus, no completed documents and early return; inspected ADR-0007 (including its preserved facet decision), docs/reference/performance/indexing-throughput.md, docs/tempdocs/798-round-7-release-blockers.md and docs/tempdocs/355-index-reset-api.md. None requires skipping reconciliation/retirement for an empty corpus or a type-only processBatch handoff. COMPLETED_EMPTY must not spuriously invalidate a settled pass (P3), which is distinct from reconciling removal to empty (C6/C17).",
      "behaviour": "Retires ProcessBackfill.noopWhenNoCompletedDocs: its query-only/early-return expectation cannot stand for successful empty-corpus reconciliation, which must retire obsolete source membership and terminate. The old method asserted the selection call (and its fixture returned no IDs); it did not assert retirement or quiescence. Boundary coverage is modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationReconciliationTest.java#sharedOwnershipSurvivesUntilFinalSourceAndEmptyCorpusRetiresEverything and #wholeCorpusAndSuccessfulQuiescence. Their method-level fail-before assertions are documented above; the 0-document parameter itself is preservation, not a repair."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationBackfillOpsTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261030-ingfix",
          "scenario": "C7",
          "quote": "One typed raw form may occur repeatedly within one document and across several documents; remove or replace one source and require membership retained until its final source disappears, while unrelated forms and entity types remain intact",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261030-ingfix",
          "scenario": "C16",
          "quote": "Keep type separation, trimming/blank handling, duplicate handling, similarity thresholds, cluster-size limits and unmapped fallback; preserve processing-source exclusions when extending traversal; check excluded heads do not starve eligible tail documents",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. Searched docs/decisions/ and docs/ for disambiguation, entity clusters, empty corpus, no completed documents and early return; inspected ADR-0007 (including its preserved facet decision), docs/reference/performance/indexing-throughput.md, docs/tempdocs/798-round-7-release-blockers.md and docs/tempdocs/355-index-reset-api.md. None requires skipping reconciliation/retirement for an empty corpus or a type-only processBatch handoff. COMPLETED_EMPTY must not spuriously invalidate a settled pass (P3), which is distinct from reconciling removal to empty (C6/C17).",
      "behaviour": "Retires only the type-only processBatch mock handoff observation in ProcessBackfill.happyPathCollectsAndProcesses. Source ownership now needs a per-source handoff; this does not retire the PERSON/ORGANIZATION/LOCATION inclusion expectations. Their replacement boundary coverage (new fixtures and observations, not an unchanged whole oracle or an Adaptation) is in the exempt modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationReconciliationTest.java#sharedOwnershipSurvivesUntilFinalSourceAndEmptyCorpusRetiresEverything (PERSON, ORGANIZATION and LOCATION retained independently) and #excludedHeadsDoNotStarveEligibleTailAndTypesRemainSeparate (type separation, blanks, duplicates and exclusions), under C7/C16. Both fail before by assertion and pass on the candidate."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationBackfillOpsTest.java"
      ],
      "class": "New",
      "behaviour": "Preserves ProcessBackfill.noopWhenServiceNull’s null-service precondition and no corpus selection obligation, but expands the observation from never queryDocIdsByField to no interactions with the corpus reader and service. That stricter whole oracle is New, not Adaptation. The original baseline counterpart passes; no assertion fail-before is claimed for the new-interface check. Its no-work guard contributes to P2’s requirement never to delay primary indexing.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261030-ingfix",
          "scenario": "P2",
          "quote": "Disambiguation still runs only when no other enrichment work is pending and never delays primary indexing.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp3-new.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationBackfillOpsTest.java#absentServiceDoesNotReadCorpus"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationBackfillOpsTest.java"
      ],
      "class": "New",
      "behaviour": "Preserves ProcessBackfill.noopWhenServiceUnavailable’s unavailable-service precondition and no corpus selection obligation, but expands the observation from never queryDocIdsByField to no corpus-reader interactions. That stricter whole oracle is New, not Adaptation. The original baseline counterpart passes; no assertion fail-before is claimed for the new-interface check. Its no-work guard contributes to P2’s requirement never to delay primary indexing.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261030-ingfix",
          "scenario": "P2",
          "quote": "Disambiguation still runs only when no other enrichment work is pending and never delays primary indexing.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp3-new.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationBackfillOpsTest.java#unavailableServiceDoesNotReadCorpus"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationBackfillOpsTest.java"
      ],
      "class": "New",
      "behaviour": "Preserves ProcessBackfill.processesAllDocsUnderForegroundLoad’s sustained foreground load and eventual processing of both eligible documents, re-encoded as an entity page and source-owned replaceSources handoff. The candidate additionally verifies acknowledgeReconciliation(1, true); the successful final cursor acknowledgment is a new observation under C17, so the whole check is New, not Adaptation. The original baseline counterpart passes; this check is not claimed as a fail-before repair. Boundary repair evidence for traversal and successful completion is modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationReconciliationTest.java#wholeCorpusAndSuccessfulQuiescence (501/1001 cases fail before by assertion).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261030-ingfix",
          "scenario": "C15",
          "quote": "D85 explicitly corrects the claim that foreground load aborts harvesting; sustain foreground load and require eventual progress, while multi-batch traversal still permits holds, shutdown and return to primary work",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261030-ingfix",
          "scenario": "C17",
          "quote": "Empty input and already-known forms are legitimate successful outcomes, distinct from unreadable or interrupted work; check a settled corpus reaches quiescence while genuine failed work remains retryable",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp3-new.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationBackfillOpsTest.java#processesAllDocsUnderForegroundLoad"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationBackfillOpsTest.java"
      ],
      "class": "New",
      "behaviour": "Given an entityCorpusState with one newly visible pending NER result, do not prepare reconciliation. This is a new precondition and a new prepareReconciliation observation under P2, not an unchanged baseline oracle. Candidate-only structural guard, without repaired: true; no fail-before is claimed. The old-interface boundary check modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/DisambiguationReadFailureTest.java#realReaderFailuresCannotLatchIncompleteWork pins readiness/count retry safety (C3) by assertion fail-before, not this new prepare-call observation.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261030-ingfix",
          "scenario": "P2",
          "quote": "Disambiguation still runs only when no other enrichment work is pending and never delays primary indexing.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp3-new.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationBackfillOpsTest.java#newlyVisiblePendingNerDefersReconciliation"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationBackfillOpsTest.java"
      ],
      "class": "New",
      "behaviour": "Inject IOException from entityPage after preparation and require that no cursor/completion acknowledgment occurs. This new fault injection and acknowledgment observation distinguish unreadable work from a successful empty pass under C3/C17. Candidate-only structural guard, without repaired: true; no fail-before is claimed. The old-interface boundary check modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/DisambiguationReadFailureTest.java#realReaderFailuresCannotLatchIncompleteWork pins the selection/read repair by assertion fail-before (including its selection parameter).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261030-ingfix",
          "scenario": "C3",
          "quote": "ID selection, individual field reads and readiness counts can return empty/zero on I/O failure, bypassing the backfill’s catches; inject faults through the real Lucene interface and require retry without treating missing reads as authoritative absence",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261030-ingfix",
          "scenario": "C17",
          "quote": "Empty input and already-known forms are legitimate successful outcomes, distinct from unreadable or interrupted work; check a settled corpus reaches quiescence while genuine failed work remains retryable",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp3-new.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationBackfillOpsTest.java#failedSelectionDoesNotAcknowledgeCursor"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationBackfillOpsTest.java"
      ],
      "class": "New",
      "behaviour": "Change reader identity between the pre-page and post-page corpus observations and require no cursor/completion acknowledgment for that mixed pass. This is a new fault/precondition and acknowledgment observation under C9. Candidate-only structural guard, without repaired: true; no fail-before is claimed. The old-interface boundary check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationReconciliationTest.java#mutationBetweenBatchesRestartsPhysicalCursor pins eventual convergence after mutation by assertion fail-before; it does not independently prove this new reader-token protocol.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261030-ingfix",
          "scenario": "C9",
          "quote": "Selection and the three entity-field reads acquire separate searchers; edit/delete a document between reads or batches and require eventual convergence to its current revision, without falsely completing a mixed or stale scan",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/ingfix-wp3-new.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/DisambiguationBackfillOpsTest.java#changedReaderDoesNotAcknowledgeMixedPass"
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
  "session": "01a1962f-c70e-7811-9092-cd98997bf71c",
  "verdict": "accept",
  "digest": "sha256:46717d2138c96080e35d3c64358f4cf5b5e1141f9f15947ee802f23315c8eae6",
  "note": "Verifier verifier-1 (WP3, candidate 48ab1fcb32) accepted all three entries (DisambiguationServiceTest defect pin; EntityClusterStoreTest and DisambiguationBackfillOpsTest adaptations); overall accept."
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "01a19706-7f73-7e01-b664-bd51f647db37",
  "verdict": "reject",
  "digest": "sha256:46717d2138c96080e35d3c64358f4cf5b5e1141f9f15947ee802f23315c8eae6",
  "note": "Audit for PR #1090: entries 1-2 accept; entry 3 reject (Adaptation also displaces the early-return expectation and adds new guard checks with new preconditions, fault injection and observations; classify them and supply evidence); exempt-file verification evidence not in the repository. Transcribed by the orchestrator from the auditor's report."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a19871-fd93-7561-842e-244fc983bb47",
  "verdict": "accept",
  "digest": "sha256:4138f92cad320ffb01d22f32ad027e1aff98d767d7567b415fe668c12cfd7627",
  "note": "All 10 entries accepted; exempt-file duties met (32 cases pass unskipped, repair checks fail before by assertion, isolation probes restored)."
}
```
