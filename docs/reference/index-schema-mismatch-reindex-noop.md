---
title: "Troubleshooting: Reindex doesn't run (schema mismatch)"
type: reference
status: stable
updated: 2026-09-03
description: 'Diagnosing "reindex doesn''t run" due to schema mismatch.'
---

# Troubleshooting: Reindex doesn't run (schema mismatch)

> **SUPERSEDED (2026-09-03, tempdoc 915).** This document's central claims — "the commit-metadata
> parity guard never enforces" and "do not fix this by enabling the guard" — describe a state that no
> longer exists. Tempdoc 915 removed the two unconditional `allow_mismatch=true` set-sites in
> `HeadlessApp.java` that caused the never-enforces behavior, and replaced the over-triggering
> whole-catalog-file fingerprint with `index_fingerprint`, a fingerprint over the effective *physical*
> index shape (annotation-only edits like `rmwPolicy` no longer flip it). **The parity guard now
> enforces**, the production default policy is `BLUE_GREEN_MIGRATE`, and a repeat-rebuild brake bounds
> auto-rebuild attempts. See `docs/explanation/11-index-schema-migration.md` for the current design
> and enforcement status. This page is kept for historical troubleshooting context (the `FieldInfos`
> detector section and the general "reindex looks like a no-op" symptom description below remain
> accurate); the specific "not enforced" / "advisory only" claims below are marked inline as
> superseded rather than deleted.

If a reindex appears to “do nothing” even though the UI/API returns success, schema mismatch is a common cause.

For the overall migration architecture, see `docs/explanation/11-index-schema-migration.md`.

## Problem: “Reindex doesn’t run” (but the button/API returns 200)

In the browser UI (and via `POST /api/indexing/reindex`), a reindex can appear to “do nothing” even though:

- The HTTP endpoint returns success (e.g. `{"status":"reindex triggered"}`).
- `/api/status` may briefly show `indexState=INDEXING` with `pendingJobs>0`, then quickly return to `IDLE`.
- The indexed document count may stay constant.
- The Library root’s `lastIndexed` timestamp may update even though content wasn’t re-processed.

### What’s actually happening

The reindex request *does* enqueue jobs. The worker then attempts to index the files but fails each job at write time because the **on-disk Lucene index was created with an older field schema** and is no longer compatible with the current code’s field mapping.

The “tell” is in the Engine log — since lane F stage A there is no separate `worker.log`; the index half logs into the one Engine log (dev: `modules/ui-web/.dev-data/logs/engine.log`, desktop: `%LOCALAPPDATA%/JustSearch/logs/engine.log`):

- Example failure:
  - `IllegalArgumentException: cannot change field "mime" from index options=NONE to inconsistent index options=DOCS`

Lucene requires that a field’s schema (index options, docvalues type, etc.) remains consistent across segments. If we change how a field is mapped (e.g., a keyword field becomes indexed for filtering), Lucene will reject updates for that field.

### Why it looks like a “no-op” in the UI

- Jobs that fail repeatedly are eventually marked `FAILED` and stop contributing to `queue_depth`.
- Even with better status reporting (we now expose `failed_count` / last failure in `/api/status`), users may still interpret “queue drained” as “success” unless we make failures and migration lifecycle very explicit.

So the UX becomes: “reindex succeeded” + “queue drained” + “docs unchanged”.

## Current fix (implemented): startup schema-mismatch guard + explicit policy

We added a **schema compatibility check at Lucene runtime startup**:

- It opens the existing index read-only and inspects Lucene `FieldInfos`.
- It compares observed field schema to the schema implied by the current `FieldMapper` / `FieldCatalogDef` rules (for the keyword/docvalues fields that were triggering failures).
- If a mismatch is detected, it throws an `IndexRuntimeIOException` with `Reason.SCHEMA_MISMATCH` (not `CORRUPT_INDEX`).

What happens next is **explicitly policy-controlled** via `index.schema_mismatch.policy`:

- `FAIL_CLOSED`: fail startup and surface a deterministic “schema mismatch / migration required” error via `/api/status` (Head carries the Worker start error string). **[Superseded 2026-09-03, tempdoc 915]** This is no longer the production default — `ResolvedConfigBuilder.normalizeSchemaMismatchPolicy` now resolves production to `BLUE_GREEN_MIGRATE` (dev to `REBUILD_BACKUP_FIRST`), and the fingerprint detector now enforces (see `docs/explanation/11-index-schema-migration.md`); `FAIL_CLOSED` remains available as an explicit opt-in.
- `REBUILD_BACKUP_FIRST` (convenient in dev): rename the index directory to a `.bak-*` backup and rebuild a fresh empty index (backup-first, guarded).
- `BLUE_GREEN_MIGRATE` (availability-first): start Blue (existing active generation) in **read-only** mode for search, build Green in a fresh generation directory, then cut over by swapping `state.json` and restarting the Worker.

### Current `BLUE_GREEN_MIGRATE` behavior (MVP, as of 2025-12-15)

- **Build verification**:
  - The Worker stamps Lucene commit metadata key `build_state` (`BUILDING|COMPLETE`).
  - Cutover verifies `build_state=COMPLETE` and `index_fingerprint` (renamed from `index_schema_fp`,
    tempdoc 915) before swapping `state.json`.
- **Cutover fence (`SWITCHING`)**:
  - The Worker enters `SWITCHING` near the end of migration (a small “quiesce + buffer” window) and enforces a `SWITCHING` deadline; if it can’t drain, it marks the migration `FAILED` (no pointer swap).
  - Failed indexing jobs do **not** block auto-cutover by default (failures are surfaced via status as `failed_count` / unhealthy).
    - Optional guardrail: set a failure budget to block auto-cutover and keep Blue active:
      - env: `JUSTSEARCH_INDEX_MIGRATION_CUTOVER_MAX_FAILED_JOBS`
      - sysprop: `-Dindex.migration.cutover.max_failed_jobs=<N>`
    - Nuance: “file not found” jobs are treated as **deletes**, not FAILED, to avoid counting benign races as failures.
- **What is buffered during `SWITCHING` (durable, Worker-side)**:
  - `submitBatch`, `deleteById`, `deleteByPath`
  - VDU mutations: `updateVduResult`, `markVduProcessing`, `recoverVduProcessing` (buffered as `VDU_RECOVER_PROCESSING`)
  - `syncDirectory(force=true)` is buffered as `SYNC_ROOT(root, force)`
  - These are stored durably in `jobs.db` (`switch_buffer`) and replayed after the Worker restarts on the new active generation.

## What is actually enforced today (2026-08, superseded 2026-09-03)

This section records the difference between the design above and the shipped runtime, **as it stood
during the sandbox round-10 investigation (tempdoc 804 §D1) and before tempdoc 915**. It is retained
for historical troubleshooting context; every bullet below is now false as of tempdoc 915 and is
struck accordingly. Current state: `docs/explanation/11-index-schema-migration.md` §Enforcement status.

- ~~**The commit-metadata parity guard never enforces.** `HeadlessApp.java:267` (`setupInfra`) and `HeadlessApp.java:607` (the sidecar entry) both set `justsearch.index.parity.allow_mismatch=true` **unconditionally** — no dev/prod condition — and `WorkerSpawner` forwards `INDEX_PARITY_ALLOW_MISMATCH` into the Worker JVM. So `IndexMetadataParityGuard.checkOnOpen()` logs `Parity mismatch detected but justsearch.index.parity.allow_mismatch=true; continuing in WARN mode.` and returns. The `SCHEMA_MISMATCH` it would otherwise raise on a rebuild-requiring key (`analyzer_fp` / `schema_ver` / `index_schema_fp`, `ParityDiagnostics.REBUILD_REQUIRING_KEYS`) is never raised, so `index.schema_mismatch.policy` never acts on a fingerprint mismatch. Treat “`FAIL_CLOSED` is shipped production enforcement” as **false** for the fingerprint path.~~ **[Superseded]** Both `HeadlessApp.java` set-sites were deleted by tempdoc 915. `justsearch.index.parity.allow_mismatch` now survives only as an explicit operator escape hatch that nothing sets by default. `IndexMetadataParityGuard.checkOnOpen()` now raises `SCHEMA_MISMATCH` on an `index_fingerprint` diff (the sole rebuild-requiring key, after `analyzer_fp` / `schema_ver` / `index_schema_fp` were folded into it), and `index.schema_mismatch.policy` acts on it.
- **Still live**: the `FieldInfos` inspection in `ComponentsFactory` (an index that genuinely cannot accept writes under the current field mapping) raises `SCHEMA_MISMATCH` on its own and *is* routed through the policy. That is the detector this document’s “Problem” section is about. (This bullet is still accurate — unaffected by tempdoc 915.)
- ~~**The schema fingerprint is advisory, not a search gate.** `index_schema_fp` is the SHA-256 of the canonical `SSOT/catalogs/fields.v1.json` (`SsotCommitMetadataSource.java:81-93`, plus a vector-dimension override when set), compared in `IndexStatusOps.safeSchemaCompatState()`. Nothing on the query path reads the schema compat state: the dense leg is gated by the **embedding** fingerprint (`EmbeddingCompatibilityController.allowQueryEmbeddings()`, consumed at `SearchPlanner.java:87`). An index can report `schema BLOCKED_MISMATCH` and serve fully hybrid (semantic + keyword) results — round 10 reproduced exactly that.~~ **[Superseded]** The query-path claim (nothing reads schema compat state; dense leg gated by the embedding fingerprint only) is still architecturally true. But the fingerprint itself is no longer a whole-catalog-file content hash — `index_fingerprint` hashes only the effective physical index shape (`IndexFingerprint.java`) — and a diff on it now raises `SCHEMA_MISMATCH`, which the enforcing guard acts on (it is not merely advisory any more).
- ~~**The fingerprint over-triggers.** Because it is a content hash of the whole catalog file, *any* byte edit flips it — including annotation-only edits with no physical index consequence. The three post-v0.1.0 catalog edits (one dead-field deletion, three `rmwPolicy` annotations) each flipped `index_schema_fp` for a v0.1.0 index that was, physically, still perfectly compatible.~~ **[Superseded]** This was the specific defect tempdoc 915 fixed. `index_fingerprint` deliberately excludes `rmwPolicy` field annotations (they cannot describe a stored/doc-values field by construction, so they never affect bytes on disk) and query-time scoring config — see `docs/explanation/11-index-schema-migration.md` §Index fingerprint for the full input/exclusion list.
- ~~**Do not “fix” this by enabling the guard.** Turning `allow_mismatch` off (or forcing `FAIL_CLOSED`) with today’s fingerprint would have refused to start on a fully working index. The truthful fix is a fingerprint over *physical* schema (or per-field consequence classes), which is not implemented; until then the schema state must be presented as advisory. The UI treats it that way as of tempdoc 804 (`index.schema_mismatch` is `info`-severity and is not in the frontend’s reindex-cause bucket).~~ **[Superseded]** The truthful fix this bullet called for — a fingerprint over physical schema — is what tempdoc 915 implemented (`index_fingerprint`). The guard is now on by default in production (`BLUE_GREEN_MIGRATE`); enabling it was correct once the fingerprint stopped over-triggering.

## Tradeoffs of the current fix

- **Fail-closed can reduce availability**: `FAIL_CLOSED` means the Worker may not start (search downtime) unless you opt into rebuild or blue/green migration. **[Superseded 2026-09-03]** The "unjustified on the common case" premise no longer holds — `index_fingerprint` (tempdoc 915) does not over-trigger the way the old catalog-file hash did, and the parity detector is no longer forced into WARN mode by default; production resolves to `BLUE_GREEN_MIGRATE`, which sidesteps the `FAIL_CLOSED` availability cost entirely by design.
- **Backup-first rebuild is still destructive to the active index** (but preserves a backup): rebuild time can be large.
- **Blue/green migrate increases complexity**: it relies on generation state (`state.json`) and a cutover step; during migration search can be briefly stale (Blue) while writes go to Green.
- **Partial coverage**: this check targets the class of failures we saw (keyword index options / docvalues mismatches). Other incompatibilities may still require additional guards or fingerprint parity to catch earlier.

## Additional codebase nuance

- ~~**Commit-metadata parity is wired but currently warn-only in every run**~~ **[Superseded 2026-09-03, tempdoc 915]**:
  - The parity guard wiring/ordering has been fixed so it checks the effective on-disk index path. (Still accurate.)
  - ~~Parity mismatches are warn-only whenever `justsearch.index.parity.allow_mismatch=true` — which the Head sets unconditionally today (`HeadlessApp.java:267` / `:607`), so this is the shipped behavior, not a dev/demo opt-in.~~ The Head no longer sets `allow_mismatch` anywhere; it survives only as an explicit operator escape hatch. See [What is actually enforced today](#what-is-actually-enforced-today-2026-08-superseded-2026-09-03) above and `docs/explanation/11-index-schema-migration.md` §Enforcement status for current behavior.
  - Legacy indexes may still require the `FieldInfos` inspection fallback when commit metadata is missing. (Still accurate.)
- **`/api/status` no longer probes Lucene files in the Head process**:
  - Index availability and failures are surfaced via Worker-reported status + explicit Worker startup error capture. (Still accurate.)
- **Dev defaults matter** — **[Superseded 2026-09-03, tempdoc 915]**:
  - ~~A production profile resolves `index.schema_mismatch.policy=FAIL_CLOSED` by default — but only the `FieldInfos` detector can reach it today (the fingerprint detector is warn-only, above).~~ A production profile now resolves `index.schema_mismatch.policy=BLUE_GREEN_MIGRATE` by default, and the fingerprint detector (`index_fingerprint`) enforces.
  - Dev/demo resolves `REBUILD_BACKUP_FIRST` by default (unchanged — a developer wants the fast rebuild).
  - `BLUE_GREEN_MIGRATE` is available everywhere as an explicit override for “no search downtime on mismatch”.


