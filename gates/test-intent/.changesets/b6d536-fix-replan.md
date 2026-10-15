---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: codex-b6d536-fix-replan-20261015
---

# Activation profile planning

The before-state is 0d90a3388. The stock run-evidence producer runs the candidate checks and
overlays those checks into a detached before-state worktree. Its JVM runs need no node_modules;
an external Node preload suppresses the producer's optional cross-checkout junction and checks
and unlinks junctions before its temporary worktree is removed. No production compatibility patch
or overlay is used. Process identities and command logs are under
F:/agent-sandbox/build-b6d536-replan/.

```json
{
  "entries": [
    {
      "items": ["modules/app-services/src/test/java/io/justsearch/app/services/settings/SettingsCommitCoordinatorTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "A transient profile candidate resolves that profile before component preparation, with the governed profile/model/projector keys reaching the one generative owner alongside its transient intent. Existing commitment and installation assertions remain intact.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn Builder brief: re-plan encoder memory caps when the chat profile changes at activation, Agreed design",
          "quote": "The plan is resolved at activation (RuntimeActivationService), before encoder composition and the controller's first start, on a settings commit that touches §2.4 keys or model selections, and on a model install or removal."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Fix it with the smallest change that follows §2.5:",
          "delegationLocation": "task t-20261005-b6d536 user turn Builder brief: re-plan encoder memory caps when the chat profile changes at activation, Task 2",
          "delegationScope": "Carry activation's selected chat model into device planning before existing settings classification and composition.",
          "choice": "Project the selected profile's model/projector pair into the candidate resolved configuration, preserving its attempt scope and reporting all three changed configuration keys to the governed generative owner.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-fix-replan.json",
      "newChecks": ["modules/app-services/src/test/java/io/justsearch/app/services/settings/SettingsCommitCoordinatorTest.java#transientProfileForcesOnePreparedGenerativeOwnerEvenWithoutASettingsDelta"]
    },
    {
      "items": ["modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceChatProfileTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "Both activation profile transitions install the same embedding, SPLADE and reranker caps as a steady start of the selected profile, route changed caps through encoder settings composition, and retain that plan on an unrelated settings commit. An interrupted committed activation recovers the selected profile's caps and encoder composition before completion.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn Builder brief: re-plan encoder memory caps when the chat profile changes at activation, Task 2",
          "quote": "Re-resolve on activation with a changed profile or model."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn Builder brief: re-plan encoder memory caps when the chat profile changes at activation, Task 2",
          "quote": "Changed caps recompose the encoders through the existing settings path, under unchanged admission."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn Builder brief: re-plan encoder memory caps when the chat profile changes at activation, Agreed design",
          "quote": "The plan is resolved at activation (RuntimeActivationService), before encoder composition and the controller's first start, on a settings commit that touches §2.4 keys or model selections, and on a model install or removal."
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-fix-replan.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceChatProfileTest.java#compactToStandardActivationReplansEncoderCaps",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceChatProfileTest.java#standardToCompactActivationReplansEncoderCaps",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceChatProfileTest.java#committedProfileRecoveryReplansEncoderCaps"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceChatProfileTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatServerControllerImplTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation checks: unchanged-profile activation does not prepare encoders; a settings chat model-path selection re-plans their caps; chat controller start and stop execute without calling the planner. Existing activation assertions and controller assertions are retained; the recording composer adds affected-encoder observations and a disabled query-role selection for its simulated encoder owner. Property cleanup retains the original absent value across repeated writes so the added profile transitions cannot leak into later tests.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn Builder brief: re-plan encoder memory caps when the chat profile changes at activation, Task 2",
          "quote": "An equal plan stays a no-op."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn Builder brief: re-plan encoder memory caps when the chat profile changes at activation, Task 2",
          "quote": "Never re-resolve on chat start or stop."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn Builder brief: re-plan encoder memory caps when the chat profile changes at activation, Agreed design",
          "quote": "The plan is resolved at activation (RuntimeActivationService), before encoder composition and the controller's first start, on a settings commit that touches §2.4 keys or model selections, and on a model install or removal."
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-fix-replan.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceChatProfileTest.java#unchangedProfileActivationDoesNotRecomposeEncoders",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceChatProfileTest.java#settingsChatModelSelectionReplansEncoderCaps",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatServerControllerImplTest.java#chatStartAndStopDoNotResolveEncoderPlan"
      ]
    }
  ]
}
```

## Acceptance records
