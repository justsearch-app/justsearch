# C1-6/C1-7 integration corrections — 2026-09-09

These are corrective item checkpoints over 2c0c686e4, tested locally on Windows with Temurin JDK 25.
They do not finish either item or C1. Raw evidence remains under this worktree's `tmp/`; retain it
through final hosted proof and lane merge. Do not delete earlier red or mutation captures.

## Ownership decisions and corrections

KnowledgeServer constructs one Lucene registration bundle before the Worker bundle, rolls it back
if Worker registration fails, injects it into writable/read-only runtime builders, and closes both
bundles after runtime owners. Component tests now inject and close one explicit registry/bundle.
CommitOps tests exercising a scheduler receive it in their test-only RuntimeSession constructor.
The ordinary production builder still rejects a missing bundle. External module test builders are
not all migrated yet; this checkpoint makes no claim about the full unit suite.

Legacy package-private OnlineModeOps protocol helpers use the registered background request owner;
public model calls still require an attached work handle. StreamCallbackPump now terminates an
already-cancelled queued dispatch without leaking its retained handle and reports interruption as
cancellation instead of claiming a successful drain. The cancellation and refusal regression uses
separate live/cancelled work fixtures so both paths are actually exercised.

Agent-history overflow uses the existing durable RunEventStore plus one coalesced reconciliation
obligation. A registered background retry timer retries admission using the canonical retry policy,
then scans the latest supplier when the worker actually starts. This avoids an unbounded payload
retry buffer or a new persistent authority. Close cancels the timer before draining the worker;
constructor rollback retains cleanup failures as suppressed exceptions. The real-registry regression
fills the actual 64-slot queue, persists a refused terminal event, proves no inline projection, and
observes recovery without restart or a new event.

GPL refusal leaves the still-live coordinator registration available for a later accepted run;
coordinator close retires it. Shutdown ordering tests expect GPL to stop before inference/Worker.
Unlock scan close must complete the already-accepted coalesced follow-up. Head must attempt later
scan and orchestration owners even when an earlier scan close fails. Head fixtures isolate both
home/data properties to keep transition logs inside their temporary directories.

## Checks and wrong-reason controls

- Full production/test compilation passed run22 and run24.
- Run25: adapters-lucene 716 tests and app-inference 319 tests passed, zero failures/errors/skips.
  `tmp/c1-batch4-composition-tests-25.txt`; XML `tmp/c1-batch4-composition-green-parts-25/`.
  Its copied UI folder is stale and is NOT evidence for that run.
- Run26: app-services 93, launcher 51 and UI 3 tests passed; the UI set is the history regression
  plus two guard checks. Raw `tmp/c1-batch4-composition-tests-26.txt`, preserved XML
  `tmp/c1-batch4-composition-green-26/`.
- Run27 passed restored history, Resource API and VDU shutdown checks plus Head/unlock checks.
  `tmp/c1-batch4-restored-tests-27.txt`; XML `tmp/c1-batch4-restored-green-27/`.
- Removing only the history overflow obligation failed the recovery assertion after its bounded
  wait. `tmp/c1-batch4-history-overflow-mutant.txt` and `.xml`. Restored before run27.
- Dropping UnlockDeferredScan's accepted pending pass and making Head immediately throw on its
  first scan-close failure each failed their intended regression. Expected Head close order was
  first-scan, second-scan, inference; the mutant reached only first-scan.
  `tmp/c1-batch4-shutdown-mutants.txt`; XML `tmp/c1-batch4-shutdown-mutants/`.
- Both shutdown mutations were restored. Run31 passed 28 Head/unlock tests, one Lucene registration
  test and four Engine executor tests, zero failures/errors/skips, including their configured
  guards. `tmp/c1-batch4-restored-tests-31.txt`; XML `tmp/c1-batch4-restored-green-31/`.
- Dependency lock regeneration run3 passed with no changed closure; the new direct core test
  fixture dependency was already represented transitively. `tmp/c1-batch4-locks-3.txt`.
- Builds29/30 exposed code-quality issues and previously uncompiled integration callers. Build32
  then reached a missing Lucene bundle in the schema-mismatch test seed. Each failure is retained
  in `tmp/c1-batch4-build-{29,30,32}.txt`. Corrections preserve validation and explicit ownership.

Still required: remaining production executor census migrations; external test-builder injection;
interruptible fanout cancellation and blocked commit shutdown proofs; retained scan-state bounds;
Rule 9 and confinement mutation proofs; full stress-enabled suite, canonical regeneration, final
installed standard-model API/query/pacing checks, hosted/platform proof, and later design stages.

## Integrated checkpoint result

`build -x test` passed in 27 seconds in run34 after correcting the remaining UI code-quality
findings; it includes the repository-configured integration tasks. `tmp/c1-batch4-build-34.txt`.
Run33 is retained as the preceding UI code-quality failure. The explicit heartbeat test seam no
longer accepts an unused registry: the supplied heartbeat already owns its scheduler. The managed
child close regression now inspects the HTTP executor and asserts it is stopped after close.
The follow-up UI suite run35 passed; `tmp/c1-batch4-ui-tests-35.txt`, preserved XML
`tmp/c1-batch4-ui-green-35/`. Integrated XML snapshots: `tmp/c1-batch4-build-green-34/`.
Neither the build nor these focused suites replace the pending full stress-enabled unit suite.
