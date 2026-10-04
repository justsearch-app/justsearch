---
title: Process Coordination
type: explanation
status: stable
description: "How the Engine coordinates its in-process owners, native children, host supervision, signalling, and ordered shutdown."
---

# Process Coordination

JustSearch runs the application and index halves in one Engine JVM. `EngineRoot` is the only
composition root that sees both halves: it builds the `KnowledgeServer`, binds the in-process
ports, and exposes one `EngineKnowledgeClient` to the application layer. The process boundary that
remains inside the product is the native `llama-server` child and the extraction child pool; neither
is an alternative Engine or a second index owner. (`modules/app-engine/src/main/java/io/justsearch/app/engine/EngineRoot.java:18-34`,
`docs/decisions/0049-one-engine-jvm-and-the-boundaries-that-survive.md`)

## In-process composition and signalling

`EngineRoot.start(...)` creates the index owner, installs the terminal-writer and migration restart
callbacks, starts it, and then binds the `EngineKnowledgeClient` to the started serving view. A
start failure retains the physical `KnowledgeServer` until its close latch confirms completion, so a
retry cannot overlap a live queue, executor, or index lock. (`modules/app-engine/src/main/java/io/justsearch/app/engine/EngineRoot.java:488-585`)

`InProcessWorkerSignalBus` is a small compatibility name for signals that now share JVM state. The
GPU and energy values are fields on the process-wide `GpuSchedulingGauge`; there is no mapped signal
file or polling protocol for them. The development reload request is the existence of
`<dataDir>/runtime/dev-reload.request`, which the bus consumes by deleting the file. The bus exposes
no heartbeat, shutdown, or suicide-pact signal; those former signals belonged to the historical
pre-merge transport described below. (`modules/worker-core/src/main/java/io/justsearch/indexerworker/coordination/InProcessWorkerSignalBus.java:13-43,61-89,112-167`)

The Engine still owns native children. A child is registered only after `ManagedChild.fromProcess`
captures its process start time and executable identity. `ManagedChildReconciler` removes dead or
proven-mismatched records, preserves unknown live identities, adopts a healthy managed llama child
only when its declared configuration hash matches, and terminates a matching child that cannot be
adopted. (`modules/app-api/src/main/java/io/justsearch/app/api/runtime/ManagedChild.java:12-23,48-97`,
`modules/ui/src/main/java/io/justsearch/ui/runtime/ManagedChildReconciler.java:21-37,96-123`)

`MutableManagedChildRegistry` is the process-scoped registry. It seeds predecessor records before
child-capable bootstrap, and its only writer is the runtime-manifest publisher. Registration and
removal persist the complete immutable list before replacing the in-memory snapshot. A failed
persistence operation therefore leaves the previous snapshot authoritative. (`modules/ui/src/main/java/io/justsearch/ui/runtime/MutableManagedChildRegistry.java:10-15,25-65`,
`modules/app-api/src/main/java/io/justsearch/app/api/runtime/ManagedChildRegistry.java:7-14`)

## Host supervisor

The supervisor is outside the Engine JVM. Tauri production and the development runner implement the
same decision contract in Rust and Node. Both read `governance/supervision-contract.v1.json`; the
decision functions are pure, while their callers perform spawn, process-handle waits, cooldowns,
request-file writes, and termination. The supervisor states are `starting`, `running`, `stopping`,
`restarting`, and `exhausted`. Readiness enters `running` and starts the stability window. Repeated
health misses request an Engine shutdown; a missed shutdown deadline permits a force kill. A clean
requested restart is uncounted, while a crash or hang consumes the restart budget. (`scripts/dev/lib/engine-supervisor.cjs:1-15,129-146,148-194,205-243`,
`modules/shell/src-tauri/src/supervisor.rs:1-27,43-53,407-420`)

The Engine receives host shutdown requests through a request file watched by
`ShutdownRequestWatcher`. The watcher accepts only the plain host request shape. Upgrade receipts
use the separate nonce-bound bridge owned by `EngineShutdownSequence`; a request file cannot forge
that receipt. (`modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java:1590-1605,1622-1638`)

The Engine JVM launch enables the G1 garbage collector at both the production shell and development
runner spawn sites. (`modules/shell/src-tauri/src/lib.rs:772-788`, `scripts/dev/dev-runner.cjs:735-744`)

## Ordered Engine shutdown

`EngineShutdownSequence` is the single memoized shutdown owner. The first reason wins, concurrent
triggers join the same result, every named step runs even after an earlier step fails, and the final
result records errors plus native quiescence. The JVM hook joins an existing run; it does not start a
second close. If native ownership remains unquiesced, the sequence uses the hard-stop path. (`modules/app-engine/src/main/java/io/justsearch/app/engine/EngineShutdownSequence.java:22-49,135-212,215-297`)

`HeadlessApp.orderedShutdownSteps` binds the sequence to the concrete owners in this order:

1. Mark the runtime manifest shutdown pending.
2. Close operation admission and freeze operation leases.
3. Cancel interactive work.
4. Quiesce recorded-ingestion producers.
5. Drain admitted work and operation bodies.
6. Stop the shutdown-request watcher.
7. Stop the index health monitor.
8. Stop the local API.
9. Close the Head/application assembly.
10. Checkpoint durable operations.
11. Close the in-process index half and record its `GRACEFUL`/failure outcome.
12. Close the operations store.
13. Close tracing and telemetry.
14. Close process resources and release the application instance lock.

The implementation retains the index until Head work has ended, retains the operations store until
the Head and index close, and retains process resources until all stateful owners have closed. The
updater reads the named `index-half` outcome rather than a positional step. (`modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java:1643-1819`,
`modules/app-engine/src/main/java/io/justsearch/app/engine/EngineShutdownSequence.java:59-65`)

## Historical appendix: the former Head/Worker transport

Before ADR-0049, the application half and index half were separate JVMs. That historical
arrangement used gRPC for requests and memory-mapped files for heartbeats, port discovery, and
interrupts. The historical gRPC service blocks, `RemoteKnowledgeClient`, MMF signal buses, worker
spawner, and Worker process were deleted in the Engine merge. The old arrangement remains relevant
only for migration history and ADR-0001/ADR-0002; it is not a current coordination mechanism.
