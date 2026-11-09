---
schema: test-intent.v1
task: t-20261105-sqpath
author-role: orchestrator
author-session: 01a1b0c3-481a-7d22-87d4-0ea0ad710b2c
---

# Test-intent entries

Existing preset tests keep their full cases and assertions while passing the same eligibility value directly. New CPU-default regression checks live in isolated test sources.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeHttpApiAdapterHarmfulCombinationsTest.java",
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/PipelineConfigPresetExpansionTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "Pass each existing RerankerConfig argument enabled() value directly to the retained boolean preset method after removing the unused package-private record adapters. The removed adapters passed this same enabled() value to the same boolean method. Every input configuration, fixture, assertion, scenario, timeout and expected result remains unchanged."
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "c1-final-verifier-20261109-c20ce777",
  "verdict": "accept",
  "digest": "sha256:a607defb2c6c8bf1f9fc8582293c0f9a544f5c4442559a93bd0a8636772264db",
  "note": "Independently reviewed the whole changeset and both covered existing test files against 3d21137a48511877301888dc52d9f37ab1e09437: identical configurations, cases, assertions and expected results; only existing enabled() input encoding changes with the package-private adapter removal. Original-interface baseline tests and adapted candidate tests pass. Selected preservation and regression before/after evidence retained separately; this acceptance covers test intent and grants no semantic runtime or publication authority."
}
```
