---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: 886833dd-a08f-492f-a40a-cc0976b5ce5e
---

# Test-intent entries

Task t-20261005-b6d536, design item 5 (Spec A steps 5 and 6, reconciliation v2). The chat
controller gains holds: a freeze, a self-test session, a VDU hold and a running hold. Runtime
activation runs its self-test as a session on the controller: the main server is stopped first,
the candidate is launched by the controller through a `SelfTestLauncher` seam, and the
activation's own launcher is removed. The VDU batch, the post-install smoke test and install
acquisition take holds instead of reconciler procedures; the reconciler mirrors the holds as its
PROCEDURE axis and its `procedureRequireEngine` is deleted. The VDU embedding hand-off stop is
removed (O2), and the VDU trigger reads the controller. This changeset covers only this slice's
files. Two evidence files of the step 2 changeset (`b6d536-chat-controller-repaired.json` and
`b6d536-chat-controller-guardrails.json`) were re-run unchanged in their checks, because this
slice edits `RuntimeReconcilerTest` and a comment in `RuntimeReconcilerGuardrailsTest`.

Records phase B (batch 3) re-ran `b6d536-chat-controller-step56-unit.json`,
`b6d536-chat-controller-step56-rewritten.json` and `b6d536-chat-controller-step56-reconciler.json`
on b599d4d7e because their check files changed after the first runs. Every check passes. The
reconciler file's preservation checks no longer compile against d3fe74ef2 even with the
TestActivationForwarders overlay: RuntimeReconcilerTest builds RuntimeReconciler with the lease-free
constructor that 5e4f4e698 left. So that file was re-run without a before-state.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java"
      ],
      "class": "New",
      "behaviour": "The holds on ChatServerControllerImpl, over a recording ChatActuator fake (no process). admissionFollowsThePrecedenceForEveryKindInEveryState: all 256 combinations of terminal, failure latch, open hold kinds and requested kind: a freeze is always admitted; then terminal gives ENGINE_STOPPING; an open self-test gives SELF_TEST (BUSY for a second self-test); a self-test needs every VDU and running hold closed (BUSY) but not a freeze; the latch gives FAILED for VDU and running holds (F11); one VDU hold at a time (BUSY); running holds share. whileAHoldIsOpenActivationsAreNotActedOnAndTheLastCloseTellsTheForwarder: a running hold inside a freeze still starts the server (VDU_ENTER); a stop activation while held is superseded and nothing is stopped; close is idempotent; listeners see open:FREEZE, open:RUNNING, close:RUNNING:false, close:FREEZE:true; with nothing held the next activation acts. aVduHoldStartsTheServerEntersVduAndExitsBeforeItReleases: up, vdu-enter; a second VDU hold is BUSY; vduMayStart is false while held; vdu-exit runs before the release reaches the forwarder. aFailedVduEntryReturnsTheServerToItsConfigurationAndReleasesTheHold: FAILED with the ModeTransitionException as cause, vdu-exit runs, no hold left open. aLatchedFailureRefusesVduAndRunningHoldsWithFailedBeforeAnyStep: FAILED with no actuation, vduMayStart false, an activation self-test is still admitted. aSelfTestStopsTheMainServerFirstRunsTheCandidateAndEndsOnClose (U11, T37-T39): the main server is down before the session returns; inside it VDU and running holds are SELF_TEST, a second session BUSY, an activation superseded, vduMayStart false (U13); the candidate runs on the controller thread with the main server down; the log is up, self-test-stop, self-test-end, so the main server is started only by the forwarder's replan; a closed session runs nothing. aSelfTestWaitsForEveryActuatingHoldButNotForAFreeze. aSelfTestFromIdleGoesStraightInAndEveryHoldDrainsWithinThirtySeconds: with no server the session does not wait; the SELF_TEST and VDU_ENTER drains are bounded at 30 s, REFUSE_BUSY, only while serving. repliesThatDoNotFinishInTheBoundRefuseTheHoldBusyAndReleaseIt: with a 200 ms bound and a drain that never ends, beginSelfTest is BUSY within 5 s, the server keeps running, the session ends and no hold is left. aHoldTakenUnderACallerLockNeverNeedsThatLockAndStaysObservable (F17): a caller holding its own lock begins and runs a session; while the controller drains, openHolds and vduMayStart answer within 1 s from other threads, and no controller step ran while the caller's lock was needed. aHoldRequestedFromTheControllerThreadThrows: IllegalStateException, not a deadlock. afterShutdownHoldsAreRefusedAndAnOpenVduHoldIsAskedToFinish: an open VDU hold reads interruptRequested after shutdown, closing it releases it, and new holds are ENGINE_STOPPING. The holds API is new in this change, so there is no before-state.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S19",
          "quote": "The activation self-test never loads a second chat model beside the running one when that would not fit.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 5-6) end to end within the agreed scenarios.",
          "choice": "Spec A steps 5 and 6 with reconciliation v2 (F11, F17) as the builder implements them: the chat controller owns four hold kinds (freeze, self-test session, VDU, running) with the admission precedence of Spec A section 2.2; while any hold is open activations are superseded and the last close tells the forwarder to replan; taking a hold drains for at most 30 s and refuses BUSY when replies do not finish; the self-test session stops the main server first (SELF_TEST), runs the candidate on the controller thread and starts nothing on close; holds are refused after shutdown and an open VDU hold is asked to finish.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step56-unit.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java#admissionFollowsThePrecedenceForEveryKindInEveryState",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java#whileAHoldIsOpenActivationsAreNotActedOnAndTheLastCloseTellsTheForwarder",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java#aVduHoldStartsTheServerEntersVduAndExitsBeforeItReleases",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java#aFailedVduEntryReturnsTheServerToItsConfigurationAndReleasesTheHold",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java#aLatchedFailureRefusesVduAndRunningHoldsWithFailedBeforeAnyStep",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java#aSelfTestStopsTheMainServerFirstRunsTheCandidateAndEndsOnClose",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java#aSelfTestWaitsForEveryActuatingHoldButNotForAFreeze",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java#aSelfTestFromIdleGoesStraightInAndEveryHoldDrainsWithinThirtySeconds",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java#repliesThatDoNotFinishInTheBoundRefuseTheHoldBusyAndReleaseIt",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java#aHoldTakenUnderACallerLockNeverNeedsThatLockAndStaysObservable",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java#aHoldRequestedFromTheControllerThreadThrows",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/ChatHoldsTest.java#afterShutdownHoldsAreRefusedAndAnOpenVduHoldIsAskedToFinish"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SelfTestSessionSeamTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceChatProfileTest.java"
      ],
      "class": "New",
      "behaviour": "U11 and I6 on the real seam, and the RuntimeActivationService client. SelfTestSessionSeamTest wires InferenceLifecycleManager (the controller) over the slow-start fake llama-server, which runs as a separate process and records each launch, and a RuntimeReconciler following the holds. theMainServerStopsBeforeTheCandidateAndLoadsOnceAfterTheCommit: booted online on model.gguf; inside beginSelfTest no launched server is alive, the manager is not online, vduMayStart is false, a direct switchToOnlineMode throws, and a spec write is deferred; session.run launches the candidate (candidate.gguf) as the controller's own process and it is exercised; after it no server is alive; applyConfig to the candidate inside the session launches nothing; after close and the spec nudge the manager is online and the launches are exactly model, candidate, candidate (one load after the commit, not two); a sampler that keeps each launched process's handle never sees two servers alive at once (I6). fromIdleTheSessionGoesStraightInAndClosingReturnsToAnOffIntent: with the intent off, beginSelfTest returns within 10 s and nothing is launched before or after. RuntimeActivationServiceChatProfileTest selfTestAndCommitRunInsideOneControllerSession: activation begins the session (runtime-variant-activation), runs the self-test, commits one profile, then closes the session, in that order. aRefusedSessionFailsTheActivationBeforeAnySelfTestOrCommit: a BUSY refusal fails the activation with SELF_TEST_FAILED naming BUSY, no self-test runs and nothing is committed. beginSelfTest and the session are new, so there is no before-state; on the before-state RuntimeActivationService launched its own self-test server beside the running one.",
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
          "choice": "Spec A step 5 (U11, I6) as the builder implements it: runtime activation is a client of the chat controller; it begins a self-test session, which stops the main server first, the candidate is launched by the controller through a SelfTestLauncher seam (where Spec B step 6 can plug in ChildControl), the commit happens inside the session, and the main server loads once, on the configuration committed, after the session closes; the activation's own launcher is removed. A refused session fails the activation with SELF_TEST_FAILED.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step56-unit.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SelfTestSessionSeamTest.java#theMainServerStopsBeforeTheCandidateAndLoadsOnceAfterTheCommit",
        "modules/app-services/src/test/java/io/justsearch/app/services/chat/SelfTestSessionSeamTest.java#fromIdleTheSessionGoesStraightInAndClosingReturnsToAnOffIntent",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceChatProfileTest.java#selfTestAndCommitRunInsideOneControllerSession",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceChatProfileTest.java#aRefusedSessionFailsTheActivationBeforeAnySelfTestOrCommit"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduChatHoldTest.java"
      ],
      "class": "New",
      "behaviour": "The VDU side of step 6. theBatchTakesOneVduHoldAndClosesItOnExit: VduProcessor.enterVduMode takes one VDU hold (owner vdu-batch); a second entry while holding fails and closes its own duplicate hold; exit closes the batch's hold once, a second exit closes nothing. aRefusedHoldFailsTheEntryWithTheReason: a SELF_TEST refusal fails the entry with a message naming SELF_TEST and the refusal as cause. whenTheControllerAsksTheBatchFinishesEarlyAndLeavesTheRestPending (U12, the part without residency pressure): when the hold reads interruptRequested after the first document, the batch processes 1 of 3, leaves 2 remaining with PROCESSING_REFUSED and closes the hold. theTriggerFiresOnlyWhenTheControllerSaysAPassMayStart (U13): VduOfflineTriggerSampler wired as CoreApiAssembly wires it starts no pass while vduMayStart is false and one when it turns true. The hold API and the sampler's controller reading are new, so there is no before-state.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 5-6) end to end within the agreed scenarios.",
          "choice": "Spec A step 6 (U12 without the residency signal, U13) as the builder implements it: a VDU batch takes one VDU hold on the chat controller instead of switching modes itself, finishes early and leaves the rest pending when the controller asks, and the automatic VDU trigger reads the controller's vduMayStart, so a server that is down for a self-test, a latched failure or an open hold never triggers a pass.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step56-unit.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduChatHoldTest.java#theBatchTakesOneVduHoldAndClosesItOnExit",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduChatHoldTest.java#aRefusedHoldFailsTheEntryWithTheReason",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduChatHoldTest.java#whenTheControllerAsksTheBatchFinishesEarlyAndLeavesTheRestPending",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduChatHoldTest.java#theTriggerFiresOnlyWhenTheControllerSaysAPassMayStart"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceChatHoldTest.java"
      ],
      "class": "New",
      "behaviour": "Spec A row 10, the post-install smoke test. aPassingSmokeTestRunsInsideARunningHoldAndReleasesIt: the smoke test takes a running hold (post-install-smoke-test), asks its question, passes, closes the hold once and makes no direct mode switch. aFailingOrCancelledSmokeTestStillReleasesItsHold: a blank answer fails with SMOKE_TEST_FAILED and a cancelled install ends cancelled with the question cancelled; each closes its hold. aRefusedHoldFailsTheSmokeTestWithTheReasonAndAsksNothing: a FAILED refusal fails the smoke test with SMOKE_TEST_FAILED and a message naming FAILED, asks no question and switches nothing. The constructor taking the holds is new, so there is no before-state.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 5-6) end to end within the agreed scenarios.",
          "choice": "Spec A step 6 rows 10 and 11 as the builder implements them: the post-install smoke test runs inside a running hold on the chat controller instead of a reconciler procedure with procedureRequireEngine, and releases it on every exit; install acquisition takes a freeze. Without a controller wired the legacy direct switch remains.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step56-unit.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceChatHoldTest.java#aPassingSmokeTestRunsInsideARunningHoldAndReleasesIt",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceChatHoldTest.java#aFailingOrCancelledSmokeTestStillReleasesItsHold",
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceChatHoldTest.java#aRefusedHoldFailsTheSmokeTestWithTheReasonAndAsksNothing"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerHoldsTest.java"
      ],
      "class": "New",
      "behaviour": "followHoldsMirrorsEachHoldKindAsItsProcedureAndDefersForwarding: RuntimeReconciler.followHolds registers one listener; each hold kind opening shows on the PROCEDURE axis as the procedure it replaced (FREEZE as INSTALL_ACQUISITION, SELF_TEST as ACTIVATION, VDU as VDU_BATCH, RUNNING as INSTALL_SMOKE_TEST) and closing returns it to none; with a self-test open a spec-off write makes no switch, and the close forwards it (one indexing switch); closing the follower removes the listener. followHolds is new, so there is no before-state.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 5-6) end to end within the agreed scenarios.",
          "choice": "Spec A step 6 as the builder implements it: the reconciler's procedures become the chat controller's holds; the reconciler keeps publishing the PROCEDURE axis by mirroring each open hold kind as the procedure it replaced and forwards deferred intent when the last hold closes.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step56-unit.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerHoldsTest.java#followHoldsMirrorsEachHoldKindAsItsProcedureAndDefersForwarding"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerTest.java"
      ],
      "class": "Obsolete",
      "removedChecks": "The expectations that the reconciler brings the engine up for a procedure and parks it for embeddings: procedureWithSpecOn_returnsEngineOnlineAfterEnd's procedureRequireEngine(true)/(false) calls, the VDU_ENTER and AUTO_START convergence reasons and the [online, indexing, online] sequence; procedureWithSpecOff_engineStaysDownAfterEnd's 'only the procedure's Phase-A up' online count of 1; installSmokeTestProcedure_specOff_engineUpNoDown_thenOneDownAtEnd's 'exactly the procedure's up-switch' count of 1 and its [online, indexing] sequence; and the procedureRequireEngine(true) inputs of procedureHoldsEngineUpSpecOff_engineReasonIsBackground and specWriteDuringProcedure_deferredUntilEnd. The rewritten checks are the New entry below.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 5-6) end to end within the agreed scenarios.",
          "choice": "Spec A step 6 as the builder implements it: the chat controller, not the reconciler, starts the server for a VDU, running or self-test hold; the reconciler's procedureRequireEngine and its embedding-phase park are deleted (O2: the VDU embedding hand-off stop is removed).",
          "label": "agent choice"
        },
        {
          "kind": "removal",
          "removed": [
            "modules/app-services/src/main/java/io/justsearch/app/services/runtimestate/RuntimeReconciler.java#procedureRequireEngine",
            "modules/app-services/src/main/java/io/justsearch/app/services/runtimestate/RuntimeReconciler.java#updateProcedurePhase"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions searched for hand-off/handoff, VDU with embedding, procedure names (beginProcedure, procedureRequireEngine, VDU_BATCH, INSTALL_SMOKE_TEST), self-test and smoke test; ADR-0002 and ADR-0049 use handoff for the retired port handoff and for thread hand-offs, ADR-0009 only as a line-wrapped 'hand-written'; ADR-0047 names VDU batches only as a VRAM co-resident; ADR-0050 decision 6 and ADR-0051, added on this branch, agree: one decider owns the chat server and runs the activation self-test with the main server stopped, and the path that stops chat so indexing gets the GPU retires)"
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerTest.java"
      ],
      "class": "New",
      "behaviour": "The rewritten procedure checks: the engine is brought up by the controller (simulated as an observed OFFLINE to ONLINE change during the procedure) and the reconciler only returns to spec when the procedure ends. procedureWithSpecOn_returnsEngineOnlineAfterEnd: spec on, the engine is ONLINE after the end and the reconciler made no switch (switchLog empty: no park for embeddings). procedureWithSpecOff_engineStaysDownAfterEnd: spec off, the engine is not ONLINE after the end, no up switch, exactly one stop [indexing]. procedureHoldsEngineUpSpecOff_engineReasonIsBackground and specWriteDuringProcedure_deferredUntilEnd keep their assertions with the controller's up as input. installSmokeTestProcedure_specOff_engineUpNoDown_thenOneDownAtEnd: no reconciler up switch, no stop while the procedure holds, then exactly one stop [indexing]. The evidence also runs them on d3fe74ef2, where they pass: given the same input, the forwarding at procedure end is unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 5-6) end to end within the agreed scenarios.",
          "choice": "Spec A step 6 as the builder implements it: the chat controller, not the reconciler, starts the server for a VDU, running or self-test hold; the reconciler only forwards spec when the last hold closes, so with spec on it makes no switch and with spec off it makes exactly one stop.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step56-reconciler.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerTest.java#procedureWithSpecOn_returnsEngineOnlineAfterEnd",
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerTest.java#procedureWithSpecOff_engineStaysDownAfterEnd",
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerTest.java#procedureHoldsEngineUpSpecOff_engineReasonIsBackground",
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerTest.java#specWriteDuringProcedure_deferredUntilEnd",
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerTest.java#installSmokeTestProcedure_specOff_engineUpNoDown_thenOneDownAtEnd"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/OfflineCoordinatorTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/OfflineCoordinatorFailureCleanupTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/OfflineCoordinatorOwnershipTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/OfflineProcessingOperationLifecycleIntegrationTest.java"
      ],
      "class": "Obsolete",
      "removedChecks": "The offline coordinator's procedure bracket, engine request and embedding hand-off. OfflineCoordinatorTest: vduRunsBeforeEmbeddingHandoff's expectations that the embedding count is read after VDU, the outcome is HANDED_OFF and the engine is switched online once and to indexing once; alreadyOnlineAvoidsRedundantTransition (no online switch when already online). OfflineCoordinatorFailureCleanupTest: setupFailureReleasesGuardAndDoesNotEndUnbegunProcedure, cleanupFailureReleasesGuardAndRemainsObservable, and the suppressed procedure-cleanup failure expected by bodyFailureRemainsPrimaryWhenProcedureCleanupAlsoFails and fatalBodyFailureIsPreservedThroughProcedureCleanup. OfflineCoordinatorOwnershipTest: the queued check's 'no procedure begun', the countPendingEmbeddings verify in admittedContextWithWorkIdFlowsToEveryProcedurePort, modeTransitionFailureIsVisibleAfterOwnershipRelease (a procedureRequireEngine failure wrapped in IllegalStateException) and modeEntryCheckpointFailurePreservesCauseAndFatality (that checkpoint now lives in the batch, where VduBatchProcessorModeScopingTest has the same check, unchanged). OfflineProcessingOperationLifecycleIntegrationTest: preselectionModeEntryFailureRecordsAiOfflineBeforeCleanupAndPreservesCause (the coordinator no longer requests the engine before selection), the postselection check's cleanup boundary (a refused VDU hold acquires nothing to clean up), and the countPendingEmbeddings verify of every port.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 5-6) end to end within the agreed scenarios.",
          "choice": "Spec A step 6 and O2 as the builder implements them: the offline coordinator no longer brackets a pass in a reconciler procedure or requests the engine; the VDU batch holds the chat controller itself; the embedding hand-off that stopped chat after VDU is removed on every profile, because the index half's own backfill embeds beside chat.",
          "label": "agent choice"
        },
        {
          "kind": "removal",
          "removed": [
            "modules/app-services/src/main/java/io/justsearch/app/services/vdu/OfflineCoordinator.java#processEmbeddingPhase",
            "modules/app-services/src/main/java/io/justsearch/app/services/vdu/OfflineCoordinator.java#procedureRequireEngine",
            "modules/app-services/src/main/java/io/justsearch/app/services/vdu/OfflineCoordinator.java#beginProcedure",
            "modules/app-services/src/main/java/io/justsearch/app/services/vdu/OfflineCoordinator.java#endProcedure",
            "modules/app-services/src/main/java/io/justsearch/app/services/vdu/OfflineCoordinator.java#finishProcedure",
            "modules/app-services/src/main/java/io/justsearch/app/services/vdu/OfflineCoordinator.java#inferenceManager",
            "modules/app-api/src/main/java/io/justsearch/app/api/OfflineProcessingOutcome.java#HANDED_OFF"
          ]
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none (docs/decisions searched for hand-off/handoff, VDU with embedding, procedure names (beginProcedure, procedureRequireEngine, VDU_BATCH, INSTALL_SMOKE_TEST), self-test and smoke test; ADR-0002 and ADR-0049 use handoff for the retired port handoff and for thread hand-offs, ADR-0009 only as a line-wrapped 'hand-written'; ADR-0047 names VDU batches only as a VRAM co-resident; ADR-0050 decision 6 and ADR-0051, added on this branch, agree: one decider owns the chat server and runs the activation self-test with the main server stopped, and the path that stops chat so indexing gets the GPU retires)"
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/OfflineCoordinatorTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/OfflineCoordinatorFailureCleanupTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/OfflineCoordinatorOwnershipTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/OfflineProcessingOperationLifecycleIntegrationTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VduProcedureExecutorLifetimeTest.java"
      ],
      "class": "New",
      "behaviour": "The rewritten coordinator checks, built with the new five-argument OfflineCoordinator. pendingEmbeddingsNeverStopChatAndThePassCompletes (O2): with 10 embeddings pending after a 5-document VDU pass, the outcome is NOT_NEEDED and complete. bodyFailureIsPrimaryAndReleasesTheGuardForTheNextPass and fatalBodyFailureIsPreservedAndReleasesTheGuard: the body's failure (also an Error) is the stage's failure with nothing suppressed, the guard is released and the next pass runs. queuedCancellationRunsNoBodyAndReleasesOwnershipExactlyOnce: a cancelled queued pass never runs the batch. runningCancellationRetainsStageGuardAndAdmissionThroughBlockedCleanup and VduProcedureExecutorLifetimeTest's two checks: the blocking step is now the batch (whose exit closes the VDU hold) or the recovery call instead of the reconciler's endProcedure; the guard, admission and executor accounting are held until it returns, as before. admittedContextWithWorkIdFlowsToEveryProcedurePort: the admitted context reaches recovery, the VDU count and the batch. vduModeFailureIsVisibleAfterOwnershipRelease: a batch failure to enter VDU mode is the stage's failure, seen after the work is closed. OfflineProcessingOperationLifecycleIntegrationTest: the cleanup boundary of the three outcome checks is VduProcessor.exitVduMode (the hold close) instead of endProcedure, with the same recorded outcomes; postselectionModeEntryFailureRecordsCapturedRemainderAndPreservesCause: a refused hold records selected 2, remaining 2, ai_offline, no document effects, no exit, FAILED with the refusal as cause; and no port counts pending embeddings. The constructor is new, so there is no before-state.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S7",
          "quote": "While chat generates, bulk GPU work (indexing, VDU) runs only at the duty floor and the app shows indexing slowed and why; while a game runs or adapter memory is low, no new bulk GPU batch starts and the app shows indexing paused and why; both resume afterwards (D4).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "Deciding the chat controller design of item 5 (Spec A steps 5-6) end to end within the agreed scenarios.",
          "choice": "Spec A step 6 and O2 as the builder implements them: the offline coordinator runs recovery, selection and the VDU batch under its guard and admission without a reconciler procedure; the batch's VDU hold on the chat controller is the procedure's cleanup; a pass with embeddings pending is complete and never stops chat.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-chat-controller-step56-rewritten.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/OfflineCoordinatorTest.java#pendingEmbeddingsNeverStopChatAndThePassCompletes",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/OfflineCoordinatorFailureCleanupTest.java#bodyFailureIsPrimaryAndReleasesTheGuardForTheNextPass",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/OfflineCoordinatorFailureCleanupTest.java#fatalBodyFailureIsPreservedAndReleasesTheGuard",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/OfflineCoordinatorOwnershipTest.java#queuedCancellationRunsNoBodyAndReleasesOwnershipExactlyOnce",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/OfflineCoordinatorOwnershipTest.java#runningCancellationRetainsStageGuardAndAdmissionThroughBlockedCleanup",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/OfflineCoordinatorOwnershipTest.java#admittedContextWithWorkIdFlowsToEveryProcedurePort",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/OfflineCoordinatorOwnershipTest.java#vduModeFailureIsVisibleAfterOwnershipRelease",
        "modules/ui/src/test/java/io/justsearch/ui/api/OfflineProcessingOperationLifecycleIntegrationTest.java#completedOutcomePublishesOnlyAfterCleanupReleasesAdmission",
        "modules/ui/src/test/java/io/justsearch/ui/api/OfflineProcessingOperationLifecycleIntegrationTest.java#acknowledgedFailureAndBlockedRemainderProduceRecordedIncompleteOutcomeAfterCleanup",
        "modules/ui/src/test/java/io/justsearch/ui/api/OfflineProcessingOperationLifecycleIntegrationTest.java#cancellationDuringCleanupCannotPublishOrDetachEarly",
        "modules/ui/src/test/java/io/justsearch/ui/api/OfflineProcessingOperationLifecycleIntegrationTest.java#postselectionModeEntryFailureRecordsCapturedRemainderAndPreservesCause",
        "modules/ui/src/test/java/io/justsearch/ui/api/VduProcedureExecutorLifetimeTest.java#aRealRegistryTimeoutRefusesCloseAndRetainsTheOwnerUntilRetry",
        "modules/ui/src/test/java/io/justsearch/ui/api/VduProcedureExecutorLifetimeTest.java#stopWaitsForTheActualProcedureAndRetainsItsAccountingUntilExit"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduConcurrentTriggerTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/vdu/OfflineCoordinatorOwnershipTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "OfflineCoordinator lost its OnlineAiLifecycleControl and RuntimeReconciler parameters. VduConcurrentTriggerTest (both checks) and OfflineCoordinatorOwnershipTest executorRefusalRetainsCleanupFailureAndReleasesOnce build it with the five-argument constructor and drop the mocks that only fed those parameters (an inference mock answering isOnline true). Inputs, fault injection and every assertion are unchanged."
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeReconcilerGuardrailsTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "The OnlineAiService switch-caller rule: no app-services class other than RuntimeReconciler and the AiInstallService LEGACY-FALLBACK calls switchToOnlineMode or switchToIndexingMode.",
      "whySurvives": "The predicate and its allowlist are unchanged; the class javadoc, the method javadoc and the allowlist comment now say the fallback is live when no chat controller is wired (it took the procedureRequireEngine path before; it takes a running hold now)."
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
  "verdict": "accept",
  "digest": "sha256:bac5c001b2d42b36aac85edf1038509a8bdb02e1239aba0c27c67f8fb26daa7c",
  "note": "Reviewed changed hold/config fixtures, candidate-only API-absence reasons and the retained controller guardrail rule. Candidate unit/reconciler and adapted guardrail checks passed; superseded manual before-pass prose is not counted as current script evidence."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS4b-20261015",
  "verdict": "accept",
  "digest": "sha256:f5f1c3db249f3db08cae49ba69897809978eecc37be611ecd97b1d8924913f7c",
  "note": "All 24 current selected hold, session, install, VDU and reconciler candidate cases pass. The expanded shared profile fixture preserves these expectations, and the entries accurately give concrete new-API reasons for candidate-only cases; retained repaired evidence is unchanged."
}
```
