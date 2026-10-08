---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, design item 5, Spec A step 12 (option B) on top of ce8ddb55b. Deleted:
RuntimeReconciler's three deprecated controller-less constructors (with switchForwarder,
DetachAction and ReasonedSwitch) and the passive RuntimeGpuLease mirror with its unused grant
logic. /api/status inference.leaseHolder stays on the wire, derived: RuntimeStatus.leaseHolderFor
maps the observed mode to the same values the mirror produced (CHAT, INDEXING, NONE; TRANSITIONING
keeps the last committed holder), so no consumer sees a change. The runtime-state register drops
the mirror's rows.

Departure from Spec A row 12, accepted by the orchestrator under the owner's delegation. Follow-up,
not in this change: the RR procedure overlay, RR chat convergence and the ILM transition entry
points. They still carry live behaviour (the overlay feeds inference.procedure and the soft-off
engine reason the UI reads; convergence re-sends the intent when the last hold closes, forwards
boot and spec writes and retries a failed stop) or would need 50+ test migrations.

No new behaviour, so no new checks. Preservation: the twelve adapted test files pass before and
after. Before: their original versions, unchanged, on ce8ddb55b (manual run in a temporary
`git worktree add` outside the repo, removed after the run; script before12/run.sh and JUnit
results before-logs12 in the builder sandbox). Result: 42 of 42 tests in the twelve files passed before (no failures, no skips). After: the adapted versions on the
candidate, recorded in the adapted evidence.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/BootstrapProjectionsTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/brainruntime/BrainRuntimeServiceImplTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/handlers/SetChatEnabledHandlerTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/operations/handlers/SwitchInferenceModeHandlerTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerHoldsTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/TestActivationForwarders.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "instrumentation"
      ],
      "whatMoved": "RuntimeReconciler's deprecated controller-less constructors (7, 9 and 10 arguments: control, mode, external adoption, DetachAction, policy, spec store, RuntimeGpuLease[, online switch, indexing switch[, component recovery pending]]) are deleted with RuntimeGpuLease. Each test now builds the reconciler with the Engine's constructor (control, mode, external adoption, policy, spec store, forwarder, component recovery pending) and passes TestActivationForwarders.switching(control, external adoption, detach[, online, indexing]), a line-for-line copy into test support of the deleted switchForwarder adapter (active runs the online switch or control.switchToOnlineMode; inactive detaches an adopted external server or runs the indexing switch or control.switchToIndexingMode; failures complete the stage exceptionally); the component-recovery supplier is the same argument, or () -> false where the 7- and 9-argument forms defaulted it. BootstrapProjectionsTest's status fixture passes RuntimeStatus.LEASE_INDEXING for RuntimeGpuLease.Holder.INDEXING (the same wire value). Inputs, preconditions, observations and expected outcomes are unchanged; the original versions pass on ce8ddb55b and the adapted ones on the candidate.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A step 12) end to end within the agreed scenarios.",
          "choice": "Spec A step 12, option B as the orchestrator accepted it under this delegation: delete RuntimeReconciler's three deprecated controller-less constructors (with their switch adapter and its DetachAction and ReasonedSwitch interfaces) and the passive RuntimeGpuLease mirror with its unused grant logic. /api/status inference.leaseHolder stays on the wire with the same values, now derived from the observed mode by RuntimeStatus.leaseHolderFor (ONLINE CHAT, INDEXING INDEXING, OFFLINE NONE; TRANSITIONING keeps the last committed holder). The RR procedure overlay, RR chat convergence and the ILM transition entry points are deferred as a follow-up: they still carry live behaviour (the overlay feeds inference.procedure and the soft-off engine reason the UI reads; convergence re-sends the intent when the last hold closes, forwards boot and spec writes and retries a failed stop) or would need 50+ test migrations. That is a departure from Spec A row 12.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step12-adapted.json"
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallAcquisitionFreezeSeamTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationSelfTestSeamTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatActivationSeamTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SelfTestSessionSeamTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/OfflinePassChatSeamTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "The seam tests build the reconciler with the Engine's constructor, which no longer takes a RuntimeGpuLease: the 'new RuntimeGpuLease()' argument and its import are removed; every other argument (manager, mode, external adoption, policy, spec store, manager::setActivation, manager::componentRecoveryPending) and every assertion is unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A step 12) end to end within the agreed scenarios.",
          "choice": "Spec A step 12, option B as the orchestrator accepted it under this delegation: delete RuntimeReconciler's three deprecated controller-less constructors (with their switch adapter and its DetachAction and ReasonedSwitch interfaces) and the passive RuntimeGpuLease mirror with its unused grant logic. /api/status inference.leaseHolder stays on the wire with the same values, now derived from the observed mode by RuntimeStatus.leaseHolderFor (ONLINE CHAT, INDEXING INDEXING, OFFLINE NONE; TRANSITIONING keeps the last committed holder). The RR procedure overlay, RR chat convergence and the ILM transition entry points are deferred as a follow-up: they still carry live behaviour (the overlay feeds inference.procedure and the soft-off engine reason the UI reads; convergence re-sends the intent when the last hold closes, forwards boot and spec writes and retries a failed stop) or would need 50+ test migrations. That is a departure from Spec A row 12.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step12-adapted.json"
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeStatusTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "RuntimeStatus takes the LEASE holder as its wire string instead of RuntimeGpuLease.Holder: leaseAxisDerivation and fullDeriveHasAllThreeAxes pass RuntimeStatus.LEASE_CHAT/LEASE_INDEXING/LEASE_NONE (and null) for Holder.CHAT/INDEXING/NONE and expect the same strings. indexingLeaseSurvivesTransitionUntilModeCommits drives the same mode sequence (INDEXING, TRANSITIONING, ONLINE, OFFLINE) through RuntimeStatus.leaseHolderFor, which carries the last committed holder the deleted mirror object held, and expects the same holders (INDEXING, INDEXING through the transition, CHAT, NONE), compared by their wire names instead of enum constants.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A step 12) end to end within the agreed scenarios.",
          "choice": "Spec A step 12, option B as the orchestrator accepted it under this delegation: delete RuntimeReconciler's three deprecated controller-less constructors (with their switch adapter and its DetachAction and ReasonedSwitch interfaces) and the passive RuntimeGpuLease mirror with its unused grant logic. /api/status inference.leaseHolder stays on the wire with the same values, now derived from the observed mode by RuntimeStatus.leaseHolderFor (ONLINE CHAT, INDEXING INDEXING, OFFLINE NONE; TRANSITIONING keeps the last committed holder). The RR procedure overlay, RR chat convergence and the ILM transition entry points are deferred as a follow-up: they still carry live behaviour (the overlay feeds inference.procedure and the soft-off engine reason the UI reads; convergence re-sends the intent when the last hold closes, forwards boot and spec writes and retries a failed stop) or would need 50+ test migrations. That is a departure from Spec A row 12.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step12-adapted.json"
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
  "digest": "sha256:ebfbd34bcde271747a1f6523526ce7e591f1dbf8b13ca5cffdf6de613fd578f0"
}
```
