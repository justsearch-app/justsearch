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

The earlier no-op bindPowerEvents experiment is withdrawn as fail-before proof: that reached
stub directly removed every effect the checks require. This entry is candidate-only. A fresh
run-evidence attempt uses the actual 48326a1ce parent plus the add-only compatibility patch
in gates/test-intent/evidence/b6d536-resume-wiring-compat.patch: PowerEvent's data record and
only SystemPowerEvents' abstract input/subscription signatures. It adds no power producer,
EngineRoot transplant or binding method. Neither actual merge parent (48326a1ce or b1cfcac8e)
has EngineRoot.bindPowerEvents. The attempted-run logs and API audit are in
F:/phaseb-fix3-b6d536-20261015; an initial full-interface attempt stopped earlier at its missing
BootPowerEvents dependency, so the declaration-only replay removes that unrelated dependency.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineResumeWiringTest.java"
      ],
      "class": "New",
      "behaviour": "Spec A §7.4 with Spec F §3.5 on the Engine root, on API new in this change (EngineRoot.bindPowerEvents). aResumeReachesTheControllerBoundBeforeThePowerEvents: with the chat controller's input bound first, a RESUME power event reaches reportResume once and a SUSPEND does not. aResumeReachesTheControllerBoundAfterThePowerEvents: bound in the other order, nothing subscribes until the controller binds, then a RESUME reaches it. aReboundControllerGetsTheResumesThroughOneSubscription: after a rebinding only the latest controller gets the resume, a second power source is ignored, and there is one subscription. theSubscriptionClosesWithTheProcessResources: closing the process resources closes the subscription and later resumes reach nothing. Candidate-only. before-state not reproducible: EngineRoot has no bindPowerEvents at either actual merge parent (48326a1ce or b1cfcac8e); 48326a1ce also has no power-event types. There is no old EngineRoot resume-binding operation to re-encode the checks against. Adding a no-op binding makes all four missing effects fail by construction, so it is not fail-before proof.",
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
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
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

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-batch1-20261008-independent",
  "verdict": "accept",
  "digest": "sha256:949fa79d0193a661709a744066ee46513fc1d61e91e67e2617a52df4a5e77c15"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS3-20261015",
  "verdict": "reject",
  "digest": "sha256:78f32a8633e73c2ac2363a3ec704af82e26b2bd110e2c10a7f7214ecd0fb8f21",
  "note": "The current preamble and entry explicitly claim fail-before by transplanting candidate EngineRoot and reducing bindPowerEvents to a no-op. All four checks directly require those removed effects, so the declared assertion failures are stub-created rather than evidence from the old implementation."
}
```
