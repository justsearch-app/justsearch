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
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "make \"GPU off\" mean no CUDA use by the chat server and its self-test",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b48 builder role contract)'; the text does not appear in the owner's turns of session 886833dd"
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
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "the launch plan must derive every device-related flag and environment variable from the one effective device policy",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b48 builder role contract)'; the text does not appear in the owner's turns of session 886833dd"
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
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "make \"GPU off\" mean no CUDA use by the chat server and its self-test",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b48 builder role contract)'; the text does not appear in the owner's turns of session 886833dd"
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
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "make \"GPU off\" mean no CUDA use by the chat server and its self-test",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b48 builder role contract)'; the text does not appear in the owner's turns of session 886833dd"
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
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "make \"GPU off\" mean no CUDA use by the chat server and its self-test",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b48 builder role contract)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b48-new-api-replay.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaDevicePlanTest.java"
      ],
      "beforeState": "before-state not reproducible: f6d579a86 has no LlamaDevicePlan type. The real unpatched replay fails compileTestJava with cannot find symbol LlamaDevicePlan. Supplying resolve, layerFlags, deviceFlags, projectorFlags and environment would implement the policy values this class asserts, so such a patch or stub would decide its outcome. run-evidence transplants the current test file after patches, so a before-API variant cannot replace this class under the recorded id."
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/SelfTestDeviceEnvironmentTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The self-test child receives the plan environment together with its matching argv and GPU ownership, for CPU and pinned GPU launches. The before-state is f6d579a86 plus the digest-bound b48-selftest-environment-compat.patch: LlamaDevicePlan only constructs the command/environment input fixture, and SelfTestSpec captures an extra environment component while retaining its original constructor. ChildSelfTestLauncher and ChildSpec are unmodified; only the real launcher can propagate the captured environment into ChildSpec.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "GPU on must keep today's placement (pinned device, layers, projector placement per profile); the self-test must follow the same plan.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b48 builder role contract)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b48-selftest-environment-repaired.json",
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
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "make \"GPU off\" mean no CUDA use by the chat server and its self-test",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b48 builder role contract)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b48-new-api-replay.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationCpuDevicePlanTest.java"
      ],
      "beforeState": "before-state not reproducible: f6d579a86 has neither LlamaDevicePlan nor RuntimeActivationService.selfTestDevicePlan(UiSettings), and its SelfTestSpec has no environment component. The real unpatched replay fails compileTestJava on the missing plan type. Adding the reflected helper would supply the exact automatic/explicit/environment layer-selection values asserted by the first case, thereby implementing the behaviour under test rather than neutral compatibility. A before-API variant is overwritten by run-evidence when it transplants this current test file."
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
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "GPU on must keep today's placement (pinned device, layers, projector placement per profile); the self-test must follow the same plan.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b48 builder role contract)'; the text does not appear in the owner's turns of session 886833dd"
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

### Signoff repair, entries 5–7 (2026-10-15)

The original new evidence remains attached only to entry 8; that entry and every other entry
outside 5–7 are unchanged. `b48-new-api-replay.json` was produced by run-evidence with the three
current classes and `--before f6d579a86`, without overlays or patches. All nine candidate cases
passed (6 device-plan, 1 child-environment, 2 activation); neither before Gradle invocation reached
a case because the plan type is absent. Entries 5 and 7 state the concrete candidate-only reasons
in their `beforeState` fields; the compile failures are attempted replay records, not repair proof.
The absence of the reflected activation helper and environment component was also checked in
the sources at that exact ref.

Entry 6 instead has its own repaired evidence and digest-bound compatibility patch. Its added
plan type is an input fixture here: the expected environment is supplied to SelfTestSpec, while
the assertion reads the ChildSpec actually received by ChildControl. The patch adds no launcher
propagation, child ownership decision, or expected output. It must not be used as fail-before
evidence for entry 5 (whose subject is that type). The before run fails in the first (CPU)
iteration: `expected: <-1> but was: <null>`, so the GPU iteration is not reached there. The
candidate case passes both iterations, including argv and GPU-ownership assertions.

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-gpuoff-20261010",
  "verdict": "reject",
  "digest": "sha256:a2b5ae0c96e4fd57585d6cb6857a13128d95c0cd911536164c8bcd47b547fe88",
  "note": "Independent rejection for the requested outcome: the automatic no-GPU / zero-profile launch oracle selects 99 GPU layers, and the actual runSelfTest boundary reproduces the no-GPU case. CPU runSelfTest publishes unrelated adapter VRAM as vramBefore. Real GPU-off POST /api/ai/runtime/activate returns 403 POLICY_GPU_DISABLED before the CPU self-test; disabling and re-enabling chat succeeds. Saved GPU-off native placement is repaired (zero dedicated/shared GPU MiB, CPU compute and projector). Four repaired checks fail before by assertion and pass after; remaining new-API checks lack an independent assertion-failure replay on the parent. Evidence: F:/agent-sandbox/design-b6d536/codex/verify-gpuoff/report.md. GPU-on and restart live comparisons remain pending."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-gpuoff-20261010",
  "verdict": "reject",
  "digest": "sha256:e535aa2e9cb55f59f780c2db2dd40e61dd6a31406c6c8ee5f7a8bbaaf037836e",
  "note": "Final independent verdict: reject. Automatic no-GPU and no-room CPU profiles select 99 layers at the actual runSelfTest boundary; CPU self-test exposes machine-wide VRAM measurements, reproduced through the real API. Saved GPU-off policy repairs native placement and survives restart, but direct activation gives 403 POLICY_GPU_DISABLED and VDU is blocked vdu.ai_offline. Explicit environment GPU-off permits CPU activation and VDU placement. Saved 12-layer setting becomes 0 without USER_SETTING_UNMET status. GPU-on P12 argv and absolute GPU identities are preserved; explicit context/layers and zero survive activation/restarts. Full safe suites, architecture, lane F, regen and API gate completed; nine governance errors added. New-API assertion replay, real retries, automatic crash restart and old-child handling remain gaps. Evidence F:/agent-sandbox/design-b6d536/codex/verify-gpuoff/report.md."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-gpuoff2-20261010",
  "verdict": "reject",
  "digest": "sha256:8b5b7295679b2ba0cb9ead47839c63b4e55aa91eaef6d3f6bfec282d3219f0db",
  "note": "Independent reject for the requested item-5 outcome. Compatible repaired CPU flags/architecture checks fail by assertion on f6d579a86 and pass on 7f908530c; GPU profile and identity preservation pass all three revisions. Own production planner/serving-command seam receives authoritative RAN_NO_DEVICE with retained automatic 99, publishes NO_GPU and still requests 99 layers: four assertions fail on all three revisions. Current b48 new evidence has a stale RuntimeActivationCpuDevicePlanTest hash; individual new-type checks lack an assertion replay on f6. Live comparisons remain pending while another session owns the stack. Policy GPU-variant 403 is documented, not rejected as inconsistency. Evidence: F:/agent-sandbox/design-b6d536/codex/verify-gpuoff2/report.md."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-gpuoff2-20261010",
  "verdict": "reject",
  "digest": "sha256:8b5b7295679b2ba0cb9ead47839c63b4e55aa91eaef6d3f6bfec282d3219f0db",
  "note": "Independent reject for the full item-5 outcome. Compatible CPU device/flag checks fail by assertion on f6d579a86 and pass on 7f908530c. Explicit layer-zero, operator-off and policy-off live llama placement pass; layer-zero dedicated/shared GPU memory is zero, CPU VDU/projector and chat pass. GPU profile/identity preservation passes. However, four production planner/command assertions fail on all three revisions for authoritative RAN_NO_DEVICE with retained automatic 99. Live fresh CUDA_VISIBLE_DEVICES=-1 reproduces RAN_NO_DEVICE code 100 and published NO_GPU, yet serving and self-test request 99 GPU layers; serving context remains 32768. The external mask prevents CUDA, not an application CPU plan. Current b48 evidence has a stale RuntimeActivationCpuDevicePlanTest hash; individual new-type checks lack an assertion replay on f6. Evidence: F:/agent-sandbox/design-b6d536/codex/verify-gpuoff2/report.md."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-gpuoff3-20261010",
  "verdict": "accept",
  "digest": "sha256:8b5b7295679b2ba0cb9ead47839c63b4e55aa91eaef6d3f6bfec282d3219f0db",
  "note": "Independent checks: four compatible CPU flags/environment/owner assertions fail on f6d579a86 and pass candidate. Real saved-zero CPU placement, memory, VDU launch and restart pass. Acceptance of these check intents only; live retry/old-identity gaps and overall NO_GPU encoder failure are recorded in F:/agent-sandbox/design-b6d536/codex/verify-gpuoff3/report.md."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-gpuoff3-20261010",
  "verdict": "accept",
  "digest": "sha256:d2ee021aa7cff4fac58d0e9f5e9678870c8f59609f94dae83f538d57579fd008",
  "note": "Bind the same independent acceptance of these demonstrated test intents to the normal local governance base. The preceding f6-specific record and report contain the replay/live evidence. This is not acceptance of the overall functional behavior: NO_GPU encoder startup still fails on baseline and candidate, and the documented operator reranker exception conflicts with the all-CPU profile. Report: F:/agent-sandbox/design-b6d536/codex/verify-gpuoff3/report.md."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS1-20261015",
  "verdict": "reject",
  "digest": "sha256:3c6a80566e419698a31de0a3c4990e81704be865105062cca90cd1bafeb73cee",
  "note": "Reject entries 5-7: their candidate-only b48-llama-gpu-off-new.json evidence has failBefore null, and neither the entries nor shared preamble explains why before execution is not reproducible. Candidate checks and the separately declared repaired before checks pass their required outcomes; they do not supply the missing reason."
}
```
