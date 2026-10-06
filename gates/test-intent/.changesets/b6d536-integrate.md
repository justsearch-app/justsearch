---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, integration of the chat controller (Spec A) with the child consumers
(Spec B), the device-lost search stack and the bulk work gate (Spec E). The merges change two
production signatures that pre-existing tests call, and add two TransitionReason members to the
chat server's stop-reason table:

- `ChatActuator.terminalStop` takes the ChildControl stop reason (Spec B) as well as the timeout.
  Four test actuators implement it.
- The public two-argument `ChatServerControllerImpl` constructor, whose self-test launcher always
  failed, is removed: it existed only for tests. Two tests now use the production constructor with
  a self-test launcher of their own that fails the same way.
- The chat controller's residency unload and reload (Spec A step 9) reach the child stop table as
  RESIDENCY_UNLOAD and RESIDENCY_RELOAD.
- Reads the merged slices made outside the configuration funnel go through it: the Engine
  incarnation and eval mode are `EnvRegistry` keys, and the llama-server launches no longer read
  the process `PATH`; `ChildSpec` carries a path prefix that child control puts in front of the
  `PATH` the child inherits. New checks pin both.

## Evidence that goes stale on the integration branch only

Each slice below ships as its own stacked PR, where its evidence was run against the test files
as that branch has them and stays valid. On this branch a test file the evidence covers was also
changed by another slice merged here, so the gate reports the evidence as stale. It is not
re-run here.

| Changeset | Test file changed after its evidence run | Changed by |
| --- | --- | --- |
| `b6d536-chat-controller-step4.md` | `LlamaServerStartFailureTest.java` | child-consumers: stops take a stop request (`stopLlamaServer(StopRequest)`) |
| `b6d536-chat-controller-step78.md` | `ChatBootOrderTest.java` | this integration: `terminalStop(StopReason, Duration)` (entry 2 above) |
| `b6d536-chat-controller-step78.md` | `readinessNotice.test.ts` | device-lost: the Spec D K3 stuck-encoder case, beside step 7's cases |
| `b6d536-search-gate.md` | `readinessNotice.test.ts` | chat-controller: step 7's cases, beside the K3 case |
| `b6d536-chat-controller-step9.md` | `ChatControllerStepFailureTest.java`, `ChatResidencyTest.java`, `ChatResidencyRoutesTest.java` | this integration: the stop signature and the constructor (entries 1 and 2 above) |
| `b6d536-llama-child.md` | `DefaultChildControlTest.java` | child-consumers (Spec B steps 5-9) |
| `b6d536-llama-child.md` | `LlamaServerChildControlTest.java` | this integration: the two stop-table rows (entry 3 above) |
| `b6d536-no-adoption.md` | `InferenceLifecycleManagerApplyConfigTest.java`, `InferenceLifecycleManagerShutdownTest.java`, `LlamaServerCandidateContextTest.java`, `LlamaServerProcessOwnershipTest.java`, `ManagedLlamaAdoptionTest.java` | child-consumers: the ChildControl stop call; bulk-gate-2 E6 refreshed `b6d536-no-adoption-rewrites-inference.json` against its own versions |
| `b6d536-shared-foundation.md` | `OperatingStateAssemblerTest.java` | bulk-gate-2 (Spec E E3/E4 rows) |
| `b6d536-child-control.md` | `RuntimeManifestIncarnationPublishTest.java`, `DefaultChildControlTest.java`, `FakeChild.java` | this integration: the system-reads checks (the last New entries above) are added to these files |
| `b6d536-bulk-gate-e6.md` | `LlamaServerDeviceLaunchTest.java` | the E6 merge: `stopLlamaServer(LlamaServerTestAccess.TEST_STOP)`, the child-consumers stop signature |

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatControllerStepFailureTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/ChatResidencyRoutesTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Two signature moves. (1) The ChatActuator stand-in each test defines implements terminalStop(StopReason reason, Duration timeout) instead of terminalStop(Duration timeout); the body is unchanged and ignores the reason, as it ignored nothing else before. (2) The public constructor ChatServerControllerImpl(executors, actuator) is removed. Each test now calls the production constructor ChatServerControllerImpl(executors, actuator, SelfTestLauncher) with the launcher spec -> SelfTestRun.failed(...), which is what the removed constructor passed: same drain bound (ChatActuationRouter.DRAIN_BOUND) and boot-gate timeout (BOOT_GATE_TIMEOUT) through the same delegation chain, and a self-test that fails without launching anything. Neither test runs a self-test. Inputs, actuator behaviour, observations and every expected value are unchanged."
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResidencyTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatBootOrderTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "The ChatActuator stand-in each test defines implements terminalStop(StopReason reason, Duration timeout) instead of terminalStop(Duration timeout), the signature the ChildControl stop call (Spec B) gives the interface. The bodies are unchanged and ignore the reason. Inputs, actuator behaviour, observations and every expected value are unchanged."
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerChildControlTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "The chat server's stop for every TransitionReason other than SHUTDOWN is the ChildControl StopReason the test's own expected table declares, and the table covers every TransitionReason member.",
      "whySurvives": "The predicate and the loop over TransitionReason.values() are unchanged. The declared table gains two rows for the two members the chat residency branch (Spec A step 9) added to TransitionReason and the child-consumers branch did not know: RESIDENCY_RELOAD stops like the other demand loads (USER_SWITCH, AUTO_START) as DEACTIVATE, and RESIDENCY_UNLOAD stops as RESIDENCY_PRESSURE, the reason the residency unload records. Every existing row keeps its value."
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/DefaultChildControlTest.java",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/FakeChild.java",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestIncarnationPublishTest.java"
      ],
      "class": "New",
      "behaviour": "(1) A ChildSpec path prefix reaches the child: a real child started through DefaultChildControl sees exactly one PATH variable whose value is the prefix directories, in order, then the PATH it inherits, unchanged. The fake child's new print-path mode reports its PATH variables; the assertion messages print no PATH value. (2) The Engine reads its supervised incarnation through the configuration registry: with justsearch.engine.incarnation set to run-registry/3, the runtime manifest the public publisher constructor seeds names session run-registry and n 3. Before the change both fail by assertion (the before-state given only the new ChildSpec and EnvRegistry): the child's PATH has no prefix, and the manifest has no incarnation because the publisher read only the process environment.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for items 5 and 6",
          "label": "agent choice",
          "choice": "Reads the integrated slices made outside the configuration funnel go through it. The supervised Engine incarnation (Spec F section 3.2) is the EnvRegistry key justsearch.engine.incarnation / JUSTSEARCH_ENGINE_INCARNATION, the variable name the supervisors already set. The process PATH is not registered, because that would print the machine's PATH in the effective-config output; instead a launch names the directories to put in front of PATH (ChildSpec.pathPrefix) and DefaultChildControl joins them to the PATH the child inherits, so no launch site reads the process environment."
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-integrate-system-reads-repaired.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/DefaultChildControlTest.java#pathPrefixGoesInFrontOfTheInheritedPath",
        "modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeManifestIncarnationPublishTest.java#theEngineReadsItsIncarnationThroughTheConfigurationRegistry"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/ChildPathPrefixTest.java",
        "modules/configuration/src/test/java/io/justsearch/configuration/EnvRegistryTest.java"
      ],
      "class": "New",
      "behaviour": "(1) The path prefix keeps the inherited variable's spelling: an environment holding Path gets the prefix directories, in order, in front of its value under Path, and no second PATH variable; an environment with no PATH gets PATH set to the prefix alone. (2) The two new registry keys: ENGINE_INCARNATION is justsearch.engine.incarnation / JUSTSEARCH_ENGINE_INCARNATION with no default (the name the dev runner's engine actuator sets), and EVAL_MODE is justsearch.eval.mode / JUSTSEARCH_EVAL_MODE and reads true when the system property is true (the dev run's -Djustsearch.eval.mode=true).",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "completing the agreed design for items 5 and 6",
          "label": "agent choice",
          "choice": "On Windows the inherited variable is spelled Path or PATH and a second spelling would be a second variable, so child control reuses the inherited key whatever its case. Eval mode, which the step 9 dev pressure route read with Boolean.getBoolean, is the EnvRegistry key justsearch.eval.mode / JUSTSEARCH_EVAL_MODE; the incarnation key keeps the supervisors' variable name."
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-integrate-system-reads-new.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/child/ChildPathPrefixTest.java",
        "modules/configuration/src/test/java/io/justsearch/configuration/EnvRegistryTest.java#engineIncarnationEntryShape",
        "modules/configuration/src/test/java/io/justsearch/configuration/EnvRegistryTest.java#evalModeEntryShape"
      ]
    },
    {
      "items": [
        "modules/configuration/src/test/java/io/justsearch/configuration/resolved/ConfigApplyScopesTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "One register count. packagedRegisterIsTheRepositoryRegister counts 284 register entries instead of 282: the two added entries are justsearch.engine.incarnation and justsearch.eval.mode (both restart-required), the keys gates/config-surface/.changesets/b6d536-system-reads-keys.md declares. The byte comparison of the packaged register with the repository register and the index.boosts scope lookup are unchanged."
    }
  ]
}
```
