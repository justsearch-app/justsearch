---
schema: test-intent.v1
task: t-20261029-85e079
author-role: builder
author-session: 2d3ae047-6cd4-488f-a289-9e5e31f15c26
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
      "change": "modified",
      "kind": "watched-build-config",
      "class": "Structural rule kept",
      "rule": "Test selection under CI and PR CI is unchanged: the pr-flakes exclusion still applies only when JUSTSEARCH_PR_CI=true on a non-Windows host, with the same identifiers from scripts/ci/pr-flakes.tsv; no other filter changes. The new list scripts/dev/prepush-excluded-tests.tsv is read only when JUSTSEARCH_PREPUSH=true, which only scripts/dev/java-prepush.mjs sets.",
      "whySurvives": "The loop now iterates over the lists that apply instead of one inline list; for CI runs (no JUSTSEARCH_PREPUSH) the list set equals the old one. The pre-push exclusion narrows only the local pre-push run, which before this change ran no integration tests at all, so no previously executed check stops running anywhere.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "proceed accordingly and you have my yes",
          "location": "task t-20261029-85e079 request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "proceed accordingly and you have my yes",
          "delegationLocation": "task t-20261029-85e079 request",
          "delegationScope": "pre-push check lists include the integration suites a change reaches, so a break like #1011 is caught before push",
          "choice": "The local pre-push run leaves out LinuxWatchedDriveReturnIntegrationTest, which mounts real drives in the user's desktop session and is red locally for a known, separately owned reason; CI keeps running it.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records
