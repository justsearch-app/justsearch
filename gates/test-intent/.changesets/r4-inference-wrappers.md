---
schema: test-intent.v1
task: t-20261018-6108b0
author-role: builder
author-session: 01a181de-5332-7be0-a7af-2f3904d4d6c0
---

# Test-intent entries

```json
{
  "entries": [
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceHttpHelpersTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerUtilsTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerUsageParsingTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/OnlineModeOpsTest.java",
        "modules/app-inference/src/test/java/io/justsearch/app/inference/ServerPropsOpsTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "split",
        "signature"
      ],
      "whatMoved": "All 31 moved parsing test methods retain their inputs, expected outcomes and display names. InferenceLifecycleManagerUtilsTest.AsIntOrNullTests moves to InferenceHttpHelpersTest; AsPositiveIntTests moves to ServerPropsOpsTest.AsPositiveIntTests. The four unique LlamaServerPropsParsingTest cases move to ServerPropsOpsTest, and both LlamaServerUsageParsingTest cases move to OnlineModeOpsTest. Calls now target their production owners directly. The displaced test-only forwarding helpers and empty usage test class are deleted. Both props-update wrappers and InferenceLifecycleManagerPropsInsightsTest remain untouched: its expected-versus-actual build assertions observe manager accessors and the staging marker, beyond the ServerPropsOps interface.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "well do whatever you think is best",
          "location": "task t-20261018-6108b0 user turn 2026-10-28 (row 4 recommendation: approve)"
        }
      ]
    },
    {
      "items": [
        "modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerPropsParsingTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "well do whatever you think is best",
          "location": "task t-20261018-6108b0 user turn 2026-10-28 (row 4 recommendation: approve)"
        },
        {
          "kind": "removal",
          "removed": [
            "modules/app-inference/src/main/java/io/justsearch/app/inference/InferenceLifecycleManager.java#extractContextTokensFromProps"
          ]
        }
      ],
      "conflictSearch": "Decision records under docs/decisions and governance/gates references searched for the affected tests: no conflicting requirement found. The removed wrapper is no longer a test target. Four unique assertions move unchanged to ServerPropsOpsTest (the Adaptation entry). extractTopLevelNctx is duplicate coverage of ServerPropsOpsTest.updateFromPropsBestEffort_extractsContextTokens: both use the exact root {\"n_ctx\":2048} and expect 2048; the surviving test observes the production props observer after parsing, rather than the removed manager delegate return."
    },
    {
      "items": [
        "modules/app-launcher/archunit_store/fbfdda09-fb1d-494e-a2a6-e593ce75d5a6"
      ],
      "class": "Structural rule kept",
      "rule": "Non-public production methods must have a production caller unless already frozen.",
      "whySurvives": "Remove exactly the four frozen InferenceLifecycleManager entries whose delegates this change deletes: extractUsageFromChatChunk, asIntOrNull, extractContextTokensFromProps, asPositiveInt. The predicate and remaining freeze entries are unchanged; no new exemption is added. Keep the updateFromPropsBestEffort freeze entry because the approved fallback retains that delegate and its LlamaServerOps target.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "well do whatever you think is best",
          "location": "task t-20261018-6108b0 user turn 2026-10-28 (row 4 recommendation: approve)"
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
  "session": "01a181e6-db44-7e60-b389-85b7948482a2",
  "verdict": "accept",
  "digest": "sha256:ece9f6a6fc31a0ec8db2ab440f823a31b16342440aa93b33ab91b69122774f85"
}
```

```json
{
  "kind": "audit",
  "role": "reviewer",
  "session": "01a181f7-6061-76e1-b075-528366567bc8",
  "verdict": "accept",
  "digest": "sha256:ece9f6a6fc31a0ec8db2ab440f823a31b16342440aa93b33ab91b69122774f85"
}
```
