---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, item 5a, Spec D rev 2 steps 8 and 9: the device encoder gate and bounded
GPU release (§2.3, §3.2), and pace points every second of bulk GPU work (§3.3). One gate per CUDA
device, shared by every GPU session handle on it, replaces the per-handle semaphore: a query is
seated before queued bulk work on the same device, bulk work is still seated by aging, and a
caller that leaves at its deadline leaves the queue. A GPU release no longer waits forever for a
stuck run. The combined backfill and the idle SPLADE backfill pace every bulkPhaseDocs documents
and at every phase boundary.

ADR-0050 decision 2 ("about 1 s" of bulk GPU work between yields; one gate per device) records
this design, but it is added in this branch (not at the gate base), so it is not cited as a
source. The entries cite the owner-adopted scenarios, and the owner's delegation for the agent's
specific choices.

The slice also carries two checks from a diagnosis of the 900bdf4a0 verification (429
ADMISSION_ENGINE_LIMIT under 8 hybrid clients): engine admission slots are not leaked by stalled
runs, and the ninth concurrent hybrid search is refused by the 8-thread inference lane, not by
admission. The lane's size and refusal code are unchanged here (an owner question).

Bulk pacing counts the time a bulk slice waited for a device seat as time it yielded, not as GPU
work (Spec D §3.3, "pace points every ≤ bulkPaceTargetMs of GPU work"); before that, pacing and the
gate's aging each took the foreground's share.

Spec D test P5a (BulkDutyFloorUnderDeviceGateTest) asserts the bulk share the gate's aging rule
guarantees, derived from DeviceGatePolicy, instead of the spec's 20% (see its entry). It is a
regression guard: its fail-before is a mutation (aging disabled), not a before-state run.

Spec D step 10 adds the device breaker and its standing state (§2, §6.3): while a GPU run that is
not its session's first has held its seat past stuckAfterMs, a foreground query leg with a keyword
fallback is turned away at once with SEMANTIC_BUDGET_EXCEEDED (cause stuck), and a breaker open for
breakerStandingMs is reported as encoders.health.semantic_stuck. The same step corrects a rerank
that got no session within its own budget: it was reported as RPC_FAILED, and is now a budget skip.

Evidence files (all written by run-evidence.mjs):

- `b6d536-search-gate-repaired.json`: G1, K5 and G5, with an assertion fail-before on 7d928036e.
- `b6d536-search-gate-admission-repaired.json`: the admission-slot check, with an assertion
  fail-before on 68547f75a.
- `b6d536-search-gate-preservation.json`: the inference-lane check, passing on 7d928036e and on the
  change. That run exits 1 by design, because it shows no repair.
- `b6d536-search-gate-new.json`: checks of new API (G2, G3, G6, the parameter-use check, the
  gate-wait pacing check and P5a), no before-state.
- `b6d536-search-gate-breaker-repaired.json`: K1 (with K2's next search and the P2 guard), the
  rerank acquisition skip and the K3 consequence check, with an assertion fail-before on 342da6779.
- `b6d536-search-gate-breaker-new.json`: checks of new API (K2 and K4 on the gate, the turned-away
  scope, the K3 readiness producer path), no before-state.

```json
{
  "entries": [
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleDeviceGateTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "G1 (two real NativeSessionHandles on one CUDA device, GPU sessions mocked): while a background lease holds the device, three background calls (two on the bulk handle, one on the query handle) and then one foreground query call queue; no other run is seated while the held seat lasts, and when it is released the foreground query is seated first of the four. On 7d928036e each handle had its own semaphore, so a background call and the query on the second handle were seated at once while the first handle's seat was held (expected [] but was [bulk-2, query]).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S4",
          "quote": "A search waits at most about one second for any encoder on the device; foreground is served before waiting bulk work.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "One device gate per CUDA device, shared by every GPU session handle on it (CPU is not gated), with one seat; a session never holds two seats. A freed seat goes to the oldest waiting foreground call whose session is free, else the oldest background call. The oldest background waiter is seated ahead of foreground after 4 foreground seatings since it queued or after 2000 ms of waiting; the foreground calls it passes are not passed again while they wait. The gate never refuses: a caller leaves only at its own deadline (phase GATE, cause contended), on cancellation or on interrupt.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-gate-repaired.json",
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleDeviceGateTest.java#aQueryFromAnotherHandleIsSeatedBeforeQueuedBulkOnTheSameDevice"
      ]
    },
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleDeviceGateTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "K5 (real NativeSessionHandle, GPU session mocked, its run blocked on a latch): releaseGpu returns while the run is stuck, without closing the session; a foreground call on another handle of the same device still waits for the seat and leaves at its 200 ms deadline with GATE; once the run returns, its lease release closes the retired session, and the other handle is then seated on the GPU. On 7d928036e releaseGpu blocked in acquireUninterruptibly (NativeSessionHandle:446) for as long as the run was stuck; the check fails by its 15 s preemptive timeout assertion and then frees the run so the build does not hang.",
      "sources": [
        {
          "kind": "defect-source",
          "safeguard": "no hang",
          "reproduction": "With a GPU run blocked in OrtSession.run, call NativeSessionHandle.releaseGpu() on 7d928036e: it waits in Semaphore.acquireUninterruptibly (NativeSessionHandle.java:446) until the run returns, so a GPU release (switching chat on, a lifecycle change) hangs for as long as the native run is stuck."
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S4",
          "quote": "A search waits at most about one second for any encoder on the device; foreground is served before waiting bulk work.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "A GPU release waits at most recompositionWaitMs (10 000 ms), interruptibly, for the session's in-flight runs. Past that the session is retired to its run: the handle drops it at once, and the last lease release closes it (lifecycle callback, run options, then the session) before freeing the device seat. Until then its seat still counts against the device.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-gate-repaired.json",
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleDeviceGateTest.java#releaseGpuWithAStuckRunReturnsAndTheRunClosesTheSessionLater"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BulkPaceStretchTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "G5 (fake encoders that advance a fake clock by measurement M5's per-document costs: embedding 7.3 ms, SPLADE 13.3 ms, NER 5.1 ms; IndexingPacing reads the same clock, so every pace() call is recorded at its time). The product's default combined backfill batch (100 parents with embedding, SPLADE and NER pending, 50 chunk docs) and the idle SPLADE backfill's default 200-document batch each run at most 1000 ms of encoder work between two pace() calls, and every document is still processed. On 7d928036e the longest stretch was 2935 ms (combined, one pace() per batch) and 2660 ms (SPLADE, one encode call).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S4",
          "quote": "A search waits at most about one second for any encoder on the device; foreground is served before waiting bulk work.",
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
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "A bulk producer runs at most bulkPaceTargetMs (1000 ms) of GPU work between two pace() calls. The combined backfill paces at every phase boundary (embedding, SPLADE, NER) and every bulkPhaseDocs documents within a phase (SPLADE 64, embedding 128 documents or encoder windows, NER 192, from M5's per-document costs); its SPLADE phase is encoded in 64-document calls. The idle SPLADE backfill (200 documents a batch) is encoded in 64-document calls with a pace point between them. The other bulk loops stay as they are: at M5's costs their batches are under the target.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-gate-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BulkPaceStretchTest.java#combinedBackfillPacesAtLeastEverySecondOfGpuWork",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BulkPaceStretchTest.java#spladeBackfillPacesAtLeastEverySecondOfGpuWork"
      ]
    },
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/DeviceEncoderGateTest.java",
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleGateWaiterRemovalTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BulkPacePolicyUseTest.java"
      ],
      "class": "New",
      "behaviour": "Checks of the new gate and pace policy, whose API does not exist before the change. G2 (DeviceEncoderGate with a fake clock, 400 seats of 50 ms, one bulk producer and a foreground stream): every foreground wait is at most the seated remainder, one aged background run and the foreground calls queued ahead of it. G3 (aging by wall time only, two bulk producers): each foreground call is overtaken at most once, bulk is seated at least once per agingWallMs, and no background wait exceeds agingWallMs plus four runs. G6 (two real handles on one device): a foreground query queued behind a 1 s bulk run leaves at its 200 ms deadline with GATE, the device has no waiter left, the query's session never runs, and the seat the bulk run frees is taken by the next caller. BulkPacePolicyUseTest: with a policy of SPLADE 5, embedding 24 and NER 7 documents, at most that many documents of each phase run between two pace() calls, each limit is reached, and no interval mixes two phases.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S4",
          "quote": "A search waits at most about one second for any encoder on the device; foreground is served before waiting bulk work.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "One device gate per CUDA device with one seat. A freed seat goes to the oldest waiting foreground call whose session is free, else the oldest background call. The oldest background waiter is seated ahead of foreground after 4 foreground seatings since it queued or after 2000 ms of waiting; the foreground calls it passes are not passed again while they wait. A caller leaves only at its own deadline (GATE), on cancellation or on interrupt, and is removed from the queue under the gate's lock; a seat granted in the same instant is released at once. The pace slice of each bulk phase is the bulkPhaseDocs value its parameter names.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-gate-new.json",
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/DeviceEncoderGateTest.java",
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleGateWaiterRemovalTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/BulkPacePolicyUseTest.java"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/pacing/IndexingPacingGateWaitTest.java"
      ],
      "class": "New",
      "behaviour": "Real DeviceEncoderGate and real IndexingPacing on one fake clock, foreground busy, 20% duty, sleeper recording. The bulk thread paces, then its background run waits 3 s at the gate behind a foreground run and works 0.5 s once seated; its next pace() sleeps nothing, because the 3 s wait already exceeds the 2 s that 0.5 s of work owes. Control: a further 0.5 s of work with no gate wait is paced 2000 ms. The gate's API does not exist before the change; against the pacing of this change before the fix the check failed by assertion (the waiting slice was paced 2000 ms).",
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
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "A pace() interval's work is its wall time less the time the thread waited for a seat at any device gate. Its wall-clock pause is the yield that work owes at the duty (work x (100 - duty) / duty) less the gate wait already spent, never below zero and at most 2000 ms.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-gate-new.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/pacing/IndexingPacingGateWaitTest.java"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/pacing/BulkDutyFloorUnderDeviceGateTest.java"
      ],
      "class": "New",
      "behaviour": "P5a, S5 regression guard. A real DeviceEncoderGate with the default policy and real IndexingPacing (20% duty) on one fake clock; two foreground callers (50 ms runs) keep a query always waiting, and one bulk producer paces, then runs 16 runs of 58.4 ms (128 documents at M5's 7.3 ms) per slice. Each seated run waits, in real time, until every other caller has queued again (or the bulk producer is inside a pacing sleep) before it advances the clock and before it releases, so the seat order and the gate-wait times do not depend on thread scheduling. Over 60 s of device time bulk gets at least 1/(agingSeatings + 2) of the seats less a stated 3-point tolerance for the window edge and pace yields, computed from DeviceGatePolicy (13.7% with the defaults). Twenty runs alone measured 16.78% minimum, 16.88% median and 17.05% maximum. Note: the spec's 20% assumed 1 in 5; derived bound per Spec D §3.2 aging rule; S5's owner-agreed 10 docs/s remains the live check P5b. Fail-before is a mutation, not a before-state run: with aging disabled (agedBackgroundWaiter returns null) bulk got 16 of 1198 seats (1.3%) and the check failed by assertion; the source was then restored byte-identical.",
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
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "The oldest background waiter is seated ahead of foreground after agingSeatings (4) foreground seatings since it queued or after 2000 ms of waiting, so a bulk producer under a constant foreground stream is guaranteed one seat in agingSeatings + 2. A pace() interval's work excludes its gate wait.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-gate-new.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/pacing/BulkDutyFloorUnderDeviceGateTest.java"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineSearchAdmissionUnderStallTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Real EngineKnowledgeClient, real WorkerSearchService and a real OnnxEmbeddingEncoder over a GPU handle whose run stalls until the test ends, engine admission at 64 slots, 4000 ms request deadline: 8 concurrent hybrid callers for 3 rounds. Sampled throughout, the admission slots in use never exceed the callers still in flight, every caller returns while the native runs still stall, and no slot is held after all callers returned. On 68547f75a slots stayed held after their callers had returned (expected 0 but was 3 in the current evidence run, 1 and 2 in earlier runs), which is how stalled runs filled the 64-slot limit.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S1",
          "quote": "During the scripted agent on a quiet 12 GB machine, no search returns a 504 or times out; every search answers within its deadline.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "An engine admission slot is held only while its caller is in flight. A native run that outlives its caller (released at the semantic deadline) holds no admission slot.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-gate-admission-repaired.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineSearchAdmissionUnderStallTest.java#slotsInUseNeverExceedLiveCallersAndAllReturnWhileRunsStillStall"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineSearchAdmissionUnderStallTest.java"
      ],
      "class": "Obsolete",
      "removedChecks": "theNinthConcurrentHybridSearchIsRefusedByTheInferenceLaneNotByAdmission: superseded by gates/test-intent/.changesets/b6d536-fg-pool.md. It recorded the zero-queue foreground inference lane refusing the ninth hybrid search with ENGINE_LIMIT until the owner decided; the owner adopted queueing within the deadline with its own refusal code (FP1, FP4). The replacement check, on the same real stack, is EngineSearchAdmissionUnderStallTest#searchesBeyondTheInferenceThreadsWaitAndOnlyAFullQueueRefusesAsInferenceBusy, with its evidence in gates/test-intent/evidence/b6d536-fg-pool-before.json.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP1",
          "quote": "with admission below its limits, 9 to 16 concurrent foreground requests that may run inference (SearchRpcOps.mayRunInference) all run; none is refused at once. The pool gets a bounded queue equal to its thread count (8 by default, 1 with the minimum policy), within the policy's foregroundQueue bound (validateAgainstPolicy).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "FP4",
          "quote": "when the inference threads and the queue are both full, the next inference request is refused with a new code ADMISSION_INFERENCE_BUSY (HTTP 429 with Retry-After; MCP -32000 with the same errorCode), never ADMISSION_ENGINE_LIMIT. If you add an EngineAdmissionException.Reason, name it so that BackgroundRunService (~:199, which builds \"ADMISSION_\" + reason.name()) yields exactly ADMISSION_INFERENCE_BUSY.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions searched for inference pool, foreground inference, SynchronousQueue, ADMISSION_ENGINE_LIMIT and inference lane; the only record of the zero-queue lane was this entry's own agent choice, made until the owner decided)"
    },
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleGpuWaiterCancellationTest.java",
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleDetachedRunTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientExecutorTest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "The per-handle Semaphore(1) field these tests read by reflection is removed; the device's seat is now the DeviceEncoderGate the handle holds in its gpuGate field. The helpers that read the semaphore's availablePermits() now read the gate's free seats (policy seats minus the seats held), and the helpers that read its queue length now read the gate's waiting count. NativeSessionHandleGpuWaiterCancellationTest and EngineKnowledgeClientExecutorTest wait for a queued waiter through that count, as they did through getQueueLength(). The inputs, the cancellations and interrupts, the expected exceptions and every assertion on free seats (1 free after release, 0 while held) are unchanged; one class comment and one inline comment are reworded from permit to seat."
    },
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleConcurrentStressTest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "Comment only: two Javadoc lines that described the removed per-handle GPU semaphore now describe the device seat. No code, input or assertion changed."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSemanticBudgetTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchLegCauseContractTest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "B3 (contendedSpladeHandleSkipsOnlyThatLeg) and C2 (aHeldSpladeDeviceCarriesTheCauseOnTheSpladeLegOnly) hold the SPLADE leg's device while the dense leg's device stays free. Both handles were built on CUDA device 0, which expressed that precondition while each handle had its own semaphore. With one gate per device, two handles on device 0 share the seat, so holding SPLADE's device also held the dense leg's. The fixture now builds the SPLADE handle on CUDA device 1 through a new gpuHandle(name, device) overload (the existing overloads delegate to device 0), which restores the stated precondition. The search request, the held background lease, the deadline and every assertion (SPLADE skipped with SEMANTIC_BUDGET_EXCEEDED and cause contended, the dense leg answered with no cause, results available) are unchanged."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceBreakerTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "K1 (S3), with K2's next search and a P2 guard. Real WorkerSearchService over a real index, the dense leg on a real NativeSessionHandle on CUDA device 51 and its real gate (real clock); only OrtSession is mocked. After a warm hybrid search (so the next run is not the session's first), a background run blocks in OrtSession.run; the check waits stuckAfterMs (10 000 ms) plus 300 ms. The first hybrid search then answers within 1 s and the next three within 50 ms each, all with keyword results, SEMANTIC_BUDGET_EXCEEDED and cause stuck. Meanwhile a hybrid search whose handle on the same device selects CPU keeps its dense leg (no fallback). Once the run returns and releases its seat, the next hybrid search uses dense again. On 342da6779 there was no breaker: the first search after the opening queued behind the stuck run at the gate and the check failed by its preemptive timeout assertion (execution timed out after 1000 ms).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S3",
          "quote": "While one native encode is stuck, later searches skip that leg at once with the same reason.",
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
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "A device's breaker is open while a seat held by a run that is not its session's first has been held at least stuckAfterMs (10 000 ms), at any urgency; it closes in the same step as that seat's release, with no timer. While it is open only foreground calls that release their caller at the deadline and may not create a GPU session (the hybrid dense and SPLADE legs, which have a keyword fallback) are turned away at once, as a RUN-phase deadline miss reported with cause stuck. Vector-only searches, the reranker, prewarm and background work still queue, and CPU legs never reach the gate.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-gate-breaker-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceBreakerTest.java#aStuckGpuRunTurnsLaterSearchesAwayAtOnceUntilItReturns"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/HeadRerankAcquireBudgetInProcessTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Verifier-3c finding 2. The production head (KnowledgeHttpApiAdapter) over the in-process engine, a hybrid search with the cross-encoder and request deadline D 5000 ms, reaching a real CrossEncoderReranker whose session handle (test double) waits until the acquisition's deadline and then throws SessionAcquireDeadlineExceededException (GATE), as a busy device does. The rerank waited for a session once, the search answers within D with its fused results, and its cross-encoder stage is SKIPPED with reason DEADLINE_EXCEEDED. On 342da6779 the acquisition miss escaped the reranker as a failed rerank call and the check failed by assertion (expected DEADLINE_EXCEEDED but was RPC_FAILED).",
      "sources": [
        {
          "kind": "defect-source",
          "stronger": {
            "kind": "owner-adopted-scenario",
            "task": "t-20261005-b6d536",
            "scenario": "S2",
            "quote": "When semantic search misses its sub-budget, the search returns keyword results with a fallback reason (per leg) that the UI, searchTrace, RAG metadata and MCP responses carry.",
            "label": "agent-drafted, owner-adopted"
          },
          "reproduction": "On 342da6779, run a hybrid search with the cross-encoder while the reranker's device stays busy until its 200 ms acquisition deadline: CrossEncoderReranker.rerank lets the SessionAcquireDeadlineExceededException out of acquire, the worker's rerank call fails, and the searchTrace's cross-encoder stage reads RPC_FAILED although the rerank call worked and only its budget ran out."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "A rerank that gets no session before its acquisition deadline is skipped, not failed. When the rerank's own budget bounded that wait, the skip cause is ACQUIRE_BUDGET_EXCEEDED, carried on the wire as the existing DEADLINE_EXCEEDED; when the search's deadline bounded it, the cause is RUN_DEADLINE_EXCEEDED, carried as the existing SEARCH_DEADLINE_EXCEEDED. Any other acquisition failure is still a failed call.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-gate-breaker-repaired.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/HeadRerankAcquireBudgetInProcessTest.java#aRerankThatGetsNoSessionWithinItsBudgetIsABudgetSkipNotAFailedCall"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/readinessNotice.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "K3, UI side: the readiness reason encoders.health.semantic_stuck is classified retrieval-impaired, is worded 'The semantic search encoder is stalled' with warn severity, and a notice for it reads 'Semantic search degraded.' with that wording as its only cause. On 342da6779 the code had no row and the check failed by assertion (expected 'unknown' to be 'retrieval-impaired').",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S3",
          "quote": "While one native encode is stuck, later searches skip that leg at once with the same reason.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "A breaker open for breakerStandingMs (5000 ms) is a standing state: the embedding component is DEGRADED with encoders.health.semantic_stuck (in the aiFeatures composite), which the shell shows as retrieval-impaired with warn severity and the wording 'The semantic search encoder is stalled'. A shorter opening is reported per search only.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-gate-breaker-repaired.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/readinessNotice.test.ts#Spec D K3: a standing stuck encoder impairs retrieval and is worded as itself"
      ]
    },
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/DeviceEncoderGateBreakerTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleSemanticStuckTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusMapperSemanticStuckTest.java"
      ],
      "class": "New",
      "behaviour": "Checks of the breaker and its standing state, whose API does not exist before the change. DeviceEncoderGateBreakerTest (DeviceEncoderGate on a fake clock): K4, a session's first run held past stuckAfterMs and a caller that leaves at its gate deadline do not open the breaker, while a later run of the same session held that long does (positive control); K2, while it is open a foreground query leg (released at its deadline, may not create a GPU session) is refused at once (RUN phase, breakerOpen), and releasing the stuck run closes the breaker in the same step so the next such leg is seated with nobody waiting; scope, while it is open a foreground caller that may create a session (VECTOR-only, reranker), a foreground caller not released at its deadline (prewarm) and a background caller are not turned away: each queues behind the stuck run and leaves only at its 50 ms gate deadline (GATE, not breakerOpen). StatusLifecycleSemanticStuckTest (production buildReadinessEnvelope, fixed clock): a breaker open since breakerStandingMs ago degrades the embedding component with encoders.health.semantic_stuck and the aiFeatures composite carries it; one millisecond less, or no open breaker, leaves the embedding READY. WorkerStatusMapperSemanticStuckTest: the worker health's semanticStuckSinceMs reaches the Head's worker view, and 0 or no health maps to null.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S3",
          "quote": "While one native encode is stuck, later searches skip that leg at once with the same reason.",
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
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "The breaker opens only for a run that is not its session's first (a session that has released a seat before), held at least stuckAfterMs (10 000 ms), and closes in the release step, with no timer. Only foreground fallback legs are turned away. The worker's health response carries the earliest open-since time across devices as epoch ms (0 when closed); once that is breakerStandingMs (5000 ms) old, the Head degrades the embedding component with encoders.health.semantic_stuck, a transient code.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-search-gate-breaker-new.json",
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/DeviceEncoderGateBreakerTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleSemanticStuckTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusMapperSemanticStuckTest.java"
      ]
    }
  ]
}
```
