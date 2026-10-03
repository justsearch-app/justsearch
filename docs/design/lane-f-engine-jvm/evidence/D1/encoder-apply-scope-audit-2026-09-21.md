# Encoder and generative apply-scope audit (2026-09-21)

This is noncanonical evidence for D1-3. It classifies only the dependency keys currently
declared by `EncoderConfigurationProjection` and `HeadAssembly`. Every scope below is a
candidate pending the root owner's direct reconciliation of fingerprint coverage, persisted
derived data, shared-owner application, and restart precedence. It is not the accepted
`config-apply.v1.json` register.

The audit was read from revision `6c95d7989e1e8e154afa61b69ece3b6b0205c5ab` plus the shared
dirty D1 worktree on Windows. No build, test, stack, or runtime execution was performed.

## Governing boundaries

D1-3 currently says that generation-bound rows equal the keys feeding
`IndexFingerprint.Inputs`, naming embedding, SPLADE, and NER model selection
(`stages/D1.md:253-265`). The current input record contains exactly those three model
fingerprints (`adapters-lucene/.../IndexFingerprint.java:213-224`). The re-grounding audit
explicitly records that BGE-M3 is absent from the fingerprint and that adding it changes the
rendering contract (`regrounding-2026-09-10.md:156-163`).

Design section 7.4 is broader: a setting that changes compatibility or interpretation of
persisted derived data is generation-bound, and it specifically names embedding models,
SPLADE vocabulary, NER labels, chunking, and persisted citation output
(`design.md:1415-1424`). The difference between that rule and D1-3's current exact-three
fingerprint statement is why the affected rows below remain pending rather than being guessed.

Encoder dependencies are declared at
`modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/EncoderConfigurationProjection.java:27-85`.
The projection creates each typed role config once from the supplied snapshot at `:112-120`.
Role selection and assembly consume those frozen configs at
`InferenceCompositionRoot.java:267-314,328-363,370-410,419-455,466-524,532-555`.

## Encoder dependency candidates

| Canonical key | Candidate apply scope | Current reader and rationale |
| --- | --- | --- |
| `justsearch.ai.embed.enabled` | pending generation audit | Frozen into role presence at `EmbeddingConfig.java:88-96`; enabling or disabling changes whether persisted vectors are produced. |
| `justsearch.embed.onnx.model_path` | `generation-bound` candidate | Snapshot-bound discovery at `EmbeddingConfig.java:74-86`; represented by the embedding model fingerprint. |
| `justsearch.embed.backend` | pending generation audit | Frozen at `EmbeddingConfig.java:98-116`; can change the implementation producing persisted vectors. |
| `justsearch.embed.gpu.enabled` | `component:encoders` candidate | Frozen at `EmbeddingConfig.java:104-116`; applied while composing the embedding session at `InferenceCompositionRoot.java:284-313`. |
| `justsearch.embed.gpu.device_id` | `component:encoders` candidate | Frozen at `EmbeddingConfig.java:104-116`; session-construction input. |
| `justsearch.embed.gpu_mem_mb` | `component:encoders` candidate | Frozen and converted to bytes at `EmbeddingConfig.java:104-116`; session-construction input. |
| `justsearch.embed.context_length` | pending generation audit | Frozen at `EmbeddingConfig.java:109-116`; changes truncation/context used to produce persisted vectors. |
| `justsearch.embed.late_chunking_enabled` | pending generation audit | Frozen at `EmbeddingConfig.java:109-116`; changes persisted embedding production. |
| `justsearch.embed.late_chunking_context_length` | pending generation audit | Frozen at `EmbeddingConfig.java:109-116`; changes persisted embedding production. |
| `justsearch.gpu.enabled` | `component:encoders` candidate | Frozen applied value at `EncoderConfigurationProjection.java:171`; feeds resolved per-role GPU choices before composition. |
| `policy.gpu_acceleration_enabled` | unresolved shared owner | Frozen for encoder composition at `EncoderConfigurationProjection.java:173-175`, but also read live by generative launch at `LlamaServerOps.java:330-335,1733-1735`; one `component:<name>` row cannot name both owners. |
| `justsearch.sparse_model` | pending generation audit | Selects BGE-M3 versus embedding/SPLADE at `InferenceCompositionRoot.java:153-155`; changes the persisted sparse/vector producer. |
| `justsearch.splade.enabled` | pending generation audit | Frozen into role presence at `SpladeConfig.java:73-101`; changes whether persisted sparse terms are produced. |
| `justsearch.splade.model_path` | `generation-bound` candidate | Snapshot-bound discovery at `SpladeConfig.java:65-71`; represented by the SPLADE model fingerprint. |
| `justsearch.splade.max_seq_len` | pending generation audit | Frozen at `SpladeConfig.java:90-101`; changes the model input used for persisted sparse terms. |
| `justsearch.splade.gpu_enabled` | `component:encoders` candidate | Frozen at `SpladeConfig.java:90-101`; applied during session composition at `InferenceCompositionRoot.java:436-455`. |
| `justsearch.splade.gpu_device_id` | `component:encoders` candidate | Frozen at `SpladeConfig.java:90-101`; session-construction input. |
| `justsearch.splade.gpu_mem_mb` | `component:encoders` candidate | Frozen and converted to bytes at `SpladeConfig.java:90-101`; session-construction input. |
| `justsearch.splade.query_mode` | pending generation audit | Frozen at `SpladeConfig.java:95-101`; interpretation must be reconciled with persisted sparse terms. |
| `justsearch.splade.activation` | pending generation audit | Frozen at `SpladeConfig.java:95-101`; changes model post-processing used for sparse values. |
| `justsearch.ner.enabled` | pending generation audit | Frozen into role presence at `NerConfig.java:62-84`; changes whether persisted entity data is produced. |
| `justsearch.ner.model_path` | `generation-bound` candidate | Snapshot-bound discovery at `NerConfig.java:54-60`; represented by the NER model fingerprint. |
| `justsearch.ner.max_seq_len` | pending generation audit | Frozen at `NerConfig.java:75-84`; changes model input used for persisted entity data. |
| `justsearch.ner.confidence_threshold` | pending generation audit | Frozen at `NerConfig.java:75-84`; changes which entity outputs are retained. |
| `justsearch.ner.gpu_enabled` | `component:encoders` candidate | Frozen at `NerConfig.java:75-84`; applied during session composition at `InferenceCompositionRoot.java:345-362`. |
| `justsearch.ner.gpu_device_id` | `component:encoders` candidate | Frozen at `NerConfig.java:75-84`; session-construction input. |
| `justsearch.ner.gpu_mem_mb` | `component:encoders` candidate | Frozen and converted to bytes at `NerConfig.java:75-84`; session-construction input. |
| `justsearch.bgem3.enabled` | pending generation audit | Frozen into role presence at `BgeM3Config.java:65-78`; changes the persisted vector/sparse producer. |
| `justsearch.bgem3.model_path` | pending generation audit | Snapshot-bound discovery at `BgeM3Config.java:57-63`; BGE-M3 is explicitly absent from the current fingerprint. |
| `justsearch.bgem3.max_seq_len` | pending generation audit | Frozen at `BgeM3Config.java:71-78`; changes model input used for persisted outputs. |
| `justsearch.bgem3.gpu_enabled` | `component:encoders` candidate | Frozen at `BgeM3Config.java:71-78`; applied during session composition at `InferenceCompositionRoot.java:388-409`. |
| `justsearch.bgem3.gpu_device_id` | `component:encoders` candidate | Frozen at `BgeM3Config.java:71-78`; session-construction input. |
| `justsearch.bgem3.gpu_mem_mb` | `component:encoders` candidate | Frozen and converted to bytes at `BgeM3Config.java:71-78`; session-construction input. |
| `justsearch.rerank.enabled` | `component:encoders` candidate | Frozen into immutable `RerankerConfig` at `RerankerConfig.java:117-148`; search-time role selection. |
| `justsearch.rerank.model_path` | `component:encoders` candidate | Snapshot-bound discovery at `RerankerConfig.java:120-125`; search-time reranker model, not an `IndexFingerprint` input. |
| `justsearch.rerank.gpu.enabled` | `component:encoders` candidate | Frozen at `RerankerConfig.java:141-148`; applied during session composition at `InferenceCompositionRoot.java:483-523`. |
| `justsearch.rerank.gpu.device_id` | `component:encoders` candidate | Frozen at `RerankerConfig.java:141-148`; session-construction input. |
| `justsearch.rerank.gpu_mem_mb` | `component:encoders` candidate | Frozen in the encoder projection at `EncoderConfigurationProjection.java:201-205`; session policy input. |
| `justsearch.rerank.top_k` | `component:encoders` candidate | Frozen at `RerankerConfig.java:141-148`; no live settings read. |
| `justsearch.rerank.deadline_ms` | `component:encoders` candidate | Frozen at `RerankerConfig.java:141-148`; no live settings read. |
| `justsearch.rerank.min_hits` | `component:encoders` candidate | Frozen at `RerankerConfig.java:141-148`; no live settings read. |
| `justsearch.rerank.max_seq_len` | `component:encoders` candidate | Frozen at `RerankerConfig.java:141-148` and used to assemble the reranker at `InferenceCompositionRoot.java:515-520`. |
| `justsearch.rerank.max_avg_doc_length_chars` | `component:encoders` candidate | Frozen at `RerankerConfig.java:141-148`; no live settings read. |
| `justsearch.rerank.judge_blend_enabled` | `component:encoders` candidate | Frozen at `RerankerConfig.java:141-148`; no live settings read. |
| `justsearch.rerank.judge_blend_alpha` | `component:encoders` candidate | Frozen at `RerankerConfig.java:141-148`; no live settings read. |
| `justsearch.rerank.judge_arbitration_enabled` | `component:encoders` candidate | Frozen at `RerankerConfig.java:141-148`; no live settings read. |
| `justsearch.rerank.judge_arbitration_alpha_diverge` | `component:encoders` candidate | Frozen at `RerankerConfig.java:141-148`; no live settings read. |
| `justsearch.rerank.judge_arbitration_skip_enabled` | `component:encoders` candidate | Frozen at `RerankerConfig.java:141-148`; no live settings read. |
| `justsearch.citation.scorer.enabled` | pending generation audit | Frozen into role presence at `CitationScorerConfig.java:76-89`; design 7.4 says persisted citation output is generation-bound. |
| `justsearch.citation.scorer.model_path` | pending generation audit | Snapshot-bound discovery at `CitationScorerConfig.java:67-74`; design 7.4 explicitly calls out the citation model. |
| `justsearch.citation.scorer.threshold` | pending generation audit | Frozen at `CitationScorerConfig.java:88-89`; can change persisted citation output selection. |
| `justsearch.citation.scorer.max_seq_len` | pending generation audit | Frozen at `CitationScorerConfig.java:88-89`; changes the model input used for citation output. |
| `justsearch.citation.scorer.deadline_ms` | `component:encoders` candidate | Frozen at `CitationScorerConfig.java:88-89`; operational deadline rather than stored representation. |
| `justsearch.models.capability_contract_strict` | `component:encoders` candidate | Frozen at `EncoderConfigurationProjection.java:232`; applied while building embedding and NER assemblies at `InferenceCompositionRoot.java:304-309,356-358`. |
| `justsearch.ort.profiling_dir` | `component:encoders` candidate | Frozen at `EncoderConfigurationProjection.java:233-237`; applied when constructing session options at `SessionOptionsApplier.java:79-81`. |
| `justsearch.ort.verbose` | `component:encoders` candidate | Frozen at `EncoderConfigurationProjection.java:239-241`; applied when constructing session options at `SessionOptionsApplier.java:52`. |
| `justsearch.onnxruntime.intra_op_threads` | `component:encoders` candidate | Frozen at `EncoderConfigurationProjection.java:243-245`; applied when constructing session options at `SessionOptionsApplier.java:46-48`. |

No declared encoder key has a current live reader that supports a `hot` classification. The role
configs and ORT session options are frozen at composition. No declared encoder key has evidence
that it requires a whole-JVM restart; the candidate component scope assumes the D1 recomposition
path rather than today's one-write startup seam.

## Generative dependency candidates

The declared set is at
`modules/app-services/src/main/java/io/justsearch/app/services/HeadAssembly.java:68-75`.
`generativeAppliedVersion` hashes the normalized `InferenceConfig` fields at `:1696-1708`.
`InferenceLifecycleManager.applyConfig` can replace that config and restart the managed child
without restarting the JVM (`InferenceLifecycleManager.java:679-723,749-761,764-848`).

| Canonical key | Candidate apply scope | Current reader and rationale |
| --- | --- | --- |
| `justsearch.server.exe` | `component:generative` candidate | Frozen in `InferenceConfig`; emitted as the launch executable at `LlamaServerOps.java:440-445`. |
| `justsearch.llm.model_path` | `component:generative` candidate | Resolved at `InferenceConfig.java:211-237`; emitted as the launch model at `LlamaServerOps.java:443-445`. |
| `justsearch.mmproj.model` | `component:generative` candidate | Resolved at `InferenceConfig.java:239-255`; emitted at `LlamaServerOps.java:447-450` when present. |
| `justsearch.server.port` | `component:generative` candidate | Normalized at `InferenceConfig.java:128-132`; used by runtime HTTP calls including `LlamaServerOps.java:1375,1393`. |
| `justsearch.context.size` | `component:generative` candidate | Normalized and frozen at `InferenceConfig.java:138-140`; included in manager/runtime identity at `InferenceLifecycleManager.java:244-246`. |
| `justsearch.gpu.layers` | `component:generative` candidate | Frozen at `InferenceConfig.java:133-150`; read during launch preparation at `LlamaServerOps.java:330-335`. |
| `justsearch.chat.profile` | `component:generative` candidate | Selects the atomic model/projector pair at `InferenceConfig.java:165-201,257-287`. |

These keys shape a replaceable managed child, not an index generation. None has evidence for a
whole-JVM `restart-required` classification. `InferenceLifecycleManager` also supports an
`APPLY_ONLY` policy, but replacing its in-memory config object does not make launch-shaping values
effective in an already-running child; that is not evidence for `hot`.

## Shared and omitted inputs requiring reconciliation

The two declared dependency sets have no exact key intersection. Their actual readers expose
three completeness issues:

1. `policy.gpu_acceleration_enabled` is declared only by encoders, but generative launch reads the
   same canonical system property live and forces `-ngl 0` when false
   (`LlamaServerOps.java:86,330-335,1733-1735`). The register permits only one scope per key, while
   `component:encoders` and `component:generative` are both affected. Root must choose an
   orchestration/precedence rule before accepting this row.
2. `justsearch.models.dir` is absent from both declared sets. It supplies encoder model resolution
   at `KnowledgeServer.java:1593-1598,3154-3159` and generative model resolution at
   `InferenceConfig.java:117-121,223-250`. The applied model paths may hide this input when they
   remain unchanged, but the dependency omission must be decided explicitly rather than inferred.
3. Generative composition is gated by undeclared `justsearch.ai.disabled` and
   `justsearch.lite.mode`, read directly from system properties/environment at
   `InferenceDecision.java:29-41`. Those keys determine whether the generative owner exists, yet
   they are not part of `GENERATIVE_DEPENDENCIES`.

This audit does not classify the omitted keys or any index dependency. It also does not prove the
future recompose path, model-generation metadata, restart ordering, or accepted register parity.
Those require the root fingerprint/restart-precedence reconciliation and executable D1-3 tests.
