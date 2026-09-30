---
title: AI Bridge Deep Dive
type: explanation
status: deprecated
description: "Historical AI bridge architecture; current inference ownership is split across app-inference, ai-backend, gpu-bridge, prompt-support, and Worker-side ORT encoders."
---

# AI Bridge Deep Dive

This page is retained as historical context. It no longer describes the live AI runtime architecture.

The former AI bridge design has been decomposed. Do not use this page as current implementation guidance for llama-server lifecycle, embeddings, GPU detection, prompt support, or backend ownership.

## Current Ownership Map

| Current area | Owner | Notes |
|--------------|-------|-------|
| Online llama-server lifecycle | `modules/app-inference` | Starts, adopts, health-checks, reloads, and stops the online OpenAI-compatible llama-server process. |
| Backend abstractions and local translator support | `modules/ai-backend` | Owns Java backend abstractions used by local translation/summarization paths. It does not own the live llama-server lifecycle. |
| GPU and VRAM detection | `modules/gpu-bridge` | Owns hardware capability detection and GPU-related helper surfaces. |
| Prompt support | `modules/prompt-support` | Owns prompt templates and prompt/reasoning support utilities. |
| Worker embeddings and ORT encoders | Worker modules plus `modules/ort-common` | Embeddings, SPLADE, NER, BGE-M3, cross-encoder reranking, and citation scoring use Worker-side ONNX Runtime session composition. |

## Current References

- Architecture overview: [05-ai-architecture.md](05-ai-architecture.md)
- Module ownership: [19-module-architecture.md](19-module-architecture.md)
- Inference runtime register: [../reference/inference-runtime-register.md](../reference/inference-runtime-register.md)
- Engine inference composition: [24-engine-inference-composition.md](24-engine-inference-composition.md)
- Historical decision: [../decisions/0017-ai-bridge-module-decomposition.md](../decisions/0017-ai-bridge-module-decomposition.md)

## Historical Context

Older documentation referred to an in-process GGUF/FFM `ai-bridge` module with llama.cpp bindings and actors such as `GenerationActor`, `EmbeddingActor`, and `SharedModel`. That architecture is obsolete for current agent-facing guidance.

When updating prompts or generated skills, route implementation questions to the current ownership map above instead of reviving the old `ai-bridge` vocabulary.

## Historical Breadcrumbs

The removed deep-dive material described these obsolete implementation concepts:

| Historical concept | Current interpretation |
|--------------------|------------------------|
| Manual FFM llama.cpp bindings and `NativeLlamaBinding` | No longer a current live-runtime guide. Online generation is managed through the external llama-server lifecycle in `app-inference`. |
| `GenerationActor`, `EmbeddingActor`, `SharedModel`, and `LlamaService` | Historical in-process concurrency model. Do not use these names when extending current inference behavior. |
| In-process GGUF embeddings | Replaced for current guidance by Worker-side ONNX Runtime encoder composition. |
| AI bridge-owned GPU/VRAM management | Split out; use `gpu-bridge` for hardware capability surfaces and `ort-common`/Worker composition for ORT session policy. |
| AI bridge-owned prompt templates | Split out; use `prompt-support`. |

This breadcrumb section exists so older tempdocs, ADRs, and commit messages remain intelligible without making the deprecated architecture look current.
