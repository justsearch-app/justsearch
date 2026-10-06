---
schema: test-intent.v1
task: t-20261005-b6d536
author-role: builder
author-session: builder-tooling-evidence-lightgbm
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
      "class": "Incidental",
      "assessment": {
        "purpose": "Give every Test task on Windows a per-worktree LIGHTGBM_NATIVE_LIB_PATH (build/lightgbm-native, DLLs copied from the lightgbm4j jar before the run) so concurrent runs from different worktrees stop sharing the DLLs in %TEMP%. A developer-set variable wins. No test selection, filter, tag or assertion changes.",
        "callersAndStateOwners": "Every Test task via JvmBaseConventionsPlugin. lightgbm4j LGBMBooster.loadNative reads the variable and loads lib_lightgbm.dll and lib_lightgbm_swig.dll from that folder without extracting; only app-services tests load LightGBM. The folder lives under the project build directory.",
        "indirectBehaviours": "The variable is not a Gradle input, so cache keys are unchanged. Non-Windows hosts and a developer-set variable keep the old behaviour. The doFirst copies only when a file is missing or its size differs.",
        "evidence": "./gradlew.bat :modules:app-services:test passed with the LightGBM tests (LambdaMartTrainingTest, LambdaMartTrainerTest, LambdaMartRerankerTest) logging 'LIGHTGBM_NATIVE_LIB_PATH is set: loading <worktree>/modules/app-services/build/lightgbm-native/lib_lightgbm.dll'.",
        "unresolvedRisks": "Two Gradle runs of the same worktree at once share the folder; the size check avoids rewriting, but a loaded DLL cannot be replaced."
      },
      "sources": [
        {
          "kind": "owner-words",
          "location": "task t-20261005-b6d536 request",
          "quote": "Tooling fixes: run-evidence unlinks the node_modules junction before the before-worktree is removed; a per-worktree LightGBM native path in the test task."
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
  "session": "verify-tool-1",
  "verdict": "accept",
  "digest": "sha256:fd2bc44cf72c8582ba5eb31b1db3d5341305adb5c3311acd87a40badea02f19e"
}
```
