---
schema: test-intent.v1
task: analyzer-bundled-warning
author-role: builder
author-session: codex-analyzer-cache-silent
---

# Test-intent entries

Preserve the adapter suite's existing silence while allowing the new registry logging test to
capture its own WARN. The independent verifier supplies acceptance after checking the final candidate.

```json
{
  "entries": [
    {
      "items": [
        "modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/IndeterminateModelParityTest.java"
      ],
      "class": "Structural rule kept",
      "rule": "Indeterminate model parity coverage keeps the same branch and model-input expectations.",
      "whySurvives": "Only the Javadoc location of the WARN-text check changes. Test bodies, inputs, assertions, setup, cleanup and selection are unchanged.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task analyzer-bundled-warning request beginning 'Thanks, that settles it'",
          "quote": "Update the comment at IndeterminateModelParityTest.java:37 so it no longer says InvariantSuiteIT's module is the only one with logback."
        }
      ]
    },
    {
      "items": [
        "modules/adapters-lucene/src/test/resources/logback-test.xml"
      ],
      "class": "Adaptation",
      "moved": ["instrumentation"],
      "whatMoved": "Supply Logback test configuration with root OFF and no appenders. Existing adapter tests retain silent logging. SsotAnalyzerRegistryLoggingTest alone installs an appender and enables its logger, restoring its prior level and removing the appender afterwards. No existing test oracle, input, precondition, fault injection, tag or execution condition changes.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task analyzer-bundled-warning request beginning 'Thanks, that settles it'",
          "quote": "Keep the rest of that module's tests silent as they are today: add src/test/resources/logback-test.xml with the root logger OFF and no appenders."
        }
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
  "session": "codex-verifier-analyzer-1",
  "verdict": "accept",
  "digest": "sha256:0d7b09fec40eb1be7a64f4d03e5039f2e260ec31b1923bf0a3b95c419beaefe3",
  "note": "L1-L4 independently verified at 15bf78b5c8 against 2461a585f0; tmp/verify-analyzer-evidence contains scripts, fail-before assertions, full-suite XML, output comparison and timing."
}
```
