---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: codex-b6d536-fix-replan-20261015
---

# Activation profile planning

The before-state for the original planning checks is 0d90a3388. The new physical-session checks
use 2c499df0d, the round 1 fix that resolves the plan but retains old bulk sessions. The stock
run-evidence producer runs the candidate checks and
overlays those checks into a detached before-state worktree. Its JVM runs need no node_modules;
an external Node preload suppresses the producer's optional cross-checkout junction and checks
and unlinks junctions before its temporary worktree is removed. No production compatibility patch
or overlay is used. Process identities and command logs are under
F:/agent-sandbox/build-b6d536-replan-r2/ (round 1: F:/agent-sandbox/build-b6d536-replan/).
The physical fixture runs activation, the settings transaction, the physical encoder owner and
session policy composition. Native loading, tokenizer assembly, inference and producer services
are stand-ins; it does not establish live GPU allocation, search continuity or E2/E3 timing.

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
    },
    {
      "items": ["modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ActivationPhysicalEncoderCapsTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "Compact-to-standard and standard-to-compact activation publish physical embedding, SPLADE and reranker sessions with the selected profile's steady caps. The checks inspect the serving owners' policies and per-handle construction caps, and require each source session to retire before its corresponding replacement is constructed, permitting role-by-role release. The permitted before-state for these physical checks is 2c499df0d.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn Builder brief (round 2), Task 2",
          "quote": "Make a changed plan actually rebuild every encoder session whose cap changed, bulk and query alike, through the existing recompose path."
        },
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn Builder brief (round 2), Task 3",
          "quote": "after a compact-to-standard activation, the physical sessions (bulk and query) carry the standard caps. Add the reverse direction too."
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-fix-replan-physical.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ActivationPhysicalEncoderCapsTest.java#compactToStandardActivationReplacesPhysicalBulkAndQueryCaps",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ActivationPhysicalEncoderCapsTest.java#standardToCompactActivationReplacesPhysicalBulkAndQueryCaps"
      ]
    },
    {
      "items": ["modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ActivationPhysicalEncoderCapsTest.java"],
      "class": "New",
      "behaviour": "Preservation: refusal after native preparation restores the source resolved configuration, captured index plan, physical bulk/query caps and a warm query handle, with no extra open physical sessions. The same check passes against 2c499df0d, where the unchanged bulk owner remains usable.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn Builder brief (round 2), Task 2",
          "quote": "searches keep serving during recomposition the way they do during any settings recompose (a lexical fallback with a reason is acceptable);"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "Make a changed plan actually rebuild every encoder session whose cap changed, bulk and query alike, through the existing recompose path.",
          "delegationLocation": "task t-20261005-b6d536 user turn Builder brief (round 2), Task 2",
          "delegationScope": "Keep the existing settings transaction's refusal recovery while extending it to physical bulk replacement.",
          "choice": "Exercise refusal after preparation and require usable source sessions, exact source plan and no extra native owners after restoration.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-fix-replan-physical.json",
      "newChecks": ["modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ActivationPhysicalEncoderCapsTest.java#precommitRefusalRestoresSourcePhysicalCapsAndWarmQuery"]
    },
    {
      "items": ["modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerQuerySettingsOwnerTest.java"],
      "class": "Adaptation",
      "moved": ["rename"],
      "whatMoved": "Clarify that the automatic-cap preservation fixture has an empty physical bulk surface: its index owner is retained because it has no sessions to rebuild. The existing assertions and inputs remain; remove one redundant assertion qualifier. The new activation checks separately cover real bulk handles.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 user turn Builder brief (round 2), Task 2",
          "quote": "Make a changed plan actually rebuild every encoder session whose cap changed, bulk and query alike, through the existing recompose path."
        }
      ]
    }
  ]
}
```

## Acceptance records
