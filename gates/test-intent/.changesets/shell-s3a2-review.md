---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: shell-s3a2-builder
---

# Agent requests reviewed in the status panel; request history made consistent (L9-L11)

Agent requests (MCP and other outside agents, the agent in Search) no longer open a dialog on
arrival: they wait in Needs you, are announced once, and the panel's review answers them through
the authorization host's one decision path. Typed approvals open the protected dialog from Review;
plugin consent keeps opening it at once, with "Allow once" as the primary. The engine records a
same-key copy as withdrawn (L9), and the HTTP history route and the MCP outcome tool answer through
one function that also reports an unanswered deadline as a terminal denial (L10, L11).

Saved-state note: L9 adds one value, `WITHDRAWN`, to the existing string `disposition` of persisted
action-ledger gate rows. Nothing is migrated or rewritten; older readers show it as an unknown
disposition. L11 adds no persisted state: the expiry answer is process-local, bounded like denials.

Repair (after the independent check of c42cb3591c): an agent's request is allowed only on live
details read for that press that still match, and a failed read sends nothing; every listed or
reviewed Allow/Deny re-arms when the request under it changes; a restored in-app approval is not
announced. Those checks fail on c42cb3591c and pass now.

Final repair (after the independent check of 38d5e1e812): only present, well-formed live details that
equal what was shown allow an agent's request; a Deny abandons an Allow still reading them, so the
backend receives only the last press; every source but the person's own window gestures waits in
Needs you; each queued elicitation prompt answers its own caller and stays modal after a remount.
Those checks fail on 38d5e1e812 and pass now.

All added checks are durable. The reflective lookup of `ResourceApiModule.historyOutcome` in the
Java check is fail-before compatibility scaffolding: on the baseline it reads the recorded outcome,
which is what that route returned there. No independent acceptance is claimed by this builder.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts"
      ],
      "class": "New",
      "behaviour": "Agent requests arrive in Needs you without a dialog and are announced once (never on rediscovery); the panel review shows who asked and when, the question, consequence lines, the agent's reason marked as written by it, the deadline and its consequence; durable scope only where the backend keeps it; Allow this time / Deny and keys 1/2 answer once after arming and never in text fields, IME composition, key repeat, with modifiers or under another modal; fresh live details are read on review; details that cannot be read never allow it, send nothing and leave it answerable (only the person's Deny denies it); a failed Deny keeps the request answerable; typed requests open the protected dialog from Review; panel, dialog and announcement execute once; a waiting run opens its request's review; the engine's pending check withdraws a gone outside request but never the agent in Search's run-owned request (its tool-call id is not a pending-store id).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE3",
          "quote": "Agent/MCP requests are reviewed in the status panel: who, consequence lines, the agent's reason marked as written by the agent, deadline and what happens on timeout, Allow this time / Deny (keys 1/2), durable scope offered only where the backend allows it (never HIGH, never live agent approvals). Fresh live details are fetched; unavailable details deny.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C8",
          "quote": "Keyboard ownership: the panel's review keys 1/2 never fire inside text fields, during IME composition, on key repeat, or under another modal.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C12",
          "quote": "One decision, one execution: the same authorization reached from the panel, a dialog and an MCP announcement executes once; repeated clicks are idempotent; expired, withdrawn or superseded requests cannot execute and leave the panel.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "Agent requests (backend pending authorizations from MCP/outside agents, in-app agent approvals and agent suggestions) no longer open a dialog on arrival. They appear in Needs you (pill state needs-you with count, an assertive live announcement once per arrival, never for historical replay); the panel's review shows the scenario's details and answers them. Typed approvals (high-risk, and agent medium-risk proposals that need typing) are listed the same way, and their Review opens the protected security dialog in the top layer.",
          "location": "task t-20261103-shell request (S3a2 builder brief, orchestrator decision 4 from the approved design, 2026-11-05)"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "ST4",
          "quote": "Needs you lists backend pending authorizations with their deadline, agent suggestions (proposed effects), plugin capability requests waiting, indexing holds, app update, rebuild gap decision, each with its actions.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "Deny in the panel calls the reject endpoint as the dialog does; a failed Deny says the request could not be cancelled and keeps it answerable (as today).",
          "location": "task t-20261103-shell request (S3a2 builder brief, 2026-11-05)"
        },
        {
          "kind": "owner-words",
          "quote": "deny here means \"never allow\"; do not send a reject for a fetch failure unless the person pressed Deny",
          "location": "task t-20261103-shell request (S3a2 repair brief from the independent check of c42cb3591c, 2026-11-05)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3a2-review-frontend.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts#DE3 a MCP request waits in Needs you, announced as a live arrival",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts#DE3 a AGENT_LOOP request waits in Needs you, announced as a live arrival",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts#DE3 a WORKFLOW request waits in Needs you, announced as a live arrival",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts#DE3 a LLM_EMISSION request waits in Needs you, announced as a live arrival",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts#DE3 a request rediscovered after a reload waits in Needs you without counting as a live arrival",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts#DE3 reviewing reads the live details again: fresh fields, null when gone, a failure when unreadable",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#DE3 the agent request waits in Needs you; its review reads the live gate again"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts"
      ],
      "class": "New",
      "behaviour": "The person's own request (a user-gesture transport, or none named) keeps its dialog on arrival. Preservation: passes before and after.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Plugin capability consent that the person's own action triggered (installing or enabling a plugin) keeps opening its protected dialog at once, \"Allow once\" primary.",
          "location": "task t-20261103-shell request (S3a2 builder brief, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3a2-review-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts#DE3 the person's own BUTTON request keeps its dialog on arrival",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts#DE3 the person's own PALETTE request keeps its dialog on arrival",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts#DE3 the person's own undefined request keeps its dialog on arrival"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "In real Chromium, in every queue order of an own typed approval, a plugin capability request and an agent's typed request, each protected request opens modal in the top layer above a skin overlay at z-index 99999, with the security-check header; the agent's typed request opens only from Review; an inline agent request never opens a dialog; Allow once is the capability primary. The panel review fits without sideways overflow in dark, light and forced colours at 1280x800, 900x800 and 600x400. The page harness gains an agent-request hook; the existing cases are unchanged.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE4",
          "quote": "Typed approvals and plugin capability consent keep the protected dialog in the top layer, not restylable, in every queue order. \"Allow once\" is the primary for capability requests.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "X1",
          "quote": "Dark, light, forced colours; 1280×800, 900×800, 600×400 without sideways overflow; no new hard-coded colours, sizes or z-indexes.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "X2",
          "quote": "Dark, light, forced colours; 1280×800, 900×800, 600×400 without sideways overflow; no new hard-coded colours, sizes or z-indexes.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3a2-review-chromium.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#DE4 typed → capability → agent-typed: each protected request opens in the top layer above a skin overlay; an agent request opens no dialog on arrival",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#DE4 typed → agent-typed → capability: each protected request opens in the top layer above a skin overlay; an agent request opens no dialog on arrival",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#DE4 capability → typed → agent-typed: each protected request opens in the top layer above a skin overlay; an agent request opens no dialog on arrival",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#DE4 capability → agent-typed → typed: each protected request opens in the top layer above a skin overlay; an agent request opens no dialog on arrival",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#DE4 agent-typed → typed → capability: each protected request opens in the top layer above a skin overlay; an agent request opens no dialog on arrival",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#DE4 agent-typed → capability → typed: each protected request opens in the top layer above a skin overlay; an agent request opens no dialog on arrival",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#X1/X2 the panel review in dark at 1280×800 fits without sideways overflow",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#X1/X2 the panel review in dark at 900×800 fits without sideways overflow",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#X1/X2 the panel review in dark at 600×400 fits without sideways overflow",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#X1/X2 the panel review in light at 1280×800 fits without sideways overflow",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#X1/X2 the panel review in light at 900×800 fits without sideways overflow",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#X1/X2 the panel review in light at 600×400 fits without sideways overflow",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#X1/X2 the panel review in forced at 1280×800 fits without sideways overflow",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#X1/X2 the panel review in forced at 900×800 fits without sideways overflow",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#X1/X2 the panel review in forced at 600×400 fits without sideways overflow"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.authorization.test.ts",
        "modules/ui/src/test/java/io/justsearch/ui/api/McpAuthorizationDecisionHttpTest.java"
      ],
      "class": "New",
      "behaviour": "L9: answering one of two same-key requests records the answered one with the decision and the other as WITHDRAWN (the existing oneAnswerWithdrawsEveryPendingOfTheSameKey expectation for the copy changes from the decision to WITHDRAWN); Earlier shows one row per decision. L10: GET /api/operation-history/{key}, as ResourceApiModule wires it, equals the outcome tool while waiting, after a denial and after expiry. L11: an unanswered request past its deadline reads failed/APPROVAL_EXPIRED 'Denied automatically' and a repeat with that key is refused without a new request; a copy that still waits keeps the key open.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "L9: two waiting requests with one operation key; Allow one → the other (withdrawn) is recorded as \"Approved\" and adds a second Earlier row. Expected: the withdrawn sibling is recorded as withdrawn (\"Withdrawn: answered with another request\" or similar plain wording), one Earlier row per decision.",
          "location": "task t-20261103-shell request (S3a2 builder brief, carried limits, 2026-11-05)"
        },
        {
          "kind": "owner-words",
          "quote": "L10: while a request waits or after a denial, the MCP outcome tool (justsearch_operation_outcome) and /api/operation-history disagree (history says \"unknown\"); the tool's description says they match. Expected: they agree.",
          "location": "task t-20261103-shell request (S3a2 builder brief, carried limits, 2026-11-05)"
        },
        {
          "kind": "owner-words",
          "quote": "L11: after a request expires, the outcome tool reports plain \"unknown\". Expected: a terminal denial (denied automatically, no answer), consistent with the Earlier wording \"Denied automatically\".",
          "location": "task t-20261103-shell request (S3a2 builder brief, carried limits, 2026-11-05)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3a2-review-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/McpAuthorizationDecisionHttpTest.java#oneAnswerWithdrawsEveryPendingOfTheSameKey",
        "modules/ui/src/test/java/io/justsearch/ui/api/McpAuthorizationDecisionHttpTest.java#operationHistoryAgreesWithTheOutcomeToolWhileWaitingAndAfterDenial",
        "modules/ui/src/test/java/io/justsearch/ui/api/McpAuthorizationDecisionHttpTest.java#anExpiredRequestIsDeniedAutomaticallyForTheToolTheHistoryAndARepeat"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/McpAuthorizationDecisionHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Preservation for L11's scope: an expired copy does not close a key while another copy of it still waits; that copy can still be approved and executes once. Passes before and after.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C12",
          "quote": "One decision, one execution: the same authorization reached from the panel, a dialog and an MCP announcement executes once; repeated clicks are idempotent; expired, withdrawn or superseded requests cannot execute and leave the panel.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3a2-review-java-preservation.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/McpAuthorizationDecisionHttpTest.java#anExpiredCopyLeavesTheKeyOpenWhileAnotherCopyStillWaits"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.authorization.test.ts"
      ],
      "class": "New",
      "behaviour": "Earlier lists one row per decision: a WITHDRAWN copy adds none, for an allowed and a denied answer.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "L9: two waiting requests with one operation key; Allow one → the other (withdrawn) is recorded as \"Approved\" and adds a second Earlier row. Expected: the withdrawn sibling is recorded as withdrawn (\"Withdrawn: answered with another request\" or similar plain wording), one Earlier row per decision.",
          "location": "task t-20261103-shell request (S3a2 builder brief, carried limits, 2026-11-05)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3a2-review-frontend.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/frameStatus.authorization.test.ts#L9 a same-key copy withdrawn by a APPROVED answer adds no Earlier row: one row per decision",
        "modules/ui-web/src/shell-v0/state/frameStatus.authorization.test.ts#L9 a same-key copy withdrawn by a DENIED answer adds no Earlier row: one row per decision"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPill.feedback.test.ts",
        "modules/ui-web/src/shell-v0/components/StatusPill.transitions.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "rename",
        "input re-encoding"
      ],
      "whatMoved": "The arrival signal for desktop escalation moved from the advisory stream's REQUIRES_ACK record to the authorization host's request (live versus rediscovered), so an MCP request is announced once rather than by both. Kept oracle: a rediscovered or startup request is silent and never reaches the desktop; a live arrival while unfocused reaches it exactly once, not again on a re-feed or a remount; a focused window does not. The PERSISTED-hint case becomes the rediscovered case: the non-escalating input is now the host's rediscovery flag. Titles renamed to the new owner.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "They appear in Needs you (pill state needs-you with count, an assertive live announcement once per arrival, never for historical replay)",
          "location": "task t-20261103-shell request (S3a2 builder brief, orchestrator decision 4 from the approved design, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3a2-review-frontend.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.halt.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "rename",
        "instrumentation"
      ],
      "whatMoved": "The waiting manual approval is observed in the host's Needs-you list instead of an open dialog (it no longer opens one on arrival; the added assertion that no dialog is open is stricter). Kept oracle: Halt withdraws it; no run-ended notice before the run concludes; exactly one notice after CANCELLED by stream or record, none on refusal; no POST. Title renamed from 'an open manual ceremony closes on Halt' to 'a waiting manual approval is withdrawn on Halt'.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Agent requests (backend pending authorizations from MCP/outside agents, in-app agent approvals and agent suggestions) no longer open a dialog on arrival.",
          "location": "task t-20261103-shell request (S3a2 builder brief, orchestrator decision 4 from the approved design, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3a2-review-adapted.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts"
      ],
      "class": "New",
      "behaviour": "An agent's request is allowed only on live details read for that press that match what the person saw: an Allow pressed while the review's read is still out answers nothing (Allow shows as waiting); an Allow in the review, in the list or in the typed security dialog reads the details again, and a failed read, one that does not answer within 5 s, or different details refuse it with a plain message (different details are shown and re-arm), send no denial and leave the request answerable; the person's Deny still denies it. A held call of the agent in Search restored by a reload or reconnect (the attach snapshot) is listed but not announced, without an invented asking time; a live frame announces.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "no path executes Allow unless fresh live details for that pending id were fetched successfully after the review opened (or, for an Allow outside the review, as part of that press) and still match; if the fetch fails, times out or returns different details, the answer is refused with a plain \"details unavailable\" message and the request stays answerable",
          "location": "task t-20261103-shell request (S3a2 repair brief from the independent check of c42cb3591c, 2026-11-05)"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE3",
          "quote": "Agent/MCP requests are reviewed in the status panel: who, consequence lines, the agent's reason marked as written by the agent, deadline and what happens on timeout, Allow this time / Deny (keys 1/2), durable scope offered only where the backend allows it (never HIGH, never live agent approvals). Fresh live details are fetched; unavailable details deny.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "restoration never announces; only a live arrival announces, once.",
          "location": "task t-20261103-shell request (S3a2 repair brief from the independent check of c42cb3591c, 2026-11-05)"
        },
        {
          "kind": "owner-words",
          "quote": "They appear in Needs you (pill state needs-you with count, an assertive live announcement once per arrival, never for historical replay)",
          "location": "task t-20261103-shell request (S3a2 builder brief, orchestrator decision 4 from the approved design, 2026-11-05)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3a2-review-repair.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts#an Allow pressed while the live details are still being read answers nothing; unavailable details send nothing and the request stays answerable",
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts#an Allow in the list reads the live details as part of the press: unavailable or different details are refused, matching ones allow",
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts#an Allow whose live details do not come in time is refused as unavailable",
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts#a typed request's Approve in the security dialog reads the live details first",
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts#C6 a held call restored by the reattach snapshot is listed without an announcement; a live frame announces"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "In real Chromium, against the engine's pending-store route: an Allow pressed in the review while its live-details read is still out (it then answers 503) sends no approval, says the details are unavailable, and the request is still answered by Deny; the list Allow and the typed security dialog's Approve read the details as part of the press and send nothing when they answer 503 or differ, and one approval when they match. With two waiting requests, a native double-click on the first row's Allow approves only the first; a click where the second row's Allow moved under the pointer 100 ms later answers nothing, and a fresh click after the arming delay answers it once. The page harness gains a live-agent hook (its details read over HTTP, its approval sent over HTTP); the existing cases are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "no path executes Allow unless fresh live details for that pending id were fetched successfully after the review opened (or, for an Allow outside the review, as part of that press) and still match; if the fetch fails, times out or returns different details, the answer is refused with a plain \"details unavailable\" message and the request stays answerable",
          "location": "task t-20261103-shell request (S3a2 repair brief from the independent check of c42cb3591c, 2026-11-05)"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "DE3",
          "quote": "Agent/MCP requests are reviewed in the status panel: who, consequence lines, the agent's reason marked as written by the agent, deadline and what happens on timeout, Allow this time / Deny (keys 1/2), durable scope offered only where the backend allows it (never HIGH, never live agent approvals). Fresh live details are fetched; unavailable details deny.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-words",
          "quote": "a control whose row identity changed under the pointer (reflow, arrival, removal) ignores input until a fresh press after the arming delay; a native double-click, key auto-repeat or held Enter answers at most one request.",
          "location": "task t-20261103-shell request (S3a2 repair brief from the independent check of c42cb3591c, 2026-11-05)"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C12",
          "quote": "One decision, one execution: the same authorization reached from the panel, a dialog and an MCP announcement executes once; repeated clicks are idempotent; expired, withdrawn or superseded requests cannot execute and leave the panel.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3a2-review-repair-chromium.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#DE3 an Allow pressed while the review is still reading the live details sends nothing; unavailable details stay answerable",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#DE3 the list Allow reads the live details as part of the press: unavailable or different details allow nothing",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#DE3 a typed request's Approve in the security check reads the live details as part of the press",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#C12 a native double-click on the first row's Allow answers only the first request",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#C12 a click where the next row moved in answers nothing; a fresh click after the delay answers it once"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "In real Chromium, Enter held on the first of two waiting requests' list Allow (the platform's auto-repeat) answers at most one request and the second still waits. Preservation: passes before and after (the list now answers on the release of an unrepeated press).",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "a control whose row identity changed under the pointer (reflow, arrival, removal) ignores input until a fresh press after the arming delay; a native double-click, key auto-repeat or held Enter answers at most one request.",
          "location": "task t-20261103-shell request (S3a2 repair brief from the independent check of c42cb3591c, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3a2-review-repair-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#C12 Enter held on the first row's Allow answers at most one request"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.approvals.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "Each click on a suggestion's Allow suggestion or Reject now comes after the fake clock advances the 400 ms arming delay (a new arm() helper), because a listed answer ignores input until it has stood still that long. Kept oracle: accept executes once on a repeated click and records acceptance; reject records rejection without applying; HIGH and agent MEDIUM proposals need the exact typed operation and keep consent provenance; a user MEDIUM proposal dispatches without a ceremony or client-minted token.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "a control whose row identity changed under the pointer (reflow, arrival, removal) ignores input until a fresh press after the arming delay; a native double-click, key auto-repeat or held Enter answers at most one request.",
          "location": "task t-20261103-shell request (S3a2 repair brief from the independent check of c42cb3591c, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3a2-review-repair-adapted.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts"
      ],
      "class": "New",
      "behaviour": "An agent's request is allowed only on live details that are present, well-formed and equal to what was shown: a read with no or an empty operation, without the shown arguments or requester, with a field that is not text, or that is not an object at all (empty, a list, plain text) refuses the Allow as unavailable; nothing is sent (no approval, no denial), what was shown stays shown, and complete matching details then allow it.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Allow proceeds only when the read returns details that are present, well-formed and equal to what was shown (operation, arguments, requester); empty, partial, malformed or different details refuse the answer (\"Its current details are unavailable…\" / \"Its details changed…\") and the request stays answerable.",
          "location": "task t-20261103-shell request (S3a2 final repair brief from the independent check of 38d5e1e812, 2026-11-05)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3a2-review-repair2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts#a live read with an empty answer refuses the Allow as unavailable, sends nothing, and the request stays answerable",
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts#a live read with no operation refuses the Allow as unavailable, sends nothing, and the request stays answerable",
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts#a live read with an empty operation refuses the Allow as unavailable, sends nothing, and the request stays answerable",
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts#a live read with no arguments refuses the Allow as unavailable, sends nothing, and the request stays answerable",
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts#a live read with no requester refuses the Allow as unavailable, sends nothing, and the request stays answerable",
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts#a live read with arguments that are not text refuses the Allow as unavailable, sends nothing, and the request stays answerable",
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts#a live read with a list refuses the Allow as unavailable, sends nothing, and the request stays answerable",
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts#a live read with plain text refuses the Allow as unavailable, sends nothing, and the request stays answerable"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts"
      ],
      "class": "New",
      "behaviour": "One decision in flight per request, in the list, the panel review and the typed security dialog: a Deny pressed while an Allow still reads the live details abandons that Allow; when the Deny fails ('could not be cancelled'), the Allow's late read sends nothing and does not replace that message, the request stays answerable, and the person's next Deny ends it.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "one decision in flight per request; pressing Deny aborts a pending Allow before its approve is sent; an Allow whose read finishes after a Deny press never sends; what the backend finally receives is what the person pressed last (or nothing, if the last press failed).",
          "location": "task t-20261103-shell request (S3a2 final repair brief from the independent check of 38d5e1e812, 2026-11-05)"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C12",
          "quote": "One decision, one execution: the same authorization reached from the panel, a dialog and an MCP announcement executes once; repeated clicks are idempotent; expired, withdrawn or superseded requests cannot execute and leave the panel.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3a2-review-repair2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts#in the list, a Deny pressed while an Allow still reads the live details abandons it: when the Deny fails, the late read allows nothing",
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts#in the review, a Deny pressed while an Allow still reads the live details abandons it: when the Deny fails, the late read allows nothing",
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts#in the security dialog, a Deny pressed while an Allow still reads the live details abandons it: when the Deny fails, the late read allows nothing"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts"
      ],
      "class": "New",
      "behaviour": "Every source the Engine names other than the person's own window gestures (BUTTON, PALETTE, RAIL) — HTTP_API, URL_BAR, URL_DEEPLINK, PLUGIN_EMITTED, SCHEDULED, RULE_ENGINE, SYSTEM_INTERNAL, and a source added later — waits in Needs you as a live, announced arrival instead of opening a dialog (MCP, AGENT_LOOP, WORKFLOW and LLM_EMISSION already did).",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "every backend pending-authorization source (MCP, AGENT_LOOP, WORKFLOW, LLM_EMISSION, HTTP_API and any other present in the code) goes to Needs you with one live announcement; only plugin consent the person's own action triggered opens at once. Pin every source in a test (parameterised).",
          "location": "task t-20261103-shell request (S3a2 final repair brief from the independent check of 38d5e1e812, 2026-11-05)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3a2-review-repair2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts#DE3 a HTTP_API request waits in Needs you, announced as a live arrival",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts#DE3 a URL_BAR request waits in Needs you, announced as a live arrival",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts#DE3 a URL_DEEPLINK request waits in Needs you, announced as a live arrival",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts#DE3 a PLUGIN_EMITTED request waits in Needs you, announced as a live arrival",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts#DE3 a SCHEDULED request waits in Needs you, announced as a live arrival",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts#DE3 a RULE_ENGINE request waits in Needs you, announced as a live arrival",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts#DE3 a SYSTEM_INTERNAL request waits in Needs you, announced as a live arrival",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts#DE3 a NOT_YET_KNOWN request waits in Needs you, announced as a live arrival"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/ElicitHost.test.ts"
      ],
      "class": "New",
      "behaviour": "Two queued required elicitation prompts each answer on a deliberate Submit, whether the host stays mounted, is re-attached, or is replaced by a new host after navigating away: the first resolves with its data, the second is shown next and its Submit resolves its own caller, and nothing is left on screen.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "the second prompt answers on a deliberate Submit and its caller resolves.",
          "location": "task t-20261103-shell request (S3a2 final repair brief from the independent check of 38d5e1e812, 2026-11-05)"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C2",
          "quote": "queued ElicitHost prompts survive navigation and remount; required prompts never resolve as cancelled unintentionally; callers never hang",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3a2-review-repair2.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/ElicitHost.test.ts#C2 two queued required prompts each answer on a deliberate Submit when the host stays mounted; neither caller hangs",
        "modules/ui-web/src/shell-v0/components/ElicitHost.test.ts#C2 two queued required prompts each answer on a deliberate Submit when the host is re-attached after navigating away; neither caller hangs",
        "modules/ui-web/src/shell-v0/components/ElicitHost.test.ts#C2 two queued required prompts each answer on a deliberate Submit when the host is replaced by a new host after navigating away; neither caller hangs"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/components/ElicitHost.queue-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "In real Chromium, against the engine's routes: a live-details read answering 200 with an empty body, no operation, no arguments, no requester, a malformed operation or null allows nothing, says the details are unavailable and leaves the request answerable (a complete read then allows it once); a Deny pressed 100 ms after an Allow whose read is slow, answered 503 by the reject route, leaves no approval sent after the read returns, and the next Deny ends the request. Two queued required elicitation prompts answer on deliberate Submit clicks, staying modal after the host is re-attached or replaced. The authorization page harness gains a cancellable live-agent request (Deny sent over HTTP) and raw or slow pending-store answers; the elicitation page is new.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Allow proceeds only when the read returns details that are present, well-formed and equal to what was shown (operation, arguments, requester); empty, partial, malformed or different details refuse the answer (\"Its current details are unavailable…\" / \"Its details changed…\") and the request stays answerable.",
          "location": "task t-20261103-shell request (S3a2 final repair brief from the independent check of 38d5e1e812, 2026-11-05)"
        },
        {
          "kind": "owner-words",
          "quote": "one decision in flight per request; pressing Deny aborts a pending Allow before its approve is sent; an Allow whose read finishes after a Deny press never sends; what the backend finally receives is what the person pressed last (or nothing, if the last press failed).",
          "location": "task t-20261103-shell request (S3a2 final repair brief from the independent check of 38d5e1e812, 2026-11-05)"
        },
        {
          "kind": "owner-words",
          "quote": "the second prompt answers on a deliberate Submit and its caller resolves.",
          "location": "task t-20261103-shell request (S3a2 final repair brief from the independent check of 38d5e1e812, 2026-11-05)"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261103-shell",
          "scenario": "C2",
          "quote": "queued ElicitHost prompts survive navigation and remount; required prompts never resolve as cancelled unintentionally; callers never hang",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3a2-review-repair2-chromium.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#DE3 a live-details read answering 200 with an empty body allows nothing and the request stays answerable",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#DE3 a live-details read answering 200 with no operation allows nothing and the request stays answerable",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#DE3 a live-details read answering 200 with no arguments allows nothing and the request stays answerable",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#DE3 a live-details read answering 200 with no requester allows nothing and the request stays answerable",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#DE3 a live-details read answering 200 with a malformed operation allows nothing and the request stays answerable",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#DE3 a live-details read answering 200 with a null body allows nothing and the request stays answerable",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#C12 a Deny pressed 100 ms after an Allow whose read is slow abandons that Allow: the failed Deny leaves nothing sent",
        "modules/ui-web/src/shell-v0/components/ElicitHost.queue-lockdown.test.ts#C2 two queued required prompts each answer on a deliberate Submit when the host stays mounted",
        "modules/ui-web/src/shell-v0/components/ElicitHost.queue-lockdown.test.ts#C2 two queued required prompts each answer on a deliberate Submit when the host is re-attached after navigating away and back",
        "modules/ui-web/src/shell-v0/components/ElicitHost.queue-lockdown.test.ts#C2 two queued required prompts each answer on a deliberate Submit when the host is replaced by a new host after navigating away and back"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts"
      ],
      "class": "New",
      "behaviour": "Preservation: a live read naming another operation, other arguments or another requester refuses the Allow as changed and sends nothing; the person's own RAIL request keeps its dialog on arrival; every TransportTag value the Engine defines is placed as either a window gesture or an outside source. Pass before and after.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Allow proceeds only when the read returns details that are present, well-formed and equal to what was shown (operation, arguments, requester); empty, partial, malformed or different details refuse the answer (\"Its current details are unavailable…\" / \"Its details changed…\") and the request stays answerable.",
          "location": "task t-20261103-shell request (S3a2 final repair brief from the independent check of 38d5e1e812, 2026-11-05)"
        },
        {
          "kind": "owner-words",
          "quote": "every backend pending-authorization source (MCP, AGENT_LOOP, WORKFLOW, LLM_EMISSION, HTTP_API and any other present in the code) goes to Needs you with one live announcement; only plugin consent the person's own action triggered opens at once. Pin every source in a test (parameterised).",
          "location": "task t-20261103-shell request (S3a2 final repair brief from the independent check of 38d5e1e812, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3a2-review-repair2-preservation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts#a live read with another operation refuses the Allow as changed and sends nothing",
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts#a live read with other arguments refuses the Allow as changed and sends nothing",
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts#a live read with another requester refuses the Allow as changed and sends nothing",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts#DE3 the person's own RAIL request keeps its dialog on arrival",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationBridge.test.ts#DE3 every source the Engine can name is pinned: only window gestures keep a dialog"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The live-details reads the existing checks feed in become complete reads (operation, arguments and requester as the engine's pending route returns them, via a details() helper), because a partial read is now refused. In the Chromium harness, the live agent's read also maps requestedBy and the pending-store mock returns it. Kept oracle: opening the review shows the fresh arguments; an unavailable or different read is refused and a matching one allows once; the typed dialog's Approve reads first; every other assertion unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Allow proceeds only when the read returns details that are present, well-formed and equal to what was shown (operation, arguments, requester); empty, partial, malformed or different details refuse the answer (\"Its current details are unavailable…\" / \"Its details changed…\") and the request stays answerable.",
          "location": "task t-20261103-shell request (S3a2 final repair brief from the independent check of 38d5e1e812, 2026-11-05)"
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts"
      ],
      "class": "New",
      "behaviour": "A live read whose operation or requester is not text refuses the Allow as unavailable, sends nothing and leaves the request answerable. Deriving the status model must not throw; the baseline fails by assertion.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Allow proceeds only when the read returns details that are present, well-formed and equal to what was shown (operation, arguments, requester); empty, partial, malformed or different details refuse the answer (\"Its current details are unavailable…\" / \"Its details changed…\") and the request stays answerable.",
          "location": "task t-20261103-shell request (S3a2 final repair brief from the independent check of 38d5e1e812, 2026-11-05)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3a2-review-type-validation.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts#a live read with an operation that is not text refuses the Allow as unavailable, sends nothing, and the request stays answerable",
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts#a live read with a requester that is not text refuses the Allow as unavailable, sends nothing, and the request stays answerable"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts"
      ],
      "class": "New",
      "behaviour": "Allow requires argsSummary to be a string, possibly empty, in both the initially shown details and the fresh details for that press. Missing or non-string arguments in both reads refuse without an approval POST and leave Deny answerable. A complete refresh cannot authorize arguments never shown. Matching empty summaries allow; changing nonempty arguments to empty refuses as changed before a later deliberate Allow. The Chromium harness reads initial details from the pending route.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Allow requires `argsSummary` to be present as a string (it may be empty) in BOTH the details shown and the fresh details read for that press; a missing key or a non-string value refuses the answer exactly like other malformed details (\"Its current details are unavailable…\"), sends nothing, and keeps the request answerable.",
          "location": "task t-20261103-shell request (S3a2 exact repairs on fcccb2528c, 2026-11-05)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3a2-review-args-required.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts#initial and fresh pending details with no arguments refuse Allow, send no approval, and stay answerable",
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts#initial and fresh pending details with null arguments refuse Allow, send no approval, and stay answerable",
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts#initial and fresh pending details with non-text arguments refuse Allow, send no approval, and stay answerable",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#DE3 initial and fresh pending details with no arguments refuse Allow, send no approval, and stay answerable",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#DE3 initial and fresh pending details with null arguments refuse Allow, send no approval, and stay answerable",
        "modules/ui-web/src/shell-v0/components/AuthorizationHost.activation-lockdown.test.ts#DE3 initial and fresh pending details with non-text arguments refuse Allow, send no approval, and stay answerable",
        "modules/ui-web/src/shell-v0/components/StatusPanel.review.test.ts#matching empty argument summaries allow, while changing nonempty arguments to empty refuses as changed"
      ]
    }
  ]
}
```

## Acceptance records
