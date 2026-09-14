# C2 d.3b.1 Java-only recorded scan admission — 2026-09-14

## Decision and boundary

C2-2's Java-only identity decision remains binding: indexing.proto, EngineContext,
correlationId and thread-local state are unchanged. Force-at-claim is locally verified at
0aa0575b4. Root owns this cross-module producer implementation.

WorkerIngestService gains one immutable Java request projection beside ScanRootRequest:
accepted operation key, positive enumeration epoch, frozen generation, single-file shape and frozen excluded
subtrees. The proto already carries path/collection/force mode/globs; do not duplicate those
in another plan. Validate bounded key, exact absolute normalized root and strict descendant
subtrees before effects. This is a projection of RecordedRootPlan.Root, not new authority.
Recorded progress uses the accepted child key as scan_id; no worker-minted replacement.

Recorded directory scans project key/epoch/subtrees into WorkerScanOps.ScanRequest. Legacy
constructors retain absent epoch/empty subtrees. Recorded flush uses enqueueRecordedEntries
with the exact open epoch and requires full acceptance. It never marks legacy forcedPaths:
the coordinator already freezes Root.force into issued IndexJob claims.

Prune excluded directory subtrees before visiting children and exclude nested single-file
roots in visitFile, before cloud-placeholder ledger effects. Recorded glob policy comes from
the frozen request; malformed globs refuse before traversal instead of broadening scope.
Keep standard locale-invariant ingestion skip policy. Recorded visitFileFailed reports failed
enumeration: partial/inaccessible coverage cannot label unseen members deleted. Missing or
changed-kind roots produce failed terminal progress; genuine empty/all-excluded roots may
complete. Cancellation is checked after backpressure, before both full and final batch
admission. A cancelled batch is not flushed; earlier admitted members remain coordinator-owned.

Recorded single-file scans use the existing Files.walkFileTree visitor on exactly the frozen
file, with maxDepth zero, an explicit singleFile flag and authoritative visitor kind checks.
Recorded roots use NOFOLLOW_LINKS consistently; a root symlink refuses, while nested symlinks
retain the existing non-following skip policy. A file becoming a directory or link between the
precheck and visitor fails instead of broadening scope or completing empty. This is simpler than a second batch
entry point: exclusions, placeholders, backpressure, admission and cancellation have one owner.
For that root, relative glob matching uses its filename. Compare the frozen generation with
strict captureServingGeneration at entry and immediately before every full or final recorded
enqueue, after backpressure. A WorkerScanOps instance without that admission guard refuses
recorded enqueue. This is an observation, not the D1 generation lease. Never use the unkeyed
switch buffer or report partial acceptance as success. No
executor is introduced in this subcut. Ordinary callers remain explicitly unrecorded.

## Activation and compatibility

The bounded Engine adapter and EngineRoot binding follow in d.3b.2. Both file and directory
roots use the existing root-walk executor and scan flow, avoiding another unary response/exit
carrier. Actual completion includes synchronous service exit, progress drain and task cleanup.

Compatibility ownership is resolved in
[d.3b.2a](recorded-force-compatibility.md): selected force remains extraction intent and cannot
attest to untouched vectors. Whole-index legacy recovery and blue/green migration own the
transition; the unsafe legacy batch trigger is removed in that follow-up. No recorded ECC
transition or parent-completion hook is needed. Actual exit and public execution remain
required in d.3b.2b; this admission subcut alone does not activate a producer.

## Acceptance

1. File/directory arms preserve key/epoch/context/collection and never use ordinary admission.
   Unit capture verifies the Worker seam; real SQLite verifies the integration boundary.
2. Reject invalid membership/root/subtrees/generation, stale/closed epochs and partial/refused
   admission. Refuse a different idle generation and a switch between full/final batches.
3. Prove frozen globs and nested directory/file ownership, no excluded cloud ledger effect,
   malformed-glob refusal and failed partial IO coverage after an admitted full batch.
   Prove start-node-only traversal and refusal when the file changes to a directory or link.
4. Deterministic cancellation during backpressure prevents full and final batch enqueue;
   cancellation before single-file admission invokes no queue effect.
5. Focused Worker/service/indexer tests, PMD/format and meaningful negative controls; broad
   integrated/live proof at the connected producer boundary. Commit/push this seam before
   d.3b.2 grows. C2 remains open and merge remains at F.

This admission subcut is implemented and locally verified; activation remains below.
September12's prepareReindexPlan API in the historical
preparation report was held/retired under September13 review R9; do not restore that
IndexingService API by treating the dated report as current code.

## Independent review correction

The first independent pass found the entry generation observation was discarded and no
per-batch check existed, plus an unlimited single-file traversal/kind race. Root corrected
both before activation. It also replaced the total-I/O-failure fixture with a full admitted
batch followed by an injected inaccessible member and added service-level frozen-glob proof.
An observed unreadable regular member now fails recorded enumeration; it is not a successful
skip. The filesystem-failure/link-race tests inject visitor observations, not Windows ACL or
real symlink creation. Real SQLite and on-disk generation-state tests cover admission fencing.
Before corrections,1765 executed44 cases/five suites, zero failures/errors/skips and PMD/format
passed. That result does not verify the corrected revision;1766 executes51 cases with the
corrections. The correction review cleared production and requested one additional explicit
unreadable-regular-member test, which root added without changing production.

## Final local evidence and next item

1769 executes52 cases/five suites, zero failures/errors/skips, on fe5742ba5 plus this final
reviewed scan-admission diff. Both selected test tasks execute; production/test PMD and format
pass. The command is:

```powershell
./gradlew.bat -PtestParallelism=1 :modules:worker-services:test --tests '*WorkerScanOpsRecordedTest' --tests '*WorkerScanOpsTest' --tests '*WorkerIngestServiceForceReindexWiringTest' :modules:indexer-worker:test --tests '*WorkerRecordedScanAdmissionTest' :modules:worker-services:pmdMain :modules:worker-services:pmdTest :modules:indexer-worker:pmdTest spotlessCheck
```

1767 removes the expected-generation comparison and per-batch guard. Twelve cases execute:
four expected failures (wrong idle generation, switch before final/full second batch and
unguarded recorded admission), two positive cases and six automatic architecture guardrails
pass.1768 removes visitor kind, unreadable-member and partial-I/O refusals. Five cases execute:
four expected failures (directory/link replacement, unreadable regular member, admitted batch
followed by inaccessible member); valid single-file admission passes. Root read the exact XML
assertions and restored both source files byte-for-byte before1769. These are deliberately red
regressions, not unresolved failures.1763's three test-PMD findings and1764's two stale/closed
epoch fixture exception-type mismatches were corrected; the queue's actual refusal type and
message are now asserted without changing its behavior.

1770 passes operation-surface, execution-surface and register-guard-resolution with zero
findings, plus canonical links, runtime-config matrix and store recoverability. Documentation
and skill regeneration produce no extra diff. Logs, numeric -counts.json command/revision
manifests, matching -xml folders and negative-driver/original-byte files are accessible under
F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/. Retain through final lane
reconciliation plus30 days, at least2026-10-14.

No new live or hosted success is claimed for this Java admission seam. Full1757 remains the
prior force-at-claim revision's proof. The actual bounded Engine adapter, generation carried
from the persisted plan, actual-exit/drain ownership, compatibility decision, prepared handlers,
integrated/live/installed checks and final hosted green remain required. This subcut introduces
no producer binding, executor, schema change or guard exemption. C2 remains open; merge at F.

Prior documentation checkpoint fe5742ba5 hosted CI34883739763 passes12/13 jobs; app-ui fails
only the unreferenced bindProducer method on three attempts. CLA34883734444 passes.1770-hosted-ci.json,
1770-hosted-failed.txt and1770-hosted-xml preserve the job results and exact downloaded failure.
The next producer binding must clear that finding; it is not exempted or counted as green.
