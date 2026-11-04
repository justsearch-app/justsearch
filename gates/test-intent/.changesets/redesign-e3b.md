---
schema: test-intent.v1
task: redesign-e3b
author-role: builder
author-session: redesign-e3b-builder
---

# Test-intent entries

These checks specify the settled bounded keyset, retention/proof and bounded pending-state scenarios.
The builder writes no execution-evidence or acceptance records. The verifier generates the two
referenced evidence files after the final candidate is committed.

Mapping: E3b-1/2 use the keyset-turn matrix and adapter keyset check; E3b-3 uses the deferred-suffix
matrix; E3b-4 uses that matrix plus the sibling/oversized chunk-proof check; E3b-5 uses the mixed
256-owner check and retained-owner priority check; E3b-6 uses owner cleanup and partial-publication
receipts. P5 measures real Lucene collector searches with temporary indexes. P6's entity reader
cursor/reset implementation is unchanged.

The partial-publication test's obsolete aggregate ambiguity is replaced only as E3b-6 explicitly
requires. Its withheld-peer resumption and conservative loop-continuation assertions remain.
All #1095, E1 and E3a expectations are unchanged.

Collection follows the orchestrator's 2026-11-03T21:21Z decision: the existing chunk-first lane
receives its retention allowance before parent content, including retained oversized parents.
The oversized-parent preservation check exercises that ordering through the real Lucene runtime.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerProviderGateTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillIndexingRulesTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BlankChunkReadinessIntegrationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BulkEncodeUrgencyTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BackfillSnapshotTestSupport.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentUnitBoundaryTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentHardStopRepairTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentFailureAccountingTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "instrumentation"
      ],
      "whatMoved": "The same fake-index snapshots now answer keyset-page, bounded-content and strict metadata-only read signatures. The batch snapshot helper retains its existing content source (individual or batch) when applying its budget. The real chunk snapshot spy moves to the bounded overload at the same boundary; per-document fixtures continue to use their existing text source, and the held NER fixture includes its already-selected pending status in its atomic snapshot. Inputs, fault injection, unit boundaries, assertions, and E1/E3a expectations are unchanged."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EmbeddingBackfillOpsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentCycleBudgetTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Synthetic identifiers encode the existing intended order: first/second/later receive a/b/c prefixes, and numeric chunk suffixes are zero-padded. The R9 encoder sequence, chunk identities translated consistently, 830-ms budget, completion count, text sizes, and the unexamined-tail non-read assertion stay unchanged. This aligns fixtures with document-ID keyset order without changing their guarded behavior.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e3b request",
          "quote": "pages progress in document-id order;"
        }
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Each standalone and combined stage advances past poison in document-ID order, preserves its key across reader refresh/deletion and an idle turn, and wraps using exact hit metadata from the existing page query. Deadline-deferred stage suffixes stay at the cursor, preserving the existing scheduler completion checks. Budget-deferred suffixes remain pending without retry charges and become the next content prefix. Proof text is counted once per parent with sibling slices and the oversized-owner exception. Deleted/replaced/externally completed owners release both route-only and window slots without disturbing live work; retained work precedes new admission, including when a refused 257th chunk has oversized proof that must not consume the retained owner's allowance. The pre-existing parent-only oversized-budget check stays unchanged; the new budget repair matrix excludes that already-bounded standalone lane.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e3b request",
          "quote": "Pending ids inserted out of order, more than one page: each turn reads at most one page per stage (no Integer.MAX_VALUE selection, no all-ids cache); pages progress in document-id order; the cursor survives across turns and an idle turn (today caches are recreated, BackfillScheduler ~739); a reader refresh or a deletion between turns does not restart the sweep; at sweep end the cursor wraps."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e3b request",
          "quote": "An early row that keeps failing (retry not yet exhausted) or is ineligible (excluded, wrong stage state) does not block later rows: the cursor advances past examined rows; the poison row stays pending and is revisited after the wrap."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e3b request",
          "quote": "When the retention allowance stops a page early, the unexamined suffix is the next page start: none of those ids is skipped, treated as blank, retried or charged."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e3b request",
          "quote": "Retained text per turn stays within the existing 512,000-character allowance (one oversized document may go alone), counting each distinct parent text used as chunk proof once."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e3b request",
          "quote": "admitted owners get served before new admissions."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e3b request",
          "quote": "A partial or route entry is released when its document is deleted, replaced (new revision), or completed elsewhere (another path published it), and after a successful publication beside a withheld peer; the slot is then available for a new owner in the next turn."
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-e3b.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java#keysetTurnsPassPoisonSurviveRefreshDeletionAndIdleThenWrap",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java#examinedIneligibleHeadDoesNotRunOrPinTheFollowingPage",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java#budgetDeferredSuffixIsNextTurnWithoutBlankRetriesOrCharges",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java#chunkProofRetentionCountsDistinctParentsOnceAndAllowsOneOversizedOwner",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java#nextTurnReleasesDeletedReplacedAndExternallyCompletedOwners",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java#retainedOwnerIsServedBeforeNewAdmissions"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentLongDocumentTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "128 window partials and 128 route-only owners fill the shared cap. A 257th owner cannot record windows or evict any admitted progress/route; explicit release makes a slot available. All existing #1095 pooling, deadlines and chunk-advancement assertions are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e3b request",
          "quote": "257 owners offered, mixing progressing window partials and route-only entries: nothing admitted is evicted, the 257th is not admitted (it stays pending without a charge), admitted owners get served before new admissions."
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-e3b.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentLongDocumentTest.java#mixedPartialAndRouteOwnersRefuseThe257thWithoutEvictingProgress"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The receipt identifies the accepted window owner beside a withheld peer. Release only the accepted owner, retain the peer sum for a later actual publication, and preserve the existing conservative aggregate progress signal. This replaces the pre-receipt expectation of retaining both owners because an aggregate count could not identify the accepted one.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e3b request",
          "quote": "A partial or route entry is released when its document is deleted, replaced (new revision), or completed elsewhere (another path published it), and after a successful publication beside a withheld peer; the slot is then available for a new owner in the next turn."
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-e3b.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#acceptedPartialPublicationReleasesOnlyItsOwnerBesideWithheldPeer"
      ]
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SpladePendingSelectionTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The page query sorts by external document ID for every stage, respects the page bound, resumes after a deleted cursor across reader refresh, and starts a fresh sweep at an empty key. The fixture adds the three status fields required by the expanded query matrix; existing SPLADE expectations are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e3b request",
          "quote": "Keyset pages use the existing document-id sorted doc values (DocumentFieldOps near the doc-id doc values reader) on the admission or search reader the stage already uses; one cursor per stage, held by the scheduler (BackfillScheduler) so it survives turns; a reader refresh keeps the cursor value (it is a document id, not a doc number)."
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-e3b.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SpladePendingSelectionTest.java#keysetPagesUseDocumentIdsAcrossInsertionOrderRefreshAndDeletedCursor"
      ]
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SpladePendingSelectionTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "A 200-document page plus content/proof collection issues at most one keyset page query plus one content/proof lookup per selected id (201 measured on both trees) for either 1,000 or 50,000 pending parents. This is a preservation/throughput ceiling, not a fail-before repair check.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e3b request",
          "quote": "Throughput: on a backlog that fits one page today, the number of index reads per turn does not grow by more than one page query per stage; report reads per turn for 1,000 and 50,000 pending documents."
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-e3b-preservation.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SpladePendingSelectionTest.java#pageAndContentReadCountsStayBoundedAsBacklogGrows"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "The #1095 three-cycle chunk-advancement bound also holds while an oversized parent retains window progress. One encoder unit per turn cannot allow that parent to consume every content-collection turn and starve an ordinary pending chunk. This is a preservation check: the baseline must pass. Uses a real temporary Lucene runtime, valid parent/chunk revision proof, committed reader refreshes, and a deterministic window-capable provider.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task redesign-e3b request",
          "quote": "P1 #1095 (eventual long-parent completion, chunk advancement within three cycles, at most one fallback classification per cycle, single-pass vectors, equivalent resumed pooling) and the E3a pins (stops before every unit, routing retention, identity, slice validation, authority)."
        },
        {
          "kind": "owner-words",
          "location": "task redesign-e3b request",
          "quote": "Decided: keep the existing chunk-first lane order during collection."
        }
      ],
      "evidence": "gates/test-intent/evidence/redesign-e3b-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java#oversizedRetainedParentStillAllowsPendingChunkToAdvanceWithinThreeCycles"
      ]
    }
  ]
}
```

## Verification commands

The compatibility patch adds production API forwarders only; it preserves the baseline selection,
retention, pooling and publication behavior. The before checkout is a shared clone under TMPDIR;
its build output is separate. Overlay helper adaptations along with the selected checks.

```sh
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-e3b.json \
  --before 67e68264e5629e24afc3f234ea1678eb6f47da95 \
  --before-patch gates/test-intent/compatibility/redesign-e3b.patch \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerProviderGateTest.java \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillIndexingRulesTest.java \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BlankChunkReadinessIntegrationTest.java \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BulkEncodeUrgencyTest.java \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BackfillSnapshotTestSupport.java \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EmbeddingBackfillOpsTest.java \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentCycleBudgetTest.java \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentUnitBoundaryTest.java \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentHardStopRepairTest.java \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentFailureAccountingTest.java \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentAttemptAccountingTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java#keysetTurnsPassPoisonSurviveRefreshDeletionAndIdleThenWrap \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java#examinedIneligibleHeadDoesNotRunOrPinTheFollowingPage \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java#budgetDeferredSuffixIsNextTurnWithoutBlankRetriesOrCharges \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java#chunkProofRetentionCountsDistinctParentsOnceAndAllowsOneOversizedOwner \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java#nextTurnReleasesDeletedReplacedAndExternallyCompletedOwners \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java#retainedOwnerIsServedBeforeNewAdmissions \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentLongDocumentTest.java#mixedPartialAndRouteOwnersRefuseThe257thWithoutEvictingProgress \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java#acceptedPartialPublicationReleasesOnlyItsOwnerBesideWithheldPeer \
  --check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SpladePendingSelectionTest.java#keysetPagesUseDocumentIdsAcrossInsertionOrderRefreshAndDeletedCursor
```

```sh
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-e3b-preservation.json \
  --check modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/SpladePendingSelectionTest.java#pageAndContentReadCountsStayBoundedAsBacklogGrows \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java#oversizedRetainedParentStillAllowsPendingChunkToAdvanceWithinThreeCycles
```

The orchestrator owns the dev-stack backlog/heap check. No shared dev stack is started by the builder.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a1a45d-53f4-7b63-8b22-9be154e56600",
  "verdict": "reject",
  "digest": "sha256:f4b59dd0df2cef2d73a509db7819482593c31ddd56a60494c47fc7c62d06470c",
  "note": "Codex acceptor-4 on 0351cab29d: entries 1-6 and 8 accept; entry 7 rejected - P5 is a ceiling but the check requires exactly 201 searches, which would reject a correct optimization."
}
```
