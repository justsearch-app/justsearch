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
