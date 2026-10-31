---
schema: test-intent.v1
task: t-20261031-unsent-work
author-role: builder
author-session: builder-start-fresh-stage2
---

# Test-intent entries

Stage 2 implements the adopted meaning of New and unfinished saved outcomes.
Original owner-quote location: "task t-20261031-unsent-work, 2026-10-31, decision on New and the F2 test".
The source field adds the gate’s required `agreement` discriminator to that task reference.
No key, format or capacity changes. Start-screen New keeps its existing null-place retirement
limit. Results drafts remain proposals accessible through the existing recovery review controls.
The cleared raw composer slot and cleared per-tab pointer represent the fresh place; an empty
hero attachment entry is omitted. Browser traversal keeps its existing capacity/refusal checks.

Existing test changes:
- Handoffs DS1 explicit New: retired B becomes retained B, restored at its origin; no dispatch remains.
- Record late-delta test: the old live turn receives its own deltas/evidence; fresh-thread isolation remains.
- Record leaving-mid-answer test: stamped saved rows correlate to the dispatch; live text wins until
  its terminal, then the saved answer appears once. The fixture carries the real Engine turnId.
- Sessions leaving-streaming test: reopening remains streaming, never halted; New’s fresh place has no Stop.
- URL C10: failed retention readback names unconfirmed New, keeps current settings and recoverable
  owned B. The partial warning survives origin return and stale lifecycle writes. C2/C3/C4/C5/C6/C8
  assertions remain, including pointer/raw faults, manual recovery and traversal refusal.
- F2 model and Ask/Model Engine-loss projections: failed becomes unfinished, with no completed-empty answer.
- Delivery tests: `New` as the stream-loss trigger becomes `remove → mount → New`; the reload variant
  remains reload. This includes exact saved outcome, uncertain evidence variants, retained-address reads,
  saved errors/no-evidence/stopped, completion versus Stop, failed retirement ACK, finite budget/rearm,
  slow reads, timed-out reads, late navigation/deletion reads, and the Retry child. All safety assertions stay.

New and rewritten tests are durable; no test scaffolding is added to the repository.
The execution runner operates in an isolated shared candidate clone, with copied dependencies;
its before worktree belongs to that clone. Source and baseline dependencies/build output are separate.
Mutation checks in that clone exercised dropped ownership, abort-on-New, empty-hero capacity,
lifecycle serialization, retained-to-fresh leakage, dispatch correlation, false failure status,
missing Summarize handling, absent checking, and accidental unfinished-action authority.
The verifier must independently accept the final digest.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/AskUnfinishedReload.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.handoffs.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.record.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.sessions.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.urlConversation.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/Sv3NoEvidence.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "allright, i guess thats fine. i agree with your recommendations",
          "location": "task t-20261031-unsent-work agreement, 2026-10-31, decision on New and the F2 test"
        },
        {
          "kind": "owner-words",
          "quote": "proceed autonomously with the remaining work",
          "location": "task t-20261031-unsent-work request, 2026-10-31"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with the remaining work",
          "delegationLocation": "task t-20261031-unsent-work request, 2026-10-31",
          "delegationScope": "Finish stage 2: New starts a fresh conversation while work remains at its previous place; unanswered saved Ask, Model and Summarize turns remain unfinished and are checked without resending. Preserve browser traversal, deletion, storage keys, format and the two-draft capacity.",
          "choice": "Use the existing acknowledged recovery write for owned departures, omit the empty fresh hero snapshot from New and lifecycle attachments, retain the cleared raw slot and reload pointer as the fresh-place representation, preserve null-place New retirement, keep live Ask streams at their dispatch owner, and reuse the finite saved-record checker for unfinished snapshots. Update task-written delivery-test setup from New to actual stream loss, preserving its original safety oracles.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none; docs/decisions contains no conflicting New/unfinished contract. The owner-adopted stage-2 decision supersedes the previous retirement/failure expectations. C3/C4 refusal, C8 traversal, C5/C6 manual recovery and fresh-thread isolation remain guarded."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delivery.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "allright, i guess thats fine. i agree with your recommendations",
          "location": "task t-20261031-unsent-work agreement, 2026-10-31, decision on New and the F2 test"
        },
        {
          "kind": "owner-words",
          "quote": "proceed autonomously with the remaining work",
          "location": "task t-20261031-unsent-work request, 2026-10-31"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with the remaining work",
          "delegationLocation": "task t-20261031-unsent-work request, 2026-10-31",
          "delegationScope": "Finish stage 2: New starts a fresh conversation while work remains at its previous place; unanswered saved Ask, Model and Summarize turns remain unfinished and are checked without resending. Preserve browser traversal, deletion, storage keys, format and the two-draft capacity.",
          "choice": "Use the existing acknowledged recovery write for owned departures, omit the empty fresh hero snapshot from New and lifecycle attachments, retain the cleared raw slot and reload pointer as the fresh-place representation, preserve null-place New retirement, keep live Ask streams at their dispatch owner, and reuse the finite saved-record checker for unfinished snapshots. Update task-written delivery-test setup from New to actual stream loss, preserving its original safety oracles.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. These tests were added by stage 1 of this task. Per the orchestrator’s explicit stage-2 choice, actual unmount/reload replaces New as the loss trigger. Correlation, no resend, fresh-draft preservation, uncertainty, bounded checks, manual rearm, late responses, Stop races, failed ACK and Retry-child assertions are preserved."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.startFresh.test.ts"
      ],
      "class": "New",
      "behaviour": "New preserves owned drafts and live streams without filling the fresh composer; two proposals remain the capacity, reload restores owned settings only for review, delayed outcomes remain unfinished without resend or Stop, record checks have a finite budget and manual rearm, and unfinished exchanges cannot authorize Retry/branch/context writes. Results proposals remain accessible through existing explicit review. Pending context sends remain abandoned across New.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "allright, i guess thats fine. i agree with your recommendations",
          "location": "task t-20261031-unsent-work agreement, 2026-10-31, decision on New and the F2 test"
        },
        {
          "kind": "owner-words",
          "quote": "proceed autonomously with the remaining work",
          "location": "task t-20261031-unsent-work request, 2026-10-31"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed autonomously with the remaining work",
          "delegationLocation": "task t-20261031-unsent-work request, 2026-10-31",
          "delegationScope": "Finish stage 2: New starts a fresh conversation while work remains at its previous place; unanswered saved Ask, Model and Summarize turns remain unfinished and are checked without resending. Preserve browser traversal, deletion, storage keys, format and the two-draft capacity.",
          "choice": "Use the existing acknowledged recovery write for owned departures, omit the empty fresh hero snapshot from New and lifecycle attachments, retain the cleared raw slot and reload pointer as the fresh-place representation, preserve null-place New retirement, keep live Ask streams at their dispatch owner, and reuse the finite saved-record checker for unfinished snapshots. Update task-written delivery-test setup from New to actual stream loss, preserving its original safety oracles.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/start-fresh-stage2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.startFresh.test.ts"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records
