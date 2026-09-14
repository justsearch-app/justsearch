# C2 d.3b prerequisite: recorded force at claim admission — 2026-09-14

## Decision and source of truth

The existing force path is not a valid recorded producer implementation. WorkerIngestService
submitBatch enqueues before marking forced paths (lines541-560 at064adc779), and
WorkerScanOps.flushBatch does the same (284-299). JobBatchExtractor reads/removes the
process-local set only after a claim (226-245). A poll can precede the mark; process restart
also loses that set. IndexingLoop205-212 already documents retained unconsumed markers.
The persisted RecordedRootPlan.Root.force is the recorded operation's sole force authority.

Replace the recorded boolean claim callback with one enum decision: DENY, ALLOW or
ALLOW_FORCE. The coordinator returns ALLOW_FORCE only after all current physical, generation,
readiness, cancellation and authority checks pass, and only from the exact validated one-root
Permission plan. Missing/malformed/multi-root permission denies. No callback may read jobs,
operations or a runner while the queue lock is held; the validated plan is immutable metadata.

SqliteJobQueue samples that decision once for each eligible recorded candidate, under its
existing lock, before applying the batch limit. Retain the exact decision in the local claimed
candidate and copy its force value into the immutable issued IndexJob.recordedForce alongside
the key, unit revision and explicit walk epoch. This is a process-private admission snapshot,
not another persistent plan or a new authority. No schema, SQL column or replay JSON is added.
Recovery probes use the same enum function only to permit unowned PROCESSING -> PENDING;
they discard force and resample at actual claim. Stopped cancelled/failed orphan bookkeeping
still avoids an authority call. DENY/null/runtime failure fences; fatal Error propagates.
All no-owner queue/lifecycle constructors remain denied. Retire Predicate authorization APIs;
existing fixtures migrate to explicit enum decisions without changing their test intent.

JobBatchExtractor uses walkEpoch != null ? claim.recordedForce : legacy forcedPaths.remove.
A recorded claim never consults or consumes a matching legacy marker. Revocation after claim
blocks the next claim, but does not retroactively change the already-admitted force decision.
Queue completion/return still requires the exact issued object; equal-valued forged claims
cannot complete, release or acknowledge it.

This is simpler than persisting a second force column or retrieving permission again during
extraction. The first forks the already-frozen plan; the second races revocation and loses
the admitted snapshot. A second force predicate next to the permission predicate has the
same split-read race. The enum permits no invalid denied-but-forced state.

## Implementation and verification plan

1. Replace the queue/lifecycle callback and add the issued force snapshot; wire the coordinator
   and extractor. Update existing constructor fixtures mechanically. Root owns implementation.
2. Prove exact single sampling (including alternating answers), denial/null/runtime/Error,
   ALLOW versus ALLOW_FORCE, default-denied startup and both recovery probes. Prove live
   revocation preserves an issued snapshot and forged force-bearing claims cannot release it.
3. Prove same-file unchanged detection: recorded FORCE reaches extraction with no path-set
   mark; recorded ALLOW ignores/preserves a matching marker; legacy behavior remains intact.
4. Prove restart from real accepted operation/root-plan binding produces a forced claim only
   after revalidated admission, preserving the existing stopped-orphan recovery cases.
5. Run focused and meaningful guard-bypass regressions, affected Worker/queue/Engine suites,
   PMD/format and relevant governance. Commit/push this per-item cut before adding producer DTOs.

Independent read-only design review at064adc779 accepted these pins and named the same tests;
it executed no behavioral proof. Its final report incorrectly said the planned producer
bypasses WorkerIngestService: the selected d.3b path remains EngineKnowledgeClient -> existing
WorkerIngestService submitBatch/scanRoot -> recorded queue admission. Root corrected that claim.
The existing force-triggered embedding-compatibility transition in WorkerIngestService521-527
still needs its pending-work/ordering contract verified during actual producer wiring. The
claim snapshot proves unchanged-bypass, not that compatibility transition. No new caller or
guard exemption is authorized by this cut.

## Implementation and evidence

The enum callback, exact issued snapshot, coordinator derivation and extractor branch are
implemented on064adc779 plus this per-item diff. Existing callback fixtures use explicit enum
decisions; the public boolean callback is retired. No persistent schema changed.

1750 passes production compilation and four production PMD tasks.1751 initially failed test
compilation because one server fixture still returned boolean; root migrated that missed
callback.1752 then executed74 cases/seven suites with no skips/failures/errors, three test PMD
tasks and format passing. The Engine reconstruction test reopens the real jobs queue and
rebuilds the runner/coordinator from accepted persisted parent/child plans for both force
values; this is same-process reconstruction, not literal new-JVM crash proof.

Independent read-only implementation review found no production defect. Root addressed its
two proof gaps: recovery now crosses both timed/unconditional probes with both allowed enum
values, then polls with the opposite force; a constructor test rejects forced legacy claims.
1755 executes16 cases/two suites with no skips/failures/errors, test PMD and format passing.
Six cases are the automatically included indexer architecture guardrails.

1754 deliberately resamples the claim decision, restores legacy-only extraction and drops
force in coordinator authorization. Eleven cases execute: four expected failures (one
double-sampling assertion, two extractor branch assertions and the recovered forced plan),
while the unforced plan and six guardrails pass.1756 rejects ALLOW_FORCE only at recovery and
removes the legacy-force constructor guard: eleven cases execute with exactly three expected
failures (both forced recovery modes and the constructor); two ordinary recovery modes and
six guardrails pass. Root reread each XML assertion and restored every mutated source
byte-for-byte. An initial1754 invocation failed before Gradle because Python passed a Unix
relative batch path to Windows; using gradlew.bat corrected the invocation before proof.

Full affected Engine/indexer/Worker-services/Worker-core suites, eight PMD tasks and format
are running as1757 on the restored reviewed source, with testParallelism=1/includeStress=true.
They are not yet passing evidence. Exact commands/revisions/counts are in numeric -counts.json,
XML in matching -xml directories and logs in numeric .txt under
F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/. Original negative-control source
bytes are1754-*.original.bin and1756-*.original.bin. Retain through final lane reconciliation
plus30 days, at least2026-10-14. No hosted result exists for this uncommitted force cut.

1758 passes operation-surface, execution-surface and register-guard-resolution gates, with no
findings. Canonical link, runtime config and store recoverability checks pass; documentation
and skill regeneration produce no additional diff. This cut is checkpointed while1757 runs,
so integrated and hosted verification remain open after the commit.

Actual bounded producer, prepared handlers and live proof remain d.3b; C2 remains open,
with merge at stage F. Checkpoint064adc779 hosted CI34878848566 passes12/13 jobs, failing only
the still-unbound producer method; CLA34878843824 passes. No guard exemption is selected.
