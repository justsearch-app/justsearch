---
title: "ADR-0004: Single-Tenant GPU Policy"
type: decision
status: superseded
description: "Historical GPU mutual-exclusion proposal; shipped builds allow ONNX encoders and llama-server to be co-resident."
date: 2026-02-03
superseded_by: "GGUF→ONNX embedding migration (tempdocs 268, 286, 327)"
probes:
  - adr-0004-gpu-mutual-exclusion
last_reviewed: 2026-10-02
---

# ADR-0004: Single-Tenant GPU Policy

## Amendment — 2026-10-02: shipped behavior is GPU co-residence

The mutual-exclusion policy below was **never applied in shipped builds** and is
not applied by the merged one-JVM Engine. In the shipped split architecture,
`HeadlessApp` passed a null index bootstrap to `InferenceWiring`, whose wiring
returned early; the Head therefore never registered a GPU-status listener.
The merged Engine likewise does not publish an Online GPU claim that causes
encoders to yield. `llama-server` and the ONNX encoders remain co-resident on
the GPU while Chat is Online. The historical MMF design and the later in-JVM
gauge description below record intended mechanisms, not enforced shipped
behavior.

Measurements on a 12 GB card found `llama-server` at 7.5 GB plus co-resident
encoders at 3.3 GB p95 / 5.5 GB peak: 11.9 GB of 12.28 GB, leaving about
0.4 GB headroom. That narrow margin carries OOM risk as workload and model
memory vary. Applying the exclusion policy made every encoder run on CPU and
raised hybrid-search p95 from 0.26 s to 9.8 s. A budget-aware policy—keeping
query encoders on CUDA while allowing bulk work to yield on small devices—is a
follow-up lane and is outside this merge. The diagnosis file cited in the
2026-10-02 owner decision is absent from this checkout; the measurements and
disposition are recorded here from that decision.

## Status

Superseded — the GGUF embedding system (in-process llama.cpp via FFM)
was deleted in March 2026 (~10,000 LOC). Embeddings now use ONNX Runtime
via `NativeSessionHandle` (formerly `OrtSessionManager`; renamed in tempdoc 397 §14.23).
The mutual-exclusion policy was not active in shipped builds and is not the
merged Engine's GPU policy. The design below is historical; its GGUF framing
and operational claims do not describe current behavior.

**Historical correction, 2026-09-08 (ADR-0049).** The Context and Decision below describe
this policy across a Head/Worker process split that no longer exists. Lane F
stage A merged the application half and the index half into one JVM — the
Engine — so the ORT encoders and the process that serves the API are now the
same process, and the GPU activity state is held by the in-process
`GpuSchedulingGauge` (`modules/core`), not a memory-mapped flag: the historical
signal bus was deleted at item A10. The `llama-server` process boundary survives
ADR-0049, but the proposed mutual-exclusion policy did not become a shipped
invariant. Read every
**Historical Head process / Worker process terminology** below means "the Engine".

## Historical Context (pre-Engine architecture)

JustSearch runs two GPU-intensive workloads:

1. **Embedding model** (`nomic-embed-text` GGUF) — runs in the Worker process via in-process llama.cpp. Used for vectorizing documents during indexing and chunk embedding backfill.
2. **Generative LLM** (e.g., `Qwen3VL-8B-Thinking` GGUF) — runs as `llama-server.exe` managed by the Head process. Used for interactive chat, Q&A, summarization, and vision document understanding.

The target hardware is consumer GPUs with 8GB VRAM. Loading both models simultaneously would exceed VRAM capacity, causing either:

- OOM errors that crash one or both processes.
- Silent fallback to system RAM, degrading inference from seconds to minutes per query.

NVIDIA's CUDA runtime does not provide reliable VRAM reservation or preemption between processes. Two processes competing for VRAM is a race condition with no portable resolution.

## Historical Decision (MMF implementation, superseded)

Enforce **mutual exclusion** for GPU access across processes using an advisory MMF flag:

- The Head process sets `main_gpu_active = 1` (MMF offset 24) before starting `llama-server.exe`.
- The Worker reads this flag in `IndexingLoop` and unloads the embedding backend to release VRAM.
- When inference ends, the Head clears the flag and the Worker reloads the embedding backend.

Three operational modes:

| Mode | Active Model | GPU Owner |
|------|-------------|-----------|
| **Indexing Mode** | Embedding model | Worker |
| **Online Mode** | Generative LLM | Head (llama-server) |
| **Offline Mode** | None | Neither |

The embedding model's GPU use follows the master GPU switch by default (`justsearch.embed.gpu.enabled` when set, else the `justsearch.gpu.enabled` auto-detect master switch, then policy-gated — see `ResolvedConfigBuilder.resolveEmbedGpuEnabled`). The generative LLM gets GPU priority when Online Mode is active.

## Consequences

**Positive:**

- No VRAM contention: only one model uses the GPU at any time.
- Predictable performance: no silent RAM fallback degradation.
- Works on 8GB consumer GPUs — the minimum viable target.
- Simple protocol: a single byte flag in shared memory, checked per indexing iteration.
- Graceful degradation: if no GPU is available, both models run on CPU (or inference stays Offline).

**Negative:**

- Embedding backfill pauses during interactive AI sessions. Documents queued during Online Mode accumulate and process after the session ends.
- The flag is advisory — no OS-level enforcement. The Worker honors it cooperatively, which is sufficient for a single-user desktop app.
- No concurrent GPU use even when VRAM would allow it (e.g., 24GB GPUs). Simplicity over optimization for the initial release.
- Mode transitions have latency: unloading the embedding model, starting llama-server, and health-checking adds seconds to the first AI interaction.

## Alternatives Considered

### Shared GPU (concurrent loading)

Load both models simultaneously with VRAM budgeting per model.

**Rejected because:** CUDA does not provide reliable cross-process VRAM reservation. Budgeting would require precise VRAM accounting (model size + KV cache + working memory) that varies by quantization level, context length, and GPU architecture. Miscalculation causes OOM with no recovery path. The complexity is not justified for 8GB target hardware.

### No GPU support (CPU-only)

Run all inference on CPU, eliminating VRAM management entirely.

**Rejected because:** CPU inference for generative models is too slow for interactive use. A 7B parameter model takes 10-30 seconds per response on CPU vs 1-3 seconds on GPU. This would make the chat and summarization features unusable in practice.

### Dynamic VRAM partitioning

Split available VRAM between models based on runtime detection.

**Rejected because:** VRAM fragmentation and CUDA context overhead make precise partitioning unreliable. A 4GB + 4GB split on an 8GB GPU leaves insufficient headroom for KV cache growth during long conversations. The engineering cost of reliable dynamic partitioning exceeds the benefit for a v1 desktop product.

See also: [AI Architecture](../explanation/05-ai-architecture.md) for the full inference architecture and mode transition protocol.

## Historical update — 2026-06-17: query-embed was designed to use CPU while Online

The historical Decision above states that on Online Mode the Worker "unloads the embedding
backend to release VRAM." As of tempdoc 598 R4 this is **narrowed**: on the
At the GPU-active rising edge the Engine now **releases the embedder's GPU
session** (intended to free VRAM for the chat LLM) but
**keeps the `EmbeddingService` alive** so a single **query embedding** continues
on the CPU fallback session. Bulk embedding **backfill** stays paused exactly as
before (`LoopPacingPolicy.shouldRunBackfill` still gates on `mainGpuActive`).

Rationale: the single embedder served two workloads the blanket flag conflated —
sustained **bulk backfill** (correctly deferred under Online) and a single,
bounded **query embed** (latency-critical, needed for dense search and RAG
*during* chat). Evicting the embedder for the latter made semantic search and
grounded Q&A silently degrade to keyword the moment chat loaded. The refinement
was: **mutual exclusion governs bulk GPU work; a bounded query embed is exempt
and runs on CPU while Online.** This was a design claim, not shipped runtime
behavior; ONNX encoders and `llama-server` are co-resident while Online.
