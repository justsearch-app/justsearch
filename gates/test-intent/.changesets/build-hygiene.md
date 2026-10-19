---
schema: test-intent.v1
task: build-hygiene
author-role: builder
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# Test-intent entries

Partial candidate, blocked on baseline fixture placement. Execution evidence is under
`tmp/build-hygiene/` in the builder checkout; Gradle checks have not run.

```json
{
  "entries": [
    {
      "items": [
        "build-logic/src/main/kotlin/conventions/JvmBaseConventionsPlugin.kt"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "The ORT optimized-model test cache directory (JvmBaseConventionsPlugin.kt ~141) is decided at execution time, not from the daemon's java.io.tmpdir at configuration time, and by default is not under /tmp on Linux",
          "location": "task build-hygiene user request WB1"
        }
      ],
      "assessment": {
        "purpose": "Move the isolated ORT test cache off Linux tmpfs and defer directory selection to task execution.",
        "callersAndStateOwners": "All Gradle Test tasks use the convention. ORT owns regenerable optimized graphs; configuration helpers consume JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR. Windows retains the same short js-ort-test-<module> path.",
        "indirectBehaviours": "No test sources, tags, filters, timeouts, retries, assertions, model inputs or task selection changed. Cross-run optimized graph reuse and configuration-cache reuse still need Gradle execution.",
        "evidence": "Working-tree diff against origin/main contains only cache routing in the Test block. tmp/build-hygiene/after-unit.log: 529 files / 6886 tests pass. These frontend tests do not validate the Gradle change. The baseline Gradle invocation never acquired the shared lock and was cancelled before execution (tmp/build-hygiene/gradle-cancelled.txt).",
        "unresolvedRisks": "Kotlin compilation, real ORT runs, daemon tmpdir variation and configuration-cache reuse are unverified. Builder stopped after the nested before-state worktree failed the existing MCP projection test on its worktree-name assumption. Independent acceptance is intentionally absent."
      }
    },
    {
      "items": [
        "gradle.properties"
      ],
      "class": "Incidental",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "gradle.properties: drop -XX:+UseLargePages; correct the stale test-gate comments",
          "location": "task build-hygiene user request WB3"
        }
      ],
      "assessment": {
        "purpose": "Remove the large-page JVM option and describe the existing test concurrency accurately.",
        "callersAndStateOwners": "Gradle daemon reads org.gradle.jvmargs. TestGateService remains controlled by JvmBaseConventionsPlugin: 3 locally, 1 in CI, with the same project-property override.",
        "indirectBehaviours": "The only property change removes UseLargePages. Test-gate edits are comments; vfs.watch, concurrency configuration and test selection are unchanged.",
        "evidence": "git diff --check passed. Diff against origin/main shows removal of one JVM argument and correction of one comment. No Gradle build ran; tmp/build-hygiene/gradle-cancelled.txt records cancellation while waiting for the shared lock.",
        "unresolvedRisks": "Gradle build and Spotless remain unverified. No acceptance record has been written."
      }
    }
  ]
}
```

## Acceptance records
