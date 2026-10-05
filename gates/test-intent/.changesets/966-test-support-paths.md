---
schema: test-intent.v1
task: t-20261005-904643
author-role: builder
author-session: builder-966-repair-1
---

# Test-intent entries

Tempdoc 966 D1 scope repair: frontend `src` files that only tests import are test infrastructure
whatever their name, so the test-intent gate flags edits to them. The committed list
`gates/test-intent/test-support-paths.v1.json` names them and is itself a watched baseline. A static
import scan (`scripts/governance/gates/test-intent/test-support.mjs`) seeded it; the gate's unit test
recomputes the scan and fails when a file the scan finds is missing from the list.

```json
{
  "entries": [
    {
      "items": [
        "gates/test-intent/test-support-paths.v1.json"
      ],
      "class": "Structural rule kept",
      "rule": "The test-intent gate's scope: a frontend src file listed in gates/test-intent/test-support-paths.v1.json (at the base or at the head) is test code, and an edit to it is a flagged item that needs an entry. The list is seeded with the 11 modules/ui-web/src files that only tests (or other test-only files) import, found by a static import scan from the module's production roots and cross-checked against TypeScript's preProcessFile import parser (the same 11 files).",
      "whySurvives": "The predicate is stricter, never weaker: the list only adds files to the gate's scope, so everything flagged before is still flagged and the listed helpers are flagged too. Removing a path from the list is itself a flagged edit of this watched baseline, and the list at the base still counts, so dropping a helper in the PR that edits it still flags the edit. The unit test fails when the scan finds a test-only file missing from the list, so the list cannot fall behind the code.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-904643 request",
          "quote": "trust in the tests, to ensure the dont protect slop code"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-904643",
          "scenario": "S1",
          "quote": "...fails the required \"Public claims\" job unless a changeset covers each flagged item with a class and a source.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    }
  ]
}
```

## Acceptance records
