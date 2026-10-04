# C1 batch 4: executor ownership and confinement

Current execution ledger: [integrated-candidate.md](integrated-candidate.md). The notes below retain
initial registry/composition findings; the producer, fanout, OCR, SSE and guard slices are now
committed and pushed with dedicated evidence. C1 remains open for final integrated/live/hosted proof.

Status: in progress, 2026-09-09, above `95ac489b2` in `worktree-lane-F-A`.
Several composition slices compile and pass focused tests; later producer migrations and the
registry self-close correction still require verification. The completed batch-3 pacing measurement
used the previous installed distribution and does not exercise batch 4. Stage C1 is not complete.

## Registry slice

The neutral core contract describes a unique logical registration, its foreground/background
kind, platform/scheduled/virtual mode, queue/thread limits and explicit instance multiplicity.
DefaultEngineExecutorRegistry projects limits from the existing authoritative policy and owns
all concrete instances. Queue, timer, instance and closed refusals are typed. It counts a
shutdown-but-running executor until actual termination. Close initiates all shutdowns before
waiting against a common deadline, without holding the registry lock during shutdown or joins.

Scheduled tasks use the JDK decoration seam and independent local/global timer permits.
One-shots release before callbacks; periodic tasks release on failure/cancel/shutdown.
Failed scheduling removes precisely the decorated task, including a throwing ThreadFactory.
Submission bookkeeping is internal to the scheduling call, not EngineContext propagation.

Independent composition review corrected the first draft's forever-reserved logical names:
the actual index boot-recovery path recreates owners in the same process. Closed names are
reusable only after every old instance terminates; an old live instance still refuses replacement.
Overlapping service/runtime instances require stable index-owner registrations, as documented
in stage C1 section 13. Process registry close is separate from restartable WorkerHost.close.

Root review corrected a shutdown lock inversion, delayed-task shutdown policy and exceptional
submission cleanup. Before executing the tests, root also corrected an ineffective test start
barrier, ensured failures from racing threads reach the test thread, waited for actual periodic
wrapper drain before checking permit release, and strengthened the aggregate-deadline test so
per-instance deadlines cannot pass. The restored implementation passed in `tmp/c1-batch4-registry-rmw-tests-3.txt` (15 registry tests).
Two deliberate mutations failed their intended assertions: a separate deadline per executor
failed the six-instance elapsed-time bound (`tmp/c1-batch4-registry-mutation-results/deadline.xml`);
dropping reentrant submission restoration leaked the outer timer permit (expected 1, actual 2,
`tmp/c1-batch4-registry-mutation-results/reentrant.xml`). Both mutations were restored. A subsequent
restoration run encountered an in-progress enrichment dependency compile and is not green evidence
(`tmp/c1-batch4-registry-restored-tests.txt`). Rerun after both source slices stabilize.

## Required next proof

Restored integrated build/test/installDist164 is green. Rerun installed standard-model
aggregate admission/fairness and continuous-load pacing on that bounded-executor candidate.
Required hosted/platform checks remain separate. The original registry deadline/reentrancy,
producer ownership, raw/async guard, confinement, and actual-exit mutation proofs are recorded in
the dedicated evidence files linked from C1 section13 and the integrated ledger. Do not repeat
old unperformed labels below as the current state.

The parent SPLADE RMW correction95ac489b2 has52focused tests and three adverse mutations
([parent-splade-rmw.md](parent-splade-rmw.md)); the new installed continuous-load run still has to
show the churn is gone. Keep raw evidence under this worktree's tmp through lane completion plus
30days, with exact command/revision/environment bindings in the current ledger.

## Composition decisions and current edits

The logical-factory census floor is 63: 58 inherited sites, OTel's periodic metric reader,
and four default JDK HttpClient executors. JDK 25 source shows its missing-executor branch builds
a cached pool; inject executors at InferenceLifecycleManager, ManagedChildReconciler,
OpenAiCompatController and RuntimeActivationService. ExternalLlamaServerClient remains an
external system-test harness exception. OTel span processors have library-owned threads and no
executor setter; registry coverage is not a claim to own every library thread.

EngineRoot constructs the registry from the same loaded policy as admission. HeadlessApp now
constructs it before telemetry and closes it after Head/index/tracing/telemetry, before the
instance lock. WorkerHost.close remains restartable. LauncherEnvironment uses exactly one
ServiceLoader provider (metadata in app-engine), with no fallback implementation. HeadAssembly
and KnowledgeServer require the injected neutral contract. Component tests use a core test-fixture
executor double; accounting and policy assertions must use the actual production implementation.
Initial wiring and lifecycle checks pass in composition-tests-4/5 (below). Later migrations require
fresh compilation; dependency locks need final regeneration after all constructor/dependency changes.

Overlapping KnowledgeServer services share stable owner registrations: watcher/readers each 2,
extraction timebox 2 globally, OCR 1, index commit 3 (active/building/previous), request/hybrid/chunk
virtual instance caps from effective aggregate admission. Per-source Head search instead owns one
persistent virtual executor. Do not re-register names per generation or erase live retired instances.

Independent enrichment review found three remaining issues after the initial 35-test green run:
SPLADE-only updates could survive a stopped/deferred unrelated stage; withheld windowed embedding
writes lost their completed accumulator; in-memory counts could falsely report durable progress.
The existing WindowedEmbedProgress owner will retain completed vectors until a successful write.
The correction now preserves the canonical blank-content reset behavior and retains completed
window accumulators until full durable write success. The dedicated RMW evidence supersedes the
earlier preliminary 35-test result and closes those three findings locally.


## 2026-09-09 composition proof and remaining migration (05:14 UTC)

All paths below are worktree-relative and retained through lane completion plus 30 days.
These are dirty-worktree local proofs above `95ac489b2`, not hosted or final-stage proof.

- `tmp/c1-batch4-composition-tests-4.txt`: PASS, 185 tests, zero failures/errors/skips
  (Engine 25, services 42, launcher 65, UI 53). XML/counts:
  `tmp/c1-batch4-composition-green-4/`. Earlier run 3 failed one pre-cancel correlation fixture,
  the superseded broad telemetry/core ban, and two missing HTTP-context fixtures. Those exact
  failures are preserved in `tmp/c1-batch4-composition-red-3/`; all were corrected before run 4.
- `tmp/c1-batch4-composition-tests-5.txt`: PASS, selected EngineKnowledgeClient executor/queue,
  watcher, runtime-activation, shutdown/startup wiring, REST and MCP refusal tests. XML preserved
  in `tmp/c1-batch4-composition-green-5/`. This predates current registry self-close handling,
  launcher scoped-global/default-path repair, and the 17 stream-controller migrations.
- `tmp/c1-batch4-composition-tests-6.txt`: RED only in the new launcher late-failure test: the
  smoke profile's unsupported nested data-directory placeholder failed before the target phase.
  Registry cancellation ordering, the runtime-activation slice, five-family confinement and
  parser/layering guards passed. The test now supplies an explicit temporary directory for its
  post-publication failure scenario; the actual smoke default-path defect is also repaired in
  source, with a separate no-override regression. Rerun both before claiming this correction.
- `tmp/c1-batch4-future-cancel-order-mutant.txt`: RED for the intended reason. Restoring public
  future cancellation before underlying task cancellation lets a queued supplier run while a
  synchronous completion dependent is blocked (expected invocation count 0, actual 1). XML:
  `tmp/c1-batch4-future-mutation-results/cancel-order.xml`. Source restored in finally.
- `tmp/c1-batch4-telemetry-boundary-mutant.txt`: RED on exactly one forbidden field dependency
  from LocalTelemetry to core.context.EngineContext; core.execution remains the sole allowed
  core package. XML: `tmp/c1-batch4-boundary-mutation-results/telemetry.xml`.
- `tmp/c1-batch4-parser-boundary-mutant.txt`: RED on exactly one injected app-services field
  referencing PDFBox PDDocument outside the two named parser owners. XML:
  `tmp/c1-batch4-boundary-mutation-results/parser.xml`. Mutation class removed in finally.

The completed confinement slice retains AUTO process routing after a failed child probe. Missing
child-command acquisition is classified as SandboxExtractionException/SANDBOX_FAILED; source-file
I/O retains its separate IO_FAILED semantics. All five real detected process families fail at the
boundary while text, Markdown and CSV succeed through the same extractor. Deferred services now
allocate no extractor. The reflective AOT exception is limited to three non-initializing Tika
loads, and the VDU PDF rendering exception names exactly PdfImageRenderer.

Independent review confirmed EngineFutures task-first cancellation and conditional ConfigStore
restoration. It additionally found final process registry close interrupted the watcher callback
that was invoking it. The source now tracks only concrete executor identity on each worker, stops
the invoking executor without self-interruption/self-await, and cancels/waits all other instances.
A real watcher callback regression checks the other owner is awaited and the active watcher stays
visible through actual callback exit. This new correction still needs its green run and mutation.

The remaining producer work includes worker watcher/timebox/readers (currently delegated), OCR and
Lucene bundle wiring, search fan-out, remaining Head producers and inference urgency separation.
Then enforce the raw-factory/bare-async census, run full/stress checks, regenerate docs/locks, and
repeat real-model admission plus continuous indexing/search against the final installed candidate.
No C1 acceptance is waived; C2/D/E/F and F/PR1 publication remain subsequent required lane work.
