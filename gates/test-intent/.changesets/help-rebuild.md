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
double with an empty settings projection. Repaired checks fail by assertion on origin/main;
preservation checks pass there and on the candidate.

```json
{
  "entries": [
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRebuildHelpTest.java"
      ],
      "class": "New",
      "behaviour": "H1/H2/H4/H5/N3/N4/N5 (repaired): after a user-started rebuild (user_requested_rebuild and user_requested_bulk_reindex) with zero watched roots completes, the promoted generation serves each top-level Markdown help file exactly once with collection justsearch-help, without a restart, a marker edit or a write to the help files; ignored .txt and nested Markdown stay absent; an excluded help file stays out, live and after restart; restarts keep the help documents without duplicates. A BUILDING generation recorded before help was admitted, and a process stop before or after the pointer commitment, each recover to a generation with the help documents exactly once. An unreadable help folder fails the rebuild and the serving generation stays. On origin/main each fails by assertion: help is missing from the rebuilt generation, or the rebuild with unreadable help promotes.",
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
          "delegationScope": "The repair the owner answered with \"do that\": a report that said \"Help documents after a rebuild: they disappear after a rebuild you start yourself. That was already broken before this fix.\" The scenarios and design below are orchestrator choices under that delegation, not owner words.",
          "choice": "User-started rebuilds (rebuild-index and bulk reindex), live and recovered after a restart, replay the bundled help folder through the existing installer help-enumeration path, unless a watched root captured for the candidate contains or is the help folder (that root then owns those files, for installer activation as well); native migration handling, filtering, exclusions and promotion checks are kept. No help-marker format change and no marker bump: v6 is unreleased and already re-submits help for installs from releases. Settled scenarios: H1 after a user-started rebuild completes and the new generation serves, the eligible bundled help documents (top-level .md files of the help folder) are in it, in the help collection, without a restart, also with zero watched roots; H2 restarts keep them, without loss or duplicate identities; H4 a stop before help is admitted, or around the pointer commitment, recovers to a rebuild that contains help without duplicates; H5 a help folder present but not fully enumerable keeps the serving generation (existing failure handling); N3 an excluded help file stays out after the rebuild and after a restart; N4 ignored .txt and nested Markdown in the help folder stay absent; N5 help files are never written.",
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
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRebuildHelpTest.java#crashAroundPointerCommitmentKeepsHelp",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRebuildHelpTest.java#unreadableHelpKeepsTheServingGeneration"
      ]
    },
    {
      "items": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRebuildHelpTest.java"
      ],
      "class": "New",
      "behaviour": "H2/H4/H5 (preservation): help rows already admitted to the BUILDING generation before a stop are served exactly once after the resumed rebuild; a cancellation at the pointer commitment keeps the serving generation and its help; a rebuild with the help folder absent or empty completes with an empty generation; a captured watched root that contains or is the help folder keeps owning those files, so the rebuild does not relabel them as help.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "do that",
          "delegationLocation": "task t-20261112-help-after-rebuild request",
          "delegationScope": "The repair the owner answered with \"do that\": a report that said \"Help documents after a rebuild: they disappear after a rebuild you start yourself. That was already broken before this fix.\" The scenarios and design below are orchestrator choices under that delegation, not owner words.",
          "choice": "Settled scenarios kept by the repair: H4 help rows already admitted to the building generation before a stop are served once after the resumed rebuild, and a cancellation before the pointer commitment keeps the serving generation and its help; H5 a rebuild with the help folder absent or empty completes (no help required); H2 overlap: when a watched folder contains or is the help folder, that folder keeps its existing binding (the watched root owns those files, as in live ingestion), so a rebuild does not relabel them as help.",
          "label": "agent choice"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/help-rebuild-preservation.json",
      "newChecks": [
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRebuildHelpTest.java#resumedBuildingGenerationWithAdmittedHelpHasNoDuplicates",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRebuildHelpTest.java#refusedRebuildKeepsServingHelp",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRebuildHelpTest.java#rebuildWithoutHelpSourceStillCompletes",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerRebuildHelpTest.java#capturedWatchedRootKeepsItsHelpBinding"
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
  "session": "cb931fec-4aa3-4c83-8112-969f6334cf08:verifier-help",
  "verdict": "accept",
  "digest": "sha256:45c77bca92c48bcc9dbdd2235101c5446a3b0e88b9eac5e90e3995f1651e9ba4"
}
```
