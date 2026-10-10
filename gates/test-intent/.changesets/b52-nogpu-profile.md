---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b52-codex-builder
---

# Test-intent entries

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/inference/NoDeviceChatPlanTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/NoDeviceCpuActivationTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "All no-device fact outcomes (0, 35, 100, 803) resolve NO_GPU into automatic GPU availability false, CPU chat layers, 8k automatic context, CPU projector and CUDA-hidden child. Serving and activation use that profile decision, even when an earlier automatic contribution requests 99 layers. Explicit context and layer preferences remain saved, positive layers are unmet, and CPU activation passes with no GPU-memory measurement.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 request (b52 builder role contract)",
          "quote": "make the NO_GPU profile, however it arises from the device facts, select the CPU tier for chat by construction: zero layers, the spec's CPU context (8k unless the user set context explicitly), CPU projector, CUDA hidden from the child, and a CPU activation self-test that passes with no GPU-memory measurement."
        }
      ],
      "evidence": "gates/test-intent/evidence/b52-nogpu-profile.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/inference/NoDeviceChatPlanTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/NoDeviceCpuActivationTest.java"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuProfileResolverTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The NO_GPU projection contributes the CPU tier and automatic GPU availability from usable-device facts, while unresolved, below-P8 and none-fits GPU rows retain their previous behavior and assertions.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 request (b52 builder role contract)",
          "quote": "Keep below-P8 / none-fits behaviour per the spec (not NO_GPU). GPU-on profiles keep their placement."
        }
      ],
      "evidence": "gates/test-intent/evidence/b52-nogpu-profile.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuProfileResolverTest.java#unplannedGpuKeepsConstantsWhileNoGpuSelectsCpuTier"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeIntentProducerTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "Preserve every accepted-refusal assertion: VERSION_CONFLICT, server-issued operation key, durable FAILED row and no reconciliation. Inject a competing physical settings witness instead of a nested same-runner operation during preparation, removing random key-stripe interference from the fixture.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 request (b52 builder role contract)",
          "quote": "report whether it is order-dependent and why; fix only if it is a test isolation fault."
        }
      ],
      "evidence": "gates/test-intent/evidence/b52-nogpu-profile.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeIntentProducerTest.java#acceptedRefusalStillReturnsItsServerIssuedKey"
      ]
    }
  ]
}
```

## Acceptance records

Independent acceptance pending. Fail-before assertion XML and guarded execution logs are retained in F:/agent-sandbox/design-b6d536/codex/b52-nogpu-profile/report.md.
