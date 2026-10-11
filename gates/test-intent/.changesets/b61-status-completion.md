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
