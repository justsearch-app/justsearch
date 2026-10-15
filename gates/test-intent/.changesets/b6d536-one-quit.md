---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, Spec F item 6 (supervision and containment), the Java and Node halves of
steps 4, 7 and 8: the supervisor-forced exit code, one quit deadline the Engine budgets its close
by (QuitBudget QB1-QB3, the request deadline, incarnation and nonce, the boot checkpoints, the chat
server stopped through the controller's terminal stop beside the drain), and the Engine's suspend
and resume producer. The shell half (quit.rs, the shell's suspend-aware clock, job terminate with
SUPERVISOR_FORCED) is Rust and not in this change; today's shell sends no incarnation or nonce, so a
request naming none is accepted as legacy. The dev-runner script test
`scripts/dev/test-dev-runner-quit.mjs` needs no entry (Spec F section 8.1). This changeset covers
only this slice's files.

**S16-D1 ruling.** Two texts conflict for a quit during an ingest unit that does not yield:

- Spec F §4.3 (specF-supervision-containment.md:464): "**New check S16-D1:** quit during a long
  ingest unit gives exit 0 within budget, and the unit replays to COMPLETE at the next start with no
  duplicate chunks."
- The D1 shutdown design
  (`docs/design/lane-f-engine-jvm/evidence/D1/publication-and-lifetime-design-2026-09-23.md:223-230`):
  "On bounded-drain refusal retain the dependencies and OS lock and report unclean close. [...]
  Route an unquiesced native owner to the already-used controlled Runtime.halt primitive [...] Use
  ordinary JVM exit only when native quiescence is confirmed."

Applied reading: the D1 design binds, and S16-D1 keeps everything except "exit 0". The quit gives
up on the unit at the end of the QB2 window (3 s) instead of holding the quit; the unit's durable
rows stay RUNNING; the steps that need the drain refuse; and the Engine ends itself inside the
budget with FATAL_OR_UNCAUGHT, or by the controlled hard stop when native work is still running.
The next start replays the unit once to COMPLETE. Why: exit 0 would need either a System.exit
racing ORT's shutdown hook with a live native call (F-016), or index and lock release while a body
still writes. Both risk the index and the user's data. The reading loses only the unit's progress,
and nothing keeps running after the quit: the dev supervisor stops after any exit once it asked
for a quit (`scripts/dev/lib/engine-supervisor.cjs`, the `hostQuitActive` branch), the shell is
itself exiting, and the Engine job ends the helpers. The cost is that such a quit reads as an unclean exit in
the ledger, which is true. This is the builder's application of the coordinator's ruling rule, for
the coordinator's review.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineExitTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineSupervisionPolicyTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Spec F step 4. supervisorForcedExitIsNamedAndTransient: the integer 1246953474 is TerminateCodes.JS_ENGINE_STOP (0x4A530002), EngineExit.describe renders it supervisor_forced, not unknown(1246953474), and EngineExit.classify puts it in TRANSIENT, so a forced stop is told apart from a crash and a forced hung Engine is restarted under the crash budget. registerCarriesTheSupervisorForcedExit: governance/supervision-contract.v1.json's engine row has an exitCodes row for 1246953474 named SUPERVISOR_FORCED with class TRANSIENT, and its lower-cased name is what EngineExit.describe gives, because both supervisors name exits only through this register.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S15",
          "quote": "Every exit of the Engine and its helpers is recorded by its owner as intended (with reason) or unexpected; a real crash during an intended stop stays unexpected; an unknown exit time reads as unknown; a clean quit shows zero unexpected exits and nothing unaccounted, console hosts included.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the open design points of the supervision and containment change end to end within scenarios S13-S16.",
          "choice": "Spec F step 4: a supervisor forces the Engine down with the job terminate code JS_ENGINE_STOP, which the register carries as SUPERVISOR_FORCED, class TRANSIENT, rendered supervisor_forced.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-quit-exit-code.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineExitTest.java#supervisorForcedExitIsNamedAndTransient",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineSupervisionPolicyTest.java#registerCarriesTheSupervisorForcedExit"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/ShutdownRequestDeadlineNameTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/LifecycleShutdownContractTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppQuitBudgetTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppBootQuitTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Spec F step 7, the quit that today overruns or obeys a stale file. deadlineIsReadUnderItsSpecFName: a request file carrying deadlineAtEpochMs is read with that deadline, and deadlineEpochMs wins when both are present. quitBodyReachesTheOrderedShutdownThroughTheBridge: a POST to the lifecycle shutdown endpoint with a body naming deadlineAtEpochMs, incarnation and detail reaches the installed shutdown action with those values, not only the reason. aQuitWhoseWorkNeverDrainsStillEndsWithinTheEngineBudget: a quit with an 8000 ms deadline whose producer, live-work, head and chat waits never return still exits on its own within the Engine budget (deadline minus the 300 ms force margin, under 7700 ms), with FATAL_OR_UNCAUGHT because the close was unclean (S16-D1 ruling above), asks the chat stop exactly once and for at most 2000 ms; today it waits each step's own timeout, about 22 s. theChatServerStopsBesideTheDrainThroughTheTerminalStop: in a quit whose operation drain yields only once the chat server's stop has begun, the quit exits 0, so the stop ran while the drain waited; in order, closing begins, the stop is recorded as the Engine's QUIT, and the terminal stop is asked, exactly once; the head is closed. The boot checkpoints, with the Engine's incarnation run-q/2: aRequestLeftByAnEarlierIncarnationIsDeletedAndNotHonoured (a QUIT naming run-q/1 is deleted and does not stop boot, at both checkpoints); aRequestNamingThisIncarnationWithoutANonceIsStale (deleted, not honoured); thisIncarnationsQuitIsHonouredAtBootAndExitsZero (a QUIT naming run-q/2 with a nonce stops boot with exit 0); aNonceLessRequestIsDeletedAtEveryCheckpoint (a request naming no incarnation is deleted and never honoured at after-lock, before-index and child-capable checkpoints); anExpiredQuitIsClearedAtEveryCheckpoint; aRestartForThisIncarnationIsLeftForTheWatcher (the file stays, boot continues); theWatcherRefusesARequestAddressedToAnotherIncarnation (the running Engine's acceptance refuses a request naming run-q/1).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S16",
          "quote": "Quitting at awkward moments (boot, model load, context relaunch, config apply, update prepare, restart cooldown) and Windows shutdown leave nothing running.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the open design points of the supervision and containment change end to end within scenarios S13-S16.",
          "choice": "Spec F section 4 as the builder implements it with today's shell: one quit deadline (contract quitDeadlineMs 8000, quitForceMarginMs 300) carried as deadlineAtEpochMs; the Engine budgets its close to the deadline minus the margin and stops the chat server through the controller's terminal stop beside the drain; a request naming an incarnation is the Engine's own only with that incarnation and a nonce, otherwise stale and deleted; a request naming none (today's shell) is legacy, cleared at the first boot checkpoint under the instance lock and honoured afterwards.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Boot honours only a quit request addressed to this Engine incarnation; requests from earlier incarnations, without nonce, or expired are deleted and not honoured.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2026-10-08 integration builder brief Q2'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-quit-repaired.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/ShutdownRequestDeadlineNameTest.java#deadlineIsReadUnderItsSpecFName",
        "modules/ui/src/test/java/io/justsearch/ui/api/LifecycleShutdownContractTest.java#quitBodyReachesTheOrderedShutdownThroughTheBridge",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppQuitBudgetTest.java#aQuitWhoseWorkNeverDrainsStillEndsWithinTheEngineBudget",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppQuitBudgetTest.java#theChatServerStopsBesideTheDrainThroughTheTerminalStop",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppBootQuitTest.java"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/QuitBudgetTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/ShutdownRequestOwnershipTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppQuitWindowTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/LifecycleShutdownContractTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppQuitBudgetTest.java"
      ],
      "class": "New",
      "behaviour": "Spec F step 7, the new quit budget and request ownership types (no fail-before claimed: the types are new). QuitBudgetTest: the Engine budget is the deadline less 300 ms less now (7700, 7400, 2400 ms in the cases); a wait is the smaller of its sub-budget and what is left after a further 300 ms exit reserve (2000, 900, 0); a past deadline leaves 0; extreme deadlines do not overflow; an unbounded budget keeps each step's own wait; a quit without a deadline gets 8000 ms from now; the QB1-QB3 sub-budgets fit the Engine budget; only a QUIT hands its budget to budgeted steps (RESTART and the unbudgeted run get unbounded); a budgeted runAndExit exits 0 once and a second trigger joins the first. ShutdownRequestOwnershipTest: this incarnation with a nonce is THIS_INCARNATION; another incarnation, this one without a nonce, or an Engine without an incarnation is STALE; a request naming none is STALE (owner Q2 ruling supersedes the prior legacy choice); incarnation and requestNonce are read from the file. HeadlessAppQuitWindowTest: in a quit the producer drain gets the QB2 window and on a restart its own deadline; live-work drains share one window counted from the producer drain; a lifecycle quit without a deadline gets the contract deadline; an exhausted chat stop still asks for 1 ms and a failing or late chat stop never throws. aBodyNamingNothingStillQuits: an empty, non-JSON or wrongly typed body still quits (preservation of today's empty-body shell). aRestartKeepsTodaysWaits: a restart keeps 5 s, 5 s and 12 s waits (preservation).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S16",
          "quote": "Quitting at awkward moments (boot, model load, context relaunch, config apply, update prepare, restart cooldown) and Windows shutdown leave nothing running.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the open design points of the supervision and containment change end to end within scenarios S13-S16.",
          "choice": "Spec F section 4.3 QuitBudget QB1-QB3 and the request ownership rule as the builder implements them: Engine budget = deadline − quitForceMarginMs − now, a further 300 ms kept for the JVM exit, restart and the unbudgeted path keep today's waits, and a request with no incarnation is legacy so today's shell keeps working.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Task t-20261005-b6d536 end to end: the owner handed over checklist item 5 (GPU co-residence) and item 6 (process ownership and exit accounting) for the agent to design, build and verify on its own judgement, and repeated the grant on 2026-10-06T21:10Z (\"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"). It covers choices within those items and the agreed scenarios; it does not authorise changing an established contract outside them. The choice below is agent wording (an orchestrator brief or contract), not the owner's.",
          "choice": "Boot honours only a quit request addressed to this Engine incarnation; requests from earlier incarnations, without nonce, or expired are deleted and not honoured.",
          "label": "agent choice",
          "provenance": "cited before as owner words at 'task t-20261005-b6d536 user turn 2026-10-08 integration builder brief Q2'; the text does not appear in the owner's turns of session 886833dd"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-quit-budget.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/QuitBudgetTest.java#theEngineBudgetIsTheDeadlineLessTheForceMarginLessNow",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/QuitBudgetTest.java#aWaitIsTheSmallerOfItsSubBudgetAndWhatIsLeft",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/QuitBudgetTest.java#aDeadlineAlreadyPastLeavesNothingToWait",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/QuitBudgetTest.java#extremeDeadlinesDoNotOverflow",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/QuitBudgetTest.java#unboundedKeepsTodaysWaits",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/QuitBudgetTest.java#aQuitWithoutADeadlineGetsTheContractDeadlineFromNow",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/QuitBudgetTest.java#theSubBudgetsFitTheEngineBudget",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/QuitBudgetTest.java#onlyAQuitHandsItsBudgetToTheSteps",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/QuitBudgetTest.java#aBudgetedQuitExitsThroughTheSameRunOnceSequence",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/ShutdownRequestOwnershipTest.java#aRequestForThisIncarnationWithANonceIsItsOwn",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/ShutdownRequestOwnershipTest.java#aRequestForAnotherIncarnationIsStale",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/ShutdownRequestOwnershipTest.java#aRequestForThisIncarnationWithoutANonceIsStale",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/ShutdownRequestOwnershipTest.java#anEngineWithoutAnIncarnationCannotOwnAnAddressedRequest",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/ShutdownRequestOwnershipTest.java#aRequestNamingNoIncarnationIsStale",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/ShutdownRequestOwnershipTest.java#theIncarnationAndNonceAreRead",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppQuitWindowTest.java#recordedProducersGetTheCooperativeWindowInAQuitAndTheirOwnDeadlineOtherwise",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppQuitWindowTest.java#liveWorkSharesOneWindowCountedFromTheProducerDrain",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppQuitWindowTest.java#aLifecycleQuitWithoutADeadlineGetsTheContractDeadline",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppQuitWindowTest.java#anExhaustedChatStopStillAsksForOneMillisecondAndReportsFailures",
        "modules/ui/src/test/java/io/justsearch/ui/api/LifecycleShutdownContractTest.java#aBodyNamingNothingStillQuits",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppQuitBudgetTest.java#aRestartKeepsTodaysWaits"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "Spec F S16-D1 as ruled above. aQuitDuringAUnitThatNeverYieldsAbandonsItToReplayOnceToComplete: a durable ingest unit whose producer ignores its cancellation is running when the quit begins closing; the producer drain, given the quit's QB2 window (QB2_COOPERATIVE_MS + QB2_CANCEL_ACK_MS = 3000 ms), reports the undrained producer with an IOException within the window plus 1 s instead of holding the quit; the accepted result stays pending and the parent and child rows stay RUNNING; after the process's stores are released without the ordered close, a successor process replays the unit exactly once (one producer call for the same child key, one pending claim), and on that claim's completion the parent and child are COMPLETE with exactly one unit completed and none failed. No fail-before is claimed: the drain and handoff exist at the base; this pins that the abandonment the quit budget introduces loses progress, not the unit. The exit code of such a quit (FATAL_OR_UNCAUGHT, not 0) is pinned by HeadlessAppQuitBudgetTest#aQuitWhoseWorkNeverDrainsStillEndsWithinTheEngineBudget.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S16",
          "quote": "Quitting at awkward moments (boot, model load, context relaunch, config apply, update prepare, restart cooldown) and Windows shutdown leave nothing running.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the open design points of the supervision and containment change end to end within scenarios S13-S16.",
          "choice": "Orchestrator rule for the S16-D1 conflict (apply the reading that never loses user data and never leaves a process running after quit), applied by the builder as the prose above states: the D1 shutdown design binds; a quit abandons a non-yielding unit at the end of QB2 to its durable replay and ends unclean within the budget; the unit replays once to COMPLETE.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-quit-d1.json",
      "newChecks": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/RecordedIngestionCoordinatorTest.java#aQuitDuringAUnitThatNeverYieldsAbandonsItToReplayOnceToComplete"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/power/WindowsPowerNotificationsTest.java"
      ],
      "class": "New",
      "behaviour": "Spec F step 8 (S11-U1), the Engine's suspend and resume producer. registersWithWindowsAndUnregisters: PowerRegisterSuspendResumeNotification with DEVICE_NOTIFY_CALLBACK returns ERROR_SUCCESS (0) and PowerUnregisterSuspendResumeNotification of the handle returns 0. theUpcallStubEnqueuesTheSuspendAndNeverThrows: calling the registered upcall stub through a native call with this registration's context and PBT_APMSUSPEND (4) returns 0 and delivers SUSPEND with epoch 1 to a subscriber, even beside a subscriber that throws; a second suspend, an unknown type and an unknown context return 0 and deliver nothing; PBT_APMRESUMEAUTOMATIC (0x12) delivers RESUME with the same epoch and the following PBT_APMRESUMESUSPEND (7) delivers nothing; the throwing subscriber was called for both events; suspendedNanosBetween counts exactly the suspend inside the window and 0 outside it. beforeBootNothingIsInstalledAndNoTimeReadsAsSuspended: before installAtBoot, SystemPowerEvents.current() is NoPowerEvents and reports no suspended time. No fail-before is claimed (Spec F section 8.1).",
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
          "delegationScope": "Deciding the open design points of the supervision and containment change (Spec F, item 6) end to end, including the power-notification producer it owns for S11.",
          "choice": "Spec F section 3.5: the windowless Engine registers once at boot with PowerRegisterSuspendResumeNotification; the upcall only enqueues, returns 0 and catches everything; a duplicate resume or a suspend while one is open is dropped.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-power-events.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/power/WindowsPowerNotificationsTest.java#registersWithWindowsAndUnregisters",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/power/WindowsPowerNotificationsTest.java#theUpcallStubEnqueuesTheSuspendAndNeverThrows",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/power/WindowsPowerNotificationsTest.java#beforeBootNothingIsInstalledAndNoTimeReadsAsSuspended"
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
  "session": "verify-oq-independent-20261007",
  "verdict": "reject",
  "digest": "sha256:534f4b2f10ae370d4cfbc141f5cf312aa6bf37b858f8778bb0b84358f8ffcef9",
  "note": "Q2 rejects requests without incarnation or nonce. The candidate intentionally honours a nonce-less legacy request at later boot checkpoints; independently reproduced by verify-oq/ProbeBootOwnership.java, laterCheckpointHonoured=true and exitCode=0. Delegation does not override the explicit Q2 contract. Live stack checks and durable chunk replay remain pending."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS2-20261015",
  "verdict": "reject",
  "digest": "sha256:3c3a3435f7dd7f51914e49ef93fe1464e331c62c46c7ce8272bab4ca14e39c6a",
  "note": "Entry 5's b6d536-power-events.json is candidate-only and supplies no reason for omitting before execution; saying no fail-before is claimed is not a reason. The test intentionally uses reflection so an absent production class still compiles and can fail an assertion."
}
```
