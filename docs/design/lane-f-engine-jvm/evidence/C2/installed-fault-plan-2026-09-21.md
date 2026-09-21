# Remaining installed C2 fault points

Design selected 2026-09-21 after transcript-guided resumption. This refines C2-11,
it does not replace its six-case requirement. Use the existing installed
OperationResumeE2ETest -> EngineSupervisedRecoveryE2ETest.runScenario ->
real-writer-recovery.mjs driver, its identity-verified crash/cooldown observation
and owned stop. Do not add another polling driver or bypass local API protections.

| Case | Actual held boundary | Post-death and successor contract |
| --- | --- | --- |
| ingest-before-accept | before runner's real INGEST row acceptance | unknown key/no job or effect; first retry executes once, second retains identity and one document |
| settings-before-accept | before SETTINGS_APPLY row acceptance | unknown key/unchanged witness; original patch/revision retry commits once, replay preserves witness |
| ingest-after-accept-before-effect | after row acceptance, before start/body | ACCEPTED/no queue effect at death; successor resumes original prepared operation, retry creates no duplicate |
| settings-after-accept-before-effect | after acceptance, before start/body | ACCEPTED/unarmed revision at death; successor fails interrupted_before_settings_commit; retry preserves failure/no effect |
| ingest-after-effect-before-checkpoint | before positive-unit ingest-receipt checkpoint | queue sealed and real write committed, row checkpoint old; successor checkpoints receipt/completes original operation with one document |
| settings-after-effect-before-checkpoint | after settings owner apply returns, before operation terminal write | exact key and expectedRevision+1 witness committed, row open; successor completes from witness, replay does not increment revision |

Public settings is SETTINGS_APPLY/settings.apply-public via /api/settings/v2;
D1 RECONFIGURE is not substituted. The exact committed-witness rule in
operations-store-design.md supersedes the old blanket-failure expectation.
For successful ingest recovery, persist a narrow watched corpus root before
boot. StructuralAuto can continue a fresh out-of-root ingest but cannot authorize
its restart; baseline2020 exposed the stale fixture precondition, and rooted2022
passes the existing installed processing/retry scenario.

Use one immutable test callback selected at HeadlessApp composition only with
JUSTSEARCH_SUPERVISOR_HARNESS=1. Compare/reject selection without harness mode.
Candidate barriers belong around the runner's actual accept writes (including
admitAndAccept.Scope.accept and ordinary/prepared acceptance), before the
Control.checkpoint call for positive-unit ingest-receipt cursors, and after
settingsOwner.apply returns in applySettingsOwned. No global SQLite/worker hook
or alternate persistence owner is needed. Reached/release files live only under
the fixture runtime directory; a persisted reached marker prevents successor
retrigger. Match operation kind/key so unrelated boot operations cannot trigger
or accidentally satisfy the test. The harness must fail if the selected boundary
is not reached and verify its own marker before identity-checked Engine death.
Record the snapshot during counted cooldown, before successor can alter it.

Add each case as a separate JUnit parameter/supervised run. New names receive
Windows process-identity gating and restart cooldown; chaos extraction argfile
stays exclusive to the original processing/operation scenarios. Held HTTP requests
need an explicit cancellable lifetime so the helper's five-second timeout cannot
be mistaken for the intended kill. Reuse the after-accept barrier for one additional
real-producer client-disconnect case: abort caller, release barrier without killing
Engine, prove eventual durable completion and admission release.

The independently reproduced closed-enumeration refusal gap (2023) is corrected
in1a94b5c0a. The installed hook/driver batch is now in progress. Test disabled-hook
behavior and each reached point, run all six installed cases plus disconnect,
run required full stress suite and both supervisor adapters, and reconcile C2.
Current files/line numbers must be rechecked against the then-current source.

The implemented boundary callback is OperationAttemptRunnerImpl.FaultBoundary,
with a no-op default. OperationFaultBarrier at HeadlessApp composition reads
JUSTSEARCH_OPERATION_FAULT_POINT (before-accept/after-accept/after-effect),
JUSTSEARCH_OPERATION_FAULT_KEY and JUSTSEARCH_OPERATION_FAULT_KIND (ingest or
settings-apply), only under JUSTSEARCH_SUPERVISOR_HARNESS=1. It atomically publishes
runtime/operation-fault-reached.json and waits at most180 seconds for
runtime/operation-fault-release. The successor does not retrigger an existing
reached marker. No non-harness store/queue API or persistent schema changes.

Focused2032 passes26 barrier/runner cases with PMD/format;2034 compiles the installed
distribution and JUnit scenario entry. Governance2033 passes operation-surface and
engine-port (the latter's informational scan population is5, matching its floor).
These are hook/plumbing proof only; installed cases are not yet executed.

Supervisor conformance2038 (dev-runner) and2039 (Tauri) each pass17/17. Tauri
build2036 initially failed because the fresh worktree lacked bundled resources;
2037 uses the existing CI conformance placeholder procedure and builds the actual
supervisor-conformance binary. Its seven existing Rust warnings and incremental
cache access warning are retained in tmp/2037-cargo.txt. This is actuator proof,
not a packaged sidecar payload. Post-run2040 reports ABSENT, no foreign runs and
no inference orphan. Raw outputs tmp/2032*, tmp/2034*, tmp/2036* through tmp/2040*
have the same lane-acceptance-plus30-days retention as the refusal correction.

2041 proved fixture interference: restoring a watched root starts an independent
scan/watcher, which indexed the test file while the explicit request was held
before acceptance. There is no existing switch for both automatic producers.
For an explicitly selected, gated fault fixture only, HeadlessApp now composes
KnowledgeServerBootstrap with automatic root producers disabled. Authority still
loads the persisted root; explicit preparation, recorded ingestion, indexing and
reconciliation are unchanged. Default constructors enable automatic producers.
The immutable selection also suppresses periodic sync and post-suspend catch-up.
This is narrower than changing eval-mode behavior or fabricating out-of-root
authority. Normal/default versus isolated initialization and resume tests pass2042;
its41 cases also include the combined scheduled refusal/reopen regression.

2043 confirmed the no-row/no-effect crash cut and actual one-document commit, but
read the status projection before it published the committed count. The driver now
waits for both searchable content and the exact status count before retained-retry
assertions.2044 passes the whole before-accept case, including unchanged second retry,
one document and owned stop. Full installed2045 failed seven of eight JUnit cases:
four internal scenario passes hit the wrapper's obsolete marker assertion, two settings
cases incorrectly expected a numeric SQLite ID in the successful public DTO, and
disconnect used an incorrect zero-admission baseline. The original processing case passed.
The wrapper now checks each new scenario's exact markers; successful settings replay
checks the public key/state and stable SQL identity rather than inventing a DTO field.

Diagnostic2046 independently establishes disconnect's pre-request activeWorkCount=1
and repeated returns to1 after completion. C1's live-policy contract explicitly retains
one indexing-bridge slot and requires equal pre/post baselines. Source review traces
that retained subscription and all HTTP/handoff/coordinator release paths; no product
leak was found. The corrected driver establishes two equal pre-request count samples,
waits for that exact count after completion, and then proves a fresh ingest succeeds
without duplicating the document. Installed2047 executes all eight cases successfully
in2m37s, with zero skips/failures/errors. XML and counts are retained in
tmp/2047-xml and tmp/2047-counts.json. PMD/format2048 passes; quick_health2049
reports ABSENT, no foreign runs and no inference orphan. Preserve the earlier failed
runs as fixture-error evidence, not successful acceptance executions.

Independent review requests direct post-recovery child receipt proof rather than
inferring it from the parent and document convergence. The after-effect ingest case
now checks the original child ID, exact selected receipt cursor/counts, one additional
attempt, unchanged sealed receipt/revision and ACK of that revision. The fixture also
clears inherited fault selectors before choosing its own. Installed2050 reruns this
stronger proof. The new scenario must be an explicit Gradle integration-test input,
so editing its assertions invalidates cached execution just like the existing drivers.

Final2051 includes that input declaration and executes all8 cases with zero
failures/errors/skips in2m41s. Its `--info` output explicitly reports execution because
the input file property was added; a subsequent edit-only invalidation check is not
claimed. This is Windows installed-process proof using one Engine JVM and real SQLite,
queue, index and HTTP owners, with inference offline. It is not model-quality proof.
Independent review has no remaining actionable finding. No dependencies changed.
Governance2052 passes operation-surface with zero findings and config-surface with
eight notes (one available rebalance, six existing dead-key baselines and one earlier
declared-growth allowance); the notes are not new failures or cleanup waivers.
Post-run2053 checks lifecycle ownership. Raw commands, counts, XML and exact per-case
output paths are retained in `tmp/2051.txt`, `tmp/2051-counts.json`, `tmp/2051-xml`,
`tmp/2051-installed-artifacts.json` and `tmp/2052-*`, through lane acceptance plus30 days.
All eight fixtures record owned stop with closed ports. Export before worktree release.

## Other C2 obligations retained by the acceptance audit

- Scheduled admission refusal before an agent-run row, followed by operations-store
  reopen and same-key outcome/retry: new BackgroundWorkLifetimeTest regression passes2042.
- Pair a declared generation change with an unrelated settings change through actual
  durable resumption; map the current typed refusal to the original inputs-changed intent.
- The existing both-store reopen proof covers streamed `core.reindex` row advancement,
  not bulk migration. Current C2-8d freezes root bindings/target generation and streams
  work; do not resurrect a full pre-walk on that path. C2-10 still owes
  `core.bulk-reindex`'s durable row, prepared plan, resume and processing-history/gaps
  connection. No later amendment moves those obligations to D1. The bulk catalog
  currently lacks a durable record kind and its handler returns immediately after
  migration dispatch. Add the connection and captured H1-to-H2 `superseded` proof;
  preserve D1's separate journal/replay/live-activation/gap-refusal ownership.
- C2-12: remove current BulkReindexHandler ownership prose residue, reconcile jobs-db
  register source/classification/version references, scope the self-matching historical
  grep, and run final dead-code/full stress proof. Do not edit historical tempdocs merely
  to make a repository-wide phrase search green.
