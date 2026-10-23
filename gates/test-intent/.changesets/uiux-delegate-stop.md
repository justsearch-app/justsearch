---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: builder
author-session: s2xi2v_y
---

# Test-intent entries

The repair cases fail by assertion before the change; the preservation cases pass before and after. The evidence runs the complete file and retains those outcomes. All tests are durable.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts",
        "modules/ui-web/src/api/streams.observeAccept.test.ts"
      ],
      "class": "New",
      "behaviour": "The live addressed Delegate turn converges on its durable Stop note, once, through delayed cancellation and record reads, remote Stop, held decisions and record fallback. Saved output, completed disposition and genuine failures keep their existing interpretations; focus stays where the reader put it. These are agent-adopted scenarios (delegated-choice), not owner-adopted scenarios. A cold observer negotiates the managed event stream and binds its first update to the discovered conversation and its freshly read canonical turn, so the same run is not presented under a placeholder conversation or a second turn.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Search v3 designs; the orchestrator adopted DS1–DS8 and DN1–DN2 in wp1.2c under this delegation, after a challenger pass. Agent-adopted, never owner-adopted.",
          "choice": "DS1 Stopping an identified Delegate run in Search v3 (Stop button or keyboard), early (the server has named the run, no tool step begun) or late (while a step runs), shows in the live transcript exactly one \"You stopped this run\" activity note, also when no answer was produced; the existing receipt wording is unchanged; no running indicator, spinner or \"working\" text remains. Bound: within 2 s after the Engine has recorded the cancellation, for a connected foreground window.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Search v3 designs; the orchestrator adopted DS1–DS8 and DN1–DN2 in wp1.2c under this delegation, after a challenger pass. Agent-adopted, never owner-adopted.",
          "choice": "DS2 The live and reloaded views of that turn agree in saved steps and output (or none), the stopped note and the actions offered; transient live details need not match.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Search v3 designs; the orchestrator adopted DS1–DS8 and DN1–DN2 in wp1.2c under this delegation, after a challenger pass. Agent-adopted, never owner-adopted.",
          "choice": "DS3 (race) With the Engine's cancellation response delayed so a first thread read lacks the Stop note, the same open turn acquires the note once the Engine records it, without reload or another user action.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Search v3 designs; the orchestrator adopted DS1–DS8 and DN1–DN2 in wp1.2c under this delegation, after a challenger pass. Agent-adopted, never owner-adopted.",
          "choice": "DS4 (late Stop, existing rule) When completion wins the race with Stop, the completed output and disposition are kept, the single durable Stop note is retained, and live and reload show the same interpretation.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Search v3 designs; the orchestrator adopted DS1–DS8 and DN1–DN2 in wp1.2c under this delegation, after a challenger pass. Agent-adopted, never owner-adopted.",
          "choice": "DS5 With two windows observing the same run, Stop in either window gives one stopped presentation in both, with no interim generic failure that stays; a different conversation's run is untouched.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Search v3 designs; the orchestrator adopted DS1–DS8 and DN1–DN2 in wp1.2c under this delegation, after a challenger pass. Agent-adopted, never owner-adopted.",
          "choice": "DS6 Stop on a run held at a budget, context or approval decision, and Stop repeated while cancellation is pending (also from another window): once cancelled, no held decision or active-work control remains, and neither live nor reload shows the Stop note twice.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Search v3 designs; the orchestrator adopted DS1–DS8 and DN1–DN2 in wp1.2c under this delegation, after a challenger pass. Agent-adopted, never owner-adopted.",
          "choice": "DS7 Dropping the event stream around a confirmed Stop: reconnect or record fallback restores the same single stopped presentation. Engine restart before the cancellation is saved does not invent a Stop; after it is saved, the Stop is kept. (No new restart-recovery feature.)",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Search v3 designs; the orchestrator adopted DS1–DS8 and DN1–DN2 in wp1.2c under this delegation, after a challenger pass. Agent-adopted, never owner-adopted.",
          "choice": "DS8 The newly confirmed stopped state is announced once through the existing announcement channel; refresh or replay does not announce it again, and focus does not move unasked.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Search v3 designs; the orchestrator adopted DS1–DS8 and DN1–DN2 in wp1.2c under this delegation, after a challenger pass. Agent-adopted, never owner-adopted.",
          "choice": "DN1 Preservation: Ask Stop, Structured Stop, Delegate completed output, genuine Delegate failure, sessions sidebar, context bar and J/K run navigation behave as before.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Search v3 designs; the orchestrator adopted DS1–DS8 and DN1–DN2 in wp1.2c under this delegation, after a challenger pass. Agent-adopted, never owner-adopted.",
          "choice": "DN2 Preservation: the old chat window's agent Halt still cancels, keeps partial output, allows later sends and refreshes its record, also while Search v3 observes the run (identical layout not required).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-delegate-stop.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts",
        "modules/ui-web/src/api/streams.observeAccept.test.ts"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentLoopServiceTest.java"
      ],
      "class": "New",
      "behaviour": "Concurrent and repeated cancellations of an approval-held Delegate run append exactly one durable Stop note per run, including after the store is reopened. Cancellation releases the pending approval and never executes the held operation. These scenarios are agent-adopted (delegated-choice), not owner-adopted.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you dont need to wait for my opinion regarding the designs, i agree with you on them",
          "delegationLocation": "task t-20261019-bd718f request",
          "delegationScope": "Search v3 designs; the orchestrator adopted DS1–DS8 and DN1–DN2 in wp1.2c under this delegation, after a challenger pass. Agent-adopted, never owner-adopted.",
          "choice": "DS6 Stop on a run held at a budget, context or approval decision, and Stop repeated while cancellation is pending (also from another window): once cancelled, no held decision or active-work control remains, and neither live nor reload shows the Stop note twice.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/uiux-delegate-stop-concurrent.json",
      "newChecks": [
        "modules/app-agent/src/test/java/io/justsearch/agent/AgentLoopServiceTest.java#concurrentCancellationAtApprovalRecordsOneStop"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "73blv3bo",
  "verdict": "accept",
  "digest": "sha256:e342dc503dc5145b1307c7bc1cd88de856ec741a975dcdaabf781b292a0bc929",
  "note": "Test-intent acceptance only: DS/DN sources reviewed; 14 checks pass, 11 fail by assertion before, 3 preservation checks pass before. Real-model system scenarios remain unverified pending the conflicting machine rule."
}
```
