---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: b60-vdu-preview-activity
---

Deliberately requested operation previews, transcript exports, history inspection and other client reads advance shared user activity. Automatic renders, timers, streams and status reads retain their declared observer behavior. The caller audit is recorded in F:/agent-sandbox/design-b6d536/codex/b60-vdu-preview-activity/report.md. Independent acceptance remains pending; the existing verifier rejection is preserved.

```json
{
  "entries": [
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/api/IndependentDeclaredActivityTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "Each audited deliberately requested GET advances activity through real HTTP production admission filters with and without internal attribution hints; the operation-preview assertion and all independent verifier assertions are retained. Automatic trace polling is additionally preserved. The same check fails by activity-not-advanced assertion on b45615514.",
      "sources": [{"kind":"owner-words","quote":"anything else a client calls deliberately is USER_ACTION","location":"task t-20261005-b6d536 request"}],
      "evidence": "gates/test-intent/evidence/b60-vdu-preview-activity.json",
      "newChecks": ["modules/ui/src/test/java/io/justsearch/ui/api/IndependentDeclaredActivityTest.java"]
    },
    {
      "items": ["modules/ui/src/test/java/io/justsearch/ui/api/DeclaredRequestActivityTest.java"],
      "class": "New",
      "repaired": true,
      "behaviour": "The exhaustive registered-route HTTP check expects the audited deliberate GET operations to advance activity. Its accepted requests, production registration and admission, observer expectations, declaration closure and capacity checks are retained. The current declaration oracle also includes the b63 caller audit; its proof is refreshed against 55e4dd392 in the b63 evidence run.",
      "sources": [{"kind":"owner-words","quote":"Change only classifications that the rule gets wrong; keep the startup validator and the b57 design.","location":"task t-20261005-b6d536 request"}],
      "evidence": "gates/test-intent/evidence/b63-vdu-observation-justified.json",
      "newChecks": ["modules/ui/src/test/java/io/justsearch/ui/api/DeclaredRequestActivityTest.java"]
    }
  ]
}
```

## Acceptance records

Pending independent verifier review.

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-vdu4-codex-20261011",
  "verdict": "reject",
  "digest": "sha256:ddd1b26cbeccab69e1bf43d7ac4fa4a6457f82d6a9703423e45926356ed6b92d",
  "note": "Independent round 4: preview repair passes assertion reproduction and native capture/interruption/recovery. Candidate fails the complete declared-activity requirement: deliberate GET /api/diagnostics/ingestion/recent remains observation with no unattended consumer; own assertion fails both arms and the real accepted pass completes 1 unit instead of interrupting. Loaded-idle automatic VDU veto is pre-existing with no supplied S7/E exemption. Full evidence, completed native positives, gate failures and limits: F:/agent-sandbox/design-b6d536/codex/verify-vdu4/report.md. CPU native check pending shared-stack release when this verdict was recorded."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verify-vdu5-codex-20261011",
  "verdict": "reject",
  "digest": "sha256:e68b19a0639c7a8526969736b9c613c55ab1c3837d4ba6594cdd252c4a45e6a8",
  "note": "Independent round 5: FAIL. Deliberate GET /api/chat/sessions and GET /api/indexing-jobs/failed/by-prefix remain observation with no unattended caller. Independent production-filter assertions fail and actual requests immediately after accepted capture do not interrupt: each operation completes one unit. Startup accepts an obsolete justification after removing its route. Five consumer justifications name sources that do not perform their claimed requests. Ledger and preview repair, real accepted chat and extra deliberate requests interrupt and recover; GPU-on completion, raster search and protocols pass. Automated gates, safety exclusions and remaining native checks/limits are recorded as they complete at F:/agent-sandbox/design-b6d536/codex/verify-vdu5/report.md. No product/test edits, commits or pushes."
}
```
