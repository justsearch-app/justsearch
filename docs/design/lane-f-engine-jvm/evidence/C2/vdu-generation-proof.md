# C2-2 generation refusal and legacy replay proof

September12,2026. Windows/Java25, implementation based on927acc400. This record
covers the generation item and its independent review corrections, not C2-2 closure.

## Implemented behavior

The [governing cut](vdu-generation-boundary.md) is implemented: same captured serving
runtime, fresh IDLE/no-building state and existing matching target before VDU effects,
with a post-commit check. Unavailable runtimes refuse through the real Engine port.
The strict read resolves paths without directory creation or backup/cache recovery.
New VDU buffering is retired. Legacy replay filters deliberate VDU deferrals while
other entries drain, validates legacy payloads and conditionally removes committed
versions. Incomplete selected recovery commits its successful subset and then fails,
retaining the legacy row. No additional durable journal, transition lock or owner.

## Execution evidence

All commands use `-PtestParallelism=1 --max-workers=4 --console=plain`;713 adds
`--continue` to collect independent failures without skipping later selected tasks.

| Run | Inputs and command selection | Observed result |
| --- | --- | --- |
| 697 | Old927acc400 production plus new VduGenerationBoundaryTest; indexer-worker:test | 18 cases/2 suites,9 intended failures: three mutations each violate identity, pre-restart migration and post-commit transition checks. Three valid commits and6 guardrails pass. |
| 698 | Initial implementation; focused VDU/replay tests and PMD | Indexer-worker test compilation fails at two missed context-constructor call sites. Worker-services executes15 passing cases. Both call sites and the app-engine counterpart are corrected. |
| 699 | Strict state fixture and other selected suites | Six cases,1 fixture failure: incomplete future-format JSON fails parsing before format validation. Corrected to mutate a complete valid state. |
| 700 | New generation/replay/port fixtures and PMD | 13 cases,1 fixture failure: no-interactions assertion includes constructor getters. Corrected to assert no field reads, index effects or commits. |
| 701 | Same selection after fixture correction | 104 cases,2 failures: nested Mockito stubbing and one remaining retired SWITCHING-buffer expectation. Corrected while preserving retry/state and continue-after-failure intent. |
| 702 | Three-module focused generation/replay/port suites and PMD | PASS111 cases/20 suites. Indexer-worker98 and app-engine7 execute; worker-core6 reuse700. |
| 711 | Snapshot702 plus absent-layout and absent-ingest regressions | Nine cases/2 suites,2 intended failures. The strict predicate creates/accepts an absent layout; absent-ingest update loses typed refusal. |
| 712 | Old recovery aggregate plus VduRecoveryReplayCompletenessTest | 11 cases/2 suites,3 intended failures: all selected misses, partial miss, partial IOException erase the row. Successful/empty selections and6 guardrails pass. |
| 713 | Corrected implementation; four-module focused selection and affected PMD | PASS133 cases/22 suites, zero failures/errors/skips,1m23s. All four test tasks execute: worker-core7, worker-services15, indexer-worker103, app-engine8. PMD passes. |

713 selects worker-core `*IndexGenerationVduEligibilityTest`; worker-services
`*VduMutationCommitTest`; app-engine `*EngineVduGenerationRefusalTest` and
`*EngineContextPortPropagationTest`; indexer-worker `*VduGenerationBoundaryTest`,
`*VduGenerationReplayTest`, `*VduRecoveryReplayCompletenessTest`,
`*WorkerIngestServiceVduHardeningTest*`, `*WorkerIngestServiceChunkRegenerationTest`,
`*VduResultReplayParityTest`, `*VduReplayFailureRetentionTest`,
`*SwitchBufferConcurrentReplayTest`, `*SyncRootReplayProvenanceTest` and
`*IngestionProvenancePersistenceTest`. PMD: worker-core and worker-services main/test,
indexer-worker main/test, app-engine test. Exact task graph is in713's log.

## Wrong-reason checks

Temporary source mutations against snapshot702 each fail the relevant assertion;
the script restores the original file bytes in a finally block after every run.

| Run | Omitted condition | Cases / intended failures |
| --- | --- | --- |
| 703 | IDLE state requirement | 6 /1 |
| 704 | No building generation | 6 /1 |
| 705 | Active target path equality | 6 /1 |
| 706 | Captured runtime identity | 18 /3 |
| 707 | Post-commit direct checks | 18 /3 |
| 708 | Ineligible legacy VDU snapshot filtering | 20 /1 |
| 709 | Post-commit legacy replay check | 20 /1 |
| 710 | Legacy retry-count/recovery-payload validation | 20 /4 |

No mutation run has errors or skips. These runs predate the three review corrections;
711/712 refute those separately, and713 verifies their combined restoration/fix.

## Independent review and limits

Independent read-only review of702 identified the three defects reproduced by711/712
and stale canonical migration text. The corrected implementation and documentation
pass a second read-only check and independent skeptical pass with no remaining
production blocker. Canonical reference/handoff drift found in that pass is corrected.
The real Lucene replay case proves replacement
parent content and regenerated chunk revisions; the mixed SQLite case proves exact
row retention across reopen and later eligible removal. Neither directly drives the
production KnowledgeServer eligibility wiring through a resumed migration and actual
activation. That combined proof remains required, along with full/stress/hosted checks
for this corrected revision, actual-model/installed offline completion and later D1
carry-forward. These limits are work in progress, not an owner-gated deferral.

## Evidence access

Logs and copied XML/counts live under this worktree's `tmp/`, with prefixes
`c2-2-vdu-generation-{negative-697,698,699,700,701,702,negative-703,...,negative-710,713}`,
`c2-2-vdu-generation-review-negative-711` and
`c2-2-vdu-recovery-review-negative-712`. Each retained test run has `-xml/` and
`-counts.json`;698/699/700 record only tasks that actually ran. Scripts:
`tmp/c2-2-capture-generation.py`, `tmp/c2-vdu-generation-mutants.py`; snapshot702
source bytes are in `tmp/c2-vdu-generation-mutant-backup/`.
Keep through lane acceptance plus30 days and export before worktree release.
