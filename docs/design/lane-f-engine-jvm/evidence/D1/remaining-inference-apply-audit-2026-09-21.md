# Remaining inference apply-scope audit (2026-09-21)

This is noncanonical D1-3 evidence for the eighteen named keys that were outside the encoder and
generative dependency projections audited in `encoder-apply-scope-audit-2026-09-21.md`. The
canonical names were checked against `tmp/2298-declared-key-union.json`. Each scope is a candidate,
not an accepted register row. Root still owns the direct fingerprint and restart-precedence
reconciliation, using `restart-required > generation-bound > component > hot` when more than one
classification applies.

The audit was read at revision `6c95d7989e1e8e154afa61b69ece3b6b0205c5ab` plus the shared dirty
D1 worktree on Windows. No Java source was edited, and no build, test, stack, or runtime execution
was performed.

## Key-by-key findings

| Canonical key | Actual reader lifetime | Candidate apply scope | Missing component dependency or unresolved point |
| --- | --- | --- | --- |
| `justsearch.llm.enabled` | Resolved into `ResolvedConfig.Ai` at `ResolvedConfigBuilder.java:1006-1013`. Its only production value read is the launcher's smoke pass at `SmokeDriver.java:43-58`; the inference manager does not consult it when deciding whether to construct or launch generative inference. | `restart-required` candidate as a bootstrap/launcher policy | It is not a `generative` dependency. The current reader only changes startup diagnostics, so this row must not claim that changing it disables a live generative component. |
| `justsearch.llm.kv_type` | Normalized once into `ResolvedConfig.Ai.llmKvType` (`ResolvedConfig.java:175-184`; `ResolvedConfigBuilder.java:1111-1122`), then read while building each llama-server launch command at `LlamaServerOps.java:355-370,488-495,527-530`. It is also part of managed-child identity at `ManagedLlamaConfigIdentity.java:21-31`. | `component:generative` candidate | Missing from `HeadAssembly.GENERATIVE_DEPENDENCIES`; a generative recompose/restart is required for an already-running child. |
| `justsearch.llm.reasoning_budget` | Normalized in `ResolvedConfigBuilder.resolveReasoningBudget` at `ResolvedConfigBuilder.java:1046-1072`; frozen into launch argv at `LlamaServerOps.java:463-474` and launch-observation state at `:374-376`. | `component:generative` candidate | Missing from generative dependencies. It is launch-time, not a live per-request setting. |
| `justsearch.llm.slots` | Resolved into `ResolvedConfig.Ai.llmSlots` (`ResolvedConfig.java:175-184`) and frozen into `-np` for each launch at `LlamaServerOps.java:488-495,527-530`; VDU mode overrides it to one slot. | `component:generative` candidate | Missing from generative dependencies. It requires child restart to affect a running server. |
| `justsearch.llm.use_thinking` | Resolved at `ResolvedConfigBuilder.java:1020`; read during launch at `LlamaServerOps.java:374-376,463-474` and when checking server props at `ServerPropsOps.java:156-184`. | `component:generative` candidate | Missing from generative dependencies. The props read observes compatibility; it does not hot-apply launch flags. |
| `justsearch.vlm.model` | Resolved into the AI snapshot at `ResolvedConfigBuilder.java:1017`; used by `InferenceConfig.fromEnvironment` to select the model file and profile ownership at `InferenceConfig.java:193-201,257-287`. | `component:generative` candidate | Missing from generative dependencies even though it can change the effective `llm.model_path` applied value. |
| `justsearch.vlm.profile` | Deprecated key read directly during `InferenceConfig` construction at `InferenceConfig.java:165-185`; selects the legacy atomic model/projector pair. | `component:generative` candidate | Missing from generative dependencies. `justsearch.chat.profile` is declared, but this still-live alias can independently select the same resources. |
| `justsearch.vdu.quality_threshold` | Read into static final `IndexingDocumentOps.VDU_QUALITY_THRESHOLD` at class initialization (`IndexingDocumentOps.java:773-776`) and supplied to each visual-routing decision at `:790-801`. | `restart-required` candidate | Missing from the index component dependencies. It is frozen for the JVM lifetime, so a config rebuild or component reconstruction does not apply it. |
| `justsearch.vram.threshold.4gb` | `VramFlagsUtil.threshold4gb` reads the current global `ConfigStore` for every `detectVramTier` call (`VramFlagsUtil.java:91-111,125-138`). Consumers call it during generative launch/status and activation (`LlamaServerOps.java:398`; `RuntimeActivationService.java:512,1134`; `InferenceHandlers.java:375`). | `hot` candidate | No component dependency is needed to make the current reader see a rebuilt global snapshot, but the register must state that it changes tier classification only. `VramFlagsUtil.java:81-86` explicitly says it does not change GPU flag recommendations. |
| `justsearch.vram.threshold.8gb` | Same live `ConfigStore` read path through `VramFlagsUtil.threshold8gb` at `VramFlagsUtil.java:91-106,125-138`. | `hot` candidate | Same classification-only limit; it is not launch memory policy despite the generative consumers. |
| `justsearch.vram.threshold.12gb` | Same live `ConfigStore` read path through `VramFlagsUtil.threshold12gb` at `VramFlagsUtil.java:91-100,125-138`. | `hot` candidate | Same classification-only limit; it is not launch memory policy despite the generative consumers. |
| `justsearch.onnxruntime.native_path` | Resolved from the rebuilt startup snapshot and translated to ORT's `onnxruntime.native.path` before any ORT session at `HeadlessApp.java:760-802`. ORT's Java loader captures that property once at class initialization (`OrtCudaHelper.java:228-233,349-368`). | `restart-required` candidate | Missing from encoder dependencies. Whole-JVM restart outranks a possible encoder recompose because the native loader cannot switch after class initialization. |
| `justsearch.onnxruntime.variantId` | Read during startup by `HeadlessApp.variantOrtNativeDir` at `HeadlessApp.java:804-835`, then used to set `justsearch.onnxruntime.native_path` before the one-shot ORT apply at `:899-916`. | `restart-required` candidate | Missing from encoder dependencies. It is an upstream bootstrap selector for the one-shot native loader, not a live variant switch. |
| `justsearch.embed.dimension` | Declared as `EnvRegistry.EMBED_DIMENSION_OVERRIDE` at `EnvRegistry.java:183`, but no production reader exists. Effective vector dimension instead comes from the composed embedding service and is installed into `IndexFingerprint` at `KnowledgeServer.java:705,2205-2260`. | unresolved/no-op; do not assign a behavioral scope yet | It is absent from encoder and index dependencies. Its name suggests generation impact, but declaration without a reader is not proof; root must retire it or connect it before classifying it. |
| `justsearch.splade.evidence_path` | Resolved into `ResolvedConfig.Ai.Splade.evidencePath` at `ResolvedConfigBuilder.java:1280`. `SpladeEncoder.buildAssembly` reads the global snapshot once during assembly and freezes the absolute path into `SpladeAssembly` (`SpladeEncoder.java:237-243`; `SpladeAssembly.java:9-27`). | `component:encoders` candidate | Missing from encoder dependencies. This is a diagnostic sidecar destination, not an `IndexFingerprint` input, but a recompose is needed to replace the frozen path. The direct global read also bypasses the supplied projection snapshot. |
| `justsearch.tessdata.path` | `TikaOcrRuntime.resolve` reads the environment/property on every resolution at `TikaOcrRuntime.java:253-275`. Production `PdfOcrEngine` deliberately invokes that resolver per engine call/document (`PdfOcrEngine.java:128-155`) and passes the result to each child process at `:162-170`. | `hot` candidate | Missing from index dependencies if component applied-value digests are intended to enumerate every input affecting indexed content. The current execution path is genuinely live and needs no component reconstruction. |
| `justsearch.tesseract.path` | `TikaOcrRuntime.resolve` reads it on every resolution at `TikaOcrRuntime.java:35-57`. `PdfOcrEngine` re-resolves the runtime per document at `PdfOcrEngine.java:128-155`. | `hot` candidate | Missing from index dependencies under the same completeness interpretation. This is live discovery, not a construction-time frozen executable. |
| `justsearch.citation.match_threshold` | Normalized into `ResolvedConfig.Rag` at `ResolvedConfigBuilder.java:1654`. Both citation paths read it while wiring long-lived services: agent wiring at `AgentLoopWiring.java:139-152` and streaming RAG wiring at `ConversationApiAssembly.java:160-176`. Matchers then retain the supplied scalar; there is no per-request `ConfigStore` read. | `component:generative` candidate | Missing from generative dependencies. Both wiring paths must be reconstructed together; classifying it `hot` would misstate the current frozen readers. |

## Classification boundaries

The four llama launch controls (`llm.kv_type`, `llm.reasoning_budget`, `llm.slots`, and
`llm.use_thinking`) are snapshot values consumed when the managed child command is assembled. A
subsequent request does not reread them. The two VLM selectors likewise feed `InferenceConfig`
construction. They fit the replaceable generative child, while `llm.enabled` currently belongs only
to launcher smoke policy and does not control that child.

The three VRAM thresholds and two Tesseract paths have actual live readers. The VRAM values are
reread for each tier calculation; Tesseract and tessdata are deliberately rediscovered per OCR
document so a repaired runtime becomes usable without reconstruction. In contrast,
`vdu.quality_threshold` is a static final class-initialization value, and both ORT native selectors
feed a property the native loader captures once. The root precedence therefore makes those three
areas `hot`, `restart-required`, and `restart-required`, respectively.

No classification here proves that the future apply operation rebuilds the relevant snapshot,
reconstructs both citation consumers, restarts the managed llama child, or restarts the JVM before
ORT/class initialization. The missing dependency findings are capture gaps, not implemented apply
behavior. `justsearch.embed.dimension` remains a declared no-op until a production reader or an
explicit retirement decision exists.
