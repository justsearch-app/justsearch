---
title: System Overview
type: explanation
status: stable
description: "The Engine JVM, the process boundaries that survive, and the module roles behind them."
---

# System Overview

JustSearch is a **local-first** application: everything runs on the user's own machine, and the design is shaped by desktop constraints — OS file locking on Windows, UI responsiveness guarantees, and a single shared GPU.

Functionally it is more than a search index: over the local corpus it layers an optional on-device **LLM agent** (cited Q&A, summarize, extract, and gated file actions — see [22. Agent System Architecture](22-agent-system-architecture.md)) and ships a **production MCP server** (`POST /mcp`) so external AI agents (Claude Code, Cursor, Claude Desktop) can drive that same search and retrieval — see [Production MCP Server](../reference/mcp-production-server.md). That agent-facing capability is JustSearch's public center: it is exposed as a small, versioned **local runtime contract** (see [28. The Runtime Contract](28-runtime-contract.md)), and the desktop app is that runtime's first-party **reference client**. The processes below are how the runtime is delivered on the desktop.

## The process model

**One process boundary earns its cost when at least one of three things differs across it:** the
runtime and its toolchain churn, the failure domain, or ownership of a scarce resource. Where only
the *rate of change* differs, a module boundary — an interface in a contract module, pinned by
ArchUnit — is enough. That rule is [ADR-0049](../decisions/0049-one-engine-jvm-and-the-boundaries-that-survive.md),
which supersedes ADR-0001 (three OS processes) and ADR-0002 (gRPC + MMF hybrid IPC).

Applying it leaves **one JVM — the Engine — plus two native process boundaries.** The application
half (HTTP + MCP API, agent loop, conversation, RAG assembly) and the index half (Lucene, job queue,
indexing loop, pacing, durable stores) are both JVM code with the same failure domain and no scarce
resource to partition, so they share a process and meet at **ports**.

```mermaid
graph TD
    User[User] --> Engine[Engine JVM\nHeadlessApp.java]
    Engine -- HTTP --> AI[Inference Server\n(llama-server.exe)]
    Engine -- child processes --> Extract[Extraction sandbox pool\n(Tika / PDF / Office / OCR)]

    subgraph "Engine (one JVM)"
        Headless[Application half\nREST + MCP + agent + RAG]
        Ports[[Ports\nSearchPort, IndexingService, ...]]
        Index[Index half\nKnowledgeServer]
        Lucene[(Lucene Index)]
        SQLite[(Job Queue)]
        Headless --> Ports --> Index
        Index --- Lucene
        Index --- SQLite
    end

    subgraph "Tauri shell"
        Tauri[Rust shell]
        Lit[Lit frontend]
    end
    Tauri --> Engine
```

### 1. The Engine (one JVM)
*   **Entry point:** `io.justsearch.ui.HeadlessApp`
*   **Modules:** `modules/ui` (headless backend), `modules/ui-web` (Lit frontend), `modules/shell` (Tauri desktop shell), `modules/app-engine` (composition root), `modules/indexer-worker` + `modules/worker-services` + `modules/worker-core` (index half)
*   **JVM heap:** `-Xmx2g` in the packaged shell (`modules/shell/src-tauri/src/lib.rs`) — the old Head 512 MB plus the Worker's 1 GB, with headroom, because there is one heap now instead of two. The index half's larger working set is off-heap (ORT arenas, Lucene mmap), which `-Xmx` does not govern. The dev-runner deliberately sets no default cap.
*   **Sidecar host:** the Engine runs as a child process of the Tauri shell.
*   **Composition:** `io.justsearch.app.engine.EngineRoot` is the composition root. It is the only module permitted to see both halves; it binds every port and owns the two sequences that span them, startup and shutdown.
*   **API gateway:** exposes the REST surface used by the UI (e.g. `/api/status`, `/api/health`, `/api/knowledge/*`, `/api/inference/*`) plus the MCP endpoint, and reaches the index half through port calls.
*   **Watchdog:** monitors `llama-server` (`InferenceLifecycleManager`, policy in `BrainSupervisionPolicy`). There is no worker-process watchdog — the Worker's `SupervisionPolicy` went with the process it supervised.
*   **Deterministic failure surfacing:** index-half startup failures are captured and surfaced via `/api/status`, and a corrupt index or schema mismatch stamps a fatal-reason marker so the UI can offer "Rebuild index" instead of blind-restarting.
*   **One log:** `%DATA_DIR%/logs/engine.log`. There is no separate `worker.log`.
*   **`--add-modules=jdk.incubator.vector`** was removed to enable the JDK 25 AOT cache's full-module-graph optimization; Lucene uses its scalar fallback. See tempdoc 269 §D4a.

#### Ports: how the two halves meet

A **port** is an interface in a contract module (`modules/core` or `modules/app-api`), catalogued in
`governance/engine-ports.v1.json` with its owner and consumers, and bound only by `EngineRoot`.
Adding a port is a catalogue entry, an interface, and a binding.

**Application code never touches Lucene** — hard invariant 1, unchanged by the merge. Sharing an
address space does not license a controller to reach past a port into the index, and two ArchUnit
rules in `modules/app-launcher/src/test/` pin it: `IndexWriterOwnershipTest` keeps Lucene imports
inside the owner packages, and `LayeringEnforcementTest` rule 6b lets only
`io.justsearch.app.engine..`, `io.justsearch.indexerworker..` and `io.justsearch.adapters..` depend
on `io.justsearch.indexerworker.{server,services,loop}..`.

**Four operation contracts survived the channel.** Deadlines, per-call result-size bounds, streaming
flow control and cancellation were requirements of the *work*, not of the network, so they are
re-homed onto the port calls (`KnowledgeClient` / `EngineKnowledgeClient`) rather than deleted with
the transport. This is the part of the merge most likely to be got wrong, because a direct method
call appears to need none of them.

### 2. The index half ("the Knowledge Server")
*   **Composed by:** `EngineRoot`, in the Engine JVM. It has no `main`, no distribution and no port — the log line at boot is `KnowledgeServer started successfully (in-process; no port)`.
*   **Modules:** `modules/indexer-worker` (lifecycle, job queue, recovery), `modules/worker-services` (search, ingest, indexing loop, pacing, extraction), `modules/worker-core` (encoders), `modules/adapters-lucene` (the only module that depends on Lucene).
*   **Role:** the heavy lifter — all file indexing, text extraction, and hybrid search.
*   **Index ownership:** owns Lucene + the generation layout (`state.json`, `indices/<gen>/`) and orchestrates blue/green schema migrations when configured.
*   **Pacing, not partition:** with one heap and one set of pools, a runaway batch can starve the HTTP listener in a way two JVMs made impossible. The mitigation is `IndexingPacing` — the loop yields to hold indexing at `justsearch.indexing.foreground_duty_pct` (default 20%) while user-waiting calls are in flight — plus per-operation budgets, rather than a second address space. See `modules/indexer-worker/README.md`.
*   **Deferred model init:** the ONNX encoders load on a background thread while the ports already answer. Search degrades to BM25 and the loop skips embedding/SPLADE until they are wired.

### 3. The extraction sandbox pool
*   **Boundary rationale:** parsing untrusted files is the one place a native fault or an infinite loop is *expected*, so containing it is worth a process. [ADR-0048](../decisions/0048-extraction-isolation-and-indexing-pacing.md) decided this independently, and the merge makes it **more** valuable, not less: a crash in the Engine is now a crash of everything.
*   **Shape:** `ExtractionSandboxFactory` offers three modes — `IN_PROCESS`, `PROCESS` (a persistent pool of child JVMs, `PersistentExtractionSandbox`, recycled after `maxRequestsPerChild`), and the shipped default `AUTO`, which routes per file family between the two. The child's deadline is the enforcing one; the outer `TimeboxedContentExtractor` waits a 15 s grace beyond it so the pool's kill-at-the-deadline path is the mechanism that actually runs.

### Configuration: one resolution, no propagation

**There is one `ResolvedConfig`, and both halves read it.** The application half and the index
half share a JVM (lane F stage A), so config does not travel: `ConfigStore.global()` is the same
object for both, and a key resolved once is resolved for everything.

**Adding a new config key:** add it to `EnvRegistry`. That is the whole procedure.

**What this replaced, and why the replacement is smaller.** Until lane F stage A the Worker was a
second process, and getting config into it took three mechanisms plus a detector:

1. a **config snapshot** — `HeadlessApp` serialised the resolved config to
   `worker-config-snapshot.json` and passed its path as `-Djustsearch.worker.config_snapshot`,
   which the Worker loaded at config ordinal 450;
2. **blanket env forwarding** of every `JUSTSEARCH_*` variable through `ProcessBuilder`;
3. **explicit `-D` forwarding** of a declared set (`WorkerSpawner.WORKER_FORWARDED_PROPS`) for keys
   read before the snapshot loaded, or read by native code;

plus **divergence detection**: after the gRPC handshake the Head compared its values against the
Worker's effective values and logged WARNs on mismatch — a mechanism whose entire purpose was to
notice when the other three had failed to agree. Items A11, A13 and A19 deleted all four. Two
processes could disagree about their configuration; one cannot, so the detector had nothing left to
detect and the ordinal-450 tier had nothing left to cross.

### 4. The Inference Server ("The Brain")
*   **Boundary rationale:** a separate native binary with its own toolchain that owns VRAM — a different runtime, a different failure domain, and a scarce resource, so it clears the rule on all three counts.
*   **Executable:** `llama-server.exe` (Native Binary, no JVM).
*   **Managed By:** `InferenceLifecycleManager.java`
*   **Arguments:** `-m <model_path> [--mmproj <mmproj_path>] --host 127.0.0.1 --port <port> -c <ctx_size> -ngl <gpu_layers> <vram-tuning-flags...>` (loopback bind is always injected as defense-in-depth).
*   **Role:** Providing Intelligence (LLM Chat & RAG).
*   **VRAM Management:**
    *   **Zombie Killing:** On Windows, `Process.destroy()` can leave VRAM locked. We use `taskkill /F /PID` to enforce release.
    *   **Hung/Crash Recovery:** The manager monitors health periodically and will hard-kill a hung owned process; it also restarts on crashes when in Online mode.
    *   **External Instance Adoption:** If a healthy `llama-server` is already listening on the configured port, the manager can **adopt** it after probing `GET /props` (prevents restart loops and avoids accidentally adopting unrelated HTTP services). Adopted servers are still health-monitored; if they die mid-session, inference switches to Offline.
    *   **Health & Props:** The manager polls `GET /health` during startup and reads `GET /props` (best-effort) to learn the *actual* `n_ctx` and `model_alias` for diagnostics.

## Module roles

The Engine and the two native boundaries above are assembled from Gradle modules. The "half" column
says which side of the Engine's port seam a module sits on — it is a **module** boundary, not a
process one, except for the `Brain` rows. This table gives each significant module's **role**; the
authoritative inventory and the full dependency graph
are generated into `docs/reference/architecture/module-deps.md` by
`node scripts/architecture/module-deps.mjs`.

| Module | Half | Role |
|---|---|---|
| `modules/ui` | application | API front — Javalin REST API + MCP, calls the index half through ports, watchdogs `llama-server` |
| `modules/ui-web` | application | Frontend — TypeScript, Lit web components, Vite |
| `modules/app-services` | application | Application service layer — bootstrap/assembly, conversation, operation registry, `KnowledgeClient` (the port facade) |
| `modules/shell` | application | Tauri desktop shell |
| `modules/app-engine` | composition root | Binds every port and owns startup/shutdown — the only module permitted to depend on both halves |
| `modules/indexer-worker` | index | `KnowledgeServer` lifecycle, job queue, index recovery — a library, no `main` |
| `modules/worker-services` | index | Index service layer — ingest, indexing loop, pacing, extraction, RAG context, search execution |
| `modules/worker-core` | index | Encoders and index primitives — SPLADE, ONNX embedding |
| `modules/adapters-lucene` | index | Lucene search integration — the only module that depends on Lucene itself |
| `modules/indexing` | index | Index document model and field definitions |
| `modules/app-inference` | Brain | Online `llama-server` lifecycle management |
| `modules/ai-backend` | Brain | Backend abstractions and local translator support |
| `modules/ort-common` | shared | ORT session infrastructure — `OrtSessionAssembler`, `SessionHandle`, `OnnxSessionCache`, `ModelManifest` |
| `modules/reranker` | shared | Cross-encoder reranking |
| `modules/gpu-bridge` | shared | GPU/VRAM detection and hardware capability helpers |
| `modules/prompt-support` | shared | Prompt templates and reasoning-support utilities |
| `modules/configuration` | shared | `EnvRegistry`, `ConfigKey`, resolved-config assembly — the single configuration authority |
| `modules/app-api` | shared | Wire DTOs and API contract records |
| `modules/ipc-common` | shared | Protobuf **message** definitions, used as the in-process port DTOs (`indexing.proto`; no `service` block, no gRPC codegen, no MMF classes — lane F items A10/A14) |
| `modules/telemetry` | shared | Tracing and metrics emission |

## Design Philosophy

### "Verify, Don't Guess"
The system is built to be deterministic.
*   **Port Discovery (gRPC):** *Retired.* The Knowledge Server's gRPC listener used to bind port `0` (ephemeral) and write the assigned port to a shared **Memory-Mapped File (MMF)**. Lane F stage A merged the two halves into one JVM, so there is no second port to discover and no handoff to verify — the gRPC channel, the MMF bus and (at item A14) gRPC itself are deleted. See [ADR-0049](../decisions/0049-one-engine-jvm-and-the-boundaries-that-survive.md).
*   **Port Discovery (HTTP):** The UI-facing HTTP API is usually configured (default `33221`), but can also be ephemeral. The backend prints `JUSTSEARCH_API_PORT=<port>` to stdout and the desktop shell injects it (Tauri `api_port` command / bridge). In browser dev mode, if no explicit port is provided, the UI auto-discovers by scanning a small loopback range (currently `33221..33250`) and validating the `/api/status` payload.
*   **State Polling:** The frontend polls `/api/status` to determine if the backend is ready, rather than assuming it is after X seconds.
*   **Lifecycle Gate:** Automation uses `GET /api/health` as a **contract-tested gate** (schema v1). It returns HTTP `200` for `READY|DEGRADED` and `503` otherwise; `/api/status` remains the richer “what’s running?” payload.

### "One Owner" Policy
Data corruption on Windows is often caused by two processes trying to open the same file. Merging
the halves removed one *class* of that risk and left the rest, so the ownership rules stand:
*   **Lucene index:** owned exclusively by the **index half**, and reached only through a port. The process boundary that used to make this true by construction is gone; the two ArchUnit rules above are what make it true now.
*   **Configuration:** one `ResolvedConfig`, resolved once, read by both halves. Nothing is injected anywhere.
*   **User settings:** owned by the application half (`settings.json`), and contributed to the resolver at ordinal 300.
*   **Enforced by locks:**
    * `AppInstanceLock` prevents multiple app instances from sharing a single `dataDir`.
    * `IndexRootLock` prevents two Engines from mutating the same effective `indexBasePath` (important when `justsearch.index.base_path` is overridden).

### Graceful Degradation
The system is designed to work even if parts fail:
*   **No GPU:** Inference Manager detects VRAM shortage and refuses to start `Online Mode`, falling back to keyword search.
*   **Models not loaded yet:** the ports answer before the encoders finish loading — search degrades to BM25, the indexing loop skips embedding/SPLADE, and ingest queues normally.
*   **Index unusable:** a corrupt index or an exhausted rebuild brake leaves search serving read-only while `/api/status` reports why, instead of crashing the boot.
*   **Index-half failure is now Engine failure.** This is the honest cost ADR-0049 records: recovery isolation is gone. An OOM or wedge in indexing takes the API with it, where before the API survived to report it. Supervision — crash detection, restart budget, cooldown — is stage B's work; until it lands the mitigation is admission control and per-operation budgets, not a second address space.

## Current implementation notes / living docs

Some detailed “current state” docs live under `docs/reference/` while the design is still evolving:

- **Schema migration architecture (stable)**: `docs/explanation/11-index-schema-migration.md`
- **UI user readiness verification notes (living checklist)**: `docs/reference/ui-user-readiness.md`
