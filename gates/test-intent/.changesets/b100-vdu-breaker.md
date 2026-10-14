---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: agent-a521e85070bbffb0f
---

# b100-vdu-breaker: pause automatic picture reading after repeated refused passes

Builder entries for lane b100 (base bc5978095). The new checks reach the new API through reflection
with fallbacks, so they compile on bc5978095 and fail there by assertion (fail-before in the
evidence file). Independent verification supplies acceptance; the builder does not accept this
changeset.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Over the production idle sampler, pass owner and batch processor (scripted model boundary, state-tracking index port, test clock): three automatic passes in a row whose document attempts are all refused, or whose vision entry fails, stop further automatic entries; status shows the cause-specific pause code and the refusal seen; documents stay PENDING with retries unchanged. Progress (a completed or failed document, including a refusal mixed with a real failure) resets the count; cancelled or activity-interrupted passes neither count nor reset. The half-open probe follows 5, 10, 20, 40, 60, 60 minutes and closes on progress, after which the back-off restarts at 5; a probe that neither counts nor progresses keeps the pause and the next eligible tick probes again. An explicit pass while paused runs normally and resets the count. A new Engine starts unpaused. Status never claims paused while closed.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "B1",
          "quote": "3 refused passes, then no 4th entry or relaunch within the back-off; status shows the reason.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "B2",
          "quote": "2 refused, 1 progressing, 2 refused: still closed.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "B3",
          "quote": "probe after 5 min; failure reopens with 10, then 20, capped at 60; success closes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "B4",
          "quote": "each close event closes the breaker at once.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "B5",
          "quote": "an explicit pass while open runs normally.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "label": "agent choice",
          "choice": "Cases implied by the adopted scenarios, as the orchestrator listed them: a refusal mixed with a real failure counts as progress; a pass cancelled by quit or activity neither counts nor resets; an Engine restart starts closed; status never claims paused when closed."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "label": "agent choice",
          "choice": "Name the two pause causes vdu.breaker_open.refused and vdu.breaker_open.entry_failed, carried in the existing VDU blocked-reason field and mapped like the other VDU codes (visualDocumentUnderstanding degraded, aiFeatures composite, not retrieval)."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "label": "agent choice",
          "choice": "Count a pass when it (re)launched llama-server for picture reading and completed no document: every attempt refused, or the vision entry failed. A loaded-chat BUSY deferral, cancellation, activity interruption or an empty pass is neutral. Progress means processed + failed > 0."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "label": "agent choice",
          "choice": "Say the refused cause in plain words: the Engine held generation closed while a settings change was in progress or during settings recovery, so every document was turned away."
        }
      ],
      "evidence": "gates/test-intent/evidence/b100-vdu-breaker-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerTest.java#refusedAutomaticPassesStopAfterThreeAndStatusShowsThePause",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerTest.java#aPassThatCompletesADocumentResetsTheCount",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerTest.java#aRefusalMixedWithARealFailureCountsAsProgress",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerTest.java#halfOpenProbeBacksOffFiveTenTwentyFortyThenSixtyAndClosesOnProgress",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerTest.java#aProbeThatNeitherCountsNorProgressesKeepsThePauseAndTheNextTickProbesAgain",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerTest.java#anExplicitPassWhileOpenRunsNormallyAndClosesTheBreaker",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerTest.java#anExplicitPassClosesTheBreakerEvenWhenItIsRefusedToo",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerTest.java#failedVisionEntriesCountAndAreReportedApart",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerTest.java#activityInterruptedPassNeitherCountsNorResets",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerTest.java#cancelledPassNeitherCountsNorResets",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerTest.java#anEngineRestartStartsClosed"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerSeamTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Over the real lifecycle, chat controller, generation tracker, reconciler, sampler, coordinator and processor with the fake llama-server: with chat off and generation held closed, three automatic passes each launch llama-server, then no further automatic pass launches it or enters picture reading, with vdu.breaker_open.refused shown and documents and retries unchanged. The same holds for the failed-vision-launch loop with vdu.breaker_open.entry_failed. A committed settings apply, a committed prepared (record-only) publish, a person's chat switch and a person's explicit pass each close the pause through the real controller, so the next automatic pass runs at once. An explicit pass while paused reads every document. A refused chat-off entry under the settings-recovery fence leaves no llama-server running. B2 at the seam: two refused passes, a pass that completes documents, two refused passes: still running; the third refused pass since progress pauses. A pass where one document really fails (a missing file, spending its attempt) and the next is refused is progress. B3 at the seam with a skewed breaker clock: no probe before 5 minutes, one probe after; then 10, 20, 40, 60 and 60 again; status detail says 'Next try at'; a probe that completes documents ends the pause and the next pass runs without waiting. A pass interrupted by activity during the pass, and a pass whose thread is interrupted mid-pass as quit's task cancellation does, each enter picture reading but neither count nor reset.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "B1",
          "quote": "3 refused passes, then no 4th entry or relaunch within the back-off; status shows the reason.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "B2",
          "quote": "2 refused, 1 progressing, 2 refused: still closed.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "B3",
          "quote": "probe after 5 min; failure reopens with 10, then 20, capped at 60; success closes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "B4",
          "quote": "each close event closes the breaker at once.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "B5",
          "quote": "an explicit pass while open runs normally.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "label": "agent choice",
          "choice": "Cases implied by the adopted scenarios, as the orchestrator listed them: a refusal mixed with a real failure counts as progress; a pass cancelled by quit or activity neither counts nor resets; an Engine restart starts closed; status never claims paused when closed."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "label": "agent choice",
          "choice": "Close the pause at once on a person's explicit pass, a committed settings apply (APPLY or PREPARED_RETIRE actuation) and a person's chat switch (USER_SWITCH activation); gate reopening is not a close event."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "label": "agent choice",
          "choice": "A server that an automatic chat-off pass launched only for picture reading and that could not enter is stopped again through the controller: the recovery fence keeps refusing drains that start or relaunch a server, and admits only the deactivation that stops it."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "label": "agent choice",
          "choice": "Name the two pause causes vdu.breaker_open.refused and vdu.breaker_open.entry_failed, carried in the existing VDU blocked-reason field and mapped like the other VDU codes (visualDocumentUnderstanding degraded, aiFeatures composite, not retrieval)."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "label": "agent choice",
          "choice": "Put the time of the next try into the existing message field of the vdu.unavailable health condition the status handler asserts for the paused component ('Next try at HH:mm', local time). The /api/status readiness component has no message field, so no wire field is added and that gap stays named."
        }
      ],
      "evidence": "gates/test-intent/evidence/b100-vdu-breaker-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerSeamTest.java#chatOffRefusedPassesStopRelaunchingLlamaServerAfterThree",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerSeamTest.java#chatOffFailedVisionEntriesStopRelaunchingLlamaServerAfterThree",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerSeamTest.java#refusedChatOffEntryUnderTheRecoveryFenceLeavesNoLlamaServerRunning",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerSeamTest.java#committedSettingsApplyClosesTheBreaker",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerSeamTest.java#preparedSettingsPublishClosesTheBreaker",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerSeamTest.java#switchingChatOffClosesTheBreaker",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerSeamTest.java#anExplicitPassClosesTheBreaker",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerSeamTest.java#anExplicitPassWhileOpenReadsTheDocuments",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerSeamTest.java#aPassThatCompletesDocumentsResetsTheCount",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerSeamTest.java#aRefusalMixedWithARealFailureCountsAsProgress",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerSeamTest.java#halfOpenProbeBacksOffFiveTenTwentyFortyThenSixtyAndClosesOnProgress",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerSeamTest.java#activityInterruptedPassNeitherCountsNorResets",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerSeamTest.java#cancelledPassNeitherCountsNorResets"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerSeamTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Round-2 repair of the round-1 commit d72a8a90c, at the same seam: with chat off and generation held closed, two refused automatic passes, then an automatic pass whose VDU entry the real controller refuses for GPU pressure before launching anything (pressure reported after the sampler's check). That pass launches no llama-server, spends no attempt and does not count, so the next refused automatic pass runs; where the breaker exists, the tick after it is paused, because the no-launch refusal did not reset the count either. Fail-before is against d72a8a90c (which counted the refusal as a failed entry and paused one pass early). On bc5978095 the check passes: nothing paused automatic passes there, and the reset clause applies only where the breaker exists.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "label": "agent choice",
          "choice": "Count a failed vision entry only when the pass launched or relaunched llama-server for picture reading: the processor compares the controller's count of llama-server launch attempts before and after the entry, and a refused entry that launched nothing (a reload back-off, a load in progress, GPU or game pressure) neither counts nor resets. This applies the agreed counting rule (decision 1); the verifier found the round-1 code counted every refused entry."
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "label": "agent choice",
          "choice": "Count a pass when it (re)launched llama-server for picture reading and completed no document: every attempt refused, or the vision entry failed. A loaded-chat BUSY deferral, cancellation, activity interruption or an empty pass is neutral. Progress means processed + failed > 0."
        }
      ],
      "evidence": "gates/test-intent/evidence/b100-vdu-breaker-no-launch-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerSeamTest.java#anEntryRefusalThatLaunchedNothingNeitherCountsNorResets"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/PausedPictureReadingConditionMessageTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Through the real status handler and lifecycle tap over a real condition store: while the Head's VDU capability shows vdu.breaker_open.refused with its pause detail, the asserted vdu.unavailable condition (subject vdu) carries that detail, including 'Next try at', in its existing message field.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "label": "agent choice",
          "choice": "Put the time of the next try into the existing message field of the vdu.unavailable health condition the status handler asserts for the paused component ('Next try at HH:mm', local time). The /api/status readiness component has no message field, so no wire field is added and that gap stays named."
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "B1",
          "quote": "3 refused passes, then no 4th entry or relaunch within the back-off; status shows the reason.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/b100-vdu-breaker-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/PausedPictureReadingConditionMessageTest.java#thePauseConditionSaysWhenTheNextTryIs"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerNextTryTextTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Over the real breaker and the Head's VDU capability state, with the monotonic and wall clocks driven by the test (UTC): opened at 14:00, the detail names the real probe time 14:05; read at 14:30 with no probe, it names no past time and no clock time, and says the next try is when the computer is next idle, while still paused. Opened at 14:00:40, the deadline 14:05:40 is shown as 14:06, never 14:05. Fail-before is against the round-2 commit 184dc8963, which wrote the text once at opening and truncated the minute; the breaker does not exist on bc5978095.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "label": "agent choice",
          "choice": "Build the next-try text when status is read, from the breaker's state and the clock: before the deadline 'Next try at HH:mm', rounded up to the minute so it is never early; once the deadline has passed, the next try is when the computer is next idle. Nothing cached can outlive its truth. (Round-3 decision after the verifier found the round-2 text, written once at opening, kept naming a past time.)"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "label": "agent choice",
          "choice": "Put the time of the next try into the existing message field of the vdu.unavailable health condition the status handler asserts for the paused component ('Next try at HH:mm', local time). The /api/status readiness component has no message field, so no wire field is added and that gap stays named."
        }
      ],
      "evidence": "gates/test-intent/evidence/b100-vdu-breaker-next-try-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerNextTryTextTest.java#aPauseReadAfterItsDeadlineWithNoProbeNamesNoPastTime",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/AutomaticVduBreakerNextTryTextTest.java#aDeadlineBetweenMinutesIsShownRoundedUpSoItIsNeverEarly"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/GenerationTrackerTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Under the recovery fence the tracker admits a deactivation drain (stopping the server), keeps admission closed after it, and still refuses a relaunch drain and an unattributed close. Existing GenerationTrackerTest cases are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "label": "agent choice",
          "choice": "A server that an automatic chat-off pass launched only for picture reading and that could not enter is stopped again through the controller: the recovery fence keeps refusing drains that start or relaunch a server, and admits only the deactivation that stops it."
        }
      ],
      "evidence": "gates/test-intent/evidence/b100-vdu-breaker-java.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/chat/GenerationTrackerTest.java#recoveryFenceAdmitsOnlyTheDeactivationThatStopsTheServer"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "The readiness envelope reports vdu.breaker_open.refused and vdu.breaker_open.entry_failed as the visualDocumentUnderstanding reason (DEGRADED, in aiFeatures, not retrieval) instead of normalising them to ocr.engine_missing. Existing cases are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "B1",
          "quote": "3 refused passes, then no 4th entry or relaunch within the back-off; status shows the reason.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "label": "agent choice",
          "choice": "Name the two pause causes vdu.breaker_open.refused and vdu.breaker_open.entry_failed, carried in the existing VDU blocked-reason field and mapped like the other VDU codes (visualDocumentUnderstanding degraded, aiFeatures composite, not retrieval)."
        }
      ],
      "evidence": "gates/test-intent/evidence/b100-vdu-breaker-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLifecycleHandlerTest.java#pausedAutomaticPictureReadingReportsItsOwnReason"
      ]
    },
    {
      "items": [
        "modules/app-engine/src/test/java/io/justsearch/app/engine/VduDeferredRetryTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "(1) The pass owner is built with the new breaker clock, an AtomicLong the test advances (the five-argument constructor remains as the fallback). In the refusals=3 case, three refused automatic passes in a row now pause automatic picture reading by design (B1). The test therefore advances that clock by the five-minute back-off before the final idle tick, and the half-open probe is the pass that reads the document. Inputs, the closed-gate fault, the per-tick pass/attempt/PENDING observations, the final pass count (refusals + 1) and the COMPLETED outcome are kept. refusals=1 and deferralsDoNotSpendTheRetryBudget keep their oracles. (2) The idle sampler is built through its existing package-private uptime seam with an uptime past the idle threshold, as the b85 round-2 adaptation did for VduOfflineTriggerSamplerTest. Without it both parameterised cases fail on bc5978095 itself whenever the test JVM is younger than five minutes ('idle tick 1 starts a pass for the deferred document ==> expected: <1> but was: <0>'), because the sampler caps idle time at process uptime.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "B1",
          "quote": "3 refused passes, then no 4th entry or relaunch within the back-off; status shows the reason.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "B3",
          "quote": "probe after 5 min; failure reopens with 10, then 20, capped at 60; success closes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do whatever you think is best. your working autonomously",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-11T16:05Z (F:/agent-sandbox/design-b6d536/owner-turns-886833dd.md:94)",
          "delegationScope": "Design and implement the JustSearch controller and VDU behaviour within the accepted specifications.",
          "label": "agent choice",
          "choice": "Keep the deferred-retry oracle: the document deferred by refusals is read automatically once generation is admitted, through the half-open probe after the back-off when three refusals paused automatic passes. Isolate the startup dwell without sleeping five minutes."
        }
      ]
    }
  ]
}
```

## Acceptance records
