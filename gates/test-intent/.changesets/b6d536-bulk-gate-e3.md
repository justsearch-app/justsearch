---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, item 5, Spec E step E3, on b6d536/bulk-gate-2 (base 124def8a1, the E2
commit). The Engine now owns the graphics memory reader through a GpuResidencySampler (every 2 s,
fixed delay). External use is adapter use minus our own processes' dedicated use, read per process
from PDH only (CH11); the pressure machine raises after 3 low samples (free under 512 MiB and
external over 1,536 MiB) and clears after 5 clear samples or 30 s without a known PDH sample. The
sampler sets the Engine's BulkWorkGate external pressure and reports EXTERNAL_MEMORY / NONE to the
chat controller. The worker bus folds the gate's hold into shouldYieldGpuBackfill() and exposes
isBulkHeld(); the loop sites of Spec E section 1.3 items 2-6 read it (backfill cycle entry, the
individual passes, inline primary embedding, the batch-embed decision, VDU trigger and
interrupt). The operating indexing block carries indexing.paused_gpu_busy for PAUSED
[gpu_busy_external]. The status projection reads the sampler's latest reading instead of opening
its own reader.

Delegated choices recorded here: (1) UNRESOLVED holds like every profile with a GPU path; only
NO_GPU never holds. (2) The reader is opened without a pinned adapter; pinning by LUID is E6.
(3) Closing the sampler clears a pressure it published. (4) BGE-M3 processes one batch per call,
so the E-S7c raise for that path lands between the batch's content fetch and its encode, and the
oracle is that the encode does not start.

Evidence files (all written by run-evidence.mjs):

- `b6d536-bulk-gate-e3-repaired.json`: repaired behaviour, failing by assertion on 124def8a1. The
  before-state carries the two original E3 sampler classes (GpuResidencySampler,
  ExternalPressureMachine) from fbc036129 in the digest-bound add-only patch
  `gates/test-intent/evidence/b6d536-e3-historical-sampler-compat.patch`, so E-S7c's pressure source
  exists there. There are no current-source overlays and no game/context APIs or stubs. The patch
  adds neither a loop-site hold check nor a status reason; nothing on 124def8a1 reads the gate's hold
  at the loop sites, so every combined batch starts (6 instead of 1) and the BGE-M3 encode runs.
  Separately, a mutation run on the candidate with only the bus fold `|| isBulkHeld()` removed
  from WorkerSignalBus.shouldYieldGpuBackfill failed both E-S7c checks by assertion (temporary
  worktree, removed after the run; not a run-evidence file).
- `b6d536-bulk-gate-e3-preservation.json`: passes on 124def8a1 and on the change; exits 1 by
  design because it shows no repair.
- `b6d536-bulk-gate-e3-new.json`: checks of classes and signatures added in this change.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/ExternalPressureHoldsBackfillTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "E-S7c. The real BackfillScheduler.runIdleCycle over the production InProcessWorkerSignalBus built over a P12 BulkWorkGate, with the real GpuResidencySampler on a manual scheduler reading PDH samples of 300 MiB free and 6 GB used by other processes. Ticks 1 and 2 land before the cycle, tick 3 (the raise) inside the first batch. Case A (combined SPLADE+NER cycle): the in-flight batch finishes and no further batch starts (1 batch start). Case B (BGE-M3): the raise lands between the batch's content fetch and its encode, and the encode does not start. In both, the bus yields and the gate's verdict is HOLD for the single reason GPU_BUSY_EXTERNAL (what the status block shows as PAUSED [gpu_busy_external]). After 4 clear samples it still yields; after the 5th it does not, the verdict is RUN, and the next cycle starts batches (and the BGE-M3 encode runs). On 124def8a1 with the original E3 sampler classes added by the digest-bound compatibility patch, Case A started 6 batches and Case B encoded once, failing by assertion.",
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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5 (GPU switches and profiles)",
          "choice": "BGE-M3 processes one batch per call, so the raise for that path lands between the content fetch and the encode, and the oracle is that the encode does not start; the original E3 sampler classes are added to the before-state by the digest-bound compatibility patch so the pressure source exists there.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e3-repaired.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/ExternalPressureHoldsBackfillTest.java#combinedCycleStartsNoBatchAfterTheRaise",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/ExternalPressureHoldsBackfillTest.java#bgeM3BatchDoesNotEncodeAfterTheRaise"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingIndexingGpuBusyTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "S7 status half: with a P12 gate under external pressure, the operating indexing block is PAUSED with the single reason GPU_BUSY_EXTERNAL, affects INDEXING, and carries the reasonCode indexing.paused_gpu_busy; once the pressure clears it is RUNNING with no reasons. On 124def8a1 the reasonCode was null, failing by assertion.",
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
          "choice": "The PAUSED code is indexing.paused_gpu_busy, TRANSIENT, registered in the readiness reason-code registry like indexing.paused_energy_saver.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e3-repaired.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/OperatingIndexingGpuBusyTest.java#externalUsePausesWithTheGpuBusyCode"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/InlineEmbeddingBulkHoldTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Spec E section 1.3 item 3 and E-P7d's inline half, through IndexingDocumentOps.buildDocument with the production InProcessWorkerSignalBus over the gate and an available embedding provider. heldBulkWorkDefersInlineEmbedding (repaired): with the P12 gate under external pressure, no inline embed starts and the document's embedding status is PENDING with no vector; on 124def8a1 the inline embed ran once, failing by assertion. energySaverStillEmbedsInline (preservation, passing on 124def8a1 and on the change): with energy saver on, the gate's hold is false, its only reason is ENERGY_SAVER, and the inline embed runs once, giving status COMPLETED and a vector.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e3-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/InlineEmbeddingBulkHoldTest.java#heldBulkWorkDefersInlineEmbedding"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/InlineEmbeddingBulkHoldTest.java"
      ],
      "class": "New",
      "behaviour": "E-P7d inline half, preservation: energy saver is status only, so the inline primary embedding still runs while it is on (see the entry above for the full description).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e3-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/InlineEmbeddingBulkHoldTest.java#energySaverStillEmbedsInline"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineGpuResidencyWitnessTest.java"
      ],
      "class": "New",
      "behaviour": "E-S7w and the section 3.4 chat input, as the Engine composes them. enginePressureMakesTheComposedBusYield: an EngineRoot with the production server factory, its gate on P12 and its own sampler over a fake PDH reader on a manual scheduler; after start, the worker bus the Engine composed (read from the KnowledgeServer) does not yield; after 3 low samples the sampler reports pressure, the bus is held and yields, and the receiver bound through the Engine's executors hears EXTERNAL_MEMORY; after 5 clear samples it no longer yields and the receiver has heard EXTERNAL_MEMORY then NONE; closing the process resources closes the reader. chatControllerHearsExternalMemoryPressure: the chat controller that BootstrapInferenceFactory installs over the Engine's executors reports NONE, then EXTERNAL_MEMORY after the raise, then NONE after the clear.",
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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5 (GPU switches and profiles)",
          "choice": "The Engine owns the sampler and its scheduler and closes both with its process resources; the reader opens without a pinned adapter (pinning by LUID is E6); a receiver bound after a raise hears the raised pressure.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e3-new.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineGpuResidencyWitnessTest.java#enginePressureMakesTheComposedBusYield",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineGpuResidencyWitnessTest.java#chatControllerHearsExternalMemoryPressure"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/ExternalPressureMachineTest.java"
      ],
      "class": "New",
      "behaviour": "E-U3, the pressure machine. Raises on the third consecutive sample with free under 512 MiB and external use over 1,536 MiB, not before. External use of exactly 1,536 MiB, or free of exactly 512 MiB, does not raise. A sample meeting neither the raise nor the clear condition breaks a raise run. Clears on the fifth consecutive sample with free at least 768 MiB or external use at most 1,024 MiB, not before. Unknown samples freeze both counters. A raised pressure clears 30 s after the last known sample, not before.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e3-new.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/ExternalPressureMachineTest.java#raisesOnTheThirdConsecutiveLowSample",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/ExternalPressureMachineTest.java#externalUseAtTheAllowancePlusMarginDoesNotRaise",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/ExternalPressureMachineTest.java#freeAtTheMarginDoesNotRaise",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/ExternalPressureMachineTest.java#anInBetweenSampleBreaksTheRaiseRun",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/ExternalPressureMachineTest.java#clearsOnTheFifthConsecutiveClearSample",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/ExternalPressureMachineTest.java#unknownSamplesFreezeTheCounters",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/ExternalPressureMachineTest.java#raisedPressureClearsAfterThirtySecondsWithoutAKnownSample"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuResidencySamplerTest.java"
      ],
      "class": "New",
      "behaviour": "E-U3, the sampler. External use (another process's memory, PDH) raises the gate's external pressure on the third tick, holds it, reports EXTERNAL_MEMORY, then clears and reports NONE. CH11: our own processes' dedicated use (the Engine and the llama child) cannot raise, however little memory is free. An NVML reading with 300 MiB free does not raise, and neither does a PDH reading without per-process figures. Unknown readings clear a raised pressure 30 s after the last known one (held at 29 s, cleared at 31 s). A receiver bound after the raise hears it. The status reader returns the latest reading and closing it does not close the sampler's reader. The sampler schedules every 2 s with a fixed delay.",
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
          "choice": "Our processes are the Engine and its descendants (the llama child); closing the sampler clears a pressure it published.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e3-new.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuResidencySamplerTest.java#externalUseRaisesHoldsAndReportsThenClears",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuResidencySamplerTest.java#ourOwnUseCannotRaise",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuResidencySamplerTest.java#nvmlAtThreeHundredMibFreeDoesNotRaise",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuResidencySamplerTest.java#pdhWithoutProcessFiguresDoesNotRaise",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuResidencySamplerTest.java#unknownReadingsClearRaisedPressureAfterThirtySeconds",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuResidencySamplerTest.java#aLateReceiverHearsRaisedPressure",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuResidencySamplerTest.java#statusReadsTheLatestReadingWithoutOwningTheReader",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuResidencySamplerTest.java#schedulesEveryTwoSecondsWithAFixedDelay"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BulkHoldKeepsQueryOnGpuTest.java"
      ],
      "class": "New",
      "behaviour": "E-CH12a: a held bulk gate (P12, external pressure) makes the production bus held and yielding, but it is not a Main GPU claim: isMainGpuActive stays false, the query-embedding GPU arbiter still allows the GPU, and the embedding lifecycle's GPU transition releases no GPU session. Control: a real Main GPU claim does release the session once.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e3-new.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BulkHoldKeepsQueryOnGpuTest.java#heldBulkWorkKeepsTheQueryEmbedOnGpuAndReleasesNothing"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/LoopPacingBulkHoldTest.java"
      ],
      "class": "New",
      "behaviour": "Spec E section 1.3 item 2: with the hold raised, backfill cycle entry is skipped and a running pass is interrupted when embeddings are on the GPU, and entry is still skipped when only the sparse or NER encoder is on the GPU; with every bulk encoder on the CPU the hold does not defer, and with the hold clear the cycle runs.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e3-new.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/LoopPacingBulkHoldTest.java#heldGpuEmbeddingSkipsTheCycle",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/LoopPacingBulkHoldTest.java#heldSparseOrNerOnGpuSkipsTheCycle"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduPacingBulkHoldTest.java"
      ],
      "class": "New",
      "behaviour": "Spec E section 1.3 item 6: VDU is bulk GPU work. A held gate defers the VDU trigger even when idle, on full power and with the LLM offline, and interrupts a running VDU batch; with the hold clear both decisions are as before.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-bulk-gate-e3-new.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduPacingBulkHoldTest.java#heldGateDefersTrigger",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduPacingBulkHoldTest.java#heldGateInterruptsBatch"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/LoopPacingPolicyTest.java"
      ],
      "class": "Adaptation",
      "moved": ["signature"],
      "whatMoved": "LoopPacingPolicy.shouldRunBackfill and shouldInterruptBackfill gained two inputs, bulkHeld and otherBulkEncoderOnGpu. Every CPU-provider row now runs with the hold raised (a HELD constant) and no other bulk encoder on the GPU, and keeps its expected value, which pins E-P2a (CPU bulk is not deferred by the hold). Every GPU-provider row runs with the hold clear and no other bulk encoder on the GPU, so its oracle is the one it had. The gauge-driven helper passes the hold for CPU rows only. No input on the original parameters and no expected value changed; the class comment explains the adaptation. The held GPU rows are the new LoopPacingBulkHoldTest."
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduPacingPolicyTest.java"
      ],
      "class": "Adaptation",
      "moved": ["signature"],
      "whatMoved": "VduPacingPolicy.shouldTrigger and shouldInterrupt gained a bulkHeld input. Every call passes bulkHeld = false, so every oracle is unchanged. The compile-time pin in interruptHasNoLlmOnlineParameter now names shouldInterrupt(long, boolean, boolean bulkHeld) and says its third input is the bulk work gate's hold, never an LLM-online signal; its assertion is unchanged. The held rows are the new VduPacingBulkHoldTest."
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
  "digest": "sha256:2bde07be7161037e0598e9a37fd8146ba3331630714eda458b8cdb44bc874c98"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-batch1-20261008-independent",
  "verdict": "accept",
  "digest": "sha256:2bde07be7161037e0598e9a37fd8146ba3331630714eda458b8cdb44bc874c98"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS1-20261015",
  "verdict": "reject",
  "digest": "sha256:82adf49b32200ee08e942aba87be73cc8dbc9a0dbb9f6bfd1ce0496406a1b187",
  "note": "Reject the repaired before evidence: exact 124def8a1 plus the two declared current sampler overlays fails app-services compilation with ten errors for absent context/game APIs and GAME_AND_EXTERNAL_MEMORY, so all three checks execute zero cases instead of the recorded assertions. Candidate checks pass, but the declared before replay does not reproduce its evidence."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS4c-20261015",
  "verdict": "accept",
  "digest": "sha256:83302eee852017765faf9274957c94a1dac696d88dd541efe6ff6f404412151e",
  "note": "Reviewed the correction against VS1: the patch adds exactly the two historical sampler classes, without stubs or a behavior replacement. Four candidate checks pass and the 124def8a1 rerun reproduces all four declared assertion failures and reasons."
}
```
