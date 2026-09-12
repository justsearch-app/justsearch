# C2-2 VDU generation boundary implementation cut

September12, source investigated at41a74500b. Implementation remains owed.

## Decision and scope

VDU mutations may acknowledge only a covering commit on the captured serving generation.
Retire new VDU switch-buffer production: update, mark and recovery refuse retryably before
effect when their captured ingest runtime is not the captured serving runtime or current
state does not identify that same target as IDLE with no building generation. Recheck the
same predicate after commit/refresh before acknowledging. A detected transition after an
effect is an incomplete operation, not permission to claim completion; retry can recover
PROCESSING and repeat derived enrichment. No new VDU receipt store or transition lock.

This corrects the earlier proposal to model buffered VDU acceptance as deferred completion.
The buffer coalesces per document and has no per-attempt completion identity. Reusing it as
an attempt ledger would require new durable ownership that D1's journal already owns.
Keep legacy VDU rows readable, but replay them only on an eligible serving runtime after
restart. They must not be applied to Green while resumed source enumeration can replace
the parent document. New callers get a retryable refusal and retain no new buffered VDU row.

C2 proves actual commit on the captured target, not survival of that derived result through
a later rebuild. A pre-effect check alone is not a lease or an atomic boundary with start,
rollback or retirement. The post-commit check detects an observed transition but does not
replace D1's accepted-write journal/activation proof. A lock around start would still not
carry a previously committed derived projection into Green. D1 owns that carry-forward,
including writes without files and deletes; do not close that acceptance with this gate.

## Existing owners and exact changes

- DefaultWorkerAppServices currently reads searchLifecycleSupplier twice, for search and
  ingest service construction (:195-217). Resolve it once for those consumers. No new
  runtime registry or cross-module wrapper.
- WorkerIngestService already receives both runtime objects, indexBasePath and activeIndexPath
  (:149-162). Retain same-runtime eligibility and the captured target path, reusing its
  existing IndexGenerationManager. Production direct update/mark/recovery invokes the
  guard before any VDU field read/effect, then after covering commit/refresh. Preserve
  blank/invalid request validation before effect. Standalone constructions with an explicit
  null indexBasePath have no generation-state authority; they still require one shared
  serving/writing runtime. Tests claiming managed generations must construct a real layout.
- IndexGenerationManager exposes a strict, fresh, read-only predicate for that target. It
  reads authoritative state.json once, validates supported format and safe generation id,
  requires explicit IDLE/no building, and compares the resolved active path. Unreadable,
  absent, malformed or unsupported state refuses. No cache, backup restore, normalization
  write or optimistic fallback on this control path. readStateBestEffort (:687-727) is
  unsuitable: an unreadable stamp deliberately serves a prior cached state; load fallback
  (:892-907) can restore state.json.prev. Keep those observational/recovery callers intact.
- Manual start records MIGRATING/building before the app requests restart
  (MigrationControlOps:47-63; EngineKnowledgeClient:259-262), so object identity alone is
  insufficient. Rollback switches active_generation while state remains IDLE
  (IndexGenerationManager:551-577), so state-name comparison alone is also insufficient.
- DrainSwitchBufferContext gets an explicit serving-runtime eligibility supplier, using
  captured identity plus the same strict predicate at replay entry and after commit. Filter legacy VDU
  kinds out of the replay snapshot when ineligible; retain their exact versions. Replay
  and conditionally remove the eligible snapshot through the existing c950 transaction.
  A deferred VDU row must not prevent independent file/delete/sync entries from draining.
  Source enumeration and cutover do not await VDU backlog; legacy VDU replay follows boot
  on the active generation. If eligibility is lost during replay, retain the eligible snapshot for retry. Preserve
  the existing all-or-none commit/removal rule within that filtered snapshot: per-row
  outcome/queue-versus-index tracking is unnecessary to isolate deliberate VDU deferral
  and would expand this item into partial effect accounting. Failed/malformed/unknown
  eligible rows retain the snapshot. Correct legacy mark/recovery missing-parent,
  blank-payload or absent-runtime cases rather than treating them as applied.
- Retire IngestSwitchBufferOps VDU put helpers and now-unused encoding/key constants in
  the same item; keep only payload readers/fixtures needed for older buffer rows.

## Required proof

1. Managed IDLE same-runtime update/mark/recovery commits and refreshes before success;
   distinct Blue/Green, MIGRATING, SWITCHING, FAILED/building and pointer rollback refuse
   before field reads/index changes or buffer insertion. Pin retryable port translation.
2. A state change injected during the covering commit makes the post-commit response refuse,
   preserving any already committed effect for recovery instead of fabricating a completed row.
3. Prime observational cache, then remove/corrupt state.json with valid previous state present:
   strict gate refuses and modifies neither file. Include future format/invalid generation id.
4. Real legacy VDU row plus an ordinary delete during resumed migration: delete drains,
   VDU remains unchanged across reopen; after eligible activation/restart, VDU applies once
   with a covering commit and its exact version is removed. Missing parent/failed commit
   still retains it. Include source re-enumeration overwriting a parent before deferred replay.
5. Legacy processing/failed marks with missing parents or malformed payloads remain;
   recovery with an unavailable runtime remains. Unknown kinds cannot be removed as success.
6. Negative mutations omit each identity/state/pointer/post-commit condition and allow
   premature replay. Require focused, full, stress and hosted proof at coherent boundaries.

Primary-source lines are from the investigated revision and must be rechecked after edits.
This is a decided mechanism within C2-2, with D1 carry-forward still an explicit later stage
obligation. No owner input is pending.
