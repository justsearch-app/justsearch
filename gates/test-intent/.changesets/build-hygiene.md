---
schema: test-intent.v1
task: build-hygiene
author-role: builder
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# Test-intent entries

Execution evidence is under `tmp/build-hygiene/` in the builder checkout.
Candidate and baseline execution evidence is complete. Native Windows execution
was unavailable. Independent acceptance is intentionally absent.

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
          "location": "task build-hygiene request"
        }
      ],
      "assessment": {
        "purpose": "Move the isolated ORT test cache off Linux tmpfs and defer directory selection to task execution.",
        "callersAndStateOwners": "All Gradle Test tasks use the convention. ORT owns regenerable optimized graphs; configuration helpers consume JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR. Windows retains the same short js-ort-test-<module> path.",
        "indirectBehaviours": "No test sources, tags, filters, timeouts, retries, assertions, model inputs or task selection changed. The isolated cache now belongs to each Linux module build directory; Windows keeps its prior short temporary path. Directory selection runs in Test.doFirst and is not a Test cache input.",
        "evidence": "tmp/build-hygiene/ort-probe-summary.json and before/after-ort-*-xml: the new Linux build-directory assertion fails before for the intended reason; changing only daemon java.io.tmpdir explicitly invalidates baseline configuration. Both candidate invocations execute and pass both checks (zero skips); the second prints Reusing configuration cache and REUSED for the same optimized graph with the same SHA-256 and sentinel modification time after daemon tmpdir A changes to B. after-existing-ort-tests-summary.json: all 43 existing OnnxSessionCacheTest/OrtOptimizedModelStoreTest checks execute and pass with zero skips. after-build-spotless.log: build -x test -PskipWebBuild=true spotlessCheck exits 0. The candidate ORT cache report records zero problems and zero java.io.tmpdir inputs. The baseline real CPU session also executes and passes twice, reusing the same graph in its second test JVM. No preservation failure remains.",
        "unresolvedRisks": "Native Windows execution is unavailable; the preserved short-path branch is checked by reading code. Upstream license-report configuration cache incompatibility remains outside this convention change. Independent acceptance is intentionally absent."
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
          "location": "task build-hygiene request"
        }
      ],
      "assessment": {
        "purpose": "Remove the large-page JVM option and describe the existing test concurrency accurately.",
        "callersAndStateOwners": "Gradle daemon reads org.gradle.jvmargs. TestGateService remains controlled by JvmBaseConventionsPlugin: 3 locally, 1 in CI, with the same project-property override.",
        "indirectBehaviours": "The only property change removes UseLargePages. Test-gate edits are comments; vfs.watch, concurrency configuration and test selection are unchanged.",
        "evidence": "git diff --check passed. Diff against origin/main removes one JVM argument and corrects one comment. tmp/build-hygiene/after-build-spotless.log: build -x test -PskipWebBuild=true spotlessCheck exits 0. after-install-1-fixed.log and after-install-2.log: strict configuration-cache installDist passes and the second invocation reuses the entry after PATH changes. after-bundle-1.log and after-bundle-2.log: strict bundleSidecar passes and reuses its entry. Both stale-jar checks and all 43 selected existing ORT tests pass.",
        "unresolvedRisks": "No Windows JVM execution occurred. License-report configuration cache incompatibility remains in upstream plugin 3.1.4 (81 problems before and after); its reports generate successfully with --no-parallel. No acceptance record has been written."
      }
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-build-hygiene-cc6d91b2",
  "verdict": "accept",
  "digest": "sha256:d85510396bb8b6e3365a0e99b7a1ca7946fc40b15799a94ff3c419bfd831be75",
  "note": "Verifier reproduced: ORT test cache off /tmp, tmpdir no longer a CC input (mutation caught), no tests changed, ORT/cross-module tests and build+spotless pass"
}
```
