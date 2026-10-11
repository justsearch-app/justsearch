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
      "behaviour": "The exhaustive registered-route HTTP check now expects the audited deliberate GET operations to advance activity. Its accepted requests, production registration and admission, observer expectations, declaration closure and capacity checks are retained.",
      "sources": [{"kind":"owner-words","quote":"Change only classifications that the rule gets wrong; keep the startup validator and the b57 design.","location":"task t-20261005-b6d536 request"}],
      "evidence": "gates/test-intent/evidence/b60-vdu-preview-activity.json",
      "newChecks": ["modules/ui/src/test/java/io/justsearch/ui/api/DeclaredRequestActivityTest.java"]
    }
  ]
}
```

## Acceptance records

Pending independent verifier review.
