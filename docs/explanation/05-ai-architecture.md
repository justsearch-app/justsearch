---
title: AI Architecture
type: explanation
status: stable
description: "Hybrid Inference (CPU/GPU) and VRAM management."
---

# AI Architecture

JustSearch implements a **Hybrid Inference Architecture** to provide advanced AI features (RAG, Vision, Summarization) on consumer hardware with limited VRAM (e.g., 8GB).

## The Problem: VRAM Contention
Modern local AI requires two distinct types of models:
1.  **Embedding Model:** ONNX Runtime encoder assets selected from the model manifest. High-throughput, used for vector search and chunk embeddings in the Worker process.
2.  **Generative LLM:** (e.g., `Qwen_Qwen3.5-9B-Q4_K_M.gguf`, the current packaged default). Latency-sensitive, used for Chat, Q&A, Summarization, and VDU (served by `llama-server.exe`). Models that emit `reasoning_content` support chain-of-thought reasoning (see §Reasoning Pipeline below).

On an 8GB GPU, loading both simultaneously (or leaving both GPU-enabled) can cause OOM (Out Of Memory) errors or fallback to ultra-slow system RAM.

## The Solution: Mutual Exclusion

JustSearch enforces a strict **Single-tenant GPU Policy** across processes:
* The **Main Process** owns Online inference (`llama-server.exe`) via `modules/app-inference` and `InferenceLifecycleManager`.
* The **Worker Process** owns indexing + Worker-side ONNX Runtime encoders, and cooperates via the MMF `main_gpu_active` flag (offset `24`, `MmfWorkerSignalLayoutV1.OFFSET_MAIN_GPU_ACTIVE`).

### The Runtime Authority (desired state, status, procedures)

Since tempdoc 737, who runs on the GPU is governed by one Head-side authority
(`io.justsearch.app.services.runtimestate`), not by callers switching modes
directly:

* **Desired state (spec):** the persisted user intent `chatEnabled`
  (`UiSettings`; written by the `core.set-chat-enabled` operation, the
  Settings API, and on successful runtime activation). "Shut Down AI" writes
  intent — it does not command a transition.
* **Observed status:** Condition-shaped per-axis state (ENGINE
  `Down/Starting/Healthy/Recovering` with a reason code, ADOPTION, GPU LEASE
  holder, and any in-flight PROCEDURE), exposed additively on `/api/status`
  (`engineState`, `chatEnabledSpec`, `engineReason`, `procedure`,
  `leaseHolder`; the older `phase` string is a deprecated alias).
* **Reconciler:** a single-writer, level-triggered loop converges the engine
  toward `spec ∧ policy`. It is the only permitted caller of the mode-switch
  primitives (ArchUnit-enforced). Autonomous work (e.g. the VDU batch) runs
  inside a declared **procedure**; when the procedure ends, the engine
  returns to spec — the system can no longer park itself in a state the user
  didn't ask for.
* **Soft-off semantics:** with `chatEnabled=false`, background procedures may
  still run the engine (idle/energy-gated); status carries the reason
  `engine-up-for-background-processing`, and the chat capability is NOT
  offered while it does.

### Modes (internal machinery)

The `Mode` enum remains the internal FSM vocabulary of
`InferenceLifecycleManager` beneath the authority; externally the Worker only
ever sees the one-bit GPU lease (`main_gpu_active`).

| Mode | Active Model | Purpose | Process |
| :--- | :--- | :--- | :--- |
| **Indexing Mode** | Embedding Model | Vectorizing documents in background | Worker |
| **Online Mode** | Generative LLM | Interactive Chat, Q&A, Summarization, Vision | Main (llama-server) |
| **Offline Mode** | none | No GPU work; background queues can accumulate | Main + Worker |

### Transition Protocol

When the user escalates to an Ask/agent turn in the unified "Search" window (tempdoc 687 — there is no separate Chat tab):
1.  **Main:** Begins a mode transition via `ModeStateMachine` (validates not already transitioning, stores previous mode for rollback).
2.  **Main:** Signals Worker via MMF (`main_gpu_active = 1`).
3.  **Worker:** Unloads/suspends GPU-backed ORT encoder work as needed and skips embedding work while the flag is set.
4.  **Main:** Starts `llama-server.exe` (or **adopts** an already-running instance on the configured port).
5.  **Main:** Polls `GET /health` until 200 OK (timeout configurable via `justsearch.inference.health_check_timeout_ms` system property, default 30000ms; progress logged every 10s during wait — tempdoc 369), then reads `GET /props` (best-effort) to learn the effective `n_ctx` and `model_alias`.
6.  **Main:** Completes the transition to ONLINE via `ModeStateMachine`. On failure at any step, rolls back to the previous mode.

When the user closes Chat or minimizes the app:
1.  **Main:** Kills `llama-server.exe`.
2.  **Main:** Signals Worker (`OFFSET_GPU_ACTIVE = 0`).
3.  **Worker:** Reloads Worker-side ORT encoders as needed and resumes backfill.

## Components

### 1. `llama-server` (The Engine)
We use the compiled binary from `llama.cpp` as a separate process (`llama-server.exe`) for maximum performance and isolation.

**Current shipping posture: chat requires a supported NVIDIA GPU.** The bundled baseline `llama-server` binary is a CPU-capable build, but no install path ever offers a chat/GGUF model download without a CUDA-functional NVIDIA GPU — `DownloadProfile`/`InstallPlanner` skip every GGUF package on a CPU-only or non-NVIDIA machine with the reason "CPU chat is not supported in this build" (`Necessity.java`, `InstallPlanner.java`), and `AiInstallService.applyCudaServerExe()` is what points chat at the `cuda12` `llama-server.exe` in the first place. So without a supported NVIDIA GPU, JustSearch has no chat/RAG capability at all — the product is **search-only** (search and its embedding/reranking pipeline run on CPU by design and are unaffected). JustSearch stages and installs the NVIDIA CUDA 12.4 runtime as a versioned `cuda12` variant through the offline GPU Booster Pack path; the existing control plane supplies flags such as `-ngl` once that variant is installed and active.
*   **Protocol:** OpenAI-compatible API (`/v1/chat/completions`).
*   **Diagnostics:** `GET /health` and `GET /props` (includes `n_ctx` + `model_alias`).
*   **Binary discovery:** `InferenceConfig.findServerExecutable()` searches canonical paths and `variants/` subdirectories. When GPU is configured (`gpuLayers > 0`), prefers `variants/cuda12/` for CUDA-optimized binary. Falls back to baseline binary. **Dev-layout path** (active only when `justsearch.repo.root` system property is set): searches `{repoRoot}/modules/shell/src-tauri/resources/headless/` (Tauri resource bundle). Added in tempdoc 369 for eval backend LLM support. (The former `{repoRoot}/third_party/llama.cpp/build/` local source-build path was removed with the vendored llama.cpp tree — tempdoc 632; the runtime is the pinned upstream prebuilt download.)
*   **Crash diagnostics:** `waitForServerHealth()` parses llama-server stderr for known failure patterns (e.g., `unknown model architecture`) and surfaces user-facing error messages instead of opaque "failed to load model" errors.
*   **Arguments:**
    *   `-m <model_path>`: Main GGUF model file.
    *   `-c <ctx_size>`: Context window - a **derived resource**, not a preference (see the section below).
    *   `-ngl <layers>`: Number of GPU layers (offload).
    *   `-np <slots>`: Parallel slots. `2` by default (`JUSTSEARCH_LLM_SLOTS`) so a background delegate cannot evict the foreground turn's prompt-cache prefix.
    *   `-kvu`: Unified KV cache. Required *because* `-np` is explicit: passing `-np` at all disables llama-server's automatic `kv_unified`, and without `-kvu` two slots halve the window a request actually gets (`n_ctx_seq`) while `/props` still reports the full `n_ctx`.
    *   `-ctk <type> -ctv <type>`: KV cache type, `q8_0` by default (`JUSTSEARCH_LLM_KV_TYPE`).
    *   `-fa on`: Flash attention, explicitly on - a quantized V-cache aborts the launch without it, so this is never left at `auto`.
    *   `-fit off`: Memory fitting off. llama-server defaults `--fit on` ("adjust unset arguments to fit in device memory") and it MAXIMIZES rather than fits - with `-c` omitted it chose 242,944 tokens and 4 GB of KV. JustSearch sets `-c` explicitly and needs a rung that does not fit to produce a hard, detectable abort, which is the signal the context ladder steps down on; a memory-adjusting heuristic running beside it could absorb that signal instead.
    *   `--mmproj`: Vision adapter path (for Qwen/Llava).
    *   `--port <port>`: HTTP port.
    *   `--jinja`, `--metrics`, `--host 127.0.0.1`, and (when thinking is enabled) `--reasoning-format deepseek --reasoning-budget N`.
*   **VDU mode flags** (applied only during VDU batch processing, not global):
    *   `-np 1`: Single slot (multi-slot causes alternating 500s on vision) - pins the common `-np` above rather than adding a second one
    *   `--cache-ram 0`: Disable prompt cache (prevents silent crashes after ~7 pages)

#### The context window (`-c`) is derived, not configured

The packaged model trains at 262k tokens; the app used to run it at 4096 because that was the
shipped value of a `UiSettings` field with no UI control, promoted to a system property so it
outranked every other source. Since tempdoc 883 the window is a resource the runtime fits:

* `ContextWindowPolicy` (`modules/app-inference`) produces a **ladder**: top rung 32768 with GPU
  layers, 8192 at `-ngl 0` (CPU prefill at 32k is minutes per RAG ask), then 16384 -> 8192 -> 4096.
* The Head contributes the top rung at ordinal 150 (`auto_detected` / `hardware_probe`) after GPU
  detection, so `/api/debug/effective-config` explains the window with the same mechanism that
  explains GPU detection.
* If llama-server refuses a rung it exits immediately; `LlamaServerOps.waitForServerHealth` steps
  down one rung and relaunches. A rung that does not fit costs context, not inference. This is why
  `-fit off` is passed: the step-down reads a hard abort, so llama-server's default memory-fitting
  pass must not be running alongside it.
* An explicit `justsearch.context.size` (env, `-D`, `settings.json`, YAML) is a **one-rung ladder**:
  honoured or the launch fails loud. `UiSettings.contextLength` `0` means auto.
* What was launched is published on `/api/inference/status` as `contextWindow`
  (`{rung, reason, freeVramBytes, slots, kvType}`, where reason is `top-rung`, `override` or
  `stepped-from:<n>`) and on the runtime manifest as `ai.contextWindow` - "what did this
  installation end up with", which is a fact about the machine rather than a setting anyone can
  read back out of config. That is the INTENT. What the server reports - `/props` `n_ctx`, and
  `n_ctx_seq` in its log - stays the authority for what a request actually gets, and is published
  separately as `llmContextTokens`.
* Caveat on `/props.n_ctx`: it reports the server's TOTAL context even when `kv_unified` is off, in
  which case each request actually gets `n_ctx / n_parallel`. A matching `n_ctx` is therefore NOT
  evidence that a request gets the full window; the guarantee is the argv (`-kvu` always
  accompanying an explicit `-np`) plus reading `n_ctx_seq` from the llama-server log.
* No VRAM arithmetic and no GGUF reader: the packaged model is a Gated-Delta-Net hybrid whose KV
  footprint no dense-attention formula predicts within 4x, and `/props` does not expose
  `n_ctx_train`. Free VRAM is recorded for diagnosis, never used as an input.
    *   `chat_template_kwargs: {"enable_thinking": false}`: Ensures VLM output goes to `content` field

### 2. `InferenceLifecycleManager` (The Manager)

Delegates to package-private collaborators: **`LlamaServerOps`** (process spawn/kill, health checks, zombie protection), **`OnlineModeOps`** (chat/vision completion requests, streaming, lock acquisition), **`TokenEndpointOps`** (tokenize/apply-template probing with caching), **`ServerPropsOps`** (/props parsing, model ID extraction), and **`ModeStateMachine`** (validated mode transitions).

*   **Responsibilities:**
    *   Spawning/Killing `llama-server`.
    *   **Zombie Protection:** Uses `taskkill /F /PID` on Windows to ensure VRAM is released.
    *   **Health Checks:** Waits for `/health` during startup; runs periodic health checks for hung detection.
    *   **External Instance Adoption:** If the configured port is already serving a healthy `llama-server`, it can adopt it instead of starting a duplicate process (prevents restart loops after a forced kill).
        * By default, adoption is verified via `GET /props` (not just `GET /health`) to avoid accidentally adopting an unrelated HTTP service.
        * Dev escape hatch: `-Djustsearch.inference.external.allow_health_only_adoption=true` (allows health-only adoption when `/props` is missing/unparseable).
        * Adopted servers are still monitored; if the external server becomes unhealthy mid-session, inference transitions to Offline (no process handle to restart).
    *   **Crash Recovery:** If the owned server crashes while in Online mode, it stops and restarts it (with cleanup first). Health checks and crash recovery run on independent schedulers (`healthScheduler`, `recoveryScheduler`), preventing a slow health probe from blocking recovery.
    *   **Mode State Machine:** `ModeStateMachine` validates all mode transitions (`beginTransition` → `complete`/`rollback`, `forceOffline` for emergencies). No raw state assignments — all transitions go through validated operations with precondition checks.
    *   **Effective Runtime Info:** Reads `/props` to capture best-effort runtime `n_ctx` and `model_alias`, which is surfaced via `/api/inference/status` and used as the request `model` id.
    *   **Hot-apply (current):** The Local API exposes `POST /api/inference/reload`, which re-reads persisted `/api/settings/v2` values and calls `OnlineAiRuntimeControl.applyRuntimeOverrides(...)` with `RESTART_IF_ONLINE`.
        * This updates model/context/gpuLayers without a full backend restart.
        * It **must not** auto-start `llama-server` when the system is Offline; it only restarts when already Online.
        * If Online AI adopted an external `llama-server` instance (no process handle), restart is rejected; use `POST /api/inference/detach` to switch to a managed server on a new port.
    *   **Server-Model Compatibility Warnings:** Two runtime warnings help detect common misconfiguration:
        1. **GPU Variant Mismatch** (`LlamaServerOps.startLlamaServer()`): Warns when `gpuLayers > 0` but server executable not under `variants/` subdirectory (indicating CPU variant). Does not block startup.
        2. **Thinking Model Mismatch** (`ServerPropsOps.warnIfThinkingMismatch()`): Warns when `USE_THINKING=true` but loaded model name lacks "Thinking" substring. Does not block startup.

        These are non-fatal warnings logged at WARN level to aid troubleshooting. System continues with potentially degraded behavior.

### 3. Embedding Backend (Worker, ONNX Runtime)
*   **Class:** `io.justsearch.indexerworker.embed.EmbeddingService`
*   **Backend:** ONNX Runtime; sessions built via the Worker composition root (`InferenceCompositionRoot.compose(...)`) and applied by `OrtSessionAssembler` in `modules/ort-common` — see the composition subsection below and register entry D-007.
*   **Default:** CPU-only (GPU offload is opt-in via `JUSTSEARCH_EMBED_GPU_ENABLED`).
*   **GPU Coordination:** `IndexingLoop` unloads/reloads the embedding backend based on `WorkerSignalBus.isMainGpuActive()`.
*   **Model File Selection:** `ModelManifest.loadOrDefault()` reads `model_manifest.json` from the model directory to determine which `.onnx` file to use for CPU vs GPU. External directories without a manifest fall back to convention (`model.onnx` CPU, `model_fp16.onnx` GPU).
*   **Long-document memory:** Parent batch embedding uses pooled-only ONNX results, retaining a running sum per document instead of unused chunk vectors. Token windows are copied only for the current inference batch (at most eight); window counting is arithmetic. Parent backfill admits at most 512,000 characters per batch, or one whole oversized document bounded by extraction policy. Original token arrays and explicitly requested chunk-vector outputs still scale with input; this is not a constant-memory tokenizer.

### ONNX Runtime Infrastructure (`ort-common`)

All ORT consumers (embedding, SPLADE, NER, BGE-M3, cross-encoder reranker, citation scorer) share a single session-construction pipeline in `modules/ort-common` (`io.justsearch.ort`). Tempdoc 397 collapsed six divergent construction paths onto the typed pipeline below; see [24-worker-inference-composition.md](24-worker-inference-composition.md) for the full explainer and register entry D-007 in `docs/reference/inference-runtime-register.md` for the decision rationale.

**Construction pipeline** (single production path, no customiser lambdas, no SPI discovery):

1. `RuntimePolicyResolver.resolve(cfg, hardware)` → `RuntimePolicy` — JVM-wide session settings (arena, CUDA provider, session, profiling).
2. `ModelSessionPolicyResolver.resolve(role, cfg, hardware, variant)` → `ModelSessionPolicy` — per-encoder GPU / CPU / lifecycle / RunOptions.
3. `InferenceCompositionRoot.compose(cfg, hardware, contract, modelsDir, arbiter)` → `InferenceSurface`. Resolves each encoder's `VariantSelection` (via `VariantSelector`, or `DevModeVariantProbe` when the install contract is absent), calls `OrtSessionAssembler.buildManager(Composition, arbiter)`, wraps sessions as `SessionHandle`, constructs encoders with pre-built `<Role>Assembly` (shape + tokenizer + vocabulary / label-mapping), returns the typed surface.
4. Encoders consume `SessionHandle` only — they do zero filesystem I/O in constructors. `ClosurePropertyTest` (ArchUnit) enforces this.

**Key classes:**

| Class | Purpose |
|-------|---------|
| `InferenceCompositionRoot.compose(...)` | Single production entry point; returns `InferenceSurface` |
| `InferenceSurface` | Typed bundle of ready-to-use encoders + `PolicySnapshot` + `List<SessionHandle>` for lifecycle management |
| `OrtSessionAssembler` | The only caller of ORT setters in production. Entries: `buildManager(Composition, GpuArbiter)`, `verifyModelSession(...)` (Gradle verify-model task), `probeModelNames(...)` |
| `SessionOptionsApplier` | Walks `RuntimePolicy` + `ModelSessionPolicy` fields → ORT setters. Every option value flows from a policy field (§6 closure property) |
| `RuntimePolicy`, `ModelSessionPolicy` | Typed policy records consumed by the applier |
| `SessionHandle` | Interface encoders consume: `acquire()`, `acquireCpu()`, `status()`, `releaseGpu()`, `reportCpuSessionFailure()`, `setLifecycleCallback(...)` |
| `NativeSessionHandle` | Concrete `SessionHandle` impl. Package-private `Builder`; external callers reach it only through the assembler |
| `GpuArbiter` | Typed replacement for `BooleanSupplier shouldUseGpu` |
| `ModelManifest` | Reads `model_manifest.json` for CPU/GPU model file selection (moved from worker-core, 359) |
| `GpuSessionConfig` | Record: `(gpuDeviceId, gpuMemLimitBytes)` |
| `OrtCudaHelper` | Windows DLL preloading, native path resolution, DLL presence checks |
| `OrtCudaStatus` | Structured CUDA observability record (`ready()`, `missingDlls()`, `providerFailed()`, `released()`) |
| `OnnxSessionCache` | Session creation with per-machine graph-optimisation caching (uses `BASIC_OPT` for FP16 models, `EXTENDED_OPT` for others) |

**Diagnostics:** `GET /api/debug/session-policies` returns the resolved `RuntimePolicy` and every `ModelSessionPolicy` as JSON, proxied from the Worker's live `InferenceSurface` via the `GetSessionPolicies` gRPC rpc (§14.28 U4). Diffing two runs is diffing two records; no log archaeology.

**Encoder runtime state:** `GET /api/inference/encoders` (tempdoc 422) returns a derived per-encoder explainer that correlates the policy snapshot with the runtime `OrtCudaView` probe to answer "why is encoder X currently on CPU/GPU/unavailable?" with one structured response. Keys are `EncoderRole.consumerName()` (`embed`, `bgem3`, `splade`, `ner`, `reranker`, `citation`) so operators can correlate the response with `ort.session.*` metric lines in `metrics-worker.ndjson`. Read-only and user/agent-facing (not under `/api/debug/`) by design — the underlying `/api/debug/session-policies` is dev-namespaced and exposes the raw policy snapshot.

Production session option values are driven by `RuntimePolicy` + `ModelSessionPolicy` — `SessionOptionsApplier` is the single setter site:
- `arena_extend_strategy = kSameAsRequested` (exact allocation; two sessions share GPU)
- `enable_cuda_graph = 0` (allows arena shrinkage between calls)
- `use_device_allocator_for_initializers = 1` (weights bypass arena)
- `setMemoryPatternOptimization(false)` (variable-length sequences)
- `setInterOpNumThreads(1)`, `allow_spinning = 0` (reduce CPU contention)

**FP16 CPU optimization caveat:** `OnnxSessionCache.optimizeAndCache()` uses `BASIC_OPT` (instead of `EXTENDED_OPT`) for FP16 models on CPU. This reduces first-run graph optimization from 30-60+ minutes to ~5-10 minutes. FP16 embedding on CPU is still broken/unsupported — ORT CPU EP has no native FP16 support and inserts Cast (FP16->FP32) nodes before every operation, causing severe runtime overhead. The correct solution is to ship FP32 model variants for CPU (SPLADE already does this correctly; embedding does not yet — see model-inventory.md `gte-multilingual-base` entry). See [ADR-0019](../decisions/0019-cpu-gpu-model-selection-strategy.md) for the full CPU/GPU model selection decision.

Model file verification: `./gradlew.bat :modules:worker-core:verifyModel -Pmodel=<path> -Pgpu=true`

### 4. Reranker GPU Coordination (Worker-side, default enabled)

The cross-encoder reranker runs in the **Worker process** (360), sharing
GPU arbitration with embedding, SPLADE, and NER via the signal bus.
GPU is enabled by default (`JUSTSEARCH_RERANK_GPU_ENABLED=true`).

GPU arbitration:
- **Startup initialization**: GPU session is created in `initDeferredModels()` with a warm-up inference to compile the ORT execution plan.
- **Signal bus arbitration**: `selectSession()` checks `!signalBus.isMainGpuActive()` — same as all other Worker ORT consumers.
- **VRAM release**: `releaseGpuSession()` frees the GPU session when Main process claims GPU (e.g., `llama-server` going online).
- **Fallback**: Reranking continues on CPU session while GPU is released or unavailable.
- **Head-side invocation**: The Head calls the Worker's `Rerank` gRPC RPC, sending pre-built document texts (title + snippet). The Head has no ORT sessions.

Defaults: `gpu_mem_mb=2048`, `max_seq_len=512`. At seq=512, GPU inference
for 20 docs takes ~2.2s (vs ~42s on CPU at seq=2048).

Observability: `OrtCudaStatus` record tracks GPU state, visible in `/api/status` under `rerankerOrtCuda`.

## Reasoning Pipeline

JustSearch supports **chain-of-thought reasoning** via the configured chat model — any model that emits `reasoning_content` in OpenAI-compatible SSE streams. This is gated by the `JUSTSEARCH_USE_THINKING` environment variable (default: `true`).

### Activation

When `USE_THINKING=true`, `LlamaServerOps` adds `--reasoning-format deepseek` to the `llama-server` command line. This tells `llama-server` to emit reasoning tokens as a separate `reasoning_content` field in SSE deltas, instead of inline `<think>` tags in the `content` field.

### Streaming Architecture

`OnlineModeOps` parses SSE deltas from `/v1/chat/completions` and routes content to `StreamCallbacks` — a record with 6 callbacks defined in `OnlineAiService`:

| Callback | Purpose |
|----------|---------|
| `onChunk` | Response text content |
| `onReasoningChunk` | Chain-of-thought reasoning (separate from content) |
| `onToolCallDelta` | Tool call JSON deltas (agent loop) |
| `onUsage` | Token usage metadata |
| `onComplete` | Stream finished |
| `onError` | Stream error |

`AgentLlmCaller` (the agent loop's LLM-caller collaborator) accumulates reasoning chunks into a `StringBuilder` and logs the full reasoning at DEBUG level after each agent turn. Its durable form is the run journal: `AgentRunStore` records every `reasoning_chunk` event, and `AgentInteractionMapper.fromRunEvents` folds those records into per-step `{text, durationMs}` blocks at read time, attaching them to the turn they belong to (a read-time projection, never a second durable representation).

On the conversation path, `ConversationEngine` forwards each reasoning chunk to the SSE sink as a `reasoning_chunk` event AND accumulates it, so `persistedAssistant` writes the turn's thinking onto the conversation record as `reasoning: [{text, durationMs}]` — absent when the model did not think. Both surfaces therefore render the same blocks live and after a reload, from one authority. The persisted key is stripped again at the LLM-input boundary (`buildLlmInput` projects every context message to the model contract's keys), so a persisted turn's reasoning is never re-sent to the server on the next turn of a conversation.

### Sampling Parameters

`SamplingParams` (`modules/app-api`) defines per-workload presets injected into HTTP request bodies at 3 injection points in `OnlineModeOps` (`streamChatWithTools`, `streamChat`, `sendChatRequest`):

| Preset | Temperature | Top-P | Used by |
|--------|-------------|-------|---------|
| `THINKING` | 0.6 | 0.95 | Reserved preset for explicit reasoning-heavy calls (not currently wired in production paths) |
| `AGENT` | 0.2 | 0.9 | Agent chat (`AgentLoopService`) |
| `DETERMINISTIC` | 0.1 | 0.9 | Summarization, Q&A |
| `VDU` | 0.0 | 0.9 | Vision document understanding (deterministic OCR output) |

When `SamplingParams` is null, no sampling parameters are sent (server defaults apply).

### Think-Tag Handling

Despite `--reasoning-format deepseek`, `<think>` tags can leak into content in edge cases (llama.cpp bug [#13189](https://github.com/ggml-org/llama.cpp/issues/13189), non-streaming responses, or a build that renders thinking inline). Both defenses live in `OnlineModeOps`, one per transport:

1. **`sendChatRequest()`** — strips `<think>...</think>` via regex from non-streaming responses before returning.
2. **`ThinkTagStreamFilter`** — a stateful filter over the streaming content channel. It is *not* a per-chunk regex: tags straddle SSE frame boundaries (`<thi` + `nk>`), so it holds back only a suffix that could still become a tag. Captured thinking is **rerouted to the reasoning channel**, not deleted, so a build that leaks inline behaves like one that emits `reasoning_content`. Content with no tags passes through byte-identically.

The agent loop no longer strips tags of its own — two strippers over the same fact is two authorities, and only the stream-level one can see a tag that arrived in two pieces.

### VDU Reasoning Suppression

`VduProcessor` prepends `/no_think` to both VDU prompt constants (Pass 1: OCR extraction, Pass 2: metadata enrichment). This is a Qwen3 soft switch — the model was trained to recognize `/no_think` in system or user messages and suppress extended reasoning.

Suppression avoids the "long-wrong trajectory" problem where thinking chains degrade perception quality on pure OCR tasks, and reduces latency and token consumption.

**Known limitation:** `/no_think` in a user message mid-conversation does NOT suppress reasoning (Qwen3 Jinja template checks the system prompt only). The VDU pipeline always sends it as the first message, so this limitation does not apply.

### Empty Output Recovery

`AgentLlmCaller.callLlmWithRetries` retries on empty content (no text and no tool calls) per `AgentRetryPolicy`'s `EMPTY_RESPONSE` decision, and the retry **changes the request**: attempt 2 goes out with `enableThinking=false`. An empty turn is usually not transient server state — a local model that returns nothing on a given prompt shape returns nothing again — so a byte-identical re-issue was a delay, not a second chance. Suppressing the thinking prompt is the one change measured to move it (tempdoc 881 §A.3). (There is no `/no_think` injection on this path.)

An empty turn is usually the model emitting its tool call *inside* an unterminated thinking block, in the XML `<tool_call><function=NAME>` grammar, which `--reasoning-format deepseek` routes wholly to `reasoning_content` where llama-server's tool-call parser cannot see it. `AgentLlmCaller` therefore recovers a wrapper-delimited call from the reasoning channel when a turn has no structured call and no text, and reports the runtime's `finish_reason` rather than guessing at a cause. Do **not** describe this failure as reasoning-token exhaustion: measured on Qwen3.5-9B at n_ctx 4096, it is `finish_reason=stop` after 35–55 of 1024 completion tokens, with the prompt at half the window (tempdoc 881 §A.2 refutes 868 §D.3).

### Reasoning Budget

`llama-server` accepts `--reasoning-budget N` to control reasoning token generation at the template level. Default: **512** — bounded reasoning, on (`JUSTSEARCH_REASONING_BUDGET` env var / `-Djustsearch.llm.reasoning_budget`).

**The budget is shared with the answer.** Reasoning tokens and answer tokens come out of the same completion ceiling (`max_tokens`, default 1024 in the conversation engine); the server spends it on reasoning first. That is why the number matters more than the switch:

| Budget | Measured behaviour (b8571, Qwen3.5-9B, default ceiling) |
|---|---|
| `0` | No reasoning. The server injects a `/no_think` equivalent at the chat template level — more reliable than prompt-level suppression, and the system prompt's `/no_think` stays as defense-in-depth. |
| `512` (default) | Binds at exactly 512 reasoning tokens and leaves room for a complete answer (3/3 non-empty at the parameterless configuration). |
| `-1` (unbounded) | Reasoning consumed the entire completion budget and **not one answer token was produced**, 4/4 — terminating with a normal `done` event and no error. |

Because that failure is silent, config resolution **refuses** an unbounded budget and any value at or above the engine's default completion ceiling, clamping back to 512 with a WARN. Raise `max_tokens` rather than the budget if a workload needs longer thinking.

**Per-request control.** `chat_template_kwargs.enable_thinking` reaches the chat template (it shifts prompt rendering) but cannot authorise reasoning generation while the server budget is 0 — upstream computes `enable_thinking = use_jinja && reasoning_budget != 0 && …`, an AND a request cannot satisfy. So a request can *suppress* thinking (`enableThinking: false`, used by the agent loop and the Quick effort rung) but not enable it against a zero-budget server. Prompt-level `/think` / `/no_think` remains available for per-step control within a session; the most recent directive wins in multi-turn Qwen3 conversations.

**Build compatibility.** Support for a finite budget varies by llama-server build (b8185 accepted only `-1` / `0`; b8571 accepts arbitrary finite values), and `/props` does not advertise it — `chat_template_caps` carries `supports_preserve_reasoning` but no `supports_enable_thinking`. The authoritative signal is therefore launch-argument acceptance: a build that rejects `--reasoning-budget` is relaunched without the flag, and the verdict is published on the runtime manifest as `ai.thinkingSupport` (`SUPPORTED` / `UNSUPPORTED` / `DISABLED` / `UNKNOWN`). Thinking fails closed; inference does not.

### Operational Findings (Tested)

These findings were validated through controlled experiments (12-16 scenario batteries, A/B testing) and are reflected in the current defaults:

1. **System prompt phrasing matters dramatically for Thinking models.** Imperative phrasing ("Call browse_folders first") causes the model to follow it literally on every query. Conditional phrasing ("Use browse_folders when you need to discover folder structure") allows contextual tool selection. This single change was the most impactful improvement in agent quality.

2. **`/no_think` works in system prompts but is ineffective mid-conversation.** The Qwen3 Jinja template checks the system prompt for `/think`/`/no_think` directives. A `/no_think` user message mid-conversation does not suppress reasoning — the model continues generating reasoning tokens (verified: 8605 chars vs 8738 chars initial). The reliable suppression mechanism is `--reasoning-budget 0` at the server level.

3. **Budget-aware prompts are counterproductive for 8B models.** Injecting remaining token budget ("Budget: X/8192 tokens used") into the system prompt caused a V4 regression (66.7% → 58.3%). The suffix "Be concise. Avoid redundant tool calls." made the agent too conservative — it stopped exploring after a single negative result instead of following up. 8B models follow concrete instructions ("use absolute paths") better than abstract meta-cognitive ones ("be concise").

4. **AGENT sampling preset (temp=0.2) is within consensus range but not A/B validated.** Industry consensus is temp 0.0-0.5 for tool-calling agents. The current temp=0.2 has not been tested against temp=0.0 or temp=0.1 for this model.

### Rollback

Set `JUSTSEARCH_USE_THINKING=false` to disable reasoning stream formatting. This omits `--reasoning-format deepseek` from the server command line. Sampling parameters are still sent when callers explicitly provide a preset. Think-tag stripping still runs as a safety net. Server restart is needed for the `--reasoning-format` flag change.

## Vision Capability Detection

JustSearch detects vision capabilities at two levels to ensure VDU features are only enabled when the runtime supports them:

### Config-Level Detection

`InferenceLifecycleManager.hasVisionCapability()` performs static capability detection based on configuration:

- **Check**: `mmprojPath != null` (vision projector model is configured)
- **When**: Configuration load time
- **Purpose**: Prevents startup when VDU is required but unconfigured

### Runtime Detection

`ServerPropsOps.extractServerProps()` performs dynamic capability detection from the running `llama-server`:

1. Fetches `GET /props` after server health check passes
2. Extracts `modalities.vision` boolean from response JSON
3. Caches result in `ServerProps` record
4. Exposes via `/api/inference/status` `hasVisionCapability` field

### VDU Processing Guard

`VduProcessor.process()` enforces runtime guard before processing vision documents:

- **Precondition**: `inferenceLifecycleManager.hasVisionCapability()` must return `true`
- **Failure mode**: Returns `VduResult.skipped()` with reason "Vision capability not available"
- **Telemetry**: `vdu.outcome_total{outcome=skipped}` counter

This dual-layer detection ensures:
1. Early failure detection (config level) before server startup
2. Runtime verification (actual server capabilities) for external server adoption
3. Graceful degradation when vision features unavailable

## RAG Summarization Architecture

To handle documents of any size, JustSearch implements a two-path summarization strategy. The entry point is `SummaryController`, which delegates to decomposed collaborators: `FullCoverageSummarizer` (paged content loading + orchestration), `MapReducePipeline` (hierarchical map/reduce), `ContentLoadingOps` (gRPC document fetching), and `SectionProcessingOps` (section splitting + token estimation):

### 1. Full Coverage (default for UI workflows)
*   **Goal:** summarize the *entire* extracted content (not just top-k chunks).
*   **Approach:** load content in pages (guard-railed), then either:
    * stream a direct summary for small inputs, or
    * run a hierarchical map/reduce when content would exceed the context window.

### 2. Quick Summary (RAG representative chunks)
*   **Goal:** fast “good enough” summary when full coverage is disabled (or as a fallback).
*   **Approach:** use Knowledge Server retrieval to pull representative chunks (top-k), then summarize those.

### Retrieval modes + degradation (current)

RAG retrieval (`SearchService.retrieveContext`) returns explicit metadata so clients can distinguish "semantic", "keyword-only", and fallback behavior:
- `retrieval_mode`: `BM25` | `HYBRID` | `CHUNK_HYBRID` | `FULLTEXT_FALLBACK`
- `retrieval_mode_reason`: allowlisted reason code explaining why a mode was chosen (or blocked); see `docs/reference/contracts/search-and-rag-reason-codes.md`
- `context_truncated`: true when the Worker hit the retrieval budget

Chunk-level hybrid (`CHUNK_HYBRID`) uses the `chunk_vector` field and is coverage-gated: it is only used when chunk vectors are sufficiently backfilled (>= 95%). Readiness is surfaced via `/api/status` (`chunkVectorCoveragePercent`, `chunkVectorsReady`, etc.). A kill switch exists via `rag.chunk_vectors.enabled` (default true).

Optional quality boost (disabled by default): a cross-encoder chunk reranker can rerank BM25 chunk hits under a tight time budget. GPU acceleration requires an ONNX Runtime CUDA-capable native runtime (see `docs/explanation/16-gpu-booster-pack.md`).

### Token budgets (current)

Every window-sized quantity in the Head is derived from **one** request-scoped record,
`ContextBudget` (`modules/core/src/main/java/io/justsearch/core/util/ContextBudget.java`). Each
consumer builds one per request from the same two inputs — the live context window and the
completion this turn reserves — and reads its derived accessors instead of carrying a literal of its
own. (It is one derivation, not one object: the RAG injector, the history injector, the selection
injector, the hierarchical runner and the agent loop each construct it, which is why the inputs and
the arithmetic live in one place.)

**Window precedence.** Observed llama-server `/props` `n_ctx` -> the configured launch window ->
`ContextBudget.FALLBACK_WINDOW_TOKENS` (4096, the smallest rung of the launch ladder). "Unknown" is
never treated as generous: it falls through to the next most authoritative value, and the last of
them is the smallest window any server this app starts can end up with. The launch ladder itself is
described in `docs/reference/configuration/runtime-config-ownership-matrix.md`
(`justsearch.context.size`).

**Input budget.** `inputBudget = TokenEstimation.computeSafeInputBudgetTokens(window, reserve)` —
`(window - reserve - 256 - 256) * 0.9` (a prompt-overhead allowance and a safety allowance, 256
tokens each), and `0` when the reservation leaves no room at all. At `n_ctx` 4096 with a 1024-token
reserve that is 2304 tokens. The
completion reserve is the turn's real `max_tokens` (the chat engine publishes it onto the request;
reasoning tokens are spent *inside* it, never alongside it), so the budget cannot drift from what
the server will actually enforce.

**Derived quantities.** Each is `min(fraction x inputBudget, ceiling)`. The fraction makes the value
scale with the window; the ceiling states the reason it should stop scaling.

| Accessor | Derivation | Consumer | Why the ceiling |
|---|---|---|---|
| `hierarchicalThreshold()` | `inputBudget` (no ceiling) | `HierarchicalShapeRunner` — single-pass vs map-reduce | None: it *is* the budget. A document that does not fit the prompt cannot be summarized in one call. |
| `sectionTarget()` | `inputBudget / 2`, max 4096 | `HierarchicalShapeRunner` — map-step size | A section is one blocking LLM call; past a few thousand tokens per-section latency, not the window, is what the user waits on. |
| `externalContextCap()` | `inputBudget / 4`, max 2048 | `ExternalContextInjector` — prior conversation turns | History is low value per token next to the material this turn retrieved. |
| `readDocumentPageTokens()` / `readDocumentPageChars()` | `inputBudget / 2`, max 4096 tokens | `ReadDocumentTool` — one page of a document | Agent-context hygiene: a 12k-token page at a 32k window fills the prompt with one document and defeats the compressor. **Today this fraction never binds** — see below. |
| `toolResultCap()` / `toolResultCapChars()` | `inputBudget / 4`, max 2048 tokens | `AgentContextCompressor` Layer-2 cut, `SearchTool` result set | One tool result must not own the prompt; the agent loop's value is holding several at once. |

The read page is additionally bounded by the Layer-2 cut minus a 600-char header allowance, because
a page that arrives clipped is the excerpt-shaped result the read tool exists to replace. That second
bound is the one that actually governs at every rung: the page fraction (`inputBudget / 2`, max 4096)
is never smaller than the tool-result fraction (`inputBudget / 4`, max 2048) it must fit inside, so
`readPageChars` always resolves to `toolResultCapChars() - 600`. The page accessor is kept as the
page's OWN stated ceiling, so that raising the tool-result ceiling later cannot silently leave pages
unbounded; `AgentContextBudgetsTest` pins both the dominance and the fit.

**Character budgets.** Consumers that cut in characters (the Layer-2 tool-result cut, the read page,
the selection injector) convert through `TokenEstimation.charsForTokens` — the documented inverse of
the estimator's default heuristic (4 chars per token), and the only conversion any of them use.

**Drops are reported, at two different altitudes.** A trimmed RAG context sets
`rag.meta.context_truncated`, which reaches the user. A dropped prior conversation turn
(`ExternalContextInjector`) and a cut selection (`SelectionContextInjector`) are reported at INFO in
the backend log only — they have no wire flag today, so an operator can see them and a reader of the
answer cannot. Putting those two on the wire is tracked as open work in tempdoc 883, not claimed
here.

**Agent knobs.** `justsearch.agent.max_tool_result_chars` and
`justsearch.agent.max_completion_tokens` both default to `0 = derive from the window`, but a
positive value does NOT mean the same thing for the two:

- `max_tool_result_chars` is an operator ceiling honoured **verbatim**.
- `max_completion_tokens` is a ceiling on a window **fraction**: the reserve is
  `min(cap, window / 4)`, where `cap` is the configured value when set and `1024` otherwise. An
  answer does not get longer because the window did, but at a window too small to afford the cap a
  flat reserve starves the input instead — so a small window reduces it. Because that reduces a
  number an operator typed, `AgentContextBudgets` reports the reduction at INFO, deduplicated per
  `(cap, window)` pair.

**Retrieval shape.** The Head passes `inputBudget` to the Worker
(`RetrieveContextRequest.max_context_tokens`) so the Worker can budget context during retrieval
(avoids "Worker fetches 200K chars, Head truncates to 3K tokens" waste), and derives how many
passages to ask for from it: `inputBudget` divided by the fixed 500-token chunk size
(`ChunkSplitter.DEFAULT_CHUNK_TOKENS`), bounded above by `justsearch.rag.top_k`. An
explicit per-request `topK` still wins verbatim. The Head keeps a safety-net truncation step and
resolves each citation to what that cut did with its passage, so a citation never claims a passage
the prompt does not contain.

## Q&A (multi-file “Ask”)

Q&A uses the Worker's retrieval path (`DocumentService.retrieveContextWithMeta(...)` → gRPC `SearchService.retrieveContext`) to get relevant context, then streams an answer via `OnlineAiService`.

Important correctness/UX detail (current):

- RAG retrieval can legitimately return an **empty** context (no chunks indexed + BM25 finds no matches).
- In that case, `SummaryController.handleAskStream` falls back to `documents().fetchBatch(...)` (full docs) instead of hard-failing with `NO_CONTENT`.

### Context size guardrails (strict char budgeting)
Token-aware budgeting is preferred when available (`max_context_tokens > 0`). Character budgeting remains a fallback safety net.
JustSearch enforces a strict **character cap** on retrieved context strings (default **200,000 chars**) to prevent oversized prompts and “soft cap” drift.

Implementation:

- **Token-aware budgeter:** `TokenAwareBudgeter` (`modules/indexing/src/main/java/io/justsearch/indexing/rag/TokenAwareBudgeter.java`) is used when the Head provides `max_context_tokens > 0`.
- **Budgeter:** `ContextBudgeter` (`modules/indexing/src/main/java/io/justsearch/indexing/rag/ContextBudgeter.java`) counts **all** overhead (section headers + separators), not just raw document content.
- **Worker retrieval:** `WorkerSearchService` uses `ContextBudgeter` when building the context returned by `SearchService.retrieveContext` (`modules/indexer-worker/src/main/java/io/justsearch/indexerworker/services/WorkerSearchService.java`).
- **Fallback retrieval:** when RAG returns empty/insufficient context, the fallback full-doc path is also budgeted via `ContextBudgeter` (`modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteDocumentService.java`).

Regression coverage:

- `modules/indexing/src/test/java/io/justsearch/indexing/rag/ContextBudgeterTest.java`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceRetrieveContextTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteDocumentServiceContextBudgetTest.java`

### Stable Intermediate Format: `SECTION_SUMMARY_V1`
To reduce long-run hallucinations in hierarchical runs, all map/reduce steps use a strict intermediate schema:

```text
<SECTION_SUMMARY_V1>
CLAIMS:
- <short claim> (evidence: "<very short quote>")
ENTITIES:
- <entity>
DATES_NUMBERS:
- <date/number> — <context> (evidence: "<very short quote>")
UNKNOWNS:
- <important missing/unclear info, or "none">
</SECTION_SUMMARY_V1>
```

* **Map:** summarize each section into exactly one `SECTION_SUMMARY_V1` block.
* **Reduce:** merge multiple blocks into exactly one smaller `SECTION_SUMMARY_V1` block (dedupe, preserve evidence).
* **Synthesis:** produce the final user-facing summary from the blocks (do not mention the tags).

All hierarchical steps use `OnlineAiService.streamChat(...)` so they share the same streaming primitive and error handling.

## Citation Pipeline

JustSearch uses a **two-pronged citation strategy** (see ADR-0006) to attribute AI-generated answers to source documents:

### Prong 1: LLM-generated citations (primary)

RAG Q&A prompts inject the retrieved source chunks as a plain `Documents:` / `Question:` user message (`RAGContext`), and instruct the LLM to place `[N]` citation markers inline. (The earlier numbered `<passage id="N" source="file">` XML wrapper is retired.) The `rag.citations` SSE event delivers the citation metadata (`ContextCitation[]`):

- `parentDocId`, `chunkIndex`, `chunkTotal` — chunk identity
- `startChar`, `endChar` — character offsets for click-to-jump
- `startLine`, `endLine`, `headingText` — section navigation
- `score` — BM25 retrieval score
- `excerpt` — source chunk text

This path works with any model capable of following citation instructions. No embedding service or ONNX models required.

### Prong 2: Post-hoc cross-encoder matching (supplementary)

After the LLM finishes streaming, `WorkerSearchService.matchCitations()` runs a CPU-only ONNX cross-encoder (`CitationScorer`) to score each answer sentence against source chunks:

1. Answer text is split into sentences via `BreakIterator`
2. Each sentence is scored against all source chunks via `CitationScorer.scoreAll()` (ms-marco-MiniLM-L-6-v2, ~22 MB INT8 ONNX)
3. Scores are sigmoid-normalized to [0,1], filtered by threshold (default 0.5)
4. Results are sent as a `citation_matches` SSE event (after `done`)

The cross-encoder runs on CPU, eliminating the GPU contention that blocked the original embedding-based approach (embedding model and LLM compete for VRAM on single-GPU systems).

Fallback chain in `matchCitations()`:
1. Cross-encoder (CPU, no GPU needed) → preferred
2. Embedding cosine similarity (requires `EmbeddingService`) → blocked during Q&A on single-GPU
3. `EMBEDDING_UNAVAILABLE` → no post-hoc matching

### Frontend rendering

The RAG-citation event orchestration lives in `modules/ui-web/src/shell-v0/views/UnifiedChatView.ts` (`onRagCitationMatches` / `onRagCitationDelta`), consuming the streaming callbacks in `modules/ui-web/src/api/streams.ts` and feeding a resolved citation model (`shell-v0/components/chat/citationResolve.ts` / `citationTypes.ts`) to the presentational components under `shell-v0/components/chat/`. It handles both prongs:

- Cross-encoder scores **refine** the RAG citations — excerpts, offsets, and headings from the `meta` event are preserved; only the citation's `score` is updated.
- LLM-generated `[N]` markers are reconciled against the resolved citations before render (prevents duplication).
- `CitationHoverCard` displays document name, excerpt preview, score badge (hidden for BM25 scores >1.0), and section metadata.
- `MarkdownBlock` parses `[N]` syntax into clickable citation buttons.

### Citation Parsing and Attribution Contract (RAG Eval)

For automated RAG evaluation, citation handling uses a permissive parser with strict attribution rules:

- Accepted marker formats: `[1]`, `[Document1]`, `[Document 1]`, and truncated `[1` (stream cutoff tolerance).
- Attribution rule is strict: a parsed marker only counts as correct when the marker number resolves to the expected source document for that claim/query.
- This keeps format tolerance high while preventing false credit from wrong-source citations.

### Configuration

The citation scorer is opt-in via environment variables:

| Env Variable | Default | Description |
|:---|:---|:---|
| `JUSTSEARCH_CITATION_SCORER_ENABLED` | `false` | Enable cross-encoder citation scoring |
| `JUSTSEARCH_CITATION_SCORER_MODEL_PATH` | — | Path to ONNX model directory (`model.onnx` + `tokenizer.json`) |
| `JUSTSEARCH_CITATION_SCORER_THRESHOLD` | `0.5` | Minimum similarity score for a match |
| `JUSTSEARCH_CITATION_SCORER_MAX_SEQ_LEN` | `512` | Maximum token sequence length |
| `JUSTSEARCH_CITATION_SCORER_DEADLINE_MS` | `2000` | Time budget for scoring |

### ONNX Model Distribution

| Feature | Model | Size | Notes |
|---------|-------|------|-------|
| Search reranker | `gte-multilingual-reranker-base` | ~340 MB (FP16 GPU) | 306M params, 70+ langs; default `maxSeqLen=512`; 175ms/20 docs on GPU (343, 359, 360) |
| Citation scorer | `ms-marco-MiniLM-L-6-v2` | ~22 MB (INT8) | CPU-only by design; upgraded from L-2 (343) |

**Auto-discovery resolution order** (implemented in `OnnxModelDiscovery` via `ResolvedPathResolver`):
1. Explicit env var override (no validation)
2. `<modelsDir>/onnx/<modelName>/` (validated, auto-enable)
3. `<dataDir>/models/onnx/<modelName>/` (validated, auto-enable)
4. `<repoRoot>/models/onnx/<modelName>/` (validated, auto-enable)
5. Dev fallback (requires `ENABLED=true` env var)

Models are bundled in the installer as flat assets (~40 MB total) with a post-download arrangement step.

### Model Identity & Swap Detection

Silent model swaps (e.g., user replaces a GGUF file between restarts) can cause subtle quality regressions without any signal. Two mechanisms detect this:

1. **ONNX model fingerprinting** — `CitationMatchOps` computes SHA-256 of `model.onnx` on scorer initialization and stores the fingerprint in a volatile field. On re-initialization, a fingerprint mismatch triggers a warning log. This covers the citation scorer and reranker ONNX models.

2. **Chat model swap detection** — `InferenceLifecycleManager` persists the active model ID (learned from `llama-server /props`) to `<dataDir>/inference-model-id.txt`. On startup, if the persisted ID differs from the newly reported model ID, a warning is logged. This covers the generative LLM served by `llama-server`.

Both mechanisms are warn-only (no blocking behavior) since legitimate model upgrades are a normal operation.

## Vision Support (VDU)
Vision Document Understanding (VDU) enriches visual documents beyond baseline text extraction.
Baseline scanned/image-text searchability is Worker-owned: structured Tika runs first, then bounded
Tika/Tesseract OCR can produce `extraction_method=OCR_TIKA` when the text layer is missing or weak.
Successful OCR also records compact `visual_extraction_evidence`, including OCR language, optional
Tesseract TSV confidence summary, fallback route, truncation, and OCR skip/guard reason when relevant.
That evidence can queue VDU enrichment when baseline text exists but OCR/layout signals suggest richer
visual understanding would help.
*   **Flow:**
    1.  Worker extracts with structured Tika. If extracted text is empty/garbage and the file is OCR-eligible, Worker extraction attempts bounded Tika/Tesseract OCR before VDU is considered.
    2.  The system goes idle (the enrichment backfill runs on idle cycles) and/or the user triggers the "Process pending enrichment" operation (`core.trigger-offline-processing`).
    3.  Head/app-services selects pending docs and runs `VduBatchProcessor` → `VduProcessor`.
    4.  `VduProcessor` calls a Vision-capable model via `llama-server` (e.g., the configured chat model + `--mmproj`) with: “Transcribe the text in this image.”
    5.  Worker persists successful non-empty VDU by overwriting `content`, re-deriving `content_preview` and `language`, regenerating chunks, and recording `extraction_method=VDU`.
    6.  Failed or completed-empty VDU preserves the best baseline text. The UI surfaces per-doc `vduStatus` + `textProvenance` in the Inspector Panel so users can see whether the current text came from Tika, OCR, or VDU.

Worker status splits visual demand into `visualTextNeededCount` for missing baseline readable text and
`visualEnrichmentNeededCount` for documents where VDU is useful after baseline text exists. OCR blockers
therefore degrade retrieval only when baseline text is still missing; VDU enrichment-only blockers degrade
AI features instead.

Verification lanes:

- Hermetic eligibility fixtures (no llama-server): `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/VduEligibilityPdfFixturesTest.java`
- Tier-2 OCR (requires llama-server): `modules/system-tests/src/systemTest/java/io/justsearch/systemtests/vdu/VduBatchProcessorE2ETest.java` (`processesScannedPdfWithRealLlm`, fixture `modules/system-tests/src/systemTest/resources/fixtures/pdf/scanned-alpha.pdf`)

### VDU Resilience & Observability

*   **Timeout Protection:** `VduProcessor` enforces strict timeouts on LLM operations to prevent single-threaded VDU queue blocking:
    *   Pass 1 (vision completion): 120 seconds
    *   Pass 2 (chat completion): 60 seconds
    *   Timeout telemetry: `vdu.timeout_total` counter increments on timeout
*   **Circuit Breaker:** `VduBatchProcessor` uses a circuit breaker (5 failures, 1 minute recovery) to fast-fail remaining documents when the LLM is repeatedly failing. This prevents hammering a dead inference engine during batch processing.
*   **Latency Metrics:** Timer metrics (`vdu.pass1.duration_ms`, `vdu.pass2.duration_ms`, `vdu.total.duration_ms`) track pipeline performance. See `docs/explanation/08-observability.md` for the full metrics list.
*   **Debug Trace Logging:** Enable TRACE logging for `io.justsearch.app.services.vdu.VduProcessor` to see truncated text samples (first 500 chars of Pass 1, first 300 chars of Pass 2). JVM property: `-Dlogging.level.io.justsearch.app.services.vdu.VduProcessor=TRACE`.
