---
schema: test-intent.v1
task: t-20261029-fs993c
author-role: builder
author-session: builder-fe-halt-confirmation-finish-20261030
---

# Test-intent entries

Base: f956fc3f9e. Candidate: bdb4c8ad4a plus the uncommitted confirmation-polling repair,
restored base test and the rejection assertion instrumentation covered here. The skeleton was
created with `node scripts/governance/gates/test-intent/cli.mjs --skeleton fe-halt-confirmation
--task t-20261029-fs993c --session builder-fe-halt-confirmation-20261030` before these entries.

The owner said "proceed accordingly" in reply to the two recommendation quotes reproduced in
the entries. Those quoted recommendations state the scope of that approval; they are not claimed
as separately owner-authored scenarios. Every precise recovery, deadline and instrumentation
choice is explicitly labelled agent choice under the earlier written delegation. That delegation
does not authorize retiring other existing obligations.

| File under modules/ui-web/src/shell-v0 | Classes | Warrant / retained obligations |
| --- | --- | --- |
| controllers/AgentSessionController.test.ts | New + Obsolete | Approved stopping-until-confirmed repair retires only immediate conclusion/notice timing; DELETE, partial text, immediate drain and one eventual toast stay. |
| views/search-v3/SearchV3View.agentRun.test.ts | Adaptation | Explicit shared-vocabulary terminal inputs; all receipt/count/outcome assertions stay. |
| views/search-v3/SearchV3View.ask.test.ts | New (preservation) | Visible-change limit and delegated preservation of indefinite Ask wait, late acceptance and one request. |
| views/search-v3/SearchV3View.delegateStop.test.ts | New + Adaptation | Approved Stop fix plus separately identified delegated recovery choices; all 13 original test bodies (16 expanded cases) stay verbatim, including late saved-DONE confirmation. |
| views/search-v3/sv3-ask.test.ts | New | Delegated HTTP-status/refusal versus transport-loss distinction for the shared vocabulary. |

Execution evidence was produced by the unchanged run-evidence.mjs in an isolated shared candidate
clone with copied dependencies. Its --before f956fc3f9e run creates its temporary checkout from
that shared clone, writing no worktree metadata into the source repository. All 29 repaired named
checks pass on the candidate and fail before by assertions. Twelve preservation/adaptation named
checks pass on the candidate. The same checks also pass in a direct shared f956fc3f9e clone with
only the five candidate test files overlaid and the standalone stopAttempt.ts module added solely
for the fake-controller import: no repaired production code is overlaid. That direct comparison
executes 295 cases: 266 pass, the same 29 repair checks fail by assertion, none skip. The two new
isolated test files are gate-exempt and are not changed by this changeset.

The complete full-suite, fixture and governance commands/results are in the builder handback
`tmp/fe-halt-confirmation/builder-report.md`; ordinary logs are supplementary and do not replace
the two script-produced evidence artifacts below. No acceptance record is authored here.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts"
      ],
      "class": "New",
      "behaviour": "After cancelSession dispatches DELETE, the identified run remains streaming and unconcluded until CANCELLED. Partial text is committed at that terminal and DELETE still targets the same session/method. Halt drains its outstanding authorization immediately, without a run-ended toast; CANCELLED emits the one eventual toast. The drain stub returns one only on its first drain so a second drain cannot invent another outstanding authorization.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed accordingly",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "owner-words",
          "quote": "Step 3 is a natural test of the idea. It applies the shared vocabulary to agent runs, which is exactly where the Stop bug sits. Fixing the bug becomes the first real proof of the approach",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "owner-words",
          "quote": "Nothing changes what you see in the app, except the Stop fix: a halted run would then show 'stopping' until the backend confirms.",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a new task register accordingly. then proceed autonomously with investigating and designing the work",
          "delegationLocation": "task t-20261029-fs993c request (earlier delegation)",
          "delegationScope": "Investigate and design the shared Stop vocabulary and agent-run Halt repair in step 3, under the subsequently approved recommendation. Preserve established obligations except the premature Halt conclusion explicitly changed by the owner.",
          "choice": "Represent the requested stopping-until-backend-confirmation behavior in controller state as well as the view: defer conclusion and the run-ended notice to the actual terminal, retaining immediate authorization cleanup, partial output and one eventual notice.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-halt-confirmation-repairs.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#cancelSession commits streaming text and sends DELETE for session",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#cancelSession drains the halted run"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed accordingly",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "owner-words",
          "quote": "Step 3 is a natural test of the idea. It applies the shared vocabulary to agent runs, which is exactly where the Stop bug sits. Fixing the bug becomes the first real proof of the approach",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "owner-words",
          "quote": "Nothing changes what you see in the app, except the Stop fix: a halted run would then show 'stopping' until the backend confirms.",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a new task register accordingly. then proceed autonomously with investigating and designing the work",
          "delegationLocation": "task t-20261029-fs993c request (earlier delegation)",
          "delegationScope": "Investigate and design the shared Stop vocabulary and agent-run Halt repair in step 3, under the subsequently approved recommendation. Preserve established obligations except the premature Halt conclusion explicitly changed by the owner.",
          "choice": "Retire only the two immediate end-of-run timing expectations: DELETE receipt alone no longer concludes streaming or emits the run-ended notice. Observe their former end-state assertions after an explicit CANCELLED instead. Keep DELETE address/method, partial text, immediate drain and exactly one eventual toast.",
          "label": "agent choice"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: none. Searched docs/decisions for Halt, Stop, stopping and cancellation; no decision requires DELETE receipt to conclude the run or announce it ended. The late saved-DONE polling obligation is unrelated and is retained verbatim.",
      "retiredExpectations": "AgentSessionController.test.ts: cancelSession commits streaming text and sends DELETE for session (immediate streaming=false); cancelSession drains the halted run (immediate run-ended toast). Neither test nor its eventual end-state oracle is removed."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.agentRun.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding",
        "instrumentation"
      ],
      "whatMoved": "The fake controller now supplies an in-memory StopAttempt and terminalOutcome. The answerless-terminal receipt case explicitly supplies cancelled; the existing completion-wins race supplies accepted and completed alongside its unchanged COMPLETED disposition/run identity. The original Halt click, two rendered calls, receipt text, halted outcome and broken=false assertions stay. Neither case asserts a pre-confirmation halted receipt: both still set runInFlight=false/isStreaming=false at the terminal. All other fake-controller and receipt checks remain.",
      "executionEvidence": "gates/test-intent/evidence/fe-halt-confirmation-preservation.json",
      "preservationChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.agentRun.test.ts#says NOTHING about the outcome when the halt reached no terminal at all",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.agentRun.test.ts#says stopped-by-you when the halt DID reach a terminal — the late-cancel race"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts"
      ],
      "class": "New",
      "behaviour": "A pending Ask Stop still says stopping after 15 seconds (no agent-Halt deadline). A late HTTP acceptance still owns that attempt, a second click dispatches no second Stop, the display remains stopping and CANCELLED ends halted.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed accordingly",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "owner-words",
          "quote": "Step 3 is a natural test of the idea. It applies the shared vocabulary to agent runs, which is exactly where the Stop bug sits. Fixing the bug becomes the first real proof of the approach",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "owner-words",
          "quote": "Nothing changes what you see in the app, except the Stop fix: a halted run would then show 'stopping' until the backend confirms.",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a new task register accordingly. then proceed autonomously with investigating and designing the work",
          "delegationLocation": "task t-20261029-fs993c request (earlier delegation)",
          "delegationScope": "Investigate and design the shared Stop vocabulary and agent-run Halt repair in step 3, under the subsequently approved recommendation. Preserve established obligations except the premature Halt conclusion explicitly changed by the owner.",
          "choice": "Preserve Ask presentation, indefinite waiting and single-request ownership while replacing its internal delivery vocabulary; pin the late-acceptance schedule without applying the agent Halt deadline to Ask.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-halt-confirmation-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.ask.test.ts#R1 Ask Stop waits beyond the Halt deadline and accepts a late success"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Keep all 13 base test bodies (16 expanded cases), including DS3 DS4 this window own Halt still waits beyond the clean-DONE grace, byte-for-byte. The harness adds reset fault controls for HTTP/network/unanswered requests, address observation, /resume SSE and optional identity withholding. Existing start calls still default to identity=true; existing DELETE defaults remain 200/ok=true. The address-aware thread stub serves the existing matching conversation and rejects mismatched addresses rather than returning another conversation data. Teardown resolves all pending DELETEs instead of only the last; output-reading instrumentation does not replace existing assertions. Existing saved/live reconciliation, completion disposition, one receipt/announcement, focus, reload and cross-window isolation assertions remain.",
      "executionEvidence": "gates/test-intent/evidence/fe-halt-confirmation-preservation.json",
      "preservationChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#DS3 DS4 this window own Halt still waits beyond the clean-DONE grace"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts"
      ],
      "class": "New",
      "behaviour": "S1: show stopping with no run receipt, keep arriving reasoning/output and remain unconfirmed after DELETE until CANCELLED. S11: close a held decision on Halt; later budget/context frames cannot reopen prompts or send automatic continuations. S12: one confirmed receipt and one announcement retain final tool count and evidence through a subsequent refresh.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed accordingly",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "owner-words",
          "quote": "Step 3 is a natural test of the idea. It applies the shared vocabulary to agent runs, which is exactly where the Stop bug sits. Fixing the bug becomes the first real proof of the approach",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "owner-words",
          "quote": "Nothing changes what you see in the app, except the Stop fix: a halted run would then show 'stopping' until the backend confirms.",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a new task register accordingly. then proceed autonomously with investigating and designing the work",
          "delegationLocation": "task t-20261029-fs993c request (earlier delegation)",
          "delegationScope": "Investigate and design the shared Stop vocabulary and agent-run Halt repair in step 3, under the subsequently approved recommendation. Preserve established obligations except the premature Halt conclusion explicitly changed by the owner.",
          "choice": "Apply the approved stopping state across rendering and decision lifecycle: keep observation/output until the backend terminal, suppress decisions after the reader asks to stop, and retain the established final evidence/count and single-notice guarantees.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-halt-confirmation-repairs.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#S1 stopping uses Ask Stop presentation and retains arriving output until the terminal",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#S11 stopping closes the held decision and suppresses later automatic budget continuation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#S12 one confirmed receipt and announcement retain the final tool count and evidence through refresh"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts"
      ],
      "class": "New",
      "behaviour": "S3 refused/network: deduplicate one pending DELETE, show the existing Ask unconfirmed wording on failed delivery, retain streaming/output, permit an explicit retry, and let a later natural completion remain complete. S3 S6 unanswered Stop: show stopping until its deadline, then unconfirmed with output and one new attempt. S4 DONE/READY_FOR_LLM after stream loss: use addressed lifecycle evidence for complete versus still streaming/unconfirmed/retryable, retaining a completed answer. R4 hanging discovery/record reads: abort each by its deadline, leave unconfirmed Stop available, invent no Stop note. R4 ERROR lifecycle: keep a failed terminal and one failed receipt across refresh without a Stop note.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed accordingly",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "owner-words",
          "quote": "Step 3 is a natural test of the idea. It applies the shared vocabulary to agent runs, which is exactly where the Stop bug sits. Fixing the bug becomes the first real proof of the approach",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "owner-words",
          "quote": "Nothing changes what you see in the app, except the Stop fix: a halted run would then show 'stopping' until the backend confirms.",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a new task register accordingly. then proceed autonomously with investigating and designing the work",
          "delegationLocation": "task t-20261029-fs993c request (earlier delegation)",
          "delegationScope": "Investigate and design the shared Stop vocabulary and agent-run Halt repair in step 3, under the subsequently approved recommendation. Preserve established obligations except the premature Halt conclusion explicitly changed by the owner.",
          "choice": "Distinguish failed/refused/unanswered delivery from terminal confirmation. Use the existing Ask failure wording, keep a live run observable and retryable, bound hanging reconciliation reads and use backend lifecycle truth after transport loss. These are agent choices for the approved repair, not separately owner-adopted scenarios.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-halt-confirmation-repairs.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#S3 refused uses Ask Stop unconfirmed presentation and keeps Stop retryable",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#S3 network uses Ask Stop unconfirmed presentation and keeps Stop retryable",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#S3 S6 no stop response becomes unconfirmed, keeps output and allows one new attempt",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#S4 losing the stream while stopping follows the DONE lifecycle without inventing confirmation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#S4 losing the stream while stopping follows the READY_FOR_LLM lifecycle without inventing confirmation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#R4 discovery and record fetches that never answer are aborted and leave unconfirmed Stop available",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#R4 ERROR lifecycle after lost stopping stream produces a failed receipt without a streamed error"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts"
      ],
      "class": "New",
      "behaviour": "N2 early Stop followed by HTTP/network/stream-loss admission failure: retain the ordinary failed receipt, release the composer, show no stopping/Stop receipt, issue no DELETE and do not poll for an unadmitted run. N2 completion before identity remains complete and releases the composer without Stop note/polling. G1 never-answering admission ends failed and releases the composer at the Stop deadline, without discovery, DELETE or Stop note.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed accordingly",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "owner-words",
          "quote": "Step 3 is a natural test of the idea. It applies the shared vocabulary to agent runs, which is exactly where the Stop bug sits. Fixing the bug becomes the first real proof of the approach",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "owner-words",
          "quote": "Nothing changes what you see in the app, except the Stop fix: a halted run would then show 'stopping' until the backend confirms.",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a new task register accordingly. then proceed autonomously with investigating and designing the work",
          "delegationLocation": "task t-20261029-fs993c request (earlier delegation)",
          "delegationScope": "Investigate and design the shared Stop vocabulary and agent-run Halt repair in step 3, under the subsequently approved recommendation. Preserve established obligations except the premature Halt conclusion explicitly changed by the owner.",
          "choice": "Latch pre-identity Stop only for that dispatch; do not turn failed or already completed admission into a confirmed halt. Give a never-answering admission a Stop deadline to release its composer, with no search for another run. Preserve the no-Stop failure path (separate preservation checks).",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-halt-confirmation-repairs.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#N2 http start failure with Stop true shows a failed receipt and releases the composer without polling",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#N2 network start failure with Stop true shows a failed receipt and releases the composer without polling",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#N2 lost start failure with Stop true shows a failed receipt and releases the composer without polling",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#G1 a start that never answers releases the composer at the Stop deadline with a failed receipt and no polling",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#N2 early Stop then completion before identity releases the composer without polling"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts"
      ],
      "class": "New",
      "behaviour": "N3 known/unknown resumed conversation: target the actual conversation, or run identity fallback until it is known; stream loss remains unconfirmed until a later addressed CANCELLED, and missing confirmation leaves retry available. G2 adopted resumed run with pending/CANCELLED/DONE stream terminal but a nonterminal saved record: address the actual conversation and stop record polling after five seconds even after notifications. A saved clean DONE while this window awaits its own Halt confirmation remains covered by the unchanged beyond-grace DS3 DS4 test.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed accordingly",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "owner-words",
          "quote": "Step 3 is a natural test of the idea. It applies the shared vocabulary to agent runs, which is exactly where the Stop bug sits. Fixing the bug becomes the first real proof of the approach",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "owner-words",
          "quote": "Nothing changes what you see in the app, except the Stop fix: a halted run would then show 'stopping' until the backend confirms.",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a new task register accordingly. then proceed autonomously with investigating and designing the work",
          "delegationLocation": "task t-20261029-fs993c request (earlier delegation)",
          "delegationScope": "Investigate and design the shared Stop vocabulary and agent-run Halt repair in step 3, under the subsequently approved recommendation. Preserve established obligations except the premature Halt conclusion explicitly changed by the owner.",
          "choice": "Keep Stop and lifecycle reconciliation scoped to the run real conversation rather than its adopted local session id. Bound resumed nonterminal-record polling only where this does not replace an established saved-DONE confirmation obligation.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-halt-confirmation-repairs.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#N3 resumed known conversation reconciles a later CANCELLED only at its actual address",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#N3 resumed unknown conversation reconciles a later CANCELLED only at its actual address",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#G2 a resumed run adopted into another conversation bounds accepted Stop polling with a nonterminal record after pending",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#G2 a resumed run adopted into another conversation bounds accepted Stop polling with a nonterminal record after CANCELLED",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#G2 a resumed run adopted into another conversation bounds accepted Stop polling with a nonterminal record after DONE"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts"
      ],
      "class": "New",
      "behaviour": "N4 accepted/refused/network/unanswered Stop with no saved DONE: read records during the five-second grace, stop further polling after it even on progress notifications, keep the run streaming and invent no Stop note. This bound does not apply to this window own Halt awaiting its saved confirmation after a clean DONE.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed accordingly",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "owner-words",
          "quote": "Step 3 is a natural test of the idea. It applies the shared vocabulary to agent runs, which is exactly where the Stop bug sits. Fixing the bug becomes the first real proof of the approach",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "owner-words",
          "quote": "Nothing changes what you see in the app, except the Stop fix: a halted run would then show 'stopping' until the backend confirms.",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a new task register accordingly. then proceed autonomously with investigating and designing the work",
          "delegationLocation": "task t-20261029-fs993c request (earlier delegation)",
          "delegationScope": "Investigate and design the shared Stop vocabulary and agent-run Halt repair in step 3, under the subsequently approved recommendation. Preserve established obligations except the premature Halt conclusion explicitly changed by the owner.",
          "choice": "Bound repeated record reads for unconfirmed attempts without a saved DONE while preserving retry/observation and the existing indefinitely late saved-DONE confirmation oracle. No old polling obligation is retired.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-halt-confirmation-repairs.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#N4 accepted Stop without a saved DONE bounds record polling to the five-second grace even after further notifications",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#N4 refused Stop without a saved DONE bounds record polling to the five-second grace even after further notifications",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#N4 network Stop without a saved DONE bounds record polling to the five-second grace even after further notifications",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#N4 no-answer Stop without a saved DONE bounds record polling to the five-second grace even after further notifications"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts"
      ],
      "class": "New",
      "behaviour": "S8 P5 reload: retain the baseline projection of an existing stop_requested record before CANCELLED/DONE/READY_FOR_LLM, exactly one halted note before discovery, no dispatch/DELETE, completed answer/disposition retained; live discovery restores streaming and removes the note. N2 no-Stop HTTP/network/stream-loss admission failures: retain failed receipt, released composer and absence of Stop controls/note/DELETE/polling. S4 CANCELLED after stream loss: retain halted outcome without invented confirmation. R4 retry after the original grace: a later saved CANCELLED still confirms halted and clears unconfirmed/Stop controls.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed accordingly",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "owner-words",
          "quote": "Step 3 is a natural test of the idea. It applies the shared vocabulary to agent runs, which is exactly where the Stop bug sits. Fixing the bug becomes the first real proof of the approach",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "owner-words",
          "quote": "Nothing changes what you see in the app, except the Stop fix: a halted run would then show 'stopping' until the backend confirms.",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a new task register accordingly. then proceed autonomously with investigating and designing the work",
          "delegationLocation": "task t-20261029-fs993c request (earlier delegation)",
          "delegationScope": "Investigate and design the shared Stop vocabulary and agent-run Halt repair in step 3, under the subsequently approved recommendation. Preserve established obligations except the premature Halt conclusion explicitly changed by the owner.",
          "choice": "Add preservation schedules for reload projection, ordinary admission failure, CANCELLED reconciliation and later explicit retry. The visible-change limit preserves these old outcomes; they pass on f956fc3f9e and the candidate and are not claimed as newly repaired behavior.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-halt-confirmation-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#S8 P5 reload projects an existing record with stop_requested before CANCELLED as on the baseline",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#S8 P5 reload projects an existing record with stop_requested before DONE as on the baseline",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#S8 P5 reload projects an existing record with stop_requested before READY_FOR_LLM as on the baseline",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#N2 http start failure with Stop false shows a failed receipt and releases the composer without polling",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#N2 network start failure with Stop false shows a failed receipt and releases the composer without polling",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#N2 lost start failure with Stop false shows a failed receipt and releases the composer without polling",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#S4 losing the stream while stopping follows the CANCELLED lifecycle without inventing confirmation",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.delegateStop.test.ts#R4 Stop retry after the polling grace reads a later CANCELLED and removes the unconfirmed controls"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-ask.test.ts"
      ],
      "class": "New",
      "behaviour": "sv3StopAsk exposes the actual HTTP response status and ok (200/true, 409/false) instead of collapsing delivery to a boolean. A lost answer rejects with the original connection-lost TypeError, separately from a received refusal. Tagged resolution/rejection assertions keep both the rejection and exact-error oracle while producing an assertion failure on the old boolean-returning implementation.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed accordingly",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "owner-words",
          "quote": "Step 3 is a natural test of the idea. It applies the shared vocabulary to agent runs, which is exactly where the Stop bug sits. Fixing the bug becomes the first real proof of the approach",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "owner-words",
          "quote": "Nothing changes what you see in the app, except the Stop fix: a halted run would then show 'stopping' until the backend confirms.",
          "location": "task t-20261029-fs993c request, 2026-10-29"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "open a new task register accordingly. then proceed autonomously with investigating and designing the work",
          "delegationLocation": "task t-20261029-fs993c request (earlier delegation)",
          "delegationScope": "Investigate and design the shared Stop vocabulary and agent-run Halt repair in step 3, under the subsequently approved recommendation. Preserve established obligations except the premature Halt conclusion explicitly changed by the owner.",
          "choice": "Expose HTTP refusal versus lost transport to the shared StopAttempt vocabulary without changing Ask visible waiting/receipt behavior. Keep status/ok response metadata and propagate the original rejection so callers can distinguish the two outcomes.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/fe-halt-confirmation-repairs.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-ask.test.ts#retains the HTTP response for status 200 instead of a boolean",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-ask.test.ts#retains the HTTP response for status 409 instead of a boolean",
        "modules/ui-web/src/shell-v0/views/search-v3/sv3-ask.test.ts#a lost answer rejects rather than reporting an HTTP refusal"
      ],
      "repaired": true
    }
  ]
}
```

## Acceptance records
