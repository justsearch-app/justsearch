---
title: "ADR-0019: CPU vs GPU Model Selection Strategy"
type: decision
status: stable
description: "Ship separate FP32 ONNX models for CPU use; use model_manifest.json to select CPU vs GPU variants at runtime."
date: 2026-04-06
probes: none - the premise is runtime behaviour (manifest-driven CPU/GPU variant selection), not a static fact; a probe needs the model-manifest schema check that tempdoc 884 PR 2 scopes.
last_reviewed: 2026-10-03
---

# ADR-0019: CPU vs GPU Model Selection Strategy

## Status
Accepted

## Context

JustSearch ships ONNX models for its search runtime (embedding, SPLADE, reranker, NER, citation). The packaged embedding model (`gte-multilingual-base`) only ships an FP16 variant, which is catastrophic on CPU: the ORT CPU execution provider has no native FP16 support, so the graph optimizer inserts thousands of Cast (FP16->FP32) nodes. At `EXTENDED_OPT`, this causes 30-60+ minute optimization hangs (G29 in tempdoc 375). Even after optimization, every inference call pays cast overhead at runtime — embedding that takes 20ms on GPU takes 2-5s on CPU.

Meanwhile, CPU-only machines download 8.5 GB of model assets but get unusable inference. The `model_manifest.json` convention already supports separate `"cpu"` and `"gpu"` keys — SPLADE correctly uses FP32 for CPU and FP16 for GPU — but embedding points both keys to the FP16 variant.

The forces at play:
- CPU-only users exist (laptops without NVIDIA GPUs, VMs, older hardware).
- FP32 embedding works correctly on CPU EP with fast session creation (~5s).
- FP16 on GPU is preferred for VRAM efficiency (628 MB vs 1.26 GB).
- The existing `ModelManifest` code already reads `cpu`/`gpu` fields and selects based on `gpuEnabled`.

## Decision

Ship separate FP32 ONNX models for CPU use alongside FP16 for GPU. The `model_manifest.json` convention maps `"cpu"` to FP32 and `"gpu"` to FP16 variants. Specifically:

1. **FP32 embedding model** uploaded to the model registry as a separate asset (`model.onnx`, 1.26 GB) alongside the existing FP16 (`model_fp16.onnx`, 628 MB).
2. **Manifest updated**: `"cpu": "model.onnx", "gpu": "model_fp16.onnx"` — the existing `ModelManifest` selection logic handles this without code changes.
3. **Short-term mitigation**: `BASIC_OPT` for FP16 models on CPU (reduces optimization from 30-60 min to ~5-10 min if FP32 is unavailable).
4. **Long-term direction**: GPU-aware download profiles at Install AI time — detect GPU presence, download only relevant model variants (~3 GB for CPU-only vs 8.5 GB full).

## Consequences

**Positive:**
- CPU users get functional inference without a GPU. FP32 embedding works correctly on CPU EP with no cast overhead.
- Fast session creation on CPU (~5s for FP32 vs 30-60 min for FP16 at `EXTENDED_OPT`).
- No code changes needed in `ModelManifest` or `NativeSessionHandle` (formerly `OrtSessionManager`; tempdoc 397 §14.23) — the manifest convention handles variant selection.
- Follows the same pattern SPLADE already uses successfully.

**Negative:**
- Doubles on-disk footprint for the embedding model (~1.26 GB FP32 + 628 MB FP16) when both variants are downloaded.
- Requires maintaining two model variants per architecture where CPU/GPU performance diverges.
- Until GPU-aware download profiles are implemented, CPU-only users still download both variants.

## Alternatives Considered

### Pre-optimized offline graph
Run `EXTENDED_OPT` once on a reference machine, upload the `.optimized` graph as a registry asset. `OnnxSessionCache` loads it with `NO_OPT`. Rejected because the `.optimized` graph is tied to the ORT version — it breaks on ORT upgrades, creating fragile asset management (ORT version in filename?). Additionally, it still pays FP16->FP32 cast overhead at runtime, only eliminating the optimization time, not the inference penalty.

### Warn and proceed
Show estimated performance before download: "CPU-only detected. Enrichment will be slow." Rejected because users still download 8.5 GB for a degraded experience. Silent degradation masked by a warning is worse than actually fixing the model variant selection.

### Single FP32-only (no FP16 variant)
Ship only FP32 for all platforms. Rejected because FP32 wastes GPU VRAM on FP16-capable hardware (1.26 GB vs 628 MB), and FP16 inference is faster on GPU due to reduced memory bandwidth. The VRAM savings matter when embedding, SPLADE, reranker, and NER share GPU memory with `llama-server`; shipped builds permit this co-residency and have limited headroom on some devices (see [ADR-0004](0004-single-tenant-gpu-policy.md)).


## 2026-10-03 amendment: optimized graph ownership

The CPU/GPU model-selection decision above remains accepted. The pre-optimized alternative records historical reasoning, not the current cache layout or load policy. Following the path-local cache multiplier incident in tempdoc 958, `OnnxSessionCache` now delegates to one content-keyed `OrtOptimizedModelStore` at `PlatformPaths.getPlatformDefault()/cache/ort-optimized/` (Windows `%LOCALAPPDATA%\JustSearch\cache\ort-optimized`). The root is overridden by `justsearch.ort.optimized_cache_dir` / `JUSTSEARCH_ORT_OPTIMIZED_CACHE_DIR`; the cap defaults to 16 GiB. Committed CPU and CUDA graphs load with `NO_OPT`; their keys include model bytes, ORT version, provider and optimization level. The store replaces graph siblings beside models and attempts exact legacy-sibling cleanup. This removes repeated optimization for identical copies but leaves the FP16-on-CPU runtime cast penalty and the FP32-for-CPU decision intact. See [D-012](../reference/inference-runtime-register.md#d-012-ort-optimized-graphs-one-content-keyed-per-machine-store--shipped) and [AI architecture](../explanation/05-ai-architecture.md) for current cache behavior.
