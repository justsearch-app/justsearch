---
schema: test-intent.v1
task: launcher-test-heap
author-role: orchestrator
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# Test-intent entries

Main's "Unit tests (app-ui-services)" job fails since #910 (c949c4dab): `:modules:app-launcher:ciUnitTest1` runs out of its 384m test heap while UnreferencedCodeTest imports every production class. The change raises the heap of app-launcher's test tasks to 768m. No test, selection, filter, tag or assertion changes.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-launcher/build.gradle.kts"
      ],
      "change": "modified",
      "kind": "watched-build-config",
      "class": "Adaptation",
      "moved": [
        "instrumentation"
      ],
      "whatMoved": "The test JVM's maximum heap for app-launcher's test and ciUnitTest tasks rises from the convention's 384m to 768m, so the whole-program ArchUnit import fits (it ran out of heap on main after #910). Every test, selection, filter, tag, store and assertion is unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "you can work autonomously, no need to ask me for these type of thigns",
          "delegationLocation": "task t-20261021-93a012 user turn 1",
          "delegationScope": "Fix-or-defer decisions on defects found during assigned work; here, the out-of-heap failure on main that followed the merge of this session's #910.",
          "choice": "Raise app-launcher's test heap to 768m (as modules/dead-code-audit does with 2g for the same kind of import) rather than narrow the import, which would weaken the unreferenced-code rule.",
          "label": "agent choice"
        }
      ]
    }
  ]
}
```

## Acceptance records
