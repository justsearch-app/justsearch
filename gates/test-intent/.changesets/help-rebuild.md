---
schema: test-intent.v1
task: t-20261112-help-after-rebuild
author-role: builder
author-session: cb931fec-4aa3-4c83-8112-969f6334cf08
---

# Test-intent entries

New checks in `KnowledgeServerRebuildHelpTest` for the bundled help documents after a user-started
rebuild (a recorded candidate without an installer configuration). They run a real
`KnowledgeServer` with a real index, queue and generation state; the recorded owner is a test
double with an empty settings projection. Repaired checks fail by assertion at 07b719af28 (help is
absent from the promoted generation); preservation checks pass there and on the candidate.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRebuildHelpTest.java"
      ],
      "class": "New",
      "behaviour": "H1/H2/H4/N3/N4/N5 (repaired): after a user-started rebuild (user_requested_rebuild and user_requested_bulk_reindex) with zero watched roots completes, the promoted generation serves each top-level Markdown help file exactly once with collection justsearch-help, without a restart, a marker edit or a write to the help files; ignored .txt and nested Markdown stay absent; an excluded help file stays out, live and after restart; restarts keep the help documents without duplicates. A BUILDING generation recorded before help was admitted, a process stop before the pointer commitment and one after it each recover to a generation with the help documents exactly once. At 07b719af28 each fails by assertion: help is missing from the rebuilt generation.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "do that",
          "location": "task t-20261112-help-after-rebuild request"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "do that",
          "delegationLocation": "task t-20261112-help-after-rebuild request",
          "delegationScope": "The repair the owner answered with \"do that\": a report that said \"Help documents after a rebuild: they disappear after a rebuild you start yourself. That was already broken before this fix.\" The scenarios and design are orchestrator choices under that delegation, not owner words or owner adoption.",
          "choice": "Reuse the existing installer help-enumeration path for every recorded BUILDING generation, live and recovered; keep native migration handling, filtering, exclusions and promotion checks; no marker format change and no help-marker bump (v6 is unreleased and already re-submits help for installs from releases). Settled scenarios, verbatim: H1 \"After a user-started rebuild (core.rebuild-index via POST /api/indexing/migration/start, and core.bulk-reindex) completes with full coverage and the new generation serves, the eligible bundled help documents are in it: the expected help document identities (top-level .md files of the help folder), in the help collection, with current-policy labels, found by a help query (e.g. \"keyboard shortcuts\"). No restart needed. Also with zero watched roots.\" H2 \"After that rebuild, restarts keep them: no loss, no duplicate identities, not relabelled as user content (also when a watched folder is an ancestor of or coincides with the help folder).\" H4 \"Recovery: a crash (process kill) during a rebuild, (a) before help is admitted to the building generation, (b) after admission before processing, (c) around pointer commitment; each recovered successful rebuild contains help without duplicates. Cancellation before pointer commitment keeps the serving generation and its help.\" N3 \"Exclusions: excluding one help file, or the whole help folder, keeps it out after a rebuild, immediately and after restart.\" N4 (part) \"ignored .txt and nested Markdown in the help folder stay absent.\" N5 \"Help files are never written or moved.\"",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/help-rebuild-repaired.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRebuildHelpTest.java#userRebuildPromotesBundledHelpWithoutRestart",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRebuildHelpTest.java#rebuiltHelpSurvivesRestartsWithoutDuplicates",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRebuildHelpTest.java#excludedHelpStaysOutOfTheRebuild",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRebuildHelpTest.java#resumedBuildingGenerationAdmitsHelpBeforePromotion",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRebuildHelpTest.java#crashAroundPointerCommitmentKeepsHelp"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRebuildHelpTest.java"
      ],
      "class": "New",
      "behaviour": "H4/H5 (preservation): help rows already admitted to the BUILDING generation before a stop are served exactly once after the resumed rebuild; a cancellation at the pointer commitment keeps the serving generation and its help; a rebuild with the help folder absent or empty completes with an empty generation.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do that",
          "delegationLocation": "task t-20261112-help-after-rebuild request",
          "delegationScope": "The repair the owner answered with \"do that\": a report that said \"Help documents after a rebuild: they disappear after a rebuild you start yourself. That was already broken before this fix.\" The scenarios and design are orchestrator choices under that delegation, not owner words or owner adoption.",
          "choice": "Settled scenarios, verbatim: H4 \"Recovery: a crash (process kill) during a rebuild, (a) before help is admitted to the building generation, (b) after admission before processing, (c) around pointer commitment; each recovered successful rebuild contains help without duplicates. Cancellation before pointer commitment keeps the serving generation and its help.\" H5 \"Help source edge cases: help folder absent or empty: the rebuild completes (no help required); help folder present but not fully enumerable: existing failure handling (serving generation kept, as native migration does today).\"",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/help-rebuild-preservation.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRebuildHelpTest.java#resumedBuildingGenerationWithAdmittedHelpHasNoDuplicates",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRebuildHelpTest.java#refusedRebuildKeepsServingHelp",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRebuildHelpTest.java#rebuildWithoutHelpSourceStillCompletes"
      ]
    }
  ]
}
```

## Acceptance records
