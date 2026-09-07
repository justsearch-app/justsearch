---
description: "TRIGGER when: creating new modules, modifying settings.gradle.kts, restructuring module boundaries, adding ArchUnit tests, or investigating module dependency issues. Loads module architecture constraints and governance rules."
user-invocable: true
---

# Module Architecture Context

Reference for module structure, dependency governance, and architectural enforcement.
Load this before creating modules, changing dependencies, or restructuring boundaries.

## Quick Facts

- **3 module layers:** Foundation (no internal deps) → API Contract → Application Services → Entry Points
- **Current modules from ai-bridge split:** `ai-backend`, `gpu-bridge`, `prompt-support` (see ADR-0017)
- **Deleted modules:** `app-ai`, `ai-worker`, `ai-bridge` — do not reference these
- **ArchUnit enforces:** layering, env access restrictions, resource ownership, network egress isolation
- **Convention plugins** in `build-logic/` enforce: Java version, dependency locking, PMD, Spotless, Error Prone

## After Module Changes

```bash
node scripts/architecture/module-deps.mjs --update-canonical  # update dep graph doc
node scripts/docs/llmstxt-generate.mjs                        # regenerate index
```

## Key ADRs

- ADR-0017: ai-bridge module decomposition
- ADR-0025: Core DTO dual-type layering (gRPC vs REST)

## Module-boundary test template (tempdoc 518 Appendix G S1 / E.1)

When a module exposes a "concrete implementation + role-typed interfaces"
pair (the impl is internal; consumers should only hold interface
references), copy this two-rule ArchUnit pattern. Canonical reference:
`modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceModuleBoundaryTest.java`.

```java
final class <Module>BoundaryTest {

  private static final String <MODULE>_INTERNALS = "io.justsearch.<package>..";

  /** Packages allowed to import inference internals. Composition root + the module itself. */
  private static final String[] PERMITTED_IMPORTERS = {
    "io.justsearch.<package>..",                  // the module itself
    "io.justsearch.app.services",                 // top-level composition root
    "io.justsearch.app.services.bootstrap..",     // BootstrapInferenceFactory et al.
    "io.justsearch.app.services.worker..",        // KnowledgeServerBootstrap et al.
  };

  /** Rule 1: nothing outside permitted importers may import internals at all. */
  @Test
  void internalsAreNotImportedOutsidePermittedPackages() {
    var importedClasses = new ClassFileImporter()
        .withImportOption(loc -> !loc.contains("/test/"))
        .importPackages("io.justsearch..");
    noClasses().that().resideOutsideOfPackages(PERMITTED_IMPORTERS)
        .should().dependOnClassesThat().resideInAPackage(<MODULE>_INTERNALS)
        .check(importedClasses);
  }

  /** Rule 2: the concrete mega-class (impl) is only held at the composition root. */
  @Test
  void concreteImplIsOnlyHeldAtCompositionRoot() {
    var importedClasses = new ClassFileImporter()
        .withImportOption(loc -> !loc.contains("/test/"))
        .importPackages("io.justsearch..");
    noClasses().that().resideOutsideOfPackages(PERMITTED_IMPORTERS)
        .should().dependOnClassesThat()
        .haveFullyQualifiedName("io.justsearch.<package>.<ConcreteImpl>")
        .check(importedClasses);
  }
}
```

**When to write this test pair:**

- The module has a non-trivial implementation class (a manager, a god
  facade, an orchestrator) plus role-typed interfaces in `app-api`.
- Consumers in `app-services` or elsewhere should NOT hold the
  concrete impl as a field — only the role interfaces.
- A future regression where someone adds `import
  io.justsearch.<package>.<ConcreteImpl>` to a non-composition-root
  class should fail at PR time.

**Why two rules and not one:** Rule 1 enforces the package-level
isolation (no random class outside the module can reach in). Rule 2
catches the narrower case where the impl-name leaks to a non-composition-
root caller via auto-import or copy-paste — even if some other internal
package were legitimately referenceable. This is the discipline boundary
that protects the role-typed-interface contract from drift.

**Avoid:** Spring-Modulith-style `<module>.internal.*` package renames
in this codebase. The explicit FQN-allowlist above is grep-discoverable
and doesn't churn every import statement. The convention-based approach
is fine in principle but the migration cost is uniformly bigger than
the benefit.

<!-- generated:start — do not edit between markers; run: node scripts/docs/skills-sync.mjs -->

<!-- source: docs/explanation/19-module-architecture.md -->

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
| `ipc-common` | Protobuf **message** definitions (`indexing.proto`) and the classes generated from them, used as the in-process port DTOs. No `service` block and no gRPC stub generation since lane F item A14 |

### Application Services Layer

| Module | Purpose |
|--------|---------|
| `app-services` | `HeadAssembly` composition root + typed bootstrap graphs, orchestration hub |
| `app-engine` | The Engine composition root (`EngineRoot`) — the only module that binds the in-process port implementations and depends on both halves |
| `app-agent` | Agent runtime/service orchestration; also owns the built-in agent tools and their `AgentToolsOperationCatalog` |
| `app-inference` | Online inference lifecycle management, including llama-server start/adopt/health/reload/stop |

### Worker Processes

| Module | Purpose |
|--------|---------|
| `indexer-worker` | Index-half services, indexing loop, embeddings. Composed in-process by `EngineRoot`; not a separate process since lane F item A11 |

### Entry Points

| Component | Purpose |
|-----------|---------|
| `ui` | HTTP REST API (Javalin) |
| `app-launcher` | Main entry point, CLI distribution |
| `ui-web` | Lit web-components frontend (non-Gradle project) |
| `shell` | Tauri desktop shell (non-Gradle project) |

## Engine boundary

The Head and Body are one **Engine** JVM ([ADR-0049](../decisions/0049-one-engine-jvm-and-the-boundaries-that-survive.md)). They meet at
catalogued in-process **ports** — plain Java interfaces in contract modules — bound by the single
composition root `EngineRoot` (`modules/app-engine`). The catalogue is
`governance/engine-ports.v1.json`; ArchUnit rule 6b plus the `engine-port` gate pin it, so only the
composition root binds an implementation:

```text
ENGINE JVM
------------------------------------------------------------------
ui  ──────────────► app-services (SearchServiceCalls,
                                  IngestServiceCalls)      [ports]
                          │
                    app-engine (EngineRoot binds the ports)
                          │
                    indexer-worker / worker-services
                      - WorkerSearchService
                      - WorkerIngestService
                      - WorkerHealthService
                      - EmbeddingService
                      - LuceneIndexRuntime

ipc-common (indexing.proto messages) supplies the DTOs at the port
signatures — no service block, no stubs, no wire (item A14).
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
| `MappedByteBuffer` (Head) | *none — forbidden outright* | ArchUnit guardrail |
| `MappedByteBuffer` (Worker) | *none — forbidden outright* | ArchUnit guardrail |

### Network Egress Isolation

AI modules (`ai-backend`, `gpu-bridge`, `prompt-support`) cannot use HTTP/network libraries directly:
- No `java.net.*`, `javax.net.*`
- No `okhttp3.*`, `org.apache.http.*`, `retrofit2.*`

(The enforcing rule is `ArchUnitEgressTest.translatorLayersMustStayOffline` in `modules/ai-backend`; it is scoped to `io.justsearch.aibackend.local..` / `.backend..` and carries no exceptions.)

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

## Port Contracts

Item A14 deleted the three `service` blocks these used to be (`SearchService`, `IngestService`,
`HealthService` in `indexing.proto`). The same call surface — same method names, same request and
response messages — is now a set of plain Java interfaces bound in-process:

| Port interface | Module | Implementation | Module |
|---------|-------|----------------|--------|
| `SearchServiceCalls` | app-services | `WorkerSearchService` (via `WorkerSearchCalls`) | worker-services / app-engine |
| `IngestServiceCalls` | app-services | `WorkerIngestService` (via `WorkerIngestCalls`) | worker-services / app-engine |
| — (health folded into the status surface) | — | `WorkerHealthService` | worker-services |

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

**Near-duplication between `core` and `app-api` DTOs:** The `core` module defines `Query`, `Result.Hit`, `Facet`, and `Cursor` as internal DTOs for the port contract boundary (the gRPC boundary it was drawn for, until lane F stage A). The `app-api` module defines `SearchRequest`, `KnowledgeSearchResponse`, and related types as external REST contract DTOs. This near-duplication is intentional layering — internal port contract vs external REST contract — with field-by-field translation occurring in `DefaultAppFacade` and `KnowledgeClient`. Not a code quality issue. See [ADR-0025](../decisions/0025-core-dto-dual-type-layering.md) for the full decision record.

**ai-bridge decomposition (ADR-0017):** The former `ai-bridge` monolith was split into focused modules. Current ownership is: `ai-backend` for backend abstractions/local translator support, `gpu-bridge` for GPU/VRAM detection, `prompt-support` for prompt support, and `app-inference` for llama-server lifecycle. The hollow `app-ai` gRPC translator module and the unused `ai-worker` process were deleted entirely. See [ADR-0017](../decisions/0017-ai-bridge-module-decomposition.md) for rationale and historical context.

<!-- generated:end -->
