---
title: "ADR-0017: ai-bridge Module Decomposition"
type: decision
status: stable
description: "Split ai-bridge monolith into ai-backend, gpu-bridge, and prompt-support; delete hollow app-ai and unused ai-worker."
date: 2026-04-06
probes:
  - adr-0017-decomposed-modules-present
  - adr-0017-retired-modules-absent
last_reviewed: 2026-09-02
---

# ADR-0017: ai-bridge Module Decomposition

## Status
Accepted

## Context

The `ai-bridge` module had grown into a monolith mixing three distinct concerns: GPU/VRAM detection and CUDA session management, prompt engineering and reasoning pipelines, and LLM server lifecycle management (llama-server bridge). At 171 source files (~77% from the founding codebase), it was the largest AI-related module and the most internally coupled.

Two additional modules added complexity without value:

- **`app-ai` (historical gRPC translator)** - A hollow translator module containing `GrpcAiTranslatorService`, `LocalAiTranslatorService`, and `NoopAiTranslatorService` implementing the `IndexingAiService` interface. The interface had a single real implementation, and the translation layer added indirection without benefit. The entire module's purpose was collapsed when inference lifecycle management moved to `app-inference`.
- **`ai-worker`** — A complete but unused 4th JVM process (14 source files, all from 2025-11-01) that predated the Brain (`app-inference`) architecture. It was never started in production and its gRPC AI services were superseded by the Brain's llama-server management.

Additionally, 721 MB of tracked native binaries (CUDA/cuBLAS DLLs) were committed directly to the repository within these modules.

## Decision

Eliminate the hollow and unused modules entirely, and split the `ai-bridge` monolith into three focused modules with clear single responsibilities:

| Module | Package | Responsibility |
|--------|---------|----------------|
| `ai-backend` | `io.justsearch.aibackend` | LLM pipeline, llama-server bridge, backend abstraction |
| `gpu-bridge` | `io.justsearch.gpu` | GPU/VRAM detection, CUDA helpers, ORT GPU session support |
| `prompt-support` | `io.justsearch.prompts` | Prompt templates, reasoning pipeline, schema guards |

Current implementation note: this table records the accepted decomposition decision. In the live architecture, online `llama-server` lifecycle ownership now belongs to `modules/app-inference`; `ai-backend` owns backend abstractions and local translator support rather than the active server lifecycle.

Eliminated:
- **`app-ai`** — Collapsed into `app-inference`. The `IndexingAiService` interface chain (`GrpcAiTranslatorService`, `LocalAiTranslatorService`, `NoopAiTranslatorService`) was removed entirely.
- **`ai-worker`** — Deleted. The 4th-process architecture was never activated; `app-inference` manages the Brain process directly.
- **721 MB native binaries** — Removed from tracked repository content.

## Consequences

**Positive:**
- Two entire modules eliminated, reducing the module count and build graph complexity.
- 721 MB of tracked native binaries removed from the repository.
- Each remaining module has a single, clear responsibility — GPU detection is separate from prompt engineering is separate from LLM server management.
- Clear package boundaries (`io.justsearch.aibackend`, `io.justsearch.gpu`, `io.justsearch.prompts`) prevent re-entanglement.
- The hollow `IndexingAiService` interface chain is gone — no more three-implementation interface with one real implementation.

**Negative:**
- All references to `ai-bridge`, `app-ai`, and `ai-worker` in documentation, configuration, and ArchUnit rules needed updating. Approximately 15 docs/READMEs contained stale references (cosmetic sweep required).
- Existing ArchUnit tests that referenced `ai-bridge` package patterns needed updating to the new package names.
- The split creates three modules where one existed — more Gradle subprojects to maintain, though each is significantly smaller and more focused.

## Alternatives Considered

### Keep ai-bridge as a monolith
Leave the module intact and accept the mixed concerns. Rejected because the module was growing (171 files) with no natural cohesion between GPU detection code and prompt template code. New contributors consistently placed code in the wrong sub-package, indicating the boundaries were not self-documenting.

### Split into 2 modules instead of 3
Combine GPU detection with LLM server management (both "infrastructure") and separate prompt engineering. Rejected because GPU/VRAM detection is used by multiple consumers (ORT sessions in `ort-common`, CUDA status in `indexer-worker`) independently of LLM server management. A two-way split would still leave one module with mixed concerns.

### Keep app-ai as a thin translator
Retain the `IndexingAiService` interface and `app-ai` module as a clean contract seam between the application layer and AI backends. Rejected because the interface was hollow — it had a single real implementation (`GrpcAiTranslatorService`), a local fallback that duplicated the real implementation's logic, and a no-op stub. The indirection added complexity (three classes, one module, one proto definition) without enabling any actual pluggability or testability benefit.
