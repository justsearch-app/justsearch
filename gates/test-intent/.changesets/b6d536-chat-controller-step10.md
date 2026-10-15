---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, design item 5, Spec A step 10 on top of a3e96417e: device lost and resume
(U21, U22). The chat controller takes ChatDeviceLoss (reportDeviceLost, reportResume; app-api).
Device-lost evidence comes from three sources: an encoder's CUDA loss (EncoderDeviceLossReporter,
now started by the Engine root when the controller binds its input), a llama-server exit whose
output names a listed code (LLAMA_EXIT), and the resume probe (RESUME_PROBE). The controller
interrupts the step in progress, stops the server now with DEVICE_LOST, then reads the Engine's
earlier DEVICE_LOST self-intents (F10), and within the limit stops for good with DEVICE_LOST and
asks the Engine to restart (exit 4, the supervisor's expected code for DEVICE_LOST); over it, or
fail closed, chat is FAILED with device_lost_limit. Device loss is exactly Spec E §6.1's code list. A resume restarts the dwell, the boot-order deadline and the server's timers
in full, clears a latched FAILED (T34) and probes a serving server. No production resume source
is built (Spec A O8): reportResume stays unwired here; its source, the Engine power events
(SystemPowerEvents) on branch b6d536/one-quit (d20c300d1), is wired at that merge. This changeset
covers only these files.

The earlier layer-stub experiment is withdrawn as fail-before proof: its reached no-op and
fixed-result methods determined the missing effects. The four New entries are candidate-only.
A fresh run-evidence attempt overlays the current checks and HealthyFakeLlamaServer onto the
actual a3e96417e parent, without production transplants or outcome stubs. The old APIs needed
by these checks are absent; the specific limitation is stated in each entry. The parent API
audit and attempted-run logs are in F:/phaseb-fix3-b6d536-20261015. No assertion repair is claimed.

Preservation: the pre-existing checks on the touched seams (the crash relaunch through the
extended stand-in, the restart dispatch HeadlessApp now shares with the device-lost restart, the
self-intents and the encoder reporter whose start now takes ChatDeviceLoss) pass on a3e96417e and
after, recorded in the preserved evidence: ChatCrashRestartTest.java, EncoderDeviceLossReporterTest.java, EngineShutdownSelfIntentTest.java, HeadlessAppComponentEscalationTest.java, HeadlessAppSelfIntentTest.java.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatDeviceLossTest.java"
      ],
      "class": "New",
      "behaviour": "Spec A §9.2 U21 on the chat controller, on new API (ChatDeviceLoss, DeviceLostRecovery and the recovery constructor are new in this change), with a stand-in actuator and a fixed wall clock. withinTheLimitTheServerStopsNowAndTheEngineRestarts: with one earlier device-lost restart 30 minutes ago, a device loss reported while a step waits on the device interrupts that step (its caller gets InterruptedException), the device-lost step stops the server now (draining with DEVICE_LOST), then the controller stops for good with DEVICE_LOST (terminalStop(DEVICE_LOST), desired terminal DEVICE_LOST) and the Engine restart is requested once; nothing is latched. theServerStopsBeforeTheLedgerIsRead and overTheLimitTheServerAlsoStopsBeforeTheLedgerIsRead: the server stop comes before the ledger read, within the limit and over it (Spec A §7.3 items 2 then 3). twoEarlierRestartsInTheHourStillLeaveRoomForAThird: restarts 50 and 11 minutes ago (and one 61 minutes ago) leave room. aRestartInTheLastTenMinutesFailsChatInsteadOfRestarting (5 minutes ago), threeRestartsInTheLastHourFailChatInsteadOfRestarting (55, 35, 15 minutes ago; LLAMA_EXIT 700), anUnreadableLedgerFailsClosed (the history throws IOException) and noBoundRestartPathFailsClosed (the three-argument constructor): the server stops once, draining with DEVICE_LOST, then device_lost_limit is latched, nothing restarts and the controller stays operable. aSecondReportWhileTheFirstIsHandledIsIgnored: a report while the first is handled runs no second stop. theLimitCountsTheNewRestartAgainstBothWindows: no restart in the last 10 minutes (exactly 10 minutes ago is outside) and at most two in the last hour; a record dated ahead counts. Candidate-only. before-state not reproducible: a3e96417e has no ChatDeviceLoss, DeviceLostEvidence or DeviceLostRecovery types, no recovery constructor and no reportDeviceLost or withinDeviceLostLimit entry points on ChatServerControllerImpl. Adding reached implementations or fixed-result stubs would determine the outcomes.",
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
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A step 10) end to end within the agreed scenarios.",
          "choice": "Spec A §7.3 and reconciliation F10 as the builder implements them: the controller acts on a device loss on its own thread ahead of every queued step and interrupts the step in progress; one step stops the server now with DEVICE_LOST and only then reads the Engine's earlier DEVICE_LOST self-intents (Spec A §7.3 order); within the limit (none in the last 10 minutes and at most two in the last hour, so the new restart is at most the third) the controller then stops for good with DEVICE_LOST and asks the Engine to restart; otherwise, and whenever the history cannot be read or a self-intent write of this incarnation failed (F10: 'a write failed this incarnation'), the step latches FAILED device_lost_limit without a terminal, so the controller stays operable for an operator retry or a resume. Further reports while one is handled are logged and ignored.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step10-new.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatDeviceLossTest.java#withinTheLimitTheServerStopsNowAndTheEngineRestarts",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatDeviceLossTest.java#theServerStopsBeforeTheLedgerIsRead",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatDeviceLossTest.java#overTheLimitTheServerAlsoStopsBeforeTheLedgerIsRead",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatDeviceLossTest.java#twoEarlierRestartsInTheHourStillLeaveRoomForAThird",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatDeviceLossTest.java#aRestartInTheLastTenMinutesFailsChatInsteadOfRestarting",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatDeviceLossTest.java#threeRestartsInTheLastHourFailChatInsteadOfRestarting",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatDeviceLossTest.java#anUnreadableLedgerFailsClosed",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatDeviceLossTest.java#noBoundRestartPathFailsClosed",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatDeviceLossTest.java#aSecondReportWhileTheFirstIsHandledIsIgnored",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatDeviceLossTest.java#theLimitCountsTheNewRestartAgainstBothWindows"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResumeTest.java"
      ],
      "class": "New",
      "behaviour": "Spec A §9.2 U22 and T34 on the chat controller, on new API (reportResume, noteResume and resumeProbe are new in this change). theResidencyDwellRestartsInFullOnResume: 60 s into the 120 s reload dwell after a pressure unload, a resume restarts it, so 60 s later a person's chat is still refused RELOAD_BACKOFF with 60 s to wait and loads after them. theServerTimersResetAtOnceAndAServingServerIsProbedAfterTheStepInProgress: while a step runs, the resume resets the server's timers at once (noteResume before the step ends) and the probe runs after the step, in that order. aProbeThatFindsTheDeviceLostTakesTheDeviceLostPath: a probe result DEVICE_LOST 719 takes the device-lost path (within the limit: terminal DEVICE_LOST and the Engine restart). aPassingProbeChangesNothing. theResumeClearsAFailureLatchAndSendsTheIntentAgain: a latched CRASH_LOOP is cleared and the activation intent is sent again once; withoutALatchTheResumeDoesNotReplan. theBootOrderDeadlineRestartsInFullOnResume: a 3 s boot-order gate resumed after 2 s does not expire at the first deadline and expires about 3 s after the resume. aResumeAfterTheControllerStoppedDoesNothing. Candidate-only. before-state not reproducible: a3e96417e has no reportResume entry point on ChatServerControllerImpl and no noteResume or resumeProbe actuator contract. There is no old resume operation to re-encode these calls against; reached resume stubs would determine the timer, probe and latch outcomes.",
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
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A step 10) end to end within the agreed scenarios.",
          "choice": "Spec A §7.4 as the builder implements it on the controller: a resume restarts a running reload dwell and a closed boot-order deadline in full, resets the server's timers at once from the caller's thread (not queued behind a running step), clears a latched FAILED and sends the activation intent again through the boot replay (T34), then runs one probe step ahead of the queue; a probe that finds device-lost text reports RESUME_PROBE with its code. No production resume source is built here (Spec A O8): reportResume has no caller yet; its source, the Engine power events (SystemPowerEvents) on branch b6d536/one-quit (d20c300d1), is wired at that merge.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step10-new.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResumeTest.java#theResidencyDwellRestartsInFullOnResume",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResumeTest.java#theServerTimersResetAtOnceAndAServingServerIsProbedAfterTheStepInProgress",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResumeTest.java#aProbeThatFindsTheDeviceLostTakesTheDeviceLostPath",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResumeTest.java#aPassingProbeChangesNothing",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResumeTest.java#theResumeClearsAFailureLatchAndSendsTheIntentAgain",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResumeTest.java#withoutALatchTheResumeDoesNotReplan",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResumeTest.java#theBootOrderDeadlineRestartsInFullOnResume",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatResumeTest.java#aResumeAfterTheControllerStoppedDoesNothing"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatResumeProbeTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerDeviceLostCodeTest.java"
      ],
      "class": "New",
      "behaviour": "The manager's step-10 primitives on the real seam, on new API (resumeProbe, deviceLostStop, noteResume and deviceLostCode are new in this change): a lifecycle manager runs llama-server as the separate stand-in process. aServerThatAnswersHealthAndOneTokenPassesTheProbe: PASSED, no relaunch. aFailedOneTokenProbeStopsTheServerAndRelaunchesItOnce: a /completion answering 500 gives RELAUNCHED; the probed server is stopped and a second launch with the same window serves; no failure is latched. aProbeFailureWithDeviceLostTextReportsTheCodeAndLeavesTheServerToTheController: a /completion that prints 'CUDA error: unspecified launch failure' gives DEVICE_LOST 719 and no relaunch. withChatOffThereIsNothingToProbe: NOT_SERVING. theDeviceLostStopEndsTheServerAndLatchesDeviceLostLimit: the server process has ended when the ledger decision is asked; told no restart, chat goes offline with device_lost_limit, nothing relaunches, and noteResume clears the latch (T34). withinTheLimitTheDeviceLostStopEndsTheServerFirstAndLatchesNothing: the server has ended when the decision is asked; told restart, the step returns true and latches nothing. aResumeRestartsTheStartsHealthDeadlineInFull: a start whose /health answers 503 (loading) survives a resume 5 s before its deadline followed by 30 s of the test clock, and serves once loading ends; withoutAResumeTheStartsHealthDeadlineStillEnds (preservation of the monotonic deadline). aStartExitNamingALostDeviceIsReportedToTheController: a start that exits printing the 719 text is reported to the installed router as LLAMA_EXIT 719; aStartExitWithoutDeviceLostTextIsNotReported: out of memory is not (F17). LlamaServerDeviceLostCodeTest: exactly Spec E §6.1's closed list (100, 214, 700, 702, 714-718, 719, 803, 35, 999): each code's CUDA error text in a llama-server exit is DEVICE_SUSPECT with that line as evidence and names its code; numbered ('CUDA error 700', ORT's 'CUDA failure 719:'), runtime and driver names name theirs; 46 (cudaErrorDevicesUnavailable), 2 (out of memory), 713, 7190, 'device lost' without a code, an 'unknown error' line without CUDA and empty output name nothing; server output names the code while the process lives. Candidate-only. before-state not reproducible: a3e96417e has no InferenceLifecycleManager.resumeProbe, deviceLostStop or noteResume, and no LlamaServerOps.deviceLostCode. The checks directly call these new operations; supplying their return values or effects would determine the probe, stop, timer and code assertions.",
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
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A step 10) end to end within the agreed scenarios.",
          "choice": "Spec A §7.3 source 2 and §7.4 in the manager as the builder implements them: a llama-server line that mentions CUDA and names a code of Spec E §6.1's closed list by number, CUDA runtime or driver name, or cudaGetErrorString text is DEVICE_SUSPECT and device loss with that code (reconciliation F17: the list is Spec E's, the same set as the encoder side's DeviceLostClassifier); the classifier's other DEVICE_SUSPECT strings without a listed code stay DEVICE_SUSPECT (never retried in place) but are not device loss; the resume probe waits for a reply in progress, then asks /health within 5 s and one token from /completion within 10 s inside an AUX lease; a dead process is left to the crash path, device-lost text is returned to the controller, and any other failure stops the server with TIMEOUT and relaunches it once with the same request; the device-lost step stops the server first and then asks the controller's ledger decision; without a restart it latches device_lost_limit before going offline; an external server is left alone and never latched; a resume restarts a start's health deadline in full and clears the latch and the spent same-configuration restart.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step10-new.json",
      "newChecks": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatResumeProbeTest.java#aServerThatAnswersHealthAndOneTokenPassesTheProbe",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatResumeProbeTest.java#aFailedOneTokenProbeStopsTheServerAndRelaunchesItOnce",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatResumeProbeTest.java#aProbeFailureWithDeviceLostTextReportsTheCodeAndLeavesTheServerToTheController",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatResumeProbeTest.java#withChatOffThereIsNothingToProbe",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatResumeProbeTest.java#theDeviceLostStopEndsTheServerAndLatchesDeviceLostLimit",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatResumeProbeTest.java#withinTheLimitTheDeviceLostStopEndsTheServerFirstAndLatchesNothing",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatResumeProbeTest.java#aResumeRestartsTheStartsHealthDeadlineInFull",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatResumeProbeTest.java#withoutAResumeTheStartsHealthDeadlineStillEnds",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatResumeProbeTest.java#aStartExitNamingALostDeviceIsReportedToTheController",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ChatResumeProbeTest.java#aStartExitWithoutDeviceLostTextIsNotReported",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerDeviceLostCodeTest.java#theCudaErrorsOnTheListNameTheirCodes",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerDeviceLostCodeTest.java#everyCodeOnSpecEsListIsDeviceLossByItsCudaErrorText",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerDeviceLostCodeTest.java#numberedAndNamedCodesOnTheListAreDeviceLoss",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerDeviceLostCodeTest.java#codesOffTheListAreNotDeviceLoss",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerDeviceLostCodeTest.java#otherDeviceSuspectExitsAndOutOfMemoryAreNotDeviceLoss",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerDeviceLostCodeTest.java#serverOutputNamesTheCodeWhileTheProcessLives"
      ]
    },
    {
      "items": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/DeviceLostRestartHistoryTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineShutdownDeviceLostTest.java",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineDeviceLossWitnessTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppDeviceLostRestartTest.java"
      ],
      "class": "New",
      "behaviour": "The Engine's device-lost restart path, on new API (DeviceLostRestartHistory, runAndExitDeviceLost, bindDeviceLostRecovery and the HeadlessApp recovery are new in this change). DeviceLostRestartHistoryTest: only engine-self-intent records with reason DEVICE_LOST count, in ledger order, among ENGINE_RESTART and QUIT records; an empty data directory has none; an unreadable line makes the history an IOException; a torn tail (a write in progress) is not an error. EngineShutdownDeviceLostTest: the restart records the self-intent DEVICE_LOST, no detail, expected code 4, closes in restart order and exits 4; an unclean close exits 1; a second exit request adds nothing. EngineDeviceLossWitnessTest: wired as the Engine composes it, an encoder loss on CUDA device 73 (DeviceHealth) reaches the controller the inference factory builds, which reads the bound restart path once, stops for good with DEVICE_LOST and requests the restart. HeadlessAppDeviceLostRestartTest: the recovery counts this data directory's DEVICE_LOST self-intents (2 of 3 records), throws IOException when a self-intent write failed, and its restart publishes the pending restart, records the self-intent, closes, exits 4, and the next incarnation counts it. Candidate-only. before-state not reproducible: a3e96417e has no DeviceLostRestartHistory or DeviceLostRecovery, no EngineShutdownSequence.runAndExitDeviceLost, no EngineRoot.bindDeviceLostRecovery and no HeadlessApp.deviceLostRecovery. The old requested restart is ENGINE_RESTART rather than the new device-loss restart/history/binding contract; adding or replacing these operations would determine the recorded intent, count and restart outcomes.",
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
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A step 10) end to end within the agreed scenarios.",
          "choice": "Reconciliation F10 as the builder implements it: the Engine counts its own DEVICE_LOST engine-self-intent records in the engine ledger (any skipped line or a missing timestamp makes the count unreadable; a torn tail does not), fails closed when a self-intent write of this incarnation failed, and restarts through the requested-restart dispatch with a DEVICE_LOST self-intent expecting exit 4. The encoder reporter is started once per incarnation by the Engine root when the chat controller binds its device-loss input, and closes with the process resources.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step10-new.json",
      "newChecks": [
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/DeviceLostRestartHistoryTest.java#anEmptyDataDirectoryHasNoEarlierRestarts",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/DeviceLostRestartHistoryTest.java#onlyDeviceLostSelfIntentsCountInLedgerOrder",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/DeviceLostRestartHistoryTest.java#anUnreadableLineMakesTheHistoryUnreadable",
        "modules/app-api/src/test/java/io/justsearch/app/api/runtime/ledger/DeviceLostRestartHistoryTest.java#aWriteStillInProgressAtTheTailIsNotAnError",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineShutdownDeviceLostTest.java#aDeviceLostRestartRecordsItsIntentClosesInRestartOrderAndExitsFour",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineShutdownDeviceLostTest.java#anUncleanCloseExitsFatalInstead",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineShutdownDeviceLostTest.java#aSecondExitRequestAddsNothing",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineShutdownDeviceLostTest.java#theIntentIsTheOneTheSupervisorExpectsForDeviceLost",
        "modules/app-engine/src/test/java/io/justsearch/app/engine/EngineDeviceLossWitnessTest.java#anEncoderDeviceLossRestartsTheEngineThroughTheBoundPath",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppDeviceLostRestartTest.java#theEarlierRestartsAreThisDataDirectorysDeviceLostSelfIntents",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppDeviceLostRestartTest.java#aFailedSelfIntentWriteFailsTheCountClosed",
        "modules/ui/src/test/java/io/justsearch/ui/HeadlessAppDeviceLostRestartTest.java#theRestartRecordsTheIntentTheNextIncarnationCountsAndExitsFour"
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/HealthyFakeLlamaServer.java"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The shared llama-server stand-in gains a /completion context and three opt-in behaviours for step 10; install(dir) and every existing behaviour and caller are unchanged. /completion answers 200 with one token by default; --completion-fails answers 500; --completion-cuda-failure prints 'CUDA error: unspecified launch failure' and a ggml-cuda location line to stdout (the launch log) and answers 500 while the process lives; --loading-until-signal answers /health 503 'Loading model' until signalReady(dir) writes ready-signal. No assertion in an existing test changes; the existing users (ChatCrashRestartTest, HealthyFakeLlamaServerTest and the other launch tests) pass in the full app-inference suite, and ChatCrashRestartTest passes before and after in the preserved evidence.",
      "behaviour": "",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A step 10) end to end within the agreed scenarios.",
          "choice": "Spec A step 10's probe and deadline checks run against the same stand-in process as the crash checks, extended only with opt-in behaviours.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step10-preserved.json"
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
  "verdict": "reject",
  "digest": "sha256:e73cc7ead1fb0f78b1fc874cfdb1cad0611064fb3aee5ba8bb0d6069b4ba7a58"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS3-20261015",
  "verdict": "reject",
  "digest": "sha256:21f9f4e8e46a1c03b0e576c61d38ca2e2775b5a0ee1d68d413ee6851e5ce07be",
  "note": "The preamble and New entries present manual failures from candidate device-loss/resume methods replaced by no-op or fixed-result stubs as fail-before proof. Those reached stubs determine the asserted missing effects; candidate checks passing does not make these manufactured failures valid before-state evidence."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS3b-20261015",
  "verdict": "accept",
  "digest": "sha256:035dc988c2f1f8b7208f521a1e6a8d9885ca9b6b9be60cabd0ba0cdec3a224b2",
  "note": "Reran all 46 changed-entry checks successfully; the actual a3e96417e replay and API audit confirm the stated missing APIs, so the candidate-only reasons are true and withdrawn outcome-stub failures are not proof. Declarations match assertions; other entries and evidence are unchanged since 9f1f6257b; bound to main."
}
```
