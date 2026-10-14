---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, item 5, Spec E steps E0b and E1. E0b adds the joint S5 counter
`embeddedAndSparseCount`. E1 adds Switch A (`BulkWorkGate` with the pure `BulkWorkPolicy`), mirrors
the energy poll into it as status only, adds the per-stage availability booleans, makes the
operating indexing block IDLE only when nothing enabled and available is left to drain, and lands
the reason code `indexing.paused_energy_saver`. E2 (the generation input) is not in this change.

The spec and ADR-0050 are on this branch, not at the gate base, so they are not cited as sources.
The entries cite the owner-adopted scenarios and the owner's delegation for the agent's choices.

Evidence files (all written by run-evidence.mjs):

- `b6d536-bulk-gate-repaired.json`: repaired behaviour, failing by assertion on 5f91835f3.
- `b6d536-bulk-gate-preservation.json`: checks that pass on 5f91835f3 and on the change. That run
  exits 1 by design, because none of them shows a repair. In the run regenerated on b599d4d7e,
  EnergyStatePollerTest's later method energyStatusChangesAdvanceCounterButReadsAndRepeatedPollsDoNot
  (added by ea0c311cb, b6d536-b64-status-counter) fails on 5f91835f3 by assertion; it was reported
  to the coordinator, not changed.
- `b6d536-bulk-gate-new.json`: checks whose expectations use API added in this change, so they
  have no before-state.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingIndexingIdleTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingStateAssemblerTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Through the production OperatingStateAssembler with status views built only from API that exists on 5f91835f3: (a) E-IDLE row 1: an empty job queue with embedding enabled and 3,000 documents pending embedding reads RUNNING, not IDLE; before the change it read IDLE because IDLE looked at pendingJobs only. (b) In OperatingStateAssemblerTest#indexingProjection, indexing paused for energy saver now carries the reasonCode indexing.paused_energy_saver; before the change the reasonCode was null. That one assertion replaces 'assertNull(paused.reasonCode(), \"no lifecycle code exists for this reason yet\")'; every other assertion of the method, IDLE with nothing pending, RUNNING with work, PAUSED [energy_saver] with affects INDEXING, and IDLE with the energy reason still listed, is unchanged. OperatingStateAssemblerTest is named as a whole class because its methods carry display names, which the evidence runner cannot select by method; on 5f91835f3 only indexingProjection fails, by assertion, and the other cases pass.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S20",
          "quote": "The GPU, chat, indexing and lifecycle states the UI shows are the ones dev tools and evaluation scripts read.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5 (GPU switches and profiles)",
          "choice": "IDLE needs an empty queue and, for each enrichment stage that is enabled and available (embedding with its chunk count, SPLADE or BGE-M3 sparse with its chunk count, NER, VDU), nothing pending. A status view that does not report availability is read as available, so a reported backlog is never hidden. The energy-saver reason code is indexing.paused_energy_saver, set when the block is PAUSED with energy saver as its primary reason; it is TRANSIENT and never enters the readiness envelope, so it is registered as exempt from readiness wording.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-repaired.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingIndexingIdleTest.java#enabledEmbeddingBacklogIsNotIdle",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingStateAssemblerTest.java"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingIndexingIdleTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/power/EnergyStatePollerTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation, passing on 5f91835f3 and on the change. E-IDLE row 2: an empty queue with 3,000 documents pending embedding but embedding disabled reads IDLE. E-P7a: the existing energy poller checks are unchanged and pass; the poll still writes the GPU-scheduling gauge, which still yields GPU backfill for energy alone.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P7",
          "quote": "Energy-saver indexing pause and replacement admission keep their behaviour.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-preservation.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingIndexingIdleTest.java#disabledEmbeddingBacklogIsIdle",
        "modules/app-services/src/test/java/io/justsearch/app/services/power/EnergyStatePollerTest.java"
      ]
    },
    {
      "items": [
        "modules/core/src/test/java/io/justsearch/core/scheduling/BulkWorkPolicyTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingIndexingAvailabilityTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/status/operating/BulkWorkVocabularyMirrorTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/power/EnergyStatePollerBulkWorkMirrorTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusMapperStageAvailabilityTest.java"
      ],
      "class": "New",
      "behaviour": "Switch A and its status. (a) E-U1, BulkWorkPolicy: on P16_PLUS, P12 and P8 a game or external memory pressure holds bulk work (PAUSED [game_mode] or [gpu_busy_external]); chat generating puts bulk on the duty floor (SLOWED [chat_generating]) at 50% with a 1 s debt cap; reasons accumulate in precedence order and the mode is the strongest present. NO_GPU never holds and keeps today's 20% duty and 2 s cap; UNRESOLVED keeps today's duty. E-P7d (gate half): on every profile energy saver reads PAUSED [energy_saver] and held() is false. BulkWorkGate starts UNRESOLVED and RUN, answers from the policy, resumes when the input clears, and moves since only on a change of mode or reasons. (b) The energy poll mirrors into the gate's verdict on every poll and at once when mirroring starts; the gate never holds for it, and the gauge still yields for energy. (c) E-IDLE rows 3 and 4 through the production assembler: embedding enabled but unavailable reads IDLE; 50 pending VDU with VDU unavailable reads IDLE, and RUNNING when available; each enabled and available stage's backlog (chunk embedding, chunk sparse, document sparse, NER) holds IDLE off and a disabled or unavailable one does not. With the gate wired: chat generating with work left reads SLOWED [chat_generating] affecting INDEXING and resumes to RUNNING; a game with energy saver and generation reads PAUSED with all three reasons in precedence order; energy saver comes from the pass's power view only; an unreadable gate leaves the block RUNNING. (d) The gate's reason and profile enums mirror IndexingReason and Profile by name and order. (e) The Head's worker status mapping carries embeddedAndSparseCount and the three availability booleans field for field.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P7",
          "quote": "Energy-saver indexing pause and replacement admission keep their behaviour.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P2",
          "quote": "Without a usable NVIDIA GPU, keyword and CPU semantic search keep working.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S20",
          "quote": "The GPU, chat, indexing and lifecycle states the UI shows are the ones dev tools and evaluation scripts read.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5 (GPU switches and profiles)",
          "choice": "BulkWorkGate lives in core beside GpuSchedulingGauge, one per Engine, owned by KnowledgeServerBootstrap; core has no project dependencies, so it declares its own Reason and Profile enums mirroring IndexingReason and Profile, pinned by a test. The hold rule is the same on every profile except NO_GPU (Spec E 1.2), so UNRESOLVED can hold, but only a known GPU profile gets the 50% generation duty; UNRESOLVED and NO_GPU keep today's 20% and 2 s. The operating block takes energy saver from the same pass's power view, so it agrees with the power card, and every other reason from the gate. Stage availability comes from BackfillScheduler.stageAvailability(), the same checks the combined backfill pass uses; VDU availability is computed on the Head from a wired VDU coordinator, no blocked capability reason and a vision-capable chat runtime.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-new.json",
      "newChecks": [
        "modules/core/src/test/java/io/justsearch/core/scheduling/BulkWorkPolicyTest.java#gpuProfileRows",
        "modules/core/src/test/java/io/justsearch/core/scheduling/BulkWorkPolicyTest.java#energyIsStatusOnly",
        "modules/core/src/test/java/io/justsearch/core/scheduling/BulkWorkPolicyTest.java#reasonsAccumulate",
        "modules/core/src/test/java/io/justsearch/core/scheduling/BulkWorkPolicyTest.java#noGpu",
        "modules/core/src/test/java/io/justsearch/core/scheduling/BulkWorkPolicyTest.java#unresolved",
        "modules/core/src/test/java/io/justsearch/core/scheduling/BulkWorkPolicyTest.java#gateDefaultsAndActuation",
        "modules/core/src/test/java/io/justsearch/core/scheduling/BulkWorkPolicyTest.java#sinceTracksTransitions",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingIndexingAvailabilityTest.java#unavailableEmbeddingBacklogIsIdle",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingIndexingAvailabilityTest.java#vduBacklogCountsOnlyWhenAvailable",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingIndexingAvailabilityTest.java#eachStageBacklog",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingIndexingAvailabilityTest.java#generatingSlows",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingIndexingAvailabilityTest.java#holdPausesAndReasonsAccumulate",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingIndexingAvailabilityTest.java#energyFromPowerView",
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingIndexingAvailabilityTest.java#unreadableGate",
        "modules/app-api/src/test/java/io/justsearch/app/api/status/operating/BulkWorkVocabularyMirrorTest.java#reasons",
        "modules/app-api/src/test/java/io/justsearch/app/api/status/operating/BulkWorkVocabularyMirrorTest.java#profiles",
        "modules/app-services/src/test/java/io/justsearch/app/services/power/EnergyStatePollerBulkWorkMirrorTest.java#pollMirrorsIntoTheGate",
        "modules/app-services/src/test/java/io/justsearch/app/services/power/EnergyStatePollerBulkWorkMirrorTest.java#mirrorPublishesTheCurrentState",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusMapperStageAvailabilityTest.java#mapsFieldForField"
      ]
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/EmbeddedAndSparseCountTest.java"
      ],
      "class": "New",
      "behaviour": "E-U9, the S5 unit. Over a real Lucene index of seven whole documents and one chunk, IndexCountOps.queryEmbeddedAndSparseCount() counts only whole documents whose embedding is COMPLETED and whose sparse status is COMPLETED or COMPLETED_EMPTY (2), which differs from the embedding count (5) and the sparse count (3), so it cannot be derived from them; an empty index counts 0.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S5",
          "quote": "Under a constant stream of agent searches with chat generating, indexing still progresses at no less than a minimum rate set from measurement M1 (ADR-0048 duty floor).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5 (GPU switches and profiles)",
          "choice": "The S5 rate counts whole documents (not chunks) with a completed embedding and a completed or completed-empty sparse encoding, as one Lucene count published on the status enrichment block as embeddedAndSparseCount.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-new.json",
      "newChecks": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/EmbeddedAndSparseCountTest.java"
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
  "session": "a3a46d49-5d40-43d9-a1ad-d9e9e536d01b",
  "verdict": "accept",
  "digest": "sha256:e4a9f3f3033c2d3490140a07ae1a426d5e8a9e194ad395ffc49778ffb92e7829"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-batch1-20261008-independent",
  "verdict": "accept",
  "digest": "sha256:e4a9f3f3033c2d3490140a07ae1a426d5e8a9e194ad395ffc49778ffb92e7829"
}
```
