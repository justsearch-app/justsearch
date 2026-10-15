---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b56-nogpu-encoders
---

# Test-intent entries

Entry 2-5 evidence (`b56-nogpu-encoders.json`) was refreshed in records phase B (batch 3) on b599d4d7e, with the fail-before again at 6b317848e. The run sets CUDA_VISIBLE_DEVICES=-1 for the whole call, as the first run did, because NoDeviceTokenizerCacheTest runs only then. cbe6650b5 made KnowledgeServerRecoveryCloseTest stub WorkerSearchService.embeddingAvailability(), which 6b317848e lacks, so the fail-before applies the compat before-patch `gates/test-intent/evidence/b56-nogpu-encoders-compat.patch`. It adds only declarations: EmbeddingProvider.Availability with its STARTING constant, and embeddingAvailability() returning null at once. No production code at 6b317848e reads either. Every check again fails by assertion there and passes on the candidate.

```json
{
  "entries": [
    {
      "items": ["modules/configuration/src/test/java/io/justsearch/configuration/resolved/ConfigApplyScopesTest.java"],
      "class": "Structural rule kept",
      "rule": "The packaged apply-scope register is byte-identical to the repository register, every declared key is covered, and captured hardware changes require restart.",
      "whySurvives": "The brief requires a captured device decision shared by configuration and encoder startup. Its new internal device-availability key adds one register entry, so the fixed declaration count rises from 284 to 285. Byte parity remains unchanged; an added scope assertion guards restart classification of the new hardware fact.",
      "sources": [{
        "kind": "delegated-choice",
        "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
        "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
        "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
        "choice": "Device facts, the profile, the ONNX session policy and the encoder plan that recovery compares against must come from one decision",
        "label": "agent choice",
        "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b56 builder role contract, task 1)'; the text does not appear in the owner's turns of session 886833dd"
      }]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/NoDeviceEncoderPlanTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRecoveryCloseTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerStartupConfigurationTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuProfileResolverTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Actual NO_DEVICE outcomes 0, 35, 100 and 803 govern every encoder GPU flag, the NO_GPU profile and the captured CPU ONNX policies, even against explicit GPU preferences and stale GPU hardware. Initial startup receives that CPU decision. A failed native start publishes the captured desired digest before owner attachment, so real recovery admission accepts the same plan; an already CPU-configured failure isolates that recovery assertion. Successful native assembly sees the desired decision before constructing the model, and every desired publication retains the captured configuration; the earlier single-write implementation assumption is replaced by these observable boundary assertions.",
      "sources": [{
        "kind": "delegated-choice",
        "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
        "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
        "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
        "choice": "Device facts, the profile, the ONNX session policy and the encoder plan that recovery compares against must come from one decision, so the plan cannot differ from what was started.",
        "label": "agent choice",
        "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b56 builder role contract, task 1)'; the text does not appear in the owner's turns of session 886833dd"
      }],
      "evidence": "gates/test-intent/evidence/b56-nogpu-encoders.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/NoDeviceEncoderPlanTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRecoveryCloseTest.java#initialNativeFailureRetainsTheCapturedDesiredPlan",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRecoveryCloseTest.java#initialCpuFailureRetainsTheCapturedDesiredPlan",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerStartupConfigurationTest.java#enabledEmbeddingCompositionAndServiceWiringUseCapturedConfiguration",
        "modules/app-services/src/test/java/io/justsearch/app/services/gpu/GpuProfileResolverTest.java#unplannedGpuKeepsConstantsWhileNoGpuSelectsCpuTier"
      ]
    },
    {
      "items": ["modules/worker-core/src/test/java/io/justsearch/indexerworker/tokenizer/NoDeviceTokenizerCacheTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "A real CPU tokenizer initializes and executes in a masked Windows JVM when a previous GPU architecture directory already occupies the DJL cache parent. The retained GPU directory survives. Replay against the old native loader fails an assertion on its missing CPU DLL, rather than failing because the new preparation class is absent.",
      "sources": [{
        "kind": "delegated-choice",
        "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
        "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
        "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
        "choice": "find why encoders cannot start without a usable device and fix it at the cause",
        "label": "agent choice",
        "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b56 builder role contract, task 1)'; the text does not appear in the owner's turns of session 886833dd"
      }],
      "evidence": "gates/test-intent/evidence/b56-nogpu-encoders.json",
      "newChecks": ["modules/worker-core/src/test/java/io/justsearch/indexerworker/tokenizer/NoDeviceTokenizerCacheTest.java"]
    },
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/api/OperatorRerankerPlacementStatusTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "The P8, P12 and P16 GPU profiles account for an exempt GPU reranker while chat keeps its CPU plan, preserving the existing below-P8 to P8 status mapping. The documented operator reranker exemption remains enabled; an empty GPU-role set still reports NO_GPU through the existing preservation check.",
      "sources": [{
        "kind": "delegated-choice",
        "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
        "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
        "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
        "choice": "Keep the documented operator reranker exemption, but status must never report a profile or placement that claims all-CPU while a model runs on the GPU: report what actually runs where.",
        "label": "agent choice",
        "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b56 builder role contract, task 2)'; the text does not appear in the owner's turns of session 886833dd"
      }],
      "evidence": "gates/test-intent/evidence/b56-nogpu-encoders.json",
      "newChecks": ["modules/ui/src/test/java/io/justsearch/ui/api/OperatorRerankerPlacementStatusTest.java"]
    },
    {
      "items": ["modules/ort-common/src/test/java/io/justsearch/ort/DevModeVariantProbeTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "An exact generation-bound FP16 artifact remains the selected file when execution moves to CPU. Its policy uses the existing BASIC_OPT CPU treatment instead of refusing the immutable artifact or substituting a different generation identity.",
      "sources": [{
        "kind": "delegated-choice",
        "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
        "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
        "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
        "choice": "on a machine without a usable GPU embedding and reranking run on the CPU",
        "label": "agent choice",
        "provenance": "cited before as owner words at 'task t-20261005-b6d536 request (b56 builder role contract, task 1)'; the text does not appear in the owner's turns of session 886833dd"
      }],
      "evidence": "gates/test-intent/evidence/b56-nogpu-encoders.json",
      "newChecks": ["modules/ort-common/src/test/java/io/justsearch/ort/DevModeVariantProbeTest.java#witnessedDescriptorIgnoresChangedManifestAndKeepsExactFile"]
    }
  ]
}
```

## Acceptance records

Independent acceptance pending. Builder execution evidence and live results are recorded in
F:/agent-sandbox/design-b6d536/codex/b56-nogpu-encoders/report.md.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-nogpu-enc-20261010",
  "verdict": "accept",
  "digest": "sha256:249309b83ca2b423cf3008812278bb7ff3959bd4692c880c93f03982b62c4f63",
  "note": "Independent verification: 18 fail-before assertions pass on 3be25dfa2; all six candidate live cases pass (retained-cache CPU cold/restart, empty-cache CPU, visible GPU initial/restored, operator-off truthful CUDA reranker exemption). Own hardware-veto/forgery and two-process partial/empty native-cache checks pass. 7817 full safe suite cases pass, 23 skipped; 84 unsafe process/link fixture classes excluded. Evidence and limits: F:/agent-sandbox/design-b6d536/codex/verify-nogpu-enc/report.md. Unrelated governance and architecture-document gates remain failing."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-nogpu-enc-20261010",
  "verdict": "accept",
  "digest": "sha256:0270fcdcbc368d5918d20687934965607399414c8fab8fe9893b7ed7fbeac4d6",
  "note": "Independent verification: 18 fail-before assertions pass on 3be25dfa2; all six candidate live cases pass (retained-cache CPU cold/restart, empty-cache CPU, visible GPU initial/restored, operator-off truthful CUDA reranker exemption). Own hardware-veto/forgery and two-process partial/empty native-cache checks pass. 7817 full safe suite cases pass, 23 skipped; 84 unsafe process/link fixture classes excluded. Evidence and limits: F:/agent-sandbox/design-b6d536/codex/verify-nogpu-enc/report.md. Unrelated governance and architecture-document gates remain failing."
}
```
