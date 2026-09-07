---
title: Architectural Risk Register
type: reference
status: stable
description: "Living registry of consciously accepted trade-offs, monitored risks, and design tensions, each naming one instrument that can be checked."
---

# Architectural Risk Register

Living document tracking architectural trade-offs that are **not bugs** (use `docs/reference/issues/`) and **not one-time decisions** (use `docs/decisions/`). Each entry represents a tension we're aware of, have evaluated, and are either accepting or monitoring.

Restored 2026-09-02 from the 2026-03 review that created it (the file was deleted seven days after that review with no successor, so `RISK-00N` survived only inside working history). Every entry now names an **instrument** — one thing a machine can check — and the `adr-coverage` kernel gate fails when an instrument reference stops resolving. That is the point of the field: a row whose instrument stops resolving is a lane that closed without building what it promised.

## How to use this document

- **Add entries** when you identify a trade-off during development that doesn't fit issues or ADRs.
- **Update status** when circumstances change (new evidence, resolved by refactoring, etc.).
- **Reassess** entries when their `Reassess when` trigger fires. A fired trigger that nobody acted on is recorded as fired, with the evidence — never quietly restated as still-monitoring.
- **Resolve entries** by setting `**Status:** Resolved` and listing the id in the [Resolved](#resolved) index. The row itself stays in place so its instrument keeps being checked; a resolved risk's instrument is what would notice the resolution coming undone. Periodically prune resolved entries that are no longer instructive.

## Status values

| Status | Meaning |
|--------|---------|
| **Accepted** | Known trade-off, consciously accepted. No action planned. |
| **Monitoring** | Risk is real but manageable. Watching for trigger conditions. |
| **Mitigating** | Active work underway to reduce the risk. |
| **Resolved** | Risk eliminated. Entry kept briefly for reference, then pruned. |

## Entry format

- **ID**: `RISK-NNN` (sequential, never reused)
- **Category**: `performance` | `reliability` | `security` | `maintainability` | `operations` | `ai-quality`
- **Status**: `Accepted` | `Monitoring` | `Mitigating` | `Resolved`
- **Trade-off**: What we chose and what we gave up (1-2 sentences)
- **Impact**: What happens if this risk materializes (1-2 sentences)
- **Reassess when**: Concrete trigger for re-evaluation. This IS the trigger; the gate does not read it, a human does.
- **Instrument**: Exactly one machine-checkable reference, in the grammar below. Required.
- **Owner tempdoc**: The working-history document that owns the remaining work, in prose ("tempdoc 885 item 21"). Never a link — canonical docs must not link `docs/tempdocs/`. `none — <reason>` when no lane owns it.
- **Last reviewed**: `YYYY-MM-DD`, the date a human last read the row against `main`.
- **Notes**: Optional. Evidence, mitigations, related ADRs/issues.

### Instrument grammar

`**Instrument:**` carries exactly one reference. The `adr-coverage` gate resolves it on every run
(`scripts/governance/gates/adr-coverage/enforcer.mjs`).

| Form | Resolves when |
|------|---------------|
| `gate:<id>` | `<id>` is a gate id in `governance/registry.v1.json` |
| `check:scripts/ci/<name>.mjs` | the file exists |
| `test:<repo-relative path>#<member>` | the file exists AND still textually declares `<member>` |
| `metric:<id>` | `<id>` appears in at least one file under `modules/**/src/main` |
| `tempdoc:<NNN>#<heading substring>` | `docs/tempdocs/<NNN>-*.md` exists AND contains a markdown heading whose text contains `<heading substring>` |
| `none - <reason>` | always resolves, but raises the `adr-coverage/risk-no-instrument` warning. A bare `none` with no reason does not count as a reason. |

Rules, in the order the gate applies them:

- **`adr-coverage/risk-instrument-unresolved`** (error) — the reference did not resolve. The fix is
  to build the instrument or amend the risk row. It is never to delete the reference.
- **`adr-coverage/risk-no-instrument`** (warning) — the row names no instrument, or names `none`.
  A `none - <reason>` row stays visible on purpose: an unowned risk with nothing to check is
  exactly the note nobody reads.
- **`adr-coverage/risk-register-malformed`** (error) — this file exists but yields no parseable
  `## RISK-NNN:` section, or reuses an id. An absent file is skipped silently; a present but
  structurally broken one is not, because that would silently disable every instrument above.

Use the `tempdoc:` form — not `metric:` or `test:` — when the instrument does not exist yet and a
named lane is scheduled to build it. Naming an unbuilt metric as if it were built is the failure
this mechanism exists to end.

## RISK-001: Single-tenant GPU policy limits concurrent AI operations

**Category:** performance | **Status:** Accepted

**Trade-off:** Mutual exclusion between GPU workloads ensures VRAM safety on consumer 8GB GPUs. The Worker yields bulk embedding backfill whenever the Head has claimed the GPU for the generative model, so the two never contend for VRAM.

**Impact:** While Online Mode holds the GPU, GPU-side embedding backfill is paused, so enrichment progress stalls for the duration. With CPU-side embeddings the practical impact is minor (slight CPU contention).

**Reassess when:** Target GPU VRAM exceeds 16GB, or CUDA adds reliable cross-process VRAM reservation.

**Instrument:** `test:modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/LoopPacingPolicyTest.java#gpuYieldIsConflictOnly`

**Owner tempdoc:** none — no lane owns it; the mutual-exclusion mechanism is pinned by ADR-0004's premise probe `adr-0004-gpu-mutual-exclusion`.

**Last reviewed:** 2026-09-02

**Notes:** Reconciled 2026-09-07 (lane F stage A). [ADR-0004](../decisions/0004-single-tenant-gpu-policy.md) is now `status: superseded` — the GGUF/FFM embedding stack it framed the trade-off around was deleted in March 2026 — but the **mechanism** survives in a new form: lane F item A10 deleted the memory-mapped Head↔Worker signal bus (`MmfWorkerSignalLayoutV1` no longer exists) along with the Worker process itself (item A11), so `main_gpu_active` is no longer a cross-process byte. It is now the `mainGpuActive` field of the single in-process `GpuSchedulingGauge` (`modules/core/src/main/java/io/justsearch/core/scheduling/GpuSchedulingGauge.java`), written by the inference mode-change listener (`InferenceWiring.wireGpuStatusBroadcast`, `modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/InferenceWiring.java`) and read by `LoopPacingPolicy.shouldRunBackfill` (`modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/LoopPacingPolicy.java:53`) in the same JVM. The mutual-exclusion trade-off itself is unchanged — this is a mechanism update only, not a resolution.

## RISK-002: SQLite job queue write contention under high-throughput ingestion

**Category:** performance | **Status:** Monitoring

**Trade-off:** SQLite gives zero-dependency persistence and simplicity for the job queue, but has write contention under high-throughput bulk ingestion.

**Impact:** Ingestion throughput degrades with large file collections (10k+ files). WAL checkpoint stalls possible under sustained write pressure.

**Reassess when:** Ingestion benchmarks show >2x throughput regression vs. direct queue, or users report slow bulk imports exceeding 30 minutes for typical collections.

**Instrument:** `metric:worker.job_queue.dequeue_rate_per_min`

**Owner tempdoc:** tempdoc 885 item 21 (decision review, lane C).

**Last reviewed:** 2026-09-02

**Notes:** This row is about **write contention under load** — the runtime behaviour. The structural fact that the queue holds a single JDBC connection is [RISK-012](#risk-012-the-job-queue-runs-on-a-single-sqlite-connection); they are not duplicates, and fixing one does not fix the other.

The instrument shipped with tempdoc 885 item 21e. The 2026-03 trigger (">2x throughput regression", ">30 minutes bulk imports") was unmeasurable for the life of this row — the queue exposed only `worker.job_queue.depth`, and depth is a level: a queue draining at 2 docs/s and one draining at 200 read identically at the same depth. Four metrics now measure it, all in `WorkerOpsMetricCatalog`: `worker.job_queue.dequeue_rate_per_min` and `worker.job_queue.enqueue_rate_per_min` (trailing sixty one-second buckets, so two runs of different lengths stay comparable — which the trigger's ratio needs), plus `worker.job_queue.lock_wait_max_ms` and `worker.job_queue.lock_wait_avg_ms`, which say whether contention is *why* a rate is low.

Names corrected 2026-09-02: an earlier revision of this row promised `queue.dequeue_rate_per_min` and `queue.enqueue_rate_per_min`, without the namespace prefix. Those identifiers were unshippable — `WorkerOpsMetricCatalog.NAMESPACE` is `worker`, and the catalog's static initializer throws on any name that does not start with `worker.`, so the row named an instrument that could never exist. The shipped names carry the prefix and join the existing `worker.job_queue.*` family.

First field reading (2026-09-02, lane C live window): after a 30-file ingest, depth read `0` while both rates read `30`/min — the flush that shows why depth alone was never enough. Under an 822-file ingest: depth `313`, dequeue `560`/min. Lock wait measured `0 ms` at `1503` enqueues/min, which is the first evidence either way that the contention this row hypothesises is not present at that scale; a corpus large enough to contend is what would move this row off *Monitoring*.

## RISK-003: Manual FFM bindings require hand-maintenance on llama.cpp updates

**Category:** maintainability | **Status:** Resolved

**Trade-off:** Full control over Panama FFM struct layouts and function descriptors, at the cost of manual maintenance on each llama.cpp upgrade (~15-20 functions, complex structs).

**Impact:** Each llama.cpp version bump required manual verification of function signatures and struct layouts in `NativeLlamaBinding`. Missed changes caused silent memory corruption or crashes.

**Reassess when:** (Trigger retired.) Originally: jextract matures to handle llama.cpp's complex structs cleanly, or the API surface we use grows beyond ~30 functions.

**Instrument:** `none - the binding layer this risk describes was deleted; there is no target left to check. ADR-0005 carries the superseding record and the adr-coverage gate watches its status.`

**Owner tempdoc:** none — resolved; kept as history.

**Last reviewed:** 2026-09-02

**Notes:** Reconciled 2026-09-02 and resolved. [ADR-0005](../decisions/0005-manual-ffm-bindings.md) is `status: superseded`; the whole FFM/llama.cpp binding layer was deleted in March 2026, embeddings moved to ONNX Runtime, and the generative model runs as `llama-server.exe` over HTTP. `NativeLlamaBinding` does not exist on `main`. The maintenance burden this risk described cannot recur without a new decision, which would be a new ADR and a new risk id.

## RISK-004: Embedding model baked into index (no hot-swap)

**Category:** ai-quality | **Status:** Accepted

**Trade-off:** A single embedding model per index generation keeps the search pipeline simple, but upgrading to a better embedding model requires a full vector rebuild of all documents.

**Impact:** Model upgrades require complete vector reindexing, which takes hours for large collections. Users on older indexes miss out on improved retrieval quality.

**Reassess when:** A significantly better embedding model emerges that warrants the reindex cost, or the blue/green migration system supports incremental vector reindexing.

**Instrument:** `test:modules/indexer-worker/src/test/java/io/justsearch/indexerworker/embed/EmbeddingFingerprintDurabilityTest.java#stampedFingerprintSurvivesReopenAsCompatible`

**Owner tempdoc:** none — no lane owns it; the accepted trade-off stands.

**Last reviewed:** 2026-09-02

**Notes:** Reconciled 2026-09-02: the risk is unchanged, but it is no longer undetected. The commit metadata now carries an `embedding_model_sha256` fingerprint stamped by `EmbeddingMetadataOverlay`, `KnowledgeServer.java:568` compares it on open, `MigrationSource.EMBEDDING_MODEL_CHANGE` is a declared migration trigger, and `KnowledgeServerMigrationOps` verifies the green generation carries a matching fingerprint before promotion. The cost of a model change is therefore visible and gated rather than silent; the cost itself (a full vector rebuild) is what remains accepted. The blue/green schema migration ([explanation/11](../explanation/11-index-schema-migration.md)) handles the generation swap. Source files are the authority; the index is derived data.

## RISK-005: No user-facing backup/restore for the Lucene index

**Category:** operations | **Status:** Monitoring

**Trade-off:** No backup infrastructure keeps the system simple, but users can't recover from index corruption without a full reindex from source files.

**Impact:** Index corruption (power loss, disk error, failed migration) requires a full reindex. Reindex time scales with collection size and is unbounded.

**Reassess when:** Users report data loss from index corruption, or index sizes grow large enough that reindexing becomes impractical (>50GB index, >4 hours reindex time).

**Instrument:** `test:modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RecoveryIntegrationTest.java#corruptIndexAutoRecoveryProducesBackupAndFreshIndex`

**Owner tempdoc:** none — no lane owns it.

**Last reviewed:** 2026-09-02

**Notes:** Reconciled 2026-09-02 and narrowed. What now exists is **automatic backup-first corruption recovery**, not backup/restore: `RuntimeSession.java:434-467` detects a corrupt index, moves it aside via `SafeIndexPathOps.backupDirectory` (`SafeIndexPathOps.java:39`), and opens a fresh one, with the same path for a schema-mismatch rebuild. That bounds the blast radius of corruption but does not restore data — the reindex still has to run, and there is still no user-initiated snapshot, no `SnapshotDeletionPolicy` anywhere in the tree, and no restore path. Verified 2026-09-02. Status stays Monitoring; the residual risk is reindex time, not silent loss.

## RISK-006: ILM ↔ Ops lambda coupling prevents clean decomposition

**Category:** maintainability | **Status:** Mitigating — **trigger fired, not acted on**

**Trade-off:** The ILM→Ops decomposition moved method bodies into collaborator classes (`LlamaServerOps`, `OnlineModeOps`, `TokenEndpointOps`, `ServerPropsOps`) but retained all shared state, lock ownership, and mode transition logic in ILM. The constructor lambdas create a circular dependency: Ops classes read/write ILM state through callbacks, while ILM owns the lock guarding that state. This keeps the code working but makes the class boundaries misleading — they suggest a separation of concerns that doesn't exist.

**Impact:** Modifying crash recovery or mode transitions requires tracing lambda flows across ILM + LlamaServerOps. A future attempt to extract a "ProcessOwner" class hits the lock ownership problem: the lock guards both process state AND mode state, so it can't move to either class without splitting responsibilities that are currently atomic.

**Reassess when:** ILM grows beyond 1000 lines again, or a new feature (multi-model, remote inference) requires a cleaner component boundary between process management and mode management.

**Instrument:** `none - the trigger is a line count and nothing measures it; no lane owns the decomposition. This row must gain a gate: or test: instrument when one is built.`

**Owner tempdoc:** none — the 2026-03 review's "decompose before the next feature" is unowned; no decision-review lane picked it up.

**Last reviewed:** 2026-09-02

**Notes:** **Reconciled 2026-09-02: this trigger has fired and has not been acted on.** `modules/app-inference/src/main/java/io/justsearch/app/inference/InferenceLifecycleManager.java` is **1,357 lines** today (`wc -l`, 2026-09-02). It was **1,053** lines when the 2026-03 review wrote this row and set the action to "decompose before the next feature" — already over the 1,000-line trigger it names, and 304 lines larger six months later. Nothing decomposed it in between, and nothing was watching: the trigger lived in a document that was deleted a week after it was written. Recording that plainly is the whole reason this register is back.

Partially mitigated by the `ModeStateMachine` extraction (2026-02-10), which formalized 20 raw `currentMode` assignments into validated operations. The forwarding tax (pure delegation methods in ILM) remains but is harmless.

## RISK-007: Agent infrastructure maturity gaps

**Category:** ai-quality | **Status:** Monitoring

**Trade-off:** JustSearch's agent system is local-first and privacy-preserving (a deliberate strength), but this means it lacks features available in managed cloud agent platforms (layered context management, model routing).

**Impact:** Quality ceiling for complex multi-step queries. No fallback model routing when the primary model fails. Context compression exists but its token-savings gate is not yet met.

**Reassess when:** The context compression A/B gate passes, or a second model is available for routing.

**Instrument:** `test:modules/app-agent/src/test/java/io/justsearch/agent/AgentLoopServiceTest.java#multiAgent_handoffToolsArePerActiveAgent`

**Owner tempdoc:** none — the remaining gaps (context lifecycle, model routing) are unowned. The window half of the context-lifecycle gap is tracked separately as [RISK-009](#risk-009-the-llm-context-window-is-unmanaged).

**Last reviewed:** 2026-09-02

**Notes:** **Reconciled 2026-09-02: the multi-agent half of this row has shipped.** The 2026-03 gap table rated "Multi-agent/handoffs" as **High — "single-agent loop only; no handoff runtime"** — and set the trigger to "multi-agent handoff work begins". Both are stale: `modules/app-agent/src/main/java/io/justsearch/agent/AgentHandoff.java` builds `handoff_to_*` tools, recognizes and parses handoff calls, resolves profiles, and prunes the outgoing agent's exploration history into a research brief, with ten handoff scenarios covered in `AgentLoopServiceTest`. The trigger fired and the work landed; the row is narrowed above to the gaps that are actually still open. The gap table below is dropped rather than restated, because a hand-maintained maturity matrix is precisely the kind of prose no instrument can check.

See [Agent System Architecture](../explanation/22-agent-system-architecture.md) for the full system design.

## RISK-008: Production JVMs missing `--enable-native-access`

**Category:** reliability | **Status:** Resolved

**Trade-off:** The Worker and inference JVMs use Panama FFM downcalls (NVML, Windows job objects, MMF signalling). Java 24+ makes restricted native access a warning today and an error in a future release; running without `--enable-native-access` traded a clean argv for a scheduled hard failure.

**Impact:** Had it not been fixed, a JDK upgrade would have turned a startup warning into an `IllegalCallerException` at the first native downcall — Worker spawn failing at runtime, on end-user machines, with no local reproduction until the same JDK shipped.

**Reassess when:** A new production JVM spawn site is added, or the JDK changes the default for restricted native access again.

**Instrument:** none — see the note below. The Worker-spawn instrument, `WorkerSpawnerJvmFlagsTest#argvEnablesNativeAccessWithNoAddOpensOrIncubatorVector`, was deleted with `WorkerSpawner` at lane F stage A item A11. The surviving spawn site that this risk applies to is the extraction sandbox child, covered by `test:modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionSandboxCommandTest.java`.

**Owner tempdoc:** tempdoc 882 item 4 (decision review, lane 0) — shipped. Re-instrumenting the remaining JVM spawn sites belongs to tempdoc 936 lane F (item A13, where the spawn paths are collapsed).

**Last reviewed:** 2026-09-07 (lane F stage A sweep: the Worker is no longer a spawned JVM, so this row now covers only the inference and extraction-sandbox spawn sites).

**Notes:** Closed by decision-review lane 0. Both production spawn sites now pass `--enable-native-access=ALL-UNNAMED`; the pre-Lucene-10 `--add-opens java.base/java.nio` line is gone (Lucene 10 no longer needs it); and the argv is pinned by the named test, which asserts the flag is present and that no `--add-opens` or `jdk.incubator.vector` argument survives. Kept as history because the instrument is what stops a new spawn site from re-opening it.

## RISK-009: The LLM context window is unmanaged

**Category:** ai-quality | **Status:** Monitoring

**Trade-off:** The chat pipeline assembles prompts (system prompt, retrieved passages, conversation history, tool results) without a single authority that reconciles their total size against the model's actual context window. Skipping that authority kept every producer independent and cheap to change.

**Impact:** With no budget owner, the failure mode is silent: the server truncates or the model degrades, and the surface that over-spent its share is not identifiable after the fact. Constants sized against an assumed window drift out of agreement with the window the runtime actually reports.

**Reassess when:** Lane A's context-budget work lands, or a model with a materially different context window becomes the default.

**Instrument:** `tempdoc:883#Item 8 — the window`

**Owner tempdoc:** tempdoc 883 item 8 (decision review, lane A).

**Last reviewed:** 2026-09-02

**Notes:** New row, 2026-09-02, opened by the decision review. Lane A owns the measurement and the design; it also owns the neighbouring item on constants sized against the window. The instrument is a `tempdoc:` reference because the budget authority does not exist yet — when it ships, this row's instrument becomes the test that pins it, and lane A closing without one is exactly what the unresolved-instrument rule would surface.

## RISK-010: The extraction sandbox is unreachable

**Category:** reliability | **Status:** Monitoring

**Trade-off:** Content extraction runs in-process behind a timeout, which is fast and simple. The out-of-process sandbox exists in the tree but has no shipped argv, so the default is `in_process` and the isolation is not reachable in production.

**Impact:** A wedged native parser (PDFBox/POI) ignores the interrupt, so `future.cancel(true)` does not free the thread. Extraction runs on a single-thread executor, so one wedged file stops **all** extraction until the Worker restarts — a whole-subsystem stall from one malformed document.

**Reassess when:** A wedged-parser stall is observed in the field, or lane C ships the persistent sandbox child.

**Instrument:** `tempdoc:885#Item 14 — extraction`

**Owner tempdoc:** tempdoc 885 item 14 (decision review, lane C).

**Last reviewed:** 2026-09-02

**Notes:** New row, 2026-09-02, opened by the decision review. The child entry point and the process sandbox both exist; what is missing is a shipped command, and the per-file JVM start is why nobody enabled it. Lane C's evidence also recorded that a nested Windows job object was blocked on a module-boundary change (`WindowsJobObject` lived in `app-util`, which `worker-services` does not depend on). That obstacle is now different in kind rather than resolved: lane F stage A item A11 **deleted** `WindowsJobObject` outright, because its only caller was `WorkerSpawner` and a containment helper with no child to contain is residue. Anything that wants nested job objects for the sandbox child must restore it from history first.

A second, independent obstacle was measured on 2026-09-02 while running the full suite for tempdoc 884 PR 2, and it is worth recording because it is not the one the row was opened for: **all six `ProcessExtractionSandboxTest` cases fail inside a deep worktree path** with `java.io.IOException: Cannot run program java.exe: CreateProcess error=206, The filename or extension is too long`. The sandbox passes the whole Worker classpath on the child's command line, so every entry inherits the checkout prefix; under `.claude/worktrees/<name>/...` the command line crosses the Windows 32k limit. It is not load-dependent (it reproduces isolated) and is expected to pass in the shorter main checkout, which is why it has not surfaced before. So the sandbox is unreachable for a second reason beyond the missing argv: as currently invoked it cannot start at all on a long path. Any fix that ships an argv must also shorten the child's command line (an argfile or a pathing jar). It was pinned meanwhile as `process-extraction-sandbox-classpath-too-long`; tempdoc 930 retired the expected-state pin mechanism, so this paragraph is now the record.

## RISK-011: The reindex mechanism has a single honest detector

**Category:** reliability | **Status:** Monitoring

**Trade-off:** Deciding whether an existing index must be rebuilt rests on a narrow set of stamped fingerprints compared at open time. That keeps the check cheap and unambiguous where it applies, at the cost of anything the fingerprints do not cover changing silently.

**Impact:** A change that alters derived index content without moving a stamped fingerprint leaves stale documents in a live index with no signal. The failure is silent and only shows up as degraded results.

**Reassess when:** A rebuild-worthy change ships without a corresponding detector, or a fingerprint input is added without a test that moves it.

**Instrument:** `tempdoc:915#C Design (Phase 1), tightened`

**Owner tempdoc:** 915 (decision-review lane D).

**Last reviewed:** 2026-09-03

**Notes:** New row, 2026-09-02, opened by the decision review; instrumented 2026-09-03 when lane D filed tempdoc 915. Partly addressed by 915 Phase 1: the five parity keys became one `index_fingerprint` over the effective physical index shape, the Head stopped disabling the guard unconditionally, and `CatalogPhysicalProjectionTest` / `IndexFingerprintTest` pin both directions (every physical input moves it; annotations do not). The risk stays open rather than closed because it is about *coverage*, and coverage is still a judgment call: the fingerprint covers the inputs 915 §C enumerates, and a future change that alters derived content through some other path would still be silent. Phases 2-3 of 915 (document identity, the reindex bundle) extend what it covers.

## RISK-012: The job queue runs on a single SQLite connection

**Category:** reliability | **Status:** Monitoring

**Trade-off:** `SqliteJobQueue` holds one JDBC `Connection` guarded by one `ReentrantLock`, which makes correctness easy to reason about and makes the SQLite update/commit hooks (which back the live job stream) trivially consistent. In exchange, every enqueue and dequeue serializes through one lock on one connection.

**Impact:** Structural, not load-dependent: the single connection is a single point of failure and a hard serialization point. One slow statement blocks every other queue caller, and a connection-level fault takes the whole queue down rather than one caller's work.

**Reassess when:** Lane C's job-queue work lands, or a queue stall is traced to lock hold time rather than to SQLite write contention.

**Instrument:** `tempdoc:885#Item 21 — job queue`

**Owner tempdoc:** tempdoc 885 item 21 (decision review, lane C).

**Last reviewed:** 2026-09-02

**Notes:** New row, 2026-09-02. **Distinct from [RISK-002](#risk-002-sqlite-job-queue-write-contention-under-high-throughput-ingestion):** that row is about SQLite *write contention under load* (a throughput property, measured by a metric that does not exist yet); this row is about the *structural single-connection design* (a shape property, true at zero load). A throughput metric would not detect this one, and moving off SQLite would not by itself fix that one.

Verified on `main` 2026-09-02: `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/queue/SqliteJobQueue.java` declares a single `private Connection connection` field and a single `private final ReentrantLock lock`, with WAL and a 5 s busy timeout. One dequeue caller (the indexing loop) and several enqueue callers on other threads share it.

## RISK-013: Single-writer NRT/commit cadence is hardcoded and silently reconfigured mid-run

**Category:** performance | **Status:** Monitoring — **contains a live defect**

**Trade-off:** One Lucene writer with a near-real-time reopen thread gives simple write coordination and immediate visibility of new documents, at the cost of reopen work competing with indexing throughput. The reopen and commit cadences were fixed constants chosen at the root commit and never revisited.

**Impact:** The cadence is not merely unmeasured — it is inconsistent. The searcher becomes stale at one rate before the first bulk backfill and at a different, configured rate afterwards, so a throughput or latency measurement taken on one side of a backfill does not describe the other.

**Reassess when:** Lane C's cadence measurement lands, or a search-latency regression is traced to reopen or commit timing.

**Instrument:** `tempdoc:885#Item 19 — cadence`

**Owner tempdoc:** tempdoc 885 item 19 (decision review, lane C).

**Last reviewed:** 2026-09-02

**Notes:** New row, 2026-09-02. **The live defect is verified on `main` (2026-09-02), not inferred:** `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/ComponentsFactory.java:324` constructs `new ControlledRealTimeReopenThread<>(w, mgr, 0.5, 0.05)` with literal seconds, while `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/CommitOps.java:274-283` (`resumeNrtRefresh`) rebuilds the same thread from `session.nrtTargetMaxStaleMs / 1000.0` and `session.nrtHardMaxStaleMs / 1000.0` after every bulk-backfill suspend/resume. When `index.nrt.*` is configured to anything other than the defaults, the cadence therefore changes at the first backfill and never changes back. `CommitOps.java:269` even documents the rebuild as using "the same parameters as the original", which is true only for the default values. `RuntimeSession.java:103-105` documents the coupling by comment ("default 50L must match the hardcoded 0.05s") — a comment is not a mechanism, which is why this is a defect and not a convention.

## Resolved

Resolved rows stay in place above so their instruments keep being checked; this index lists them.

| ID | Title | Resolved |
|----|-------|----------|
| [RISK-003](#risk-003-manual-ffm-bindings-require-hand-maintenance-on-llamacpp-updates) | Manual FFM bindings require hand-maintenance on llama.cpp updates | 2026-09-02 — the binding layer was deleted in March 2026; ADR-0005 superseded. |
| [RISK-008](#risk-008-production-jvms-missing---enable-native-access) | Production JVMs missing `--enable-native-access` | 2026-09-02 — both spawn sites pass the flag; argv pinned by a named test. |
