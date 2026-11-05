---
schema: test-intent.v1
task: t-20261103-shell
author-role: builder
author-session: a446007c-8bf5-4a65-8fe9-bb7018e32a58
---

# Two reject-endpoint tests type-check again after the advisory event type gained fields

#1139 added two frontend tests whose advisory fixtures were written against the AdvisoryEvent shape
before #1137's sibling changes made `diagnosticsLink`, `provenance`, `primaryAction`,
`primaryActionKind` and `bodyI18nKey` required; after both merged, `npm run typecheck` on main fails
(TS2352 ×3) and one test imports `OperationClient` without using it (TS6133). The fixtures now carry
the five fields as `null` (as `frameStatus.test.ts` already does) and the unused import is gone. No
input the derivation reads changes, and no assertion changes.

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/frameStatus.authorization.test.ts",
        "modules/ui-web/src/shell-v0/operations/authorizationReject.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Adaptation",
      "moved": "input re-encoding",
      "whatMoved": "frameStatus.authorization.test.ts: the three expired-advisory fixtures gain the five AdvisoryEvent fields the current type requires, each null (frameStatus.test.ts uses the same null set); deriveFrameStatus reads none of them for these cases. authorizationReject.test.ts: an unused import of OperationClient is removed. Every test, input the derivation reads and assertion is unchanged; the whole oracle is kept.",
      "sources": [
        { "kind": "owner-adopted-scenario", "task": "t-20261103-shell", "scenario": "DE8", "quote": "Denying a backend pending authorization cancels it in the backend (new reject endpoint: single-use, fail-closed, idempotent), and every window drops it from Needs you.", "label": "agent-drafted, owner-adopted" }
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
  "session": "01a1a93d-a26e-7852-b126-8e533e193eb0",
  "verdict": "accept",
  "digest": "sha256:5f6261157adf4aad5e7905fb9779a397eedb2a7bae9351245255c05d74dd3aa6",
  "note": "Typecheck fails on 51bfa7756c (TS2352 x3, TS6133) and passes after; the two files' 11 tests pass before and after; diff is the two tests and this changeset. Correction to the entry text: the derivation does read some of the added fields on the advisory-only path; three extra cases (advisory-only, GATED, EXPIRED) show identical outputs with the fields missing or null, so the oracle is kept. Transcribed by the orchestrator from the verifier's answer (Codex sandbox)."
}
```
