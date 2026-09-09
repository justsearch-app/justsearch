---
title: Inference Runtime Register
type: reference
status: stable
created: 2026-03-19
updated: 2026-04-24
description: "Shared decision register for GPU, ORT, VRAM, and inference runtime. Read before starting inference work. Update before finishing."
---

# Inference Runtime Register

Coordination register for inference runtime work (GPU detection, ORT
sessions, VRAM management, model loading, CPU/GPU routing). Every
inference-related tempdoc agent must read this before starting and
update it before closing.

**Rules:**
- Do not re-run an experiment listed under Baselines or Findings without
  justification (e.g., driver update, ORT version change, new hardware).
- When your work settles a question from Open Questions, move it to
  Findings with your tempdoc citation.
- When your work opens a new question, add it to Open Questions.
- Keep entries terse. Evidence lives in tempdocs; this file is the index.
- After ORT/session concurrency changes, run the stress-tagged Gradle tests
  explicitly with `./gradlew.bat test -PincludeStress=true --tests "*Stress*"`.
  There is no scheduled stress cadence, so runtime agents touching
  `NativeSessionHandle` or `SessionHandle` are one trigger point for this
  opt-in verification.

**Replaces:** the GPU- and inference-related items of the former
`docs/reference/issues/` registers (`gpu-detection.md`, `retrieval-quality.md`
RAG-001/RAG-009). That whole register set was retired in tempdoc 821 §7 D5
(2026-08-12); its still-live entries were routed into the observations store
(retired, tempdoc 872 — see git history of `docs/observations.md`), and
anything belonging to this domain should be promoted from there into the
sections below rather than re-created as a standalone issue file.

---

## Canonical Baselines

Frozen reference measurements. Do not re-measure unless the runtime
environment changes (ORT version, driver, hardware).

| Metric | Model | Value | Conditions | Measured in | Valid since |
|--------|-------|-------|------------|-------------|-------------|
| Encode throughput | BGE-M3 FP16 GPU | 100.2 docs/sec (6.4ms/doc) | RTX 4070 12GB, batch=50, SciFact | 322 | dc4f79a |
| Encode throughput | SPLADE-v3 O3+FP16 GPU | 40.1 docs/sec (28ms/doc) | RTX 4070 12GB | 322 | dc4f79a |
| Encode throughput | EmbeddingGemma INT8 GPU | 10.2 docs/sec (98ms/doc) | RTX 4070 12GB, batch=8, 2048MB arena | 312 | 078aee2 |
| Encode throughput | EmbeddingGemma INT8 CPU | 6.7 docs/sec (150ms/doc) | 20 logical cores, batch=8 | 312 | 078aee2 |
| Encode throughput | EmbeddingGemma Q4 GPU | 9.4 docs/sec (106ms/doc) | RTX 4070 12GB, batch=8, 2048MB arena | 312 | 078aee2 |
| Encode throughput | nomic-embed GPU | 10.3 docs/sec (97ms/doc) | RTX 4070 12GB | 322 | dc4f79a |
| CE rerank top-20 | GTE-ModernBERT GPU | ~40-80ms | RTX 4070, ONNX, 8192 context | 309 §41 | dc4f79a |
| CE rerank top-20 | MiniLM-L6-v2 CPU | ~40-80ms | CPU INT8 | 309 §15 | — |
| VRAM peak | BGE-M3 FP16+Flash | ~2.6 GB | 8192-token input, batch=50 | 322 | dc4f79a |
| VRAM steady | GTE-ModernBERT INT8 | ~150 MB | ONNX, arena shrinkage enabled | 309 §41 | dc4f79a |
| GPU cold start | BGE-M3 first batch | ~1441ms/doc | Session init overhead, then steady | 322 | dc4f79a |

---

## Findings

Settled empirical facts. Each was an open question that got answered.

### F-001: ONNX GPU lazy session init causes first-query timeouts

- **Answer:** First query after backend start exceeds the 5 s search deadline (a gRPC deadline when this was measured; the same budget is now enforced on the in-process port call) because the ONNX GPU session initializes lazily on first use.
- **Evidence:** tempdoc 309 §35, §41. Observed across BGE-M3, SPLADE, GTE-ModernBERT.
- **Conditions/caveats:** Only affects first query after cold start. Subsequent queries fast. Workaround: warmup query at startup.

### F-002: CUDA DLL path must be explicitly configured for runHeadlessEval

- **Answer:** `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH` must point to a directory containing `onnxruntime_providers_cuda.dll`, `cublasLt64_12.dll`, etc. Without it, all ONNX GPU sessions fall back to CPU silently.
- **Evidence:** tempdoc 309 §33. Path used: `tmp/ort-variant-test/cuda-12.4/`.
- **Conditions/caveats:** Only affects `runHeadlessEval`. Production app-launcher bundles CUDA DLLs in `native-bin/`.

### F-003: GPU transition propagation was broken (fixed)

- **Answer:** `IndexingLoop.reloadEmbeddingService()` created a new EmbeddingService during GPU transitions but only wired it to the loop, not SearchOrchestrator. Fixed: added `Consumer<EmbeddingService>` listener + corrected `lastMainGpuActiveState` startup assumption (true→false).
- **Evidence:** tempdoc 309 §33. Fix merged in `eccf9e0d5`.
- **Conditions/caveats:** Fix is correct but was not the cause of the Phase 1a dense failure (that was an eval workflow issue — index built without embeddings).

### F-004: bge-reranker-v2-m3 has ONNX GPU regression (5.7x slower)

- **Answer:** ONNX Runtime CUDA provider is 5.7x slower than PyTorch for XLM-RoBERTa-based cross-encoder models. GPU acceleration is counterproductive.
- **Evidence:** tempdoc 309 §39, FlagEmbedding issue #987.
- **Conditions/caveats:** May be fixed in future ORT releases. Model forced to CPU-only.

### F-005: batch=8 is optimal for 300M-param embedding models on RTX 4070

- **Answer:** `MAX_ORT_BATCH_SIZE=8` is optimal. batch=16 needs ~940MB (borderline OOM on 2048MB arena). batch=32 needs ~1.9GB (always OOMs). External benchmarks confirm gte-large (335M, comparable) bottoms out at batch=3-5 on GPU.
- **Evidence:** tempdoc 312 items 29-30. BFCArena math: EmbeddingGemma MultiHeadAttention at batch=8 = ~470MB (fits in 853MB available from 2048MB arena).
- **Conditions/caveats:** Specific to RTX 4070 with 2048MB embedding arena. Larger arenas or GPUs with more VRAM could support larger batches.

### F-006: CPU intraOpNumThreads tuning has no effect on 300M-param models

- **Answer:** Tested default (20 cores), 10 (physical), 4. Results: 158ms, 161ms, 161ms per doc — within noise. ORT saturates available threads regardless.
- **Evidence:** tempdoc 312 item 33.

### F-007: ORT sequence length cap does not affect GPU memory allocation

- **Answer:** `maxSeqLen` only affects tokenizer truncation, not GPU memory. ORT allocates dynamically based on actual input tensor dimensions. Batch padding pads to max-in-batch, not to `maxSeqLen`.
- **Evidence:** tempdoc 312 item 34. Tested 2048, 512, 128 — no effect.

### F-008: NER per-call overhead dominates encoder efficiency

- **Answer:** RTX 4070 FP16 roofline is 29.15 TF. Embed/SPLADE achieve 42–53% GPU efficiency. NER achieves only 18% — a ~5.6ms fixed overhead per `session.run()` call at batch=1 dominates. 82–92% of each encoder call is spent in `session.run()`.
- **Evidence:** tempdoc 356 roofline analysis (RTX 4070, 49s theoretical, 81–111s realistic).

### F-009: NaN-on-CPU-OOM behavior in ORT sessions

- **Answer:** When ORT CPU session exhausts memory, some models return NaN outputs silently rather than throwing an exception. `SessionHandle.reportCpuSessionFailure()` (impl: `NativeSessionHandle`, formerly `OrtSessionManager`) handles this case and BFC arena failures are detected via `NativeSessionHandle.isBfcArenaFailure()`.
- **Evidence:** tempdoc 359 D9. Fixed in shared handle infrastructure (renamed in tempdoc 397 §14.23).

### F-010: Cross-encoder latency baselines (GPU vs CPU)

- **GPU:** ~2.2s for top-20 documents at seq=512, 2048MB arena, RTX 4070. Default: `gpu=true, mem=2048MB, seq=512`.
- **CPU:** ~42s for top-20 documents at seq=2048 on RTX 4070 host CPU.
- **VRAM budget (all ORT consumers):** embed ~2GB + SPLADE ~1GB + NER ~0.5GB + reranker ~2GB = ~5.5GB total (leaves ~6.5GB for LLM on 12GB GPU). *Updated by tempdoc 691:* NER's arena cap is now 2GB (see F-013) — caps are per-session budgets, not pre-allocations, and enrichment backfill yields the GPU when Main claims it, so LLM coexistence is unaffected; measured total VRAM peak during full-corpus enrichment (no LLM): 7.7GB of 12GB. *Further 691 Phase-N note (2026-07-11):* the default-on long-doc single-pass embed (batch-1, up to 8192 tokens) can BFC-OOM inside the 3072MB embed arena on near-8k docs (fragmentation from varying seq lengths; ~1.3-1.5GB BiasSoftmax requests) — it falls back to windowed cleanly, but `JUSTSEARCH_EMBED_GPU_MEM_MB=6144` removes the double-pay and recovers the last ~0.04 nDCG on legal-clerc (0.2967→0.3401). **6144 is the shipped default since 2026-07-11 (founder decision; history 2048→3072 (391)→6144 (691/F-031))** — worst-case cap sum across lanes now exceeds 12GB on paper, but caps are per-session budgets, not pre-allocations, and GPU mutual exclusion + shrinkage + the windowed fallback bound the realized peak (measured 7.7GB at the old caps; re-measure at next full-corpus enrichment profiling).
- **Evidence:** tempdoc 360 (Worker migration), tempdoc 361 I9; tempdoc 691 Phase C (NER cap update).

### F-011: JAR-bundled CUDA defeats native-path-based GPU-failure-reproduction

- **Answer:** Setting `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH` to an empty (or DLL-missing) directory triggers the documented `"ORT CUDA DLLs not found … will try CUDA provider anyway (JAR-bundled)"` log line in `NativeSessionHandle.tryCreateGpuSession`, but `OnnxSessionCache.createCachedGpuSession` then extracts CUDA from JAR-bundled resources and GPU init succeeds anyway. The native-path env var is therefore NOT a viable reproducer for `gpu_init_failure_total{cause=cuda_unavailable}` or any other live GPU-init-failure path.
- **Evidence:** tempdoc 414 V4 validation attempt, 2026-04-26. Worker logs confirmed the warning fired but `tryCreateGpuSession` succeeded.
- **Conditions/caveats:** Live failure reproduction requires either (a) running on a non-CUDA machine, (b) deliberate JAR modification (delete the bundled CUDA resources), or (c) a test-only `JUSTSEARCH_FORCE_GPU_INIT_FAILURE` flag injected into `tryCreateGpuSession` to throw a synthetic `OrtException`. Future agents authoring tempdocs that propose env-var-based GPU-failure reproducers should reference this finding before promising the gate works.

### F-012: LLM-generation latency/throughput is gate-able as a relative ratchet — tokens/sec needs no backend change

- **Answer:** LLM generation latency + throughput regress invisibly the same way retrieval quality did. Tempdoc 640 L added a `bench`-sourced `llm-gen` ratchet: `jseval llm-bench` (warmup-discard + multi-sample) → `jseval llm-gate` gates **TTFT**, **e2e summarize**, and **tokens/sec** medians against RELATIVE ratio bands (no absolute SLO), a sibling of the perf / relevance ratchets sharing `jseval/ratchet_kernel.py`.
- **Don't re-derive (640 D):** tokens/sec needs **no** conversation-subsystem change — the chat `done` event already emits `promptTokens` + `totalTokens` flat (`ConversationEngine:357-361`), so `llm_bench` derives `completion = total − prompt`. (The confidence pass disproved the "needs a backend SSE `usage` emit" assumption.)
- **Baseline (RTX 4070, Qwen3VL-8B-Thinking Q4, summarization):** TTFT ~103 ms, e2e ~6.3 s, ~25.5 tokens/sec. Floor: `scripts/jseval/llm-gen-ratchet-baselines.v1.json` (projected from a green bench via `--update-baseline`, never hand-typed; per-machine + per-configured-LLM).
- **Evidence:** tempdoc 640 L + D (2026-06-24); live-confirmed end-to-end on a real summarization (25.5 t/s).
- **Conditions/caveats:** Advisory tier — run after inference-path edits, not a CI-blocking gate. The committed baseline pins TTFT + e2e; tokens/sec pins on the next `--update-baseline` (needs a bench run where doc-discovery serves the eval index).

### F-013: A missing fp16 variant silently runs the INT8 CPU model on CUDA at ~10× per-call cost (NER incident, fixed)

- **Answer:** When a model dir has only the quantized CPU `model.onnx` (no `model_fp16.onnx`), dev-mode variant selection loads it on the CUDA EP where dynamic-INT8 QOperator nodes lack native kernels — per-node CPU-fallback round-trips cost ~10-15× per call. For NER this made enrichment backfill ~75% NER (per-call p50 28.8ms vs healthy 3.16ms) and corpus build 2.69× slower end-to-end (battlefield: 333s → 124s after fix). The file went missing via an interrupted `scripts/models/build-ner.py` run (~2026-07-01: `model.onnx` downloaded, fp16 step never completed, no `build.json` written).
- **Fixes (tempdoc 691):** (1) `model_fp16.onnx` restored with provenance (sha256 `8121A428…DA49`, HF `Xenova/distilbert-base-multilingual-cased-ner-hrl` @ `c2a4dbf5…`); (2) `DevModeVariantProbe` now reports CPU-file-on-CUDA as **degraded** (mirroring `VariantSelector`'s contract branch) and `InferenceCompositionRoot.resolveVariant` WARN-logs every degraded selection — the silent week came from the probe labeling this case `optimal`; (3) NER arena default `justsearch.ner.gpu_mem_mb` 512 → 2048 (the fp16 variant's attention intermediates, O(batch×heads×seq²) ≈ 200MB at batch=16/seq=512, OOM a 512MB arena → continuous batched→per-doc fallback; at 2048: zero OOM).
- **Metric caveat (open):** `encoder_profiles` NER `ortP50` was recorded only by the batch=1 `infer()` path; the batched `inferBatch()` path now records too (tempdoc 691), but runs before that fix report meaningless NER p50s in batched-healthy conditions — use batch-timing shares for pre-691 NER attribution.
- **Corrects F-008's frame:** the 2026-era "NER 18% GPU efficiency / per-call overhead" analysis measured the healthy fp16 path; the 2026-07 incident was a different mechanism (INT8-on-CUDA), not that overhead worsening.
- **Evidence:** tempdoc 691 Phases A-C (attribution runs, root-cause chain, C-series A/B verification, all artifacts + provenance).

---

### F-014: 708 offline encoder screen — candidate footprint/throughput record + two runtime facts (tempdoc 708, 2026-07-11)

- **Context:** the 708 bake-off (search-quality F-034: NO MODEL SWAP) measured candidate encoders
  offline in torch fp16 on the RTX 4070 — a screen, NOT ORT production baselines (the Canonical
  Baselines table above stays ORT-only). Doc-side encode throughput at chunk granularity
  (500-token chunks, includes tokenize+forward+pool), fp16 size estimates:
  incumbent gte-multilingual-base 628 MB / 9.7 docs/s; arctic-embed-m-v2.0 ~610 MB / 10.0;
  granite-278m ~556 MB / 12.9; arctic-embed-l-v2.0 ~1.1 GB / 5.6; bge-m3 ~1.1 GB / 6.2;
  multilingual-e5-large ~1.1 GB / 6.0; Qwen3-Embedding-0.6B ~1.2 GB / 1.35 (W1; ~6× slower than
  same-size peers — decoder-style embedder; its W2 8k-context run exceeded 60 min for 198 docs and
  was abandoned).
- **Runtime fact 1 (production-relevant):** `OnnxEmbeddingEncoder.createChunks` raw id-slice
  windows CLS-pool a non-[CLS] token on windows 2+ — offline A/B isolates this as the dominant
  share of the old whole-doc dense death (0.105 vs 0.745 R@10 with proper per-window special
  tokens, same model/windowing). F-031's single-pass path moots it up to 8192 tokens; any residual
  >8192-token window-mean path still carries it (open, no active tempdoc — the observations
  inbox that held it was retired in tempdoc 872).
- **Runtime fact 2 (tooling):** Snowflake arctic-embed-m-v2.0's HF remote code (mGTE family)
  hard-requires `xformers` on CUDA (`AssertionError: please install xformers`); the incumbent's
  Alibaba remote code does not. `xformers` 0.0.35 installs clean against torch 2.13.0+cu126
  (`--no-deps`; triton warnings non-fatal).
- **Evidence:** tempdoc 708 §Execution log (final table + run JSON pointers).

### F-015: Non-NVIDIA backends measured on the dev box — llama.cpp Vulkan at CUDA parity for the packaged model; ORT WebGPU plugin EP loads from the stock Java jar at ORT >= 1.24.4 (tempdoc 903, 2026-09-02)

- **Context:** 887 §S row 1.1 re-opened the 311 DirectML rejection (which compared against CUDA,
  not the CPU tier). No AMD/Intel GPU was available, so both measurements are on the RTX 4070 and
  bound BACKEND overhead (portable vs vendor backend on the same silicon), not vendor performance.
  A game client and browsers held ~2.8 GB / 22-40 % GPU throughout (same load for every row).
- **Chat (llama-bench, pinned b8571, `-p 512 -n 128 -r 3 -fa 1`, `-ngl 99` on GPU):**
  Qwen3.5-9B-Q4_K_M — CUDA pp512 2935-3007 / tg128 55.1-55.3 tok/s (two runs); **Vulkan pp512
  2808 / tg128 63.0** (NV_coopmat2); CPU (i7-12700K, 12 threads) 45.8 / 4.52.
  Qwen3.5-4B-Q4_K_M — CUDA 4616 / 86.9; Vulkan 4264 / 98.0; CPU 80.0 / 9.22. Vulkan is
  0.92-0.96x CUDA on prefill and 1.13-1.14x on generation here; the CPU tier is 53-64x slower on
  prefill (an 8k RAG prefill is ~3 min on CPU) and 9-14x slower on generation. llama.cpp's upstream op-support table (`docs/ops/Vulkan.csv` in the ggml-org repo) confirms
  `GATED_DELTA_NET`, `SSM_CONV`, `SOLVE_TRI` and q8_0-KV `FLASH_ATTN_EXT` are supported, so the
  packaged hybrid runs fully offloaded with the D-010 launch line.
- **Encoders (ORT WebGPU plugin EP, `onnxruntime-ep-webgpu` 0.3.0, from Java via
  `OrtEnvironment.registerExecutionProviderLibrary` + `SessionOptions.addExecutionProvider`):**
  the pinned **1.24.3 refuses** (`ORT runtime version "1.24.3" is below the minimum required
  version "1.24.4"`; Maven has no 1.24.4 — next is 1.25.0, current 1.29.0). With 1.29.0 the stock
  CPU `onnxruntime` jar registers the DLL, enumerates the GPU via DXGI, and runs (batch 1, mean of
  50): gte-multilingual-base fp16 on WebGPU **14.8 ms** vs shipped FP32 CPU 113 ms at seq 256
  (7.7x); reranker fp16 14.8 ms vs shipped CPU variant 57.0 ms (3.9x); NER fp16 6.2 ms vs INT8
  CPU 7.0 ms at seq 64. Loading the INT8/CPU-variant model on WebGPU is 2-5x SLOWER than running
  it on CPU (QOperator fallback — the F-013 shape); a GPU EP must always get the fp16 variant.
- **What is NOT settled:** AMD/Intel numbers (Q-003), the Dawn D3D12-vs-Vulkan backend choice,
  multi-session behaviour under the Worker's GPU lease (WebGPU has no BFC arena, so
  `arenaCapBytes > 0 <=> GPU` in `ModelSessionPolicyResolver` needs a second signal),
  production batch sizes. DirectML is in maintenance mode with no Java binding and is not a
  candidate; ORT has no Vulkan EP (WebGPU is the successor).
- **Evidence:** tempdoc 903 §2 (tables), Appendix A (probe source), Appendix B (raw rows);
  artifacts under the gitignored `tmp/903-bench/`.

### F-016: JVM exit can race ORT initialization and session teardown

- **Finding (2026-09-08):** ORT 1.24.3 registers its own environment shutdown
  hook. Entering JVM exit before Engine-owned native resources finish closing
  permits concurrent native teardown; an NRT failure also deadlocked when its
  uncaught handler entered exit while the Engine hook joined that same thread.
- **Ownership:** terminal-writer faults use HeadlessApp's ordered sequence before
  exit. KnowledgeServer close awaits its existing deferred-model initializer's
  completion before releasing model fields; a timeout alone is not quiescence.
- **Verification:** `KnowledgeServerCloseCompletionTest`,
  `HeadlessAppShutdownWiringTest`, `TerminalWriterFailureTest` and
  `TerminalWriterSupervisedRecoveryE2ETest`. This narrow ordering repair does
  not establish general NativeSessionHandle concurrency safety.

### F-017: cancelled GPU waiters must leave admission without releasing active native work

- **Finding (2026-09-09):** NativeSessionHandle's uninterruptible GPU semaphore kept
  cancelled Engine calls alive behind stalled native inference. Engine deadlines already
  interrupt the owning work thread; GPU acquisition now honours that interrupt.
- **Ownership:** interrupted waiters restore interruption and throw cancellation. A permit
  acquired before cancellation but not handed to a lease is released exactly once. Issued
  leases still retain their permit until native work exits; this is not native-call termination.
- **Verification:** NativeSessionHandleGpuWaiterCancellationTest covers blocked, pre-interrupted
  and post-acquire interruption with a mocked native session boundary. Real-model pacing and
  overall native close/quiescence remain separate proof obligations.

## Decisions

Design choices in the current inference runtime, with rationale.

### D-001: GTE-ModernBERT as default CE model — SHIPPED

- **Choice:** Replace MiniLM-L6-v2 (22.7M, 512 tokens) with GTE-ModernBERT-base (149M, 8192 tokens) at `models/onnx/reranker/`. Default `maxSequenceLength` changed to 512 (GPU-viable; model supports 8192 but attention is O(n²)).
- **Rationale:** Auto-detects `needsTokenTypeIds` from ONNX input names. GPU default since tempdoc 360: `gpu=true, mem=2048MB, seq=512` — 2.2s for topK=20 on GPU (vs 42s CPU at seq=2048). Batch padding requires `attentionMask[0]=1` in padding rows (ModernBERT global attention NaN fix, 360).
- **Evidence:** tempdoc 309 §41 (model selection), tempdoc 360 (Worker migration, GPU defaults, NaN fix)
- **Revisit when:** settled.

### D-003: gte-multilingual-base as default embedding model — SHIPPED (supersedes EmbeddingGemma-300M)

- **Choice:** Replace EmbeddingGemma-300M with `Alibaba-NLP/gte-multilingual-base` as the production ONNX embedding model. `EmbeddingOnnxModelDiscovery` hardcodes `MODEL_NAME = "gte-multilingual-base"` and delegates to resolved model roots; it has no automatic EmbeddingGemma or nomic fallback.
- **Rationale:** Equivalent quality (nDCG@10 0.7132 vs 0.7128 on SciFact). 39s faster pipeline (181s vs 220s). 70+ languages (vs English-only). Apache 2.0 license. Lazy CPU session design avoids 20+ GB RAM spike on GPU failure.
- **Evidence:** tempdoc 358 (exhaustive model search, only 2 models pass all hard requirements H1–H9); tempdoc 312 items 23-24 (original EmbeddingGemma selection, now superseded)
- **Previous default:** EmbeddingGemma-300M (Q4 GPU / INT8 CPU, tempdoc 312) — retained as legacy backup at `models/onnx/embeddinggemma-300m/`
- **Revisit when:** settled.

### D-004: Centralized ORT GPU session creation (historical) — SUPERSEDED by D-007

- **Choice:** Extracted identical GPU session creation code from all five ORT consumers (`SpladeEncoder`, `OnnxEmbeddingEncoder`, `BgeM3Encoder`, `BertNerInference`, `CrossEncoderReranker`) into a shared `OrtSessionFactory` in the `ort-common` module (tempdoc 349). Superseded by `OrtSessionManager` (tempdoc 359, renamed `NativeSessionHandle` in tempdoc 397 §14.23); all five consumers co-located in Worker (tempdoc 360); factory deleted (tempdoc 397 §14.22 Phase A).
- **Rationale:** All encoders used identical session options (`kSameAsRequested` arena, no CUDA graph, device allocator for initializers, no memory pattern optimization). The factory encoded these once.
- **Evidence:** tempdoc 349 (factory extraction), tempdoc 352 (module split), tempdoc 359 (`OrtSessionManager`), tempdoc 360 (Worker co-location), tempdoc 397 (factory absorbed into assembler + handle; closure property §6).
- **Current shape:** See D-007 below. The identical-session-options claim is now enforced by `SessionOptionsApplier` walking `RuntimePolicy` records (tempdoc 397 §14.24 FA) rather than by a shared factory class.
- **Verification:** `./gradlew.bat :modules:worker-core:verifyModel -Pmodel=<path>` task — routes through `OrtSessionAssembler.verifyModelSession`, which shares the applier apply path with production (§14.24 FA).

### D-005: Model file manifest convention (`model_manifest.json`) — SHIPPED

- **Choice:** Each model directory declares CPU/GPU model file selection via `model_manifest.json` (fields: `cpu`, `gpu`, `tokenizer`, `pooling_config`, `label_config`). Encoders use `ModelManifest.loadOrDefault()` — falls back to convention (`model.onnx` CPU, `model_fp16.onnx` GPU) for external directories without a manifest.
- **Rationale:** Eliminates implicit file naming conventions that caused the Q4 CPU regression (tempdoc 334). Swapping a model file requires updating one JSON field; encoders pick it up automatically.
- **Evidence:** tempdoc 340
- **Key class:** `ModelManifest` in `modules/worker-core/.../ort/ModelManifest.java`
- **Revisit when:** settled.

### D-006: Model build provenance (`build.json`) — CONTRACT SHIPPED, PUBLIC COVERAGE PARTIAL

- **Choice:** Package-specific build scripts emit `build.json` with source identity/revision, transformations, output SHA-256, tool versions, and an exact build command. The public tree currently tracks this record only for SPLADE; the absence of `build.json` for another package is missing provenance, not a successful integrity check. GGUF candidates use an equivalent per-file immutable source/digest and quantization manifest rather than the ONNX build shape.
- **Rationale:** Model files were opaque blobs with no recorded origin. Updating or debugging a model required reverse-engineering from commit messages and memory.
- **Evidence:** tempdoc 348
- **Integrity check:** `python scripts/models/check-integrity.py` verifies only directories where `build.json` already exists. `python scripts/models/model_promotion_planner.py --registry <registry.json> --package <id> --candidate <candidate.json>` is the write-free, package-scoped readiness check. Its deterministic review bundle preserves canonical provenance, remote-verification facts, evidence references, projection results/diffs, and explicit approval tied to the proposed license while reporting missing publication/runtime/quality/migration evidence without changing assets or registry state.
- **Revisit when:** settled.

### D-007: Single-entry session construction via `OrtSessionAssembler` — SHIPPED (tempdoc 397)

- **Explainer:** [docs/explanation/24-worker-inference-composition.md](../explanation/24-worker-inference-composition.md) — conceptual walkthrough of the pipeline (resolvers → composition root → assembler → handle → surface), policy record shape, and diagnostic endpoint.
- **Choice:** All ORT session construction flows through `OrtSessionAssembler` in `modules/ort-common`. **Three external entry points** (post-§14.28 U1): `buildManager(Composition, GpuArbiter) → SessionHandle` for variant-driven composition-root calls; `verifyModelSession(env, modelPath, GpuSessionConfig) → OrtSession` for the `verifyModel` Gradle task; `probeModelNames(env, modelPath) → ProbedNames` for the short-lived probe session per-encoder `buildAssembly` factories use. Setter-to-policy mapping centralises on package-private `SessionOptionsApplier`, which walks `RuntimePolicy` + `ModelSessionPolicy` fields one-for-one.
- **Rationale:** 394 item 4 revealed two call paths producing non-equivalent sessions under equal inputs. 397 made that class of bug type-unrepresentable via the §6 closure property: every ORT setter value reads a typed policy-record field; there is exactly one apply site (`SessionOptionsApplier`). Policy flows through typed records (`RuntimePolicy`, `ModelSessionPolicy`, `Composition`) resolved by pure functions (`RuntimePolicyResolver`, `ModelSessionPolicyResolver`).
- **What enforces the boundary:** Java visibility + single-apply-site invariant + Gradle source-set scoping + ArchUnit rule.
  - `NativeSessionHandle.Builder` is package-private (§14.19 Phase 4; class renamed from `OrtSessionManager` in §14.23 Phase B). `NativeSessionHandle.builder(...)` factory method is package-private. Flat policy-substitute setters (`.gpuConfig`, `.deferCpuSession`, `.cpuOptLevel`, `.gpuRetryEnabled`, `.gpuRetryIntervalMs`) deleted in §14.26 T1-B; Builder accepts `.runtime(RuntimePolicy)` + `.policy(ModelSessionPolicy)` for policy inputs only. `ModelSessionPolicy.forFallback(...)` factory composes scalar inputs into a policy record at the assembler boundary (mirrors `forVerification` for the verifier).
  - `OrtSessionAssembler.buildManager` returns `SessionHandle`, not `NativeSessionHandle` (§14.21 R1).
  - `NativeSessionHandle.selectSession` is `private`; `runOptionsFor(OrtSession)` deleted; `peekCpuSession` is package-private (§14.21 R2). `inputNames()` + `outputNames()` removed from `SessionHandle` interface (§14.25 FD-ProbeDeletion).
  - **Closure property** (§14.25 FA): `NativeSessionHandle.createGpuSession` + `OrtSessionAssembler.verifyModelSession` both delegate to `SessionOptionsApplier.{applyBase, applyGpuSessionOptions, applyCudaProviderOptions, buildGpuRunOptions}`. Zero hardcoded option values remain outside the applier. §14.28 U2 further collapses the handle's `gpuEnabled` derivation to one branch: `ModelSessionPolicyResolver` zeroes `arenaCapBytes` for non-CUDA variants so `arenaCapBytes > 0` ⇔ GPU session (policy record is self-describing).
  - **Three fallback methods deleted** (§14.28 U1): `buildFallback`, `composeRerankFallback`, `composeCitationFallback` are gone. Test harnesses route through `InferenceCompositionRootTestHelper.sessionFor` in `modules/ort-common`'s testFixtures source set — Gradle scope makes the helper unreachable from production classpaths.
  - **ArchUnit enforcement** (§14.28 U8): `ClosurePropertyTest` is a denylist over owner packages (`java.nio.file`, `java.io`, `java.nio.channels`) + specific classes (`ModelManifest`, `ObjectMapper`, `JsonParser`, `HuggingFaceTokenizer`, `DefaultVocabulary`, `Model`, `ModelZoo`). Encoder primary constructors (FQN-based allowlist: `BertNerInference`, `CrossEncoderReranker`, `CitationScorer`, `OnnxEmbeddingEncoder`, `SpladeEncoder`, `BgeM3Encoder`) cannot call any method on those owners. Negative test verified.
- **Encoder shape (§14.25 FD):** every ORT-backed encoder constructor accepts `(SessionHandle, <Role>Shape, <Role>Tokenizer, ...role-specific)`. Encoders perform zero filesystem I/O. Shape records: `NerShape`, `RerankerShape` (shared by reranker + citation), `EmbeddingShape`, `SpladeShape`, `BgeM3Shape`. Assembly records: `NerAssembly`, `RerankerAssembly`, `EmbeddingAssembly`, `SpladeAssembly`, `BgeM3Assembly`. Each composed via `InferenceCompositionRoot.compose<Role>Assembly(...)` (variant-driven) or each encoder's static `buildAssembly(sessions, ...)` (fallback).
- **Composition root** (§14.27 T2-C1/C2): `InferenceCompositionRoot.compose(ResolvedConfig, HardwareProfile, InstallContract, Path modelsDir, GpuArbiter) → InferenceSurface` is §7.6's single-entry composition. `InferenceSurface` is a record bundling `Optional<EmbeddingAssembly> embedding`, `Optional<NerAssembly> ner`, `Optional<RerankerAssembly> reranker`, `Optional<RerankerAssembly> citation`, `Optional<SpladeAssembly> splade`, `Optional<BgeM3Assembly> bgeM3`, `PolicySnapshot policies`, `List<SessionHandle> handles`. Per-encoder failures are caught inside `compose()` and surface as `Optional.empty()` — graceful degradation preserved. `KnowledgeServer.initDeferredModels()` calls compose() once and destructures.
- **Dev-mode variant resolution** (§14.27 T2-A1): `DevModeVariantProbe.probe(Path modelDir, boolean gpuEnabled) → VariantSelection` centralises filesystem-probe variant discovery for dev mode (no `InstallContract`). Every `VariantSelection` in the JVM comes from one of two sibling paths: `VariantSelector.select` (contract-driven) or `DevModeVariantProbe.probe` (filesystem-driven). Composition root + assembler never know the difference.
- **Ops-layer eager-wire** (§14.27 T2-E1): `RagContextOps.getChunkReranker`, `CitationMatchOps.getCitationScorer`, and `NerService` are pure getters over encoders the composition root wired. No lazy `construct-on-first-use-if-not-wired` paths. `WorkerAppServices.wireCitationScorer(CitationScorer)` carries the eagerly-built encoder. `NerService.buildFallback` deleted.
- **Query-handler gate** (§14.28 U3): `WorkerSearchService` awaits a `modelReadyLatch` (120 s timeout) at entry of `search` / `retrieveContext` / `rerank` / `matchCitations`. Closes a boot-race regression where queries arriving before `initDeferredModels` completion silently missed reranker + citation wiring. Latch supplier wired via `WorkerAppServices.wireModelReadyLatch`.
- **Diagnostic endpoint** (§14.25 FB + §14.28 U4): `JUSTSEARCH_ORT_PROFILING_DIR` + `JUSTSEARCH_ORT_VERBOSE` are typed via `RuntimePolicy.Profiling` → `ResolvedConfig.Ai.Profiling` → `EnvRegistry.ORT_PROFILING_DIR` / `ORT_VERBOSE_LOGGING`. `SessionOptionsApplier` reads `runtime.profiling()`; zero `System.getenv` calls remain in the apply path. `/api/debug/session-policies` reads the index half's authoritative `PolicySnapshot` via the `getSessionPolicies` **port call** — a gRPC rpc until lane F stage A item A14 deleted the wire; the call and its proto response message are unchanged (§14.28 U4 — JSON payloads decouple the message format from `RuntimePolicy` schema evolution). The endpoint reports what the index half actually observed when constructing ORT sessions at boot, not what a re-resolve at the API front would produce. `SessionPoliciesController` is a thin adapter over `KnowledgeClient.getSessionPolicies`; the pre-§14.28 re-resolve path is deleted. Response shape: `{configStatus: "ok" | "config-unavailable" | "surface-unavailable" | "worker-unreachable", runtime, models}` — `worker-unreachable` is the literal the controller still emits when its client is unbound, so do not "fix" the string to match the new architecture.
- **Evidence:** tempdoc 397 (closed 2026-04-21 through §14.28). §14.20 initial closure + §14.21 R1–R5 + §14.22 Phase A + §14.23 Phase B + §14.24 audit + §14.25 FA/FE/FB/FC/FD (11 commits) + §14.26 residuals audit + §14.27 T1/T2 remediation (8 commits) + §14.28 critical-review remediation (9 commits). Total: 30+ commits across 397's landed arc.
- **Key classes (internal, opaque to external callers):** `NativeSessionHandle`, `SessionOptionsApplier`, `OnnxSessionCache`, `DevModeVariantProbe`.
- **Key classes (external):** `SessionHandle` (interface, zero I/O methods), `OrtSessionAssembler` (three entry points: `buildManager`, `verifyModelSession`, `probeModelNames`), `Composition`, `ModelSessionPolicy` (+ `Gpu` / `Cpu` / `Lifecycle` / `RunOptions` subrecords + `forFallback` + `forVerification` factories), `RuntimePolicy` (+ `Arena` / `CudaProvider` / `Session` / `Profiling` subrecords + `defaults()` factory), `InferenceCompositionRoot.compose` + `compose<Role>Assembly`, `InferenceSurface`, role-specific shape + assembly records.
- **Test harness:** `InferenceCompositionRootTestHelper.sessionFor(consumerName, modelDir, gpu, gpuMemMb) → SessionHandle` in `modules/ort-common`'s testFixtures source set. Single authorised test-only surface for integration tests + benchmarks to construct a `SessionHandle` without a full `ResolvedConfig`. `@VisibleForTesting` semantic is enforced structurally by Gradle source-set scoping (testFixtures is not on production runtime classpaths).
- **Verification:** `NativeSessionHandleConcurrentStressTest` for concurrency baseline (10 threads covering #3 CPU recreation + #5 lifecycle-callback + post-close acquire; invariants #1/#2/#4 require CUDA, parked as tempdoc 398; metadata-read thread retired in §14.25 FD-ProbeDeletion); `OrtSessionOptionsTest` for applier parity + causality invariants; `RuntimePolicyResolverTest` for profiling round-trip + CPU-variant zero-arena invariant (§14.28 U2); `ClosurePropertyTest` for §7.5 pure-encoder contract (denylist-by-default, §14.28 U8); `InferenceSurfaceTest` + `InferenceCompositionRootComposeTest` for compose orchestration shape (§14.28 U6/U7); `WorkerSearchServiceModelReadyLatchTest` for the query-handler gate (§14.28 U3); `SessionPoliciesControllerTest` for the gRPC-bridged diagnostic (§14.28 U4); jseval pipeline anchor (§14.7.3): 191.1 s baseline. Post-§14.28 reference run: 208 s total / 24.9 docs/sec / nDCG@10 = 0.750 on 300 scifact queries (commit `0ed0321ce`, 2026-04-21).
- **Revisit when:** 395 A1/A4/A7 adaptive policy work starts (resolver now has a real read-path; §14.28 U2 further made the record self-describing); 394 P3 scheduler lands new `RunOptions` fields (`SessionOptionsApplier.buildGpuRunOptions` is the single setter site); tempdoc 400 observability work identifies a structural gap that motivates additional runtime assertions on the closure property.

### D-011: Late-chunk fallback must make resumable progress — SHIPPED

- **Choice:** A long parent that returns null or a BFC-arena OOM from the late-chunking
  whole-document probe enters `WindowedEmbedProgress` directly. Once a matching partial exists,
  later cycles bypass the whole-document probe and resume at the next window. A probe that spends
  the embed share or cycle deadline cannot prevent the first real window slice, while shutdown,
  interruption, GPU yield, pending ingest, and bulk deletion still pre-empt immediately.
- **Rationale:** The whole-document probe is classification work, not progress that survives a
  cycle boundary. Counting it toward the existing first-unit floor allowed every cycle to end
  before window zero, leaving the progress map empty and re-tokenizing/re-probing the same queue
  head indefinitely. Only a recorded window advances the resumable unit.
- **Boundaries:** Chunk-SPLADE retry-only writes remain scheduler activity but not tight-loop
  progress. The existing SPLADE/NER reservation and mean-pooling algorithm are unchanged; the
  repair changes routing and scheduling only, not vectors or ranking formulas.
- **Evidence:** `CombinedEnrichmentBackfillOps` owns direct fallback/resume routing and the window
  floor; `BackfillScheduler` supplies the separate hard-stop signal.
  `CombinedEnrichmentLongDocumentTest` pins deadline-spent fallback, cross-cycle resume, no repeated
  probe/token-count pass, and hard-preemption behavior. `CombinedEnrichmentBackfillOpsTest` pins the
  null and arena-OOM routes plus retry-only activity/progress semantics.
- **Revisit when:** late chunking gains a streaming single-pass encoder whose partial state is itself
  resumable, or the scheduler replaces cycle/share suppliers with an explicit typed stop reason.

The window encoder also bounds Java-heap allocations: `TokenWindows` materializes only requested
windows and computes their count arithmetically. Parent batch embedding pools vectors as inference
completes and omits unused per-window outputs; parent content collection has a 512,000-character
batch budget, allowing one whole oversized document. The original token arrays remain input-sized.
This addresses eager-window Java-heap exhaustion, independently of BFC/GPU arena recovery. It does
not establish a whole-corpus speedup or change window geometry, pooling order, or ranking formulas.

### D-010: llama-server context window is a derived resource - SHIPPED (tempdoc 883, PR 1)

- **Choice:** `-c` is no longer a user preference. `ContextWindowPolicy`
  (`modules/app-inference`) produces a **ladder** - top rung 32768 with GPU layers, 8192 at
  `-ngl 0`, then 16384 -> 8192 -> 4096. The top rung is a **budget, not a fit** (measured evidence
  below): the recorded reason for an un-stepped launch is `top-rung`, never `fit` - and the launch steps down one rung on a
  `PROCESS_EXITED` startup failure (the same seam `relaunchWithoutReasoningBudget` uses). The Head
  contributes the top rung at `ORDINAL_AUTO_DETECT` (150, `auto_detected` / `hardware_probe`)
  AFTER GPU detection, so `/api/debug/effective-config` explains the window with the mechanism that
  already explains GPU detection. `UiSettings.contextLength` `0` = auto (settings schema bumped to
  2, migrating the old 4096 default); the settings-to-sysprop promotion and the
  `justsearch.context.size.source` marker are deleted. An explicit
  `justsearch.context.size` above ordinal 150 is a ONE-RUNG ladder: honoured or failed loud.
- **Slots + KV + fit:** `-np 2 -kvu -ctk q8_0 -ctv q8_0 -fa on -fit off`, keys `justsearch.llm.slots` (default 2,
  clamped [1,8]) and `justsearch.llm.kv_type` (default `q8_0`, restricted to llama.cpp cache types).
  `-kvu` is mandatory next to an explicit `-np`: llama-server enables `kv_unified` only when the
  slot count is automatic, so `-c 32768 -np 2` alone gives `n_ctx_seq` 16384 while `/props` still
  reports `n_ctx` 32768. Two slots is a SCHEDULING choice (a background delegate must not evict the
  foreground prompt-cache prefix, tempdoc 841), not a memory one. `-fit off` is what makes the
  ladder mean anything: b8571 defaults `--fit on` (verified against the bundled binary's `--help`:
  `-fit, --fit [on|off] ... default: on`) and it MAXIMIZES rather than fits, so leaving a
  memory-adjusting pass running next to an explicit `-c` risks absorbing the hard abort the
  step-down reads as its signal.
- **Rationale:** the model trains at 262k; the app ran it at 4096 with four engine-chosen slots and
  an f16 KV cache. Measured on the bundled b8571 (tempdoc 883 independent review): `-fa auto`
  resolves to on for CUDA and for `-ngl 0` but is passed explicitly because a q8_0 V-cache aborts
  the launch without it; KV at 32k q8_0 is 544 MiB on both profiles; `--fit` (default on) MAXIMIZES
  rather than fits, choosing 242,944 tokens / 4 GB KV when `-c` is omitted, so an explicit `-c` is
  required. No VRAM arithmetic and no GGUF reader: Qwen3.5 is a Gated-Delta-Net hybrid (8 of 32
  layers carry KV, plus ~50 MiB/slot of recurrent state independent of n_ctx; 32 KiB/token f16,
  17 KiB/token q8_0), so any dense-attention formula is ~4x wrong, and `/props` on b8571 does not
  expose `n_ctx_train`. Free VRAM is recorded on the activation record, never used as an input.
- **What is intent vs observation:** `/api/inference/status.contextWindow` and the runtime
  manifest's `ai.contextWindow` (`{rung, reason, freeVramBytes, slots, kvType}`) are the INTENT.
  `/props` `n_ctx` (published as `llmContextTokens`) and `n_ctx_seq` in the llama-server log are the
  OBSERVATION and stay authoritative. `ServerPropsOps` compares the readback against the LAUNCHED
  rung, not `InferenceConfig.contextSize()` - the latter is stale by construction after a step-down.
  Note `/props.n_ctx` reports the TOTAL context even when `kv_unified` is off (each request then
  gets `n_ctx / n_parallel`), so it cannot by itself prove a request gets the full window.
- **Adopted servers are judged by the floor, not by our rung:** `externalServer.contextTooSmall`
  compares an adopted BYO server's window against `ContextWindowPolicy.MIN_USABLE_ADOPTED_TOKENS`
  (4096, the ladder's bottom rung), not against the derived 32k we would have chosen for a server
  we launched ourselves.
- **Measured live 2026-09-02** (b8571 `8571 (e397d3885)`, Qwen3.5-9B-Q4_K_M, RTX 4070 12281 MiB,
  standalone, argv as shipped):
  - `-c 262144` **LOADS**: 33/33 layers, `n_ctx_seq 262144`, `kv_unified true`, KV 4352 MiB;
    model 5060.88 + KV 4352.00 + recurrent 100.50 + compute 808.02 = **10,321 MiB of 12,281**. The
    model's whole training context fits, so the 32k top rung is a deliberate budget, not a limit.
  - `-c 32768`: KV **544.00 MiB**, `n_ctx_seq == n_ctx == 32768`, `kv_unified true`, 33/33 layers,
    6,206 MiB total. Reproduces the review fold's [R2] figure to the MiB and confirms [R1]'s
    halving does NOT occur with `-np 2 -kvu`.
  - `-c 1000000`: KV 16604.75 MiB -> `CUDA error: out of memory` -> **exit 127**. An unfittable
    rung is a hard, nonzero-exit abort - what `awaitServerHealth` turns into `PROCESS_EXITED` and
    the step-down acts on. `-fit off` does not mask it.
  - **KV cost at q8_0 is exactly linear: 17.0 KiB/token** (544 MiB / 32768 == 4352 MiB / 262144),
    for this model's 8-of-32 KV-carrying layers.
  - llama-server's `n_ctx_seq (32768) < n_ctx_train (262144) -- the full capacity of the model will
    not be utilized` at the top rung is **expected**, not a defect to chase.
- **Why the budget is 32k, not what fits:** (a) prefill latency per RAG ask scales with the prompt,
  and the budget fractions fill whatever window exists, so the rung bounds worst-case latency;
  (b) KV is reserved up front for the whole `n_ctx` whether used or not, and the same card holds
  the embedding / SPLADE / NER encoders, the reranker and VDU batches - 544 MiB at 32k versus
  ~2.2 GB at 128k is headroom kept on purpose; (c) the ladder exists to step DOWN on small cards,
  not to maximize on large ones. Users who want more set `contextLength` (Settings -> AI -> Agent
  -> Context window, see the next bullet) or `JUSTSEARCH_CONTEXT_SIZE`, neither of which has an
  upper clamp below `n_ctx_train`.
- **Where a user sees it (2026-09-02, wave-1 UI follow-up):** Settings -> AI -> Agent -> **Context
  window** (`SettingsSurface.renderContextWindow`, register key `context-window`) reads
  `Auto -> 32,768 tokens (top-rung, 2 slots, q8_0)`, sourced from `/api/inference/status` through
  the shared AI store and the ONE `core.ai.contextWindow` display fact the Brain surface already
  renders (`shell-v0/display/facts.ts`) - so the two readouts cannot word the derivation
  differently. The same fact now carries the derivation parenthetically beside the observed count,
  and `AiRuntime.contextWindowDerived` (`shell-v0/state/aiStateStore.ts`) is its projection of the
  wire block; `RuntimeManifestView.ai.contextWindow` (`api/http.ts`) mirrors the GENERATED schema
  (`schema-types/inference-status-response.ts`, where every field is optional), so the
  browser-side manifest view is no longer a silently-narrower copy of the AI block. It is not a
  field-for-field mirror of the Java record: `ContextWindow.rung` and `.slots` are primitive
  `int` there, optional here, which is the schema's nullability, not the record's.
  The section also carries the override field: blank/0 = Auto, >=512 explicit, written as
  `POST /api/settings/v2 {llm:{contextWindow:N}}` (the wire name `SettingsController.mergeV2Into`
  maps onto `UiSettings.contextLength`), with help text stating the override is honoured verbatim
  or fails loud. This closes 883 D-A.7 / §C.6b (b): `contextLength` had never had a UI control.
- **Now an ADR.** [ADR-0047](../decisions/0047-context-window-is-a-derived-resource.md) records the
  decision, the alternatives it rejected (raise the default; compute the window from free VRAM; let
  `--fit` choose; hand-scale the downstream constants; mirror the rung into a sysprop) and its
  reassess triggers, with premise probes in `governance/adr-probes.v1.json`
  (`adr-0047-fit-off-explicit`, `adr-0047-no-context-size-promotion`, `adr-0047-ladder-policy-test`,
  `adr-0047-budgets-are-window-fractions`, `adr-0047-no-window-blind-threshold`). This entry stays
  the measurement record; the ADR is the decision record.
- **Prompt-side budgets are downstream of this entry, and have ONE authority** (tempdoc 883 PR 2):
  `ContextBudget` (`modules/core`, `io.justsearch.core.util`), built per request from the observed
  window plus that request completion reserve. Every derived quantity is
  `min(fraction x inputBudget, cap)`: hierarchical threshold = `inputBudget` (no cap), section
  target = `min(ib/2, 4096)`, external-context = `min(ib/4, 2048)`, agent read page =
  `min(ib/2, 4096)`, tool-result cap = `min(ib/4, 2048)`, agent completion reserve =
  `min(configured cap, window/4)`. Anything that needs "how much room does this turn have" asks
  `ContextBudget`; a second window walk is a fork. One known survivor:
  `AgentLoopService.java:456-460` still hand-walks `llmContextTokens()` else
  `configuredContextTokens()` for the run ECONOMIC budget — it lacks the fallback rung and can
  NPE-unbox where `ContextBudget` cannot, and routing it through
  `ContextBudget.of(...).windowTokens()` is open work (883 §C.6b).
- **Measured 2026-09-02, q8_0 vs f16 at the 32768 rung** (standalone, 3 x 200 generated tokens,
  `cache_prompt:false`): q8_0 median **69.66 tok/s** at KV 544.00 MiB; f16 median **69.54 tok/s** at
  KV 1024.00 MiB. q8_0 is 0.2% FASTER, inside run-to-run noise, while halving the cache — design
  decision 2 revisit trigger ("if q8_0 exceeds 10%, make f16 the default at 16k and below") does NOT
  fire. See Q-002, whose tok/s half is answered.
- **Evidence:** tempdoc 883 (contract, independent review fold R1-R4, §B pre-impl pass, §C
  post-impl pass, §D review fold, three §Live verification windows). Live acceptance is complete
  except two named gaps: the `JUSTSEARCH_CONTEXT_SIZE` env arm at ordinal 400 (needs an
  orchestrator-owned restart with the variable in the backend process environment — the ordinal
  chain is verified at 150 / 300 / 500, not observed at 400), and a successful rung-walk witness (a
  lower rung actually loading after a higher one aborted; the inter-rung VRAM gap of 272 MiB is
  smaller than the ~280 MiB free-VRAM noise on the dev card, so it needs a different card or a test
  seam). The step-down trigger, its `PROCESS_EXITED` gate and its override branch ARE live-verified.
- **Revisit when:** co-residency is actually measured, since the top rung is a budget held FOR that
  co-residency and a measurement could justify raising it; when lane F adds a second VRAM arbiter -
  the window, `gpuLayers`, slots, KV type and reranker/VDU co-residency all compete for the same
  VRAM and should be one memory plan at activation, not several; or when a packaged model arrives
  whose `n_ctx_train` is below 32768, which the ladder has no source for today. (The q8_0 tok/s
  trigger is retired: measured above, it does not fire.)

### D-002: BGE-M3 VRAM budget — FP16+Flash at 3072 MB arena

- **Choice:** FP16+Flash Attention with 3072 MB arena limit (`JUSTSEARCH_BGE_M3_GPU_MEM_MB=3072`).
- **Rationale:** 8192-token input at FP16 needs ~2.6 GB. 3072 MB provides headroom. Coexists with GTE-ModernBERT (~150 MB) and 7B LLM (~4.5 GB Q4_K_M) on 12 GB GPU.
- **Evidence:** tempdoc 322
- **Revisit when:** model changes or VRAM budget analysis for different GPU tiers.

### D-008: Runtime lifecycle authority — desired-state spec/status + single-writer reconciler — SHIPPED (tempdoc 737)

- **Choice:** Head-side AI-runtime lifecycle is governed by one authority
  (`io.justsearch.app.services.runtimestate`): persisted desired state
  (`UiSettings.chatEnabled`, nullable = never-set, default off; set true on
  successful activation), Condition-shaped `RuntimeStatus`
  (ENGINE/ADOPTION/LEASE/PROCEDURE axes with reason codes),
  `RuntimeGpuLease` (binary grants; size-admitting interface for future
  co-residency), and a single-thread level-triggered `RuntimeReconciler` —
  the ONLY sanctioned caller of `switchToOnlineMode/switchToIndexingMode`
  (ArchUnit-forced, `RuntimeReconcilerGuardrailsTest`). Machine actors hold
  non-spec engine states only inside declared procedures (VDU_BATCH);
  `endProcedure` returns the engine to spec. User semantics: soft-off —
  `chatEnabled=false` disables the chat service; procedures may run the
  engine with reason `engine-up-for-background-processing`, and
  `InferenceCapability` composes spec so chat is never offered during
  soft-off background work.
- **Consequences for runtime agents:** never call `switchTo*` directly
  (build fails); request engine state via spec writes
  (`core.set-chat-enabled` operation / `POST /api/settings/v2`) or a
  procedure. `Mode` is internal FSM machinery projected to the wire; the
  `/api/status` `phase` field is a deprecated alias of the additive
  `engineState/chatEnabledSpec/procedure/engineReason/leaseHolder` fields.
  New representations of runtime state must register in
  `governance/runtime-state.v1.json` (fork gate fails the build otherwise).
- **Re-entrancy contract:** listeners fire under the transition lock;
  reconciliation never runs on a listener thread (level-triggered dirty
  flag). Anti-flap: repeated foreign flips (>3 in 5 min) hold convergence
  with reason `convergence-held-flap-suspected`.
- **Evidence:** tempdoc 737 (§8 diagnosis, §12 design, §14 derisk, §15
  implementation log; live Checkpoint 1 record).
- **Revisit when:** sized GPU grants (12 GB+ co-residency) are implemented —
  the lease interface admits sizes but only binary logic ships; or when the
  deprecated wire aliases (`phase`, `starting`) retire per §12d.

### D-009: Observed execution provider on runtime status — SHIPPED (tempdoc 805 W3)

- **Choice:** `/api/ai/runtime/status.onnxFeatures[]` carries additive OBSERVED fields
  (`executionProvider`, `gpuFallback`, `fallbackReason`) beside the intent/discovery fields,
  projected from `EncoderRuntimeExplainer` — now the single authority for "what EP is this
  encoder actually on", consumed by BOTH `/api/inference/encoders` (tempdoc 422) and the
  runtime status. New Head-side seam: `EncoderRuntimeCache` / `WorkerEncoderRuntimeCache`
  (2 s TTL, last-known-good) over the Worker's `getSessionPolicies` +
  `getEncoderOrtCudaViews` RPCs. No proto change: per-encoder `OrtCudaStatus` already crossed
  as `StatusResponse.gpu.*OrtCuda` → `OrtCudaView`.
- **Rationale:** round 11 (tempdoc 734 R11-F3): an upgraded machine ran ALL ONNX inference on
  CPU (retained runtime pack missing the four ORT natives PR #276 moved to
  `ort-native-cuda12-v1.24.3.zip`) while the status reported `cuda12`/`active`/`gpuLayers:99`
  — `sessionActive`/`modelActive` is TRUE for a CPU-fallback session and is explicitly NOT an
  EP claim; the honest reading is the observed triple. A status field is an intent or an
  observation, never an intent presented as an outcome (805 Part D principle 3).
- **Companion guarantees:** the ORT native pack is content-checked at publish
  (`scripts/release/check-ort-native-asset.mjs`, DLL set parsed from
  `OrtCudaHelper.ORT_NATIVE_DLL_SET` — no second hardcoded list), per the ORT-bump coupling
  checklist in `cut-a-release.md`. Install truth split into two axes:
  `installedFully` (install history, contract-measured per FILE, entry-kind aware) vs
  `repairNeeded` (disk reality vs current registry, contract-independent) —
  `InstallCompleteness` in app-services, registered as a logic seam.
- **Evidence:** tempdoc 805 Parts G.3/H/I; round-11 mechanism in tempdoc 734.
- **Revisit when:** the sandbox `onnx-ep-fallback-vs-status` must-watch converts to an API
  assertion (its note names the condition), after which the watch entry retires.

---

## Open Questions

Unanswered questions that need investigation. Agents should prefer
picking up items here over inventing new experiments.

### Q-001: ~~Should GPU sessions warm up at startup?~~ — ANSWERED

- **Answer:** Yes. Warm-up inference added to `initDeferredModels()` in tempdoc 360 (Worker migration). All ORT GPU sessions now run a dummy inference at startup to prime CUDA kernels and BFC arena. First-query DEADLINE_EXCEEDED no longer occurs.
- **Evidence:** tempdoc 360 (warm-up implementation); tempdoc 356 (identified the fix).

---

### Q-002: ~~What does q8_0 KV cost in tok/s on the dev GPU?~~ ANSWERED — does the 32k top rung hold under co-residency? STILL OPEN

- **Half answered (2026-09-02, tempdoc 883 live window 2, F12).** q8_0 costs **nothing**: median
  69.66 tok/s vs f16 69.54 at the same 32768 rung (3 x 200 generated tokens each,
  `cache_prompt:false`, RTX 4070), i.e. 0.2% FASTER and inside run-to-run noise, while halving the
  KV cache (544.00 vs 1024.00 MiB). The design revisit trigger ("if q8_0 exceeds 10%, make f16 the
  default at the 16k rung and below") does not fire; **q8_0 stays the default at every rung.**
  Recorded in D-010.
- **Still open: (b), the co-residency half.** Nothing has yet measured whether 32768 holds with the
  reranker and a VDU batch co-resident, which is the reason the rung is 32768 rather than 131072
  ([ADR-0047](../decisions/0047-context-window-is-a-derived-resource.md) Decision 2(b), and its
  first reassess trigger). The full 262,144-token context measurably FITS on the dev card in
  isolation (10,321 of 12,281 MiB), so this is a budget question, not a capacity one.
- **Original framing below.**

- **Context:** tempdoc 883 decision 2 ships `-ctk q8_0 -ctv q8_0` by default and decision 1 ships a
  32768 top rung, both argued from launch-time fit (KV at 32k q8_0 measured at 544 MiB) rather than
  from throughput or from behaviour under load.
- **What to measure:** (a) generation tok/s with `-ctk/-ctv q8_0` vs `f16` at the same rung, on the
  dev RTX 4070 - the design says that if q8_0 costs more than 10%, `f16` becomes the default at the
  16k rung and below; (b) whether the 32k rung still fits with the reranker and a VDU batch
  co-resident, or whether the top rung should stay at 16k until that is measured (the owner's own
  open question in 883).
- **Instrument:** `jseval llm-bench` / `llm-gate` (F-012) for tok/s; the recorded
  `contextWindow.reason` on `/api/inference/status` for step-downs.

### Q-003: How much does a non-NVIDIA GPU beat the CPU tier on an actual AMD / Intel machine? OPEN (tempdoc 903)

- **Context:** F-015 measured Vulkan (chat) and the WebGPU plugin EP (encoders) only on the
  NVIDIA dev card, which bounds backend overhead but says nothing about AMD RDNA / Intel Arc-Xe
  performance, driver behaviour (Intel coopmat TDR, llama.cpp #20554; Q8 slowdown, #24002), or
  iGPU memory reporting (#16832). Tempdoc 903 ranks "Vulkan for chat" first on the strength of
  the "chat vs no chat" cliff (`InstallPlanner.java:207-231` skips every GGUF unless the profile
  is `GPU_FULL`), not on vendor numbers.
- **What to measure (exact commands in 903 §2.3):** on the first AMD or Intel box, llama-bench
  Vulkan vs CPU for the 9B and 4B packaged models (Intel: also with `GGML_VK_DISABLE_COOPMAT=1`
  and a Q6_K quant); `jseval llm-bench` end to end with the Vulkan variant forced via
  `-Djustsearch.server.exe`; the WebGPU probe (903 Appendix A) on the fp16 embedder / reranker /
  NER; `llama-server --list-devices` total/free versus OS-reported memory (sizes the detection
  problem on UMA parts). Record the hardware inventory as the row key.
- **Also open here:** whether `GPU_TOP_RUNG` (32768) needs a UMA/iGPU rung (prefill on an
  8060S-class iGPU is ~5-10x slower than a 4070); WebGPU session lifecycle under the
  `main_gpu_active` lease and co-residency with llama-server (F-010 budgets are CUDA-arena
  numbers). Owner: 903 §6's opus chunk records what it could and could not measure; the vendor
  rows stay open until hardware exists.

## Future Work

Identified improvements not yet started. Lower priority than Open
Questions — these are "we should eventually" not "we need to know."

- **FW-001: Arena shrinkage tuning** — Current arena limits (BGE-M3 3072MB, SPLADE 2048MB) are conservative. Actual peak may be lower with arena shrinkage enabled. Profile and right-size. Source: tempdoc 311.
- **FW-002: CPU fallback latency budget** — GTE-ModernBERT at 149M params on CPU: ~160-300ms for top-20. Borderline for interactive search. Measure and decide if CPU CE should be disabled under latency pressure. Source: tempdoc 309 §15.
- **FW-003: ORT CUDA runtime pack** — GPU-accelerated embedding via ORT+cuDNN. Self-check and status wiring in place but ~2.2 GiB pack not assembled. Blocked on cuDNN redistribution licensing. Source: RAG-001 (retired from issues/).
- **FW-004: Speculative decoding** — Eagle-3 could improve generation throughput but needs 400MB-1.3GB VRAM (conflicts with 8GB budget) and isn't integrated into llama-server API yet. Deferred until Eagle-3 llama-server integration + user base with >12GB VRAM. Source: RAG-009 (retired from issues/).
