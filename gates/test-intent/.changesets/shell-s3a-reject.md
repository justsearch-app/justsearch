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
      "class": "Adaptation",
      "moved": [
        "input re-encoding",
        "instrumentation"
      ],
      "whatMoved": "The current MCP gate fixture carries its exact pendingId while the older legacy row remains unkeyed; the observation selects those same history rows by identity instead of outcome wording. The whole waiting/answered/expired and inert-history oracle is kept. C12's added find assertion restates the existing not-toContain assertion: it is preservation, not a repair, and passes before and after in shell-s3a-reject-history.json.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "With an older waiting request and a newer answered request for the same operation, the status panel lists the waiting one only under Needs you, and the answered one (with its outcome) under Earlier.",
          "location": "task t-20261103-shell request (L5 builder brief, 2026-11-04)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3a-reject-history.json"
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
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpOperationKeyTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "The pending-store stub and verifications use the create overload with the gate's pendingId as the final argument and assert that exact link. The whole oracle is kept: the server-minted key and preparation nonce survive without a caller key; the caller-supplied key is retained separately from public arguments; public arguments contain no operationKey; the error response still routes approval to the app. Exact public-argument equality is classified separately as New below."
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpOperationKeyTest.java"
      ],
      "class": "New",
      "behaviour": "Exact public-argument JSON equality is a new check in both keyed and server-minted-key approval paths, in addition to preserving key separation and nonce linkage.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "McpOperationKeyTest.java's added exact-JSON assertions are new checks: give them a New entry with source and evidence",
          "location": "task t-20261103-shell request (S3a repair brief, 2026-11-04; ea4e44dfb5 independent-check failures)"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-s3a-repair-key-json.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpOperationKeyTest.java#approvalRetainsKeyAndPublicArguments",
        "modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpOperationKeyTest.java#preparedGateRetainsServerMintedKeyAndNonceWithoutCallerKey"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/McpAuthorizationDecisionHttpTest.java"
      ],
      "class": "New",
      "behaviour": "Loopback /mcp rejection yields terminal failed/USER_DENIED using existing fields. Same-key replay cannot dispatch or announce another pending; a fresh key can. Approving a newer waiting request for Apply Excludes or ingest publishes exactly one keyed APPROVED row and executes once, for public-only and frozen-preparation capsules.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "after a reject, the outcome tool reports a terminal \"denied by the user\" for that operation key, and a repeated call with the same key returns that denial without creating a new pending request or prompt; a call with a new key is a new request. No other change to the MCP protocol's shape",
          "location": "task t-20261103-shell request (S3a repair brief, 2026-11-04; ea4e44dfb5 independent-check failures)"
        },
        {
          "kind": "owner-words",
          "quote": "one Earlier row per decision. Execution already happens once; keep that.",
          "location": "task t-20261103-shell request (S3a repair brief, 2026-11-04; ea4e44dfb5 independent-check failures)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3a-repair-decisions.json",
      "newChecks": [
        "modules/ui/src/test/java/io/justsearch/ui/api/McpAuthorizationDecisionHttpTest.java"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.authorization.test.ts"
      ],
      "class": "New",
      "behaviour": "Expired advisory-only history, terminal EXPIRED ledger rows and expired keyed GATED rows all read Denied automatically. The old exact Expired wording assertion is rewritten to the explicit owner wording decision.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "an expired request's Earlier row reads \"Denied automatically\" (matching the waiting text \"Denied automatically in N min\"), not \"Expired\". Denied by you and Allowed unchanged.",
          "location": "task t-20261103-shell request (S3a repair brief, 2026-11-04; ea4e44dfb5 independent-check failures)"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-s3a-repair-expiry.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/state/frameStatus.authorization.test.ts#Earlier names an expired request instead of saying approval requested",
        "modules/ui-web/src/shell-v0/state/frameStatus.authorization.test.ts#Earlier names automatic denial for both terminal ledger expiry and a stale keyed waiting row"
      ]
    },
    {
      "items": [
        "modules/ui/src/test/java/io/justsearch/ui/api/AuthorizationControllerTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "The late capsule-mint admission failure is injected into and verified on the pending-aware mint overload. All late-refusal, retryUnsafe, consumed-pending and no-dispatch assertions are retained."
    }
  ]
}
```

## Acceptance records

Pending independent verification.
