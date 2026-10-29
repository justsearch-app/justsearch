---
schema: test-intent.v1
task: t-20261029-66fde4
author-role: builder
author-session: 01a188d3-e9e3-70d3-b48a-97f7dd738d4f
---

# Test-intent entries

Skeleton written by the test-intent gate. Rules: docs/reference/testing/test-intent.md.
Give every entry a class and the fields that class needs; group items that share one warrant.
The acceptor (not the author) appends the acceptance record under the heading at the end.

```json
{
  "entries": [
    {
      "items": [
        "build-logic/src/main/kotlin/conventions/JvmBaseConventionsPlugin.kt"
      ],
      "class": "Structural rule kept",
      "rule": "Every Test task retains the same excludeTestsMatching calls: PR flake identifiers from scripts/ci/pr-flakes.tsv only on non-Windows hosts with JUSTSEARCH_PR_CI=true, and pre-push identifiers from scripts/dev/prepush-excluded-tests.tsv only with JUSTSEARCH_PREPUSH=true. With neither flag there are no exclusions from these lists.",
      "whySurvives": "The environment predicates, list paths, gradle-tab prefix filter, identifier column and per-task excludeTestsMatching call are unchanged. Only pre-push exclusion logging changes from lifecycle to info; PR flake logging remains at lifecycle. The java-prepush launcher prints the gradle-row identifiers once before Gradle starts, including in dry-run mode, without changing the exclusion list or selection.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "takeover all of them",
          "location": "task t-20261029-66fde4 request"
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
  "session": "acceptor-66fde4-claude-2",
  "verdict": "accept",
  "digest": "sha256:01006ea592db5ce26bc61d1ff90b226b4663452f001cad35bca21bdd86dd44a6",
  "note": "Structural rule kept: env predicates, list paths, gradle-row filter, identifier column and excludeTestsMatching unchanged; only the pre-push SKIP log moves to info, PR flake stays lifecycle"
}
```
