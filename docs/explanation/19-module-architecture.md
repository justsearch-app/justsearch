---
title: Module Architecture & Dependency Governance
type: explanation
status: stable
description: "The Engine's three rings, composition root, Gradle modules, ports, and dependency enforcement."
---

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
