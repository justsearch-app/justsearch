---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b51-codex-builder
---

# Test-intent entries

Evidence is regenerated on 2e6fca66d against the original 3241289f5 before-state. The later
chat-profile fixture now calls ChatArtifactIdentity.rememberWritten; its absent declaration
initially prevented all four selected app-services checks from compiling. The add-only
b51-gpuoff-consistency-compat.patch declares that method as a no-op. No production code at
3241289f5 names the class, so the before-state keeps its own model-identity, planning, layer
selection and activation behavior. No assertion or test input is patched.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationAutomaticCpuTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationCpuCommitTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Automatic CPU profile layers remain zero at the actual self-test and accepted settings boundaries. CPU activation has no adapter VRAM measurement, uses a CPU success message, and preserves explicit context and layers including zero.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Activation and self-test must take their default layers from the same profile/plan decision the serving launch uses, not from a hard-coded default; only an explicit user value overrides it.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b51 builder role contract)'; the text does not appear in the owner's turns of session 886833dd"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "A CPU activation reports no VRAM measurement and describes itself truthfully.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b51 builder role contract)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b51-gpuoff-consistency.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationAutomaticCpuTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationCpuCommitTest.java"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/GpuOffUnmetStatusTest.java",
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/PlannedContextSettingsIntegrationTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Both policy and explicit master GPU-off keep saved 4096 context / 12 layers, publish user_setting_unmet for layers through operating status and real settings/status HTTP, and clear it after zero. CPU context and GPU-off are met and have no spurious warning.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Raise the existing user_setting_unmet (layers) standing degradation with inference.setting_unmet, setting kept, cleared when no longer unmet.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b51 builder role contract)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b51-gpuoff-consistency.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/GpuOffUnmetStatusTest.java",
        "modules/ui/src/integrationTest/java/io/justsearch/ui/api/PlannedContextSettingsIntegrationTest.java#cpuPlacementKeepsTheHttpLayerSettingAndPublishesThenClearsItsUnmetNotice"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/CpuCrashClassificationTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "An intentionally hidden CUDA device cannot classify a CPU child crash as DEVICE_SUSPECT. Existing stable restart and early-crash bounds continue through the actual captured server-failure classification.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Also investigate (report, fix if contained and caused by GPU-off handling): CRASH-RECOVERY in the same report",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b51 builder role contract)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b51-gpuoff-consistency.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/CpuCrashClassificationTest.java"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationChatSwitchTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceChatProfileTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Existing chat-switch, profile selection and operator precedence scenarios preserve an automatic layer preference instead of inventing a saved 99-layer override. All other assertions are retained.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Activation and self-test must take their default layers from the same profile/plan decision the serving launch uses, not from a hard-coded default; only an explicit user value overrides it.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b51 builder role contract)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b51-gpuoff-consistency.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationChatSwitchTest.java#chatTurnedOnDuringTheSelfTestIsKeptAndTheActivationCompletes",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceChatProfileTest.java"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationCpuDevicePlanTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The automatic GPU test supplies the 99-layer resolved profile at auto-detect ordinal 150, rather than relying on the removed activation constant. Its 99 / explicit zero / explicit 12 / higher-priority environment zero and CPU launch assertions are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Activation and self-test must take their default layers from the same profile/plan decision the serving launch uses, not from a hard-coded default; only an explicit user value overrides it.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b51 builder role contract)'; the text does not appear in the owner's turns of session 886833dd"
        }
      ]
    }
  ]
}
```

## Acceptance records

Independent acceptance pending.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-gpuoff2-20261010",
  "verdict": "reject",
  "digest": "sha256:91f4c09083875d8e336d16b30844f61b266df82bac3745f6135032963459bc35",
  "note": "Independent reject for the full requested outcome. Actual activation automatic-zero, explicit-zero/context/layer preservation, CPU telemetry without GPU measurements, and unmet-layer notice/clearing checks pass on the candidate; compatible earlier checks fail by assertion on f6d579a86 or 3241289f5 for their tested repair. Own automatic-GPU-99 preservation check passes. The broader authoritative-NO_GPU planner/command seam remains wrong: retained automatic 99 is not vetoed on all three revisions. Complete safe services rerun and engine product suites pass; governance adds twelve normalized findings vs the first repair. Visible UI writes only gpuLayers; administrative policy documents blocked GPU-variant activation, so its 403 is not a user-switch consistency failure. Live evidence is pending due to foreign ownership. Evidence: F:/agent-sandbox/design-b6d536/codex/verify-gpuoff2/report.md."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-gpuoff2-20261010",
  "verdict": "reject",
  "digest": "sha256:91f4c09083875d8e336d16b30844f61b266df82bac3745f6135032963459bc35",
  "note": "Independent reject: automatic NO_GPU CPU activation/telemetry still fails live. Fresh masked-device run reports GPU self-test inconclusive and NOT activated, with adapter VRAM before/after 835743744 and delta 0, instead of CPU activation and null measurements. Explicit CPU activation, saved zero/positive layers and context preservation, automatic GPU 99 preservation, layer-unmet notice/clearing, full CPU restart and stable crash RESTART_SAME pass; first early crash is bounded. Initial zero-layer enable returned GENERATIVE_PREPARATION_REFUSED, repeat passed; cause remains unexplained. Safe complete product suites pass after the documented services rerun; governance adds twelve normalized findings vs first repair. The UI writes only gpuLayers, without a separate global GPU-off switch; administrative policy GPU-variant 403 is documented and passes, CPU chat toggle still works. Evidence: F:/agent-sandbox/design-b6d536/codex/verify-gpuoff2/report.md."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-gpuoff3-20261010",
  "verdict": "accept",
  "digest": "sha256:91f4c09083875d8e336d16b30844f61b266df82bac3745f6135032963459bc35",
  "note": "Independent check-intent acceptance: b51 immediate-parent replay on 3241289f5 fails one crash, nine service/commit and two unmet assertions; candidate passes. Saved zero/context and positive layers survive, CPU activation passes with null GPU measurements, early/stable CPU crashes follow existing bounds. Administrative policy refusal matches documentation. Overall profile gaps remain in verify-gpuoff3/report.md."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-gpuoff3-20261010",
  "verdict": "accept",
  "digest": "sha256:7a8a43386bc088cb80f2efdf2b097fa22e23e014611c0bdd0d59ebaff80f1a16",
  "note": "Bind the same independent acceptance of these demonstrated test intents to the normal local governance base. The preceding f6-specific record and report contain the replay/live evidence. This is not acceptance of the overall functional behavior: NO_GPU encoder startup still fails on baseline and candidate, and the documented operator reranker exception conflicts with the all-CPU profile. Report: F:/agent-sandbox/design-b6d536/codex/verify-gpuoff3/report.md."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS4b-20261015",
  "verdict": "accept",
  "digest": "sha256:24a453bf39fe74fec00f5f4db100dd12f4cfee7d038b9200be18c7b61228bfe9",
  "note": "Current candidate checks pass all 33 case references, including the expanded profile fixture. Fresh 3241289f5 replay with the declared add-only compatibility class matches all seven assertion-classified check IDs; the no-op class has no original production consumer and supplies no activation decision."
}
```
