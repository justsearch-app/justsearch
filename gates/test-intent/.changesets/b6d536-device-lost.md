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

Evidence files (all written by run-evidence.mjs):

- `b6d536-device-lost-repaired.json`: E-S11a, with an assertion fail-before on e26c94d53. Spec E §8
  names 6f7895a79 as the before-state, but the check shows that the request after the loss does
  not enter the device's gate by holding that gate's seat (DeviceEncoderGates), which 6f7895a79
  does not have. e26c94d53 is this branch's base, with the gate and without this change.
- `b6d536-device-lost-preservation.json`: E-S11b, passing on e26c94d53 and on the change. That run
  exits 1 by design, because it shows no repair.
- `b6d536-device-lost-new.json`: checks of new API (E-U8, E-S11c, lost versus stuck, the
  no-report half of E-S11b, the P2 CPU guard), no before-state.

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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for item 5a (search budget)",
          "choice": "The Engine reports encoder device loss to the chat server controller through one reporter per incarnation, subscribed to DeviceHealth: the first loss is reported as reportDeviceLost(ORT_CUDA, code as a decimal string), a device already lost when it starts is reported at once, and later losses in the same incarnation are logged only. The reporter never restarts anything.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-device-lost-new.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EncoderDeviceLossReporterTest.java"
      ]
    }
  ]
}
```

## Acceptance records
