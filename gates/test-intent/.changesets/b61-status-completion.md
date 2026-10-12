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

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status6-independent",
  "verdict": "reject",
  "digest": "sha256:40c1102172c8c038fd1a6618d3769514d3830e98604aa57440f17c827df878f9",
  "note": "Evidence invalid on the candidate (statusCompletion.test.ts changed since its evidence run); completion now also rides the backstop counter, so this record is invalidated. Independent candidate 6377d0030: FAIL. (1) StatusPublicationBackstop counts measurement noise: live idle head advanced the counter 51 times in 10 min (GPU utilisation 0<->1, VRAM, recent* arrays, telemetry window state); the idle mounted UI made 37 fresh reads and a failed 422 action triggered a fresh read and snapshot replacement in 3 of 4 runs. (2) Metadata loss: UI kept 'unlocked' for 12 polls while canonical was locked (live, header-stripping proxy; module case fails, passes on 93479e944). (3) After a real head restart a 1000-request UI burst produced 392 refused fresh reads in 3.6 s, contradicting 'refused fresh reads do not create a loop'. Passing: lock/unlock, raw settings, second tab, approval, agent tool, chat, GPU/index, replay/reattach, restart display, full suites, architecture, regen-all, API contract, lane F. Report: F:/agent-sandbox/design-b6d536/codex/verify-status6/report.md"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-status7-independent",
  "verdict": "reject",
  "digest": "sha256:40c1102172c8c038fd1a6618d3769514d3830e98604aa57440f17c827df878f9",
  "note": "Evidence already invalid before this candidate (statusCompletion.test.ts changed since its run). Live on 6f177f241: approval, chat tool, raw plugin settings completions refresh before the next render, but completions after refused fresh reads are not refreshed while back-off lasts. Independent verify-status7 on 6f177f241: FAIL S20 / OWN7-BACKOFF. On the built UI against the running candidate head, after 40 s of refused fresh reads (6 refused, back-off works) and a successful ordinary poll, a lock by another client and, separately, the mounted UI's own successful lock action are not shown at the next render: 0 fresh reads, display unlocked while canonical is locked, caught up only 8-15 s later by ordinary polling. statusInvalidation.ts keeps backoffUntil (up to 30 s, Retry-After up to 300 s) until a successful signal-triggered fresh read; successful ordinary polls do not end it and due() gates every action and receipt signal on it. Unit form fails on 6f177f241 and passes on 823c82104."
}
```
