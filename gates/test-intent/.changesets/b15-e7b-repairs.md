---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b15-e7b-repair-codex-20261007
---

# Test-intent entries

Repairs requested after the independent E7b rejection. The P8 empty-list expectation is deliberately obsolete; the notice is the binding intent. Earlier E7a, E7b and no-adoption records remain history. Current evidence and final coefficients replace stale attachments without authoring acceptance on the verifier's behalf. Owner-word quotes below are from the current repair request of 2026-10-07 for this task; the orchestrator should retain that request in the task record; private records were not opened here. The integration build dependency is setup, outside the gate's flagged test items.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/PlannedContextSettingsIntegrationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerQuerySettingsOwnerTest.java",
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/LocalApiIntegrationTestBase.java",
        "modules/ui/src/integrationTest/java/io/justsearch/indexerworker/server/PlannedSettingsQueryOwner.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "HTTP settings partial context 131072 commits, survives a fresh store load, retains index ownership, and status exposes user_setting_unmet / inference.setting_unmet with chat enabled or disabled. The harness adds a real KnowledgeServer query owner with empty model roles and a fake chat actuator whose publication callback runs; native loading is absent. Automatic caps can vary through the real query guard without rebuilding an index owner when its physical bulk-session surface is empty; explicit index caps remain guarded. The renamed automaticMemoryCapsDoNotRebuildAnIndexOwnerWithoutPhysicalSessions retains the original inputs and assertions and states that fixture limit explicitly. The test-only module dependency makes this integration seam compile; existing harness callers keep their original defaults.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Afterwards, the update is accepted and persisted, and where the plan cannot meet it, status carries user_setting_unmet / inference.setting_unmet (P4).",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b15-e7b-repairs.json",
      "newChecks": [
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/PlannedContextSettingsIntegrationTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerQuerySettingsOwnerTest.java#automaticMemoryCapsDoNotRebuildAnIndexOwnerWithoutPhysicalSessions"
      ]
    },
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/GpuArenaExplicitSettingsTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "A user-pinned COMPACT 16k with CPU projector fits P16_PLUS on 12282 MiB: C=3703, A_run=4713, embedding cap=5311.3. Profile selection uses the explicit footprint for every candidate. An already-unplanned device retains and explains context 131072 without raising its device class.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Repeat §2.2 with the explicit values, so encoder caps account for a user-pinned chat size, and report unmet settings.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b15-e7b-repairs.json",
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/GpuArenaExplicitSettingsTest.java"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingGpuProfileTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "On P8 without COMPACT (the 9B on 8,068-11,083 MiB), status must carry the over-commit notice",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "conflictSearch": "Decision records and binding r4 other rows, revision 3 §2.2 and Q7 searched: the empty P8 degradation oracle conflicts with the required notice; no conflicting owner scenario."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingGpuProfileTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Unplanned P8 without COMPACT publishes device_memory_overcommit / inference.device_memory_overcommit while preserving the 9B launch and constants; below-P8 and unresolved notices still clear correctly.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "On P8 without COMPACT (the 9B on 8,068-11,083 MiB), status must carry the over-commit notice",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b15-e7b-repairs.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingGpuProfileTest.java#belowP8ReadsAsTheP8ClassSaysSoAndNoPlanReadsAsUnresolved"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingGpuProfileTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The CPU BGE-M3 plan publishes bge_m3_unmeasured / indexing.bge_m3_unmeasured as a standing episode and clears it when the role disappears.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "When the plan puts BGE-M3 on the CPU with bge_m3_unmeasured, operating status must publish that reason",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b15-e7b-repairs.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingGpuProfileTest.java#bgeM3sUnmeasuredCpuPlacementIsPublishedAndClearsWithThePlan"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuProfileResolverTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "A 1000 MiB unmeasured PADDLE file contributes 2450 MiB including KV and fixed overhead, and that full term is subtracted from the encoder activation budget at the resolver adapter.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "An explicit unmeasured model such as PADDLE_OCR_VL must count its file size plus the fixed term (file + 1,450 MiB).",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b15-e7b-repairs.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuProfileResolverTest.java#theResolverCountsTheUnmeasuredChatFileAlongsideKvAndOverhead"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/splade/DeviceSeatDuringCpuStepTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Both bulk documents complete after the admitting deadline expires during the first CPU fallback; every CPU step releases the seat and each bulk reacquisition has a fresh bounded wait. Query authorities preserve their deadlines and cancellation remains live.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "A bulk (indexing) ladder must never be cut short by a request deadline. Query calls keep their deadline semantics.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b15-e7b-repairs.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/splade/DeviceSeatDuringCpuStepTest.java#anExpiredRequestDeadlineCannotCutShortTheBulkEmbeddingLadder"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerQuerySettingsOwnerTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/splade/DeviceSeatDuringCpuStepTest.java",
        "modules/ort-common/src/test/java/io/justsearch/ort/GpuArenaExplicitSettingsTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation: explicit index caps still require index ownership; query ladder deadline misses stay typed; a pinned 9B that cannot fit remains selected with context/chat_profile/encoder_memory reported unmet.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Explicit user settings (context, layers, GPU off) win over profiles and are never silently changed; where they cannot be met, the app says so.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b15-e7b-guards.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerQuerySettingsOwnerTest.java#anExplicitIndexCapStillCannotBeChangedByAQueryOnlyTransaction",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/splade/DeviceSeatDuringCpuStepTest.java#aQueryEmbeddingLadderStillStopsAtItsRequestDeadline",
        "modules/ort-common/src/test/java/io/justsearch/ort/GpuArenaExplicitSettingsTest.java#anExplicitNineBOnACompactSizedDeviceIsKeptAndReportedUnmet"
      ]
    }
  ]
}
```

## Acceptance records

Pending independent verification. The builder does not accept its own work.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-e7c-codex-20261008",
  "verdict": "reject",
  "digest": "sha256:e31d2484a4c2e77e56c26bdc9fd01b60d51aed5fdcb9a5e2666910f4592fdb92",
  "note": "The 38 selected checks pass on the candidate and 10 assertion failures reproduce on 5970a2de5. However the settings integration seam uses empty native roles and fails to verify its claimed retained indexing ownership. On the real candidate, a single 16384-to-32768 context write returns 200 but the next approved text ingest fails with Mutation producer belongs to a retired generation; the old indexed alpha remains searchable and beta does not appear. The same before-state and context update on f648453b5 indexes beta successfully. Evidence: verify-e7c/ownership-single-update-engine.log and pre-ownership-engine.log plus API receipts. Other repair checks have appropriate oracles, but this required preservation claim is unsatisfied."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS4b-20261015",
  "verdict": "accept",
  "digest": "sha256:dc34ffd5c84bd8f1cccf6a1769dda0bdb3f7db91ad5bae813a13e50b8fc7f56c",
  "note": "Reviewed the corrected empty-physical-session scope, delegated choices and preservation guards; all 17 candidate case references pass. Fresh 5970a2de5 replay of the seven repaired check IDs matches their assertion failures; unchanged guard proof was checked against its retained source and evidence."
}
```
