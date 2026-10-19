---
schema: test-intent.v1
task: ci-critical-path
author-role: builder
author-session: 01a1520d-5a3b-7373-9834-7dd1b5fb7221
---

# Test-intent entries

```json
{
  "entries": [
    {
      "items": [
        ".github/workflows/ci.yml",
        "scripts/ci/unit-test-shard-policy.v1.json",
        "gradle.properties"
      ],
      "class": "Adaptation",
      "moved": [
        "split",
        "instrumentation"
      ],
      "whatMoved": "The existing app-ui module tasks and Windows-native Node, conformance, JVM and release checks move to parallel jobs. Every input, assertion, test budget, retry setting, platform selection and conformance repetition stays intact. Required check names become aggregates requiring success from both expected parts. Evidence artifacts gain part and attempt identity. This throwaway run places the still-blocking test-intent gate last in Public claims so pending independent acceptance does not prevent other checks from producing evidence, and disables Gradle build and setup caches solely to measure cold execution; no task or test selection changes. CP2 evidence pending hosted comparison of run 39182583158 and the candidate.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Nothing may be weakened to save time.",
          "location": "task ci-critical-path request"
        }
      ]
    }
  ]
}
```

CP2 execution evidence: pending hosted candidate; before run https://github.com/justsearch-app/justsearch/actions/runs/39182583158. Comparison script and full output will be in tmp/cp/.

## Acceptance records
