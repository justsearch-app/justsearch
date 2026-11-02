---
title: "ADR-0005: Manual FFM Bindings for llama.cpp"
type: decision
status: superseded
description: "Hand-written Panama FFM bindings instead of jextract codegen or JNI."
date: 2026-02-03
superseded_by: "GGUF→ONNX embedding migration (tempdocs 268, 286, 327)"
probes: none - superseded by the GGUF to ONNX embedding migration (tempdocs 268, 286, 327); the hand-written FFM bindings it decided are gone, so there is no live premise to probe.
last_reviewed: 2026-09-02
---

# ADR-0005: Manual FFM Bindings for llama.cpp

## Status

Superseded — the entire FFM/llama.cpp native binding layer (~10,000 LOC)
was deleted in March 2026. Embeddings migrated to ONNX Runtime. The
generative LLM uses `llama-server.exe` over HTTP (managed by
`LlamaServerOps`), not in-process FFM calls. This ADR documents a
deleted architecture.

## Context

The `ai-bridge` module needs to call llama.cpp's C API from Java for model loading, tokenization, embedding, and inference. Three binding approaches are available in modern Java (22+):

1. **JNI (Java Native Interface)** — the traditional approach: write C wrapper functions, compile to a shared library, call via `native` methods.
2. **jextract** — an official tool that parses C headers and generates Java FFM binding code automatically.
3. **Manual FFM** — hand-write `MemoryLayout` definitions and `MethodHandle` lookups using the Panama Foreign Function & Memory API directly.

The llama.cpp API surface that JustSearch uses is moderate (~15-20 functions) but involves complex struct layouts (batch processing, KV cache management, sampling parameters). The API evolves with each llama.cpp release.

## Decision

Use **manual FFM bindings** with hand-written struct layouts and function descriptors in `NativeLlamaBinding`.

Key implementation details:

- `MemoryLayout.structLayout()` definitions match llama.cpp header structs exactly (e.g., `llama_batch`, `llama_model_params`).
- `MethodHandle` lookups via `SymbolLookup` for each native function.
- A small C shim (`jllm_bridge`) handles platform-independent aligned allocation, safe backend loading, and ABI version checking.
- `Arena`-based memory management for automatic cleanup of native allocations.

## Consequences

**Positive:**

- No codegen step in the build pipeline. The bindings are plain Java source files, reviewed and understood directly.
- Finer control over memory layouts — struct padding, alignment, and field access are explicit in code rather than hidden in generated output.
- Easier debugging — stack traces show our code, not generated wrapper methods.
- Smaller binding surface — we only bind the ~15-20 functions we actually use, not the entire llama.cpp API.

**Negative:**

- Manual maintenance: when llama.cpp changes a struct layout or function signature, we must update the Java layouts by hand. This has caused bugs when fields were added to structs in upstream releases.
- No compile-time verification that layouts match the native library — mismatches manifest as silent memory corruption or segfaults at runtime.
- Requires deep understanding of C memory layouts (padding, alignment) from Java developers maintaining the module.
- The `ai-bridge` module is the most complex in the codebase (~17,400 LOC) partly because of this manual binding approach.

## Alternatives Considered

### JNI (Java Native Interface)

Write C wrapper functions that call llama.cpp, compile to `jllm.dll`/`libjllm.so`, use `System.loadLibrary()`.

**Rejected because:** JNI requires maintaining both Java native method declarations and C implementation files. Every API change requires recompilation of the native library for each target platform. The `ai-bridge` module already has complex lifecycle management (actor model, VRAM coordination); adding a C compilation step for each platform increases build complexity significantly. FFM eliminates the native compilation requirement for the binding layer.

### jextract (automatic FFM generation)

Run jextract on llama.cpp headers to auto-generate Java FFM bindings.

**Rejected because:** jextract generates bindings for the *entire* header, producing thousands of methods and struct definitions for an API where we use ~15-20 functions. The generated code is verbose and hard to read. When llama.cpp updates headers, regenerating produces large diffs that are difficult to review for correctness. Manual bindings for a focused API surface are more maintainable than filtering/customizing jextract output.

### ONNX Runtime Java API

Use ONNX Runtime's official Java bindings instead of llama.cpp for inference.

**Rejected because:** ONNX Runtime's GGUF support is limited. The llama.cpp ecosystem provides the best GGUF model compatibility, quantization support, and community momentum for local LLM inference. ONNX Runtime is used separately for the reranker (where it's the better fit), but the generative and embedding workloads require llama.cpp's specialized optimizations (KV cache management, speculative decoding, context shifting).

See also: [ADR-0017](0017-ai-bridge-module-decomposition.md) for how the former ai-bridge module was decomposed.
