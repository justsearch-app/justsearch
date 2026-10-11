---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b61-status-approval
---

Execution receipts and streamed tool completions reach the production canonical status poller. The checks drive the mounted typed authorization host and pending bridge, the host AI reader, direct/resumed/reattached streams, trusted plugin fetch and invocation, virtual commands, consented retry and concurrent approvals. The unchanged operation, settings and after-action preservation checks retain their assertions. Independent acceptance is pending.

```json
{
  "entries": [
    {
      "items": ["modules/ui-web/src/api/statusCompletion.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "A successful server-executed pending approval, state-changing streamed tool completion or raw plugin operation request refreshes the production canonical observation before completion is delivered. Unknown operation identity after reconnect is observed conservatively. Failed, skipped and known read-only completions do not refresh; receipts remain readable and concurrent completions coalesce. Existing invocation, consented retry and virtual-command paths retain canonical completion.",
      "sources": [{"kind": "owner-words", "quote": "Every path by which an action that changes status-surface state completes successfully in or through this UI must reach the same completion mechanism", "location": "task t-20261005-b6d536 request (b61 builder role contract, task item 5 / S20)"}],
      "evidence": "gates/test-intent/evidence/b61-status-completion.json",
      "newChecks": ["modules/ui-web/src/api/statusCompletion.test.ts"]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status4-independent",
  "verdict": "reject",
  "digest": "sha256:38691478f3c19fc54445850d1b3a70596e86cf3e03fe9b7e4f4c8df901aa5346",
  "note": "Independent candidate 8fb8c7c4b: FAIL S20/OWN-RAW-SETTINGS on mounted native UI (COMPLETE chat disable, next captured render Online/Shut Down AI, canonical OFF, zero fresh reads); OWN-REATTACH-READONLY passes 3e64a384b and fails 8fb by assertion, reproduced with controlled SSE on built UI. Evidence: F:/agent-sandbox/design-b6d536/codex/verify-status4/report.md. Qualified second E2 window: 239/239 status polls, max full-body 38.7 ms, at most one Gradle invocation; foreign load, no quiet-machine claim."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status5-independent",
  "verdict": "reject",
  "digest": "sha256:40c1102172c8c038fd1a6618d3769514d3830e98604aa57440f17c827df878f9",
  "note": "Current ea0c311cb supersedes this operation-classified completion mechanism. Its historical evidence cannot establish the server-owned counter design; naming this invalidated evidence is necessary to avoid carrying its acceptance forward. Raw plugin settings, approval and exercised ordinary completion paths now pass, but all-path S20 remains rejected by the native encryption-owner omission and mounted missing-metadata rollback. Historical verifier rejections retained. Current independent report: F:/agent-sandbox/design-b6d536/codex/verify-status5/report.md."
}
```
