---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b59-status-after-action
---

The actual Brain handlers use the production canonical status poller. All four repaired cases fail by assertion on 1939ad02c. The operation spy retains execution, unlike a stubbed successful operation. Transport tests cover completion receipts, concurrent batching and real admission waiting. Independent acceptance is pending.

```json
{
  "entries": [
    {
      "items": ["modules/ui-web/src/shell-v0/views/BrainSurface.after-action.test.ts"],
      "class": "New",
      "repaired": true,
      "behaviour": "Successful chat disable and GPU settings completion refresh and publish canonical status before the next scheduled poll through the real action handlers and shared production poller. No locally assumed status is published. Chat capability and Health engine labels follow the canonical observation while the legacy inference poll is stale.",
      "sources": [{"kind": "owner-words", "quote": "Once an action that changes status-surface state succeeds, every UI surface that shows that state reflects the post-action state without waiting for the scheduled poll, read from the same canonical status that dev tools and scripts read", "location": "task t-20261005-b6d536 request (b59 builder role contract, task item 5 / S20)"}],
      "evidence": "gates/test-intent/evidence/b59-status-after-action-repaired.json",
      "newChecks": ["modules/ui-web/src/shell-v0/views/BrainSurface.after-action.test.ts"]
    },
    {
      "items": ["modules/ui-web/src/api/statusInvalidation.test.ts"],
      "class": "New",
      "repaired": false,
      "behaviour": "The shared invalidation mechanism observes operation and undo receipts, validated settings COMPLETE, and direct runtime/indexing/lifecycle writes through production transports and canonical polling. Concurrent completions coalesce, 429 keeps the fresh read inside admission, and failed or read-only actions do not invalidate status.",
      "sources": [{"kind": "owner-words", "quote": "a fresh read must not bypass admission or produce a storm (coalesce concurrent fresh requests)", "location": "task t-20261005-b6d536 request (b59 builder role contract, preservation constraint)"}],
      "evidence": "gates/test-intent/evidence/b59-status-after-action-new.json",
      "newChecks": ["modules/ui-web/src/api/statusInvalidation.test.ts"]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status3-independent",
  "verdict": "reject",
  "digest": "sha256:4f4e0d07998bc94a6dd09d1572d435d316ab894cdc51b157de86df90b0396fe5",
  "note": "FAIL S20 OWN-APPROVAL-COMPLETION: real approved pending execution returns Settings committed but next render remains Online/Shut Down AI while canonical status is OFF; no fresh read. Independent new checks pass 35/35 and fail before by assertion; full suites pass, 239/239 canonical E2 polls succeed under foreign load. Report F:/agent-sandbox/design-b6d536/codex/verify-status3/report.md; named unverified cases and E3 chunk-progress failure retained."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status4-independent",
  "verdict": "reject",
  "digest": "sha256:4f4e0d07998bc94a6dd09d1572d435d316ab894cdc51b157de86df90b0396fe5",
  "note": "Independent candidate 8fb8c7c4b: b59 completion remains incomplete for raw plugin settings POST; native COMPLETE receipt leaves UI Online/Shut Down AI while canonical status is OFF, with zero fresh reads. Ordinary actions and MCP approval pass, but all-path S20 fails. Evidence: F:/agent-sandbox/design-b6d536/codex/verify-status4/report.md. Qualified second E2 window: 239/239 status polls, max full-body 38.7 ms, at most one Gradle invocation; foreign load, no quiet-machine claim."
}
```
