# D1 remaining search/index apply-scope audit

Source audit at `6c95d7989e1e8e154afa61b69ece3b6b0205c5ab` in the
Windows `lane-f-pr1-verify` worktree. The candidate set is the `index.*`,
`search.*`, and `rag.*` remainder in
`tmp/2298-dependency-candidate-partition.json` plus the named search/index
carrier keys in the audit brief: 61 canonical keys in total. This file is the
only output. No Java source, build, stack, or commit was changed, and no test was
run; integrated build `2298` was owned by the root session and is not evidence
for this audit.

## Ownership boundary

`KnowledgeServer` supplies its captured startup `ResolvedConfig` to both
writable and read-only Lucene builders (`KnowledgeServer.java:2151-2182,
2223-2233`). `RuntimeSession` retains that snapshot and returns it from
`resolvedConfig()` (`RuntimeSession.java:420-422,992-993`). `SearchOrchestrator`
wires all Worker search planning, execution, and response readers to
`lifecycle::resolvedConfig` (`SearchOrchestrator.java:73-99`). A value read once
per request through that supplier is therefore **frozen for the physical index
component**, not hot.

The current digest union is `IndexRuntimeConfiguration.dependencies()` plus the
explicit `IndexConfigurationProjection.LOCAL` map
(`IndexConfigurationProjection.java:35-36,128-145`). None of the frozen hybrid,
RAG, correction, chunk-aware, or evidence keys below appears in either owner
projection. Each is consequently a missing `index` dependency and missing
applied-value projection unless a row says otherwise.

## Frozen search owners: `component:index`

The smallest truthful projection seam is the already captured
`ResolvedConfig`: project its normalized `HybridSearch`, `Rag`, and `Search`
values. Do not re-read raw declarations or duplicate builder clamping. Builder
normalization is at `ResolvedConfigBuilder.java:1645-1750`.

| Canonical keys | Actual reader and lifetime | Candidate scope / dependency result |
| --- | --- | --- |
| `index.hybrid.vector_skip_min_chars`; `index.hybrid.vector_skip_min_df_fraction` | `SearchPlanner` reads the runtime snapshot while deciding whether to skip the dense leg (`SearchPlanner.java:213-229`). | `component:index`; both missing. |
| `index.hybrid.cc_weight_sparse`; `index.hybrid.cc_weight_dense`; `index.hybrid.cc_weight_splade`; `index.hybrid.adaptive_weights_enabled` | Three-way fusion reads the captured weights and adaptive gate (`SearchExecutor.java:491-553`). | `component:index`; all four missing. |
| `index.hybrid.branch_fusion_strategy`; `index.hybrid.branch_cc_zero_exclude`; `index.hybrid.branch_cc_weight_whole`; `index.hybrid.branch_cc_weight_chunk`; `index.hybrid.branch_chunk_min_weight_multiplier`; `index.hybrid.branch_ramp.full_weight_max_tokens`; `index.hybrid.branch_ramp.zero_weight_min_tokens` | Whole-document/chunk branch fusion reads the captured strategy, weights, exclusion rule, multiplier, and ramp bounds (`SearchExecutor.java:867-924`). | `component:index`; all seven missing. |
| `index.hybrid.chunk_cc_weight_sparse`; `index.hybrid.chunk_cc_weight_dense`; `index.hybrid.chunk_cc_weight_splade`; `index.hybrid.chunk_cc_zero_exclude`; `index.hybrid.chunk_collapse_limit_multiplier`; `index.hybrid.chunk_leg_recall_complete_enabled`; `index.hybrid.chunk_leg_recall_complete_top_n` | Chunk fusion reads the captured weights, zero-exclusion, collapse cap, and recall-complete controls (`SearchExecutor.java:751-785,965-978`). | `component:index`; all seven missing. |
| `index.hybrid.chunk_branch_requires_base_results` | The captured gate suppresses the chunk branch when base results are empty (`SearchExecutor.java:606-610,627`). | `component:index`; missing. |
| `rag.retrieve.mode`; `rag.retrieve.overretrieve_factor`; `rag.union.enabled` | `RagContextOps` reads the captured RAG record for retrieval mode, candidate count, and union leg (`RagContextOps.java:422-438,534-548`). | `component:index`; all three missing. |
| `rag.diversify.mode`; `rag.mmr.max_candidates`; `rag.mmr.lambda` | Diversification/MMR reads the same captured record (`RagContextOps.java:1381-1392,1433-1437,1485-1486`). | `component:index`; all three missing. |
| `rag.max_chunks_per_article` | The applied normalized cap is read during context assembly (`RagContextOps.java:609-624`). | `component:index`; missing. |
| `search.chunk_aware.enabled` | The planner reads the runtime snapshot before chunk merge (`SearchPlanner.java:280-289`); input capture also uses that supplier. | `component:index`; missing. The duplicate `EnvRegistry`/`ConfigKey` declarations are one canonical key. |
| `search.corrections.enabled`; `search.corrections.df_threshold`; `search.corrections.max_edit_distance`; `search.corrections.zero_hit_retry_enabled` | Planner and executor read the captured correction policy (`SearchPlanner.java:113-119`; `SearchExecutor.java:242-278`). | `component:index`; all four missing. |
| `search.evidence_preview.enabled` | `SearchResponseBuilder` resolves it through the runtime supplier (`SearchResponseBuilder.java:118-123,568`). | `component:index`; missing. |
| `search.evidence_span.enabled`; `search.evidence_span.entity_signal` | Response construction reads the captured enable gate and entity-signal selector (`SearchResponseBuilder.java:739-756`). | `component:index`; both missing. |
| `search.mcp_delivery.entity_carriage_enabled` | Worker response construction uses the frozen gate to decide whether to fetch parent entities (`SearchResponseBuilder.java:690-709`), while the Head delivery layer also reads it live (`McpEntityCarriage.java:93-102`). | Minimum scope `component:index`; missing from index dependencies. The Head half needs no recompose. |

These 37 keys affect output from services owned and replaced with the index
component. Treating them as hot because the accessor occurs per request would
make the applied digest claim a value that the current runtime does not use.

## True live reads: `hot`

| Canonical keys | Actual reader | Candidate scope / dependency result |
| --- | --- | --- |
| `justsearch.search.query_classification.enabled` | The Head search path reads `ConfigStore.globalOrNull().get()` for each search (`KnowledgeSearchEngine.java:525-529,652-659`). | `hot`; no index dependency. |
| `justsearch.search.title_boost` | `TextQueryOps` reads the global store when it builds a text query (`TextQueryOps.java:186-193`). | `hot`; no index dependency. This is a deliberate global read inside the index adapter, so a future owner cleanup must preserve per-query behavior. |
| `search.mcp_delivery.budget_bytes` | MCP delivery resolves the current global value (`McpToolSurface.java:1055-1065`). | `hot`; no index dependency. |
| `search.mcp_delivery.entity_carriage_max_chars` | MCP carriage resolves the current global `EntityCarriage` record (`McpEntityCarriage.java:93-102`); the Worker does not use the max-char value. | `hot`; no index dependency. |
| `search.mcp_framing.continuation_enabled`; `search.mcp_framing.evidence_not_answer_enabled`; `search.mcp_framing.calibrated_absence_enabled`; `search.mcp_framing.thin_result_floor_bytes`; `search.mcp_framing.weak_score_floor` | MCP framing resolves all five from the current global store (`McpDeliveryFraming.java:90-110`). | `hot`; no index dependency. |

The `search.*` delivery keys declared in both `EnvRegistry` and `ConfigKey`
still require one register row per canonical key; the second declaration is a
source alias, not another lifecycle owner.

## Open-time and commit-metadata inputs

| Canonical key | Actual reader and lifetime | Candidate scope / dependency result |
| --- | --- | --- |
| `justsearch.index.parity.allow_mismatch` | The parity guard consults the global store when an index is opened (`IndexMetadataParityGuard.java:227-235`). It has no effect on an already-open runtime. | `component:index`; missing. Bind the open decision to the captured component snapshot before projecting it so digest and applied behavior cannot race. |
| `index.boosts` | Already established in the apply-register audit: `SsotCommitMetadataSource` writes `boosts_fp` from the captured normalized boosts map (`SsotCommitMetadataSource.java:124-132,364-372`). It is commit/parity metadata, not an `IndexFingerprint.Inputs` member. | `component:index`; missing from the current projection. Do not classify generation-bound. |

## Copied carriers and inert declarations

Copied legacy IPC fields are not operational dependencies. `WorkerConfig.load`
copies the worker-indexer record (`WorkerConfig.java:49-68`), but production
`EngineRoot` embeds `KnowledgeServer` in-process (`EngineRoot.java:203-220`). In
the Worker, only `config.host()` is later logged (`KnowledgeServer.java:2299`);
there is no consumer of its port, deadline, queue, byte limit, or backpressure
mode.

| Canonical keys | Evidence | Candidate treatment |
| --- | --- | --- |
| `justsearch.indexer.host`; `justsearch.indexer.port`; `justsearch.indexer.deadlineMs`; `justsearch.indexer.queueSize`; `justsearch.indexer.maxInFlightBytes`; `workers.indexer.backpressure_mode` | Copied into `WorkerConfig` at `WorkerConfig.java:51-61`; no operational reader. Host is diagnostic-only. | No component dependency. Mark unresolved/retirement candidates for the retired IPC transport; do not fabricate hot apply. |
| `justsearch.index.collection` | Deprecated declaration (`EnvRegistry.java:267-268`) copied to `WorkerConfig.collection` (`WorkerConfig.java:62-64`) with no production consumer of that field. Actual collection roots are owned by `index.collections`. | No component dependency; retirement candidate. |
| `workers.indexer.enabled` | Only the launcher smoke driver checks it before a run (`SmokeDriver.java:49`). Embedded engine composition does not use it. | `restart-required` launch flag, not an index dependency. Its narrow smoke-only status should be documented or retired rather than presented as live component apply. |
| `justsearch.search.pipeline` | Parsed into `ResolvedConfig.Search.pipeline`, but there is no production accessor of `search().pipeline()`; requests choose an explicit pipeline or preset (`KnowledgeSearchEngine.java:639-650`). | No operational owner; unresolved/retirement candidate. |
| `justsearch.search.pipeline.profile` | Only `LauncherCommands` prints the value in a verification marker (`LauncherCommands.java:69`); search execution does not read it. | Diagnostic-only, no component dependency; resolve or retire rather than label hot. |
| `justsearch.path_resolution.retention_days` | Declaration at `EnvRegistry.java:577-579`; no production read. Queue cleanup accepts a caller-supplied retention integer (`SqliteJobQueue.java:2106-2111`) but this declaration never supplies it. | Inert; unresolved/retirement candidate. |
| `index.commit.debounce_ms` | Declaration only (`ConfigKey.java:43`); no production reader. | Inert; unresolved/retirement candidate. |
| `index.watcher.overflow.rescan_on_overflow` | Declaration only (`ConfigKey.java:27`); no production reader. | Inert; unresolved/retirement candidate. |

## Required D1-3 reconciliation

1. Add the 37 frozen hybrid/RAG/search keys and the two open/metadata keys to
   the index dependency/value projection, using normalized fields from the
   captured snapshot. `search.mcp_delivery.entity_carriage_enabled` must remain
   in that set despite its second live Head reader.
2. Keep the nine true live keys out of component digests. Their code reads the
   current `ConfigStore` on the operation path and therefore supports `hot`.
3. Keep the copied/no-op keys out of component digests. Register rows for inert
   declarations must say unresolved/retirement candidate until the declaration
   is removed or an actual owner is deliberately introduced.
4. Test the lifecycle distinction, not just membership: mutate one frozen
   hybrid value and show the current runtime retains it until index recomposition;
   mutate one MCP framing value and show the next delivery observes it without
   recomposition; prove a copied IPC value does not move the index applied
   version.
