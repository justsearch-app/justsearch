---
title: "ADR-0028: Scoped Reverse Path-Hash Lookup"
type: decision
status: accepted
description: "Refines the tempdoc-410 ingestion-ledger privacy contract. Raw paths still never appear in any operator-visible export, telemetry stream, or support-bundle generation. They may now be returned by a single dedicated Worker RPC reachable only from one HTTP endpoint, invoked in direct response to a user's 'show filename' click in the local UI. Backed by a new internal-only `path_resolution` table and an ArchUnit guard."
date: 2026-04-26
probes:
  - adr-0028-one-http-resolver-caller
  - adr-0028-document-identity-path-free
last_reviewed: 2026-09-03
---

# ADR-0028: Scoped Reverse Path-Hash Lookup

## Status

**Accepted (2026-04-26).** Implementation lands in tempdoc 419 substrate
slice T5 (T5.1–T5.6).

## Context

Tempdoc 410 §8 + Slice E shipped an ingestion ledger whose privacy
contract (`docs/explanation/03-knowledge-server.md` §"Ingestion Ledger
Privacy Contract") states:

> Any operator-visible export of ledger or queue data carries a
> `path_hash` (SHA-256 over the normalized absolute path), never the
> raw path, and never any path-derived field that could reverse-map to
> the user's filesystem.
>
> [...] `path_hash` is one-way. To correlate a flagged event back to a
> specific file, operators hash candidate paths themselves [...] There
> is no reverse lookup; the system intentionally does not maintain a
> hash-to-path table.

The contract was designed for the *operator* persona — someone reading
diagnostics for support or telemetry analysis, where leaked paths would
be a real privacy hazard. It works.

Tempdoc 419 (Unused User / AI-Agent Capability Discovery) recommended
WP1 (Library Indexing Activity panel) as the highest-value
productization of the ledger. That panel raises a different persona's
question: the user looking at their own machine, asking "*which file
failed?*" The current contract gives no answer — the user sees a
64-character hash with no way to map it back to a filename, even though
the file is on their own filesystem and they have full read access.

This ADR refines the contract to distinguish **exportable surfaces**
(where the original prohibition holds absolutely) from **owner-display
surfaces** (where a scoped, user-initiated reverse lookup is
permitted).

## Decision

### Refined invariant

> Raw paths from the indexed corpus never appear in any
> **operator-visible export**, telemetry stream, or support-bundle
> generation. They may be returned by a dedicated `LookupPathByHash`
> Worker RPC reachable only from a single HTTP endpoint
> (`POST /api/library/resolve-hash`) invoked in direct response to a
> user's "show filename" click in the local UI.

### Mechanical enforcements

1. **`path_resolution` table.** A new SQLite table in `jobs.db`
   (alongside `ingestion_ledger`) stores `(path_hash, normalized_path,
   last_seen_at, removed_at)`. It is the only persistent place where
   raw paths are stored after admission. It is never marshaled into
   `IngestionEventView`; the existing structural pin
   (`JobQueueTest.ingestionEventViewExportContractIsPinned`) continues
   to enforce that.

2. **`LookupPathByHash` RPC.** A new RPC on the existing `IngestService`
   gRPC service. *(Since lane F stage A this is `IngestServiceCalls#lookupPathByHash`,
   an in-process port call — the RPC and the gRPC service it sat on are deleted, item A14. The
   single-caller property this ADR decides is unchanged and is still pinned by
   `adr-0028-one-http-resolver-caller` plus `LibraryResolveHashOnlyCallerPin`.)*
   Returns `Optional<Path>` semantics:
   - `found=true, path=<...>` if the hash maps to a file currently
     under a watched root, not marked removed, and within retention.
   - `found=false` otherwise (path was removed and retention expired,
     root was unwatched, or hash was never seen by this Worker).

3. **One-endpoint exemption.** The new endpoint
   `POST /api/library/resolve-hash` is the *only* HTTP caller of
   `LookupPathByHash`. The diagnostic export endpoints
   (`/api/diagnostics/ingestion/recent`, `/summary`, and any future
   `/api/diagnostics/export`) **must not** call this RPC.

4. **ArchUnit-enforced separation.** A new test
   `LibraryResolveHashOnlyCallerPin` (in `modules/app-launcher`)
   asserts that no class in the diagnostic export call tree
   transitively depends on `PathResolutionStore` (the worker-side
   class that backs `LookupPathByHash`). The test fails the build if
   the export endpoints accidentally start resolving hashes.

5. **Lifecycle and retention.**
   - On file admission (any successful or partial ingestion outcome),
     the worker calls `pathResolutionStore.record(pathHash,
     normalizedPath)` alongside the existing ledger write.
   - On observed file deletion (via `WorkerMethvinWatcher` DELETE
     events or `deleteByPath` flows), the worker marks
     `removed_at = now`. Rows are **not** deleted at this point so
     the user can still see "this file was deleted on X" for
     recently-deleted entries in the activity panel.
   - Periodic prune (in the existing job-cleanup periodic task in
     the indexing loop) removes rows where `removed_at + retention
     window < now`. The retention window defaults to **90 days**,
     overridable via `JUSTSEARCH_PATH_RESOLUTION_RETENTION_DAYS`.
   - When a watched root is unwatched (via
     `RootWatcherRegistry.unwatch`), all `path_resolution` rows
     under that root prefix are pruned **immediately**, regardless
     of retention. Rationale: an unwatched root is effectively
     "out of scope"; resolver responses for those hashes would
     return `found=false` anyway, so retaining the rows serves no
     user purpose and only enlarges the table.

### Consequences

**Positive:**

- The most natural product question in the activity panel ("which
  file failed?") gets an honest answer for files still under watched
  roots.
- The operator-export privacy property is **preserved by mechanical
  test**, not by promise. A future bug that leaks paths into a
  diagnostic endpoint fails the ArchUnit guard at build time.
- The retention window provides a clean answer to "this file was
  deleted last week" — surfacing useful audit information without
  unbounded table growth.
- The resolver is stateless from the caller's perspective: the only
  inputs are `pathHash` and the worker's own state. No caller-side
  caching of resolved paths is required (or recommended; see Frontend
  Implementation Guide).

**Negative:**

- A new table grows roughly proportionally to the number of files
  ever indexed, capped by retention. For a 100K-document corpus with
  90-day retention, expect ~10–20 MB of table data plus index. Not a
  scaling concern at desktop-app scale.
- The contract sentence "There is no reverse lookup" must be rewritten
  to "There is no reverse lookup *in any export path*; the scoped
  resolver at `POST /api/library/resolve-hash` is the only exception,
  governed by ADR-0028." This is a real, written-promise change. The
  doc update lands in the same slice as the code (T5.6).
- A new failure mode appears: "which file is this hash?" → "I don't
  know anymore." The UI must handle this honestly (see Frontend
  Implementation Guide, `docs/how-to/library-indexing-activity-panel.md`).

**Neutral:**

- Worker process memory and disk usage both grow modestly. Negligible
  at desktop-app scale; would warrant revisiting if JustSearch ever
  served multi-tenant deployments.
- The ArchUnit test adds ~1 second to the system-tests run.

## Alternatives considered

### Alt-1: keep the contract absolute (V1 behavior)

Status quo. Operator-export safety is maximal. The activity panel's
"which file?" question stays permanently unanswered. The user has to
navigate to the file via the Library tree manually.

**Rejected because:** the operator persona's needs and the
local-machine user persona's needs are genuinely different. Punishing
both with the more restrictive contract solves a problem only the
operator persona has, while leaving the user persona's most natural
question unaddressed.

### Alt-2: scoped resolver but no retention; immediate prune on file delete

Same as the accepted decision but `removed_at` triggers an immediate
delete rather than a 90-day grace period.

**Rejected because:** the activity panel's value is highest right
*after* a deletion ("oh, that's the file I just deleted, I expected
it to disappear from the index"). Immediate prune kills that
explanation. Retention adds modest cost for real audit value.

### Alt-3: never prune `path_resolution`; let it grow forever

Same as the accepted decision but no retention window.

**Rejected because:** unbounded growth is a footgun for long-running
desktop installs, and the marginal value of seeing "this file was
deleted 18 months ago" is negligible.

### Alt-4: store the full path on `ingestion_ledger` directly; add an export-time redaction layer

Store `normalized_path` in the ledger row; the diagnostic export
endpoints redact it on the way out via a sanitizer.

**Rejected because:** redaction-at-export is a *promise* enforced by
code review, not a *property* enforced by structural test. A bug that
forgets to apply the sanitizer leaks paths silently. The accepted
design encodes the separation as a class-level dependency that
ArchUnit can prove absent.

### Alt-5: per-root user toggle for whether resolution is enabled

Each watched root has an "allow filename display" flag the user sets.
Only roots with the flag set are queryable by the resolver.

**Rejected because:** desktop-app users already expressed consent by
adding the root in the first place. A second consent gate adds UI
complexity without proportional privacy gain. Could be added later as
an enhancement if a multi-user shared-machine scenario emerges.

## Verification

Before T5.1–T5.6 lands, the following must be true:

1. The structural pin
   `ingestionEventViewExportContractIsPinned` continues to pass
   unchanged (the export shape doesn't gain new fields).
2. The new ArchUnit pin `LibraryResolveHashOnlyCallerPin` exists and
   would fail if any export-path class transitively depended on
   `PathResolutionStore`.
3. The wire-level integration test in T6.4 ingests a corpus, calls
   `POST /api/library/resolve-hash` for one of the resulting hashes,
   asserts the path is returned, then calls
   `GET /api/diagnostics/ingestion/recent` and asserts NO path
   strings appear in the response (only hashes).

## Migration

Schema migration `MIGRATE_V6_TO_V7_ADD_PATH_RESOLUTION` adds the
`path_resolution` table to existing `jobs.db` files. The table starts
empty; existing ledger entries do **not** get back-populated. The
resolver returns `found=false` for any hash from before the migration
date — the worker only learns the path when a future scan or watcher
event observes the file again.

This is acceptable because:
- The activity panel value is forward-looking ("what's happening
  now?"), not historical.
- A back-population walk would re-introduce the very Head-side
  enumeration that tempdoc 418 was designed to remove.
- Files still under watched roots will be re-resolved naturally on
  the next scan or watcher-driven update.

## Amendment: path-free durable identity (2026-09-03)

Schema V11 adds a second table to the same `jobs.db` file:
`document_identity(path_hash, doc_uid, first_seen_at, last_seen_at)`. It contains no raw path. The
random, content-independent UID is therefore outside the privacy and retention rationale that
governs `path_resolution`; identity rows are not pruned on removal, retention expiry, or unwatch.
`path_resolution` remains the only persistent table that can reverse a hash into a path.

The Worker resolves this identity beside the existing path-resolution admission write and fails the
job closed if the identity authority is unavailable. On first V11 boot, or after restoring an older
pre-V11 queue backup, the serving index seeds missing parent mappings before indexing begins.
Existing store mappings remain authoritative on later restarts. API-driven renames re-key the store
before Lucene path fields are rewritten; a stale historical destination identity is displaced by the
moving source. Watcher-driven filesystem renames are still observed as delete plus create and are not
covered by that rename-preservation contract.

## Open questions

None blocking. The retention default (90 days) and prune-on-unwatch
behavior are recommended choices; the ADR review is the moment to
override.

## References

- Original contract: `docs/explanation/03-knowledge-server.md`
  §"Ingestion Ledger Privacy Contract (tempdoc 410 §8 + Slice E +
  Slice G.4)".
- Source tempdoc: tempdoc 410 (adversarial ingestion resilience).
- Productization tempdoc: tempdoc 419
  §"Long-term Substrate Decisions for Ingestion-Ledger Productization"
  (S5).
- Frontend implementation guide:
  `docs/how-to/library-indexing-activity-panel.md` §"Privacy contract".
- Substrate plan: `C:\Users\<user>\.claude\plans\snazzy-soaring-sonnet.md`
  §"Slice T5".
