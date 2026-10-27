---
schema: test-intent.v1
task: t-20261018-6108b0
author-role: builder
author-session: 01a17afc-67b9-7770-8fbd-d05b25ad29a2
---

# Test-intent entries

B2 compares the candidate with origin/main. The local deadline seam keeps the public
constructor on System.nanoTime. The classifications below separate retained-oracle
clock instrumentation from new observations, including the additional assertion in
an existing method. No production repair is claimed by the New entries.

The owner delegation quotes and their locations are copied verbatim from
testspeed-device-backfill.md. The choices below apply that delegation to B2;
the detailed T1-T6 scenarios are agent choices, not owner-authored scenarios.
The settled design and supplemental independent verification summaries are in
tmp/accept-evidence. They are not substitutes for the generated execution evidence.

The evidence uses the class-only Java check ID because the existing WARN method
has a DisplayName and parameterized boundary cases have invocation display names.
It must execute all eleven SlowBatch cases without skips. The three New entries
map that class evidence to these specific checks:

- budgetTripWithNoProgress_stillLogsTheWarn: "the budget-trip WARN still fires when a slow cycle advances nothing".
- cycleDeadlineBeforeAtAndAfter_completedEncodeSpendsProbeBudget: all three 5 s boundary invocations.
- embedShareBeforeAtAndAfter_reservesTimeForPendingNer: all three 3 s boundary invocations.

BackfillSchedulerPersistenceTest is an isolated new test file exempt from this gate;
this changeset does not claim to accept its persistence proof or broader B2 acceptance.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "Only retained-oracle portions: add a fixture-local monotonic supplier constructor and charge modeled nanoseconds after completed encodes. budgetTripWithNoProgress_stillLogsTheWarn replaces proportional sleeping with the same 1.6 s per encode unit, keeping the blank-content write-but-never-advance inputs, null vectors, no-progress population, cycle watchdog and ZERO stage advancement WARN expectation. Its new explicit modeled-budget assertion is New below. The original real slowBatchOverrunningTheBudget_yieldsWithinBudgetPlusOneEncodeUnit anchor still uses public default-clock construction, proportional real delays, the original budget lower/upper bounds and document observations. All three synchronous ingest/deletion/resume cases retain their inputs, event positions, completed/skipped-document and next-cycle oracles. Default nerPending=false keeps NER terminal in every existing case; default modeledNanosPerEncode=0 adds no modeled duration to the public-clock cases. No original execution, tag or condition is removed. The two added boundary methods and their NER-pending fixture branch are New below."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerTightLoopTest.java"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "Supply fixture-local monotonic nanoseconds and charge each completed combined write 100 ms; time reads never advance the clock. advancingButNeverDrainingPopulation_budgetReturnsControl measures the same budget-spent lower bound of 4000 ms in that domain, retaining its never-draining progressing population, successful SPLADE results, more-than-ten-writes expectation and real 20 s preemptive watchdog. The independent no-progress immediate-stop and pending-ingest at-most-one-combined-batch cases retain their original inputs, observations, bounds and expected outcomes under the same supplier. Comments describe the monotonic observation domain. No expectation, execution, tag or condition is added or removed."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java"
      ],
      "class": "New",
      "behaviour": "In budgetTripWithNoProgress_stillLogsTheWarn add an explicit assertion that completed encodes consumed at least 5 s of modeled work before the no-progress cycle returns. This is a stronger observation within the existing WARN case, separate from the retained WARN oracle and the clock adaptation. It rules out a premature return masquerading as budget exhaustion. No production defect is repaired.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover all accoridngly, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; B2 permits a scheduler-local deadline seam while preserving existing product behavior, public default construction and persistence semantics.",
          "choice": "T2/T3: explicitly witness budget consumption from completed encode events in the existing activity-only no-progress WARN case.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well then proceed with implementation. only use codex subagents",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; B2 permits a scheduler-local deadline seam while preserving existing product behavior, public default construction and persistence semantics.",
          "choice": "T2/T3: explicitly witness budget consumption from completed encode events in the existing activity-only no-progress WARN case.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/testspeed-b2-clock.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java"
      ],
      "class": "New",
      "behaviour": "Add cycleDeadlineBeforeAtAndAfter_completedEncodeSpendsProbeBudget at 4999999999, 5000000000 and 5000000001 ns per completed encode. Start the supplier at an unrelated negative epoch and reuse the scheduler for two cycles. Each cycle permits two encode units just before 5 s and one at or after 5 s; cumulative encode-call and encoded-document counts prove the probe spends the cycle budget and each cycle samples a fresh deadline. Per-cycle written-document counts prove completed units publish before return. Each progressing cycle must log INFO still advancing documents and must not log WARN ZERO stage advancement. The zero-delay fixture and unchanged real watchdog bound a failed implementation. No production defect is repaired.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover all accoridngly, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; B2 permits a scheduler-local deadline seam while preserving existing product behavior, public default construction and persistence semantics.",
          "choice": "T1-T3: add before/at/after cycle-boundary coverage with completed-work-driven time, an unrelated epoch, fresh cross-cycle deadlines, completed-unit publication and preserved progress logging.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well then proceed with implementation. only use codex subagents",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; B2 permits a scheduler-local deadline seam while preserving existing product behavior, public default construction and persistence semantics.",
          "choice": "T1-T3: add before/at/after cycle-boundary coverage with completed-work-driven time, an unrelated epoch, fresh cross-cycle deadlines, completed-unit publication and preserved progress logging.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/testspeed-b2-clock.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java"
      ],
      "repaired": false
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java"
      ],
      "class": "New",
      "behaviour": "Add embedShareBeforeAtAndAfter_reservesTimeForPendingNer at 2999999999, 3000000000 and 3000000001 ns for the first completed encode, with later encodes costing 1 ns. Introduce the NER-pending fixture branch and positively observe NER extraction, whose stub raises pending ingest. Assert two encode calls just before the 3 s soft share and one at or after it, exactly one NER extraction, and total modeled time below the 5 s cycle budget. Together these observations show the embed reservation leaves the later stage runnable and pending ingest ends subsequent tight-loop work independently of deadline exhaustion. No production defect is repaired.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover all accoridngly, consider that you will be the orchestrator for this task. firstly proceed autonomously with investigating and designing the fixes and improvements for the ideal state of that area",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; B2 permits a scheduler-local deadline seam while preserving existing product behavior, public default construction and persistence semantics.",
          "choice": "T2-T4: cover before/at/after the embed soft-share boundary with positively pending NER, prove NER still executes, and distinguish pending-ingest termination from spending the full cycle budget.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "well then proceed with implementation. only use codex subagents",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-26",
          "delegationScope": "Reduce waiting without reducing meaningful fault detection across the five testing outcomes; B2 permits a scheduler-local deadline seam while preserving existing product behavior, public default construction and persistence semantics.",
          "choice": "T2-T4: cover before/at/after the embed soft-share boundary with positively pending NER, prove NER still executes, and distinguish pending-ingest termination from spending the full cycle budget.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/testspeed-b2-clock.json",
      "newChecks": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BackfillSchedulerSlowBatchTest.java"
      ],
      "repaired": false
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "01a17b3c-b881-7571-9787-aaa4ceb74f93",
  "verdict": "accept",
  "digest": "sha256:856079c86d032c67c01a864c7f091d1bbd2741d725e5f9a38feb9a77ef575108"
}
```
