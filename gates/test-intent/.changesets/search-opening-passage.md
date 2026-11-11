---
schema: test-intent.v1
task: t-20261111-readfollow
author-role: builder
author-session: 01a1cc3b-71cc-72e1-a61f-4fec9dd19b01
---

# Test-intent entries

The leading three candidates of the reranker window also compete on title plus document
opening, keeping the better passage score. A chunk's opening comes from its parent's stored
preview in the Worker's wire-only `parent_opening` field. Identical passages are not scored
twice; incomplete score vectors preserve the entire original order. The opening field is
removed from search outputs, MCP responses, folder file maps and exports. With cross-encoding
disabled the Worker does not read openings. Stored data is unchanged.

The owner quote delegates judgment about this follow-up after being told that PDF answers
rank slightly lower since #1209. The specific expectations below are delegated agent choices,
not verbatim owner requirements. Both gated files use `New`: the existing files gain
expectations and inputs, so their edits do not preserve the whole oracle as `Adaptation` requires.
The GPL assertion protects an existing export projection rather than claiming a repaired export
defect. The fallback check pins the ranking repair and its output boundary; the passage-building and score-folding unit checks are in the new isolated KnowledgeSearchEngineOpeningPassageTest.

The gate exempts these isolated new test sources; their expectations and execution remain for
the independent verifier:

- `KnowledgeSearchEngineOpeningPassageTest.java`
- `KnowledgeFolderFilesWireFieldsTest.java`
- `WorkerSearchOutputProjectionTest.java`
- `KnowledgeSearchParentOpeningExitTest.java`
- `SearchResponseBuilderParentOpeningTest.java`

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpl/GplJobCoordinatorTest.java"
      ],
      "class": "New",
      "behaviour": "Inject parent_opening=private-opening into detailResult search hits and assert that neither the wire field name nor its value appears in the two exported GPL training rows. Preserve all existing pipeline, positive/negative feature, branch-column and report assertions; the shared fixture injection also exercises existing detailResult callers. This is added preservation coverage for the existing numeric-feature export projection, not a claim that this change repairs GPL export behavior.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "think about how to most efficiently approach this and wether some items arent worth it, then proceed accordingly",
          "delegationLocation": "task t-20261111-readfollow request",
          "delegationScope": "Assess and carry out the follow-up to PDF answers ranking slightly lower since #1209, including the chosen opening-passage ranking improvement and its boundary and preservation checks.",
          "choice": "Keep the parent's opening as reranker-only input: GPL exports retain their existing training-feature projection even when Worker search hits contain parent_opening. Strengthen the existing export check with injected wire-only data and absence assertions while keeping its established feature expectations.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/search-opening-passage-gpl.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/gpl/GplJobCoordinatorTest.java#negativeSampling_usesStage3aPipelineAndWritesBranchColumns"
      ]
    },
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineOpeningFallbackTest.java"
      ],
      "class": "New",
      "behaviour": "With four chunk candidates, submit their four match passages plus only the first three title/opening passages, and let candidate 2's stronger opening move it ahead of candidates 0, 1 and 3. Check ordinary and authorized search, ordinary and authorized search sessions, and the agent search tool. Incomplete original/opening score vectors (2, 4 or 6 of 7 scores) retain the complete pre-rerank order with no cross-encoder trace. Null, skipped, unavailable and deadline-exceeded rerank results also preserve that order. Every response removes parent_opening; the agent tool output also omits the opening value. Restore the previous global ConfigStore after each case.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "think about how to most efficiently approach this and wether some items arent worth it, then proceed accordingly",
          "delegationLocation": "task t-20261111-readfollow request",
          "delegationScope": "Assess and carry out the follow-up to PDF answers ranking slightly lower since #1209, including the chosen opening-passage ranking improvement and its boundary and preservation checks.",
          "choice": "Limit extra opening scoring to the first three reranker-window candidates, retain each better score, and discard incomplete score vectors rather than partly reordering results. Preserve existing unavailable/skipped/timeout fallback behavior while routing opening passages through public search entry points and preventing reranker-only data from escaping, including through the agent tool.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/search-opening-passage-fallback.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineOpeningFallbackTest.java"
      ],
      "repaired": true
    }
  ]
}
```

## Evidence commands

Run on the host from the candidate checkout (the builder sandbox cannot create the before-state worktree):

```bash
env -u DISPLAY -u WAYLAND_DISPLAY -u TESSDATA_PREFIX js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/search-opening-passage-fallback.json \
  --before 07769e24e2 \
  --check modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeSearchEngineOpeningFallbackTest.java

env -u DISPLAY -u WAYLAND_DISPLAY -u TESSDATA_PREFIX js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/search-opening-passage-gpl.json \
  --before 07769e24e2 \
  --check 'modules/app-services/src/test/java/io/justsearch/app/services/gpl/GplJobCoordinatorTest.java#negativeSampling_usesStage3aPipelineAndWritesBranchColumns'
```

GPL is a preservation check: it passes before and after, and its entry claims no repair.

## Acceptance records
