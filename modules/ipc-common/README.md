# `modules/ipc-common` — IPC contracts (Protobuf)

This module is the **shared home for Protobuf contracts** that define process boundaries and shared message DTOs used across JustSearch.

## Surfaces in this repo

### Knowledge Server message surface (application half ↔ index half)

- **Proto**: `src/main/proto/indexing.proto`
- **Package**: `io.justsearch.ipc`
- **What it is**: The live, versioned-by-repo **message** contract — the DTO vocabulary at the Engine's in-process ports. There is no transport under it any more: lane F stage A deleted the gRPC client (`RemoteKnowledgeClient`, item A10), the gRPC server and its interceptors (item A9), the worker process (item A11), and finally the `service` blocks themselves plus the `protoc-gen-grpc-java` generator (item A14). **protoc generates messages and nothing else here.** Adding a `service` block back is not a way to add an API; it is a way to resurrect a deleted transport — a new capability belongs on the REST API or on a port interface (`governance/engine-ports.v1.json`, [ADR-0049](../../docs/decisions/0049-one-engine-jvm-and-the-boundaries-that-survive.md)).
- **Who uses it**: `KnowledgeClient` (`modules/app-services`) and its live binding `EngineKnowledgeClient` (`modules/app-engine`) on one side; `WorkerAppServices` and the services behind it (`modules/worker-services`, `modules/indexer-worker`) on the other.

### Versioned “v1” messages (in-process / tooling / infra)

- **Protos**: `src/main/proto/io/justsearch/ipc/v1/*.proto`
- **Package**: `io.justsearch.ipc.v1`
- **What it is**: Message types used by pipeline tooling / infra integrations. Not the Knowledge Server gRPC surface.

## Why this README exists

Historically the repo had **two different files named `indexing.proto`**. The Knowledge Server surface is the unversioned `indexing.proto` at the proto root; the `v1/` proto contains **message-only pipeline envelope types**.

If you are looking for “what the UI calls for search/indexing”, start with:

- `src/main/proto/indexing.proto`


