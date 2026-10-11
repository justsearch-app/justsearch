---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b62-batch-health-race
---

# Health publication and committed-reader regression checks

Owner brief requires R1 current-state publication under blocked mapping/delivery, R2 durable
chunk completeness, and preservation of R3/R4. Independent verifier acceptance remains pending.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeConcurrentPublicationTest.java"
      ],
      "class": "New",
      "behaviour": "Health conditions publish atomically with the component; a blocked mapper or Health observer cannot retain a stale failure, delay recovery, or gate another subscriber.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn 2026-10-11 (b62 builder brief)",
          "quote": "Condition state and component state must be published together so that no reader of the Health snapshot or stream can observe a combination that never existed"
        }
      ],
      "conflictSearch": "Existing R1-R4 checks retain their expectations; no conflicting rule found.",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeConcurrentPublicationTest.java#recoveryPublishesWhileAnEarlierMapperIsBlocked",
        "modules/app-engine/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeConcurrentPublicationTest.java#recoveryPublishesWhileAPublicHealthObserverIsBlocked",
        "modules/app-engine/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeConcurrentPublicationTest.java#failureConditionIsInstalledBeforeOrdinaryComponentObserversRun"
      ],
      "evidence": "gates/test-intent/evidence/b6d536-b62-health-race.json",
      "repaired": true
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkCompletenessAdmissionTest.java"
      ],
      "class": "New",
      "behaviour": "Unchanged admission rejects a durably deleted child before background search reader refresh, while unchanged cost stays independent of child count.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn 2026-10-11 (b62 builder brief)",
          "quote": "The unchanged decision must be made against state at least as new as the last committed write that affects that parent, without giving back R3's bounded cost"
        }
      ],
      "conflictSearch": "Existing R1-R4 checks retain their expectations; no conflicting rule found.",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkCompletenessAdmissionTest.java#committedChildDeletionCannotBeAdmittedBeforeTheSearchReaderRefreshes"
      ],
      "evidence": "gates/test-intent/evidence/b6d536-b62-health-race.json",
      "repaired": true
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkAdmissionCostTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeSubscriberRecoveryTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Mockito closeables and the Health bridge are declared before try-with-resources; observed state uses CopyOnWriteArrayList because publication now drains independently. All assertions, work budgets, stimuli and expected outcomes are unchanged.",
      "behaviour": "Use explicitly owned closeable resource declarations to remove unused-resource PMD findings; preserve the same work budgets and Health attachment assertions with thread-safe observed-state storage.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn 2026-10-11 (b62 builder brief)",
          "quote": "Fix the PMD findings without suppressions."
        }
      ],
      "conflictSearch": "Existing R1-R4 checks retain their expectations; no conflicting rule found."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkAdmissionCostTest.java#firstUnchangedAdmissionAfterAnUnrelatedCommitReadsNoChildren"
      ],
      "class": "New",
      "behaviour": "The first unchanged decision for a complete 5,000-chunk parent after another parent loses a committed child performs zero child metadata reads and zero parent content-block reads. This is a preservation scenario; the baseline already meets it.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn 2026-10-11 (b62 builder brief)",
          "quote": "without giving back R3's bounded cost (no per-chunk work for an unchanged parent)"
        }
      ],
      "conflictSearch": "R3 and existing work-counter checks require bounded admission. Metadata preparation remains a commit publication cost; no assertion on extraction, splitting or legacy preservation is changed.",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkAdmissionCostTest.java#firstUnchangedAdmissionAfterAnUnrelatedCommitReadsNoChildren"
      ],
      "evidence": "gates/test-intent/evidence/b6d536-b62-health-race.json",
      "repaired": false
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-batch4-20261011",
  "verdict": "reject",
  "digest": "sha256:3860c8dcd8a6e7a6645dc4513f802d99b44a47c7efe2381d275ffb232c26aa1c",
  "note": "Independent V5 fails: an interruptible blocked Health observer remains alive after unsubscribe and bridge close; candidate retains its channel-owned virtual delivery thread until external release. O8/O10/O9 repairs and V1-V4 pass. Evidence F:/agent-sandbox/design-b6d536/codex/verify-batch4/report.md and OwnObserverRetirement.java; C:/agent-sandbox-tmp/verify-batch4/logs/candidate-V5.log. Required live and quiet-machine checks remain pending; no unverified item is accepted."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-batch4-20261011",
  "verdict": "reject",
  "digest": "sha256:3860c8dcd8a6e7a6645dc4513f802d99b44a47c7efe2381d275ffb232c26aa1c",
  "note": "Final independent verdict: reject V6. With public DefaultEngineComponentRegistry/CapabilityHealthBridge/HealthEventChangeRegistry wiring, an interruptible blocked observer survives unsubscribe, bridge close and producer executor shutdown on fe62ed231;13950b090 exits it. Evidence OwnObserverOwnerClose.java and C:/agent-sandbox-tmp/verify-batch4/logs/{previous,candidate}-V6.log. O8/O10 Health projection and O9 committed-deletion repairs pass; required live R1/R2/R4 and quiet R3/O7 measurements are complete. Candidate large-file median0.1059ms (before-rule0.1628ms);100000-chunk metadata heap before/after rescan27148696/27363168bytes,12 refresh cycles39661032-40429008bytes. Full governance still fails with stale evidence/acceptance findings; skipped/excluded checks and the canceled optional repetition supplement are not accepted. Complete proof and limits F:/agent-sandbox/design-b6d536/codex/verify-batch4/report.md. Own live stack stopped cleanly; no foreign process stopped."
}
```
