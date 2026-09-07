---
title: Module Architecture & Dependency Governance
type: explanation
status: stable
description: "Gradle multi-project structure, dependency layers, and governance rules."
---

# Module Architecture & Dependency Governance

JustSearch uses a Gradle multi-project architecture with dependency governance enforced at build and test time.
The authoritative module inventory and direct internal dependency edges are generated from `settings.gradle.kts` and `modules/**/build.gradle.kts` in `docs/reference/architecture/module-deps.md`.

## Module Layers

### Foundation Modules (No Internal Dependencies)

Representative leaf modules that other modules depend on:

| Module | Purpose |
|--------|---------|
| `core` | Base types, DTOs, `SearchPort` abstraction |
| `configuration` | `EnvRegistry`, `PlatformPaths`, SSOT loading |
| `telemetry` | NDJSON metrics, OpenTelemetry integration |
| `ort-common` | Shared ORT infrastructure (`SessionHandle`, `NativeSessionHandle`, `OrtSessionAssembler`, `SessionOptionsApplier`, `RuntimePolicy`, `ModelSessionPolicy`, `OnnxSessionCache`, `OrtCudaHelper`, `OrtCudaStatus`, `GpuSessionConfig`) |
| `ai-backend` | Backend abstractions and local translator support |
| `gpu-bridge` | GPU/VRAM detection and hardware capability helpers |
| `prompt-support` | Prompt templates and prompt/reasoning support utilities |

### API Contract Modules

| Module | Purpose |
|--------|---------|
| `app-api` | Application facade interfaces (`AppFacade`, `IndexingService`, `DocumentService`, `OnlineAiService`) |
| `app-agent-api` | Operation/registry substrate (Operation, OperationHandler, OperationCatalog, the `AgentToolEmitter` SPI) plus agent-facing request/response contracts |
| `ipc-common` | gRPC proto definitions and generated stubs |

### Application Services Layer

| Module | Purpose |
|--------|---------|
| `app-services` | `HeadAssembly` composition root + typed bootstrap graphs, orchestration hub |
| `app-search` | Search clients (gRPC, Lucene) |
| `app-indexing` | Indexing service, file watchers |
| `app-agent` | Agent runtime/service orchestration; also owns the built-in agent tools and their `AgentToolsOperationCatalog` |
| `app-inference` | Online inference lifecycle management, including llama-server start/adopt/health/reload/stop |

### Worker Processes

| Module | Purpose |
|--------|---------|
| `indexer-worker` | gRPC services, indexing loop, embeddings (Body process) |

### Entry Points

| Component | Purpose |
|-----------|---------|
| `ui` | HTTP REST API (Javalin) |
| `app-launcher` | Main entry point, CLI distribution |
| `ui-web` | Lit web-components frontend (non-Gradle project) |
| `shell` | Tauri desktop shell (non-Gradle project) |

## Process Boundary

The Head and Body run in separate OS processes with gRPC IPC:

```text
HEAD PROCESS                      | WORKER PROCESS
----------------------------------|---------------------------------
ui                                | indexer-worker
app-services                      |   - WorkerSearchService
                                  |   - WorkerIngestService
app-search (gRPC client)          |   - WorkerHealthService
app-indexing (gRPC client)        |   - EmbeddingService
                                  |   - LuceneIndexRuntime
    v                             |
ipc-common (proto) <--- gRPC ---> ipc-common (proto)
```

## Architectural Enforcement

### ArchUnit Tests

JustSearch uses ArchUnit test suites to enforce module boundaries at test time, including:

| Test Class | Module | Rules Enforced |
|------------|--------|----------------|
| `LayeringEnforcementTest` | app-launcher | Layering and dependency-direction rules |
| `ArchUnitSanityTest` | core | No AWT/Swing, no Lucene deps, no env access |
| `ArchitectureRulesTest` | app-api | No Lucene/aibridge deps, no env access |
| `IndexWriterOwnershipTest` | app-launcher | Only adapters-lucene can create `IndexWriter` |
| `IndexerWorkerGuardrailsTest` | indexer-worker | No env access, no test-support, MMF isolation |
| `UiApiGuardrailsTest` | ui | No env access, no proto DTO leakage |

### Key Layering Rules

1. **Foundation modules are leaf nodes:** `core`, `configuration`, and `telemetry` cannot depend on UI, app-service, adapter, AI, or worker modules.
2. **UI is the top layer:** Only `app-launcher` may depend on the `ui` module.
3. **`app-api` is a clean contract seam:** It must not depend on implementation modules.
4. **Worker isolation:** `indexer-worker` cannot depend on `ui` or head-side orchestration modules.
5. **No circular dependencies:** Key service modules avoid bidirectional coupling (for example, `app-inference` and `app-search` do not depend on `app-services`).

### Environment Access Restrictions

To prevent configuration leakage and ensure testability, environment access is restricted.

**Modules with NO env/sysprop access:**
- `core`
- `app-api`
- `adapters-lucene`
- `indexer-worker`
- `ui`

**Allowlisted classes (can access env via `EnvRegistry`):**
- `HeadAssembly` (entrypoint)
- `InferenceLifecycleManager`
- ~~`WorkerSpawner`~~ — deleted at lane F stage A item A11 with the worker process it launched
- `KnowledgeServerConfig`

### Resource Ownership

| Resource | Exclusive Owner | Enforcement |
|----------|-----------------|-------------|
| `IndexWriter` | `adapters-lucene` | ArchUnit `IndexWriterOwnershipTest` |
| `MappedByteBuffer` (Head) | `MainSignalBus` | ArchUnit guardrail |
| `MappedByteBuffer` (Worker) | `MmfWorkerSignalBus` | ArchUnit guardrail |

### Network Egress Isolation

AI modules (`ai-backend`, `gpu-bridge`, `prompt-support`) cannot use HTTP/network libraries directly:
- No `java.net.*`, `javax.net.*`
- No `okhttp3.*`, `org.apache.http.*`, `retrofit2.*`

Exception: gRPC service classes in workers are allowed for IPC.

## Gradle Build Governance

### Convention Plugins

Located in `build-logic/`:

| Plugin | Purpose |
|--------|---------|
| `conventions.jvm-base` | Java 21+, Spotless, PMD, Error Prone, JaCoCo |
| `conventions.locking` | Dependency locking |
| `conventions.deps-hygiene` | No dynamic/SNAPSHOT versions |

### Version Management

- **Catalog:** `gradle/libs.versions.toml` (version catalog)
- **Lockfiles:** root + per-module `gradle.lockfile` files
- **Verification:** `gradle/verification-metadata.xml` (SHA256 checksums)
- **Convergence:** root `build.gradle.kts` resolution strategies force version alignment

**Dependency locking scope:** The `conventions.locking` plugin activates Gradle dependency locking on production and test classpath configurations only. Tool-only configurations (SpotBugs, PMD, JaCoCo, Error Prone, protobuf locators) are excluded via exact-match set. Auxiliary PMD classpath configs (`mainPmdAuxClasspath`, etc.) mirror the app classpath and remain locked.

**Resolution strategies:** The root `allprojects` block forces version convergence to eliminate lockfile skew. Coordinated-release groups (`org.slf4j`, `org.ow2.asm`, `org.apache.logging.log4j`) use group-level forces. Independent libraries (commons-io, commons-codec, etc.) use per-artifact forces.

**Lockfile regeneration:** `./gradlew.bat --no-configuration-cache resolveAndLockAll --write-locks` regenerates all project lockfiles. CI runs a freshness check in both `fast_build` and `full_build` jobs.

## app-services: Orchestration Facade

`app-services` is intentionally orchestration-heavy and remains one of the highest fan-in modules in the build graph (see `docs/reference/architecture/module-deps.md` for the current count).

- `HeadAssembly.java`: composition root + startup wiring (replaced `AppFacadeBootstrap` and the `DefaultAppFacade` facade in tempdoc 519)
- Service wiring exposed via typed bootstrap-graph records (`SubstrateGraph`, `CapabilityGraph`) in `bootstrap/`
- Subsystems are organized in dedicated packages (for example `vdu/` and `worker/`)
- Dependency direction remains acyclic

## gRPC Service Contracts

| Service | Proto | Implementation | Module |
|---------|-------|----------------|--------|
| SearchService | indexing.proto | `WorkerSearchService` | indexer-worker |
| IngestService | indexing.proto | `WorkerIngestService` | indexer-worker |
| HealthService | indexing.proto | `WorkerHealthService` | indexer-worker |

## Port/Adapter Pattern

Primary interfaces in `app-api`:

| Interface | Implementations |
|-----------|-----------------|
| ~~`AppFacade`~~ (dissolved, tempdoc 519) | typed service records via `HeadAssembly` |
| `DocumentService` | `RemoteDocumentService` |
| `OnlineAiService` | `OnlineAiServiceImpl` |

## Plugin System (Planned)

AI capabilities (embeddings, reranking, VDU) are implemented directly in their respective modules:

| Capability | Implementation | Module |
|-----------|---------------|--------|
| Embeddings | `EmbeddingService` | indexer-worker |
| Cross-encoder reranking | `CrossEncoderReranker` | reranker |
| VDU (Visual Document Understanding) | `VduProcessor` | app-services |
| ORT session construction (single apply site) | `OrtSessionAssembler` + `SessionOptionsApplier` | ort-common |
| Worker inference composition (all ORT encoders) | `InferenceCompositionRoot.compose(...) → InferenceSurface` | indexer-worker |
| Model file selection (CPU/GPU) | `ModelManifest` | worker-core |

A ServiceLoader-based plugin system for extensibility (egress control, artifact integrity) is planned but not yet implemented. When searching for capability implementations, use the generated dependency graph as the source of truth for current module boundaries.

## Design Notes

**`ui` -> `core` direct dependency:** The `ui` module imports `DocumentTypeDetector`, `TokenEstimation`, and two other types directly from `core` (4 imports total). This is a direct dependency, not a violation — `core` is a foundation module intended for cross-cutting use.

**Near-duplication between `core` and `app-api` DTOs:** The `core` module defines `Query`, `Result.Hit`, `Facet`, and `Cursor` as internal DTOs for the gRPC contract boundary. The `app-api` module defines `SearchRequest`, `KnowledgeSearchResponse`, and related types as external REST contract DTOs. This near-duplication is intentional layering — internal gRPC contract vs external REST contract — with field-by-field translation occurring in `DefaultAppFacade` and `RemoteKnowledgeClient`. Not a code quality issue. See [ADR-0025](../decisions/0025-core-dto-dual-type-layering.md) for the full decision record.

**ai-bridge decomposition (ADR-0017):** The former `ai-bridge` monolith was split into focused modules. Current ownership is: `ai-backend` for backend abstractions/local translator support, `gpu-bridge` for GPU/VRAM detection, `prompt-support` for prompt support, and `app-inference` for llama-server lifecycle. The hollow `app-ai` gRPC translator module and the unused `ai-worker` process were deleted entirely. See [ADR-0017](../decisions/0017-ai-bridge-module-decomposition.md) for rationale and historical context.
