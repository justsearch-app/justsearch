---
title: Storage Engine
type: explanation
status: stable
description: "Lucene internals, schema (SSOT), and locking strategies."
---

# Storage Engine

JustSearch wraps **Apache Lucene 10** as its core storage engine. We do not use higher-level wrappers like Elasticsearch or Solr; we interact directly with the Lucene API (`IndexWriter`, `IndexSearcher`) for maximum performance and minimal footprint.

## Directory Structure

### Index root (`indexBasePath`)

The effective on-disk index root is `RuntimeConfig.indexBasePath()`:

- **Default**: `<dataDir>/index/<collection>` (collection defaults to `default`)
- **Override**: `JUSTSEARCH_INDEX_BASE_PATH` or `-Djustsearch.index.base_path=<path>`

In dev (`npm --prefix ./modules/ui-web run dev:all`) this commonly points at `modules/ui-web/.dev-data/index/default`, but the override mechanism is the source of truth.

### Generation layout (blue/green ready)

The index root is **generation-scoped** (managed by `IndexGenerationManager`):

```text
<indexBasePath>/
  state.json                       # pointers + migration state (format_version=2)
  migration_progress.json           # best-effort enumerator progress snapshot
  indices/
    <generationId>/
      .justsearch-generation.sentinel
      .justsearch-index-generation.json
      segments_N / _*.cfs / ...     # Lucene files
```

This layout enables safe schema migration (build a new generation alongside the active one) and crash-safe pointer updates.

*   **MMapDirectory:** We use memory-mapped files for the index.
    *   *Pros:* Extremely fast read access. The OS handles caching.
    *   *Cons:* On Windows, MMap files are **locked** by the OS. This is the primary reason for our 3-process architecture: only the Worker process ever opens these files, preventing `AccessDeniedException` when the UI process tries to delete them.

## Schema Management

Data in Lucene is schema-less by default, but JustSearch enforces a strict schema via the **SSOT** field catalog (`FieldCatalogDef`), injected via `FieldMapper`.

### Core Fields (`SchemaFields.java`)
| Field | Type | Purpose |
| :--- | :--- | :--- |
| `doc_id` | keyword | Primary Key (Normalized file path). |
| `doc_uid` | keyword | Stable, content-independent identity plus the search-after tie-breaker. Parent UIDs survive reindex and supported renames; chunk UIDs derive from the parent UID and chunk ordinal. |
| `content` | text | Main searchable text (tokenized). |
| `content_sha256` | keyword (stored only) | SHA-256 of this parent document's stored `content` — its CONTENT REVISION. Not indexed, not DocValues. Same digest definition as `chunk_parent_content_sha256`, so parent and chunk revisions are directly comparable. |
| `content_preview` | text | Small stored snippet source (first few KB) for fast results list rendering. |
| `title` | text | Optional extracted title (stored). |
| `path` | keyword | Normalized path (stored). |
| `filename` | keyword | Filename (stored). |
| `mime` | keyword | Raw MIME (often includes parameters like `; charset=`). |
| `mime_base` | keyword | Normalized base MIME without parameters (DocValues; filter/facet friendly). |
| `file_kind` | keyword | Canonical UX-oriented type bucket (DocValues; filter/facet friendly). |
| `language` | keyword | Heuristic language/script tag (DocValues; filter/facet friendly). |
| `vector` | floats | 768-dim embeddings (HNSW). |
| `modified_at` | long | Timestamp for change detection. |
| `size_bytes` | long | File size (DocValues; sortable). |
| `parent_doc_id` | keyword | For Chunk documents (points to File). |
| `is_chunk` | boolean | "true" if this is a chunk, absent otherwise. |
| `chunk_index` | int | Sequential index of the chunk (0, 1, 2...). |
| `chunk_total` | int | Total number of chunks for the parent document. |
| `chunk_content` | text (indexed, not stored) | Searchable chunk text used for BM25 retrieval; read paths reconstruct it from the stored parent `content` and chunk offsets. |
| `chunk_start_char` | int | Start character offset (0-based) into the parent document’s extracted text. |
| `chunk_end_char` | int | End character offset (exclusive, 0-based) into the parent document’s extracted text. |
| `chunk_parent_content_sha256` | keyword (stored only) | SHA-256 of the parent `content` revision the offsets above address. Not indexed, not DocValues. |
| `chunk_start_line` | int | Optional start line number (1-based) for citation/navigation UX. |
| `chunk_end_line` | int | Optional end line number (1-based) for citation/navigation UX. |
| `chunk_heading_text` | keyword | Optional nearest preceding Markdown heading text (empty when N/A). |
| `chunk_heading_level` | int | Optional Markdown heading level (1–6; 0 when N/A). |
| `chunk_vector` | floats | 768-dim chunk embeddings (HNSW) used for chunk-level hybrid retrieval. |
| `chunk_embedding_status` | keyword | Chunk embedding generation status (`PENDING|COMPLETED|FAILED`). |
| `chunk_embedding_retry_count` | long | Retry count for chunk embedding poison-pill protection. |
| `entity_persons_raw` | keyword (SortedSetDocValues) | Person entity values for filtering, faceting, and NER-membership evidence selection. |
| `entity_organizations_raw` | keyword (SortedSetDocValues) | Organization entity values for filtering, faceting, and NER-membership evidence selection. |
| `entity_locations_raw` | keyword (SortedSetDocValues) | Location entity values for filtering, faceting, and NER-membership evidence selection. |
| `meta_source` | keyword (stored, DocValues) | Document source for filter/facet. |
| `meta_author` | keyword (stored, DocValues) | Document author for filter/facet. |
| `meta_category` | keyword (stored, DocValues) | Document category for filter/facet. |
| `meta_published_at` | long (stored, DocValues) | Publication timestamp for filter/sort. |
| `extraction_method` | keyword | Extraction tier used (e.g., STRUCTURED_TIKA, FLAT_TIKA). |
| `extraction_quality_score` | double | Numeric quality score 0.0–1.0 for provenance. |

### Document identity

The Worker keeps the parent mapping `path_hash → doc_uid` in the path-free
`document_identity` table inside `jobs.db`. Admission resolves that mapping before extraction, so a
normal rewrite, delete-and-reindex, or Blue/Green rebuild writes the same UID. The serving index
seeds the table from its stored parent `doc_id` and `doc_uid` fields before indexing starts. That
scan is a seeding step, not a per-boot pass: it runs only when the table is empty or when
`document_identity_import` holds no row for the serving generation — after the first import every
parent resolves its uid through the store, so the only states left to repair are an index older than
the store and a wiped or restored `jobs.db`. It streams parents into SQLite in transactions of 1,000
rather than materialising the corpus, and a live parent whose `doc_id`/`doc_uid` docvalues are
missing or blank is counted in `parents_skipped` (one WARN) and re-mints at its next admission
instead of failing the boot. SQLite identity failures are fail-closed: the queue retries the
document rather than minting from a second authority.

Chunk documents use `parentDocUid + "#" + chunkIndex`, making chunk regeneration deterministic
without adding a second schema field. API-driven moves re-key the parent mapping before Lucene path
fields are rewritten. Filesystem-watcher renames still arrive as delete plus create events and do not
currently carry rename identity.

The Head's authored feedback streams use the same stable parent identity. New
`FeatureSnapshot.HitFeatures.docId` and `ResultDisposition.docId` values are parent UIDs; the snapshot
also retains a path-oriented `sourceDocId` solely to correlate the unchanged search UI and agent
citation events before persistence. Chunk-only search results receive the parent UID through the
Worker's existing parent-metadata enrichment. Missing or conflicting UID evidence produces no new
feedback row rather than falling back to a path key. Pre-Phase-2 NDJSON rows omit `sourceDocId` and
remain readable and re-projectable with their legacy path keys; there is no path-to-UID backfill.
The derived `real-feedback-triples.ndjson` file keeps the trainer-compatible JSON property name
`doc_id`, whose value is the stable UID for new real-feedback labels.

#### Identity versus content revision

Identity answers *which document this is*; the content revision answers *which version of it*.
Feedback captures the pair `(doc_uid, content_revision)`, where the revision is the parent's
`content_sha256`. The Head injects both into the Worker projection for capture and strips them from
the HTTP response unless the caller requested them by name; a chunk hit receives its parent's
revision through the same parent-metadata enrichment that supplies the parent UID. Identity survives
edits and verified moves, while the revision advances on every content change, so `LabelProjection`
can tell a label that still describes the current text from one that does not: a disposition whose
revision differs from the newest revision observed for that document is projected as STALE — kept,
but written with its score multiplied by `STALE_LABEL_WEIGHT` (0.5) and a `stale: true` property, and
counted in `LabelProjection.Result.staleTriples`. A null revision is UNKNOWN, never a mismatch, so
rows written before the field and documents indexed before it are never down-weighted.

#### Confirmed deletion and the identity grace window

Identity rows are never dropped on deletion. Instead, the points where the Worker removed a document
BECAUSE the file is verified absent — the periodic sync's orphan prune, the indexing loop's
missing-source delete, and the filesystem watcher's DELETE event — set a nullable `deleted_at` on the
row (schema V13). Every one of them re-verifies absence at the mark, so an unreadable file, a dead
mount, or a cloud placeholder records nothing; removals that are policy rather than deletion (an
un-watched root, a dropped collection, an exclude rule) never mark at all. `resolve` then reads the
mark against `index.identity.deletion_grace_ms` (default 30 days): inside the window the mark is
cleared and the uid is kept, because a file reappearing that soon is the same document returning;
past it a new uid is minted onto the same row and `first_seen_at` resets, so a replacement file
cannot inherit the previous document's feedback. A verified rename clears the mark outright.

**Notes on new field groups:**

- `meta_*` keyword fields are lowercased at both index time and query time (same pattern as `mime_base`). See [ADR-0020](../decisions/0020-structured-metadata-filterable-facets.md) for the full design rationale.
- `long` fields with `filter` role are dual-indexed as `LongPoint` (BKD-tree) + `NumericDocValuesField`, and `QueryFilterBuilder` wraps range queries in `IndexOrDocValuesQuery`. This benefits both `meta_published_at` and `modified_at`. (362)
- **SSOT catalog drift caveat:** the root-level `SSOT/catalogs/fields.v1.json` and `modules/adapters-lucene/src/main/resources/SSOT/catalogs/fields.v1.json` are separate copies. Adding fields to the root catalog does not update the classpath copy (used in production). Both must be synced. (326)

### Chunking Strategy
Large documents are split into overlapping chunks (default 500 tokens) to support RAG.
*   **Storage:** Chunks are stored as separate Lucene documents.
*   **Linkage:** They are linked to the original file via `parent_doc_id`.
*   **Retrieval:** Searches can target `is_chunk:true` to find specific relevant passages rather than whole files.

`chunk_content` contributes analyzed postings but is deliberately not stored a second time. A read
that explicitly projects it loads each distinct parent at most once and returns the exact Java
UTF-16 substring `content.substring(chunk_start_char, chunk_end_char)`—including original CRLF,
fence, whitespace, and non-BMP characters, with no trimming or normalization. Missing parents and
invalid or out-of-range offsets fail closed without fabricated text (batch/generic projections omit
the value; the chunk-search envelope retains its existing empty-string fallback). Read-modify-write
operations apply the catalog policy `rederive-parent-slice` so unrelated updates do not erase the
chunk's indexed postings.

Offsets alone do not say *which* parent revision they address, so every chunk also carries
`chunk_parent_content_sha256` — the SHA-256 of the exact parent `content` string it was cut from.
The parent write and the chunk regeneration that follows it are two separate coordinator calls, so
an NRT refresh in between exposes the new parent content beside the not-yet-regenerated chunks; an
equal-or-longer rewrite fits the old offsets and would silently re-slice the wrong text. Before
re-deriving, the read-modify-write path hashes the parent it read and compares: a missing or
differing hash refuses the rewrite with an `IOException`, the same fail-closed path as a missing
parent. Refusing loses nothing — such a chunk is stale by definition and regeneration deletes it.

The read path applies the same test. Every reconstruction — `getDocumentContent` on a chunk id, the
`chunk_content` projection on a search hit, the chunk-search envelope — routes through one guard
that returns the slice only when the parent's current revision equals the chunk's
`chunk_parent_content_sha256`. On a mismatch, or for a legacy chunk carrying no revision at all,
the chunk is omitted exactly the way a missing parent is: the point lookup returns nothing, the hit
arrives without `chunk_content` so its excerpt is empty rather than borrowed, and a RAG context
drops the passage instead of citing text from a revision the user never saw. Each refused
reconstruction increments `index.runtime.chunk_revision_mismatch_total`, so the parent-rewrite
window is visible rather than silent. The comparison reads the parent's stored `content_sha256`
where it exists and hashes the parent's content only for documents indexed before that field, once
per parent per read rather than once per chunk.

Because it is a stored field, adding it moved `index_fingerprint`, so it lands with the reindex
that bundle already required. One degenerate embedding no longer costs a batch either: a zero-
magnitude or non-finite dense vector is dropped from that one document (its `embedding_status`
becomes `FAILED` and `index.runtime.vector_dropped_total` counts it) instead of aborting every
document written alongside it.

Chunk-level vector retrieval uses **field separation**:
- Full documents embed into `vector`
- Chunk documents embed into `chunk_vector`

This prevents doc/chunk mixing and keeps filter parity safe across TEXT/VECTOR/HYBRID query paths.

### Large docId-set filters (scale guardrail)
Some query paths (especially RAG retrieval) need to search within a **set of specific documents** (e.g., “only these docIds selected in the UI”).

Naively implementing that as a `BooleanQuery` with one `TermQuery` clause per docId can hit Lucene’s `maxClauseCount` (default 1024) and throw `TooManyClauses`.

Current implementation uses `TermInSetQuery` for these "ID set" filters to avoid clause explosion:

- Worker search runtime ops: `ReadPathOps` (read), `WritePathOps`+`CommitOps` (write/commit), `RunningRuntime`+`RuntimeSession` (lifecycle) in `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/`

Regression coverage:

- `modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkSearchIntegrationTest.java` asserts `searchFullDocsForDocs` / `searchChunksForDocs` handle docId selections larger than `IndexSearcher.getMaxClauseCount()` without throwing; `TextSearchIntegrationTest` covers the prefix-expansion high-fanout variant.

## Interactive Search Runtime (current behavior)

Interactive file search is latency-sensitive (called on every keystroke), so the Worker enforces:

- **Projection**: callers can request only the fields they need for the list UI.
- **No `content` in hits**: full extracted `content` is never returned as part of search hits.
- **No `content` materialization for interactive search**: the Worker avoids decoding large stored `content` when building hit payloads.
- **Snippets**: the UI uses `content_preview` (small stored field) plus client-side highlighting.

## Sorting + cursor pagination (TEXT mode)

For large result sets, TEXT mode supports:

- **Query-time sorting** (e.g., relevance vs modified time).
- **Cursor-based pagination** using Lucene `searchAfter`.

The cursor token is intentionally opaque and short-lived: index mutations (especially reindex) can invalidate it.

### Validation Strategies
1.  **Startup Check:** `LuceneIndexRuntime.validateIndexableFields(...)` ensures the loaded catalog matches the constant `SchemaFields.INDEXABLE_FIELDS`. This prevents "silent failures" where code writes to a field that doesn't exist in the schema.
2.  **Runtime Check:** `FieldMapper` logs a warning (once per field) if an unknown field is encountered.

## Reliability Strategies

### 1. Corruption Recovery
`IndexRuntimeIOException` classifies errors. If `Reason.CORRUPT_INDEX` is detected at startup (e.g., missing segment files or `CorruptIndexException`):

- **Auto-recovery (guarded, backup-first)**: if `index.auto_recovery=true`, the runtime will **rename the broken index directory to a timestamped backup** and rebuild an empty index. This avoids destructive deletes and is more Windows-friendly.
- **Manual**: if auto-recovery is disabled, startup fails with a typed error; operators can safely rename/remove the affected generation directory.

### 2. Schema mismatch (distinct from corruption)

Schema mismatches are **not** treated as “corruption”.

- **Typed reason**: `IndexRuntimeIOException.Reason.SCHEMA_MISMATCH`
- **Policy-controlled** via `index.schema_mismatch.policy` (also overridable via `JUSTSEARCH_INDEX_SCHEMA_MISMATCH_POLICY` / `-Dindex.schema_mismatch.policy=...`):
  - `FAIL_CLOSED`: refuse to rebuild; require operator action
  - `REBUILD_BACKUP_FIRST`: rename-to-backup and rebuild empty (**dev default**)
  - `BLUE_GREEN_MIGRATE`: orchestrate a blue/green migration, serving read-only Blue while building Green (**production default**, tempdoc 915)

Stable migration architecture is described in `docs/explanation/11-index-schema-migration.md`.

**Index identity:** what triggers `SCHEMA_MISMATCH` is a mismatch on `index_fingerprint`, a
SHA-256 stamped into Lucene commit user-data by `IndexFingerprint`
(`modules/adapters-lucene/.../commit/IndexFingerprint.java`) over the canonical JSON of the effective
*physical* index shape — catalog schema version, each field's physical projection, the analyzer
fingerprint, vector format, HNSW `m`/`ef_construction`, chunking parameters, and the
embedding/SPLADE model digests. It is the one rebuild-requiring parity key; `boosts_fp` (query-time
field boosts) is the other tracked key but never triggers a rebuild. Full input list, exclusions, and
the enforcement mechanics live in `docs/explanation/11-index-schema-migration.md`.

### 3. Commit Strategy
Writing to disk is expensive. `IndexingLoop` controls commits, but `LuceneIndexRuntime` enforces the physical write.
*   **Trigger:** We commit to disk (fsync) when:
    1.  **Time:** > 10 seconds since last commit.
    2.  **Size:** > 1000 documents in buffer.
    3.  **Event:** Shutdown signal received (Safe close).

### 4. Backpressure
The `queueDepth` counter guards against overloading the writer.
*   If `queueDepth > maxQueueDepth` (default 10,000), `indexBatch` throws `BACKPRESSURE` exception to slow down the ingest loop.

### 5. Vector Search (HNSW)
We use `Lucene99HnswVectorsFormat` (Float32) in the current default codec (`JustSearchCodec`, which extends `Lucene104Codec`).
*   **Quantization:** Available behind a flag (default off): `index.vector.quantization.enabled`, `JUSTSEARCH_INDEX_VECTOR_QUANTIZATION_ENABLED`, or `-Djustsearch.index.vector.quantization.enabled=true`. Uses `Lucene104HnswScalarQuantizedVectorsFormat`. Tested with Lucene 10.3.1 (5K/20K/50K docs, all modes pass). Provides ~75% vector storage reduction. Float32 remains default for backwards compatibility with existing indexes.
*   **Dimension:** Validated against the SSOT catalog (768).
*   **Validation:** `FieldMapper` strictly checks that `vector` field arrays match the expected dimension, throwing errors if they drift.

### 6. Performance Configuration

JustSearch uses tuned Lucene defaults optimized for desktop workloads:

| Setting | Default | Purpose |
|---------|---------|---------|
| Directory type | MMapDirectory | Memory-mapped files for fast reads (explicitly set at `ComponentsFactory.java:83`) |
| RAM buffer | 64 MB | Larger buffer reduces flush frequency (+20-30% indexing throughput vs Lucene's ~16MB default) |
| Commit interval | 10s / 1000 docs | Balance between durability and performance |

These defaults are optimized for desktop systems with sufficient RAM. The RAM buffer setting can be overridden via `index.writer.ram_buffer_mb` in configuration YAML if needed.

## Operation outcome storage

`operations.db` is a separate SQLite store owned by the Engine composition. Its app-api port is
opened only after the process acquires AppInstanceLock for its data directory, including
LauncherEnvironment. A second launcher in the same JVM must acquire its own lock and
is refused while the first remains active. Shutdown retains the lock until the operations
store closes; failed setup releases it after safe store cleanup. The port is
implemented in app-observability, and the same instance is injected into the application and index
composition before asynchronous startup. Startup retries SQLite BUSY (including extended BUSY
codes) during WAL/schema/pruning setup only after the attempted connection closes successfully.
Compatibility inspection and corruption preservation run once before these attempts; contention
never triggers quarantine. A monotonic five-second window bounds retry admission, while each
admitted native call retains its five-second busy timeout. Other failures, uncertain close and
interruption fail startup. It closes after the index half drains. The schema starts
at version 1 and migrates to version 2 with SQL payload bounds (262144 UTF-8 bytes
for identity, 4096 for checkpoint cursor), preserving rows, ordering sequence and
history fence; `jobs.db` independently uses version 18. Versions 15–17 retain nullable
`jobs.content_hash` for legacy rows and add opaque revisions to switch-buffer replacements
and queue admissions. Version 18 adds the finite-walk projection schema and epoch primitives. Explicit recorded
enumeration preserves same-walk retry state; maintenance preserves active membership while
assigning a fresh admission revision. Recorded terminal ledger coverage and monotonic counters
commit with the job outcome on the same connection. Successful counters deduplicate by
walk, path hash and committed content hash; failed counters count each terminal admission once.
Enumeration closure and administrative skips commit with their ledger coverage. Sealing
requires closed enumeration, terminal current members, exact matching ledger coverage and
no issued claims; the immutable versioned receipt distinguishes historical effects from
current failures and skips. Cleanup retains recorded evidence until exact final acknowledgement.
Queue notifications deliver committed keys outside the lock. Existing age cleanup prunes old
sealed exactly acknowledged progress only after all keyed jobs and ledger references are gone.
The Engine's `RecordedIngestionCoordinator` owns outer acknowledgement and uses queue
notifications plus maintenance to reconcile committed progress and recover stored walks.
Replay removes only the versions it applied and committed, preserving admissions that arrive
during replay even when their keys, payloads and timestamps match an earlier version. Migration DDL and `user_version` commit together, and checked or unchecked failures
roll back both.

Version 3 adds the bounded pending/accepted prepared-invocation payload. Version 4
persists the acceptance-time history mode (`NONE`, `STANDARD`, `UNDOABLE`, `UNDO`)
and original provenance instant. The dispatcher derives the mode from its audit
and undo declaration; an audited non-dispatched producer supplies it explicitly
through `OperationAttemptRunner.Request`. Producers with their own ledger use NONE.
The first accepted mode wins on keyed retry independently of public input identity.
Legacy rows migrate to NONE because the original audit declaration is unavailable.
The recoverability gate compares both operations and jobs register versions with
their declared Java schema constants, ignoring comments and string literals.
The stored context/executor/initiator/correlation fields supply attribution; signed
intent tokens and handler content are excluded from these history metadata fields.
Recent history is a bounded SQL projection of visible terminal rows, ordered by
completion time and row id. Its latest200-row display window does not delete the
underlying operations. The same metadata projection serves committed live entries;
optional operationKey distinguishes repeated invocations of one operation and
supplies the new committed action-ledger identity. The existing audit journal forces
the record file before accepting append, including the actual retained generation
on deduplicated retry after an uncertain write. Legacy/uncommitted live failure
observations have no committed key. A live STORAGE_FAILED observation cannot add a
terminal row to durable history. Version5 adds a row-owned history_pending bit:
new visible terminal transitions and pre-start refusals set it atomically. The
bounded pending read excludes private payloads; acknowledgement changes only this
bit after sink acceptance or an explicit projection ownership exclusion. Existing
terminal v4 rows retain their prior best-effort ledger guarantee and durable recent
visibility; open rows acquire the obligation when they finish after migration.
Migration does not replay old legacy ledger identities or invent past delivery.
One `OperationHistoryProjector` attaches at the end of Head bootstrap. Its completion
subscription publishes committed history and live ledger entries without journal I/O
on the producer thread. Memory and note rows remain visible on agent-loop transport;
generic agent-loop operation entries are explicitly excluded because the agent-run
source owns their ledger projection.

The registered `head.operations-history` timer retries every second. Its durable arm
reads at most256 oldest pending rows, forces journal acceptance, publishes live, then
acknowledges; it stops at the first append or acknowledgement failure. Disabled or
failed persistence keeps rows pending. A separate startup arm reads at most256 rows
per tick through a transient completion-time/id cursor, with SQL restricted to row
ids at or below the maximum accepted id captured after subscription. That finite
cohort remains bounded when completion clocks regress. This lets a restarted live ledger expose a larger
backlog despite a failed oldest append, without continually replaying new arrivals.
The pending bit remains the only durable progress authority. The live ring holds500
events, so replay after eviction can repeat an update; this is not unbounded exactly-once
stream delivery. Atomic SSE snapshot/reconnect remains a separate mechanism.

Construction gates callbacks and timer activation until all acquisitions succeed.
Close unsubscribes and quiesces callbacks, cancels and awaits the timer, then releases
its registration. A timeout preserves the owner for retry and prevents Head dependency
teardown. Failed construction marks the inactive owner stopping before releasing its
activation gate and unwinds its acquired resources.

Compatibility inspection copies a quiescent main file and WAL into a private temporary directory.
SQLite reads that copy, including uncheckpointed committed versions, so refusing a future format
cannot change the original main file, WAL or SHM. The temporary copy is removed before startup
continues. This costs temporary disk space proportional to one database plus its WAL; it avoids
hand-written WAL parsing and SQLite's otherwise writable shared-memory side effects in read-only
mode. Callers must exclude concurrent writers during inspection. The operations
store uses SQLite `quick_check` for startup integrity inspection.

Terminal operation rows expire after30 days by completion time once their history
projection is acknowledged (or the row owes no projection). A100000-row cap evicts
eligible terminal rows first; open work, COMPLETE_WITH_GAPS and pending history
projection survive age and capacity pruning. If protected rows fill the cap,
acceptance refuses with OPERATIONS_CAPACITY; it never drops undelivered history.
Pruning runs once at store open and hourly through the registered
`head.operations-retention` timer; acceptance reserves capacity under the same store
lock. Timer failures log ERROR and retry on the next tick. Its owner cancels and
awaits the timer before releasing its executor registration.

Each eviction transaction advances `history_since_ms` to at least the newest evicted
key's UUIDv7 timestamp plus one millisecond. A present row wins over that fence;
an absent older key is expired. A fence ahead of the clock includes its remaining
retry delay in the store refusal. Terminal transitions return their committed row
snapshot under the store lock, so eviction cannot erase an outcome before live
completion publication. The pending bit retains rows until projection acknowledgement; ordinary completion observers alone do not extend retention.

An unreadable operations database is preserved with its WAL and SHM in one timestamped directory.
An interrupted `.pending` directory is resumed before creating a replacement. The replacement's
singleton metadata records a history fence at recovery time plus five minutes plus one millisecond;
old preservation directories retain that fence if initialization itself is interrupted. The Health
surface reports the history loss, the preservation directory and the fence time. Failure to preserve
bytes refuses startup. An external rollback to a valid older database is outside this detection
contract. Durable operation acceptance and replay are separate consumers of this store.

Operation dispatch validates caller context and registered authority before pure handler
preparation. A keyed request compares the public argument digest before preparing; a
matching recorded outcome returns without invoking a handler. The dispatcher freezes an
optional versioned replay payload in a bounded, nonce-bound pending preparation and
performs the trust gate before atomically accepting that exact preparation. Content-bearing
payloads use the existing data-key cipher; metadata payloads remain unsealed. Raw public
arguments stay out of the public identity, although the private envelope contains the
canonical prepared invocation. Changed public input under the same key conflicts.

Preparation may read scope but must not schedule work, register roots or enqueue writes.
Ordinary preparation failures receive an accepted attempt without a replay payload and a
terminal refusal; capability and admission checks still gate execution after acceptance.
Generic handlers retain their passthrough behavior. A replay handler must validate its
schema and implement prepared execution. An accepted incomplete row does not authorize
caller-driven re-execution; its recovery owner must revalidate authority and resume it.

`OperationPolicy.recordKind` classifies ordinary, prepared, refused and undo attempts.
Every dispatched operation accepts a durable attempt, including operations with
`AuditPolicy.NONE`; that policy suppresses history projection only. Admission
refusals persist their specific reason (`CONTEXT_LIMIT`, `ENGINE_LIMIT`, `FROZEN`
or `WORK_FINISHED`) without invoking the handler.
The registry's `OperationKind` enum in app-agent-api is also the store's kind contract;
its JSON and SQLite spelling is the same closed lowercase vocabulary, including `memory`
and `note`. Existing policy constructors default to `operation`. The full declaration
schema includes this backend field; the selected policy axes in the live UI registry
projection do not include it. Java consumers of the former app-api enum must update
their import to `io.justsearch.agent.api.registry.OperationKind`.

A record-kind declaration alone does not connect a producer or recovery owner.
Declaring a kind does not change admitted survival:
the work owner retains its original survival for cancellation and disconnect handling.
Recorded producers must resolve their survival policy before admission, or admit a
separately owned child, before activating a recovery classification.

The compiled operations API includes a frozen root-plan value and derived ingestion-child
acceptance. The process root binds the existing prepared-envelope codec resolver to the
runner. It selects a root from the original parent preparation outside SQLite; the store
then compares that exact preparation witness, copies parent attribution, and inserts the
child only while the parent is RUNNING. The child identity contains a parent key and plan
digest; its private payload holds the one-root plan. Repeated acceptance returns the same
child, including its terminal outcome. Terminal children remain retained while their parent
is nonterminal, including COMPLETE_WITH_GAPS, and ordinary retention resumes afterward.
Parent completion remains the producer's explicit composition of durable child outcomes.
The Engine's `RecordedIngestionCoordinator` now connects these records to the actual bounded
Java root producer. Prepared ingest/reindex handlers forward the issued parent handle;
the coordinator derives children, freezes their policy and checks generation and authorization
before queue effects. It checkpoints committed progress and waits for sealed child receipts
and durable acknowledgements before completing the parent. On restart it resolves the stored
prepared envelope rather than preparing against current filesystem or configuration state.
Permanent recovery refusal is recorded before retiring unfinished queue members. The
parent checkpoint `ingest-refusal:1:<code>` preserves the allowlisted refusal across
restart and later authority changes; progress checkpoints retain that decision. Unknown
versions or codes fail closed with unavailable-state failure and retain unresolved queue
evidence; a corrupt marker cannot authorize retirement or acknowledgement. Boot
reconciliation writes a valid checkpoint on the existing
RUNNING attempt without executing a new attempt. The parent stays open until existing
children settle and their receipts are acknowledged, then publishes the recorded failure.
At the attempt limit, receipt bookkeeping for a validated refusal also uses the existing
RUNNING attempt; it cannot spend another execution attempt or replace the refusal with
attempt-exhaustion noise from a child.
For a completed enumeration, queue retirement requires the exact plan hash and no issued
claims. It records policy-skipped coverage for pending or orphaned processing members,
preserving indexed results and the enumeration outcome. Cancellation and temporary
recovery waits do not become permanent refusal markers.
The public REST ingestion alias enters the same operation dispatcher and prepared handler.
Its response supplies the durable operation key; the keyed operation-history read projects
committed progress and the terminal outcome from the operations store.

The architecture gate forbids producers from calling the store's lifecycle methods
directly. `governance/engine-ports.v1.json` catalogs the store and runner interfaces,
their outer process bindings and consumers. The operation-surface register separately
governs sibling records and row cardinality.

A failed durable attempt transition logs an ERROR with its key and intended state.
The runner retains the first persistence failure for the process; Health reports it
through a sticky `operations.persistence_failed` condition even when Health attaches
after the failure. Dispatcher history records FAILURE with a bounded error code;
it never reports successful completion when the terminal write failed. The durable
row remains unresolved. Exception details are retained in diagnostics, while the
Health condition carries only operation metadata.
