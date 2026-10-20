---
schema: test-intent.v1
task: t-20261019-bd718f
author-role: orchestrator
author-session: orchestrator-8c330fb4
---

# Test-intent entries

WP3.1 follow-up for CI. Moving the exclusion matcher into ExclusionPatterns left three
package-private delegating wrappers that only tests called (ExcludeGlobs.expandBarePattern,
ExcludeMatcher.expandBarePattern, ExcludeMatcher.fromPatterns) and a stale system-access allowlist
entry (ExcludeGlobs#isWindows). The unreferenced-code and system-access gates fail on them. The
wrappers and the entry are removed; the two tests now call the shared code the wrappers forwarded to.
No acceptance record is authored here.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/indexing/ExcludeGlobsBarePatternTest.java"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Adaptation",
      "moved": ["signature"],
      "whatMoved": "The eight bare-pattern expansion checks call ExclusionPatterns.expandBarePattern directly instead of the removed ExcludeGlobs.expandBarePattern, which only forwarded to it unchanged. Every input string and every expected expansion list is unchanged; the class javadoc and the remaining ExcludeGlobs-level checks are unchanged."
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/ForceReindexScanModeTest.java"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Adaptation",
      "moved": ["signature"],
      "whatMoved": "The exclude-set supplier builds the one-pattern matcher with ExcludeMatcher.fromRawJsonStrict(\"[\\\"*.tmp\\\"]\", true) instead of the removed ExcludeMatcher.fromPatterns(List.of(\"*.tmp\"), true); both build the same ExclusionPatterns for the same single pattern on the Windows flag. The non-empty exclude set on both arms, the force/unforced arms, the recorded scan mode and every assertion are unchanged."
    }
  ]
}
```

## Acceptance records
