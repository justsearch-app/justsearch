# App Services Module

This module is the integration "glue" of the application. It bridges the gap between the UI/Shell and the core business logic.

**Critical Responsibility**: It implements the **Process Management** for the new "Knowledge Server" architecture (Client/Server), while maintaining the Legacy (In-Process) stack for backward compatibility.

> **Configuration Dependency:** This module depends on `modules/configuration` for centralized config loading.
> Embedding clients (`LocalEmbeddingClient`, `GrpcEmbeddingClient`, `AnnRetrievalClient`) require explicit
> dimension injection—they no longer have hardcoded fallbacks. Use `FieldCatalogDef.vectorDimension()` to get
> the correct dimension. See [Configuration Module README](../configuration/README.md).

## 🧠 Key Concepts

> **Lane F stage A (tempdoc 936) has moved this ground, and it has now finished moving.** Item A6
> composes the index half **in-process** (`modules/app-engine` → `EngineRoot`); item A9/A10 deleted
> the gRPC server, the gRPC client (`RemoteKnowledgeClient` no longer exists), and the memory-mapped
> Head↔Worker signal bus (`MainSignalBus` no longer exists); item A11 deleted the worker child
> process, `WorkerSpawner`, and its supervision. **Every "process", "spawn", "gRPC", and "MMF"
> statement below this line describes the retired pre-lane-F shape** — kept as historical
> architecture narrative, not current fact — except where a note says otherwise.

1.  **The "Hollow" Shell**: *(historical — pre-lane-F)* The Main Process (UI) did *not* index or
    search directly; it spawned a `Worker Process`. Since item A6 it constructs the same Knowledge
    Server in its own JVM, and since item A9/A10 there is no gRPC channel between them either.
2.  **Worker Spawning** *(retired at item A11)*: `WorkerSpawner` launched the worker process,
    injecting specific JVM flags (Vector API, AppCDS). There is no child process to launch now.
3.  **IPC Coordination** *(retired at item A10)*: `MainSignalBus` polling on a dedicated thread is
    gone; the signals it carried live in the in-process `GpuSchedulingGauge`
    (`modules/core/src/main/java/io/justsearch/core/scheduling/GpuSchedulingGauge.java`) and
    `WorkerSignalBus` (`modules/worker-core/.../coordination/WorkerSignalBus.java`).
4.  **Self-Healing** *(retired at item A11)*: `WorkerSpawner` performed pre-flight checks to clean up
    stale locks from previous crashes.

---

## 🔥 Hot Paths & Execution Flows

### 1. Knowledge Search Flow (Target Architecture)
*Trace of a query going to the isolated worker.*

```text
[UI Request] POST /api/knowledge/search
      |
      v
[KnowledgeSearchController]
      |
      v
[RemoteKnowledgeClient] search(query)
      |
      +-> [MainSignalBus] Check Worker Port (MMF)
      |
      +-> [gRPC Stub] SearchService.search(...)
             |  (Network Loopback)
             v
      [Worker Process] (Lucene/HNSW/RRF)
             |
             v
[Response] SearchResponse (Hits)
```

### 2. Indexing Flow
*Trace of a file being indexed.*

```text
[OS File Event] MODIFY /docs/report.pdf
      |
      v
[WatcherBootstrap] onEvent()
      |
      v
[DefaultIndexingService] indexPath()
      |
      v
[gRPC → IndexerWorker / IndexingLoop]
      |
      +-> Extract Text (Tika)
      +-> Analyze (Lucene Analyzers)
      |
      v
[IndexRuntime] Write to Local IndexWriter
```

---

## 🗺️ Developer Task Map

| Goal | Architecture | Primary File(s) |
| :--- | :--- | :--- |
| **Update in-process GPU/energy signaling** | Knowledge | `KnowledgeServerBootstrap.java`, `GpuSchedulingGauge.java` (`modules/core`), `WorkerSignalBus.java` (`modules/worker-core`) — `MainSignalBus.java` no longer exists (item A10). |
| **Modify Worker Startup/Flags** | Knowledge | `modules/app-engine/.../EngineRoot.java` — the composition root. `WorkerSpawner.java` (Vector API, AppCDS, Stale Lock Cleanup) owned this until item A11 deleted it. |
| **Change in-process Knowledge Client logic** | Knowledge | `KnowledgeClient.java` (abstract base, `app-services`), `EngineKnowledgeClient.java` (`modules/app-engine` — the concrete in-process impl). `RemoteKnowledgeClient.java` no longer exists (item A9/A10). |
| **Edit Legacy Pipeline** | Legacy | `SearchRuntimeBootstrap.java`, `StageAdapterFactory` |
| **Fix Local File Watching** | Legacy | `DefaultIndexingService.java` |
| **Config Loading** | Shared | `ConfigManagerBootstrap.java` |

---

## 🧪 Verification & Diagnostics

### Self-Checks
1.  **Knowledge Health**: `InfraHealthController` (or `/api/knowledge/status`).
    *   *Verify*: "Engine State" should be `READY`.
2.  **IPC Sanity**: Check `~/.justsearch/worker_signal.lock` timestamp.
    *   *Verify*: Main process updates "Heartbeat" (bytes 8-15) every second.

### Log Patterns to Watch
| Pattern | Meaning | Action |
| :--- | :--- | :--- |
| `Knowledge Server is READY on port` | Worker started & MMF port discovery succeeded. | ✅ Normal |
| `Worker heartbeat expired` | "Suicide Pact" triggered; worker terminated. | ⚠️ Check Main Process pauses |
| `Indexing paced by foreground load` | Indexing throttled to its minimum duty while search-family RPCs are in flight. | ✅ Normal (Throttling) |
| `Found stale Lucene write.lock` | Self-healing active; recovering from previous crash. | ℹ️ Recovery |
| `Started watching /path` | Local file watcher active (Legacy Mode). | ℹ️ Legacy Context |

---

## Architecture Overview

### 1. Worker Management (The "Knowledge Server")
The core of the modern architecture. `app-services` acts as the **Process Manager** and **Client**:
*   **Spawning** *(retired at item A11; the composition root is `EngineRoot` in `app-engine`)*:
    `WorkerSpawner` launched `indexer-worker.jar` (or native image).
    *   **Optimization**: Previously injected `--add-modules jdk.incubator.vector` for SIMD; removed to enable AOT Cache (see tempdoc 269 §D4a).
    *   **Resilience**: Deleted stale `write.lock` files if the previous run crashed.
*   **Discovery**: Uses `MainSignalBus` (Memory Mapped File) to discover the ephemeral gRPC port chosen by the worker.
*   **Liveness**: Maintains a "Heartbeat" in the MMF; if this stops, the worker self-terminates (Suicide Pact).

### 2. Application Facade (Legacy/Local)
The `DefaultAppFacade` provides the interface for the **Local/In-Process** stack.
*   **Search**: Runs the `SearchRuntime` in the main JVM.
*   **Indexing**: Runs `DefaultIndexingService` in the main JVM.
*   *Note*: This path is kept for backward compatibility and development testing but is strictly separated from the Knowledge Server path.

### 3. Bootstrapping Flow
1.  **Config**: Loads `RuntimeConfig`.
2.  **Worker**: `KnowledgeServerBootstrap` starts the child process and waits for `READY`.
3.  **Legacy**: `HeadAssembly` initializes local Lucene resources (if enabled).

## File Directory & Purpose

### Root (`io.justsearch.app.services`)

| File | Purpose |
| :--- | :--- |
| `HeadAssembly.java` | **Legacy Entry Point**. Wires local in-process services. |
| `DefaultAppFacade.java` | **Legacy Facade**. Orchestrates in-process Search/Indexing. |

### Worker (`io.justsearch.app.services.worker`)

| File | Purpose |
| :--- | :--- |
| `KnowledgeServerBootstrap.java` | **New Architecture Entry**. Starts and manages the Knowledge Server (a child process until item A11; in this JVM via `EngineRoot` since item A6). |
| ~~`WorkerSpawner.java`~~ | Deleted at lane F stage A item A11. Handled process creation, JVM flags, self-healing, and dev/prod profile switching. |
| `RemoteKnowledgeClient.java` | gRPC Client for Search/Ingest/Health. |
| `MainSignalBus.java` | **IPC Layer**. Manages the Memory Mapped File (Signal Bus) for coordination. |

### AI (`io.justsearch.app.services.ai`)
*Bridge to AI capabilities (used by both stacks).*

| File | Purpose |
| :--- | :--- |
| `GrpcAiTranslatorService.java` | Client for AI services (Translation/Embedding). |

### Indexing (`io.justsearch.app.services.indexing`)
*Legacy/Local indexing implementation.*

| File | Purpose |
| :--- | :--- |
| `DefaultIndexingService.java` | **Local** Indexing Manager. |
| `WatcherBootstrap.java` | **Local** File Watcher. |

### Search (`io.justsearch.app.services.search`)
*Legacy/Local search implementation.*

| File | Purpose |
| :--- | :--- |
| `SearchRuntimeBootstrap.java` | **Local** Search Engine Bootstrapper. |
| `IndexSearcherProvider.java` | **Local** Lucene Searcher manager. |
