---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, item 5, Spec E step E2, on b6d536/bulk-gate-2 (base 429efb084, the merge of
the bulk-gate E0b/E1 commit with chat-controller steps 2-6). Chat generation now reaches indexing
pacing through Switch A: the chat server's generation signal (GenerationActivity) drives the
Engine's one BulkWorkGate; IndexingPacing reads the gate's duty floor as its second input
(effective duty = search duty while search is busy, else the generation duty while chat generates,
else 100); generation no longer enters the search gauge (ForegroundLoadGate); the operating
indexing block carries `indexing.slowed_chat_generating` when SLOWED.

Delegated choices recorded here: (1) UNRESOLVED keeps the hold and the duty floor like every other
profile with a GPU path (Spec E 1.2 "same on every profile"), and with today's 20% duty and 2 s cap
while generating; (2) every GENERATE lease counts as generating, foreground or background (ruling
F1). The spec's exclusion of ASSIST and VDU leases is not implemented: the chat server's tracker has
no lease kind for them, so query-understanding and VDU generations also slow indexing. That case is
unverified and open.

Evidence files (all written by run-evidence.mjs):

- `b6d536-bulk-gate-e2-repaired.json`: repaired behaviour, failing by assertion on 429efb084.
- `b6d536-bulk-gate-e2-preservation.json`: passes on 429efb084 and on the change; exits 1 by
  design because it shows no repair.
- `b6d536-bulk-gate-e2-new.json`: checks that construct IndexingPacing with the generation input
  added in this change, so they have no before-state.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/ChatGenerationSlowsIndexingTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "E-S7a and E-S7b, composed as the Engine composes them (Engine root, the inference manager from BootstrapInferenceFactory, a stand-in llama-server streaming an answer, and the KnowledgeServerBootstrap whose bulk-work verdict the operating status block reads). While a chat answer streams, the verdict is DUTY_FLOOR with the single reason CHAT_GENERATING within 4 s; within 4 s of the answer's end it is RUN with no reasons. The same holds for a background-urgency generation. Before the change the verdict stayed RUN, because nothing fed the gate's generation input.",
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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5 (GPU switches and profiles)",
          "choice": "The chat server's GenerationActivity (open GENERATE leases) is the gate's generation input, for every urgency (ruling F1). The 4 s bound is the spec's E-S7a window.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e2-repaired.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/ChatGenerationSlowsIndexingTest.java#chatAnswerSlowsIndexingUntilItEnds",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/ChatGenerationSlowsIndexingTest.java#backgroundGenerationSlowsIndexingToo"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootInProcessPortsTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Changed intent of generationKeepsIndexingPacedAfterRetrievalAndReleasesOnCancellation and generationStartedBeforeIndexCompositionIsPacedUntilProducerExit (Spec E section 8). The old oracle was the search gauge: a foreground generation added one in-flight and one started foreground call to ForegroundLoad, a fresh IndexingPacing(load, 20, 0) read busy only for foreground generation and idle for background, and the composed pacing read busy. The new oracle is the composed IndexingPacing.foregroundBusy() through BulkWorkGate.dutyFloor(): generation adds nothing to ForegroundLoad (in-flight 0, started count unchanged); after the retrieval cooldown has passed, the composed pacing reads busy while any generation streams, foreground, background or detached, and reads idle again after the producer exits, on completion and on cancellation. The case of a generation that began before index composition is kept: the pacing composed later still sees it. Every other assertion (retrieval counting once, the terminal outcome, cancellation, the drain, upstream outliving startup, the exited producer not resurrected) is unchanged. On 429efb084 each scenario fails by assertion: foreground generations on the in-flight count, the background one on the pacing reading idle. The parameterized method's invocation name pattern now starts with the method name, only so the evidence runner can select it; its values are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5 (GPU switches and profiles)",
          "choice": "Ruling F1: generation of every urgency holds the duty floor, so the background scenario now paces; generation leaves the search gauge, so the search duty governs only search.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e2-repaired.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootInProcessPortsTest.java#generationKeepsIndexingPacedAfterRetrievalAndReleasesOnCancellation",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootInProcessPortsTest.java#generationStartedBeforeIndexCompositionIsPacedUntilProducerExit"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingIndexingAvailabilityTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "generatingSlows: indexing SLOWED [chat_generating] now carries the reasonCode indexing.slowed_chat_generating. That one assertion replaces 'assertNull(slowed.reasonCode(), \"the chat-generating code lands with its producer in E2\")'; every other assertion of the method (SLOWED, the single reason, affects INDEXING, resuming to RUNNING) is unchanged. Before the change the reasonCode was null.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5 (GPU switches and profiles)",
          "choice": "The SLOWED code is indexing.slowed_chat_generating, TRANSIENT, outside the readiness envelope and registered as exempt from readiness wording, like indexing.paused_energy_saver.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e2-repaired.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingIndexingAvailabilityTest.java#generatingSlows"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/CpuChatPacesIndexingTest.java"
      ],
      "class": "New",
      "behaviour": "E-P14, preservation, passing on 429efb084 and on the change: with chat on the CPU and no GPU profile known, a streaming answer makes the Engine's composed indexing pacing busy, and a 1 s indexing work slice then yields today's capped debt of 2 s (20% duty, 2 s cap), measured on the wall clock between 1.9 and 3 s.",
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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5 (GPU switches and profiles)",
          "choice": "NO_GPU and UNRESOLVED keep today's search-load duty (20%) and 2 s debt cap while chat generates; only a known GPU profile gets 50% and 1 s.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e2-preservation.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/CpuChatPacesIndexingTest.java#cpuChatPacesIndexingAtTodaysDuty"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/pacing/IndexingPacingGenerationDutyTest.java"
      ],
      "class": "New",
      "behaviour": "E-U2, the duty arithmetic, with injected clocks and sleep. GPU profile, generating, no search: 400 ms of work yields 400 ms (50%) and a 1 s slice yields the 1 s cap. NO_GPU and UNRESOLVED while generating: 100 ms yields 400 ms (20%) and a 1 s slice the 2 s cap, as today. Search and generation together: the search duty (20%) and its 2 s cap govern. Neither input: no yield. Generation ending mid-yield releases after one 100 ms chunk. A search ending mid-yield while chat still generates leaves at most the generation cap (1 s) after the first chunk.",
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
          "scenario": "S5",
          "quote": "Under a constant stream of agent searches with chat generating, indexing still progresses at no less than a minimum rate set from measurement M1 (ADR-0048 duty floor).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5 (GPU switches and profiles)",
          "choice": "One governor: effective duty = search duty while search is busy, else the generation duty while the duty floor holds, else 100; the debt cap follows the same choice; each 100 ms yield chunk re-reads both inputs and re-caps the remaining debt.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e2-new.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/pacing/IndexingPacingGenerationDutyTest.java#generationOnAGpuProfileRunsAtFiftyPercent",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/pacing/IndexingPacingGenerationDutyTest.java#generationOffAGpuProfileKeepsTodaysDuty",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/pacing/IndexingPacingGenerationDutyTest.java#searchDutyGovernsWhenBothAreBusy",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/pacing/IndexingPacingGenerationDutyTest.java#noInputNoYield",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/pacing/IndexingPacingGenerationDutyTest.java#generationEndingMidYieldReleases",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/pacing/IndexingPacingGenerationDutyTest.java#searchEndingMidYieldDropsToTheGenerationCap"
      ]
    }
  ]
}
```

## Acceptance records
