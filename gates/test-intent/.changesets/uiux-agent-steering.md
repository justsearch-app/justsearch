---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: agent-session-apvdaxpn
---

# uiux-agent-steering: steering, handoff and resume report and keep every direction

These entries cover every test file added or changed in 485fe86b8..HEAD plus the working tree for the agent steering package: WP6.1 (one leading system message per agent request; later system entries go out as labelled user turns) and WP6.2 (every direction applied or honestly reported not applied, kept through handoff, compaction, fork and resume), including the repair of the independent verifier's findings (directions left queued after an interrupted recovery or finalisation; a final outcome arriving after done; replayed old acknowledgements shown as "Steered"). Sources cite the agreed scenarios under the gate ids S8.n → ASn, N8.n → ANn, S6.n → GSn, N6.n → GNn; each quote is the scenario text verbatim.

Evidence: run-evidence.mjs with --before 485fe86b8, once for the Java checks (uiux-agent-steering-java.json, under the shared Gradle lock, ModelRequestFixtures.java passed as an overlay) and once for the vitest checks (uiux-agent-steering-ui.json). Entries are split by each check's recorded before-result: checks that failed by assertion on 485fe86b8 carry repaired: true; the preservation checks that pass on 485fe86b8 carry none.

Edits that serve the evidence without changing an expectation:
- The JUnit XML names each invocation of a parameterized test by its display name ("[1] …"), which run-evidence.mjs cannot address by method. The parameterized tests in AgentSteeringResumeTest, AgentModelMessagesTest and AgentDirectionRecordTest therefore name invocations "{displayName} [{index}] {arguments}".
- On 485fe86b8 two checks failed by exception, not assertion: AgentModelMessagesTest#everyCompletionPathSendsLaterSystemEntriesAsUserTurns (the strict-template model refused the request by throwing) now wraps the ordinary and retry calls in assertDoesNotThrow, and AgentSteeringResumeTest#resumeAfterHandoffKeepsTheDirectionAndAddsTheResumeWarning asserts that the first run reached the write's approval before reading its checkpoint.

The verifier-finding repairs were also run against the first candidate 985ade65d (not part of the gate evidence): AgentDirectionRecordTest#recoveryReportsADirectionLeftQueuedByAnyInterruption failed there for the stamped, DONE, CANCELLED and ERROR states ("expected: <not_applied> but was: <queued>") and passed for the ordinary first-start case; the three new controller tests for an acknowledgement without an id, the post-done outcome and the replayed old acknowledgement failed there by assertion.

Removed expectations (the Obsolete entries):
- AgentLoopServiceTest: agentSession_drainInterject_isExactlyOnce tested AgentSession.drainInterject/setInterject, which the change removes (one queued direction replaced by a queue, S8.1). steeringDirective_afterLastBoundary_drainedAtRunEnd expected a direction sent during the final answer to be acknowledged like an applied one; under S8.2 it never reached the model, and steeringDirective_afterLastBoundary_reportedNotApplied pins the opposite.
- AgentInteractionMapperTest and AgentRunDurabilityClosureTest listed directive_acknowledged among the kinds the thread record drops. Under S8.2 a direction's outcome must be visible after reload, and N8.3 allows restored threads to show direction outcomes, so it now projects (one entry per direction with its final outcome; an id-less historical acknowledgement still projects nothing).
- AgentSessionController.test.ts "onDirectiveAcknowledged appends a human-origin steer-directive entry" fed an id-less acknowledgement and expected an entry. Under N8.3 a historical acknowledgement alone is not shown as proof the direction reached the model, so the case now feeds an applied report, and "onDirectiveAcknowledged shows nothing for an acknowledgement without an id" pins the old input.

Coverage limits: no live Engine/Qwen check was run by the builder (S6.3's French correction and template acceptance remain the verifier's live checks). Recovery is checked at the store → reconciler → thread-record boundary with planted crash-equivalent states, not a timed Engine kill. The unlock-time re-run of the reconciler is checked against a run still live in the same process through the production loop and the real on-disk store (review 2), not through the encrypted-unlock HTTP trigger.

Review history: a second independent verifier failed 30d55b804 on two regressions, and the review-2 repair answers them. (1) Recovery, which the unlock listener re-runs while the process still runs agents, reported a still-queued direction of a live run not_applied/run_interrupted; the live loop then sent it and reported it applied, so one direction had two final outcomes (the same pass also stamped the live run interrupted, which was pre-existing). The running loop now owns its run in the store from before its first write to after its last, and recovery leaves an owned run alone, which also ends the false interrupted stamp on a live run. (2) The old window rendered one applied direction twice once the record carried it as well as the live run; the record now carries the directive id the live report has, and the merge shows each direction once by that id, with the furthest-known outcome. The review-2 checks are in the main evidence files (fail-before on 485fe86b8) and in uiux-agent-steering-java-review2.json / uiux-agent-steering-ui-review2.json, which run them with --before 44b5a6d43 (the candidate the verifier failed) and record each failing there by assertion for the reason under test. The changed expectations in AgentDirectionRecordTest#reloadedThreadShowsEachDirectionOnceWithItsFinalOutcome (the record's directiveId) and AgentSessionController.test.ts "a final outcome reported after done reaches the live display of both windows" (the live projection's directiveId) are part of repair (2).

Review history (round 3: lost-response false certainty): a third independent verifier failed 31053095f on S8.2 because a steer whose response was lost after the Engine had accepted it (HTTP 200, direction applied once) was reported as "It was not applied" in the old window, and beside "Steered" in Search v3 while live; it rejected the two controller checks that pinned that claim for a connection failure and for an HTTP status other than 404. The steer handler (AgentController.handleSteeringDirective) answers 404 only when AgentSessionRegistry.injectSteeringDirective finds no session or AgentSession.submitDirection refuses a closed one, before anything is queued; a 400 can also follow queueing (handleControl maps an exception from publishing the queued report to 400), and a proxy can answer 5xx after forwarding, so only 404 is a known refusal. The controller now keeps "the run already finished" for 404 only; for any other failure it says the direction could not be confirmed and lets the run's own reports decide: a report for that text first seen after the send began removes the note (or prevents it), so the windows show only the run's outcome; the text is not handed back to re-send, as before. The two rejected checks are rewritten to assert only the honest diagnosis, and three new checks cover acceptance before a lost response, a 5xx followed by the run's report, and an earlier same-text direction not settling the note; all five are in uiux-agent-steering-ui.json (fail-before on 485fe86b8) and in uiux-agent-steering-ui-review3.json, run with --before 92a21116f (the candidate this verifier failed), where each fails by assertion. A new preservation check pins the 404 wording in both windows.

No acceptance record is supplied by the builder.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentDirectionRecordTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Over the production run store, startup reconciler and public thread record (AgentRunQueryService.threadEvents): a reloaded thread shows one entry per direction, at its first place, with its final outcome (applied, not_applied with reason) and its directive id, and a late queued report never undoes a final one. A direction a dead process left queued gets exactly one not_applied outcome on recovery, however often recovery runs and whichever write the process died after: before recovery (run_interrupted), after an interrupted recovery had stamped interruptedAt (run_interrupted), after the run's DONE / CANCELLED / ERROR checkpoint but before its final outcome and termination reason (run_finished / run_stopped / run_failed). A direction that already has a final outcome gets no second one.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AS2",
          "quote": "A direction is shown and recorded as applied only when it went out in a model request. A direction that never did (run finished, stopped, failed, hit its step or budget limit, or the run was interrupted before the next step) is reported as not applied, in Search v3 and the old window, live and after reload or reconnect; a network or HTTP failure of the send is reported as such, not as \"the run already finished\".",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-agent-steering-java.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentDirectionRecordTest.java#reloadedThreadShowsEachDirectionOnceWithItsFinalOutcome",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentDirectionRecordTest.java#interruptedRunReportsItsUnappliedDirectionsAsNotApplied",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentDirectionRecordTest.java#recoveryReportsADirectionLeftQueuedByAnyInterruption"
      ]
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentDirectionRecordTest.java"
      ],
      "class": "New",
      "behaviour": "An acknowledgement written before directions had ids and outcomes shows nothing on the reloaded thread. A finished run whose directions all have outcomes and whose termination reason was written is left alone by recovery: no stamp, no extra outcome.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AN3",
          "quote": "Saved sessions, events and acknowledgements from before the change load, display, fork and resume as before, apart from S8.5's repaired request and S8.2's display corrections (restored threads may now show direction outcomes; a historical acknowledgement alone is not shown as proof the direction reached the model).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AS2",
          "quote": "A direction is shown and recorded as applied only when it went out in a model request. A direction that never did (run finished, stopped, failed, hit its step or budget limit, or the run was interrupted before the next step) is reported as not applied, in Search v3 and the old window, live and after reload or reconnect; a network or HTTP failure of the send is reported as such, not as \"the run already finished\".",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-agent-steering-java.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentDirectionRecordTest.java#legacyAcknowledgementWithoutOutcomeIsNotShownAsApplied",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentDirectionRecordTest.java#recoveryLeavesAFinishedRunWhoseOutcomesWereAllWrittenAlone"
      ]
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentModelMessagesTest.java",
        "modules/app-agent/src/test/java/io/justsearch/agent/ModelRequestFixtures.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Every agent request a strict chat template (ModelRequestFixtures: one system message, first; every assistant tool call answered by its tool results before anything else) accepts: after steering at separate steps behind real tool rounds the directions go out once, in order, as labelled user turns after the last tool result; saved READY_FOR_LLM / AFTER_TOOL_RESULT runs with trailing system notes resume by id and last, and a direction sent after the resume reaches the model; a resumed approval still needs a fresh decision (approve runs the write once, reject never); with a profile prompt the direction, the resume warning and the profile prompt all reach the model and a handoff after steering sends a valid list; ordinary completion, retry, budget-edge and step-ceiling finalisation all send later system entries as labelled user turns and leave the saved history unchanged; the token count after compaction measures the list that is sent.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "GS1",
          "quote": "Steering a Delegate run after a real model/tool round reaches the model: no model error, the next request contains the direction, and the run continues, with the shipped Qwen3.5 template.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "GS2",
          "quote": "A run interrupted while waiting for approval resumes (resume by id and resume last) and reaches further agent processing without a model error; the resume note reaches the model, and a write still runs only after a fresh approval (rejection prevents it).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "GS3",
          "quote": "Steering more than once at separate steps, and steering after a resume, work; each consumed direction reaches the model once, in order, and a later correction (e.g. \"answer in French instead\") visibly changes the following output (one live attempt; model behaviour, judged by the verifier).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "GS4",
          "quote": "Saved runs that already contain trailing system messages (consumed steering in READY_FOR_LLM / AFTER_TOOL_RESULT checkpoints) resume without a model error.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "GS5",
          "quote": "Every agent request that applies the template accepts the list after steering or resume: streaming and non-streaming completion, token counting (/apply-template), finalization and retry.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "GS6",
          "quote": "With an active agent profile (system prompt swapped at start), the direction, the resume warning and the profile prompt all still reach the model. A handoff after steering sends a valid list (one system message, first) and does not error.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AS5",
          "quote": "Resuming a run interrupted with outstanding tool calls (waiting for approval; partway through a batch of calls; READY_FOR_LLM after a rejection; AFTER_TOOL_RESULT with later unanswered calls) sends the model a valid conversation: every assistant tool call is answered, and an unexecuted call is described truthfully as not executed. Completed writes are not repeated; a pending write runs only after a fresh approval and never on rejection or a stale approval id. Both resume by id and resume last; snapshots written before the change resume too, and resuming does not rewrite the saved snapshot.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AS7",
          "quote": "A fork of a steered or resumed run keeps its directions and resume warning in the fork's model requests, with valid tool-call structure; prompt-token counting measures the same list that is sent.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-agent-steering-java.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentModelMessagesTest.java#directionsAfterToolRoundsGoOutAsUserTurnsAfterTheLastToolResult",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentModelMessagesTest.java#savedTrailingNotesResumeAndSteeringAfterResumeReachTheModel",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentModelMessagesTest.java#resumedApprovalStillRequiresAFreshDecision",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentModelMessagesTest.java#profileKeepsDirectionsAndResumeWarningAndHandoffSendsValidList",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentModelMessagesTest.java#everyCompletionPathSendsLaterSystemEntriesAsUserTurns",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentModelMessagesTest.java#tokenCountAfterCompactionMeasuresTheSentList"
      ]
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentModelMessagesTest.java",
        "modules/app-agent/src/test/java/io/justsearch/agent/ModelRequestFixtures.java"
      ],
      "class": "New",
      "behaviour": "An unsteered history (system, user, assistant tool calls, tool results) reaches the model exactly as saved: same messages, same tool-call ids, same order, the same objects passed through.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "GN1",
          "quote": "Unsteered agent runs and live approve/reject send the same messages to the model as today.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "GN5",
          "quote": "In a folded request, user, assistant and tool messages, tool-call ids and the relative order of all non-system messages are exactly as before folding.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AN1",
          "quote": "Unsteered runs, live approve/reject, tool-call ids and finalization: same requests to the model and same saved records as WP6.1. Single-direction runs: the same, apart from S8.2's acknowledgement meaning and timing and S8.3/S8.4's kept context.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-agent-steering-java.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentModelMessagesTest.java#unsteeredMessagesAndToolIdsReachTheModelUnchanged"
      ]
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentSteeringResumeTest.java",
        "modules/app-agent/src/test/java/io/justsearch/agent/ModelRequestFixtures.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Through the production agent loop, session store, query service and handoff against a scripted model that refuses what a strict template refuses: several directions before one step (including repeats) all reach the next request once, in order, and are reported applied; directions submitted from four threads while steps drain are each applied or reported not applied exactly once, per-submitter order kept. A direction is reported queued, then applied only once the model accepts a request carrying it; a refused request does not count (not_applied / run_failed); one arriving during the last call is not applied (run_finished, run_failed, step_limit); one drained but ended before the call by a context or budget gate stop or a cancel is not applied (run_stopped, budget_limit); after its final reports the run refuses new directions. A direction sent while a write awaits approval neither approves nor rejects it and is reported applied only after the decided action ran (not applied when the run is stopped). The first handoff (immediately and after exploration), a second handoff and a return keep the direction for the receiving agent; a resume after a handoff keeps it and adds the resume warning. Compaction keeps older directions and the resume warning and reports only what it dropped. Resuming with outstanding calls (WAITING_APPROVAL partway through a batch, READY_FOR_LLM after rejection, AFTER_TOOL_RESULT with later unanswered calls; by id and last) sends a valid conversation with every call answered and unexecuted ones described as not executed, repeats no completed write and leaves the saved snapshot unchanged; a pending write runs only after a fresh valid approval, never on rejection or a stale id; a snapshot written before the change resumes. A fork of a steered, resumed run keeps its directions and warning with valid calls, and counting measures the sent list.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AS1",
          "quote": "Several directions sent before one step (including in quick succession and while a step is consuming them) all reach the model, once each, in the order sent; none is lost or applied twice.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AS2",
          "quote": "A direction is shown and recorded as applied only when it went out in a model request. A direction that never did (run finished, stopped, failed, hit its step or budget limit, or the run was interrupted before the next step) is reported as not applied, in Search v3 and the old window, live and after reload or reconnect; a network or HTTP failure of the send is reported as such, not as \"the run already finished\".",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AS3",
          "quote": "A handoff after steering or after a resume keeps the user's directions and the resume warning in the receiving agent's requests, including after a second handoff (A→B→C), a return (A→B→A), and a resume after a handoff.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AS4",
          "quote": "Context compaction in a long run keeps the user's directions and the resume warning.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AS5",
          "quote": "Resuming a run interrupted with outstanding tool calls (waiting for approval; partway through a batch of calls; READY_FOR_LLM after a rejection; AFTER_TOOL_RESULT with later unanswered calls) sends the model a valid conversation: every assistant tool call is answered, and an unexecuted call is described truthfully as not executed. Completed writes are not repeated; a pending write runs only after a fresh approval and never on rejection or a stale approval id. Both resume by id and resume last; snapshots written before the change resume too, and resuming does not rewrite the saved snapshot.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AS6",
          "quote": "A direction sent while a write waits for approval neither approves nor rejects it, and the reported timing matches what actually ran.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AS7",
          "quote": "A fork of a steered or resumed run keeps its directions and resume warning in the fork's model requests, with valid tool-call structure; prompt-token counting measures the same list that is sent.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-agent-steering-java.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentSteeringResumeTest.java#severalDirectionsBeforeOneStepAllReachTheModelOnceInOrder",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentSteeringResumeTest.java#concurrentDirectionsDuringStepsAreNeitherLostNorDuplicated",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentSteeringResumeTest.java#directionIsReportedQueuedThenAppliedWhenTheModelAcceptsItsRequest",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentSteeringResumeTest.java#directionInARequestTheModelRefusesIsNotReportedApplied",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentSteeringResumeTest.java#directionArrivingDuringTheLastModelCallIsReportedNotApplied",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentSteeringResumeTest.java#directionDrainedButEndedBeforeTheModelCallIsReportedNotApplied",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentSteeringResumeTest.java#directionAfterTheRunStoppedTakingThemIsRefusedNotAcceptedAndDropped",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentSteeringResumeTest.java#directionWhileApprovalIsHeldNeitherDecidesItNorClaimsEarlierTiming",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentSteeringResumeTest.java#firstHandoffAfterSteeringKeepsTheDirectionForTheReceivingAgent",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentSteeringResumeTest.java#secondHandoffAndReturnKeepTheDirections",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentSteeringResumeTest.java#resumeAfterHandoffKeepsTheDirectionAndAddsTheResumeWarning",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentSteeringResumeTest.java#compactionKeepsOlderDirectionsAndTheResumeWarning",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentSteeringResumeTest.java#compactOlderTurnsReportsOnlyWhatItDropped",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentSteeringResumeTest.java#resumeWithOutstandingCallsSendsAValidConversationAndRepeatsNoWrite",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentSteeringResumeTest.java#pendingWriteRunsOnlyAfterAFreshValidApproval",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentSteeringResumeTest.java#snapshotWrittenBeforeTheChangeResumesWithItsStraySystemsAndDanglingCall",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentSteeringResumeTest.java#forkOfASteeredResumedRunKeepsDirectionsAndSendsValidCalls"
      ]
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentSteeringResumeTest.java",
        "modules/app-agent/src/test/java/io/justsearch/agent/ModelRequestFixtures.java"
      ],
      "class": "New",
      "behaviour": "An unsteered run with a live approval sends and saves the same messages as before (tool-call ids included); an unsteered handoff prunes and briefs exactly as before.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AN1",
          "quote": "Unsteered runs, live approve/reject, tool-call ids and finalization: same requests to the model and same saved records as WP6.1. Single-direction runs: the same, apart from S8.2's acknowledgement meaning and timing and S8.3/S8.4's kept context.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AN2",
          "quote": "Handoffs without steering prune and brief exactly as today.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-agent-steering-java.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentSteeringResumeTest.java#unsteeredApprovalRunSendsAndSavesTheSameMessages",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentSteeringResumeTest.java#unsteeredHandoffPrunesAndBriefsAsBefore"
      ]
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentLoopServiceTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "A direction submitted during the final answer of a single-call run never reaches the model, so the run reports it queued and then not_applied with reason run_finished, not as an applied direction.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AS2",
          "quote": "A direction is shown and recorded as applied only when it went out in a model request. A direction that never did (run finished, stopped, failed, hit its step or budget limit, or the run was interrupted before the next step) is reported as not applied, in Search v3 and the old window, live and after reload or reconnect; a network or HTTP failure of the send is reported as such, not as \"the run already finished\".",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-agent-steering-java.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentLoopServiceTest.java#steeringDirective_afterLastBoundary_reportedNotApplied"
      ]
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentLoopServiceTest.java"
      ],
      "class": "New",
      "behaviour": "A blank direction is accepted (not refused) but neither queued nor reported, and the model receives only the system prompt and the user turn, as in an unsteered run.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AN1",
          "quote": "Unsteered runs, live approve/reject, tool-call ids and finalization: same requests to the model and same saved records as WP6.1. Single-direction runs: the same, apart from S8.2's acknowledgement meaning and timing and S8.3/S8.4's kept context.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-agent-steering-java.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentLoopServiceTest.java#steeringDirective_blankIsIgnored"
      ]
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentLoopServiceTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "removal",
          "removed": [
            "modules/app-agent/src/main/java/io/justsearch/agent/AgentSession.java#drainInterject",
            "modules/app-agent/src/main/java/io/justsearch/agent/AgentSession.java#setInterject"
          ]
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AS1",
          "quote": "Several directions sent before one step (including in quick succession and while a step is consuming them) all reach the model, once each, in the order sent; none is lost or applied twice.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AS2",
          "quote": "A direction is shown and recorded as applied only when it went out in a model request. A direction that never did (run finished, stopped, failed, hit its step or budget limit, or the run was interrupted before the next step) is reported as not applied, in Search v3 and the old window, live and after reload or reconnect; a network or HTTP failure of the send is reported as such, not as \"the run already finished\".",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions/, the WP6.1/WP6.2 scenario record and its 2026-10-19 decisions): none"
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentInteractionMapperTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/AgentRunDurabilityClosureTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AS2",
          "quote": "A direction is shown and recorded as applied only when it went out in a model request. A direction that never did (run finished, stopped, failed, hit its step or budget limit, or the run was interrupted before the next step) is reported as not applied, in Search v3 and the old window, live and after reload or reconnect; a network or HTTP failure of the send is reported as such, not as \"the run already finished\".",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AN3",
          "quote": "Saved sessions, events and acknowledgements from before the change load, display, fork and resume as before, apart from S8.5's repaired request and S8.2's display corrections (restored threads may now show direction outcomes; a historical acknowledgement alone is not shown as proof the direction reached the model).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions/, the WP6.1/WP6.2 scenario record and its 2026-10-19 decisions): none"
    },
    {
      "items": [
        "modules/app-agent-api/src/test/java/io/justsearch/agent/api/AgentEventPayloadsCoverageTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/AgUiEventTranslatorConformanceTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/AgentEventPayloadConformanceTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/conversation/AgentEventSchemaConformanceTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "AgentEvent.DirectiveAcknowledged lost its one-argument constructor and gained directiveId, outcome and reason. Each fixture list builds the event through the four-argument constructor: the schema test with all nulls as before, the translator test as a queued report, and the two payload-key tests with every optional field set, the way their comments already require for conditional keys, so base() emits every key the event can carry. The assertions (every payload key declared in the schema and covered, the translator emits one frame per event) are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AS2",
          "quote": "A direction is shown and recorded as applied only when it went out in a model request. A direction that never did (run finished, stopped, failed, hit its step or budget limit, or the run was interrupted before the next step) is reported as not applied, in Search v3 and the old window, live and after reload or reconnect; a network or HTTP failure of the send is reported as such, not as \"the run already finished\".",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "In the controller both windows render from: a direction's reports update one entry per direction id (queued becomes applied in place; a late queued never undoes a final outcome; not_applied keeps its reason); a final outcome the run reports after done (queued, done, not_applied/run_finished on one SSE stream) reaches the live display, as 'Not applied' in Search v3's run feed and as the not_applied attributes (with the directive id) the old window's chip and merge read; an acknowledgement without an id, live or in a replayed exported transcript followed by an error, shows nothing (no 'Steered'), while a replayed queued/applied direction still reads 'Steered'; a failed steer says why (connection failure, HTTP status other than 404, or no session yet) and claims no more than the send establishes: a connection failure or a status other than 404 says the direction could not be confirmed, never that it was not applied or that the run finished; an unconfirmed send whose direction the run reports (queued while the request was in flight, or after a 5xx) shows only that report, once, in both windows (Search v3 'Steered', the old window's applied chip); the unconfirmed note stays when the only report is for an earlier direction with the same text.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AS2",
          "quote": "A direction is shown and recorded as applied only when it went out in a model request. A direction that never did (run finished, stopped, failed, hit its step or budget limit, or the run was interrupted before the next step) is reported as not applied, in Search v3 and the old window, live and after reload or reconnect; a network or HTTP failure of the send is reported as such, not as \"the run already finished\".",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AN3",
          "quote": "Saved sessions, events and acknowledgements from before the change load, display, fork and resume as before, apart from S8.5's repaired request and S8.2's display corrections (restored threads may now show direction outcomes; a historical acknowledgement alone is not shown as proof the direction reached the model).",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AN4",
          "quote": "Search v3 Delegate and the old window's agent view show steering as before for applied directions.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-agent-steering-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#onDirectiveAcknowledged shows nothing for an acknowledgement without an id",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#a queued direction becomes applied in place, one entry, keyed by its id",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#a not-applied direction keeps its reason, and a late queued report does not undo a final one",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#a final outcome reported after done reaches the live display of both windows",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#a replayed old acknowledgement is not shown as Steered; a replayed applied direction still is",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#steer names a connection failure as such, not as a finished run, without claiming non-application",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#steer names an HTTP error other than 404 by its status, without claiming non-application",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#a lost answer to a direction the run took shows only the run report, in both windows",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#a 5xx answer is settled by the run report that follows it, in both windows",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#an unconfirmed send is not settled by an earlier direction with the same text",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#steer before the run has a session says so instead of dropping the direction silently"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts"
      ],
      "class": "New",
      "behaviour": "After done, the stream still changes nothing else on the finished run: a stray text chunk or progress step after done adds no entry.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AN4",
          "quote": "Search v3 Delegate and the old window's agent view show steering as before for applied directions.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AS2",
          "quote": "A direction is shown and recorded as applied only when it went out in a model request. A direction that never did (run finished, stopped, failed, hit its step or budget limit, or the run was interrupted before the next step) is reported as not applied, in Search v3 and the old window, live and after reload or reconnect; a network or HTTP failure of the send is reported as such, not as \"the run already finished\".",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-agent-steering-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#after done, only direction reports still reach the display"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AN3",
          "quote": "Saved sessions, events and acknowledgements from before the change load, display, fork and resume as before, apart from S8.5's repaired request and S8.2's display corrections (restored threads may now show direction outcomes; a historical acknowledgement alone is not shown as proof the direction reached the model).",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "conflictSearch": "decision records searched for a conflict (docs/decisions/, the WP6.1/WP6.2 scenario record and its 2026-10-19 decisions): none"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-record.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run.test.ts",
        "modules/ui-web/src/shell-v0/views/unifiedThreadProjection.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Both windows label a direction by what happened to it, live and reloaded: an applied one reads as before ('Your direction …' chip in the old window, 'Steered' in Search v3), a queued one says it applies at the agent's next step, a not-applied one says why (run stopped, finished, interrupted when JustSearch restarted); the live projection carries the outcome and reason under the same keys as a reloaded direction.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AS2",
          "quote": "A direction is shown and recorded as applied only when it went out in a model request. A direction that never did (run finished, stopped, failed, hit its step or budget limit, or the run was interrupted before the next step) is reported as not applied, in Search v3 and the old window, live and after reload or reconnect; a network or HTTP failure of the send is reported as such, not as \"the run already finished\".",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AN4",
          "quote": "Search v3 Delegate and the old window's agent view show steering as before for applied directions.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-agent-steering-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.test.ts#keeps the applied chip unchanged and marks queued and not-applied directions",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-record.test.ts#becomes a note labelled by its recorded outcome",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-run.test.ts#labels applied, queued and not-applied directions differently",
        "modules/ui-web/src/shell-v0/views/unifiedThreadProjection.test.ts#WP6.2 — a live direction carries its outcome to the spine, like a reloaded one"
      ]
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentDirectionRecordTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Review 2. Through the production agent loop and the real on-disk run store: after the first model/tool round the user sends a direction and recovery runs twice (as the unlock listener re-runs it) while the run is live; recovery stamps nothing and gives the still-queued direction no outcome, the next model request carries the direction, and the run's record ends with exactly one final outcome for it (queued, applied), no interruptedAt, and nothing more from a recovery pass after the run finished. The reloaded thread carries each direction's directive id.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AS2",
          "quote": "A direction is shown and recorded as applied only when it went out in a model request. A direction that never did (run finished, stopped, failed, hit its step or budget limit, or the run was interrupted before the next step) is reported as not applied, in Search v3 and the old window, live and after reload or reconnect; a network or HTTP failure of the send is reported as such, not as \"the run already finished\".",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-agent-steering-java-review2.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentDirectionRecordTest.java#recoveryWhileTheRunIsLiveLeavesItsDirectionsToTheLoop",
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentDirectionRecordTest.java#reloadedThreadShowsEachDirectionOnceWithItsFinalOutcome"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.test.ts",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Review 2. The old window's merge of the live run with the record shows each direction once, matched by directive id, at the record's place: an applied direction both carry renders one 'Your direction …' chip; a record read while the direction was still queued shows the live run's final outcome and reason; a direction only the record has (an earlier run) or only the live run has (not yet recorded) stays; two directions with the same words stay two. Without the live run (restored conversation) the record alone shows each once. The live projection carries the directive id it is matched by.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AS2",
          "quote": "A direction is shown and recorded as applied only when it went out in a model request. A direction that never did (run finished, stopped, failed, hit its step or budget limit, or the run was interrupted before the next step) is reported as not applied, in Search v3 and the old window, live and after reload or reconnect; a network or HTTP failure of the send is reported as such, not as \"the run already finished\".",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AN4",
          "quote": "Search v3 Delegate and the old window's agent view show steering as before for applied directions.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-agent-steering-ui-review2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/UnifiedChatView.test.ts#shows each direction once when the live run and the record both carry it",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#a final outcome reported after done reaches the live display of both windows"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts"
      ],
      "class": "New",
      "behaviour": "A steer the server refuses with 404 (answered only when no live run took the direction) still reads 'Could not steer — the run already finished.' in both windows: a Search v3 'Progress' note and the old window's progress note, and no direction entry.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AS2",
          "quote": "A direction is shown and recorded as applied only when it went out in a model request. A direction that never did (run finished, stopped, failed, hit its step or budget limit, or the run was interrupted before the next step) is reported as not applied, in Search v3 and the old window, live and after reload or reconnect; a network or HTTP failure of the send is reported as such, not as \"the run already finished\".",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-agent-steering-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#a 404 refusal of a direction still says the run already finished, in both windows"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Review 3. A steer whose answer is lost after the run took it, or answered with a status other than 404 (5xx), is not reported as not applied: the note says the direction could not be confirmed (naming the connection failure or the HTTP status), and once the run reports the direction (queued during the request, or after the failure) only that report shows, once, in both windows: 'Steered' in Search v3 and the old window's applied chip. A report for an earlier direction with the same text does not settle it.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AS2",
          "quote": "A direction is shown and recorded as applied only when it went out in a model request. A direction that never did (run finished, stopped, failed, hit its step or budget limit, or the run was interrupted before the next step) is reported as not applied, in Search v3 and the old window, live and after reload or reconnect; a network or HTTP failure of the send is reported as such, not as \"the run already finished\".",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-bd718f",
          "scenario": "AN4",
          "quote": "Search v3 Delegate and the old window's agent view show steering as before for applied directions.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-agent-steering-ui-review3.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#steer names a connection failure as such, not as a finished run, without claiming non-application",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#steer names an HTTP error other than 404 by its status, without claiming non-application",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#a lost answer to a direction the run took shows only the run report, in both windows",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#a 5xx answer is settled by the run report that follows it, in both windows",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#an unconfirmed send is not settled by an earlier direction with the same text"
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
  "session": "agent-session-l3uugof9",
  "verdict": "accept",
  "digest": "sha256:b9bde6c8ebe9d1d0215a7ef92e63fb17107fdace4de73bca7bf15e108939c271",
  "note": "Reviewed sources and baseline tests before the diff; fresh execution evidence matches committed results. Test-intent acceptance only; the independent live-recovery regression is reported separately."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "agent-session-np0ocnyn",
  "verdict": "reject",
  "digest": "sha256:c69ca94d31270767c08b568499a9609c89e70b05eece9fd50c607a177014b759",
  "note": "Reject the New entry for AgentSessionController.test.ts: steer names a connection failure as such, not as a finished run, and steer names an HTTP error other than 404 by its status both require the unsupported final claim It was not applied. AS2 requires honest outcomes; a failed response does not establish non-application. The real API/browser loss-of-response probe accepts the direction with HTTP 200 and applies it once, while the UI emits that false claim. Missing coverage and intent warrant: acceptance before response loss, and an uncertain response outcome. The HTTP-500-after-acceptance variation was not exercised live. Earlier acceptance is superseded by this independently discovered counterexample; other evidence classifications still match."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "agent-session-mlc4telw",
  "verdict": "accept",
  "digest": "sha256:57828b344472a17b07b1fc2ec24755227f89bd0f321d73b8fcf23b837e06a22d",
  "note": "Sources and before-state expectations reviewed; fresh frontend evidence matches committed classifications. Prior backend evidence reused unchanged. Test-intent acceptance only; identical-text send reconciliation fails separately."
}
```
