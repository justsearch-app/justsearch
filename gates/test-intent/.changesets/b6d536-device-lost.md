---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, Spec E revision 3 step E8 (§6.1, §6.2): encoder device loss. A GPU run
that returns a CUDA error from the closed device-lost list (§6.1) latches its device for the rest of
the process (DeviceHealth). Every GPU session handle on that device then selects its CPU session
before the device gate, drops its GPU session without closing it natively, bumps its session
generation (so prewarm warms the CPU session) and reports CPU as its query device. Out-of-memory,
the BFC arena message, error 46 and any message without a listed code change nothing. A listener
reports the loss to the chat server controller once per Engine incarnation as
reportDeviceLost(ORT_CUDA, code); restarting within the limit is the controller's (Spec A).

A lost device is not a stuck one. The breaker (Spec D) opens while a GPU run has not returned for
stuckAfterMs, turns query legs with a keyword fallback away at the gate, and closes when the run
returns. Device loss is set by a run that returned a listed error, is read before the gate, never
opens the breaker, and does not clear within the process.

Repair after verify-d810 Finding 1 (CPU recourse for turned-away query legs). This departs from Spec D §2.2, §3.2 rule 4 and K1, which turn a query leg away from a busy or stuck device with keyword results only. The owner adopted it on 2026-10-06 (task t-20261005-b6d536): with the words "proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input" the owner accepted the recommendations "CPU encoding for turned-away searches: keep it. Without it, semantic search was off 75% of the scripted window. With it, results stay semantic at about 40 ms per query." and "CPU model: keep it loaded. 1.7 GB of RAM buys no first-query delay in the moment the GPU is busy, which is exactly when it matters." verbatim. The recommendations are agent-drafted, owner-adopted; the CPU-recourse entries below cite that adoption (they cited the delegation before, and the verifier rejected them for it; the record is kept below). The adoption supersedes S3's "skip that leg at once with the same reason" for foreground query legs: a leg the device turns away is first encoded on the warm CPU session within its remaining budget. S3 still holds when the CPU also misses (or there is no warm CPU session): the leg then falls back with SEMANTIC_BUDGET_EXCEEDED and its cause. A foreground query leg (hybrid, RAG or VECTOR-only) that the device cannot seat (breaker open, or no seat within min(1 s, half its budget)) runs on the handle's warm CPU session within its remaining budget, through the CPU lease path a lost device uses; only if that also misses does it fall back with SEMANTIC_BUDGET_EXCEEDED. Bulk work, prewarm and the reranker never do. The query prewarm warms the CPU session of GPU-served query encoders after ready.

Evidence files (all written by run-evidence.mjs):

- `b6d536-device-lost-repaired.json`: E-S11a, with an assertion fail-before on e26c94d53. Spec E §8
  names 6f7895a79 as the before-state, but the check shows that the request after the loss does
  not enter the device's gate by holding that gate's seat (DeviceEncoderGates), which 6f7895a79
  does not have. e26c94d53 is this branch's base, with the gate and without this change.
- `b6d536-device-lost-preservation.json`: E-S11b, passing on e26c94d53 and on the change. That run
  exits 1 by design, because it shows no repair.
- `b6d536-device-lost-new.json`: checks of new API (E-U8, E-S11c, lost versus stuck, the
  no-report half of E-S11b, the P2 CPU guard), no before-state.
- `b6d536-device-lost-cpu-recourse-repaired.json`: the CPU recourse checks, with an assertion
  fail-before on 8ba4bff64 (E8, this branch before the repair).
- `b6d536-device-lost-cpu-recourse-preservation.json`: the bounds of the recourse, passing on
  8ba4bff64 and on the change; exits 1 by design.
- `b6d536-device-lost-cpu-recourse-new.json`: checks of new API (CPU prewarm, the trace event, the
  prewarm runner), no before-state.
- `b6d536-device-lost-cpu-recourse-visible-repaired.json`: the search trace's device note and the
  shell's query-on-CPU wording, with an assertion fail-before on 93a8b2038 (this branch before the
  status and trace change).
- `b6d536-device-lost-cpu-recourse-miss-preservation.json`: a CPU encode that also misses the budget,
  passing on 93a8b2038 and on the change; exits 1 by design (the behaviour was there, unchecked).
- `b6d536-device-lost-cpu-recourse-visible-new.json`: checks of new API (the recourse record, the
  worker health field, the Head's worker view and readiness), no before-state.

Records phase B (batch 3) re-ran four of these files on b599d4d7e because their check files changed
after the first runs:

- `b6d536-device-lost-repaired.json`: NativeSessionHandleDeviceLostTest gained a method (7de3976a1)
  that calls DeviceHealth, which e26c94d53 lacks. The fail-before applies the compat before-patch
  `gates/test-intent/evidence/b6d536-device-lost-compat.patch`. It adds only the declaration
  DeviceHealth with forDevice(int), which returns a new instance, and reportLost(int), which returns
  false at once. No e26c94d53 code reads it. The check again fails by the same assertion there.
- `b6d536-device-lost-cpu-recourse-visible-repaired.json`: WorkerSearchServiceCpuRecourseVisibilityTest
  now reads QueryCpuRecourse.lastTakenNanos() (04368bfca), which 93a8b2038 lacks. The fail-before
  applies `gates/test-intent/evidence/b6d536-device-lost-cpu-recourse-visible-compat.patch`, which
  adds only that class with lastTakenNanos() returning OptionalLong.empty() at once. No 93a8b2038
  code reads it. Both checks again fail by the same assertions there.
- `b6d536-device-lost-cpu-recourse-miss-preservation.json`: for the same reason, its check no longer
  compiles on 93a8b2038. It is a preservation check, so it was re-run without a before-state and
  passes on the change.
- `b6d536-device-lost-cpu-recourse-visible-new.json`: passes, no before-state.

```json
{
  "entries": [
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleDeviceLostTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "E-S11a (a real NativeSessionHandle on CUDA device 61 and its real gate, built by OrtSessionAssembler.buildManager; only the GPU and CPU OrtSessions are mocks). The first GPU run throws onnxruntime 1.24.3's CUDA_CALL message for error 719 ('CUDA failure 719: unspecified launch failure ; GPU=0 ; ...'). While another caller holds the device's only seat, the next foreground acquire (500 ms deadline) returns at once with a CPU lease, its run returns the CPU session's embedding vector, and the GPU session ran only once. On e26c94d53 the handle went back to the GPU: the acquire queued at the held seat and left at its deadline, and the check failed by assertion ('the request after the device-lost run waited at the device's gate').",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S11",
          "quote": "After a driver reset, driver update or sleep/resume, search and chat end up working without a crash loop; suspended time is not treated as a hang or a deadline.",
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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "Device loss is detected where every native run passes (SessionHandle.Lease run and runPinned, which detached runs use too), only on a GPU lease, which carries its CUDA device. The failure is classified, the device is latched in a per-process DeviceHealth, and the failure is rethrown unchanged. Each GPU session handle on that device then drops its GPU session (not closed natively; its in-flight leases are not closed on release), selects its CPU session before the device gate, bumps its session generation and reports CPU as its query device and the GPU as unavailable. The latch is not cleared within the process.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-device-lost-repaired.json",
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleDeviceLostTest.java#afterADeviceLostRunTheNextRequestRunsOnCpuWithoutEnteringTheGate"
      ]
    },
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleGpuErrorKeepsGpuTest.java"
      ],
      "class": "New",
      "behaviour": "E-S11b, preservation, passing on e26c94d53 and on the change (same seam as E-S11a, one CUDA device per case: 62, 63, 64). A GPU run that throws 'CUDA failure 2: out of memory', the BFC arena message ('BFCArena::AllocateRawInternal ... Available memory of 0 is smaller than requested bytes of 268435456') or 'CUDA failure 46: all CUDA-capable devices are busy or unavailable' leaves the next request on the GPU. That none of them reports a loss is checked in DeviceLostIsNotStuckTest (new API).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S11",
          "quote": "After a driver reset, driver update or sleep/resume, search and chat end up working without a crash loop; suspended time is not treated as a hang or a deadline.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "Only Spec E §6.1's closed list is device loss: CUDA error 35, 100, 214, 700, 702, 714 to 719, 803 and 999, read from ORT's 'CUDA failure <code>:' message or from the exact cudaError / CUDA_ERROR_ name of a listed code. Out of memory (2), error 46, the BFC arena message, allocation failures and cuDNN or cuBLAS errors without a listed code, and messages with no code, leave the handle as it was.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-device-lost-preservation.json",
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleGpuErrorKeepsGpuTest.java"
      ]
    },
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/DeviceLostClassifierTest.java"
      ],
      "class": "New",
      "behaviour": "E-U8, the closed list (DeviceLostClassifier is new API). Each listed code in onnxruntime 1.24.3's CUDA_CALL format ('CUDA failure <code>: <text> ; GPU=0 ; hostname=... ; file=... ; line=... ; expr=...', wrapped as ORT Java wraps a run's status) classifies as that code: 35, 100, 214, 700, 702, 714, 715, 716, 717, 718, 719, 803, 999. The runtime and driver names of listed codes (cudaErrorLaunchFailure, cudaErrorIllegalAddress, cudaErrorInsufficientDriver, cudaErrorUnknown, CUDA_ERROR_LAUNCH_FAILED, CUDA_ERROR_NO_DEVICE, CUDA_ERROR_SYSTEM_DRIVER_MISMATCH) classify as their codes. Not device loss: CUDA failure 2 and 46, the BFC arena message, CUBLAS_STATUS_ALLOC_FAILED, CUDNN_STATUS_NOT_SUPPORTED, CUDNN_STATUS_EXECUTION_FAILED, CUBLAS_STATUS_EXECUTION_FAILED, CUDA_ERROR_OUT_OF_MEMORY, cudaErrorMemoryAllocation, cudaErrorDevicesUnavailable, a listed name inside a longer name, a listed number after 'CUBLAS failure' or in other text, a message without a code, and a missing message.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S11",
          "quote": "After a driver reset, driver update or sleep/resume, search and chat end up working without a crash loop; suspended time is not treated as a hang or a deadline.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "A message is device loss only when it carries a listed code: first ORT's 'CUDA failure <code>:' (the CUDA library only, not CUBLAS or CUDNN), then an exact cudaError name, then an exact CUDA_ERROR_ name. Anything else, including a listed number elsewhere in the text, is not.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-device-lost-new.json",
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/DeviceLostClassifierTest.java"
      ]
    },
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/DeviceLostIsNotStuckTest.java"
      ],
      "class": "New",
      "behaviour": "Lost versus stuck, the no-report half of E-S11b, and a P2 guard (real NativeSessionHandles on their devices' real gates and real clock; only OrtSessions are mocks; DeviceHealth is new API). Device 65: after a warm GPU run, a background GPU run blocks for stuckAfterMs plus 300 ms; the breaker is open, a foreground query leg (released at its deadline, may not create a GPU session) is turned away with breakerOpen, the device is not lost and the query device stays GPU. Once the run returns the breaker is closed. The next GPU run throws CUDA error 719: the device is lost with code 719, the breaker stays closed, the same query leg gets a CPU lease and the query device is CPU. Devices 66 to 68: a GPU run failing with out of memory, the BFC arena message or error 46 latches nothing and no loss listener is told. A CPU-only handle whose CPU run fails with the 719 message latches nothing, tells no listener, and keeps CPU as its query device.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S11",
          "quote": "After a driver reset, driver update or sleep/resume, search and chat end up working without a crash loop; suspended time is not treated as a hang or a deadline.",
          "label": "agent-drafted, owner-adopted"
        },
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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "A lost device and a stuck one are kept apart. The breaker is unchanged and gains no cause: it is opened only by a run that has not returned, and closes when it returns. Device loss is latched only from an error a GPU run returned, is read in selectSession before the gate, and does not open the breaker. A CPU lease carries no device, so a CPU run never reports device loss.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-device-lost-new.json",
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/DeviceLostIsNotStuckTest.java"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EncoderDeviceLossReporterTest.java"
      ],
      "class": "New",
      "behaviour": "E-S11c (EncoderDeviceLossReporter is new API). Two real session handles built by OrtSessionAssembler.buildManager on CUDA devices 71 and 72, whose GPU runs go through the production run path; the OrtSessions and the ChatServerController are mocks. After a warm run (query device GPU), a GPU run on device 71 throws CUDA error 719, then a GPU run on device 72 does too. The controller gets reportDeviceLost(new DeviceLostEvidence(ORT_CUDA, \"719\")) exactly once and no other report, the first handle's generation listener fired, and its query device is CPU.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S11",
          "quote": "After a driver reset, driver update or sleep/resume, search and chat end up working without a crash loop; suspended time is not treated as a hang or a deadline.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "The Engine reports encoder device loss to the chat server controller through one reporter per incarnation, subscribed to DeviceHealth: the first loss is reported as reportDeviceLost(ORT_CUDA, code as a decimal string), a device already lost when it starts is reported at once, and later losses in the same incarnation are logged only. The reporter never restarts anything.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-device-lost-new.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EncoderDeviceLossReporterTest.java"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceCpuRecourseTest.java",
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleCpuRecourseTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Turned-away query legs run on the warm CPU session (verify-d810 Finding 1). WorkerSearchService with the production index and planner; the dense leg acquires a real NativeSessionHandle on its device's real gate and runs the lease's session; only the OrtSessions are mocks, and the CPU session has run once, as any CPU encode makes it. Device 52: with the device free a hybrid search's dense leg runs on the GPU (P1); while a bulk lease holds the seat, a hybrid search (3 s deadline) answers without a fallback, its dense leg ran on the CPU session, and it answers inside D. Device 53: after a warm search, a bulk GPU run blocks for stuckAfterMs plus 300 ms; a hybrid search answers within 1 s without a fallback from the CPU session, and a VECTOR-only search answers VECTOR with its hit within 1 s from the CPU session; once the run returns the next leg runs on the GPU. ort-common, devices 81 and 82: with the seat held, a hybrid-leg request and a VECTOR-only-shaped request (3 s) each get a CPU lease after about the 1 s seat wait and inside 2 s; with the seat free the hybrid leg gets the GPU. On 8ba4bff64 each check fails by assertion: the busy hybrid search fell back with SEMANTIC_BUDGET_EXCEEDED (cause contended), the stuck check's first hybrid search fell back with SEMANTIC_BUDGET_EXCEEDED (cause stuck), so its VECTOR-only step was not reached there, and both ort-common acquisitions threw a deadline miss (assertDoesNotThrow).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S2",
          "quote": "When semantic search misses its sub-budget, the search returns keyword results with a fallback reason (per leg) that the UI, searchTrace, RAG metadata and MCP responses carry.",
          "label": "agent-drafted, owner-adopted"
        },
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
          "scenario": "S4",
          "quote": "A search waits at most about one second for any encoder on the device; foreground is served before waiting bulk work.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P1",
          "quote": "With chat idle on 12 GB, hybrid search stays as fast (p95 about 0.26 s) and indexing as fast as today.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn 2026-10-06T21:10Z",
          "quote": "proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input",
          "adoptedVerbatim": ["CPU encoding for turned-away searches: keep it. Without it, semantic search was off 75% of the scripted window. With it, results stay semantic at about 40 ms per query.","CPU model: keep it loaded. 1.7 GB of RAM buys no first-query delay in the moment the GPU is busy, which is exactly when it matters."],
          "label": "agent-drafted, owner-adopted",
          "supersedes": "S3 ('While one native encode is stuck, later searches skip that leg at once with the same reason') for foreground query legs: a leg the device turns away is first encoded on the warm CPU session within its remaining budget. S3 still holds when the CPU also misses (or there is no warm CPU session): the leg then falls back with SEMANTIC_BUDGET_EXCEEDED and its cause.",
          "implementedAs": "Departs from Spec D: Spec D §2.2 ('nothing is redirected to CPU'), §3.2 rule 4 ('It never redirects to CPU') and K1 ('skip semantic') turn a query leg away from a busy or stuck device with keyword results only. Verification verify-d810 (Finding 1) measured the cost: with single SPLADE bulk calls of 188 s and 737 s holding the device's one seat, 74.6% of hybrid searches were keyword-only for about 15 of 20 minutes (baseline 13.3%). S2 asks for keyword results only when semantic search misses its sub-budget, so a leg that can still be encoded within that sub-budget on the CPU now is. Rule: a foreground request released at its deadline (a hybrid or RAG query leg, or a VECTOR-only leg), on a handle whose current CPU session has completed a run, goes to that CPU session through the same CPU lease path a lost device uses (cpuSessionFor, acquireCpuLease) when the device's breaker is open (at once), or when no seat comes within min(1 000 ms, half its remaining budget), S4's 'about one second'. A free seat is still taken first, so an idle device serves the leg on the GPU (P1). The CPU run keeps the leg's deadline: if it also misses, the leg falls back with SEMANTIC_BUDGET_EXCEEDED as before (cause stuck). Bulk (background), prewarm and the reranker and citation scorer (not released at a deadline) never take this path. The trace records the device used: lease.mode=cpu on the lease span, encoder.gpu=false on the encoder's ort_run span, and a cpu_fallback.triggered event (fallback.cause device_stuck or device_contended, fallback.encoder) on the caller's span."
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-device-lost-cpu-recourse-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceCpuRecourseTest.java#aSearchOnABusyDeviceGetsItsDenseLegFromTheWarmCpuSession",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceCpuRecourseTest.java#whileAGpuRunIsStuckSearchesGetTheirVectorsFromTheWarmCpuSession",
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleCpuRecourseTest.java#aContendedQueryLegRunsOnTheWarmCpuSessionWithinItsBudget",
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleCpuRecourseTest.java#aContendedVectorOnlyLegAlsoRunsOnTheWarmCpuSession"
      ]
    },
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleCpuRecourseBoundsTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation, passing on 8ba4bff64 and on the change (same seam as NativeSessionHandleCpuRecourseTest). Device 83, CPU session warm, seat held by a bulk lease: a background request (300 ms) and a foreground request that is not released at its deadline (prewarm, reranker; 300 ms) both end with GATE and never get the CPU session. Device 84, CPU session present but never run: a hybrid query leg (500 ms) still ends with GATE.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S2",
          "quote": "When semantic search misses its sub-budget, the search returns keyword results with a fallback reason (per leg) that the UI, searchTrace, RAG metadata and MCP responses carry.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S4",
          "quote": "A search waits at most about one second for any encoder on the device; foreground is served before waiting bulk work.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn 2026-10-06T21:10Z",
          "quote": "proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input",
          "adoptedVerbatim": ["CPU encoding for turned-away searches: keep it. Without it, semantic search was off 75% of the scripted window. With it, results stay semantic at about 40 ms per query.","CPU model: keep it loaded. 1.7 GB of RAM buys no first-query delay in the moment the GPU is busy, which is exactly when it matters."],
          "label": "agent-drafted, owner-adopted",
          "supersedes": "S3 ('While one native encode is stuck, later searches skip that leg at once with the same reason') for foreground query legs: a leg the device turns away is first encoded on the warm CPU session within its remaining budget. S3 still holds when the CPU also misses (or there is no warm CPU session): the leg then falls back with SEMANTIC_BUDGET_EXCEEDED and its cause.",
          "implementedAs": "Only foreground requests released at their deadline, on a handle whose current CPU session has completed a run, may run on the CPU when the device turns them away. Bulk work, prewarm, the reranker and the citation scorer wait for the device as before, and a CPU session that never ran is not used as a warm one."
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-device-lost-cpu-recourse-preservation.json",
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleCpuRecourseBoundsTest.java"
      ]
    },
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleCpuPrewarmTest.java",
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleCpuRecourseTraceTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/QueryEncoderPrewarmCpuWarmTest.java"
      ],
      "class": "New",
      "behaviour": "New API (SessionHandle.prewarmCpuForQueries, QueryEncoderPrewarm.Target.cpuWarm). Device 85: a CPU session that never ran gives no recourse (GATE); prewarmCpuForQueries runs it once on input_ids 16 zeros, attention_mask 16 ones and token_type_ids 16 zeros, returns true, a second call does not run it again, and a turned-away hybrid leg then gets a CPU lease. A CPU-only handle returns false and does not run; a model whose input is a float pixel tensor (device 87) is not run. Device 86: a turned-away leg's span carries cpu_fallback.triggered with fallback.cause device_contended and fallback.encoder the handle name. QueryEncoderPrewarm on a real scheduled executor: after onReady, the CPU warm-up runs for the GPU-served EMBEDDING and SPLADE targets in order with a FOREGROUND request, not for a CPU-served BGE_M3 target, and a throwing warm-up reports no failure.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S2",
          "quote": "When semantic search misses its sub-budget, the search returns keyword results with a fallback reason (per leg) that the UI, searchTrace, RAG metadata and MCP responses carry.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "P1",
          "quote": "With chat idle on 12 GB, hybrid search stays as fast (p95 about 0.26 s) and indexing as fast as today.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn 2026-10-06T21:10Z",
          "quote": "proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input",
          "adoptedVerbatim": ["CPU encoding for turned-away searches: keep it. Without it, semantic search was off 75% of the scripted window. With it, results stay semantic at about 40 ms per query.","CPU model: keep it loaded. 1.7 GB of RAM buys no first-query delay in the moment the GPU is busy, which is exactly when it matters."],
          "label": "agent-drafted, owner-adopted",
          "supersedes": "S3 ('While one native encode is stuck, later searches skip that leg at once with the same reason') for foreground query legs: a leg the device turns away is first encoded on the warm CPU session within its remaining budget. S3 still holds when the CPU also misses (or there is no warm CPU session): the leg then falls back with SEMANTIC_BUDGET_EXCEEDED and its cause.",
          "implementedAs": "The query prewarm, after it reports the encoders ready (so readiness is not delayed), calls prewarmCpuForQueries on every query encoder whose query device is GPU (embedding, SPLADE when not inference-free, BGE-M3). On a GPU handle it creates the CPU session if it was deferred (the embedding's is) and runs it once on a one-row, 16-token input (ids 0, attention mask 1, every input a 2-D int64 tensor); a model with other inputs is not run. A failure is logged and leaves those legs to the keyword fallback. Measured once on this machine (RTX 4070, onnxruntime 1.24.3, gte-multilingual-base model_fp16.onnx, which is the CPU session's model on GPU machines): CPU session creation 685 to 740 ms without the optimized store, first run 61 to 91 ms, then about 40 ms per query; process private memory +1.69 GB. The CPU and GPU query vectors (CLS) of six texts agreed with cosine 0.999997 or more. The suite has no GPU test environment, so that agreement is not a suite check."
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-device-lost-cpu-recourse-new.json",
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleCpuPrewarmTest.java",
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleCpuRecourseTraceTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/QueryEncoderPrewarmCpuWarmTest.java"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceCpuRecourseVisibilityTest.java",
        "modules/ui-web/src/shell-v0/state/readinessNoticeQueryOnCpu.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Status and the search's trace say the CPU served a turned-away query leg (finding 1 of the 4070 run: the searchTrace had no device, and the CPU-recourse log line is DEBUG). WorkerSearchService with the production index and planner; the dense leg acquires a real NativeSessionHandle on device 54's real gate and runs the lease's session; only the OrtSessions are mocks. With the device free the dense-retrieval stage's detail is 'device=gpu'; while a bulk lease holds the seat the leg executes with detail 'device=cpu; cpu-recourse=contended'; with the CPU session stalled the search falls back with SEMANTIC_BUDGET_EXCEEDED and the detail still says 'device=cpu; cpu-recourse=contended'. Shell: encoders.health.query_on_cpu is worded 'Searches are running on the CPU while the GPU is busy' at info severity; beside encoders.health.semantic_stuck the stall is no longer retrieval-impairing or above info, the info notice reads 'Reduced search capability.' with no keyword claim and lists both causes, and beside index.dense_unavailable the impairing notice names that cause, not the stall. On 93a8b2038 both fail by assertion: the idle leg's detail was empty (expected <device=gpu> but was <>), and the code had no row (expected 'Degraded: encoders.health.query_on_cpu' to be the wording).",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn 2026-10-06T21:10Z",
          "quote": "proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input",
          "adoptedVerbatim": ["CPU encoding for turned-away searches: keep it. Without it, semantic search was off 75% of the scripted window. With it, results stay semantic at about 40 ms per query.","CPU model: keep it loaded. 1.7 GB of RAM buys no first-query delay in the moment the GPU is busy, which is exactly when it matters."],
          "label": "agent-drafted, owner-adopted",
          "supersedes": "S3 ('While one native encode is stuck, later searches skip that leg at once with the same reason') for foreground query legs: a leg the device turns away is first encoded on the warm CPU session within its remaining budget. S3 still holds when the CPU also misses (or there is no warm CPU session): the leg then falls back with SEMANTIC_BUDGET_EXCEEDED and its cause.",
          "implementedAs": "The searchTrace's dense-retrieval and splade-retrieval stages carry, in their existing optional 'detail', the device that encoded the leg and whether the CPU recourse took it ('device=gpu', 'device=cpu', 'device=cpu; cpu-recourse=contended|stuck'), recorded per leg on the request thread at every tracing level; no wire change. While the recourse took a query leg within the last QueryCpuRecourse.RECENT_MS (60 000 ms), the embedding readiness component is READY with the informational encoders.health.query_on_cpu, and a standing breaker is reported beside it as gpu DEGRADED encoders.health.semantic_stuck. The shell words the code at info severity and, while it is present, does not count the stall as a keyword fallback."
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S2",
          "quote": "When semantic search misses its sub-budget, the search returns keyword results with a fallback reason (per leg) that the UI, searchTrace, RAG metadata and MCP responses carry.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-device-lost-cpu-recourse-visible-repaired.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceCpuRecourseVisibilityTest.java#theTraceSaysWhichDeviceEncodedTheDenseLegAndWhetherTheCpuRecourseTookIt",
        "modules/ui-web/src/shell-v0/state/readinessNoticeQueryOnCpu.test.ts#words query_on_cpu calmly, and a stall beside it stays listed without a keyword claim"
      ]
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceCpuRecourseVisibilityTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation, passing on 93a8b2038 and on the change (finding 2 of the 4070 run: no check drove it). Same seam, device 55: while a bulk lease holds the seat and the warm CPU session stalls, a hybrid search (3 s deadline) starts its CPU run, answers within D keyword-only with SEMANTIC_BUDGET_EXCEEDED, cause stuck (the run started and did not return inside the sub-budget), effective mode TEXT and its hit; a VECTOR-only search starts its CPU run and ends within D in WorkerServiceException DEADLINE_EXCEEDED whose message contains 'SEMANTIC_BUDGET_EXCEEDED (cause: stuck)'.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn 2026-10-06T21:10Z",
          "quote": "proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input",
          "adoptedVerbatim": ["CPU encoding for turned-away searches: keep it. Without it, semantic search was off 75% of the scripted window. With it, results stay semantic at about 40 ms per query.","CPU model: keep it loaded. 1.7 GB of RAM buys no first-query delay in the moment the GPU is busy, which is exactly when it matters."],
          "label": "agent-drafted, owner-adopted",
          "supersedes": "S3 ('While one native encode is stuck, later searches skip that leg at once with the same reason') for foreground query legs: a leg the device turns away is first encoded on the warm CPU session within its remaining budget. S3 still holds when the CPU also misses (or there is no warm CPU session): the leg then falls back with SEMANTIC_BUDGET_EXCEEDED and its cause."
        },
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
          "scenario": "S2",
          "quote": "When semantic search misses its sub-budget, the search returns keyword results with a fallback reason (per leg) that the UI, searchTrace, RAG metadata and MCP responses carry.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-device-lost-cpu-recourse-miss-preservation.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceCpuRecourseVisibilityTest.java#aCpuEncodeThatAlsoMissesTheRemainingBudgetGivesTheReasonAndItsCause"
      ]
    },
    {
      "items": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleQueryCpuRecourseTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerHealthServiceQueryCpuRecourseTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusMapperQueryOnCpuTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleQueryOnCpuTest.java"
      ],
      "class": "New",
      "behaviour": "New API (QueryCpuRecourse, HealthCheckResponse.query_cpu_recourse_at_ms, WorkerOperationalView.queryCpuRecourseAtMs, encoders.health.query_on_cpu). ort-common, device 88: a leg the free device seats is Served(gpu, null), a CPU lease asked for is Served(cpu, null), a leg turned away while a bulk lease holds the seat is Served(cpu, contended) with trace detail 'device=cpu; cpu-recourse=contended', and lastTakenNanos is set at or after that leg; device 89: an observation sees neither a nested observation's lease nor another thread's. worker-services, device 57: after such a leg the health check's query_cpu_recourse_at_ms lies between the leg and the report. app-services: the value reaches the Head's worker view; 0 and a missing health check map to null. ui (production buildReadinessEnvelope, fixed clock): a recourse RECENT_MS - 1 ms ago leaves embedding READY with encoders.health.query_on_cpu and aiFeatures READY carrying it, RECENT_MS ago clears it; with a standing breaker and a recent recourse the embedding is READY with query_on_cpu and gpu is DEGRADED with semantic_stuck, both in aiFeatures; with a standing breaker and no recent recourse the embedding is DEGRADED with semantic_stuck as before and gpu READY.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn 2026-10-06T21:10Z",
          "quote": "proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input",
          "adoptedVerbatim": ["CPU encoding for turned-away searches: keep it. Without it, semantic search was off 75% of the scripted window. With it, results stay semantic at about 40 ms per query.","CPU model: keep it loaded. 1.7 GB of RAM buys no first-query delay in the moment the GPU is busy, which is exactly when it matters."],
          "label": "agent-drafted, owner-adopted",
          "supersedes": "S3 ('While one native encode is stuck, later searches skip that leg at once with the same reason') for foreground query legs: a leg the device turns away is first encoded on the warm CPU session within its remaining budget. S3 still holds when the CPU also misses (or there is no warm CPU session): the leg then falls back with SEMANTIC_BUDGET_EXCEEDED and its cause.",
          "implementedAs": "Status says the CPU served query legs with a registered reason, beside the stuck signal: the worker's health reports when the recourse last took a query leg (epoch ms, 0 when never; proto field 11 of the internal Head-Worker health message), the Head keeps it on the worker view (not serialized), and the named window is QueryCpuRecourse.RECENT_MS = 60 000 ms."
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S3",
          "quote": "While one native encode is stuck, later searches skip that leg at once with the same reason.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-device-lost-cpu-recourse-visible-new.json",
      "newChecks": [
        "modules/ort-common/src/test/java/io/justsearch/ort/NativeSessionHandleQueryCpuRecourseTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerHealthServiceQueryCpuRecourseTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusMapperQueryOnCpuTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleQueryOnCpuTest.java"
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
  "session": "3143a670-275e-4f62-bc98-c13f5ac32e05",
  "verdict": "reject",
  "digest": "sha256:6a9db3c1766f0db027cb3d723c2c38570c660c71cc9331aa0a9fed70443fd76c",
  "note": "Entries 1-5 (E8 device loss) are supported: fail-before on e26c94d53 reproduced as an assertion, preservation passes on both, the closed list matches ORT 1.24.3's CUDA_CALL format. Rejected for the CPU-recourse entries (WorkerSearchServiceCpuRecourseTest + NativeSessionHandleCpuRecourseTest; and the bounds and prewarm/trace entries that serve the same choice): their delegated-choice is recorded as a departure from Spec D 2.2, 3.2 rule 4 and K1, but the delegation's scope is 'completing the agreed design for item 5a', and a stronger source conflicts: owner-adopted S3 ('While one native encode is stuck, later searches skip that leg at once with the same reason'), which the entry itself cites, while a stuck leg is now served from the CPU with no reason. Live checks support the behaviour (0 keyword-only vs 60.8% on base; VECTOR-only 30/30 under an open breaker), so the fix is a source, not code: cite the owner's words adopting CPU recourse or re-wording S3, then re-review. Also note: no status or searchTrace field reports the CPU use (only the OTel trace, off by default)."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-batch1-20261008-independent",
  "verdict": "accept",
  "digest": "sha256:97e3abc1dfa529c3ab41c0e03b5d27d20be24fd6d1801d8327140a5ff1d2fad4"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-batch1-20261008-independent",
  "verdict": "reject",
  "digest": "sha256:97e3abc1dfa529c3ab41c0e03b5d27d20be24fd6d1801d8327140a5ff1d2fad4"
}
```
