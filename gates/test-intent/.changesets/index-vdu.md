---
schema: test-intent.v1
task: t-20261019-b81d05
author-role: builder
author-session: lane-a-b81d05
---

# Test-intent entries

F1/F2 follow the adopted b81d05 scenarios. I5 uses five alternating pairs on matched filesystems after profiling and optimizing batch capture/publication. The earlier mixed tmpfs/ext4 pipeline comparison is withdrawn; corrected timings and scaling diagnostics are in the lane report. The gate only accepts letter suffixes: I4a/I4b/I4c cite I4+/I4++/I4+++; I2b cites I2++/I10+; I12a cites I12+; I3a cites I1++/I3+. Source-refresh/replay use existing formats; no new persisted format is introduced.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/ExternalPressureHoldsBackfillTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/VduDeferredRetryTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerSeamTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduLoadedChatTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduRecoveryTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/OfflineCoordinatorOwnershipTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/OfflineCoordinatorTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/OfflinePassChatSeamTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduAttemptTestSupport.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduBatchAcknowledgedOutcomeTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduBatchProcessorAbstentionTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduBatchProcessorModeScopingTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduBatchProcessorTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduChatHoldTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduConcurrentTriggerTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduDeferralDuringChatDrainTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/PruneWriterFenceTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/VduGenerationBoundaryTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceChunkRegenerationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceVduHardeningTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/OfflineProcessingOperationLifecycleIntegrationTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VduRequestActivityTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerModeRecordingTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerStallDetectionTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerTightLoopTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BackfillSnapshotTestSupport.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BgeM3BackfillOpsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BulkEncodeUrgencyTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BulkPacePolicyUseTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BulkPaceStretchTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOpsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentLongDocumentTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EmbeddingBackfillOpsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/NerBackfillOpsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/SpladeBackfillOpsTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduCancellationRetryTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduMutationCommitTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding",
        "instrumentation"
      ],
      "whatMoved": "Existing fake-index inputs and assertions are retained. Snapshot and attempt arguments now carry the same fixture content, fields and Engine owner. Mockito adapters forward to the original fixture stubs and observation points. Real VDU fixtures supply stored source proof and explicitly claim each attempt before results, including fault injection. The NER retry helper checks exercise its replacement snapshot-bound failure path; injected failures and retry/status oracles remain. Preflight refreshes are accounted for before the original no-refresh-after-failed-commit assertion. GPU-hold fixtures raise at the same content-read boundary and the HTTP lifecycle fixtures retain exact Engine-context assertions.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-b81d05",
          "scenario": "I3",
          "quote": "Ordinary VDU success, cancellation, retry accounting, and startup recovery of PROCESSING work all keep working.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-b81d05",
          "scenario": "I4a",
          "quote": "Covers individual, batch and fallback publication, blank-content failure, late chunking, and mixed batches where some items win and some lose.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/LegacyVduSourceRefreshTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/VduNativePublicationFixture.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduRevisionRaceTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduRevisionTestSupport.java"
      ],
      "class": "New",
      "behaviour": "Reject C0 and ownerless VDU publication, distinguish attempts under one owner, preserve replacement UID/source authority, refresh only a hashless source without burning retries across two passes and reopen, and remove omitted vectors/entities when VDU replaces text. The native writer fixture publishes real source hashes rather than manufacturing revision proof. Linux case-distinct real native files retain separate claims through an atomic rename/replacement and reopened reads.",
      "repaired": true,
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-b81d05",
          "scenario": "I1",
          "quote": "A VDU result computed from content C0 is never published after the file's content C1 was published.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-b81d05",
          "scenario": "I2",
          "quote": "Legacy documents with no source revision or hash: a result whose revision cannot be verified is not published.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-b81d05",
          "scenario": "I3",
          "quote": "Ordinary VDU success, cancellation, retry accounting, and startup recovery of PROCESSING work all keep working.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-b81d05",
          "scenario": "I4c",
          "quote": "When VDU publishes C1, earlier vectors and entity fields from C0 are never served by vector or entity queries before backfill, or after a reopen.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/index-vdu-races.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduRevisionRaceTest.java#oldSourceResultNeverPublishesOntoReplacement",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduRevisionRaceTest.java#nativeBatchWriterReplacementInvalidatesPendingVdu",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduRevisionRaceTest.java#recreatedUidInvalidatesAnOtherwiseIdenticalSource",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduRevisionRaceTest.java#ownerlessRequestCannotPublish",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduRevisionRaceTest.java#oldAttemptCannotAlterSameOwnersReclaimedDocument",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduRevisionRaceTest.java#acceptedReplacementDropsEarlierVectorsAndEntitiesBeforeBackfill",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/LegacyVduSourceRefreshTest.java#hashlessSourceRefreshIsTargetedDurableAndEventuallyProvesItsRevision",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/LegacyVduSourceRefreshTest.java#unavailableHashlessSourceDefersWithoutSpendingRetries",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduRevisionRaceTest.java#linuxDistinctCaseAndAtomicReplacementKeepSeparateRevisionAuthority"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentRevisionRaceTest.java"
      ],
      "class": "New",
      "behaviour": "Block CPU providers after C0, publish C1 with real Lucene, and reject old success, failure and fallback publication for embedding, chunk embedding, SPLADE, NER, combined and BGE-M3. A replacement child under the same ID is included. Preserve winning mixed-batch items and completed current work, protect blank-content retries, and invalidate equal-length Java-hash-colliding window progress. The combined blank path is also retained as a preservation check. Mixed snapshots for two chunks of the same parent independently compare their parent incarnation proofs, including when the current item is published first.",
      "repaired": true,
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-b81d05",
          "scenario": "I4",
          "quote": "An enrichment computed from C0 is never published onto C1, and that includes chunks of C1's parent.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-b81d05",
          "scenario": "I4a",
          "quote": "Covers individual, batch and fallback publication, blank-content failure, late chunking, and mixed batches where some items win and some lose.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-b81d05",
          "scenario": "I4b",
          "quote": "Cached embedding windows (WindowedEmbedProgress, length plus hash) never resume C1 from C0's windows, including on an equal-length hash collision.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/index-vdu-races.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentRevisionRaceTest.java#staleSuccessCannotCompleteReplacement",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentRevisionRaceTest.java#staleFailureCannotSpendReplacementRetries",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentRevisionRaceTest.java#fallbackCannotPublishOntoReplacement",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentRevisionRaceTest.java#blankInputFailureCannotSpendReplacementRetries",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentRevisionRaceTest.java#completedCurrentWorkSurvivesAnOlderInFlightResult",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentRevisionRaceTest.java#equalLengthJavaHashCollisionDoesNotResumeOldWindows",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java#sameParentBatchKeepsDifferentRevisionExpectationsSeparate"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/PrunePrefixReplayFenceTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/VduGenerationReplayTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/VduReplayFailureRetentionTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/VduResultReplayParityTest.java"
      ],
      "class": "New",
      "behaviour": "Unverifiable legacy VDU rows are rejected before chunks or parent status changes and retired by exact journal revision after a covering commit. Invalid payloads, unavailable targets, commit failure and generation-ineligible rows retain their existing recovery behavior. The legacy prune-race barrier now holds the snapshot read under the live file fence because chunk insertion is forbidden for such a row; the direct authorized VDU/prune race still holds its first actual chunk. Final parent/chunk/sibling and receipt assertions remain.",
      "repaired": true,
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-b81d05",
          "scenario": "I2b",
          "quote": "Old TEXT or EMPTY rows over C1 never change C1's status, and the rows are eventually retired exactly, without blocking migration or cleanup.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-b81d05",
          "scenario": "I3",
          "quote": "Ordinary VDU success, cancellation, retry accounting, and startup recovery of PROCESSING work all keep working.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/index-vdu-races.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/VduGenerationReplayTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/VduReplayFailureRetentionTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/VduResultReplayParityTest.java"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPublicationBenchmarkTest.java"
      ],
      "class": "New",
      "behaviour": "A fixed real-Lucene test-embedding fixture measures publication and end-to-end batch time without timing assertions. Explicit conditional expectations retain match/mismatch behavior and preserve vectors on unchanged-content RMW; the existing parent text bound admits one oversized first parent and leaves following work pending. Legacy hashless parent snapshots retain one SHA-256 computation per parent across sibling chunks; the prior candidate computed it once per sibling (assertion failure recorded in the profiling evidence).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-b81d05",
          "scenario": "I5",
          "quote": "Throughput is measured on a fixed real-Lucene fixture.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-b81d05",
          "scenario": "I4a",
          "quote": "Covers individual, batch and fallback publication, blank-content failure, late chunking, and mixed batches where some items win and some lose.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/index-vdu-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPreservationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentPublicationBenchmarkTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentRevisionRaceTest.java#blankCombinedInputStillDoesNotSpendReplacementRetries"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduCrashRecoveryTest.java"
      ],
      "class": "New",
      "behaviour": "Actual child-JVM death after a committed partial chunk replacement or after a covered parent replacement rejects the abandoned result after restart, preserves sibling content, and permits startup recovery followed by complete VDU publication.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-b81d05",
          "scenario": "I12a",
          "quote": "A kill after a partial chunk replacement, durable buffering, Lucene deletion, queue removal or commit, or before journal cleanup, plus a recreate after the final absence probe with a newer queued UPSERT. Each leaves exact revisions and sibling files correct after a reopen.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/index-vdu-crash.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduCrashRecoveryTest.java"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/VduOpsAttemptOwnershipTest.java"
      ],
      "class": "New",
      "behaviour": "An older completed cancellation retires only its captured attempt IDs; a later claim remains available to its own cancellation, and a failed cancellation retains its attempts for retry. Claim insertion and removal share one owner-map operation so concurrent completion cannot orphan a new attempt set.",
      "repaired": true,
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-b81d05",
          "scenario": "I3a",
          "quote": "An old result, release or cancellation never alters the successor's claim or retries.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/index-vdu-client-attempts.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/VduOpsAttemptOwnershipTest.java"
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
  "session": "verifier-claude-lane-a-30f848dc9",
  "verdict": "accept",
  "digest": "sha256:0000e1d20f0f2756f4c9cba9c406c4ec3a8cf49ef0603fb8053ea73a5ea6a187",
  "note": "Real-Engine F1 (stale VDU rejected, live+restart; baseline publishes V0 text) and F2 (windowing restarts; baseline resumes C0) checked; adapted-test assertion removals are signature/preflight-refresh adaptations."
}
```
