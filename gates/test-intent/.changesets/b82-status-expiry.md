---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b82-status-expiry
---

# Test-intent entries

The b82 round has six parts. S1 to S3 come from the b82 brief, and S4 to S6 from the coordinator's addition to it.

- **S1.** A confirmed recovery reset completes, and the status counter advances once.
- **S2.** A fresh read's 100 ms deadline ends only the wait for that response. A lane is stale only by its own age (more than 3 × its own sampling period; after L1, 3 × its effective cadence, which adds the recent age of the data it published, up to three periods) or by a failed observation. Observation times are real probe times.
- **S3.** A UI status read the server never answers is abandoned after one poll interval, and polling continues.
- **S4.** Headerless status bodies are no longer blocked forever once an SSE epoch arrives.
- **S5.** A restart signal also revokes the previous head's inference data.
- **S6.** Fresh reads and sampler passes attach no waiters to a held lane's future.

The witness check in StatusPublicationCompletenessTest answers the coordinator's request to strengthen that test.

The repaired checks' fail-before (`--before 98cad099f`) was produced in a throwaway copy of this candidate (detached 98cad099f plus the candidate's changed files). Its ui-web/node_modules was a private install, not a junction.

The changed preservation tests keep every assertion. Only their way of making a held lane old changes. They used to wait 1.2 s, which relied on the old rule that an in-flight refresh older than 1 s was stale. They now advance the handler clock beyond the longest limit an idle lane can have and run one sampler pass, because S2 replaces that rule. Since L1 that limit is 3 × (period + the three-period allowance), 120 s, because a lane's earlier slow or cached probes can earn it part of the allowance.

pollingLifecycle's status variants used to hold a status read pending for 30 s. They now hold it for one millisecond less than the poll interval, because S3 abandons a read that is still unanswered after one interval. The inference variants are unchanged.

The L1 round (the orchestrator's decisions after the live window) adds the effective cadence. A measured live window showed that no lane's probe takes a period: the GPU reading is old when it is published because of the GPU sampler's tick and the projection's cache. So, per Option A, the allowance is the age of the published data, from the source reading's own timestamp to publish. The repaired checks were also shown to fail by assertion in throwaway worktrees (detached 98cad099f plus candidate files):

- StatusProbeCadenceTest, on the candidate before L1: age 6050 ms against the 6000 ms limit.
- StatusGpuReadingAgeTest and StatusGpuStaleSourceTest, on the candidate with the first L1 rule (probe duration only), both by assertion. The first saw a 6100 ms reading reported stale. The second saw a 7000 ms reading reported current.

Since L1, the S2 check in StatusReadDeadlineHttpTest judges each held lane against that lane's own limit (three effective cadences). It asserts that the limit lies between three periods and the cap. Before L1 it used three bare periods.

The same check also caught a publication race, in about 1 of 4 runs: the sampler and an HTTP read built responses at the same time, and one built from older observations published after a newer one. That made the at-rest value alternate (ENCRYPTED, NOT_ENCRYPTED, ENCRYPTED) and advanced the counter three times for one change. Building and publishing a response now happen one at a time. The build only reads observations already published in memory, so no producer, owner lock or I/O is held while it runs. Before the fix, 5 of 16 repeated runs failed this way; after it, 0 of 16 did.

StatusGpuStaleSourceTest passes on 98cad099f, whose limit is three bare periods. That is why it is a preservation check here.

The allowance counts only data ages within three periods. An older value is stale. This keeps the existing guarantee that a Worker contact arriving more than three periods late is stale (IndexReadinessPublicationTest, unchanged).

Decision records were searched for a conflict with these behaviours (status polling, single flight, stale period, fresh read). None was found.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/SettingsResetCoordinatorTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "A confirmed reset that recovers from quarantined settings (so there are no prior settings) completes normally: the reset reports completed, the operation is not marked uncertain, and the status change counter advances exactly once. No previous chat-enabled value is invented for the comparison.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S20",
          "quote": "The GPU, chat, indexing and lifecycle states the UI shows are the ones dev tools and evaluation scripts read.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "how status publication (the status change counter added for task item 5 / S20) behaves when settings change. The delegation is the owner's end-to-end takeover grant (repeated 2026-10-06T21:10Z: \"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"); the choice serves item 5's completion condition: \"Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.\" (task request).",
          "choice": "Orchestrator decision in the b82 brief, S1: \"a confirmed recovery reset completes normally and the status counter advances once. Do not invent a previous value: a recovery reset has no prior settings to compare.\"",
          "label": "agent choice",
          "provenance": "delegationQuote was 'Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.' at 'task t-20261005-b6d536 request (task item 5, quoted in the b82 builder brief)', which is item wording, not a delegation"
        }
      ],
      "evidence": "gates/test-intent/evidence/b82-status-expiry-repaired-java.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/settings/SettingsResetCoordinatorTest.java#confirmedRecoveryResetCompletesAndAdvancesTheStatusCounterOnce"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusReadDeadlineHttpTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "Over real HTTP, previously successful GPU and at-rest observations whose refreshes are held beyond 100 ms and beyond 1 s while repeated fresh reads arrive: each read answers within a bounded time; GPU and at-rest values do not alternate and keep their last real value; the status counter does not advance because of read deadlines; the held lanes become stale only once their own age exceeds 3 x their own period (one counter advance for that), and recovery is one more advance. Cached probe answers report the real probe time, not the time the cached value was returned. Timed-out fresh reads (batches of 20) and sampler passes alone leave at most one waiter on a held lane's future, and releasing the lane runs a bounded amount of projection work rather than replaying every earlier request.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.",
          "location": "task t-20261005-b6d536 request (task item 5 completion condition, quoted in the b82 builder brief)"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S20",
          "quote": "The GPU, chat, indexing and lifecycle states the UI shows are the ones dev tools and evaluation scripts read.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "how the status surface reports components that cannot answer in time (task item 5 / S20). The delegation is the owner's end-to-end takeover grant (repeated 2026-10-06T21:10Z: \"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"); the choice serves item 5's completion condition: \"Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.\" (task request).",
          "choice": "Orchestrator decision in the b82 brief, S2: \"the HTTP read deadline ends the wait for that response only; it does not invalidate an observation, change a reported value or advance the counter. A lane is reported stale (existing stale markers, unchanged wire format and reason codes) only from its own observation age against its own sampling period or a failed observation [...] values must not alternate because of request deadlines, and observation timestamps must reflect real probe times. The counter still advances for every real state change, including a lane becoming stale or recovering because of its own age or failure.\"",
          "label": "agent choice",
          "provenance": "delegationQuote was 'Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.' at 'task t-20261005-b6d536 request (task item 5)', which is item wording, not a delegation"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "how the status surface reports components that cannot answer in time (task item 5 / S20). The delegation is the owner's end-to-end takeover grant (repeated 2026-10-06T21:10Z: \"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"); the choice serves item 5's completion condition: \"Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.\" (task request).",
          "choice": "Orchestrator addition to the b82 brief, S6: \"Timed-out fresh reads accumulate completion callbacks behind a held lane and replay obsolete work on recovery [...] Proposed check: freshDeadlineDetachesOutstandingObservationWaiters (hold one lane, many batches of real fresh HTTP reads, bounded getNumberOfDependents() on the lane future; release: bounded projection work; repeat with periodic sampling alone).\"",
          "label": "agent choice",
          "provenance": "delegationQuote was 'Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.' at 'task t-20261005-b6d536 request (task item 5)', which is item wording, not a delegation"
        }
      ],
      "evidence": "gates/test-intent/evidence/b82-status-expiry-repaired-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusReadDeadlineHttpTest.java#heldSlowLanesKeepTheirLastRealValueAcrossReadDeadlinesAndAgeOnlyByTheirOwnPeriod",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusReadDeadlineHttpTest.java#cachedProbeAnswersKeepTheirRealProbeTime",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusReadDeadlineHttpTest.java#freshDeadlineDetachesOutstandingObservationWaiters",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusReadDeadlineHttpTest.java#samplerPassesLeaveNoWaitersOnAHeldLane"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/api/statusHungRead.test.ts",
        "modules/ui-web/src/api/statusBoundaryPoll.test.ts",
        "modules/ui-web/src/shell-v0/state/aiStateStore.restartInference.test.ts"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "statusHungRead: a status read that never settles is aborted after one poll interval and the next poll shows current state; a fresh read requested behind a hung read is not lost and never runs beside it (at most one status read in flight). statusBoundaryPoll: behind a proxy that strips status headers, an SSE status epoch (before or after the first body) does not block later headerless bodies; within two intervals of successful ordinary polls the current canonical state is displayed; the earlier cases (no older body shown as current after a new epoch) are kept unchanged. aiStateStore.restartInference: after a restart signal from a new head whose status and inference reads fail, the store no longer reports the previous head's online mode or chat capability, and the Brain panel offers no Shut Down AI.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S20",
          "quote": "The GPU, chat, indexing and lifecycle states the UI shows are the ones dev tools and evaluation scripts read.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "how the UI keeps its status display current for task item 5 (S20). The delegation is the owner's end-to-end takeover grant (repeated 2026-10-06T21:10Z: \"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"); the choice serves item 5's completion condition: \"Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.\" (task request).",
          "choice": "Orchestrator decision in the b82 brief, S3: \"Repair so a read that does not settle cannot stop the polling schedule or leave the display stuck, without raising any timeout as a fix for server slowness, without more than one read in flight per kind beyond what the existing rules allow, and without read storms (an abandoned read is aborted, not left running in parallel indefinitely). Decide the bound from the existing poll interval and justify.\"",
          "label": "agent choice",
          "provenance": "delegationQuote was 'Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.' at 'task t-20261005-b6d536 request (task item 5)', which is item wording, not a delegation"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "how the UI keeps its status display current for task item 5 (S20). The delegation is the owner's end-to-end takeover grant (repeated 2026-10-06T21:10Z: \"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"); the choice serves item 5's completion condition: \"Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.\" (task request).",
          "choice": "Orchestrator addition to the b82 brief, S4: \"Proposed check: headerlessStatusConvergesAfterSseEpoch in statusBoundaryPoll.test.ts (display headerless body, deliver SSE status signal, two intervals of successful ordinary polls: canonical status visible again). Keep the earlier guarantee: no older body shown as current after a new epoch.\"",
          "label": "agent choice",
          "provenance": "delegationQuote was 'Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.' at 'task t-20261005-b6d536 request (task item 5)', which is item wording, not a delegation"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "how the UI keeps its status display current for task item 5 (S20). The delegation is the owner's end-to-end takeover grant (repeated 2026-10-06T21:10Z: \"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"); the choice serves item 5's completion condition: \"Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.\" (task request).",
          "choice": "Orchestrator addition to the b82 brief, S5: \"Proposed check: mounted restart test with an online inference fixture from A, then B with status and inference reads failing: Brain not Online, chat capability unavailable.\"",
          "label": "agent choice",
          "provenance": "delegationQuote was 'Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.' at 'task t-20261005-b6d536 request (task item 5)', which is item wording, not a delegation"
        }
      ],
      "evidence": "gates/test-intent/evidence/b82-status-expiry-repaired-ui.json",
      "newChecks": [
        "modules/ui-web/src/api/statusHungRead.test.ts",
        "modules/ui-web/src/api/statusBoundaryPoll.test.ts#headerlessStatusConvergesAfterSseEpoch (SSE before the first body=false)",
        "modules/ui-web/src/api/statusBoundaryPoll.test.ts#headerlessStatusConvergesAfterSseEpoch (SSE before the first body=true)",
        "modules/ui-web/src/shell-v0/state/aiStateStore.restartInference.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusPublicationCompletenessTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation check of existing canonical status publication, not repaired behavior. An independent witness list of user-visible state fields (at-rest protection, GPU availability, conversation protection, worker RPC staleness, core index health and state, inference engine state, readiness components, lifecycle components, operating chat, indexing, GPU staleness and freshness sources) must each be treated as a state that signals a canonical change; the expectation no longer derives only from the production exclusion predicate, so a misclassified state fails. The two existing cases are unchanged. The new independent witness knownUserVisibleStatesAlwaysSignal passes on 98cad099f, where StatusPublicationBackstop.content/observation already exist, and on the candidate; the unchanged two cases remain covered by the kept-Java evidence.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S20",
          "quote": "The GPU, chat, indexing and lifecycle states the UI shows are the ones dev tools and evaluation scripts read.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "how status publication is checked for task item 5 (S20). The delegation is the owner's end-to-end takeover grant (repeated 2026-10-06T21:10Z: \"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"); the choice serves item 5's completion condition: \"Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.\" (task request).",
          "choice": "Orchestrator addition to the b82 brief: \"if cheap and sound, strengthen two tests the reviewer says assert less than their names: StatusPublicationCompletenessTest (~64) derives expected inclusion from the production exclusion predicate, so a misclassified state passes\"",
          "label": "agent choice",
          "provenance": "delegationQuote was 'Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.' at 'task t-20261005-b6d536 request (task item 5)', which is item wording, not a delegation"
        }
      ],
      "evidence": "gates/test-intent/evidence/b82-status-expiry-publication-preservation.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusPublicationCompletenessTest.java#knownUserVisibleStatesAlwaysSignal"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusLaneAging.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus3Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus4Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus5Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java"
      ],
      "class": "New",
      "behaviour": "The earlier rounds' isolation, hung-worker, blocked-disk, sampler-failure and additional-producer cases keep every assertion: a hung or failing producer is marked stale with exactly that part affected and the rest current, responses stay bounded, and the mark clears on recovery. Under S2 a held lane becomes stale by its own age, not by a refresh being in flight for 1 s, so instead of sleeping 1.2 s the cases now advance the handler clock beyond 3 sampler periods (StatusLaneAging) and run one bounded sampler pass before asserting staleness; StatusNonBlockingHttpTest's blocked-dependency case advances its clock the same way. Build cleanup removes redundant qualification in StatusIsolationHttpTest; removes trailing blank lines in VerifierStatus2Test, VerifierStatus3Test, VerifierStatus4Test, VerifierStatusIsolationTest; documents the baseline missing-header catches and removes trailing blank lines in VerifierStatus5Test, retaining original inputs, resource lifetimes and every existing assertion.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S20",
          "quote": "The GPU, chat, indexing and lifecycle states the UI shows are the ones dev tools and evaluation scripts read.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "how the status surface reports components that cannot answer in time (task item 5 / S20). The delegation is the owner's end-to-end takeover grant (repeated 2026-10-06T21:10Z: \"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"); the choice serves item 5's completion condition: \"Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.\" (task request).",
          "choice": "Orchestrator decision in the b82 brief, S2: \"A lane is reported stale (existing stale markers, unchanged wire format and reason codes) only from its own observation age against its own sampling period or a failed observation, so a hung or failing producer is still marked stale, with exactly that part affected and the rest current, and clears on recovery, as verified in earlier rounds (OWN7-HUNG-WORKER, isolation cases).\"",
          "label": "agent choice",
          "provenance": "delegationQuote was 'Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.' at 'task t-20261005-b6d536 request (task item 5)', which is item wording, not a delegation"
        }
      ],
      "evidence": "gates/test-intent/evidence/b6d536-build-green-candidate-b3b4c08bac.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatusIsolationTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus2Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus3Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus4Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/VerifierStatus5Test.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusIsolationHttpTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusNonBlockingHttpTest.java"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/utils/pollingLifecycle.test.ts"
      ],
      "class": "New",
      "behaviour": "Slow status and inference polls stay single-flight and resume after completion; obsolete completions after resubscribe or rebind are ignored; one queued post-action status read resolves refreshes only after it publishes. For the status variants the slow read is now held for one millisecond less than the poll interval instead of 30 s, because a status read unanswered for a whole interval is abandoned (S3); the single-flight, ordering and publication assertions are unchanged, and the inference variants keep the 30 s hold.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "how the UI keeps its status display current for task item 5 (S20). The delegation is the owner's end-to-end takeover grant (repeated 2026-10-06T21:10Z: \"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"); the choice serves item 5's completion condition: \"Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.\" (task request).",
          "choice": "Orchestrator decision in the b82 brief, S3: \"Repair so a read that does not settle cannot stop the polling schedule or leave the display stuck [...] without more than one read in flight per kind beyond what the existing rules allow, and without read storms (an abandoned read is aborted, not left running in parallel indefinitely). Decide the bound from the existing poll interval and justify.\"",
          "label": "agent choice",
          "provenance": "delegationQuote was 'Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.' at 'task t-20261005-b6d536 request (task item 5)', which is item wording, not a delegation"
        }
      ],
      "evidence": "gates/test-intent/evidence/b82-status-expiry-kept-ui.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/utils/pollingLifecycle.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/api/statusBoundary.test.ts"
      ],
      "class": "New",
      "behaviour": "Unchanged in this round. It is listed because its earlier entry (b67, entry 1) shares an evidence file with statusBoundaryPoll.test.ts, which S4 extends. Its cases still pass with the S4 change. These include: missing metadata cannot certify an older response as current after a restart signal; a retired head's response cannot replace a snapshot after a refused new-head read; foreign publications on read-only responses refresh at most once.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "how the UI keeps its status display current for task item 5 (S20). The delegation is the owner's end-to-end takeover grant (repeated 2026-10-06T21:10Z: \"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"); the choice serves item 5's completion condition: \"Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.\" (task request).",
          "choice": "Orchestrator addition to the b82 brief, S4: \"Keep the earlier guarantee: no older body shown as current after a new epoch.\"",
          "label": "agent choice",
          "provenance": "delegationQuote was 'Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.' at 'task t-20261005-b6d536 request (task item 5)', which is item wording, not a delegation"
        }
      ],
      "evidence": "gates/test-intent/evidence/b82-status-expiry-kept-ui.json",
      "newChecks": [
        "modules/ui-web/src/api/statusBoundary.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusProbeCadenceTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusGpuReadingAgeTest.java"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "A status lane that keeps producing on its cadence does not read stale because its data is about one period old when it is published. Its age is measured against 3 x its effective cadence: its period plus the largest recent age of the data it published, from the source reading's own timestamp (or, without one, from the probe's start) to publish. On a controlled clock with a lane whose probe takes 2.1 s, sampled every 4 s on the busy 2 s period, the lane stays current between readings and while each next probe is in flight. On the real status path under indexing, a GPU reading that reaches status through the three 2 s delays measured live (the GPU sampler's tick, the projection's cache window, one status period), and so is 6.1 s old, leaves operating.gpu current.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S20",
          "quote": "The GPU, chat, indexing and lifecycle states the UI shows are the ones dev tools and evaluation scripts read.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "how the status surface reports components that cannot answer in time (task item 5 / S20). The delegation is the owner's end-to-end takeover grant (repeated 2026-10-06T21:10Z: \"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"); the choice serves item 5's completion condition: \"Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.\" (task request).",
          "choice": "Orchestrator decision on L1 (under the owner's delegation): \"a lane must not read stale because its own probe is slow while it is still producing samples on its cadence [...] A lane's staleness limit is 3 x its effective cadence, where the effective cadence is its configured period plus its recent observed probe duration (bounded; use the larger of the last few, capped so that a hung probe still goes stale [...]). A lane that stops producing samples, fails, or has no sample still goes stale exactly as now.\" Then Orchestrator decision on L1, Option A (under the owner's delegation): \"A lane's effective cadence counts the age of the data it publishes, measured from the source reading's own timestamp to publish, so the gpu-memory lane's allowance comes from the GPU reading's age at publish (sampler tick plus projection cache) rather than from the near-zero read time; apply this generally (for lanes whose source has no timestamp, probe duration as now), not as a gpu-memory special case. Keep the cap: \"capped\" means excluded [...]; a probe or a data age beyond 3 periods is a hang or stale source and grants no allowance, and it still goes stale within the cap.\"",
          "label": "agent choice",
          "provenance": "delegationQuote was 'Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.' at 'task t-20261005-b6d536 request (task item 5)', which is item wording, not a delegation"
        }
      ],
      "evidence": "gates/test-intent/evidence/b82-status-expiry-repaired-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusProbeCadenceTest.java#aLaneWhoseProbeTakesAboutOnePeriodStaysFreshWhileItKeepsSamplingOnItsCadence",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusGpuReadingAgeTest.java#aPublishedGpuReadingAgedByTheThreeStackedTwoSecondDelaysIsNotStale"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusProbeHangTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusGpuStaleSourceTest.java"
      ],
      "class": "New",
      "behaviour": "Counting a lane's data age in its cadence does not hide a lane that stops producing or a stale source. A hung probe still goes stale: after three periods for a lane whose probes were fast, after three periods for a lane whose probes took longer than the allowance (three periods; such probes are hung, not slow, and earn no cadence), and within the capped cadence 3 x (period + 3 periods) for a lane whose probes were just inside it. On the real status path under indexing, a GPU reading already older than the allowance when it is published (7 s on the busy 2 s period) makes operating.gpu stale, even after a slow read that earns the lane an allowance.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-b6d536",
          "scenario": "S20",
          "quote": "The GPU, chat, indexing and lifecycle states the UI shows are the ones dev tools and evaluation scripts read.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "I want you to autonomous takeover your work end to end from here. I'm going to sleep now, so you need to think for yourself",
          "delegationLocation": "task t-20261005-b6d536 user turn 2026-10-05T23:01Z",
          "delegationScope": "how the status surface reports components that cannot answer in time (task item 5 / S20). The delegation is the owner's end-to-end takeover grant (repeated 2026-10-06T21:10Z: \"proceed autonomously end to end accordingly. prefer codex subagents. do not require owner input\"); the choice serves item 5's completion condition: \"Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.\" (task request).",
          "choice": "Orchestrator decision on L1 (under the owner's delegation): \"a lane must not read stale because its own probe is slow while it is still producing samples on its cadence [...] A lane's staleness limit is 3 x its effective cadence, where the effective cadence is its configured period plus its recent observed probe duration (bounded; use the larger of the last few, capped so that a hung probe still goes stale [...]). A lane that stops producing samples, fails, or has no sample still goes stale exactly as now.\" Then Orchestrator decision on L1, Option A (under the owner's delegation): \"A lane's effective cadence counts the age of the data it publishes, measured from the source reading's own timestamp to publish, so the gpu-memory lane's allowance comes from the GPU reading's age at publish (sampler tick plus projection cache) rather than from the near-zero read time; apply this generally (for lanes whose source has no timestamp, probe duration as now), not as a gpu-memory special case. Keep the cap: \"capped\" means excluded [...]; a probe or a data age beyond 3 periods is a hang or stale source and grants no allowance, and it still goes stale within the cap.\"",
          "label": "agent choice",
          "provenance": "delegationQuote was 'Done when the E2/E3 scripted-agent timeouts are gone on a quiet machine.' at 'task t-20261005-b6d536 request (task item 5)', which is item wording, not a delegation"
        }
      ],
      "evidence": "gates/test-intent/evidence/b82-status-expiry-kept-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusProbeHangTest.java#aLaneWhoseProbeHangsAfterSlowProbesStillGoesStaleWithinTheCap",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusProbeHangTest.java#probesLongerThanTheAllowanceEarnNoCadenceSoAHungProbeIsStaleAfterThreePeriods",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusProbeHangTest.java#aLaneWhoseFastProbeHangsGoesStaleAfterThreeOfItsPeriods",
        "modules/ui/src/test/java/io/justsearch/ui/api/StatusGpuStaleSourceTest.java#aSourceReadingOlderThanTheCapIsStale"
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
  "session": "VC-status9-20261014",
  "verdict": "accept",
  "digest": "sha256:07641993d43330376c73a720fd4edd65e6ea7a3fcc91a699aea0348419d94bd3",
  "note": "VC-status9 (independent Claude verifier) on b6d536/status-timeout-2 vs 98cad099f: accepted ce7685a1c (b82: settings recovery null priorSettings fixed, 8 failures gone; data-age staleness, live 20-min E2 scripted-agent load 0 at-rest/GPU flips vs 92/106 on the base, 10871 canonical and 1186 fresh reads with 0 timeouts; ordered publication under an injected barrier; hung UI status read no longer stops polling; nothing blocks under the publication lock) and the records commits 19f230c5f, 41554dac9 and 0f9e824b2 (docs state staleAfterMs = 3 x sampler period and lanes stale after 3 of their own cadences with consequences a and b; Adaptations name the right decisions; compat patches add only PROBE_ALLOWANCE_PERIODS; before-failures rerun at f6d579a86, ff0c00f5e and 93479e944 match the records; behaviour texts corrected to the current code). Decided trade-offs: a hung lane is stale only after 3 of its cadences (30-120 s idle); a fresh read missing its deadline shows the last published value as current; a server always slower than 10 s shows empty status. Unverified: none beyond the named flaky StatusIsolationHttpTest under full CPU load (6/30 on both). Digest bound with --base 98cad099f, the lane's base."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS1-20261015",
  "verdict": "accept",
  "digest": "sha256:32acc35f261bba7aa7ca40430ad126fef9817748a541c04bf1cd671616fecdda",
  "note": "Re-reviewed producer staleness, status publication and the preserved hang controls; candidate and declared before checks reproduce the recorded outcomes."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "VS5a-20261016",
  "verdict": "reject",
  "digest": "sha256:7840a1a483a9379ee78b4cf080cf7d1ae07ee589e9bc3942eca4ed9420a6f2ad",
  "note": "Reject: entry 4 attaches b82-status-expiry-kept-java.json with failBefore null and gives no reason for candidate-only execution. Its StatusPublicationBackstop content/observation seam already exists on 98cad099f, so the strengthening description does not explain omitting before execution."
}
```
