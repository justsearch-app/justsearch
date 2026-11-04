---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: shell-s3a-builder
---

# Reject pending authorizations and preserve exact request history

DE8, DE7, C12 and L5 follow the builder brief and binding D1/D2 decisions.
The gate mints the pending id before publishing its ledger row; HTTP and MCP
carry that same id into the pending store. Only optional pendingId is added to
persisted gate rows. No migration or rewrite is required. Workflow/chat gates
use a different approval registry and retain their existing reject route.

All added tests are durable. Reflective lookup in the Java checks is fail-before
compatibility scaffolding only: missing production behavior stays missing.
No independent acceptance is claimed by this builder.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/operations/authorizationReject.test.ts",
        "modules/ui-web/src/shell-v0/state/frameStatus.authorization.test.ts"
      ],
      "class": "New",
      "behaviour": "Deny cancels before closing, failed cancellation stays answerable, terminal stream events withdraw all copies, a lost response is safely retried, L5 pairs exact request ids, and Earlier names the terminal outcome.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Denying a backend pending authorization cancels it in the backend (new reject endpoint: single-use, fail-closed, idempotent), and every window drops it from Needs you.",
          "location": "task t-20261103-shell request (DE8 builder brief, 2026-11-04)"
        },
        {
          "kind": "owner-words",
          "quote": "With an older waiting request and a newer answered request for the same operation, the status panel lists the waiting one only under Needs you, and the answered one (with its outcome) under Earlier.",
          "location": "task t-20261103-shell request (L5 builder brief, 2026-11-04)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3a-reject-frontend.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/operations/authorizationReject.test.ts",
        "modules/ui-web/src/shell-v0/state/frameStatus.authorization.test.ts#L5 pairs by exact pending id when an older request waits and a newer request was answered",
        "modules/ui-web/src/shell-v0/state/frameStatus.authorization.test.ts#Earlier names an expired request instead of saying approval requested",
        "modules/ui-web/src/shell-v0/state/frameStatus.authorization.test.ts#Earlier names an allowed request and retires its keyed GATED row"
      ]
    },
    {
      "items": [
        "modules/app-observability/src/test/java/io/justsearch/app/observability/advisory/PendingAuthorizationAdvisoryProjectorTest.java",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/ledger/AuthorizationGateIdentityTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/executor/OperationExecutorImplTest.java",
        "modules/ui/src/test/java/io/justsearch/ui/api/AuthorizationRejectTest.java"
      ],
      "class": "New",
      "behaviour": "Window-only rejection is single-use and fail-closed across replay, expiry, restart and approval races. Rejected peek/approval report denial, terminal events broadcast once, ledger identity survives legacy journal reads, and terminal events never announce another waiting request.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Denying a backend pending authorization cancels it in the backend (new reject endpoint: single-use, fail-closed, idempotent), and every window drops it from Needs you.",
          "location": "task t-20261103-shell request (DE8 builder brief, 2026-11-04)"
        },
        {
          "kind": "owner-words",
          "quote": "Authorized saved-state change: an OPTIONAL, additive `pendingId` (or equivalent unique request key) on persisted action-ledger gate rows. Readers must tolerate its absence (rows written before this change); no migration, no rewrite of existing rows, no other field changes.",
          "location": "task t-20261103-shell request (binding decision D2, 2026-11-04T18:35Z)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3a-reject-java.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/AuthorizationRejectTest.java",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/ledger/AuthorizationGateIdentityTest.java",
        "modules/app-observability/src/test/java/io/justsearch/app/observability/advisory/PendingAuthorizationAdvisoryProjectorTest.java#terminalDecisionsDoNotAnnounceAnotherApprovalRequest",
        "modules/app-services/src/test/java/io/justsearch/app/services/registry/executor/OperationExecutorImplTest.java#gateIdentityExistsBeforePublicationAndSurvivesTheConfirmation"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/operations/OperationClient.test.ts",
        "modules/ui-web/src/shell-v0/operations/pendingAuthorizationWithdrawal.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Prompts carry their cancellation callback, and the HTTP fixtures answer the new reject request. Existing gate-error, exact-argument, no-approve, stale-neutral-closure and no-execution assertions are retained."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts"
      ],
      "class": "New",
      "behaviour": "L5 keeps an actively waiting request only in Needs you, including its retained advisory. Answered history remains inert and cannot execute.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "With an older waiting request and a newer answered request for the same operation, the status panel lists the waiting one only under Needs you, and the answered one (with its outcome) under Earlier.",
          "location": "task t-20261103-shell request (L5 builder brief, 2026-11-04)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3a-reject-history.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/frameStatus.test.ts#C12 the broadcast advisory and the request-scoped prompt for one authorization give one decision route"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.authorization.test.ts"
      ],
      "class": "New",
      "behaviour": "Pre-existing gate rows without pendingId remain visible in Earlier even when another request for the same operation is waiting.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Authorized saved-state change: an OPTIONAL, additive `pendingId` (or equivalent unique request key) on persisted action-ledger gate rows. Readers must tolerate its absence (rows written before this change); no migration, no rewrite of existing rows, no other field changes.",
          "location": "task t-20261103-shell request (binding decision D2, 2026-11-04T18:35Z)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3a-reject-legacy.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/frameStatus.authorization.test.ts#legacy gate rows without a pending id never hide an answered row for the same operation"
      ]
    }
  ]
}
```

## Acceptance records

Pending independent verification.
