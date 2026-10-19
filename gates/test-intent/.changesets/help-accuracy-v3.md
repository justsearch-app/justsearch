---
schema: test-intent.v1
task: t-20261019-b24879
author-role: builder
author-session: agent-a3d964ccf0b121268
---

# Test-intent entries

Help accuracy v3: the in-app help (SSOT/docs/help and the Help view) corrected against the live UI, and
HELP_FILES_VERSION bumped to v3. Fail-before evidence ran against origin/main (b823d22f2).
The scenarios were drafted by the orchestrator under the owner's overnight delegation, not agreed
in an agreement step, so each source is a delegated choice.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/HelpIngestMarkerRecoveryTest.java"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "New",
      "behaviour": "An install whose help marker says v2 (the help shipped before this correction) re-ingests the bundled help exactly once on the next start and the marker then reads v3; a following start with the v3 marker does not ingest again. Existing cases in the file are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18 ~21:20 UTC",
          "delegationScope": "all remaining work of that night, which included landing worktree-help-content-accuracy (kept in task t-20261018-9feb50 to land after the Linux port); the owner validates in the morning",
          "choice": "Scenario H2 of task t-20261019-b24879: After upgrade, the Engine re-ingests the help once; help search shows the new text and no stale copy",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/help-accuracy-v3.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/HelpIngestMarkerRecoveryTest.java#v2MarkerReingestsOnceAndWritesV3"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/DeferredForcedIngestTest.java"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "behaviour": "On a real KnowledgeServer booted over an existing index (an upgrade), the Worker is still read-only when the Head ingests the help with force_reindex. That forced submit is accepted instead of failing with a null indexing loop, and once the writer upgrade runs, the loop it creates re-extracts the file even though its modification time equals the indexed copy's, so the new help text replaces the old one.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18 ~21:20 UTC",
          "delegationScope": "all remaining work of that night, which included landing worktree-help-content-accuracy (kept in task t-20261018-9feb50 to land after the Linux port); the owner validates in the morning",
          "choice": "Scenario H2 of task t-20261019-b24879: After upgrade, the Engine re-ingests the help once; help search shows the new text and no stale copy",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/help-accuracy-v3.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/DeferredForcedIngestTest.java#forcedSubmitWhileDeferredSucceedsAndReachesTheUpgradedLoop"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/views/HelpSurface.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "New",
      "behaviour": "The Help view's shortcut table lists the bindings the app registers (Ctrl/⌘+L or /, Alt+←/→, Ctrl+D, Ctrl+Shift+A, Ctrl+Enter) and no longer claims Esc clears the search box; its troubleshooting list names operations the Library and Health views render (Recover component, Force Rebuild), not the non-existent 'Restart worker' or 'Library (Detailed)'; its FAQ no longer teaches the retired ?? prefix. The pre-existing expectations of the first test (Ctrl / ⌘ + K, J / K, and the three removed rows) are kept.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18 ~21:20 UTC",
          "delegationScope": "all remaining work of that night, which included landing worktree-help-content-accuracy (kept in task t-20261018-9feb50 to land after the Linux port); the owner validates in the morning",
          "choice": "Scenario H1 of task t-20261019-b24879: Every factual statement in the help files matches current UI and code, on Windows and Linux",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/help-accuracy-v3.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/views/HelpSurface.test.ts#lists only shortcuts that actually fire; the defunct `/` and `??` rows are gone",
        "modules/ui-web/src/shell-v0/views/HelpSurface.test.ts#troubleshooting names only operations the Library and Health views render",
        "modules/ui-web/src/shell-v0/views/HelpSurface.test.ts#FAQ no longer teaches the retired `??` prefix"
      ],
      "repaired": true
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/HelpMigrationSourceTest.java"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The fixture's startup marker literal moves from \"v2\" to \"v3\" so it is still the CURRENT help version after HELP_FILES_VERSION was bumped, which is the premise the test name states (greenIncludesResolvedHelpDespiteCurrentStartupMarker...). The final assertion compares the marker with the same literal the fixture wrote, so the observation (migration enumerates help and leaves the startup marker untouched) and every other input, precondition and expected outcome are unchanged.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "i want you to proceed with all remaining work fully autonomously. ill validate once im awake again.",
          "delegationLocation": "task t-20261018-6108b0 user turn 2026-10-18 ~21:20 UTC",
          "delegationScope": "all remaining work of that night, which included landing worktree-help-content-accuracy (kept in task t-20261018-9feb50 to land after the Linux port); the owner validates in the morning",
          "choice": "Scenario H2 of task t-20261019-b24879: After upgrade, the Engine re-ingests the help once; help search shows the new text and no stale copy",
          "label": "agent choice"
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
  "session": "verifier-help-round2-506ee9529-20261019",
  "verdict": "accept",
  "digest": "sha256:879f61956e05a394a0ed7088845cf588ba62e7d61fca69ad5e1fcd00b6ed6638",
  "note": "Reviewed final 506ee9529 test changes: New and Adaptation entries preserve existing assertions and match delegated H1/H2; deferred forced-ingest check fails on 9ab5a0428 and passes on candidate. Product H1 AND wording still fails independently."
}
```
