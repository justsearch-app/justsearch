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

The Engine is one JVM with a module boundary between the application-facing services and the index
half. The authoritative Gradle inventory and direct production edges are generated in
[`docs/reference/architecture/module-deps.md`](../reference/architecture/module-deps.md). That graph
is the source of truth for module names and dependencies; this page explains the architectural
direction those modules implement.

## The three rings

The Engine design in section 3.2 has three rings. Dependencies point inward: the API front may use
the core, and the core reaches platform or runtime implementations through interfaces. The
composition root is outside the rings and binds the implementations.

```text
API front     loopback HTTP, Host/Origin checks, auth, request identity,
              admission, REST, SSE, and MCP
  Core        ports, EngineContext, orchestration, retrieval, durable stores,
              indexing runtime, pacing, executors, and lifecycle state
  Edges       operating system, generative backend, inference, and extraction
              contracts whose implementations are bound by the root

Composition root: modules/app-engine / io.justsearch.app.engine
```

The API front is implemented primarily by `ui`; it owns the local transport and trust checks. The
core work is spread across existing `app-services`, `app-agent`, `indexing`, `worker-services`,
`worker-core`, and `indexer-worker` modules. `app-engine` is the only module that composes both
halves: `EngineRoot` binds the application services to the in-process index implementation and
constructs the `KnowledgeServer`. (`modules/app-engine/src/main/java/io/justsearch/app/engine/EngineRoot.java:18-34,363-405`)

The names `worker-core`, `worker-services`, and `indexer-worker` predate the one-JVM merge. They
identify index-half packages and Gradle modules; they do not identify a separate process. The same
rule applies to source-level names such as `KnowledgeServer` and `WorkerSearchService`. The current
module graph explicitly shows `app-engine` depending on those modules in process. (`docs/reference/architecture/module-deps.md:51-77`)

## Ports and the composition root

Application code reaches index operations through catalogued ports. `app-engine` is the binding site;
sharing a JVM does not allow application code to open Lucene directly. The relevant current seams
are:

| Concern | Current seam | Binding/owner |
| --- | --- | --- |
| Search | `SearchPort` and the `KnowledgeClient` search methods | Engine composition and index services |
| Ingestion and document writes | `IndexingService` / `KnowledgeClient` | `EngineRoot` and `EngineKnowledgeClient` |
| Operations and admission | `EngineAdmissionService`, `OperationAttemptRunner`, operation surfaces | Engine process owner |
| Component lifecycle | `EngineComponentRegistry` | `DefaultEngineProcessResources`, bound by `EngineRoot` |
| Native model children | `ManagedChildRegistry` | runtime manifest publisher and application host |

`EngineRoot` registers the `index` and `encoders` component handles beside the shared executor,
admission, and publication owners. The component registry is the D1 lifecycle and readiness
authority. Its public contract is a registry, snapshot, subscriptions, and serialized apply lease. (`modules/app-engine/src/main/java/io/justsearch/app/engine/EngineRoot.java:70-126,443-467`,
`modules/core/src/main/java/io/justsearch/core/component/EngineComponentRegistry.java:7-29`)

## Current module roles

These roles describe the modules that are actually in the Gradle graph:

| Module group | Current responsibility |
| --- | --- |
| `core`, `core-contracts`, `configuration`, `telemetry` | foundational types, contract records, resolved configuration, and telemetry |
| `app-api`, `app-agent-api`, `ipc-common` | application and operation contracts; `ipc-common` supplies protobuf message DTOs |
| `app-services`, `app-agent`, `app-inference`, `app-observability` | application orchestration, agent/runtime services, generative process lifecycle, and durable observations |
| `indexing`, `adapters-lucene`, `worker-core`, `worker-services`, `indexer-worker` | index ports, Lucene adapter ownership, indexing support, index services, and the in-process index runtime |
| `ort-common`, `reranker`, `ai-backend`, `gpu-bridge`, `prompt-support` | inference/session construction, reranking, backend abstractions, device support, and prompt support |
| `app-engine` | composition root that binds the application and index halves |
| `ui`, `app-launcher`, `ui-web`, `shell` | HTTP/desktop entry points and the Lit/Tauri clients; `ui-web` and `shell` are not Gradle projects |

The generated dependency graph lists the exact edges and also records test-only coupling. A module
role in this table does not override those generated edges.

## Inference and lifecycle ownership

Inference composition is owned by `indexer-worker`'s `InferenceCompositionRoot` and the shared ORT
module. It returns typed `InferenceSurface` values that are later split into generation-owned
`EncoderSet` and independently replaceable query roles. The surface's handles and observations are
closed through their owning generation; process resources, component observations, and admission
remain Engine-level owners. See [Engine inference composition](24-engine-inference-composition.md).

The process resource bundle owns the shared publication lock, admission controller, executor
registry, and component registry. `EngineRoot.closeIndex` first refuses teardown while admitted work
or operation attempts remain, stops recorded producers, closes the client, closes the in-process
index, and waits for `KnowledgeServer.awaitClosed` before releasing the server reference. (`modules/app-engine/src/main/java/io/justsearch/app/engine/DefaultEngineProcessResources.java:10-60`,
`modules/app-engine/src/main/java/io/justsearch/app/engine/EngineRoot.java:940-999`)

## Enforced boundaries

The architecture is enforced in code and governance:

- The port catalogue and `app-engine` binding gate keep port implementations at the composition root.
- ArchUnit layering rules keep foundation and contract modules independent of UI and implementation modules.
- Lucene construction remains in the index half; application code reaches it through ports.
- The generated module dependency page is checked against `settings.gradle.kts` and module build files.

<!-- generated:end -->
