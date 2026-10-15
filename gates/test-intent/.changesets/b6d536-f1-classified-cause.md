---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, repair of findings F1 and F2 from the live verification of Spec A steps 7
and 8, on top of 5e4f4e698. Every chat start now names its own classified failure. The operator
retry (POST /api/engine/components/generative/recover) is a new attempt: it latches and publishes
the classified cause of its own start instead of keeping the earlier inference.crashed. A settings
apply that starts chat from offline and fails, or whose rollback fails, latches its own failure;
one that starts chat clears an older failure, as an activation does. Mode switch, crash relaunch,
resume probe, resume replan and device-lost restart already classified their starts. On System
Health and in the controls, a failed chat reads as its cause in words: the inference.capability
and ai.not-ready health events take a per-reason sentence from the health-events catalog instead
of the evidence string or a bare reason token; the "What you can do right now" rows and the AI
Engine card use readinessNotice's wording for the cause instead of "offline".

Changed intent: InferenceLifecycleManagerApplyConfigTest's
failedRetryKeepsClosedLifecycleCauseAndAddsTypedFailureEvidence pinned the defect F1 repairs (a
failed retry reported the earlier inference.crashed). It is renamed
failedRetryNamesItsOwnClassifiedCauseAndAddsTypedFailureEvidence; same inputs (a retry whose
health wait times out), and the expected reason changes from inference.crashed to the retry's own
inference.startup_timeout. The typed-failure evidence assertions are unchanged.

Evidence, written by run-evidence.mjs with --before 5e4f4e698:

- `b6d536-f1-classified-cause-repaired-java.json`: the Java checks, each failing by assertion
  before.
- `b6d536-f1-classified-cause-repaired-ui.json`: the ui-web checks, each failing by assertion
  before. The candidate's health-events catalog is overlaid on the before-state, so the row checks
  fail on the old rendering, not on a missing catalog key.
- `b6d536-f1-classified-cause-preservation.json`: preservation checks, passing before and after.

Records phase B (batch 3) re-ran `b6d536-f1-classified-cause-repaired-java.json` after merging
b6d536/integration at 181363e86, because InferenceLifecycleManagerApplyConfigTest and
CapabilityHealthBridgeChatCauseTest changed after the first run. The before ref stays 5e4f4e698, the
parent of the lane commit f7cd27765. Since ee3c86b19, CapabilityHealthBridgeChatCauseTest builds
HealthEventChangeRegistry with a delivery executor, so the fail-before applies the compat before-patch
`gates/test-intent/evidence/b6d536-f1-classified-cause-repaired-java-compat.patch`. It adds only
that constructor, which ignores the executor and delegates to 5e4f4e698's own no-argument
constructor. No 5e4f4e698 code calls it, and it supplies no chat-cause wording. All nine checks pass
on the change and again fail by assertion there.

Records phase B (batch 3) re-ran `b6d536-f1-classified-cause-preservation.json` on b599d4d7e because
its check files changed after the first run. CapabilityHealthBridgeChatCauseTest no longer compiles
on 5e4f4e698 (ee3c86b19 made it build HealthEventChangeRegistry with its delivery executor). The
three ui-web checks still pass there. The file holds preservation checks only, so it was re-run
without a before-state, and all four pass on the change.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerApplyConfigTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeChatCauseTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Every chat start names its own failure, in status and on Health. failedRetryNamesItsOwnClassifiedCauseAndAddsTypedFailureEvidence (changed intent, see the preamble): a failed operator retry whose health wait times out completes FAILED with inference.startup_timeout and keeps its typed failure evidence. aFailedOperatorRetryOfACorruptModelSaysTheModelDidNotLoad: online on the fake llama-server, the server crashes; the retry's start exits with a MODEL_LOAD_FAILED classification; the result is FAILED with inference.model_load_failed and the latched failure is MODEL_LOAD_FAILED. aSettingsApplyThatStartsChatFromOfflineAndFailsShowsItsOwnFailure: offline after a crash (CRASH_LOOP latched), an apply of a new configuration with RESTART_ALWAYS whose start exits MODEL_LOAD_FAILED is LEFT_OFFLINE, and the OFFLINE landing's listener reads MODEL_LOAD_FAILED. aSettingsApplyWhoseRollbackFailsShowsTheRollbackFailure and aPreparedSettingsApplyWhoseRollbackFailsShowsTheRollbackFailure: online, the candidate fails its health wait and restarting the incumbent exits MODEL_LOAD_FAILED; the apply is LEFT_OFFLINE (the prepared apply throws) and the landing reads MODEL_LOAD_FAILED. aSettingsApplyThatStartsChatClearsAnOlderFailureAsAnActivationDoes and aPreparedSettingsApplyThatStartsChatClearsAnOlderFailureAsAnActivationDoes: after a crash, an apply of the same configuration that starts chat is APPLIED and ONLINE, and the controller's failure latch is empty. aChatModelThatDidNotLoadIsNamedByItsCauseNotTheEvidence: a generative component FAILED with inference.model_load_failed and evidence 'chat failed: model_load_failed' gives an inference.capability condition with reason InferenceModelLoadFailed and no message. everyChatFailureCauseHasWordsOnBothHealthEvents: the health-events catalog has a 'The local AI model ...' sentence at health-events.<id>.reason.<Cause>.message for the five causes on inference.capability and ai.not-ready. On 5e4f4e698: 'expected: <inference.startup_timeout> but was: <inference.crashed>', 'expected: <inference.model_load_failed> but was: <inference.crashed>', the landings read [CRASH_LOOP] and [null], the latch was still CRASH_LOOP after a successful apply, the condition's reason was Degraded, and the catalog key was missing.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "F1",
          "quote": "when the chat server fails, status and the UI readiness notice show the classified cause (out of memory, startup timeout, start failed, model load failed) instead of only inference.crashed",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "F2",
          "quote": "A failure belongs to the attempt that set it: a later refused activation does not show an older failure as current",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-f1-classified-cause-repaired-java.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerApplyConfigTest.java#failedRetryNamesItsOwnClassifiedCauseAndAddsTypedFailureEvidence",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerApplyConfigTest.java#aFailedOperatorRetryOfACorruptModelSaysTheModelDidNotLoad",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerApplyConfigTest.java#aSettingsApplyThatStartsChatFromOfflineAndFailsShowsItsOwnFailure",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerApplyConfigTest.java#aSettingsApplyWhoseRollbackFailsShowsTheRollbackFailure",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerApplyConfigTest.java#aPreparedSettingsApplyWhoseRollbackFailsShowsTheRollbackFailure",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerApplyConfigTest.java#aSettingsApplyThatStartsChatClearsAnOlderFailureAsAnActivationDoes",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerApplyConfigTest.java#aPreparedSettingsApplyThatStartsChatClearsAnOlderFailureAsAnActivationDoes",
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeChatCauseTest.java#aChatModelThatDidNotLoadIsNamedByItsCauseNotTheEvidence",
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeChatCauseTest.java#everyChatFailureCauseHasWordsOnBothHealthEvents"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/chatFailureCause.test.ts",
        "modules/ui-web/src/shell-v0/aggregate-substrate/strategies/healthEventActivityRow.test.ts",
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "On System Health and in the controls, a failed chat reads as its cause in words. chatFailureCause.test.ts, through the real aiStateStore fed an offline status: the AI state's chatFailureReason is the classified code (inference.model_load_failed, inference.out_of_memory), null for inference.offline and null when the user switched chat off; for each of inference.out_of_memory, startup_timeout, start_failed, model_load_failed and crashed, projectAvailability for an affordance gives readinessNotice's wording ('The local AI model ran out of graphics memory', 'took too long to start', 'failed to start', 'file could not be loaded', 'stopped unexpectedly'). healthEventActivityRow.test.ts: an ai.not-ready or inference.capability condition with reason InferenceModelLoadFailed and no message renders the catalog's sentence 'The local AI model file could not be loaded' (read from the real catalog file) and not the token. HealthSurface.render.test.ts: with chat offline and the AI reason inference.model_load_failed, the AI Engine card says 'The local AI model file could not be loaded' instead of 'Offline'. On 5e4f4e698: chatFailureReason was undefined, every control read 'The local AI model is offline', the rows showed the reason token, and the card read 'AI Engine offline Offline'.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "F1",
          "quote": "when the chat server fails, status and the UI readiness notice show the classified cause (out of memory, startup timeout, start failed, model load failed) instead of only inference.crashed",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S20",
          "quote": "The GPU, chat, indexing and lifecycle states the UI shows are the ones dev tools and evaluation scripts read.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-f1-classified-cause-repaired-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/chatFailureCause.test.ts#the AI state names the classified failure, and nothing else",
        "modules/ui-web/src/shell-v0/state/chatFailureCause.test.ts#a control unavailable because chat failed with inference.out_of_memory says why",
        "modules/ui-web/src/shell-v0/state/chatFailureCause.test.ts#a control unavailable because chat failed with inference.startup_timeout says why",
        "modules/ui-web/src/shell-v0/state/chatFailureCause.test.ts#a control unavailable because chat failed with inference.start_failed says why",
        "modules/ui-web/src/shell-v0/state/chatFailureCause.test.ts#a control unavailable because chat failed with inference.model_load_failed says why",
        "modules/ui-web/src/shell-v0/state/chatFailureCause.test.ts#a control unavailable because chat failed with inference.crashed says why",
        "modules/ui-web/src/shell-v0/aggregate-substrate/strategies/healthEventActivityRow.test.ts#words a ai.not-ready failure by its classified cause from the catalog, not a bare token",
        "modules/ui-web/src/shell-v0/aggregate-substrate/strategies/healthEventActivityRow.test.ts#words a inference.capability failure by its classified cause from the catalog, not a bare token",
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts#F1: the AI Engine card names a failed chat model by its cause"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeChatCauseTest.java",
        "modules/ui-web/src/shell-v0/state/chatFailureCause.test.ts",
        "modules/ui-web/src/shell-v0/aggregate-substrate/strategies/healthEventActivityRow.test.ts",
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts"
      ],
      "class": "New",
      "behaviour": "Preservation: only a classified chat failure changes the words. aFailureWithoutAClassifiedCauseKeepsItsMessage: a generative component FAILED with inference.activation_failed and evidence 'Self-test failed after 4200ms' still gives an inference.capability condition with reason Degraded and that message. 'a chat model that is merely offline still says offline': with the AI reason inference.offline the control reads 'The local AI model is offline'. 'a condition's own message still wins over the catalog's per-reason sentence': an enrichment.incomplete condition with a counts message and a seeded per-reason sentence renders the counts message, not the sentence. 'F1: an AI Engine that is merely offline still says Offline': the AI Engine card reads 'Offline' for inference.offline. Each passes on 5e4f4e698 and on the candidate.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 7-8) end to end within the agreed scenarios.",
          "choice": "The F1 repair changes the words only for a classified chat failure (inference.out_of_memory, startup_timeout, start_failed, model_load_failed, crashed): a chat model that is merely offline still reads offline, a health condition's own message still wins over a catalog sentence, and a capability failure without a classified cause keeps its evidence message.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-f1-classified-cause-preservation.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/CapabilityHealthBridgeChatCauseTest.java#aFailureWithoutAClassifiedCauseKeepsItsMessage",
        "modules/ui-web/src/shell-v0/state/chatFailureCause.test.ts#a chat model that is merely offline still says offline",
        "modules/ui-web/src/shell-v0/aggregate-substrate/strategies/healthEventActivityRow.test.ts#a condition's own message still wins over the catalog's per-reason sentence",
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts#F1: an AI Engine that is merely offline still says Offline"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-batch1-20261008-independent",
  "verdict": "accept",
  "digest": "sha256:0b11cf782778ab66f0fe573536f7b5ceadff44aea74b250092328a125b352cdf"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-batch1-20261008-independent",
  "verdict": "reject",
  "digest": "sha256:0b11cf782778ab66f0fe573536f7b5ceadff44aea74b250092328a125b352cdf"
}
```
