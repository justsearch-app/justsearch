---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b62-batch-health-race
---

# Health publication and committed-reader regression checks

The orchestrator's b62 brief (agent wording under the owner's end-to-end delegation, cited below as
delegated choices) requires R1 current-state publication under blocked mapping/delivery, R2 durable
chunk completeness, and preservation of R3/R4. Independent verifier acceptance remains pending.

Evidence refreshed in records phase B (batch 3) on b599d4d7e, fail-before again at 13950b090. ee3c86b19 made CapabilityHealthBridgeConcurrentPublicationTest build HealthEventChangeRegistry with its delivery executor, which 13950b090 lacks, so the fail-before applies the compat before-patch `gates/test-intent/evidence/b50-status-timeout-compat.patch`. It adds only the declaration HealthEventChangeRegistry(ExecutorService), which ignores its argument and calls the existing no-argument constructor; no production code at 13950b090 calls it. Entries 1 and 2 again fail by assertion there. Entry 4's check (not a repair) now compiles before and passes there; the earlier run recorded a compile failure.

Records phase B (batch 3), gate tidy. Against main, ChunkAdmissionCostTest.java is an added file, so the gate flags it as one file-level item, not per method. Entry 4's item named the method `#firstUnchangedAdmissionAfterAnUnrelatedCommitReadsNoChildren` and was reported as a stale entry item; it now names the file, which is the item the gate flags. The method, its newChecks selector and its evidence are unchanged.

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
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Condition state and component state must be published together so that no reader of the Health snapshot or stream can observe a combination that never existed",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2026-10-11 (b62 builder brief)'; the text does not appear in the owner's turns of session 886833dd"
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
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "The unchanged decision must be made against state at least as new as the last committed write that affects that parent, without giving back R3's bounded cost",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2026-10-11 (b62 builder brief)'; the text does not appear in the owner's turns of session 886833dd"
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
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Fix the PMD findings without suppressions.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2026-10-11 (b62 builder brief)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "conflictSearch": "Existing R1-R4 checks retain their expectations; no conflicting rule found."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkAdmissionCostTest.java"
      ],
      "class": "New",
      "behaviour": "The first unchanged decision for a complete 5,000-chunk parent after another parent loses a committed child performs zero child metadata reads and zero parent content-block reads. This is a preservation scenario; the baseline already meets it.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "without giving back R3's bounded cost (no per-chunk work for an unchanged parent)",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2026-10-11 (b62 builder brief)'; the text does not appear in the owner's turns of session 886833dd"
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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-batch5-20261012",
  "verdict": "accept",
  "digest": "sha256:3860c8dcd8a6e7a6645dc4513f802d99b44a47c7efe2381d275ffb232c26aa1c",
  "note": "verify-batch5: entries pass on 7aafc6d72 (V5/V6, B5-H2 observer consistency, B5-C1 deleted child, R3 bounded unchanged cost 0.45-0.6 ms with one content check). The separate producer-interrupt regression B5-H1 is attributed to b65."
}
```
