# C2 design record: the operations store and the outcome boundary

Status: DESIGN, decided 2026-09-10 under the delegated lane authority (design section 0,
"Decision authority"). Grounded at `4229f1091` on `worktree-lane-F-A`. This file carries the
full mechanism for the two C2 questions the entry investigation left open
([c2-entry-investigation.md](../C1/c2-entry-investigation.md)); `design.md` section 0 carries
the dated one-line rows, sections 7.6 and 16 carry the contract changes, and `stages/C2.md`
carries the per-item amendments. Nothing here is implemented.

Coordinated with the stage D1 re-grounding (its record is `evidence/D1/regrounding-2026-09-10.md`
on this worktree, written by the D1 agent). The D1 agent's inputs are marked *(D1)* where they
changed this design.

## 1. Where the store lives and who owns its lifetime

### 1.1 The producers, and the module that all of them see

| producer | module | what it writes |
|---|---|---|
| `OperationExecutorImpl.dispatch` (every catalog operation: HTTP, MCP, agent tools) | app-services | acceptance, completion |
| ingest requests, boot and recovery walks through `KnowledgeClient.scanRoot` | app-services | acceptance |
| per-unit checkpoints from the ingest loop; the reindex plan capture and successor ingest acceptance at activation *(D1)* | worker-services, indexer-worker | checkpoint, acceptance |
| `BackgroundRunService.schedule` and its timer fire | app-agent | acceptance, running, typed refusal |
| settings apply | ui at C2, the reconfigure handler at D1 | acceptance, completion |

The lowest module every producer depends on is `app-api` (`worker-services/build.gradle.kts:12`,
`indexer-worker/build.gradle.kts:15`, `app-agent/build.gradle.kts:11`). app-agent sees
`app-observability` only as `testImplementation` (`app-agent/build.gradle.kts:35`), so writing
to that module directly is not available to scheduled runs.

### 1.2 Decision

- **Port in `app-api`**, package `io.justsearch.app.api.operations`, one interface
  (working name `DurableOperationStore`; the implementer checks the name against the
  `forbiddenReintroduction` regexes in `governance/operation-surfaces.v1.json` and the registered
  `admission-policy` seam before fixing it). The same shape C1 used for `EngineAdmissionService`:
  interface in app-api, one implementation, the root binds it.
- **SQLite implementation in `app-observability`.** That module already owns the surface C2-4
  makes durable (`OperationHistoryStore`) and already holds a durable AUTHORED store with its own
  register row (`ActionEventJournal`). SQLite needs `java.sql` plus a `runtimeOnly` driver, the
  worker-core precedent (`worker-core/build.gradle.kts:29`, `EntityClusterStore.java:48-58`).
- **Rejected homes.** `app-engine`: a wiring module under a size ratchet (design 10). `worker-core`
  or `indexer-worker`: invisible to app-services and app-agent, and the owner would read `WORKER`
  against the C2-1 decision. A new module: nothing app-observability lacks.
- **Construction.** `HeadlessApp` opens the store at the point where the admission reference
  exists (`HeadlessApp.java:1191`), before the asynchronous index fork, so a boot scan cannot
  outrun its own acceptance row. The store is handed to `HeadAssembly` and to `EngineRoot`; the
  index half receives the same instance. `LauncherEnvironment` opens it through the same factory
  (app-launcher sees app-observability through app-services' `api` dependency), so no ServiceLoader
  provider is needed; the trade-off is recorded because the admission SPI needed one only because
  its implementation sits in app-engine.
- **Close.** A dedicated ordered shutdown step placed **after** the index half. The `head-assembly`
  step precedes the index half in `HeadlessApp.orderedShutdownSteps` (step ids
  `runtime-manifest, operation-admission, interactive-work, shutdown-request-watcher, local-api,
  worker-health-monitor, head-assembly, <index half>, tracing, telemetry, executor-registry,
  app-instance-lock`), and the worker drain inside the index half writes the last checkpoints.
  C2-7's `durable-operations-checkpoint` step sits before the index half; the close step sits
  after it. Neither is inside the index-half step, so a failure is attributed to the right owner.
- **Verification profile.** D2's ephemeral store axis gains this store as its fifth SQLite file;
  C2 ships the path-taking constructor only.

### 1.3 Schema v1

`operations.db`, WAL, `synchronous = NORMAL` (process-crash durable, Q6), `busy_timeout`,
`user_version` ladder with future-schema refusal leaving the bytes untouched
(`EntityClusterStore.initSchema` shape). Single connection behind one lock.

`operations`:

| column | meaning |
|---|---|
| `id INTEGER PRIMARY KEY AUTOINCREMENT` | the ordering stamp (Q1) |
| `operation_key TEXT NOT NULL UNIQUE` | canonical lowercase UUIDv7 (section 2) |
| `kind TEXT NOT NULL` | `operation`, `reconfigure`, `reindex`, `ingest`, `settings-apply`, `accept-gaps`, `scheduled-run` |
| `survival`, `urgency TEXT NOT NULL` | 3.4's two axes, as admitted |
| `state TEXT NOT NULL` | `ACCEPTED`, `RUNNING`, `COMPLETE_WITH_GAPS` (non-terminal, reindex only), `COMPLETE`, `FAILED`, `CANCELLED` |
| `phase TEXT` | reindex only: `building`, `replaying`, `awaiting_acceptance`, `activating` *(D1)* |
| `operation_ref TEXT` | catalog operation id for dispatched kinds, else null |
| `identity_json TEXT NOT NULL` | the declared dependency set (7.5): root set plus generation for `ingest`; captured plan plus target generation for `reindex`; the operation id and argument hash for `operation` |
| `grant_ref TEXT` | re-resolved at resume (7.5 authority) |
| `client_kind`, `client_id`, `session_id`, `source_tier`, `transport`, `executor`, `initiator`, `correlation_id` | the provenance projection the history entry needs; the signed intent token is never persisted |
| `checkpoint_cursor TEXT`, `units_completed INTEGER`, `units_failed INTEGER`, `attempts INTEGER` | resume state |
| `accepted_at`, `started_at`, `updated_at`, `completed_at INTEGER` | Engine clock, epoch ms |
| `urgency_detached_at INTEGER` | C1-11's flip, recorded here |
| `failure_reason TEXT`, `failure_detail TEXT` | reason code plus bounded detail |
| `result_json TEXT` | bounded (4 KB) result summary so a same-key retry answers faithfully; `execution_id` inside it for undo linkage |
| `accepted_settings_revision INTEGER` | `settings-apply` and `reconfigure`: the revision the call was issued against |
| `building_generation_id TEXT`, `target_settings_json TEXT` | reindex: the journal identity and the generation-bound desired values *(D1)* |
| `gaps_json TEXT`, `processing_history_json TEXT`, `processing_history_counts_json TEXT` | reindex: the full gap list; a capped sample (200) plus counts of failed and superseded units |
| `gaps_accepted_at INTEGER`, `gaps_accepted_by TEXT`, `gaps_list_hash TEXT` | written by the `accept-gaps` operation; a stale list is refused by hash *(D1)* |
| `journal_replayed_entries INTEGER` | evidence stamp at reindex completion *(D1)* |
| `superseded_from INTEGER` | successor ingest row: the ingest row it replaces *(D1)* |

`operations_meta` (one row): `history_since_ms`, `created_at_ms`, `schema_note`.

Indexes: unique on `operation_key`; `(state)` for owner reconciliation; `(completed_at)` for
retention; `(kind, state)` for the reindex and ingest reconcilers.

The reindex journal is **not** here. It is the widened `switch_buffer` in `jobs.db`, keyed by
`building_generation_id` (design section 0, D1 row 2026-09-09; the D1 agent's re-grounding
sharpens it). Nothing in v1 reserves room for a journal table.

### 1.4 The port

```
accept(key, descriptor, context)      -> Accepted(id) | Existing(outcome)   // fail-closed
start(id); checkpoint(id, cursor, unitsCompleted); detachUrgency(id)
complete(id, resultSummary); fail(id, reason, detail, unitsCompleted); cancel(id, reason)
lookup(key)                            -> accepted | running(phase, units) | complete | failed | unknown | expired
recent(n), recentSince(id)             -> the history projection
registerReconciler(kind, reconciler); reconcile(kind)   // owner-scoped, section 1.6
prune(now)                             -> rows evicted; advances history_since
```

`accept` is `INSERT ... ON CONFLICT(operation_key) DO NOTHING` followed by a read in one
transaction, so two concurrent same-key calls produce one execution and the loser receives the
in-progress outcome. A failed write throws and the caller must not proceed
(`putSwitchBufferOrThrow` shape). The client key is an explicit parameter at every seam and
never a field on `EngineContext`.

### 1.5 Which calls get a row

- **Dispatched catalog operations.** The executor writes the row for every call, never the
  handler. The row's kind is a declaration on the catalog `Operation`: a new `recordKind` policy
  field with the closed vocabulary above, default `operation`. The kind fixes the row's survival
  (`reindex` durable; `reconfigure`, `accept-gaps`, `operation` interactive unless the context
  says durable for `operation` only). The handler receives an `OperationRecordHandle` (id,
  checkpoint, result, complete, fail) through the dispatch context and never calls `accept` for
  its own row; it may accept child rows under fresh Engine-minted keys (the successor ingest row).
  A row is written for every keyed call, and for every unkeyed call whose `AuditPolicy` is not
  `NONE`, which reproduces today's history exactly; audit suppression never suppresses a keyed
  acceptance. *(D1's "one row per dispatch" item.)*
- **Ingestion.** One row per walk: an ingest request, a forced rescan, or a boot recovery walk
  for a root with no open row. The 60-second periodic sync is maintenance and gets no row. On
  boot, a `RUNNING` ingest row for a root is resumed by the recovery walk, never re-minted. The
  Head side accepts before the port call into the worker and passes the record id down as the
  scan id (the hollow `scanId`, C2.md I5). A successor ingest row at activation is accepted
  from the index half with an Engine-minted key, client kind `INTERNAL`, survival `DURABLE`,
  urgency `BACKGROUND`, identity = root set plus Green's generation id, and `superseded_from`
  *(D1)*.
- **Scheduled runs.** `ACCEPTED` at schedule time, `RUNNING` after admission at timer fire, an
  admission or executor refusal at fire time becomes `FAILED` with the typed reason (closes the
  C2 scheduled-outcome dependency).
- **Settings apply.** `settings-apply` at C2 (the controller); `reconfigure` at D1 (the handler).

### 1.6 Reconciliation at boot, owner-scoped

The store owns no kind-specific logic. Each owner registers a reconciler for its kinds and runs
it when that owner becomes ready, not at a single store-level boot pass, because the reindex
witness needs `state.json` and the index half may come up after the Head, or not at all. A row
whose owner is not ready stays exactly as it is and the outcome query reports it truthfully.

The store's one built-in rule: an open row with `survival = interactive` and no reconciler
verdict is marked `FAILED` with reason `interrupted_by_restart`. Durable rows are never touched
by the default.

Registered reconcilers *(D1, cited here for the row shapes they need)*: reindex (pointer equals
`building_generation_id` and a successor ingest row exists in `ACCEPTED` or `RUNNING` advances the
row to `COMPLETE`; pointer equals building id with no successor row is `FAILED`
`SUCCESSOR_ROW_MISSING`, reported, never fabricated; pointer still Blue resumes replay); ingest
(a row whose generation is not the active pointer becomes `COMPLETE` with reason
`superseded_by_generation`); reconfigure (a `RUNNING` row becomes `FAILED`
`ENGINE_RESTARTED_DURING_APPLY`, overriding the default). Cancel or abandon of a reindex cancels
an `ACCEPTED` successor row on the path that abandons the generation.

### 1.7 Activation order *(D1)*

1. Accept the successor ingest row (`ACCEPTED`, generation = Green's building id).
2. `state.json` pointer swap (the effect).
3. In-memory runtime re-point.
4. Start Green's ingest loop; the successor row goes `RUNNING`.
5. Complete the reindex row (`journal_replayed_entries` stamped).
6. Mark the old ingest row `COMPLETE` with reason `superseded_by_generation`.

Nothing shares a transaction; the effect is an atomic file move. Acceptance precedes effect at
step 1, and "pointer swapped, successor missing" is unreachable.

### 1.8 The accepted-settings revision port *(D1)*

C2-6 is an `app-api` port with one atomic call: `apply(expectedRevision, settings)` returns the
new revision or refuses `VERSION_CONFLICT`. The revision is a field in the settings document
written in the same atomic file write as the settings, distinct from `UiSettings.version` (a
schema-migration integer), and exposed on GET as well as on the POST success body. At C2 the
controller calls it; at D1 the reconfigure handler calls it on the success path after the last
component composes, and restart-required keys use the same call at their persist. The
`settings-apply` row stores the expected revision; its result carries the new one.

## 2. The key, retention, and the outcome boundary

### 2.1 The gap

With random v4 keys and rows evicted at 30 days or the row cap, a missing key is
indistinguishable: never accepted, or accepted and evicted. Tombstones only move the boundary.

### 2.2 Decision

- **Every operation key is a UUIDv7, validated on ingress.** Version nibble 7, RFC variant,
  canonical lowercase. A key whose embedded time is later than now plus the margin is refused
  as `OPERATION_KEY_INVALID`: a far-future key would never expire and would defeat the boundary.
  Non-v7 keys are refused with the same code. Internal producers mint v7 keys server-side. A
  write arriving with no key gets an Engine-minted key returned in the response; the recovery
  contract holds only for client-chosen keys. No production caller sends a key today
  (`OperationClient.ts:193` forwards an optional field; `KnowledgeIngestRequest` has only
  `paths`), so this is not a compatibility break.
- **Lookup order.** Row first, always. Only a missing key consults `history_since`: `expired`
  if the key's embedded time is earlier than `history_since + margin`, else `unknown`.
- **Margin direction.** The margin errs toward `expired`. A client clock running ahead is the
  only case that could turn an evicted key into `unknown` and license a duplicate; a false
  `expired` claims nothing and the client mints a new key. Margin: 5 minutes. Clients are loopback
  today; a paired-device ADR revisits the value.
- **`history_since` is monotonic and advances to the newest evicted acceptance.** Retention
  prunes terminal rows whose `completed_at` is older than 30 days. The row cap (100 000) evicts
  the oldest terminal rows first. After either, `history_since := max(history_since,
  max(accepted_at of the evicted rows) + 1 ms)`. Every evicted key was minted no later than it
  was accepted, so it now answers `expired`, and every surviving row is still found by lookup.
  The oldest-survivor rule is wrong and rejected: a long-running row can survive while newer
  terminal rows between it and now are pruned, and a key in that gap would answer `unknown`.
- **Restore transitions.** Corruption quarantine (the `handleCorruptDatabase` shape, minus a
  backup copy: `operations.db` has none) sets `history_since := now`. The encrypted-backup
  import of tempdoc 629 covers the AUTHORED catalog only, so it never touches this store. An
  external file-level rollback is undetectable from the file alone and is declared outside the
  contract; the register row says so.
- **Invoke with an expired key** is refused `OPERATION_KEY_EXPIRED` (class `CONFLICT`). The
  Engine cannot rule out a prior effect, so the client mints a new key and owns the duplication
  risk, as 7.6 already states for new keys.
- **Cap reached with only non-terminal rows.** Acceptance is refused with a typed capacity code
  rather than evicting resumable work. Attempts budget: 3.

### 2.3 Rejected alternatives

A separate `issuedAt` field is a second thing every client must persist beside the key.
Accepting v4 and returning `history_since` for the client to compare spreads the judgment across
four client implementations, and the webview loses its keys on reload anyway. Bounded tombstones
add a mechanism and keep the boundary.

## 3. What the outcome query looks like

`GET /api/operation-history/{operationKey}` answers
`{ state, phase?, historySince, acceptedAt?, completedAt?, unitsCompleted?, unitsFailed?,
reason?, result? }` with `state` in `accepted | running | complete | failed | unknown | expired`.
`COMPLETE_WITH_GAPS` projects as `running` with `phase: awaiting_acceptance` and the gap list in
`result`; `CANCELLED` projects as `failed` with reason `cancelled`. The wire answer set does not
grow. An MCP tool exposes the same query. The new route triggers the live re-capture obligation
in `stages/C2.md` section 5. `GET /api/operation-history` and its SSE stay as the projection
over terminal rows; the reconnect window becomes the retention window.

## 4. Corrections found by this pass (for `stages/C2.md` section 0.2)

1. C2.md I11 still names the `withContext` bound view; C1 shipped required `EngineContext`
   parameters (design 3.4, C1-3).
2. `KnowledgeIngestRequest(List<String> paths)` has no key slot; C2-3 adds one.
3. The ordered shutdown runs `head-assembly` before the index half; a Head-owned store the
   worker drain writes must close in its own step after the index half.
4. app-agent's dependency on app-observability is test-only; the port must be in app-api.
5. C1's `EngineContext` carries an optional in-process `workId`; the client key is a separate
   explicit parameter and is never derived from it.
6. The D1 row of 2026-09-09 already fixed the journal as the widened `switch_buffer`; 17.4's
   "a journal table beside the operations table" is the older sentence and reads as superseded.

## 5. Acceptance additions specific to these decisions

- Key rule: v4 refused; future key refused; boundary `expired` versus `unknown` on both sides
  of `history_since + margin`; a concurrent same-key pair yields exactly one execution.
- `history_since`: monotonic under retention, under cap eviction, and under quarantine; the
  long-running-survivor case (a row older than evicted rows) still answers `expired` for the
  evicted keys.
- Lifetime: the store opens before the async fork (a boot scan writes a row); the close step
  runs after the index half and a checkpoint written during the drain lands.
- Boot: an open interactive row becomes `failed: interrupted_by_restart`; a durable row is
  untouched until its owner reconciles; a row whose owner never becomes ready stays as it is.
- Module: only app-observability implements the port; worker-services, indexer-worker and
  app-agent reach it through app-api only (ArchUnit).
- `recordKind`: a dispatched `core.bulk-reindex` produces exactly one row of kind `reindex`.
