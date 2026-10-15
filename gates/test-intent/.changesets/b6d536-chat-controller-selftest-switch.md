---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, design item 5 (Spec A steps 5-6 follow-up) on top of dee93cc5a. An
independent verifier of steps 5-6 found (own case c1) that POST mode=online while the activation
self-test runs fails the activation with RUNTIME_ACTIVATION_FAILED "Settings changed since this
candidate was read", on 125bfe23d and 19f7c8a81 alike. Spec A decides it. §2.2: "evaluated on every
input change; first matching rule wins", with rule 3 "in.selfTest != null -> NOT_RUNNING(self_test)".
T39: "SELF_TEST | session closed | — | replan (usually the newly committed config)". §4.1:
"desired-state inputs (async; never block the caller)". Turning chat on or off is the Activation
input, so the open session only defers it. The activation now commits over a chat switch made since
its candidate was read. The switch was made later, so it is applied after: the user's chat choice
is kept and the activated runtime is committed. Any other settings change still refuses the commit,
because the self-test validated the settings it read. Deactivation (under a freeze, not a
self-test) is unchanged. This changeset covers only these files.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationChatSwitchTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "A chat switch made while the activation self-test runs is deferred, never a conflict. chatTurnedOnDuringTheSelfTestIsKeptAndTheActivationCompletes: with chat off, the user turns chat on through RuntimeSpecStore (the settings owner of the mode route) during a passing self-test; the activation completes, chat is on, and the settings carry the variant executable and null configuredGpuLayers for automatic profile selection. chatTurnedOffDuringTheSelfTestStaysOffAndTheRuntimeIsStillActivated: with chat on, the user turns chat off during the self-test; the activation completes with the variant executable and chat stays off. On dee93cc5a both fail the activation as the verifier saw it ('code=RUNTIME_ACTIVATION_FAILED message=Runtime apply failed: Settings changed since this candidate was read ==> expected: <completed> but was: <failed>').",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S19",
          "quote": "The activation self-test never loads a second chat model beside the running one when that would not fit.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 5-6) end to end within the agreed scenarios.",
          "choice": "Spec A §2.2 rule 3 and T39 as the builder implements them at the activation commit: when the settings changed since the candidate was read in the chat switch alone, the activation rebases onto the newer settings, keeping the user's chatEnabled (applied after) and committing the runtime fields, retrying a bounded three times if another write races it; any other change keeps the stale-witness refusal.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-selftest-switch-repaired.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationChatSwitchTest.java#chatTurnedOnDuringTheSelfTestIsKeptAndTheActivationCompletes",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationChatSwitchTest.java#chatTurnedOffDuringTheSelfTestStaysOffAndTheRuntimeIsStillActivated"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationChatSwitchTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation, passing on dee93cc5a and after (recorded in the preserved evidence's fail-before run). aContextChangeDuringTheSelfTestStillRefusesTheActivation: a chat switch plus a context length change during the self-test still fails the activation RUNTIME_ACTIVATION_FAILED; the newer settings (context 12288, chat on) survive and the stale candidate's executable does not land.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 5-6) end to end within the agreed scenarios.",
          "choice": "Only the chat switch is a deferred desired-state input; a configuration change during the self-test invalidates what the self-test validated, so the commit is still refused.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-selftest-switch-preserved.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationChatSwitchTest.java#aContextChangeDuringTheSelfTestStillRefusesTheActivation"
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
  "session": "VS2-20261015",
  "verdict": "reject",
  "digest": "sha256:6aee54d6ee945e7759b7b6ace6e7c34d7da9e715b415a807a4af50c32c4392f0",
  "note": "Entry 1 says activation preserves 99 GPU layers, while the current chatTurnedOnDuringTheSelfTestIsKeptAndTheActivationCompletes assertion requires configuredGpuLayers to be null for automatic profile selection. The declaration was not corrected when that oracle changed."
}
```
