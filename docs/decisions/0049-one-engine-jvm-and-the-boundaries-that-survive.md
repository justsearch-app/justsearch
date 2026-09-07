---
title: "ADR-0049: One Engine JVM, and the process boundaries that survive"
type: decision
status: stable
description: "The application half and the index half share one JVM and meet at catalogued ports; the boundaries that remain are llama-server and the extraction child pool, each justified by a differing runtime, failure domain or scarce resource."
date: 2026-09-07
supersedes:
  - "0001-three-process-architecture.md"
  - "0002-grpc-mmf-hybrid-ipc.md"
probes:
  - adr-0049-lucene-owners-pinned
  - adr-0049-engine-port-boundary
  - adr-0049-no-grpc-in-module-builds
last_reviewed: 2026-09-07
---

# ADR-0049: One Engine JVM, and the process boundaries that survive

## Status

Accepted (2026-09-07). Supersedes [ADR-0001](0001-three-process-architecture.md) (three separate OS
processes) and [ADR-0002](0002-grpc-mmf-hybrid-ipc.md) (gRPC + MMF hybrid IPC), which are the record
of the decision this replaces and are marked `superseded`.

Implemented across lane F stage A (tempdoc 936, design `docs/design/lane-f-engine-jvm/design.md`).

## Context

ADR-0001 split the product into Head (UI + API), Body (indexing + search) and Brain (inference).
ADR-0002 gave the first two a gRPC channel plus a memory-mapped file for sub-millisecond signalling.
Both were reasonable in 2026-02 and both were re-examined when the cost of the boundary became
measurable.

**What the split actually bought, stated fairly.** Two things, and only two. *Recovery isolation*: a
Worker OOM or wedge left the API answering, so the product could report its own failure. *Resource
partition*: two heaps and two thread pools by construction, so a runaway indexing batch could not
starve the HTTP listener.

**What it cost.** The Head and the Worker do not differ in the ways a process boundary is supposed
to pay for. Same runtime (both JVM; Lucene 10 is pure Java over Panama), same failure domain (Java
exceptions), and neither owns a scarce resource the other wants — the GPU is owned by the models,
which sit on both sides. The boundary between them was therefore paid for in mechanism rather than
in isolation: a memory-mapped signal bus with a suicide pact, an ephemeral-port handoff, a
config-snapshot tier at its own resolution ordinal, three argv builders, 49 RPCs, a circuit breaker,
deadline categories, and a client stack that had to re-implement flow control the wire gave away
for free. Every one of those had to be understood before an ordinary change could be made safely,
and several were found during the merge to have been silently broken for weeks — a foreground-load
gauge with no producer, a hot-reload trigger nothing read, a config forwarding path nothing
consumed.

**The observation that decided it.** The Worker served both search *and* indexing, so the split
never isolated retrieval from indexing — the two things that actually contend. It isolated the API
from the process that owns the index, which is a narrower property than the architecture's name
suggested.

## Decision

**One process boundary earns its cost when at least one of three things differs across it:** the
runtime and its toolchain churn, the failure domain, or ownership of a scarce resource. A module
boundary — an interface in a contract module, pinned by ArchUnit — is enough when only the *rate of
change* differs.

Applying that rule:

| component | runtime | failure domain | owns | boundary |
|---|---|---|---|---|
| HTTP + MCP API, agent loop, conversation, RAG assembly | JVM | Java exceptions | nothing scarce | **module** |
| Lucene index, job queue, indexing loop, pacing, durable stores | JVM | Java exceptions | disk | **module** |
| ONNX encoders (embedding, NER, SPLADE, reranker, citation) | ORT + CUDA | native faults | GPU (VRAM) | in-process for now; a boundary here is the inference lane's decision under this rule |
| generative LLM | llama.cpp | native faults | GPU (VRAM) | **process** — already, and it stays |
| document parsing (Tika, PDF, Office, OCR) | JVM + native parsers | untrusted input | CPU | **process** — the extraction child pool (ADR-0048), and it stays |

**So the application half and the index half share one JVM — the Engine — and meet at ports.** A
port is an interface in a contract module (`core` or `app-api`), catalogued in
`governance/engine-ports.v1.json` with its owner and consumers, and bound only by the composition
root `io.justsearch.app.engine`. Adding a port is a catalogue entry, an interface, and a binding.

**The two surviving process boundaries are not exceptions to the rule; they are the rule applied.**
`llama-server` is a separate native binary with its own toolchain that owns VRAM. The extraction
child pool exists because parsing untrusted files is the one place a native fault or an infinite
loop is *expected*, and containing it is worth a process — ADR-0048 decided that independently, and
the merge makes it *more* valuable, not less, because a crash in the Engine is now a crash of
everything.

**Head never touches Lucene, still.** Sharing an address space does not license application code to
reach past a port into the index. That invariant survives ADR-0001 verbatim and is enforced from two
sides: `IndexWriterOwnershipTest` keeps Lucene imports inside the two owner packages, and
`LayeringEnforcementTest`'s rule 6b permits only `io.justsearch.app.engine..`,
`io.justsearch.indexerworker..` and `io.justsearch.adapters..` to depend on
`io.justsearch.indexerworker.{server,services,loop}..`.

**The four operation contracts survive the channel.** Deadlines, per-call result-size bounds,
streaming flow control and cancellation were requirements of the *work*, not of the network. They
are re-homed onto the port calls rather than deleted with the transport. This is the part of the
merge most likely to be got wrong, because a direct method call appears to need none of them.

## Consequences

**Given up, deliberately, and this is the honest cost.** Recovery isolation is gone: an OOM in
indexing now takes the API with it, where before the API survived to report it. Resource partition
is gone: one heap, one set of pools, so a runaway batch can starve the HTTP listener in a way two
JVMs made impossible. Supervision — crash detection, restart budget, cooldown — has no producer at
the end of stage A and is stage B's work. These are real losses, not accounting entries, and the
mitigation is admission control plus per-operation budgets rather than a second address space.

**Gained.** One config resolution, so two halves cannot disagree about their configuration; one log;
one AOT cache and one distribution; one lifecycle to reason about. A large IPC substrate deleted —
the measured inventory (files, RPCs, MMF fields, argv builders, the config ordinal, the log file,
tests) is the positive-benefit table in `docs/design/lane-f-engine-jvm/stages/A.md` §16, kept there
rather than restated here so there is one count and not two. With it goes the class of defect the
merge kept surfacing: a producer writing somewhere no consumer reads. Trace context is current on the callee's thread by construction rather than by header
propagation — with one exception worth naming, because it is the shape of what this merge gets
wrong: a thread hand-off still loses it, so an executor that fans out must wrap its tasks.

**gRPC is gone from the product.** It went in two steps. Items A9-A11 removed it from the
Head↔index channel, which is what ADR-0002 was about. Item A14 then removed the remainder: the
`service` blocks in `indexing.proto`, the separate infra-health gRPC service and the Netty server
that hosted it, and the `protoc-gen-grpc-java` generator in `modules/ipc-common`. No module
declares a gRPC dependency and no gRPC method is served. The infra-health service was deleted
outright rather than migrated — nothing dialled its two RPCs, and an HTTP handler over the same
`InfraDiagnosticsService` payload already existed (`InfraHealthController`). What survives of
`indexing.proto` is its message half, still the DTO vocabulary at the in-process ports. The
absence probe below stays scoped to the index-half module build files, not widened to the
repository: it is the probe for *this* decision, and an absence probe that grows to cover
everything stops meaning anything.

**Reversibility.** Re-splitting is possible but is not a rollback: the ports are the seam, so a
future boundary would be drawn at a port and would need its own ADR under the decision rule above.
No dual-mode fallback exists, deliberately — maintaining four mechanisms in two modes was judged
worse than shipping a fix release.

## Alternatives considered

**Keep the split and fix the mechanisms.** Rejected: the mechanisms were not individually broken so
much as individually unnecessary, and their maintenance cost was being paid to buy a property
(recovery isolation) that a supervisor can provide more cheaply than a second JVM.

**Merge behind a flag, flip the default later.** Withdrawn by the owner on 2026-09-07. A flag means
every one of the four operation contracts, plus spawn, config and logging, exists in two shapes at
once; the branch would carry both indefinitely and neither would be well tested.

**Merge only search, keep indexing separate.** Rejected: it is the same JVM either way, since the
Worker served both. Splitting search from indexing is a different decision with different evidence,
and this ADR does not make it.

## Reassessment triggers

- **An OOM or wedge in indexing takes the API down in the field.** That is the loss above becoming
  concrete. The answer is stage B's supervisor, not a re-split — unless the supervisor proves
  insufficient, which would reopen this ADR.
- **A paired measurement shows the merge did not deliver its expected gains** (startup, memory,
  agent-loop latency). The design's decision rule says a failed envelope is answered by the rule,
  not by re-splitting.
- **The ONNX encoders acquire their own process** on the inference lane. That is anticipated by the
  table above and does not contradict this decision.
- **A second entry appears in the port catalogue's `operations` or `memory` rows as a real
  interface.** Those are catalogued as non-interfaces today; making them ports is a design step this
  ADR permits but does not perform.

## Evidence

Design and staging: `docs/design/lane-f-engine-jvm/design.md` (§2 the decision rule, §3.3 ports, §6
the four operation contracts, §8 the shared-resource consequences) and `stages/A.md` §0.1, which
records what each item actually found — including the three silently-broken mechanisms named in the
Context above. Live check after item A13: `docs/design/lane-f-engine-jvm/evidence/A/a13-live-check.txt`
(one JVM, index half READY in 4.7 s, 30 documents ingested and enriched, hybrid search correct; the
second JVM observed after ingest is the extraction child pool, which is this ADR's surviving
boundary doing its job).
