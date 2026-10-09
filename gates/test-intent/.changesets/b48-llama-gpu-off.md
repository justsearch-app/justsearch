---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b48-codex-builder
---

# Test-intent entries

P4 GPU-off placement repair. Only this builder's eight test files are covered here. Independent acceptance remains for the verifier; the builder does not append it.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaGpuOffPlanTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "A CPU chat launch must disable all devices and vision projector offload, even when projector offload defaults to true. Merely setting zero transformer layers leaves CUDA compute and CLIP buffers allocated.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "make \"GPU off\" mean no CUDA use by the chat server and its self-test",
          "location": "task t-20261005-b6d536 request (b48 builder role contract)"
        }
      ],
      "evidence": "gates/test-intent/evidence/b48-llama-gpu-off-repaired.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaGpuOffPlanTest.java"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaDevicePlanArchitectureTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "No chat or self-test launch owner may construct device-selection flags or CUDA visibility outside LlamaDevicePlan.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "the launch plan must derive every device-related flag and environment variable from the one effective device policy",
          "location": "task t-20261005-b6d536 request (b48 builder role contract)"
        }
      ],
      "evidence": "gates/test-intent/evidence/b48-llama-gpu-off-repaired.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaDevicePlanArchitectureTest.java"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerLaunchFlagsTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The full CPU argv adds device none while preserving CPU context, zero layers, slots, unified KV, quantization and fit flags. All GPU, VDU, context and reasoning checks retain their oracles.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "make \"GPU off\" mean no CUDA use by the chat server and its self-test",
          "location": "task t-20261005-b6d536 request (b48 builder role contract)"
        }
      ],
      "evidence": "gates/test-intent/evidence/b48-llama-gpu-off-repaired.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerLaunchFlagsTest.java"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerPinnedDeviceEnvironmentTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The former CPU no-device expectation was wrong: CPU must explicitly disable offload devices. GPU pin flags and environment expectations are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "make \"GPU off\" mean no CUDA use by the chat server and its self-test",
          "location": "task t-20261005-b6d536 request (b48 builder role contract)"
        }
      ],
      "evidence": "gates/test-intent/evidence/b48-llama-gpu-off-repaired.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerPinnedDeviceEnvironmentTest.java#cpuLaunchDisablesDevices"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaDevicePlanTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "Effective explicit master GPU-off or policy GPU-off wins over requested layers, pinning and projector defaults; explicitly resolved layers also win over caller or activation defaults. Zero layers hides CUDA; pinned and unpinned GPU behavior is preserved.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "make \"GPU off\" mean no CUDA use by the chat server and its self-test",
          "location": "task t-20261005-b6d536 request (b48 builder role contract)"
        }
      ],
      "evidence": "gates/test-intent/evidence/b48-llama-gpu-off-new.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaDevicePlanTest.java"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/SelfTestDeviceEnvironmentTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "The self-test child receives the plan environment together with its matching argv and GPU ownership, for CPU and pinned GPU launches.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "GPU on must keep today's placement (pinned device, layers, projector placement per profile); the self-test must follow the same plan.",
          "location": "task t-20261005-b6d536 request (b48 builder role contract)"
        }
      ],
      "evidence": "gates/test-intent/evidence/b48-llama-gpu-off-new.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/SelfTestDeviceEnvironmentTest.java"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationCpuDevicePlanTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "A GPU-off activation self-test submits zero layers, device none, CPU projector and hidden CUDA to the session; it can pass without NVML or a GPU-memory delta and does not rewrite requested layers. Automatic activation probes its 99-layer candidate; explicit zero, positive layers and higher-priority environment settings remain effective.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "make \"GPU off\" mean no CUDA use by the chat server and its self-test",
          "location": "task t-20261005-b6d536 request (b48 builder role contract)"
        }
      ],
      "evidence": "gates/test-intent/evidence/b48-llama-gpu-off-new.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationCpuDevicePlanTest.java"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/inference/LlamaProfileDevicePlanTest.java"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "Actual Engine contributions for P16_PLUS, P12 and P8 preserve 99 layers, pinned CUDA0 and per-profile projector placement. These preservation checks also run on f6d579a86.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "GPU on must keep today's placement (pinned device, layers, projector placement per profile); the self-test must follow the same plan.",
          "location": "task t-20261005-b6d536 request (b48 builder role contract)"
        }
      ],
      "evidence": "gates/test-intent/evidence/b48-llama-gpu-off-new.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/inference/LlamaProfileDevicePlanTest.java"
      ]
    }
  ]
}
```

## Acceptance records
