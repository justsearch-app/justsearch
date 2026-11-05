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
      "behaviour": "Agent requests arrive in Needs you without a dialog and are announced once (never on rediscovery); the panel review shows who asked and when, the question, consequence lines, the agent's reason marked as written by it, the deadline and its consequence; durable scope only where the backend keeps it; Allow this time / Deny and keys 1/2 answer once after arming and never in text fields, IME composition, key repeat, with modifiers or under another modal; fresh live details are read on review and unavailable details deny; a failed Deny keeps the request answerable; typed requests open the protected dialog from Review; panel, dialog and announcement execute once; a waiting run opens its request's review; the engine's pending check withdraws a gone outside request but never the agent in Search's run-owned request (its tool-call id is not a pending-store id).",
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
    }
  ]
}
```

## Acceptance records
