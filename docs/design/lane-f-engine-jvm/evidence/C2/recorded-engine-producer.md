# C2 d.3b.2b — bounded recorded Engine producer

Supersedes tmp/recorded-producer-draft.md's stale single-file submitBatch and ECC ideas.
Frozen2026-09-14 after prerequisite1a75eb891 was committed and pushed. Root owns
lifecycle/source installation; delegation may draft isolated test/fixture changes only.

## Reuse and ownership

1. Keep KnowledgeClient's existing one-thread bounded knowledge-client-root-walk executor.
   Expose its ExecutorService to the Engine subclass through one protected accessor, with
   ownership/close remaining in KnowledgeClient. Preserve the existing executeRootWalk
   override for legacy callers. Extract its scheduling body into a private stage-returning
   helper taking an optional producer CancelToken. This avoids changing every abstract
   client/test adapter and introduces no new executor or registry.
2. OwnedStreamTask already has a completion future. Return a minimal CompletionStage from
   the helper, completing only after its work registration/retained owner cleanup. A cleanup
   failure must settle exceptionally instead of leaving the future pending, and owner.close
   must still run if registration.close throws. Register the producer token before scheduling;
   queued cancellation removes this exact task and releases only its retained owner. Running
   cancellation reaches the synchronous scan signal. Never cancel the durable parent work
   merely for index replacement.
3. Producer.enumerate takes the existing immutable one-root RecordedRootPlan, not Root alone,
   so it retains the persisted generation without another metadata representation. Validate
   exactly one root; project key/epoch/generation/root shape/policy into RecordedRootScan.
   Preserve the proto schema and explicit Java identity. The single-file and directory arms
   both use WorkerIngestService.scanRecordedRoot and the reviewed WorkerScanOps policy.

## Actual exit includes the flow's task

Reuse executeScanRoot/scanRootWork's foreground accounting, call context, deadline cancellation
and bounded progress flow. Thread the optional RecordedRootScan beside ScanRootRequest into
the existing Java service call. A missing/failed/cancelled terminal cannot map to COMPLETE.
The producer maps Worker failures to FAILED or cancellation to CANCELLED only after exit.

BoundedHandoff.drainAndClose waits for delivery counts, but its timeout closes the queue while
a consumer callback may still run. Therefore the root-walk task future alone is insufficient.
Make executeOwnedStream return its existing OwnedStreamTask completion stage. Record the one
delivery task stage in a local invocation holder passed explicitly to scanRootWork; ordinary
callers may ignore it. Producer completion composes both root-walk and delivery-task actual
exit, including cleanup, without blocking the root-walk executor or creating another task.
The default completed delivery stage represents only 'delivery was never scheduled'; publish
the actual stage immediately after successful scheduling, before the walk can exit. A bounded
close timeout remains an incomplete close with owners retained, never success or detached work.

An alternative direct synchronous internal progress collector would eliminate delivery ownership
but would fork the existing scan path's flow semantics. Reusing the existing task futures keeps
one scan/cancellation policy and makes actual exit explicit without a new persistent authority.

## Engine binding and failed startup

EngineRoot binds the built client's producer only after the physical queue lifecycle attaches.
The existing test ServerFactory drops that lifecycle; replace those test factories with the
same three-argument factory used in production and adapt mocks/real servers to attach it.
No production no-op or missing-attachment fallback is allowed.

Publishing client before bind can retain cleanup ownership but must not let a failed bind or
failed close cause a later start() to return an unready client. Use a root-synchronized readiness
flag, set only after successful binding and cleared before any close attempt. Existing client
and server fields continue owning cleanup; start refuses retained unready ownership until close
finishes. This bounded readiness fact is simpler than a new startup state machine or additional
pending-client owner, and avoids interpreting constructor completion as activation. On bind
failure attempt existing ordered close, preserve cleanup failures/surviving owners, then throw.

## Acceptance

- Exact plan generation/policy/key/epoch and provenance reach real Java service admission.
- Frozen force never triggers global ECC; .2a owns that rule.
- Queued cancellation/saturation invoke no service and release retained work exactly once.
- Running cancellation/deadline requests stop but does not settle before synchronous service
  and delivery task exit. Replacement retains parent work and fails closed on a held producer.
- Missing/wrong generation and root kind refuse without retargeting; full/final batches use
  the .1 guard. Terminal result cannot outrun actual task cleanup or turn IO failure into empty
  success. Cleanup failure settles the exit stage exceptionally, with all owners attempted.
- EngineRoot production binding invokes real producer, clears the unreferenced-method finding,
  and failed bind/close cannot return a ready client on retry. Test factories retain lifecycle.
- Real coordinator + queue + accepted persisted parent/child plans exercise this adapter and
  receipt/parent outcome. Focused negative controls, independent review, full affected suites,
  live API/model and hosted proof precede final C2 reconciliation; prepared handlers follow .3.

## Implementation batch and evidence plan

One per-item implementation commit contains the producer, task-exit correction, Engine binding,
factory migration and regressions. Push immediately; commit explicit WIP at an hour if unfinished.
No transient production fallback or unreferenced-code exemption is allowed. Prepared operation
handlers remain a separate next item (.3), not a reason to call this item or C2 complete early.

The producer projects only its accepted one-root plan; complete means a complete terminal frame
with no refusal reason. Missing/incomplete/EMPTY_STREAM/unknown terminals fail. Cancellation is
reported only after owned exit, and cleanup exceptions settle the stage exceptionally. Both walk
and delivery completion must be awaited even when the walk fails. The callback is internal (queue
receipts carry durable progress); it adds no second persisted progress writer.

Retain EngineRoot.client until its close succeeds; clear readiness before producer shutdown.
A retained unready client prevents start even if prior close failed. Startup mocks explicitly
attach an offline queue through the real supplied lifecycle; actual-server fixtures pass the
supplied registry and lifecycle. This removes the old test factory's discarded ownership input.

Run focused adapter/coordinator/startup tests before full app-engine/app-services suites and
PMD/format, independent source/negative-evidence review, governed docs/gates and hosted checks.
Required live/model/installed and complete C2 proofs remain in the stage checklist.

## Implementation and initial proof (2026-09-14)

The adapter and Engine binding compile at1814. Root installed the fixture migration draft after
correcting the constructor to its existing ManagedChildRegistry.noop() contract; null is forbidden.
Successful mocked servers attach an offline queue (empty generation, online=false) and own its
close. Three missed duplicate factory lambdas were migrated by root;1815's compile failure was
one missed fixture, not behavioral proof. No assertion or production invariant was weakened.

Initial independent review identified cleanup errors discarded on synchronous executor refusal.
1816-rejection-negative executes two tests and both fail for the expected reason: the root walk
throws the Engine-limit error and delivery throws executor rejection instead of the owner-close
failure. The correction preserves cleanup as primary, with scheduling refusal suppressed; clean
saturation retains its existing translation. Runtime task cleanup attempts both registration and
owner release and completes its stage exceptionally if either fails.

1817 executes41 cases/six suites, zero failures/errors/skips, at1a75eb891 plus the producer diff.
It includes real SQLite runner/coordinator/queue and real WorkerIngestService filesystem walking
(directory and single-file), recorded key/epoch/force claims, parent pending until index receipt,
final child acknowledgement; held delivery cleanup for both walk success and failure; combined
registration/owner failures; synchronous cleanup failures; failed bind/client-close retention;
and existing queued work and startup/authority tests. An unused duplicate helper accidentally
inserted into the unary task was removed after this run; final verification must include that
source correction. Other adapter cases and final review/gates/full suites remain pending.

Bulky evidence remains in tmp/1814.txt,1815-rejection-negative.txt,1816-rejection-negative.txt and
its copied XML/count manifest,1817.txt/XML/count manifest; retain through lane completion plus30
days (at least2026-10-14). These are local proofs, not final live/installed/hosted C2 acceptance.

1821 passes48 cases/seven suites plus four PMD tasks and format after deadline correction.
1819's new adapter regression had shown a clean late frame returning COMPLETE after deadline
cancellation. A separate local deadlineExpired observation is required because normal flow.close
also flips the generic cancel signal:1820's first correction incorrectly cancelled five positive
paths, all corrected in1821. No new persistent state or cancellation authority is introduced.
1822 passes seven cases/two suites including real bounded-queue saturation and an actual adapter
held across physical replacement; the original durable parent survives and completes once.

The review's additional subscription-leak hypothesis is refuted by the existing outer
subscribeIndexingWork catch, which closes its flow on every RuntimeException/Error. The public
subscription regression in1821 proves delivery exit and zero active retained work after scheduling
rejection plus producer cleanup failure. No redundant cleanup branch was added on that hypothesis.
Final source review and fault-injection checks remain in progress.

Independent correction review retracts the subscription-leak finding and finds no remaining
production defect. Root tightened service and SQLite claim provenance to exact
EnqueueProvenance(system,SYSTEM_INTERNAL), alongside the existing exact context assertions.
1823 deliberately omits the delivery-exit wait and startup readiness guard: all three cases fail
for the intended reason (two early completions, one unready client returned).1824 deliberately
skips owner release after a registration failure: its combined-owner failure assertion fails.
All mutated source bytes are restored before full1826. The earlier1816 rejection/cleanup and
1819 deadline failures remain the pre-fix controls; no need to repeat those same mutations.
1825 canonical index/skill regeneration and checks, links, three governance gates and store
recoverability pass. Final restored1826 passes3,240 cases/490 suites, with three existing
app-services skips and zero failures/errors. Both full test tasks execute (2,945 services and
295 Engine cases); four PMD tasks and whole format pass. The exact command/revision/counts and
copied XML are retained in tmp/1826-counts.json,1826.txt and1826-xml. Existing JDK Unsafe,
class-sharing and LightGBM fixture warnings remain visible. No warning was suppressed.

The1823/1824 negative driver is tmp/producer-negative.py; original source bytes and failing
XML/counts remain beside the logs. Docs/gates/store outputs are tmp/1825-docs.txt,
1825-governance.txt/SARIF and1825-store.txt. These paths share the retention limit above.
The owned fixture UI helper was stopped through the verified repository sweep (1828); no
dev stack is retained. Hosted predecessor1a75eb891 has12/13 passing CI34896562458 jobs and
passing CLA34896560028; its unreferenced-code failure is not proof for this new binding.
Prepared handlers/REST ingress are the next per-item cut. Final live/model/installed and
hosted acceptance remains required in C2; this local producer proof does not close the stage.

Final independent evidence review reconciles every copied1826 XML and the three existing skips,
all1823/1824 intended assertion failures, and byte-identical restored source against saved
originals. Exact provenance is verified at service admission and SQLite issued claims. Three
PMD tasks and aggregate/other format tasks reuse unchanged results; Engine PMD test and Engine
format tasks execute. The review finds no remaining source/evidence defect for this bounded
item; stage-level live/model/installed/hosted work and public prepared handlers remain open.

Focused1831 executes the exact app-launcher unreferenced-method guard that failed recent hosted
runs: that case passes, with no exemption; the task executes36 cases/16 suites including
the mandatory architecture guards. This establishes the binding clears that
local source-reachability check; the new commit still requires hosted CI. The prior full1826
XML was copied before this focused run. Final1832 canonical/skills/link checks also pass.
