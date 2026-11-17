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
          "choice": "One name per thing in the display text a user reads or hears: the app reads files; the features are search by meaning and word search. R1 indexing -> reading, indexed -> read, indexing hours -> reading hours, documents counted by that activity -> files (1 file, 2 files). R2 semantic search -> search by meaning (Search by meaning at a sentence start), semantic ranking -> ranking by meaning, ranked semantically -> ranked by meaning, semantic and keyword retrieval -> search by meaning and word search; meaning-based results stays. R3 keyword search -> word search, keyword results -> word results, keyword-only -> word search only. R4 index as a noun, rebuild, Reindex required., embedding model, Collection and every word that R1-R3 and the table do not name stay. The table, decided with the rules, in full (a table sentence wins over a rule): Outside indexing hours. -> Outside reading hours.; Indexing stays held until the reset is confirmed in Settings. -> Reading stays held until the reset is confirmed in Settings.; Indexing throughput has stalled -> Reading has stalled; Indexing throughput is degraded -> Reading is slower than usual; No documents indexed yet -> No files read yet; OCR is disabled, so scanned text cannot be indexed yet -> OCR is disabled, so scanned text cannot be read yet; The GPU is indexing your files -> The GPU is reading your files; The semantic embedding index is not ready -> Search by meaning is not ready; Passage embeddings are not ready -> Passage-level search by meaning is not ready; Passage embeddings are still being computed -> Passage-level search by meaning is still being prepared; The semantic search encoder is stalled -> Search by meaning is stalled; while passage embeddings are missing -> while passage data for search by meaning is missing; Indexing embeddings… -> Preparing search by meaning…; Building semantic search -> Preparing search by meaning; Background indexing -> Background reading; Indexed (a record's result) -> Read; Index failed · -> Couldn't read ·; Index gave up · -> Gave up reading ·; Indexed · -> Read ·; Index state unknown -> Reading outcome unknown; Indexing… -> Reading…; Indexed N documents -> Read N files; Indexed N -> Read N; the Activity filter and details heading Indexing -> Reading; indexing (in Activity's description) -> file reading; Folder added. Indexing activity is shown below. -> Folder added. Reading progress is shown below.; Drop folder to index -> Drop folder to add it to your library; Drop to add to index -> Drop to add to your library; Files will be added to your search index -> Files will be read and become searchable; Searching and indexing are on hold. -> Searching and reading are on hold.; the Settings section heading Indexing -> Reading files; System's Indexing -> Reading; Why is indexing slow? -> Why is reading my files slow?; Initial indexing can take time for large folders. Once complete, incremental updates are fast. Check the Health tab of the System view for indexing status. -> Reading a large folder for the first time can take a while. After that, changes are picked up quickly. The Health tab of the System view shows how reading is going.; Help names controls by the labels they have today; Semantic search build — last known -> Search by meaning build — last known. Added by the same session when it combined the parts: the calm status word Keyword search ready -> Word search ready; Showing keyword-ranked results — semantic ranking is degraded -> Showing word search results — ranking by meaning is degraded; semantic matches; keyword search covers them -> matches by meaning; word search covers them; Embedding cannot run right now. -> Search by meaning cannot be prepared right now.; Keyword search remains available. -> Word search remains available.",
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
          "choice": "One name per thing in the display text a user reads or hears: the app reads files; the features are search by meaning and word search. R1 indexing -> reading, indexed -> read, indexing hours -> reading hours, documents counted by that activity -> files (1 file, 2 files). R2 semantic search -> search by meaning (Search by meaning at a sentence start), semantic ranking -> ranking by meaning, ranked semantically -> ranked by meaning, semantic and keyword retrieval -> search by meaning and word search; meaning-based results stays. R3 keyword search -> word search, keyword results -> word results, keyword-only -> word search only. R4 index as a noun, rebuild, Reindex required., embedding model, Collection and every word that R1-R3 and the table do not name stay. The table, decided with the rules, in full (a table sentence wins over a rule): Outside indexing hours. -> Outside reading hours.; Indexing stays held until the reset is confirmed in Settings. -> Reading stays held until the reset is confirmed in Settings.; Indexing throughput has stalled -> Reading has stalled; Indexing throughput is degraded -> Reading is slower than usual; No documents indexed yet -> No files read yet; OCR is disabled, so scanned text cannot be indexed yet -> OCR is disabled, so scanned text cannot be read yet; The GPU is indexing your files -> The GPU is reading your files; The semantic embedding index is not ready -> Search by meaning is not ready; Passage embeddings are not ready -> Passage-level search by meaning is not ready; Passage embeddings are still being computed -> Passage-level search by meaning is still being prepared; The semantic search encoder is stalled -> Search by meaning is stalled; while passage embeddings are missing -> while passage data for search by meaning is missing; Indexing embeddings… -> Preparing search by meaning…; Building semantic search -> Preparing search by meaning; Background indexing -> Background reading; Indexed (a record's result) -> Read; Index failed · -> Couldn't read ·; Index gave up · -> Gave up reading ·; Indexed · -> Read ·; Index state unknown -> Reading outcome unknown; Indexing… -> Reading…; Indexed N documents -> Read N files; Indexed N -> Read N; the Activity filter and details heading Indexing -> Reading; indexing (in Activity's description) -> file reading; Folder added. Indexing activity is shown below. -> Folder added. Reading progress is shown below.; Drop folder to index -> Drop folder to add it to your library; Drop to add to index -> Drop to add to your library; Files will be added to your search index -> Files will be read and become searchable; Searching and indexing are on hold. -> Searching and reading are on hold.; the Settings section heading Indexing -> Reading files; System's Indexing -> Reading; Why is indexing slow? -> Why is reading my files slow?; Initial indexing can take time for large folders. Once complete, incremental updates are fast. Check the Health tab of the System view for indexing status. -> Reading a large folder for the first time can take a while. After that, changes are picked up quickly. The Health tab of the System view shows how reading is going.; Help names controls by the labels they have today; Semantic search build — last known -> Search by meaning build — last known. Added by the same session when it combined the parts: the calm status word Keyword search ready -> Word search ready; Showing keyword-ranked results — semantic ranking is degraded -> Showing word search results — ranking by meaning is degraded; semantic matches; keyword search covers them -> matches by meaning; word search covers them; Embedding cannot run right now. -> Search by meaning cannot be prepared right now.; Keyword search remains available. -> Word search remains available.",
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
          "choice": "One name per thing in the display text a user reads or hears: the app reads files; the features are search by meaning and word search. R1 indexing -> reading, indexed -> read, indexing hours -> reading hours, documents counted by that activity -> files (1 file, 2 files). R2 semantic search -> search by meaning (Search by meaning at a sentence start), semantic ranking -> ranking by meaning, ranked semantically -> ranked by meaning, semantic and keyword retrieval -> search by meaning and word search; meaning-based results stays. R3 keyword search -> word search, keyword results -> word results, keyword-only -> word search only. R4 index as a noun, rebuild, Reindex required., embedding model, Collection and every word that R1-R3 and the table do not name stay. The table, decided with the rules, in full (a table sentence wins over a rule): Outside indexing hours. -> Outside reading hours.; Indexing stays held until the reset is confirmed in Settings. -> Reading stays held until the reset is confirmed in Settings.; Indexing throughput has stalled -> Reading has stalled; Indexing throughput is degraded -> Reading is slower than usual; No documents indexed yet -> No files read yet; OCR is disabled, so scanned text cannot be indexed yet -> OCR is disabled, so scanned text cannot be read yet; The GPU is indexing your files -> The GPU is reading your files; The semantic embedding index is not ready -> Search by meaning is not ready; Passage embeddings are not ready -> Passage-level search by meaning is not ready; Passage embeddings are still being computed -> Passage-level search by meaning is still being prepared; The semantic search encoder is stalled -> Search by meaning is stalled; while passage embeddings are missing -> while passage data for search by meaning is missing; Indexing embeddings… -> Preparing search by meaning…; Building semantic search -> Preparing search by meaning; Background indexing -> Background reading; Indexed (a record's result) -> Read; Index failed · -> Couldn't read ·; Index gave up · -> Gave up reading ·; Indexed · -> Read ·; Index state unknown -> Reading outcome unknown; Indexing… -> Reading…; Indexed N documents -> Read N files; Indexed N -> Read N; the Activity filter and details heading Indexing -> Reading; indexing (in Activity's description) -> file reading; Folder added. Indexing activity is shown below. -> Folder added. Reading progress is shown below.; Drop folder to index -> Drop folder to add it to your library; Drop to add to index -> Drop to add to your library; Files will be added to your search index -> Files will be read and become searchable; Searching and indexing are on hold. -> Searching and reading are on hold.; the Settings section heading Indexing -> Reading files; System's Indexing -> Reading; Why is indexing slow? -> Why is reading my files slow?; Initial indexing can take time for large folders. Once complete, incremental updates are fast. Check the Health tab of the System view for indexing status. -> Reading a large folder for the first time can take a while. After that, changes are picked up quickly. The Health tab of the System view shows how reading is going.; Help names controls by the labels they have today; Semantic search build — last known -> Search by meaning build — last known. Added by the same session when it combined the parts: the calm status word Keyword search ready -> Word search ready; Showing keyword-ranked results — semantic ranking is degraded -> Showing word search results — ranking by meaning is degraded; semantic matches; keyword search covers them -> matches by meaning; word search covers them; Embedding cannot run right now. -> Search by meaning cannot be prepared right now.; Keyword search remains available. -> Word search remains available.",
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
          "choice": "One name per thing in the display text a user reads or hears: the app reads files; the features are search by meaning and word search. R1 indexing -> reading, indexed -> read, indexing hours -> reading hours, documents counted by that activity -> files (1 file, 2 files). R2 semantic search -> search by meaning (Search by meaning at a sentence start), semantic ranking -> ranking by meaning, ranked semantically -> ranked by meaning, semantic and keyword retrieval -> search by meaning and word search; meaning-based results stays. R3 keyword search -> word search, keyword results -> word results, keyword-only -> word search only. R4 index as a noun, rebuild, Reindex required., embedding model, Collection and every word that R1-R3 and the table do not name stay. The table, decided with the rules, in full (a table sentence wins over a rule): Outside indexing hours. -> Outside reading hours.; Indexing stays held until the reset is confirmed in Settings. -> Reading stays held until the reset is confirmed in Settings.; Indexing throughput has stalled -> Reading has stalled; Indexing throughput is degraded -> Reading is slower than usual; No documents indexed yet -> No files read yet; OCR is disabled, so scanned text cannot be indexed yet -> OCR is disabled, so scanned text cannot be read yet; The GPU is indexing your files -> The GPU is reading your files; The semantic embedding index is not ready -> Search by meaning is not ready; Passage embeddings are not ready -> Passage-level search by meaning is not ready; Passage embeddings are still being computed -> Passage-level search by meaning is still being prepared; The semantic search encoder is stalled -> Search by meaning is stalled; while passage embeddings are missing -> while passage data for search by meaning is missing; Indexing embeddings… -> Preparing search by meaning…; Building semantic search -> Preparing search by meaning; Background indexing -> Background reading; Indexed (a record's result) -> Read; Index failed · -> Couldn't read ·; Index gave up · -> Gave up reading ·; Indexed · -> Read ·; Index state unknown -> Reading outcome unknown; Indexing… -> Reading…; Indexed N documents -> Read N files; Indexed N -> Read N; the Activity filter and details heading Indexing -> Reading; indexing (in Activity's description) -> file reading; Folder added. Indexing activity is shown below. -> Folder added. Reading progress is shown below.; Drop folder to index -> Drop folder to add it to your library; Drop to add to index -> Drop to add to your library; Files will be added to your search index -> Files will be read and become searchable; Searching and indexing are on hold. -> Searching and reading are on hold.; the Settings section heading Indexing -> Reading files; System's Indexing -> Reading; Why is indexing slow? -> Why is reading my files slow?; Initial indexing can take time for large folders. Once complete, incremental updates are fast. Check the Health tab of the System view for indexing status. -> Reading a large folder for the first time can take a while. After that, changes are picked up quickly. The Health tab of the System view shows how reading is going.; Help names controls by the labels they have today; Semantic search build — last known -> Search by meaning build — last known. Added by the same session when it combined the parts: the calm status word Keyword search ready -> Word search ready; Showing keyword-ranked results — semantic ranking is degraded -> Showing word search results — ranking by meaning is degraded; semantic matches; keyword search covers them -> matches by meaning; word search covers them; Embedding cannot run right now. -> Search by meaning cannot be prepared right now.; Keyword search remains available. -> Word search remains available.",
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
          "choice": "One name per thing in the display text a user reads or hears: the app reads files; the features are search by meaning and word search. R1 indexing -> reading, indexed -> read, indexing hours -> reading hours, documents counted by that activity -> files (1 file, 2 files). R2 semantic search -> search by meaning (Search by meaning at a sentence start), semantic ranking -> ranking by meaning, ranked semantically -> ranked by meaning, semantic and keyword retrieval -> search by meaning and word search; meaning-based results stays. R3 keyword search -> word search, keyword results -> word results, keyword-only -> word search only. R4 index as a noun, rebuild, Reindex required., embedding model, Collection and every word that R1-R3 and the table do not name stay. The table, decided with the rules, in full (a table sentence wins over a rule): Outside indexing hours. -> Outside reading hours.; Indexing stays held until the reset is confirmed in Settings. -> Reading stays held until the reset is confirmed in Settings.; Indexing throughput has stalled -> Reading has stalled; Indexing throughput is degraded -> Reading is slower than usual; No documents indexed yet -> No files read yet; OCR is disabled, so scanned text cannot be indexed yet -> OCR is disabled, so scanned text cannot be read yet; The GPU is indexing your files -> The GPU is reading your files; The semantic embedding index is not ready -> Search by meaning is not ready; Passage embeddings are not ready -> Passage-level search by meaning is not ready; Passage embeddings are still being computed -> Passage-level search by meaning is still being prepared; The semantic search encoder is stalled -> Search by meaning is stalled; while passage embeddings are missing -> while passage data for search by meaning is missing; Indexing embeddings… -> Preparing search by meaning…; Building semantic search -> Preparing search by meaning; Background indexing -> Background reading; Indexed (a record's result) -> Read; Index failed · -> Couldn't read ·; Index gave up · -> Gave up reading ·; Indexed · -> Read ·; Index state unknown -> Reading outcome unknown; Indexing… -> Reading…; Indexed N documents -> Read N files; Indexed N -> Read N; the Activity filter and details heading Indexing -> Reading; indexing (in Activity's description) -> file reading; Folder added. Indexing activity is shown below. -> Folder added. Reading progress is shown below.; Drop folder to index -> Drop folder to add it to your library; Drop to add to index -> Drop to add to your library; Files will be added to your search index -> Files will be read and become searchable; Searching and indexing are on hold. -> Searching and reading are on hold.; the Settings section heading Indexing -> Reading files; System's Indexing -> Reading; Why is indexing slow? -> Why is reading my files slow?; Initial indexing can take time for large folders. Once complete, incremental updates are fast. Check the Health tab of the System view for indexing status. -> Reading a large folder for the first time can take a while. After that, changes are picked up quickly. The Health tab of the System view shows how reading is going.; Help names controls by the labels they have today; Semantic search build — last known -> Search by meaning build — last known. Added by the same session when it combined the parts: the calm status word Keyword search ready -> Word search ready; Showing keyword-ranked results — semantic ranking is degraded -> Showing word search results — ranking by meaning is degraded; semantic matches; keyword search covers them -> matches by meaning; word search covers them; Embedding cannot run right now. -> Search by meaning cannot be prepared right now.; Keyword search remains available. -> Word search remains available.",
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
          "choice": "One name per thing in the display text a user reads or hears: the app reads files; the features are search by meaning and word search. R1 indexing -> reading, indexed -> read, indexing hours -> reading hours, documents counted by that activity -> files (1 file, 2 files). R2 semantic search -> search by meaning (Search by meaning at a sentence start), semantic ranking -> ranking by meaning, ranked semantically -> ranked by meaning, semantic and keyword retrieval -> search by meaning and word search; meaning-based results stays. R3 keyword search -> word search, keyword results -> word results, keyword-only -> word search only. R4 index as a noun, rebuild, Reindex required., embedding model, Collection and every word that R1-R3 and the table do not name stay. The table, decided with the rules, in full (a table sentence wins over a rule): Outside indexing hours. -> Outside reading hours.; Indexing stays held until the reset is confirmed in Settings. -> Reading stays held until the reset is confirmed in Settings.; Indexing throughput has stalled -> Reading has stalled; Indexing throughput is degraded -> Reading is slower than usual; No documents indexed yet -> No files read yet; OCR is disabled, so scanned text cannot be indexed yet -> OCR is disabled, so scanned text cannot be read yet; The GPU is indexing your files -> The GPU is reading your files; The semantic embedding index is not ready -> Search by meaning is not ready; Passage embeddings are not ready -> Passage-level search by meaning is not ready; Passage embeddings are still being computed -> Passage-level search by meaning is still being prepared; The semantic search encoder is stalled -> Search by meaning is stalled; while passage embeddings are missing -> while passage data for search by meaning is missing; Indexing embeddings… -> Preparing search by meaning…; Building semantic search -> Preparing search by meaning; Background indexing -> Background reading; Indexed (a record's result) -> Read; Index failed · -> Couldn't read ·; Index gave up · -> Gave up reading ·; Indexed · -> Read ·; Index state unknown -> Reading outcome unknown; Indexing… -> Reading…; Indexed N documents -> Read N files; Indexed N -> Read N; the Activity filter and details heading Indexing -> Reading; indexing (in Activity's description) -> file reading; Folder added. Indexing activity is shown below. -> Folder added. Reading progress is shown below.; Drop folder to index -> Drop folder to add it to your library; Drop to add to index -> Drop to add to your library; Files will be added to your search index -> Files will be read and become searchable; Searching and indexing are on hold. -> Searching and reading are on hold.; the Settings section heading Indexing -> Reading files; System's Indexing -> Reading; Why is indexing slow? -> Why is reading my files slow?; Initial indexing can take time for large folders. Once complete, incremental updates are fast. Check the Health tab of the System view for indexing status. -> Reading a large folder for the first time can take a while. After that, changes are picked up quickly. The Health tab of the System view shows how reading is going.; Help names controls by the labels they have today; Semantic search build — last known -> Search by meaning build — last known. Added by the same session when it combined the parts: the calm status word Keyword search ready -> Word search ready; Showing keyword-ranked results — semantic ranking is degraded -> Showing word search results — ranking by meaning is degraded; semantic matches; keyword search covers them -> matches by meaning; word search covers them; Embedding cannot run right now. -> Search by meaning cannot be prepared right now.; Keyword search remains available. -> Word search remains available.",
          "label": "agent choice"
        }
      ],
      "behaviour": "The folder caveats, the calm status word, the blocked-embedding detail, the Search degradation notice and the retired-search note say word search and search by meaning; when each appears, and that Library never shows a coverage caveat for a quiet folder, is unchanged.",
      "conflictSearch": "Decision records under docs/decisions/ (54) searched for semantic search, keyword search, keyword results, keyword-only, indexing hours, documents indexed, search by meaning, word search, and user-facing wording, copy or vocabulary: none governs the names the UI gives reading or the search modes (0048 and 0050 use 'indexing' for Engine pacing and GPU scheduling, not display text). The consequence-classification register (governance/consequence-classification.v1.json) keeps its claim phrases and gains 'word results', so the keyword-fallback claim stays contained. No conflict.",
      "assertionChanges": [
        {
          "oldAssertion": "'keyword search ready · semantic search still catching up', 'keyword search ready · semantic search waiting for AI install', toContain('keyword search ready'), pill label 'Keyword search ready', 'Embedding cannot run right now. Keyword search remains available.', 'Semantic search paused; keyword search available.', toContain('Keyword search remains available'), causes ['Semantic search models are reloading'], toContain('semantic search did not answer in time'), not.toContain('semantic search still catching up' / 'semantic search waiting for AI install').",
          "newAssertion": "'word search ready · search by meaning still catching up', 'word search ready · search by meaning waiting for AI install', toContain('word search ready'), pill label 'Word search ready', 'Search by meaning cannot be prepared right now. Word search remains available.', 'Search by meaning paused; word search available.', toContain('Word search remains available'), causes ['Search by meaning models are reloading'], toContain('search by meaning did not answer in time'), not.toContain('search by meaning still catching up' / 'search by meaning waiting for AI install').",
          "reason": "R2, R3 and the table, including the sentences the session added when it combined the parts (named at the end of the source): each changed expectation holds the reworded sentence the production function produces; preconditions and observations are unchanged. One input changed with them: folderStatus.test.ts passes the blocked-embedding detail as a fixture string that stands for the sentence indexingProgress produces, so the fixture is reworded like its source ('Search by meaning cannot be prepared right now. Word search remains available.'). The negative assertions in LibrarySurface.render.test.ts now exclude the sentence production can produce today, so they keep guarding the same claim."
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
  "session": "d5886b95-ce0e-4a81-a866-f77d0505bec4",
  "verdict": "reject",
  "digest": "sha256:75b8b57b5f16df169d778cab7b0e5c6faa3f4a9daa57c16bac6bd2c15f1aeafd",
  "note": "Wording expectations outside the cited source. The source's choice states R1-R3, a table introduced by 'e.g.', and R4 'every word R1-R3 do not name stay'. These pinned sentences follow from none of R1-R3 or the quoted table, and several replace words R4 keeps: 'The semantic embedding index is not ready' -> 'Search by meaning is not ready'; 'Passage embeddings are not ready' -> 'Passage-level search by meaning is not ready'; 'Passage embeddings are still being computed' -> 'Passage-level search by meaning is still being prepared'; 'The semantic search encoder is stalled' -> 'Search by meaning is stalled' (R2 gives 'The search by meaning encoder is stalled'); 'Indexing throughput has stalled/is degraded' -> 'Reading has stalled'/'Reading is slower than usual' (new file); 'passage embeddings are missing' -> 'passage data for search by meaning is missing' (new file); 'Embedding cannot run right now.' -> 'Search by meaning cannot be prepared right now.'; 'Showing keyword-ranked results - semantic ranking' -> 'Showing word search results - ranking by meaning'; \"semantic matches\" -> 'matches by meaning'; 'Folder added. Indexing activity is shown below.' -> 'Reading progress is shown below.' (R1 gives 'Reading activity'). The last four are named only in an assertionChanges reason as 'the orchestrator's additions', not in any source. Files: readinessNotice.test.ts, readinessNoticeQueryOnCpu.test.ts, folderStatus.test.ts, frameStatus.optionalAi.test.ts, indexingProgress.pause.test.ts, availability.test.ts, FirstUse.app-lockdown.test.ts, first-minutes-wording.test.ts. Also folderStatus.test.ts changes an input (the enrichmentBlockedDetail fixture), contrary to 'every input unchanged'. Repair: quote the decided table in full in the source choice (with these sentences), or reword to what R1-R4 give. Everything else checked: diffs change only expected sentences, negatives still guard their claims, all files pass, the new file fails before by assertion and caught a reinserted 'Semantic search degraded.'"
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "a5321ecd-fbbf-4063-8e71-f3790ac0911d",
  "verdict": "accept",
  "digest": "sha256:784a66d9440ab9170aea20c0cecfb308229d86b5b828216bc973735063e6f692"
}
```
