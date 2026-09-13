# C1 queued root scans retain admitted work

Standard-primary run229 on f4ab8ceba indexed469 documents and served13500 continuous searches
without errors (p50 3.147ms, p95 6.012ms, maximum10886.471ms). Readiness passed after266.52s.
That apparent success hid a failed initial scan: at12:20:31.069UTC, walk-bg attempted to attach
the HTTP request's already-finished work and threw `WORK_FINISHED`. The periodic rescan later
indexed the corpus. This run proves primary progress under load but cannot close scan liveness.
The existing dataset-certification warnings remain; these measurements make no quality claim.

Hosted CI34350223349 at f4ab8ceba independently establishes the same cause in all three attempts
of IndexingLedgerCoherenceTest (12:25:43.899,12:31:30.745,12:34:08.512UTC). Each preserved
Engine log has RootLifecycleOps.addWatchedRoot → walkAndSubmit → EngineKnowledgeClient.executeScanRoot
→ EngineAdmissionController.attach → `WORK_FINISHED`. This explains why the hosted30-second
ledger assertion failed while local fast starts passed. The earlier stream-opening retry repair
was a separate real defect; it did not repair this ownership race.

Evidence: [hosted run](https://github.com/justsearch-app/justsearch/actions/runs/34350223349),
artifact `integration-test-results` (id10104013291), reports/isolated-backend directories named
`20260909-122614.795-IndexingLedgerCoherenceTest`, `20260909-123201.753-IndexingLedgerCoherenceTest`,
and `20260909-123439.508-IndexingLedgerCoherenceTest`, each containing backend.log and engine.log.
Local copies: `tmp/c1-hosted-artifacts-233/`; standard run logs copied before owned stop to
`tmp/c1-standard-primary-evidence-231/logs/`. Summary229 is under
`scripts/jseval/tmp/eval-results/lane-f-c1-standard-primary/20260909T122517_golden_synth-multihop-prose-v2/summary.json`.
Owned run d59d7ea8-5d5a-430b-93d7-70f69f4dd53c stopped with ports closed.

The correction retains admission synchronously before enqueueing. KnowledgeClient's protected
root-walk hook delegates ownership to the Engine; RootLifecycleOps carries the returned exact
context through initial-add, path-add, forced/excluded reindex and persisted-root reindex queues.
The existing OwnedStreamTask already supplies cancellation, executor-rejection cleanup, queued
shutdown cleanup and actual-exit ownership, so no second task state machine or persistent marker
is needed. Existing scan/prune calls continue to own their own foreground lifetime. The queued
wrapper preserves entering trace and request correlation; it does not reclassify caller axes.
Availability-cache refresh and diagnostic logging remain ordinary executor tasks.

Adverse232 adds a deterministic blocked-queue test to original production code. After the HTTP
request handle closes, active work is0 rather than1; the assertion fails before the delayed scan
can be masked by a rescan. Restored234 passes this regression plus existing executor, context
propagation and root mapping tests with strict test-source checks. Logs:
`tmp/c1-root-ownership-before-232.txt`, `tmp/c1-root-ownership-restored-234.txt`.
Expanded run235 passes all three ownership tests under the production executor registry, plus
existing executor/context and root mapping checks. The isolated ledger and all five installed
recovery cases also pass. Strict test-source checks and installDist succeed. Snapshot:
`tmp/c1-root-recovery-results-235/manifest.json`; command log `tmp/c1-root-recovery-235.txt`.
This is focused verification; the manifest also retains unrelated earlier reports and their
timestamps, which are not new execution claims for235.
Independent review then found that raw internal reindex callers admitted one work per root.
The blocked 20-root regression244 failed CONTEXT_LIMIT at the shipped16-work limit. Both reindex
entry points now share one batch owner before looping. Restored246 executes all four ownership
tests; it observes actual work completion after asynchronous scan delivery exits. Existing
app-services mapping tests retain234's timestamps. Proof logs:
`tmp/c1-root-batch-before-244.txt`, `tmp/c1-root-batch-restored-246.txt`,
`tmp/c1-root-batch-results-246/manifest.json`. The reviewer found no remaining code defect.
Entering trace/request propagation has been source-reviewed but still needs its queued regression.
Full252 found only style issues in the trace scope name and test type qualification; build256
passes after those corrections. The235 installed proof precedes the batch-owner correction.
Hosted green and a restored live initial scan remain required before C1 acceptance.
