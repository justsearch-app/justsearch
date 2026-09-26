# WP4: inference-host decision, containment and measured triggers

Type: a decision record plus one architecture rule plus E measurement additions. Record it
through §17.6: a dated §0 line plus a short paragraph in §5.

## Question

Design §5 keeps the ONNX/CUDA encoders in the Engine JVM ("stage 1") and defers an
out-of-process inference host. The review flagged that much of D1's hardest remaining work
exists only because the encoders are in-process. Should the host come into Lane F?

## Findings

- **In-process-specific work:**
  - already on the branch: about 1.3k to 1.9k lines (`EncoderSet`, `NativeSessionHandle`
    leases +437/-82, `DeviceMemoryLine`, and an encoder share of `KnowledgeServer` and
    `IndexGenerationManager`);
  - still open: most of D1-12, D1-13 and D1-14, three of D1's four largest items;
  - designed but not coded: the ORT shutdown-hook exit strategy.
- **A host would remove:** native-fault blast radius, the beside-vs-in-place VRAM choice,
  per-handle lease accounting, the ORT exit hazard, and the ~40 s encoder reload on Engine
  restart (if the host is adopted warm like llama-server).
- **A host would keep:** generation binding of query embeddings, model identity per call,
  cancellation, readiness states, and the priority policy (it moves to the host queue).
- **Cost:** a minimal host is about 4 to 6 sessions. Its templates are llama-server's
  HTTP, health checks and warm adoption (`app-inference`) plus the extraction pool's spawn,
  registry and argfile handling. It puts an IPC hop back on request-time calls (query
  embedding, query SPLADE, rerank of 20, citation), which stage A just made in-process
  (`RemoteDocumentService` citation matching was on gRPC before A6).
- **Design §5's triggers** (field encoder faults, a second GPU runtime or OS, concurrent
  verification stacks, unacceptable stage-1 reconfigure cost): none is evidenced yet.

## Decision (recommended; the orchestrator records it)

**Keep the host out of Lane F.** D1-12, D1-13 and D1-14 are partly built and proven; moving
now forks attention across two unfinished migrations and re-adds IPC to the request path.
Two things make that deferral cheap and reversible:

1. **Containment, so a future host can *delete* the native machinery rather than untangle
   it.** Add an ArchUnit rule (beside the existing §5 pins on `InferenceSurface` and
   `*Assembly`):
   - classes in `io.justsearch.ort..` (`NativeSessionHandle`, leases, session policy) and
     `EncoderSet` are referenced only from the encoder owners: `io.justsearch.ort..`, the
     `indexerworker.server` inference and encoder-set classes, and the encoder services
     (`worker-core` embed/splade/ner, `reranker`, citation);
   - no `app-*`, `ui`, `app-engine` API-facing or `core` code depends on native session or
     lease types;
   - lease and quiescence accounting stays behind the `InferenceSurface`/`EncoderSet` API.
     Callers get results, never lease tokens.

   First enumerate the current dependents on the branch (`git grep` plus the ArchUnit
   importer). If a dependent outside that set exists, either move it behind the API or list
   it as a dated, reasoned exception. `DeviceMemoryLine` (a `core` type) is generic and
   allowed.
2. **Measure the triggers at E, so the next decision is data-driven.** Add a "host decision
   inputs" section to the E record, taken from rows E runs anyway:
   - native encoder faults observed (supervisor exit class `native crash`) across soak and
     recovery runs;
   - encoder reconfigure wall-clock and the text-only (`reloading`) window, in place and
     beside (from the D1 feature acceptance);
   - the share of Engine restart time spent reloading encoders;
   - request-time encoder latency (idle and contended), which is the baseline a host must
     not regress beyond its IPC cost.

   The design §5 paragraph then names the rule: **re-open the host question when any
   trigger is evidenced, or at the first release after Lane F, using these numbers.**

## Implementation plan

| # | Item | R/I | Acceptance |
|---|---|---|---|
| 4.1 | Dependent inventory plus the ArchUnit containment rule | R | the rule fails on a planted dependency from an `app-*` class (negative control), then passes; exceptions listed with reasons |
| 4.2 | Record the decision in design §0 and §5 | R | `docs-validate` for any canonical doc touched; §0 line present |
| 4.3 | E runbook: a "host decision inputs" section in `stages/E.md` §8 (record) | R | the section lists the four inputs and the source row for each |
| 4.4 | Minimal inference host | I | **not in Lane F**; a follow-up lane, gated by the triggers |

**Estimate:** under 0.5 session. Land 4.1 with the next D1-13 or D1-14 checkpoint, so new
native code is born contained.
