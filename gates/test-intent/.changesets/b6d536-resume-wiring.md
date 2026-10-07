---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536: the merge of b6d536/one-quit (b1cfcac8e: Spec F one quit deadline and
power events, and the llama-child rollback-survivor repair) into b6d536/integrate on top of
48326a1ce, and the one wiring it enables: the machine's resumes reach the chat controller
(Spec A §7.4; the step-10 changeset left reportResume without a caller until this merge). The
merged branches keep their own changesets. This changeset covers only the wiring test.

Fail-before for checks on API new in this change (manual run, not a run-evidence file). The run
used a temporary `git worktree add` of 48326a1ce outside the repo, merged with b1cfcac8e without
a commit, removed after the run; the script, the stub patch and the JUnit result are kept in the
builder sandbox (stubs-merge/run.sh, stub-logsm). Before-state: that merge plus the candidate's
EngineRoot with bindPowerEvents reduced to a no-op (the merge alone, without the wiring). All 4
checks fail by AssertionFailedError: the three delivery checks at "expected: <1> but was: <0>" and
theSubscriptionClosesWithTheProcessResources at "the subscription closed ==> expected: <true> but
was: <false>".

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineResumeWiringTest.java"
      ],
      "class": "New",
      "behaviour": "Spec A §7.4 with Spec F §3.5 on the Engine root, on API new in this change (EngineRoot.bindPowerEvents; the layer-stub run below records the fail-before by assertion). aResumeReachesTheControllerBoundBeforeThePowerEvents: with the chat controller's input bound first, a RESUME power event reaches reportResume once and a SUSPEND does not. aResumeReachesTheControllerBoundAfterThePowerEvents: bound in the other order, nothing subscribes until the controller binds, then a RESUME reaches it. aReboundControllerGetsTheResumesThroughOneSubscription: after a rebinding only the latest controller gets the resume, a second power source is ignored, and there is one subscription. theSubscriptionClosesWithTheProcessResources: closing the process resources closes the subscription and later resumes reach nothing.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S11",
          "quote": "After a driver reset, driver update or sleep/resume, search and chat end up working without a crash loop; suspended time is not treated as a hang or a deadline.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-06",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 10 and 11, and the merges they need) end to end within the agreed scenarios.",
          "choice": "Spec A §7.4's resume source, wired at the merge of b6d536/one-quit (d20c300d1, Spec F §3.5 SystemPowerEvents) into b6d536/integrate as the step-10 changeset named it: HeadlessApp binds the boot power events into the Engine root, which subscribes once, when both the power events and the chat controller's device-loss input are bound, forwards each RESUME to the latest bound controller's reportResume, ignores SUSPEND, and closes the subscription with the process resources.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-resume-wiring.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineResumeWiringTest.java#aResumeReachesTheControllerBoundBeforeThePowerEvents",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineResumeWiringTest.java#aResumeReachesTheControllerBoundAfterThePowerEvents",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineResumeWiringTest.java#aReboundControllerGetsTheResumesThroughOneSubscription",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineResumeWiringTest.java#theSubscriptionClosesWithTheProcessResources"
      ]
    }
  ]
}
```
