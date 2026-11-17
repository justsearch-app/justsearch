---
schema: test-intent.v1
task: t-20261113-fmimpl
author-role: builder
author-session: fm-rest-w-builder
---

# First minutes: the app reads files; search by meaning and word search

The comparison of the finished packaged app with the first-minutes pictures found old names in text
a user can reach ("Outside indexing hours", "No documents indexed yet", "Semantic search degraded",
"Drop folder to index", "Index failed"); the design rule is one name per thing: the app reads files,
Search by meaning, word search. Under the owner's delegation of 2026-11-17 ("takeover the remaining
work and proceed until you think its done") the implementation session decided rules R1-R4 and a
table of sentences; this change applies them to display text only (no id, key, code, test id,
stored value or telemetry name changes).

The new file `first-minutes-wording.test.ts` calls the producing functions and pins the new
sentences, and guards the 18 reworded files against the old phrases in display text. On the base
b63294eff8 its 10 wording cases and 6 of its guard cases fail by assertion; all pass on the candidate
(gates/test-intent/evidence/fm-rest-w.json). The other 12 guard cases pass on both (those files had
no guarded phrase).

Existing tests that pinned the old sentences are updated to the new ones; their inputs and
observations are unchanged. Negative assertions that excluded an old claim phrase now exclude the
new phrase as well, so they keep guarding the same claim. Test titles are left as they were (some
are named in governance/search-window-scenarios.v1.json).

```json
{
  "entries": [
    {
      "items": [
        "modules/ui-web/src/shell-v0/first-minutes-wording.test.ts"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "New",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover the remaining work and proceed until you think its done",
          "delegationLocation": "task t-20261113-fmimpl request",
          "delegationScope": "finish the first-minutes design's remaining work after comparing the finished packaged app with the pictures",
          "choice": "One name per thing in the display text a user reads or hears: the app reads files; the features are search by meaning and word search. R1 indexing -> reading, indexed -> read, indexing hours -> reading hours, documents counted by that activity -> files (1 file, 2 files). R2 semantic search -> search by meaning (Search by meaning at a sentence start), semantic ranking -> ranking by meaning, ranked semantically -> ranked by meaning, semantic and keyword retrieval -> search by meaning and word search; meaning-based results stays. R3 keyword search -> word search, keyword results -> word results, keyword-only -> word search only. R4 index as a noun, rebuild, Reindex required., embedding model, Collection and every word R1-R3 do not name stay. Table: e.g. Outside indexing hours. -> Outside reading hours.; No documents indexed yet -> No files read yet; Indexing embeddings… -> Preparing search by meaning…; Building semantic search -> Preparing search by meaning; Background indexing -> Background reading; Index failed · -> Couldn't read ·; Index gave up · -> Gave up reading ·; Indexed · -> Read ·; Index state unknown -> Reading outcome unknown; Indexed N documents -> Read N files; Drop folder to index -> Drop folder to add it to your library; Searching and indexing are on hold. -> Searching and reading are on hold.; Why is indexing slow? -> Why is reading my files slow?; Help names controls by their current labels.",
          "label": "agent choice"
        }
      ],
      "behaviour": "The functions that produce the readiness notices, the rebuild verdict body, the AI engine's reading body, the blocked-embedding progress words, the coverage caveats and labels, Activity's reading labels, outcomes, source and scan counts (singular and plural), and the search trace's mode and meaning-ranking reasons say reading, search by meaning and word search, with the expected sentences written out; a guard over the 18 reworded files fails when display text (comments removed) again contains semantic search, keyword search, keyword results, indexing hours or documents indexed.",
      "evidence": "gates/test-intent/evidence/fm-rest-w.json",
      "repaired": true,
      "newChecks": [
        "modules/ui-web/src/shell-v0/first-minutes-wording.test.ts#words each cause row in the first-minutes names",
        "modules/ui-web/src/shell-v0/first-minutes-wording.test.ts#words the banner headlines and bodies in the first-minutes names",
        "modules/ui-web/src/shell-v0/first-minutes-wording.test.ts#words the shared caveats in the first-minutes names",
        "modules/ui-web/src/shell-v0/first-minutes-wording.test.ts#words the rebuild verdict body in the first-minutes names",
        "modules/ui-web/src/shell-v0/first-minutes-wording.test.ts#says the AI engine prepares search by meaning",
        "modules/ui-web/src/shell-v0/first-minutes-wording.test.ts#names a blocked embedding stage as search by meaning and the fallback as word search",
        "modules/ui-web/src/shell-v0/first-minutes-wording.test.ts#words the coverage caveats and labels",
        "modules/ui-web/src/shell-v0/first-minutes-wording.test.ts#words a reading record, its outcome and its source",
        "modules/ui-web/src/shell-v0/first-minutes-wording.test.ts#words a scan as reading a number of files, singular and plural",
        "modules/ui-web/src/shell-v0/first-minutes-wording.test.ts#words the retrieval mode and the meaning-ranking reasons",
        "modules/ui-web/src/shell-v0/first-minutes-wording.test.ts#state/readinessNotice.ts",
        "modules/ui-web/src/shell-v0/first-minutes-wording.test.ts#state/verdict.ts",
        "modules/ui-web/src/shell-v0/first-minutes-wording.test.ts#state/indexingProgress.ts",
        "modules/ui-web/src/shell-v0/first-minutes-wording.test.ts#state/enrichmentCoverage.ts",
        "modules/ui-web/src/shell-v0/first-minutes-wording.test.ts#views/BrainSurface.ts",
        "modules/ui-web/src/shell-v0/first-minutes-wording.test.ts#aggregate-substrate/strategies/searchTraceExplain.ts"
      ],
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for semantic search, keyword search, keyword results, keyword-only, indexing hours, documents indexed, search by meaning, word search, and user-facing wording, copy or vocabulary: none governs the names the UI gives reading or the search modes (0048 and 0050 use 'indexing' for Engine pacing and GPU scheduling, not display text). The consequence-classification register (governance/consequence-classification.v1.json) keeps its claim phrases and gains 'word results', so the keyword-fallback claim stays contained. No conflict."
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/readinessNotice.test.ts",
        "modules/ui-web/src/shell-v0/state/readinessNoticeQueryOnCpu.test.ts",
        "modules/ui-web/src/shell-v0/state/verdict.test.ts",
        "modules/ui-web/src/shell-v0/state/availability.test.ts",
        "modules/ui-web/src/shell-v0/state/aiStateStore.test.ts",
        "modules/ui-web/src/shell-v0/state/unrequestedAi.test.ts",
        "modules/ui-web/src/shell-v0/state/pillAnnouncements.test.ts",
        "modules/ui-web/src/shell-v0/state/uncountedIndex.test.ts",
        "modules/ui-web/src/shell-v0/state/indexingProgress.test.ts",
        "modules/ui-web/src/shell-v0/components/indexingSurfaceSweep.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.reindex-coherence.test.ts",
        "modules/ui-web/src/shell-v0/views/BrainSurface.snapshotLiveness.test.ts",
        "modules/ui-web/src/shell-v0/views/HealthSurface.render.test.ts",
        "modules/ui-web/src/shell-v0/views/SystemSelfView.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover the remaining work and proceed until you think its done",
          "delegationLocation": "task t-20261113-fmimpl request",
          "delegationScope": "finish the first-minutes design's remaining work after comparing the finished packaged app with the pictures",
          "choice": "One name per thing in the display text a user reads or hears: the app reads files; the features are search by meaning and word search. R1 indexing -> reading, indexed -> read, indexing hours -> reading hours, documents counted by that activity -> files (1 file, 2 files). R2 semantic search -> search by meaning (Search by meaning at a sentence start), semantic ranking -> ranking by meaning, ranked semantically -> ranked by meaning, semantic and keyword retrieval -> search by meaning and word search; meaning-based results stays. R3 keyword search -> word search, keyword results -> word results, keyword-only -> word search only. R4 index as a noun, rebuild, Reindex required., embedding model, Collection and every word R1-R3 do not name stay. Table: e.g. Outside indexing hours. -> Outside reading hours.; No documents indexed yet -> No files read yet; Indexing embeddings… -> Preparing search by meaning…; Building semantic search -> Preparing search by meaning; Background indexing -> Background reading; Index failed · -> Couldn't read ·; Index gave up · -> Gave up reading ·; Indexed · -> Read ·; Index state unknown -> Reading outcome unknown; Indexed N documents -> Read N files; Drop folder to index -> Drop folder to add it to your library; Searching and indexing are on hold. -> Searching and reading are on hold.; Why is indexing slow? -> Why is reading my files slow?; Help names controls by their current labels.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Readiness notices, verdict bodies, availability reasons and caveats, and the coverage labels Brain, Health and System show say search by meaning, word search and reading; which notice, caveat, remedy, severity or consequence class appears for which reason codes is unchanged.",
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for semantic search, keyword search, keyword results, keyword-only, indexing hours, documents indexed, search by meaning, word search, and user-facing wording, copy or vocabulary: none governs the names the UI gives reading or the search modes (0048 and 0050 use 'indexing' for Engine pacing and GPU scheduling, not display text). The consequence-classification register (governance/consequence-classification.v1.json) keeps its claim phrases and gains 'word results', so the keyword-fallback claim stays contained. No conflict.",
      "assertionChanges": [
        {
          "oldAssertion": "Expected sentences with Semantic search / keyword search / keyword results / keyword-only / semantic ranking / ranked semantically / Passage embeddings / The GPU is indexing / No documents indexed yet / Building semantic search / semantic search catching up / Semantic search waiting for AI install (e.g. readinessNotice.test 'Semantic search paused; keyword search available.', 'Semantic search degraded.', HealthSurface.render.test 'Building semantic search — 22%', SystemSelfView.test 'Enriching — semantic search catching up').",
          "newAssertion": "The same sentences reworded by R1-R3 and the table (e.g. 'Search by meaning paused; word search available.', 'Search by meaning degraded.', 'Preparing search by meaning — 22%', 'Enriching — search by meaning catching up'). availability.test's /document/i on the no-files reason became /file/i and indexingSurfaceSweep.test's toContain('documents') became toContain('files'). Negative assertions that excluded an old claim phrase now exclude the new phrase too, so they still guard the same claim: not.toContain('keyword') became not.toMatch(/keyword|word (results|search)/), not.toContain('keyword results') became not.toContain('word results') (which also matches the old phrase), not.toContain('Showing keyword') became not.toMatch(/Showing (key)?word/), not.toContain('keyword-only') became not.toMatch(/keyword-only|word search only/), toContain('keyword results') became toContain('word results').",
          "reason": "R1-R3 and the table: Each changed expectation now holds the reworded sentence the production function produces (old phrase replaced by R1-R3 or the table); every input, precondition and observation is unchanged."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/aggregate-substrate/strategies/searchTraceExplain.test.ts",
        "modules/ui-web/src/shell-v0/views/AskFallbackReason.test.ts",
        "modules/ui-web/src/shell-v0/views/AskTerminalReload.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover the remaining work and proceed until you think its done",
          "delegationLocation": "task t-20261113-fmimpl request",
          "delegationScope": "finish the first-minutes design's remaining work after comparing the finished packaged app with the pictures",
          "choice": "One name per thing in the display text a user reads or hears: the app reads files; the features are search by meaning and word search. R1 indexing -> reading, indexed -> read, indexing hours -> reading hours, documents counted by that activity -> files (1 file, 2 files). R2 semantic search -> search by meaning (Search by meaning at a sentence start), semantic ranking -> ranking by meaning, ranked semantically -> ranked by meaning, semantic and keyword retrieval -> search by meaning and word search; meaning-based results stays. R3 keyword search -> word search, keyword results -> word results, keyword-only -> word search only. R4 index as a noun, rebuild, Reindex required., embedding model, Collection and every word R1-R3 do not name stay. Table: e.g. Outside indexing hours. -> Outside reading hours.; No documents indexed yet -> No files read yet; Indexing embeddings… -> Preparing search by meaning…; Building semantic search -> Preparing search by meaning; Background indexing -> Background reading; Index failed · -> Couldn't read ·; Index gave up · -> Gave up reading ·; Indexed · -> Read ·; Index state unknown -> Reading outcome unknown; Indexed N documents -> Read N files; Drop folder to index -> Drop folder to add it to your library; Searching and indexing are on hold. -> Searching and reading are on hold.; Why is indexing slow? -> Why is reading my files slow?; Help names controls by their current labels.",
          "label": "agent choice"
        }
      ],
      "behaviour": "The search trace's user line and the Ask fallback note say Word search / Search by meaning for the retrieval mode and ranking by meaning / search by meaning for the degradation reasons; which reason is worded where is unchanged.",
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for semantic search, keyword search, keyword results, keyword-only, indexing hours, documents indexed, search by meaning, word search, and user-facing wording, copy or vocabulary: none governs the names the UI gives reading or the search modes (0048 and 0050 use 'indexing' for Engine pacing and GPU scheduling, not display text). The consequence-classification register (governance/consequence-classification.v1.json) keeps its claim phrases and gains 'word results', so the keyword-fallback claim stays contained. No conflict.",
      "assertionChanges": [
        {
          "oldAssertion": "'Keyword search' as the TEXT mode; 'semantic ranking blocked — …'; 'Semantic ranking was skipped — …'; '… semantic search did not answer in time …'.",
          "newAssertion": "'Word search'; 'ranking by meaning blocked — …'; 'Ranking by meaning was skipped — …'; '… search by meaning did not answer in time …'.",
          "reason": "R2 and R3: Each changed expectation now holds the reworded sentence the production function produces (old phrase replaced by R1-R3 or the table); every input, precondition and observation is unchanged."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/operations/ActionLedgerClient.test.ts",
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.test.ts",
        "modules/ui-web/src/shell-v0/components/ActionLedgerView.feed.test.ts",
        "modules/ui-web/src/shell-v0/views/ActivitySurface.group-details.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover the remaining work and proceed until you think its done",
          "delegationLocation": "task t-20261113-fmimpl request",
          "delegationScope": "finish the first-minutes design's remaining work after comparing the finished packaged app with the pictures",
          "choice": "One name per thing in the display text a user reads or hears: the app reads files; the features are search by meaning and word search. R1 indexing -> reading, indexed -> read, indexing hours -> reading hours, documents counted by that activity -> files (1 file, 2 files). R2 semantic search -> search by meaning (Search by meaning at a sentence start), semantic ranking -> ranking by meaning, ranked semantically -> ranked by meaning, semantic and keyword retrieval -> search by meaning and word search; meaning-based results stays. R3 keyword search -> word search, keyword results -> word results, keyword-only -> word search only. R4 index as a noun, rebuild, Reindex required., embedding model, Collection and every word R1-R3 do not name stay. Table: e.g. Outside indexing hours. -> Outside reading hours.; No documents indexed yet -> No files read yet; Indexing embeddings… -> Preparing search by meaning…; Building semantic search -> Preparing search by meaning; Background indexing -> Background reading; Index failed · -> Couldn't read ·; Index gave up · -> Gave up reading ·; Indexed · -> Read ·; Index state unknown -> Reading outcome unknown; Indexed N documents -> Read N files; Drop folder to index -> Drop folder to add it to your library; Searching and indexing are on hold. -> Searching and reading are on hold.; Why is indexing slow? -> Why is reading my files slow?; Help names controls by their current labels.",
          "label": "agent choice"
        }
      ],
      "behaviour": "Activity says the app read files: Read · / Couldn't read · / Gave up reading · / Reading outcome unknown labels, Read N bursts, Read N files (Read 1 file) scans, Reading · <collection>… while a scan runs, and Background reading as the source; grouping, counting, ordering and routing are unchanged.",
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for semantic search, keyword search, keyword results, keyword-only, indexing hours, documents indexed, search by meaning, word search, and user-facing wording, copy or vocabulary: none governs the names the UI gives reading or the search modes (0048 and 0050 use 'indexing' for Engine pacing and GPU scheduling, not display text). The consequence-classification register (governance/consequence-classification.v1.json) keeps its claim phrases and gains 'word results', so the keyword-fallback claim stays contained. No conflict.",
      "assertionChanges": [
        {
          "oldAssertion": "'Indexed · …', 'Index failed · …', 'Index gave up · …', 'Index state unknown (PAUSED) · …', 'Indexed N · …', 'Indexed 5184 documents · scifact · 6m 12s', 'Indexed 11 documents', 'Indexed 1 documents', 'Indexing · scifact…', toContain('Indexing'), 'Background indexing', startsWith('Indexed') / startsWith('Index failed').",
          "newAssertion": "'Read · …', \"Couldn't read · …\", 'Gave up reading · …', 'Reading outcome unknown (PAUSED) · …', 'Read N · …', 'Read 5184 files · scifact · 6m 12s', 'Read 11 files', 'Read 1 file', 'Reading · scifact…', toContain('Reading'), 'Background reading', startsWith('Read ·') / startsWith(\"Couldn't read\").",
          "reason": "R1 and the table (ActionLedgerClient, ActionLedgerView, activityWords rows); 'Indexed 1 documents' becomes 'Read 1 file' because R1 asks for the singular. Each changed expectation now holds the reworded sentence the production function produces (old phrase replaced by R1-R3 or the table); every input, precondition and observation is unchanged."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/components/DragOverlay.test.ts",
        "modules/ui-web/src/boot/desktopEngineLoss.test.ts",
        "modules/ui-web/src/shell-v0/chrome/Shell.engineLoss.test.ts",
        "modules/ui-web/src/shell-v0/chrome/FirstUse.app-lockdown.test.ts",
        "modules/ui-web/src/shell-v0/views/HelpSurface.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover the remaining work and proceed until you think its done",
          "delegationLocation": "task t-20261113-fmimpl request",
          "delegationScope": "finish the first-minutes design's remaining work after comparing the finished packaged app with the pictures",
          "choice": "One name per thing in the display text a user reads or hears: the app reads files; the features are search by meaning and word search. R1 indexing -> reading, indexed -> read, indexing hours -> reading hours, documents counted by that activity -> files (1 file, 2 files). R2 semantic search -> search by meaning (Search by meaning at a sentence start), semantic ranking -> ranking by meaning, ranked semantically -> ranked by meaning, semantic and keyword retrieval -> search by meaning and word search; meaning-based results stays. R3 keyword search -> word search, keyword results -> word results, keyword-only -> word search only. R4 index as a noun, rebuild, Reindex required., embedding model, Collection and every word R1-R3 do not name stay. Table: e.g. Outside indexing hours. -> Outside reading hours.; No documents indexed yet -> No files read yet; Indexing embeddings… -> Preparing search by meaning…; Building semantic search -> Preparing search by meaning; Background indexing -> Background reading; Index failed · -> Couldn't read ·; Index gave up · -> Gave up reading ·; Indexed · -> Read ·; Index state unknown -> Reading outcome unknown; Indexed N documents -> Read N files; Drop folder to index -> Drop folder to add it to your library; Searching and indexing are on hold. -> Searching and reading are on hold.; Why is indexing slow? -> Why is reading my files slow?; Help names controls by their current labels.",
          "label": "agent choice"
        }
      ],
      "behaviour": "The drop overlay, the engine-loss notice, the folder-added message and Help say reading and name today's control labels; when each appears is unchanged.",
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for semantic search, keyword search, keyword results, keyword-only, indexing hours, documents indexed, search by meaning, word search, and user-facing wording, copy or vocabulary: none governs the names the UI gives reading or the search modes (0048 and 0050 use 'indexing' for Engine pacing and GPU scheduling, not display text). The consequence-classification register (governance/consequence-classification.v1.json) keeps its claim phrases and gains 'word results', so the keyword-fallback claim stays contained. No conflict.",
      "assertionChanges": [
        {
          "oldAssertion": "DragOverlay h3 'Drop folder to index' / 'Drop to add to index'; engine loss contains 'Searching and indexing are on hold. …'; FirstUse first launch shows no 'Folder added. Indexing activity is shown below.'; Help contains 'Rebuild search index'.",
          "newAssertion": "'Drop folder to add it to your library' / 'Drop to add to your library'; 'Searching and reading are on hold. …'; no 'Folder added. Reading progress is shown below.'; Help contains 'Rebuild all search data' (the label core.rebuild-index has today in Health).",
          "reason": "The table (DragOverlay, EngineLoss, FolderAddFlow, HelpSurface: use the label the named control has today). Each changed expectation now holds the reworded sentence the production function produces (old phrase replaced by R1-R3 or the table); every input, precondition and observation is unchanged."
        }
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/state/folderStatus.test.ts",
        "modules/ui-web/src/shell-v0/state/frameStatus.optionalAi.test.ts",
        "modules/ui-web/src/shell-v0/state/indexingProgress.pause.test.ts",
        "modules/ui-web/src/shell-v0/components/StatusPill.calmDot.test.ts",
        "modules/ui-web/src/shell-v0/views/LibrarySurface.render.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.degradation.test.ts",
        "modules/ui-web/src/shell-v0/views/search-v3/SearchV3View.retirement.test.ts"
      ],
      "change": "modified",
      "kind": "test-code",
      "class": "Obsolete",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "takeover the remaining work and proceed until you think its done",
          "delegationLocation": "task t-20261113-fmimpl request",
          "delegationScope": "finish the first-minutes design's remaining work after comparing the finished packaged app with the pictures",
          "choice": "One name per thing in the display text a user reads or hears: the app reads files; the features are search by meaning and word search. R1 indexing -> reading, indexed -> read, indexing hours -> reading hours, documents counted by that activity -> files (1 file, 2 files). R2 semantic search -> search by meaning (Search by meaning at a sentence start), semantic ranking -> ranking by meaning, ranked semantically -> ranked by meaning, semantic and keyword retrieval -> search by meaning and word search; meaning-based results stays. R3 keyword search -> word search, keyword results -> word results, keyword-only -> word search only. R4 index as a noun, rebuild, Reindex required., embedding model, Collection and every word R1-R3 do not name stay. Table: e.g. Outside indexing hours. -> Outside reading hours.; No documents indexed yet -> No files read yet; Indexing embeddings… -> Preparing search by meaning…; Building semantic search -> Preparing search by meaning; Background indexing -> Background reading; Index failed · -> Couldn't read ·; Index gave up · -> Gave up reading ·; Indexed · -> Read ·; Index state unknown -> Reading outcome unknown; Indexed N documents -> Read N files; Drop folder to index -> Drop folder to add it to your library; Searching and indexing are on hold. -> Searching and reading are on hold.; Why is indexing slow? -> Why is reading my files slow?; Help names controls by their current labels.",
          "label": "agent choice"
        }
      ],
      "behaviour": "The folder caveats, the calm status word, the blocked-embedding detail, the Search degradation notice and the retired-search note say word search and search by meaning; when each appears, and that Library never shows a coverage caveat for a quiet folder, is unchanged.",
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for semantic search, keyword search, keyword results, keyword-only, indexing hours, documents indexed, search by meaning, word search, and user-facing wording, copy or vocabulary: none governs the names the UI gives reading or the search modes (0048 and 0050 use 'indexing' for Engine pacing and GPU scheduling, not display text). The consequence-classification register (governance/consequence-classification.v1.json) keeps its claim phrases and gains 'word results', so the keyword-fallback claim stays contained. No conflict.",
      "assertionChanges": [
        {
          "oldAssertion": "'keyword search ready · semantic search still catching up', 'keyword search ready · semantic search waiting for AI install', toContain('keyword search ready'), pill label 'Keyword search ready', 'Embedding cannot run right now. Keyword search remains available.', 'Semantic search paused; keyword search available.', toContain('Keyword search remains available'), causes ['Semantic search models are reloading'], toContain('semantic search did not answer in time'), not.toContain('semantic search still catching up' / 'semantic search waiting for AI install').",
          "newAssertion": "'word search ready · search by meaning still catching up', 'word search ready · search by meaning waiting for AI install', toContain('word search ready'), pill label 'Word search ready', 'Search by meaning cannot be prepared right now. Word search remains available.', 'Search by meaning paused; word search available.', toContain('Word search remains available'), causes ['Search by meaning models are reloading'], toContain('search by meaning did not answer in time'), not.toContain('search by meaning still catching up' / 'search by meaning waiting for AI install').",
          "reason": "R2 and R3, and the orchestrator's additions on the combined candidate (the pill 'Word search ready', 'Search by meaning cannot be prepared right now.', 'Showing word search results', 'matches by meaning'): each changed expectation holds the reworded sentence the production function produces; every input, precondition and observation is unchanged. The negative assertions in LibrarySurface.render.test.ts now exclude the sentence production can produce today, so they keep guarding the same claim."
        }
      ]
    }
  ]
}
```

## Acceptance records
