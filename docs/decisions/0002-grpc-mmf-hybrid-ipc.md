---
title: "ADR-0002: Use gRPC + MMF Hybrid for IPC"
type: decision
status: stable
description: "gRPC for structured data transfer, MMF for sub-millisecond signaling."
date: 2026-02-03
probes:
  - adr-0002-mmf-layout-pinned
  - adr-0002-mmf-constants-pinned
  - adr-0002-grpc-present
last_reviewed: 2026-09-02
---

# ADR-0002: Use gRPC + MMF Hybrid for IPC

> **Under re-examination (decision-review lane F, engine merge, 2026-09).** The three-process
> split this IPC design serves is being re-derived; this ADR is the record of the original
> decision and may be superseded. Do not treat its Consequences as settled while lane F is open.

## Status

Accepted

## Context

The three-process architecture ([ADR-0001](0001-three-process-architecture.md)) requires reliable IPC between the Head (Main Process) and Body (Knowledge Server). Two distinct communication needs exist:

1. **Rich data transfer** — search results, document submission, health status. Requires structured payloads, type safety, and reasonable throughput.
2. **Sub-millisecond signaling** — heartbeats (suicide pact), port discovery, GPU arbitration flags, and (originally) user-activity detection. Requires near-zero latency and must work even during JVM GC pauses. The user-activity slot has since been retired: the Worker paces indexing on its own in-flight foreground-RPC gauge rather than on a Head-written timestamp, so that signal no longer crosses the MMF.

No single IPC mechanism handles both well. REST/HTTP adds too much overhead for heartbeat signals. Named pipes or sockets require custom serialization for rich payloads. Shared memory alone lacks the structured RPC model needed for search operations.

## Decision

Use a hybrid approach:

- **gRPC** for all structured data transfer (search queries/results, file submission, health checks, index operations). Provides strong typing via protobuf, streaming support, and built-in retry/deadline semantics.
- **Memory-Mapped Files (MMF)** for all signaling (heartbeat, port discovery, GPU active flag, user activity timestamp). A 64-byte shared memory segment (`WorkerSignalBus`) with atomic reads/writes at fixed offsets.

The Worker starts its gRPC server on port 0 (ephemeral). The OS assigns a port, and the Worker writes it to MMF offset 20. The Head polls this offset until it sees a non-zero value, then connects.

## Consequences

**Positive:**

- Heartbeat/signal reads are sub-millisecond and work during GC pauses (OS memory-mapped page, not JVM heap).
- gRPC provides type-safe contracts with protobuf schemas, deadline categories, and circuit breaker integration.
- Port discovery via MMF eliminates hardcoded ports and config-file coordination.
- The MMF "Suicide Pact" ensures zombie Worker processes self-terminate within 5 seconds if the Head crashes.

**Negative:**

- Two IPC mechanisms to understand and maintain.
- MMF layout is fragile — offset/size changes require coordinated updates across Head and Worker. Mitigated by single-ownership in `MmfWorkerSignalLayoutV1.java` and, until lane F stage A item A10, a cross-compat test. That test was deleted with the Worker-side bus it compared against: with one JVM there is no second reader whose view of the offsets could drift.
- Windows-specific complexity: MMF files are OS-locked while mapped, requiring explicit `Unsafe.invokeCleaner()` to unmap before the data directory can be deleted.
- MMF fields rely on "effectively atomic" aligned reads on x86/ARM — not guaranteed by the Java specification (acceptable for desktop target platforms).

## Alternatives Considered

### Pure REST/HTTP

Use REST for everything, including heartbeats and signaling.

**Rejected because:** REST heartbeats run inside the JVM and are susceptible to GC pauses — both sender and receiver can stall simultaneously, producing false stale-heartbeat readings. REST also has the same port-discovery chicken-and-egg problem as gRPC (need a listener before you can communicate the port). Finally, REST lacks the strong typing, streaming support, and deadline semantics that gRPC provides for search operations.

### Pure gRPC

Use gRPC for both data transfer and signaling (heartbeat via streaming RPC).

**Rejected because:** gRPC requires the server to be running and the channel to be connected before any communication. This creates a chicken-and-egg problem for port discovery — the Head needs to know the Worker's gRPC port before it can connect, but with pure gRPC there's no out-of-band channel to communicate the port. Also, gRPC heartbeats are JVM-level and susceptible to GC pauses.

### Named pipes / Unix domain sockets

Platform-native IPC for all communication.

**Rejected because:** Requires custom serialization for structured payloads (reinventing protobuf). Named pipes on Windows have their own quirks (blocking semantics, handle inheritance). Would sacrifice gRPC's built-in retry policies, deadline management, and circuit breaker integration.

See also: [Process Coordination](../explanation/02-process-coordination.md) for the full MMF layout and protocol details.

## Reassess When

- JDK restricts `Arena`-based memory mapping (Foreign Function & Memory API changes), breaking the current MMF signaling implementation.
- gRPC adds local/in-process discovery that eliminates the port-discovery chicken-and-egg problem, making pure gRPC viable for both data and signaling.

*Added by tempdoc 269 trigger audit (2026-03).*
