# `modules/ipc-common` — IPC contracts (Protobuf)

This module is the **shared home for Protobuf contracts** that define process boundaries and shared message DTOs used across JustSearch.

## Surfaces in this repo

### Knowledge Server gRPC surface (UI/Head ↔ Worker)

- **Proto**: `src/main/proto/indexing.proto`
- **Package**: `io.justsearch.ipc`
- **What it is**: The live, versioned-by-repo contract used by:
  - Head (HTTP API in `modules/ui`) → gRPC client (`RemoteKnowledgeClient`; retires at lane F stage A item A10)
  - Worker (`modules/indexer-worker`). Its gRPC **server** was deleted at lane F stage A item A9 and the worker process at item A11; the generated message types are still the shape the in-process ports pass, so this contract is live even though the transport under it is not.

### Versioned “v1” messages (in-process / tooling / infra)

- **Protos**: `src/main/proto/io/justsearch/ipc/v1/*.proto`
- **Package**: `io.justsearch.ipc.v1`
- **What it is**: Message types used by pipeline tooling / infra integrations. Not the Knowledge Server gRPC surface.

## Why this README exists

Historically the repo had **two different files named `indexing.proto`**. The Knowledge Server surface is the unversioned `indexing.proto` at the proto root; the `v1/` proto contains **message-only pipeline envelope types**.

If you are looking for “what the UI calls for search/indexing”, start with:

- `src/main/proto/indexing.proto`


