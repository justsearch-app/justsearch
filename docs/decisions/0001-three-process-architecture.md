---
title: "ADR-0001: Use Three Separate OS Processes"
type: decision
status: superseded
description: "Split into Head (UI), Body (indexing), and Brain (inference) processes. SUPERSEDED by ADR-0049: the Head and Body share one Engine JVM; the Brain's boundary survives."
date: 2026-02-03
superseded_by: "ADR-0049 (0049-one-engine-jvm-and-the-boundaries-that-survive.md)"
probes: []
last_reviewed: 2026-09-07
---

# ADR-0001: Use Three Separate OS Processes

> **SUPERSEDED by [ADR-0049](0049-one-engine-jvm-and-the-boundaries-that-survive.md) (2026-09-07).**
> The re-examination this banner announced concluded. The Head and Body do not differ in runtime,
> failure domain, or ownership of a scarce resource, so the boundary between them was paid for in
> mechanism rather than in isolation; they now share one Engine JVM and meet at catalogued ports.
> The Brain's boundary survives, for the reasons this ADR gave. **Read this document as history:**
> its Context is still the best account of why the split looked right in 2026-02, and its
> Consequences are no longer in force. The one invariant that survives verbatim — Head never
> touches Lucene — is carried by ADR-0049 and enforced by `IndexWriterOwnershipTest` plus rule 6b.
> Its `adr-0001-lucene-owners-pinned` probe moved to ADR-0049 rather than being retired; the
> property is unchanged, only its owning decision is new.

## Status

Accepted

## Context

JustSearch is a desktop search application that combines UI rendering, Lucene indexing, content extraction (Tika), and local LLM inference. These workloads have conflicting resource requirements:

- **UI responsiveness** demands low-latency event handling. A garbage collection pause or heavy Tika extraction in the same process would cause visible UI stutters.
- **Windows file locking** is aggressive. On Windows, memory-mapped files (used by Lucene's `MMapDirectory`) are locked by the OS kernel. If the UI process opens Lucene index files, it becomes impossible to delete or migrate them without killing the process — making schema migrations, corruption recovery, and clean shutdown unreliable.
- **LLM inference** requires exclusive GPU access (VRAM) and can crash independently (e.g., corrupted model files, OOM). Coupling it to the UI process would mean inference crashes kill the entire application.

A monolithic process would conflate all three failure domains. A crash in Tika parsing a malformed PDF would take down the UI. A Lucene `LockObtainFailedException` from concurrent access would be unrecoverable without restarting the entire app.

## Decision

Split the application into three OS processes with distinct ownership boundaries:

1. **Head (Main Process)** — UI host, API gateway, configuration owner, watchdog. Entry point: `HeadlessApp.java`. Never touches Lucene index files.
2. **Body (Knowledge Server)** — Lucene indexing, search, content extraction. Entry point: `IndexerWorker.java`. Exclusive owner of all index files.
3. **Brain (Inference Server)** — `llama-server.exe` (native binary, no JVM). Managed by `InferenceLifecycleManager.java`.

Each process can crash and restart independently. The Head monitors both children and implements auto-restart with exponential backoff.

## Consequences

**Positive:**

- Crash isolation: a Tika "poison pill" PDF crashes only the Worker; the UI stays responsive and shows "Index Offline" until auto-restart completes.
- File locking safety: only the Worker opens Lucene files, eliminating `AccessDeniedException` on Windows during schema migration or corruption recovery.
- Resource isolation: the Brain's VRAM usage is fully independent of JVM heap pressure.
- Independent scaling: each process can have its own JVM heap tuning (`-Xmx`).

**Negative:**

- IPC complexity: requires gRPC + MMF coordination between Head and Body (see [ADR-0002](0002-grpc-mmf-hybrid-ipc.md)).
- Startup latency: two JVMs + one native binary must initialize sequentially.
- Debugging difficulty: distributed tracing across processes is harder than single-process debugging.
- Zombie process risk: if the Head crashes, children must self-terminate (mitigated by the "Suicide Pact" heartbeat mechanism).

## Alternatives Considered

### Monolithic single-process

Run everything in one JVM. Simplest possible architecture.

**Rejected because:** Windows file locking makes this untenable — the UI process would hold Lucene file locks, preventing index migration, corruption recovery, and clean deletion. GC pauses from heavy indexing would cause UI stutters.

### Two-process (UI + combined backend)

Merge the Knowledge Server and Inference Server into a single backend process.

**Rejected because:** The Worker already uses llama.cpp in-process via FFM for embeddings, but the generative server API (chat/streaming/vision/KV cache management) is a much larger surface. Wrapping the full `llama-server` API via FFM would multiply the binding complexity for marginal benefit. More importantly, crash isolation is the key driver — a model OOM or corrupted GGUF file would take down the indexer, and VRAM management (load/unload cycles) would compete with Lucene's memory-mapped I/O in the same process.

See also: [System Overview](../explanation/01-system-overview.md) for the full architecture description.

## Reassess When

- Targeting a platform without aggressive file locking (Linux/macOS-only), removing the primary reason Head and Body must be separate JVMs.
- Worker is replaced by an external search server (e.g., hosted Lucene/Elasticsearch), eliminating the local file-locking constraint entirely.

*Added by tempdoc 269 trigger audit (2026-03).*
