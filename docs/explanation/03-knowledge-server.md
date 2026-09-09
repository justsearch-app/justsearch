---
title: Knowledge Server
type: explanation
status: stable
description: "Formatting logic, JobQueue, and Tika usage."
---

# Knowledge Server

The **Knowledge Server** (`modules/indexer-worker`) is the heavy-lifting "Body" of JustSearch. It is composed in-process by the merged Engine JVM (lane F stage A) — there is no separate Worker process, so nothing spawns or manages it as a child.

Their primary responsibility is to convert a chaotic filesystem into a structured Lucene index.

## Core Loop (`IndexingLoop`)

The `IndexingLoop.java` class is an infinite loop that processes files one by one (or in small batches).

### The Pipeline
1.  **Pace:** After each batch (and each extracted file), `IndexingPacing.pace()` yields proportionally while Engine knowledge-port work has foreground urgency, holding indexing at the configured minimum duty instead of pausing it.
2.  **Poll Job:** Takes the next `PENDING` job from `SqliteJobQueue`.
3.  **Validate:** Checks `Files.exists()` and `Files.isReadable()`.
4.  **Check Modified:** Compares `Files.getLastModifiedTime()` against the Lucene index. If unchanged, mark `DONE` and skip.
5.  **Extract:** Passes file to `TimeboxedContentExtractor` (wraps `ContentExtractor` / Tika) to prevent pathological files from hanging the loop; timeouts fail the job with `EXTRACTION_TIMEOUT`.
6.  **Embed (Deferred):** During primary indexing, embedding is deferred — documents get `EMBEDDING_STATUS=PENDING`. After the queue drains, `EmbeddingBackfillOps` batch-embeds via ORT (EmbeddingGemma-300M by default). If `WorkerSignalBus.isMainGpuActive()` is true, the Worker skips/unloads GPU embedding work to avoid VRAM contention with Online mode.
7.  **Index:** Writes `IndexDocument` via the write-path ops (`WritePathOps`/`CommitOps`), including canonical metadata fields used by the UI:
    - `mime_base`: normalized MIME without parameters (e.g., strip `; charset=` from Tika `mime`)
    - `file_kind`: UX-friendly type bucket (e.g., `pdf|markdown|image|code|text|office|archive|binary|unknown`)
    - `content_preview`: small stored snippet source (first ~4KB) for the results list
    - `language`: lightweight heuristic (script-based; fast/no deps)
8.  **Commit:** Every 10 seconds or 1000 documents.

## File skip lists

The Worker applies hardcoded skip rules at two stages, independent of user-configured exclude patterns.

### Directory traversal skips (`WALK_SKIP_DIRS` in `SyncDirectoryOps.java`)

During `Files.walkFileTree`, these directories trigger `SKIP_SUBTREE` (name compared lowercase):

`$recycle.bin`, `system volume information`, `.git`, `.svn`, `.hg`, `.bzr`, `cvs`, `node_modules`, `bower_components`, `__pycache__`, `.tox`, `.pytest_cache`, `.mypy_cache`

### Per-file skips (`shouldSkip()` in `IndexingLoop.java`)

Before processing, each file is checked against:

1. **Hidden files** — names starting with `.` (except `.env`, `.gitignore`)
2. **Extension skip list** (`SKIP_EXTENSIONS`): `pyc`, `pyo`, `class`, `o`, `obj`
3. **Name-contains patterns** (`SKIP_PATTERNS`): system/temp files like `thumbs.db`, `desktop.ini`
4. **Binary content detection** — files detected as binary by content inspection are skipped

### Design rationale

Only unambiguously tool-generated patterns are hardcoded. Context-dependent directories (`build/`, `dist/`, `vendor/`, `.cache`, `.venv`) are left to user-configurable exclude patterns (see `docs/explanation/06-configuration-ssot.md` § "Exclude patterns").

## The Job Queue (`SqliteJobQueue`)

We use **SQLite** as a persistent Job Queue (`jobs.db`, stored under the Worker `dataDir`).
*   **Why SQLite?** It survives crashes. An in-memory queue would lose thousands of pending files if the worker was killed by the "Suicide Pact."
*   **Schema (conceptual):** jobs are durable rows with a `state` machine and retry/backoff metadata.
*   **States:** `PENDING`, `PROCESSING`, `DONE`, `FAILED`, `RETRY_EXHAUSTED`. The head-side vocabulary is the `IndexingJobView.STATE_*` constants; `FAILED` and `RETRY_EXHAUSTED` are both terminal and both counted as failures by every projection (failure summary, per-folder counts, scan rollup, the FE task rail).

### The failure ladder

Which terminal state a job reaches is decided by its **failure class**, not by its attempt count. Every failure carries a typed `IngestionOutcome` whose `IngestionRetryPolicy` is one of `NONE`, `RETRY_WITH_BACKOFF` or `DEFER_WITHOUT_ATTEMPT`, and `SqliteJobQueue.markFailed` branches on it:

| Retry policy | Example outcome | Behaviour |
|---|---|---|
| `NONE` | `PARSER_FAILED`, `BUDGET_EXCEEDED` | Terminal `FAILED` on the **first** failure. The file cannot be parsed; retrying it changes nothing. |
| `RETRY_WITH_BACKOFF` | `IO_FAILED`, `PARSER_TIMEOUT`, `SANDBOX_FAILED` | Returns to `PENDING` with `retry_after` set, on the ladder below, **without** the attempts cap applying. After 7 days of continuous failure the job becomes terminal `RETRY_EXHAUSTED`. |
| `DEFER_WITHOUT_ATTEMPT` | cloud placeholder, `WRITE_UNAVAILABLE_DRAINING` | Routed through `defer(...)`, not `markFailed(...)`; no attempt is consumed. |

*   **Backoff ladder** (`IngestionRetryLadder`): 1 min → 10 min → 1 h → 6 h → 24 h, with the 24 h step repeating, plus capped additive jitter (`[0, min(1s, backoff)]`) to prevent synchronized retry bursts.
*   **The bound:** the ladder is bounded at **7 days from the first failure of the current failure run**, recorded in the `first_failed_at` column (schema V10). No retry is ever scheduled past that boundary — the ladder's next-retry time is clamped to it. The failure that occurs at or after the boundary transitions the job to `RETRY_EXHAUSTED`.
*   **Resetting an exhausted job:** anything that re-enqueues the path clears the row. The enqueue statement is `INSERT OR REPLACE`, so a rescan of the containing root, or a watcher event on an mtime/size change, restores `state=PENDING`, `attempts=0`, `retry_after=NULL` and `first_failed_at=NULL`, and the retry window starts again from the next failure. `RetryIndexingJob` re-enqueues by the same path.
*   **Attempts cap:** `maxAttempts` (one home: `SqliteJobQueue.DEFAULT_MAX_ATTEMPTS = 3`) still governs the **untyped** `markFailed(path, message)` path, which has no outcome class to classify on. It does not apply to a classified transient failure — a network share unreachable for twenty minutes must not become permanently `FAILED`.
*   **`error_message`** holds the exception's own text (class name plus message, collapsed to one line and truncated at 512 chars by `IngestionOutcome`), not a fixed literal. For a sandbox failure that text carries the child process's exit code.

### Concurrency & configuration

*   **Single-Writer model:** `SqliteJobQueue` uses a single JDBC `Connection` and serializes all access via a `ReentrantLock`. This avoids `SQLITE_BUSY` errors and aligns with SQLite best practices.
*   **Pragmas:** Configured with `journal_mode = WAL`, `synchronous = NORMAL`, and `busy_timeout = 5000`. WAL mode provides good crash resilience for a job queue (process crashes are safe; OS crashes may lose the last transaction, but `recoverStuckJobs()` heals on next boot).

### Ingest guardrails (backpressure)

The ingest port surface (`IngestServiceCalls`) enforces caps before the queue even sees data:

*   `submitBatch` rejects batches larger than **10,000** paths (`MAX_BATCH_SIZE`).
*   `submitBatch` rejects submissions when `queueDepth >= 100,000` (`MAX_QUEUE_DEPTH`) with `RESOURCE_EXHAUSTED`. Callers should retry later.

### Crash recovery

On startup, `recoverStuckJobs()` resets all `PROCESSING` jobs back to `PENDING`. This heals incomplete work from a prior crash without burning retry budget (since `attempts` = failures, not claims).

The indexing loop is also resilient *in-process*: a per-document `Error` (for example a plugin `LinkageError`, `AssertionError`, or `IOError`) is logged and the loop continues to the next batch. A fatal `VirtualMachineError` or uncaught loop-thread failure publishes `LoopState.FAILED` and clears liveness before logging. Core status reports `indexState=FAILED` and `indexHealthy=false`; the index health port exposes the failed loop state while serving readiness remains independently probed. Ordinary document `ERROR`, deferred startup, and intentional quiescence remain distinct. A new loop start clears the fatal state.

KnowledgeServer also runs an age-bounded reaper every two minutes. It requeues `PROCESSING` rows whose last update exceeds the five-minute liveness window; actively processed jobs refresh that timestamp through heartbeats. This can recover orphaned rows without a process restart, but does not itself restart a failed indexing loop.

The reaper owns the registered background scheduler `index.stuck-job-reaper`. Shutdown cancels the periodic task and waits for its actual exit before closing the job queue. Deferred model initialization similarly owns `index.deferred-model-init`; shutdown waits for its executor to terminate before closing published model and runtime resources, even when the initializer's exposed future has been canceled. Both registrations have one thread and one live instance, with queue capacity supplied by the Engine background policy.

### Schema versioning & migrations

The job queue uses `PRAGMA user_version` for linear schema evolution:

*   **Version tracking:** SQLite's native `user_version` header field stores the current schema version.
*   **Migration ladder:** On open, the queue applies pending migrations sequentially (V0→V1→V2→...) inside an explicit transaction.
*   **Fail-fast:** If a migration fails, the transaction rolls back and the queue throws a fatal exception.
*   **DDL SSOT:** All DDL and migration SQL is centralized in `SqliteSchema`. Migration orchestration (version ladder, transaction management, rollback) lives in `SqliteQueueMigrationOps`.

### Pre-migration backups

Before schema migrations (or when triage is needed), the queue creates a backup:

*   **Mechanism:** `VACUUM INTO` creates a consistent snapshot to `jobs.db.bak.tmp`, then atomically replaces `jobs.db.bak`.
*   **WAL safety:** Unlike raw file copy, `VACUUM INTO` handles WAL mode correctly (no need to reason about `-wal`/`-shm` sidecars).
*   **Guard:** Backups are only created when `jobs.db` existed before the current open (not for fresh installs).

### Startup integrity check (Triage pattern)

On startup, if the database already exists, the queue runs `PRAGMA quick_check`:

*   If all rows return `ok`, the database is healthy.
*   If corruption is detected, the queue throws `SQLITE_CORRUPT`.
*   `KnowledgeServer` catches this and initiates **triage**: quarantine the corrupt files (`{db, -wal}.corrupt`), restore from `jobs.db.bak` if available, and re-open.

**Post-restore validation:** After triage restores from backup, `KnowledgeServer` calls `jobQueue.openWithIntegrityCheck()` instead of plain `open()`. This forces `PRAGMA quick_check` even though the restored database appears "new" to the open logic (the `existedBeforeOpen` flag would be false for a freshly restored file). The `forceIntegrityCheck` flag in `SqliteJobQueue` bypasses this skip, ensuring backup integrity is validated before the queue resumes processing.

### Atomic job claiming

`pollPending()` uses a single-statement atomic claim to prevent burning attempts on crashes:

```sql
UPDATE jobs SET state = 'PROCESSING', last_updated = :now
WHERE path IN (SELECT path FROM jobs WHERE state = 'PENDING' AND ...)
RETURNING path;
```

### Attempt semantics

`attempts` represents **failures**, not claims:

*   Claiming a job (`pollPending`) does **not** increment `attempts`.
*   Only `markFailed()` increments `attempts` and schedules `retry_after`.
*   `recoverStuckJobs()` (crash recovery) resets `PROCESSING` → `PENDING` without mutating `attempts`.

This ensures transient crashes don't burn retry budget.

`attempts` is a **display** fact — how many times this file has been tried. It is not the terminal signal for a classified transient failure (see the failure ladder above); the seven-day window measured from `first_failed_at` is.

### Retention & bloat

`markDone` transitions jobs to `DONE` but does not delete them. A batch variant `markDoneBatch(Collection<Path>)` executes a single `UPDATE ... WHERE path IN (?, ...)` with chunking at 499 params (SQLite limit), replacing per-path individual UPDATEs at commit boundaries (tempdoc 312 item 8). A `cleanupOldJobs(retentionDays)` method exists but is not currently scheduled, so `jobs.db` can grow over time on long-running installs.

**Incremental auto-vacuum:** `PRAGMA auto_vacuum = 2` (INCREMENTAL) is set in the `open()` PRAGMA block, before `CREATE TABLE`. This enables SQLite to reclaim freelist pages without a full `VACUUM`.

**Waste monitoring:** `checkAndVacuum()` queries `page_count * page_size` and `freelist_count * page_size`. When waste ratio exceeds 25%, runs `PRAGMA incremental_vacuum(500)` (~2MB reclaimed per invocation). Called after `cleanupOldJobs()` when rows are deleted.

**Existing DB limitation:** `PRAGMA auto_vacuum = 2` on a database created with `auto_vacuum = 0` has no effect until a full `VACUUM`. New installations get incremental vacuum immediately; existing installations benefit from waste monitoring but won't reclaim freelist pages until a schema migration (V5) or manual `VACUUM`.

### Ingestion Ledger Privacy Contract (tempdoc 410 §8 + Slice E + Slice G.4)

The Worker writes an `ingestion_ledger` audit row for typed ingestion outcomes (skip, success, failure, defer). Operators read these rows via `GET /api/diagnostics/ingestion/{recent,summary}` and the in-process `RecentIngestionEvents` / `IngestionOutcomeSummary` calls. Both surfaces marshal `JobQueue.IngestionEventView` records — never the raw queue row.

Schema V14 adds nullable `originator` and `transport` to both `jobs` and `ingestion_ledger`.
The Engine bridge derives originator through the existing action-ledger projection and passes
that value with the caller's Engine context. Batch submission and root scans persist it at
admission. The atomic claim snapshots that attribution into the extraction/write job; terminal
ledger writes use the snapshot, including an explicitly unknown legacy origin. A later admission
of the same path cannot relabel an already claimed outcome. Contextless queue maintenance can
recover attribution from the durable row. Maintenance re-enqueue preserves prior attribution; a new
explicit admission replaces it. Legacy rows remain null. Watcher events identify the internal
producer, and cloud-placeholder observations carry the observing scan's attribution. These
database columns do not widen the export view described below.

**Invariant:** any operator-visible export of ledger or queue data carries a `path_hash` (SHA-256 over the normalized absolute path), never the raw path, and never any path-derived field that could reverse-map to the user's filesystem.

#### Path normalisation spec

`path_hash` is computed as `sha256_hex(PathNormalizer.normalizePath(path.toAbsolutePath().toString()))`.

- `path.toAbsolutePath()` resolves to the JVM's working-directory-anchored absolute form before normalisation. Symlinks are NOT followed (matches the `LinkOption.NOFOLLOW_LINKS` posture used elsewhere in the admission boundary).
- `PathNormalizer.normalizePath` (`modules/worker-services/src/main/java/io/justsearch/indexerworker/util/PathNormalizer.java`) replaces every `/` with the platform-native separator (`File.separatorChar`) — on Windows that produces backslash-form paths like `c:\users\<user>\…\file.txt`; on Linux/macOS the path is left as-is. Case folding fires on case-insensitive filesystems (Windows): the normalizer lowercases the absolute path so the same file produces the same hash regardless of how the operator typed the case.
- The hex form is **lowercase 64-char SHA-256**. Operators correlating events to files should match on the full 64 characters; substring matching breaks the privacy property because partial hashes can be brute-forced against a known directory layout.
- The canonical helper lives at `CloudPlaceholderRecorder.sha256Hex` (`modules/worker-services/src/main/java/io/justsearch/indexerworker/services/CloudPlaceholderRecorder.java`, package-private static). Workers writing new ledger entries should reuse it; rolling a private SHA-256 helper risks producing inconsistent normalisation that breaks the operator-side correlation pattern below.

#### In-scope record fields

(`JobQueue.IngestionEventView`, the export wire shape — 14 fields as of 2026-04-25):

- `id`, `observedAtMs` — opaque event identity + timestamp.
- `pathHash` — SHA-256 hex over the normalized absolute path. The only path-derived field allowed.
- `collection` — operator-visible collection tag; never carries the path.
- `outcomeClass`, `reasonCode`, `retryPolicy` — typed outcome triple from `IngestionOutcomeClass` / `IngestionReasonCodes` / `IngestionRetryPolicy`.
- `diagnosticSummary` — sanitized free-form summary, capped at `LEDGER_ENTRY_MAX_FIELD_CHARS` (256). Operators producing this string must not embed raw paths or extracted text.
- `sourceSizeBytes`, `sourceModifiedAtMs`, `sourceKind` — file metadata captured at ingestion. None of these are reversible to the path.
- `artifactStatus`, `policyId`, `parserId` — extraction provenance (matches `ExtractionStatus`, `TikaExtractionPolicy.policyId()`, parser identifier).

#### Path-derivable vs. not-path-derivable — concrete examples

When deciding whether a new field belongs on `IngestionEventView`, ask: "could a sufficiently motivated operator with knowledge of the user's filesystem layout reverse-engineer the path from this field, alone or in combination with other fields already in the export?"

**Forbidden (path-derivable):**
- Raw path strings of any kind: absolute, relative, basename, parent directory.
- File extensions when combined with `sourceSizeBytes` + `sourceModifiedAtMs` (the triple is enough to fingerprint a specific file inside a known root).
- Hashed-but-unsalted partial paths (e.g., a hash of just the parent directory) — partial hashes are brute-forceable against a candidate directory listing.
- Filename character counts (in combination with size + mtime).
- Originating watcher root identity if the root path is derivable from an operator-visible setting.

**Allowed (not path-derivable):**
- Outcome classes, reason codes, retry policies — pure enum values.
- `policyId` / `parserId` — fixed identifiers shared across the install.
- `sourceSizeBytes`, `sourceModifiedAtMs` — file metadata. Alone these don't identify a path; the privacy property assumes they're not paired with path-derivable signals.
- `sourceKind` — the typed source class (e.g., `CLOUD_PLACEHOLDER`, `REGULAR_FILE`).
- `diagnosticSummary` strings that don't embed paths or extracted content (e.g., `"Indexed successfully"`, `"Cloud-only placeholder; reading would hydrate over network"`).

**Rule for adding a field:** any new component on `IngestionEventView` or `IngestionLedgerEntry` must either (a) carry no path-derivable information per the examples above, or (b) be opted out of operator-visible exports via a documented mechanism (e.g., a separate internal projection that the gRPC layer never marshals — none currently exist; see Slice G.4 plan). The `ingestionEventViewExportContractIsPinned` test in `JobQueueTest` pins the exact 14-field set so accidental additions break the build with an actionable diff.

#### Operator query pattern

`path_hash` is one-way. To correlate a flagged event back to a specific file, operators hash candidate paths themselves:

```text
sha256_hex(PathNormalizer.normalizePath(candidatePath.toAbsolutePath().toString()))
```

and compare to the event's `pathHash` field. **There is no reverse lookup *in any export path*.** The scoped resolver at `POST /api/library/resolve-hash` is the only exception, governed by [ADR-0028](../decisions/0028-scoped-reverse-path-lookup.md).

#### Scoped reverse-lookup exemption (ADR-0028)

The local UI's "show filename" affordance in the Library Indexing Activity panel needs to answer "which file is this hash?" for files still under a watched root. ADR-0028 refines the contract to permit exactly that — and only that — via a single, deliberately-narrow surface:

- **One backing table.** `path_resolution(path_hash, normalized_path, last_seen_at, removed_at)` lives in `jobs.db` alongside the ingestion ledger. It is populated on every successful or partial admission via the `IndexingLoop.pathResolutionStore` recorder seam.
- **One port call.** `lookupPathByHash(pathHash) → Optional<Path>` returns the resolution if the file is still under a watched root and within retention; returns `found=false` otherwise (path was removed and retention expired, root was unwatched, or hash was never seen).
- **One HTTP endpoint.** `POST /api/library/resolve-hash` is the only HTTP caller of the resolver port call. The diagnostic export endpoints (`/api/diagnostics/ingestion/recent`, `/api/diagnostics/ingestion/summary`, and any future `/api/diagnostics/export`) **must not** call it.
- **Mechanical enforcement.** The ArchUnit pin `LibraryResolveHashOnlyCallerPin` (in `modules/app-launcher`) asserts that no class in the diagnostic export call tree depends on `PathResolutionStore`. Adding a new caller requires adding it to the pin's `APPROVED_CALLERS` set with a written reason — the pin's job is to make every expansion a deliberate, reviewed action.
- **Lifecycle.** Observed file deletions mark `removed_at = now`; rows are pruned after `JUSTSEARCH_PATH_RESOLUTION_RETENTION_DAYS` (default 90). Unwatching a watched root prunes everything under that prefix immediately. Existing ledger entries from before V7 migration return `found=false` until they are re-resolved by a future scan.

The structural pin `ingestionEventViewExportContractIsPinned` is unchanged — `IngestionEventView` still has 14 fields, and the `path_resolution` table is never marshaled into it. The contract refinement does not change what gets exported; it only adds a separate, scoped path for in-process display on direct user action.

### Durable cutover buffer (`switch_buffer`)

During schema migration cutover (`SWITCHING` state), the Worker durably buffers mutating ingest operations into `jobs.db.switch_buffer` (rather than relying on UI/client retries). On restart after cutover, the Worker replays buffered ops against the new generation.

Buffered UPSERT payload version 1 retains the absolute path, collection and admission provenance.
Replay also accepts pre-C1 raw path payloads with unknown collection/provenance. Unknown versions
or refused enqueues leave the durable buffer available for retry instead of acknowledging a loss.
SYNC_ROOT version 1 carries root, force and paired nullable originator/transport fields. Explicit
sync admissions retain their caller; INTERNAL/SYSTEM_INTERNAL maintenance preserves existing job
attribution. Replay executes internally while retaining the separately persisted admission value.
Legacy unversioned root/force payloads remain readable with unknown attribution. Missing versioned
fields or provenance attached to an unversioned payload are malformed and remain buffered.
The typed sync-buffer admission coalesces under the existing queue lock. A later maintenance
sync retains earlier buffered caller attribution while applying its incoming root/force; a new
explicit admission replaces it. Unreadable prior work refuses maintenance replacement. The shared
SYNC_ROOT codec owns encoding and decoding for admission, coalescing and replay.

This is the core correctness mechanism that prevents lost updates during blue/green pointer swaps.

**Fail-closed semantics:** Buffering is part of the write path. If `putSwitchBuffer()` fails (SQL error), the ingest port calls fail with `UNAVAILABLE` (retryable) instead of ACKing the operation. This prevents "ACK without durability" during cutover. Switch buffer SQL operations are implemented in `SqliteQueueSwitchBufferOps`.

## Index generations & schema migration (Blue/Green)

JustSearch uses a generation-scoped index layout and a migration state machine so schema changes can be deployed without downtime:

- **Generation manager**: `IndexGenerationManager` owns `<indexBasePath>/state.json` (active/building/previous generation pointers + `migration_state`).
- **Dual runtime wiring** (Worker-only):
  - `searchRuntime` serves queries (Blue during migration; read-only for rollback safety)
  - `ingestRuntime` performs all writes (Green during migration; Active when not migrating)
- **Schema mismatch policy**: when the active generation’s schema is incompatible, behavior is driven by `index.schema_mismatch.policy` (see `docs/explanation/04-storage-engine.md`).
- **Operator controls**: migration start/cutover/rollback/pause/resume are exposed on the ingest port and surfaced via REST (see `docs/explanation/07-ui-host-architecture.md`).

Stable migration architecture is described in `docs/explanation/11-index-schema-migration.md`.

## Content Extraction (`ContentExtractor`)

We use **Apache Tika** to handle diverse formats.
*   **Supported:** PDF, DOCX, PPTX, HTML, XML, Markdown, Source Code.
*   **Timeout protection:** `TimeboxedContentExtractor` enforces a hard extraction deadline (default 60s) and increments `extraction.timeout_total` when it triggers. The job is marked failed as `EXTRACTION_TIMEOUT` instead of blocking the indexing loop indefinitely. On a timeout the extractor also replaces its single-thread executor, because `Future.cancel(true)` only interrupts and a wedged native parser ignores the interrupt — without the replacement, one bad file held the only extraction thread and stopped **all** extraction until the Worker restarted.

### Extraction sandbox pool

Parser families that can wedge or exhaust a heap run **out of process**, in a pool of persistent child JVMs.

*   **Routing (`justsearch.extraction.sandbox.mode`)**: `auto` (default) routes by the file kind `IndexingDocumentOps.classifyFileKind` already assigns — `pdf`, `office`, `archive`, `image` and unrecognised `binary` go out of process; `text`, `markdown` and `code` (which includes CSV/JSON) stay in the Worker JVM, where the IPC round-trip would be pure overhead. `in_process` and `process` force one side for measurement or incident response.
*   **Persistent children, not one JVM per file**: `PersistentExtractionSandbox` spawns each child lazily and reuses it, so JVM start and Tika class-loading are paid once rather than per file. One request is in flight per child; `justsearch.extraction.sandbox.pool` (default 1) sets how many children exist.
*   **Protocol**: length-prefixed UTF-8 JSON frames (`SandboxFrames`) over the child's stdin/stdout, carrying the existing `SandboxExtractionRequest` / `SandboxExtractionResponse` records. The child captures the real `System.out` at startup and redirects `System.out` to stderr, so parser chatter cannot corrupt a frame. The child's stderr is drained continuously into a bounded tail — draining is mandatory, not diagnostic, since a full stderr pipe would wedge the child mid-parse.
*   **Child command**: built in-process from `java.home` + `java.class.path` (the Worker runs from a plain `-cp lib\*` classpath, not a jlink image), with `-XX:+UseSerialGC`, `--enable-native-access=ALL-UNNAMED`, and a heap of at least 4x the largest accepted input with a 512m floor (`justsearch.extraction.sandbox.heap`). The Worker's own `-XX:AOTCache` is inherited when it has one and the file exists. `JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND` overrides the whole argv.
*   **Two deadlines, deliberately unequal**: the sandbox owns the extraction deadline and enforces it by killing the child; the surrounding `TimeboxedContentExtractor` waits 15s longer and is only a backstop for a sandbox that itself wedges. When both used the same value the timebox always won (it starts its clock first), its `shutdownNow()` interrupted the pool's wait, and the pool's kill-at-the-deadline path never ran.
*   **Recycling**: a child is killed and respawned on a missed deadline, on a crash, and after `justsearch.extraction.sandbox.max_requests` requests (default 500 — the leak guard). Each event increments `extraction.sandbox_restart_total{reason}` (`timeout` | `crash` | `oom` | `request_budget` | `protocol` | `interrupted` | `probe_failed`); spawns increment `extraction.sandbox_spawn_total`.
*   **Startup probe**: because spawning is lazy, a broken child command would otherwise be invisible until the first file and would then fail every file. At wiring time the Worker spawns one child and runs a trivial extraction through it. The extraction deadline is 20s and the kill that follows a hang waits up to 5s more, so boot blocks for at most ~25s — and only against a child that launches and then hangs. A command that cannot launch at all is rejected immediately. On failure it logs a WARN naming the reason and records `reason=probe_failed`. Process-routed families remain isolated and report `SANDBOX_FAILED` until the child command recovers; decoder-only families continue in-process under `auto`. The probe never enables an implicit in-process fallback.
*   **Failure classification**: a missed deadline is `PARSER_TIMEOUT` (retryable) as before; a child whose stderr carries `OutOfMemoryError` is a **permanent** `PARSER_FAILED` (`IngestionRetryPolicy.NONE`), because a file that does not fit the child heap will exhaust it again; any other non-zero exit is a retryable `SANDBOX_FAILED` carrying the exit code and a bounded stderr tail.
*   **Parser and native-child lifetime**: the Engine kills parser JVMs when the extractor closes or a request is recycled. Before serving any request, `ExtractionSandboxChild.initializeProcessBoundary` assigns the Windows parser to a kill-on-close Job Object through `WindowsParserContainment` in `worker-services`. The parser retains the sole non-inheritable handle until process death, so Windows also terminates its native descendants (including Tesseract) on forced recycling. Setup failure aborts bootstrap; it never enables in-process fallback. The parent-PID watchdog halts the parser after Engine death, triggering the same native cleanup. Custom parser implementations must call this bootstrap before spawning native children. Windows is the supported platform; other platforms retain only the parent watchdog and have no native-descendant containment guarantee.
*   **Garbage Detection:**
    *   Tika often returns "garbage" for scanned/image-only PDFs (random unicode characters) or empty text for images.
    *   We use `TextQualityAnalyzer` (Alphanumeric Ratio < 0.3) to detect this.
    *   **OCR fallback:** If the structured Tika pass is weak and the file is OCR-eligible, extraction may render PDF pages and invoke the app-owned Tesseract runtime. Each OCR component lazily opens one bounded pool, registered in the Engine or local to a parser child, and reuses it until component close. Document cancellation has a five-second cleanup budget and retains live tasks, child handles and temporary files in bounded document slots until actual exit; it never shuts down the shared pool. Optional OCR capacity refusal preserves successful structured text and records failure evidence. Auto worker count respects the Engine background thread policy, and an explicit larger count is rejected before extraction starts. Direct image OCR remains synchronous. Successful OCR writes `extraction_method=OCR_TIKA`, becomes the baseline searchable text, and records compact visual extraction evidence such as OCR language, optional confidence summary, fallback route, truncation, and skip/guard reason.
    *   **VDU enrichment:** Documents that still lack baseline readable text, or that can benefit from richer visual/layout understanding, are marked `VDU_STATUS_PENDING` with `vdu_demand_kind` distinguishing `baseline_text` from `visual_enrichment`.
    *   When VDU later produces non-empty text, the Worker updates `content`, `content_preview`, `language`, chunks, and `extraction_method=VDU`. Failed or empty VDU preserves the best baseline text.
*   **Frontmatter title extraction:** Apache Tika's `MarkdownParser` does not extract YAML frontmatter metadata. `ContentExtractor.extractFrontmatterTitle()` provides a fallback: when Tika returns null for title and content starts with `---`, it parses the `title:` field from YAML frontmatter (handles standard, double-quoted, and single-quoted values). This populates the `title` field used by suggest ranking.
*   **Archive/Binary guardrails:** archives and unknown binaries are classified as `file_kind=archive|binary`. Extraction is best-effort and must not crash the Worker on corrupt/unknown inputs. Regression coverage lives in `modules/system-tests/src/test/java/io/justsearch/systemtests/NastyCorpusTest.java` (fixtures under `modules/system-tests/src/test/resources/corpus/nasty/`).

### Extraction Resilience

The content extraction pipeline is hardened against real-world file system edge cases, particularly on Windows:

*   **Cloud-provider placeholder detection:** On Windows, `isCloudPlaceholder()` checks `dos:attributes` for `FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS` (0x400000) to detect OneDrive Files-on-Demand placeholders. Reading these would trigger silent network downloads or IOException. Uses `FileSystems.getDefault().supportedFileAttributeViews().contains("dos")` for platform detection (respects ArchUnit guardrails against `System.getProperty`). No-op on non-Windows.
*   **File walking resilience:** `syncDirectory()` uses `Files.walkFileTree()` (not `Files.walk()`) to survive `AccessDeniedException` on system directories (`$Recycle.Bin`, `System Volume Information`). The `visitFileFailed` handler logs and continues instead of aborting the entire walk. `preVisitDirectory` applies `WALK_SKIP_DIRS` at traversal time to avoid entering system/tool directories.
*   **Office document memory protection:** A 30MB size limit (`MAX_OFFICE_FILE_SIZE`) gates Office documents before Tika parsing. POI (Tika's Office parser) can expand a 12MB xlsx to 300MB+ in heap — exceeding the Worker's 512MB default. MIME detection via `tika.detect(file)` (magic bytes only) short-circuits before `parseToString()`.
*   **Native access:** both the Head and Worker JVMs are launched with `--enable-native-access=ALL-UNNAMED` because they make FFM downcalls (NVML, the Windows job object, the GPU driver probe); Lucene 10's `MMapDirectory` uses the FFM `MemorySegment` provider, so no `--add-opens` is needed.
*   **Encoding resilience:** Tika 3.x correctly auto-detects and decodes UTF-8 (with/without BOM), UTF-16 LE/BE, Windows-1252, ISO-8859-1, and Shift-JIS. Verified by 8 encoding tests in `ContentExtractorTest`. Correctly-decoded non-ASCII text passes `TextQualityAnalyzer` without false positives.

One filesystem-identity gap remains here: Windows junction points can expose the same file under distinct paths and therefore produce duplicate documents. Tempdoc 889 owns the active investigation. User-configured excludes already reach the Worker's traversal, and extraction failures become typed failed-job and ingestion-ledger outcomes rather than placeholder-indexed documents.

## Embedding Strategy

> **Session construction.** The `OnnxEmbeddingEncoder` session is built by the Worker's composition root alongside the other five ORT encoders (SPLADE, NER, BGE-M3, reranker, citation). See [24-worker-inference-composition.md](24-worker-inference-composition.md) for the pipeline (resolvers → composition root → assembler → `SessionHandle`) and register entry D-007.

*   **Class:** `io.justsearch.indexerworker.embed.EmbeddingService`
*   **Backend:** ONNX Runtime via `OnnxEmbeddingEncoder` (default model: EmbeddingGemma-300M INT8, 298 MB).
*   **Discovery:** `EmbeddingOnnxModelDiscovery` tries `embeddinggemma-300m/` first, falls back to `embedding/` (nomic). Explicit override via `JUSTSEARCH_EMBED_ONNX_MODEL_PATH`.
*   **Default Mode:** CPU-only by default; GPU offload is opt-in via `JUSTSEARCH_EMBED_GPU_ENABLED`.
*   **Batch size:** `MAX_ORT_BATCH_SIZE=8` (optimal for 300M-param models; batch=16+ causes GPU OOM on 2048 MB arena).
*   **Key Env Vars:**
    *   `JUSTSEARCH_EMBED_ONNX_MODEL_PATH`: explicit model directory path.
    *   `JUSTSEARCH_EMBED_GPU_ENABLED`: enables CUDA execution provider for embedding (default `false`).
    *   `JUSTSEARCH_EMBED_GPU_MEM_MB`: GPU arena size in MB (default `2048`).
    *   `JUSTSEARCH_EMBED_BACKEND=onnx`: backend selector (default `auto`, which resolves to ONNX when model found).
    *   `JUSTSEARCH_LLM_BACKEND=stub`: disables embeddings entirely (useful for hermetic tests).
*   **Constraint:** When `llama-server` is running in Online mode on low-VRAM cards, we must not keep a GPU embedding backend loaded.
*   **Logic (`IndexingLoop.handleGpuStateTransition`):**
    *   If `signalBus.isMainGpuActive()` is **TRUE**: the Worker **unloads** `EmbeddingService` (best-effort VRAM release) and skips embedding work.
    *   If `signalBus.isMainGpuActive()` is **FALSE**: the Worker **reloads** `EmbeddingService` (auto-discovery) and performs a backfill pass for pending embeddings.

### Deferred embedding lifecycle

During primary indexing, embedding is **deferred** to backfill (tempdoc 312 item 19). Documents are indexed with `EMBEDDING_STATUS=PENDING` and no vector. After the job queue drains, `EmbeddingBackfillOps` batch-embeds pending documents and updates them via Lucene read-modify-write. This yields 86–235 docs/sec primary indexing (vs 5.8 docs/sec with inline embedding). Users get BM25 search immediately; vector search improves progressively.

**Exception:** During blue-green migration (embedding model change), inline batch embedding is enabled so the new index has vectors at cutover. Controlled by `migrationActiveSupplier` in `IndexingLoop` (tempdoc 312 item 20).

Embedding compatibility gating (vector safety):
- The Worker compares the current embedding model fingerprint to the stored index fingerprint (commit metadata) and blocks VECTOR/HYBRID queries when incompatible; `/api/status` surfaces `embeddingCompatState` and `embeddingCompatReason`.
- Legacy auto-rebuild heuristics count **parent docs only** (exclude `is_chunk=true`) so chunk documents do not break “all pending” detection when chunks exist.

Chunk vectors (Phase 6) are also supported:
- Chunk documents can embed into `chunk_vector` and track status via `chunk_embedding_status`.
- Chunk-vector enablement is controlled by `rag.chunk_vectors.enabled` (default true); retrieval is coverage-gated (see below).

### Embedding Performance Tuning

The embedding service is configured for optimal throughput:
- **Batching enabled**: Multiple documents are embedded in a single inference call, providing +200-300% throughput vs sequential processing.
- **Backfill batch size**: 100 documents per backfill cycle (up from 50; provides +40% backfill throughput).
- **GPU arbitration**: When `isMainGpuActive()` is true, embedding is paused to avoid VRAM contention with Online mode chat.

## RAG Chunking
*   **Class:** `ChunkSplitter.java`
*   **Strategy:** Text is split into chunks of **500 tokens** (approx. 375 words) with **50 tokens overlap**.
*   **Algorithm:** `findBoundary()` prefers Paragraph, then Sentence, then Word boundaries to avoid cutting context mid-thought.
*   **Content-aware modes (current):** Chunking mode is selected from `{mimeBase, fileKind}` to avoid format-specific boundary breaks:
    * `MARKDOWN`: respects heading boundaries, fenced code blocks
    * `CODE`: prefers newline boundaries
    * `CSV`: avoids splitting inside quoted fields (quote-aware newline detection)
    * `JSON`: avoids splitting inside string literals (best-effort state machine)
*   **Storage:** Chunks are stored as separate Lucene documents with `is_chunk=true` and `parent_doc_id` pointer, including:
    * `chunk_content` + `chunk_start_char`/`chunk_end_char` for citation offsets
    * optional navigation metadata (`chunk_start_line`, `chunk_end_line`, `chunk_heading_text`, `chunk_heading_level`)
*   **Threshold:** Chunk docs are only generated for sufficiently large documents (currently `>= 2000` extracted chars) to avoid tiny-fragment overhead.
*   **Performance:** Uses `estimateTokens` (word count * 1.3) rather than a heavy tokenizer for speed.

Chunk regeneration is centralized in `ChunkDocumentWriter` so index-time chunking and VDU-driven content updates produce consistent chunk docs and offsets.

## Search and Retrieval

The Worker handles both interactive search and RAG retrieval behind the `SearchServiceCalls` port. The search pipeline includes BM25, dense vector (KNN), and SPLADE retrieval legs with multi-stage fusion and reranking.

For the full query pipeline (fusion algorithms, reranking cascade, degradation signals), see `docs/explanation/23-search-pipeline-overview.md`.
