# C1 GPU scheduling at async index connection

2026-09-09, root-owned implementation after clean standard-model pacing207.

Stack205 ran c7c1eb17e (stamp f5751d3eb605cf1c), Engine30616, API56195, fresh data directory
`tmp/c1-clean-standard-data`. Initial206 proves zero documents/jobs and the standard9B model.
No abandoned llama process remained before startup;208 records only the Engine's own llama child.
469 documents indexed, but enrichment stayed at5.1% embedding/61.6% SPLADE/0 NER for minutes
with GPU utilization100% and approximately11.4GiB/12GiB VRAM. Thread dumps210/211 show native
embedding/reranking calls; unlike170, only one waiter remained. The later dump has a different
rerank thread and a different embedding call path, so this does **not** prove permanent deadlock.
The application log reports one combined backfill encoder unit taking270769ms and writing0 rows.

The decisive startup log at13:37:29+02:00 is `No KnowledgeServerBootstrap; GPU status broadcast
disabled`. The actual application logs were under `build/headless-data/logs`, not the configured
data directory (Logback initialization precedes the data-dir property here). Copies of the current
log and run-time rotations are retained in `tmp/c1-clean-pacing-212/`. Run207 output is
`tmp/c1-clean-standard-pacing-207.txt`; process and initial snapshots206/208, ledger209 and thread
dumps210/211 are sibling files under tmp. The diagnostic run was interrupted after this finding;
it is not a completed jseval campaign. Owned stop closed ports and left no llama process.

Production ownership: `ServicePhase` formerly passed the constructor's bootstrap value to
`InferenceWiring`; normal Head construction precedes `connectKnowledgeServer`, so that value was
null for the whole listener setup. The existing live supplier now resolves the current bootstrap
on each publication. `HeadAssembly.connectKnowledgeServer` seeds the current manager mode after
publishing the bootstrap. One retained listener follows reconnect and is removed by the existing
orchestration close. Under the gauge lock, publication reads current manager state instead of the
callback's historical `to` argument; no new state authority is needed.

Unit215 proves async/eager setup, activation before connect, later transitions, reconnect and
stale-callback handling (2 focused tests);18 HeadAssembly tests exercise the real connect path.
Two old bootstrap mocks were completed with the same non-null gauge that production always owns.
Mutation217 removes only the connect seed: the real HeadAssembly connect assertion fails with
`connect seeds the current offline inference mode`,7 represented cases/1 failure. Source restored
in finally. Run219 restores the exact215 source/test inputs and passes via compatible test cache,
including installed distribution (97 tasks:1 executed,2 cached,94 up-to-date). Logs and XML:
`tmp/c1-gpu-connect-mutation-217*`, `tmp/c1-restored-215/`, `tmp/c1-gpu-restored-219.txt`.

Live acceptance remains open. Correct operating-shape arms are standard-active primary indexing
under continuous search, and chat-offline full enrichment under continuous search. Bulk GPU
backfill intentionally pauses while chat owns the GPU (`EmbeddingProviderLifecycle` /
`LoopPacingPolicy`); primary indexing continues and query embedding uses CPU. The failed207
campaign mixed those shapes and cannot establish either a pass or a permanent native deadlock.
Its independent discovery of a missing production GPU broadcast remains valid.
