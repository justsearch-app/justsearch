---
schema: test-intent.v1
task: t-20261024-7d41c2
author-role: builder
author-session: funnel-timeout-builder
---

# Test-intent entries

```json
{
  "entries": [
    {
      "items": [
        "modules/dead-code-audit/src/test/java/io/justsearch/deadcode/ImportedProgram.java",
        "modules/dead-code-audit/src/test/java/io/justsearch/deadcode/ProcessLaunchFunnelTest.java",
        "modules/dead-code-audit/src/test/java/io/justsearch/deadcode/SystemAccessFunnelTest.java",
        "modules/dead-code-audit/src/test/java/io/justsearch/deadcode/WholeProgramDeadCodeTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "Process launches and system-access sites must stay on their exact ratcheted allowlists; the whole-program import must retain its coverage and introduce no new dead classes.",
      "whySurvives": "All test bodies, assertions, fixtures, allowlists, import options and audited dependencies are unchanged. The same once-per-JVM cached import runs in inherited @BeforeAll setup with an explicit 120-second timeout; each test body retains the repository default of 30 seconds. Setup failure still fails the suite. The separate import budget removes the accidental dependence on which test runs first without changing any structural predicate.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you really shouldnt have needed to ask me for this",
          "delegationLocation": "task t-20261024-7d41c2 user turn 2026-10-24",
          "delegationScope": "Task t-20261024-7d41c2: fix main's intermittent timeout of the dead-code-audit funnel tests without weakening their assertions; does not authorise changing any other test's oracle or the repo-wide timeout.",
          "choice": "Run the once-per-JVM whole-program import in inherited @BeforeAll setup bounded at 120 seconds while retaining the 30-second per-test default.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records
