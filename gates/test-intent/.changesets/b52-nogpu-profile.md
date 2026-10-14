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
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "make the NO_GPU profile, however it arises from the device facts, select the CPU tier for chat by construction: zero layers, the spec's CPU context (8k unless the user set context explicitly), CPU projector, CUDA hidden from the child, and a CPU activation self-test that passes with no GPU-memory measurement.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b52 builder role contract)'; the text does not appear in the owner's turns of session 886833dd"
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
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Keep below-P8 / none-fits behaviour per the spec (not NO_GPU). GPU-on profiles keep their placement.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b52 builder role contract)'; the text does not appear in the owner's turns of session 886833dd"
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
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "report whether it is order-dependent and why; fix only if it is a test isolation fault.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b52 builder role contract)'; the text does not appear in the owner's turns of session 886833dd"
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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-gpuoff3-20261010",
  "verdict": "accept",
  "digest": "sha256:1a145d777a4876173e3401b0b97f84a443614d749b05a14ac7cf45f4fd16571e",
  "note": "Accept these chat/profile/test-isolation intents: ef2c3805a fails four serving, four activation and one CPU projection assertions; candidate passes. Three real cold NO_GPU enables pass CPU0/8k/chat; explicit settings/unmet/visibility restoration pass. Old producer fixture fails trial92, candidate1024 passes. This does not approve full all-CPU NO_GPU behavior: encoder startup fails on f6, ef2 and candidate; documented operator reranker remains CUDA. Overall FAIL and limits: F:/agent-sandbox/design-b6d536/codex/verify-gpuoff3/report.md."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-gpuoff3-20261010",
  "verdict": "accept",
  "digest": "sha256:e66e41a680573b16ac74796b786fbc8b14a0214ea5f8c49af657268e2c1a1c24",
  "note": "Bind the same independent acceptance of these demonstrated test intents to the normal local governance base. The preceding f6-specific record and report contain the replay/live evidence. This is not acceptance of the overall functional behavior: NO_GPU encoder startup still fails on baseline and candidate, and the documented operator reranker exception conflicts with the all-CPU profile. Report: F:/agent-sandbox/design-b6d536/codex/verify-gpuoff3/report.md."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-nogpu-enc-20261010",
  "verdict": "reject",
  "digest": "sha256:4e79e7786a0d9c24f6cda5d458c05ffd51ee154abef523f9643455e445353b3b",
  "note": "The attached evidence is stale because GpuProfileResolverTest changed. Independent assertion replay and live CPU tier/positive-preference preservation pass, but this record does not accept stale attached evidence. Only the authorized verdict records were edited; the evidence JSON must be refreshed by its owner. Independent evidence: F:/agent-sandbox/design-b6d536/codex/verify-nogpu-enc/report.md."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-nogpu-enc-20261010",
  "verdict": "reject",
  "digest": "sha256:f9741c1ae390484271cd53f6479c36ceac54c75015da90b2079a33be6f4b44cd",
  "note": "The attached evidence is stale because GpuProfileResolverTest changed. Independent assertion replay and live CPU tier/positive-preference preservation pass, but this record does not accept stale attached evidence. Only the authorized verdict records were edited; the evidence JSON must be refreshed by its owner. Independent evidence: F:/agent-sandbox/design-b6d536/codex/verify-nogpu-enc/report.md."
}
```
