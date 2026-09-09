# C1 batch 3: admission and exact work lifetime

Status: implementation in progress, 2026-09-09. Base checkpoint `986962ec0`, branch
`worktree-lane-F-A`, Windows/Temurin 25.0.2. Frontend sub-checkpoint `b0078e6` is committed. Integrated build/suite and live admission
proof pass; continuous-search indexing proof and final reconciliation are running.

## Implemented and focused proof

The root owns one EngineAdmissionController, decorating the real lease service under one lock
for register/freeze/release. Front and Engine share it before asynchronous startup. A neutral
EngineWorkHandle retains exact work through asynchronous owners; only its final close releases
capacity. EngineContext carries an optional process-local id, distinct from C2's durable key.
Fairness keys client kind/id; aggregate exhaustion wins a cap collision. Attached rebases cannot
change work-owned survival or urgency. Shutdown freezes admission and requests interactive cancel.

The gauge reads urgency for unary search, ingest, health and streaming work. Durable foreground
work holds one increment per work id until completion/detachment. The former label-parity source
scan is replaced by actual port-dispatch urgency tests. Unary deadlines release callers while
workers unwind; completion is arbitrated after cleanup. Independent timed waiting survives timer
scheduler shutdown. Subscription installation and cancellation use single-owner close, including
pre-install cancellation and an idle delivery thread interrupted during shutdown.

Background callbacks create fresh work. Approval execution reserves a fresh child before consuming
the pending record; it preserves the original eight context fields and exact InvocationProvenance.
An admission refusal leaves the pending record available for retry.

Frontend HTTP, streaming-open and authorized fetch share an abortable admission wait. It handles
the two capacity codes and upgrade refusal, honors Retry-After, and emits one superseding info
message through the existing message model. Replay requires the front's `retrySafe: true` guarantee;
an internal execution refusal cannot authorize blind replay of earlier mutation effects.

Focused commands and accessible logs (worktree `tmp/`):

| Evidence | Result / scope |
|---|---|
| `c1-batch3-gate-tests-3.txt` | PASS: ForegroundLoadGateTest, EngineAdmissionControllerTest, EnginePortForegroundTest, EngineContextPortPropagationTest |
| `c1-batch3-cancellation-tests-1.txt` | PASS: those four plus EngineWorkCancellationTest; first reason, actual occupied worker, deadline through blocked cleanup, transport-close caller release |
| `c1-batch3-wire-tests-2.txt` | PASS: real loopback production filters/controller/MCP framing for POST search, GET suggest, MCP id/code/Retry-After, health, aggregate one/many clients and freeze distinction |
| `c1-batch3-stream-tests-1.txt` | PASS: WorkerIndexingFeedCancellationTest and EngineWorkCancellationTest, including idle actual WorkerIngestService subscription shutdown |
| `c1-batch3-lifetimes-tests-2.txt` | PASS: AuthorizationControllerTest, BackgroundWorkLifetimeTest, EngineAdmissionTransportTest |
| `c1-batch3-ui-retry-tests-1.txt` | PASS: admissionFetch, http and authorizedFetch tests; exact delay, info notice, abort, refusal safety |
| `c1-batch3-ui-typecheck-2.txt` | PASS: frontend typecheck |
| `c1-batch3-ui-gates-1.txt` | PASS: UI governance gates |

These are incremental dirty-snapshot results, not integrated verification of the final candidate.
Earlier failing runs are retained: gate-tests-1 exposed the intentionally retired label API's
remaining test; wire-tests-1 lacked LocalApiServer's body-preserving HttpResponseException mapper
in the fixture. The fixture now includes the same mapper, without changing production refusal.

Independent read-only registry and cancellation reviews found five concrete defects: split freeze
on invalid reasons, attached-axis override, spurious background transition, subscription install
cancellation leak, and idle delivery interruption leak. All were corrected with runnable regressions.
The cancellation review also corrected first-winner arbitration and deadline cleanup ordering.
Reviewers accepted their bounded production areas; they did not run builds or claim full acceptance.

## Remaining before batch acceptance

- Finish the continuous-search indexing run and inspect raw evidence for progress under load.
- Independently reconcile the live artifacts and all batch acceptance, refresh canonical notes,
  and commit the integrated batch. The final executor registry, bounds, extraction pins and
  stress suite belong to batch 4, which must repeat live capacity/pacing after changing the
  executor mechanism. C2 supplies persistent scheduled acceptance and terminal outcomes.

Publication remains F/PR1. The current run state is recorded by the latest progress entry below.

## 2026-09-09 model ownership and retry review progress

Frontend checkpoint `b0078e603210c2c1c64aa5e1c700a5086f125c6f` contains the bounded retry fixes
and tests (the rest of batch 3 remains dirty). Named admission refusals cannot fall through the
ordinary HTTP 5xx retry loop; `retrySafe:false` is retained on ApiError. Single-use streaming
uploads are not replayed. Pre-aborted stream opens never fetch, custom abort reasons do not retry,
and stream signal listeners detach. Focused frontend proof: 40 tests across admissionFetch/http/
streams and typecheck passed, recorded by the worker; root integration is still required.

The neutral StreamRequest now carries the borrowed EngineWorkHandle. The inference owner retains
before enqueue, interrupts the actual producer, closes its active HTTP response body, and retains
capacity until runnable exit. The callback pump retains independently through its actual exit.
The request lock is interruptible. Terminal callbacks follow the callback already running; an
interrupted dispatcher or callback Error cannot become successful completion. The owner only
clears the interrupt of its own cancelled producer, never an unrelated submitting thread.

ConversationEngine attaches for the whole turn and checks cancellation through post-model
consumers, persistence and terminal emission. Cancellation marks callbacks abandoned but waits
for the producer terminal, so a cancellation error cannot overtake a blocked chunk. AgentLoopService
receives the same admission owner through HeadAssembly and lazy orchestration wiring, attaches
for the whole run/finalizer, and binds session stop to model cancellation. Agent cancellation is
never a transient model retry. The optional AgentError.reasonCode preserves the first cause in
wire and persisted event payloads; SYSTEM distinguishes Engine cancellation from the existing
USER/BUDGET/TOOL_LOOP termination causes. Hierarchical summary section and synthesis calls also
carry the work handle; owned timeout/interruption cancels the producer before the turn terminates.

This intentionally supersedes waiter-only cancellation. A separate per-model persistent marker
or per-session registry was unnecessary: the existing neutral work owner supplies the identity,
monotonic cancellation reason and references. Session state borrows its run's handle; producers
retain explicitly. An active uncooperative callback still occupies capacity until it leaves.

Incremental logs:

- `tmp/c1-batch3-latest-focused-1.txt`: RED, exposed CompletableFuture copying a CancellationException
  and hiding its typed first reason. Port boundary now unwraps that copy; original assertion retained.
- `tmp/c1-batch3-model-compile-1.txt`: PASS compile inference/UI and EngineWorkCancellationTest.
- `tmp/c1-batch3-model-cancel-tests-1.txt`: PASS actual HTTP stream cancellation, model lock release
  while the server's first response remains unfinished, retained callback, pre-cancel/rejected pump,
  and existing OnlineModeOps tests. This predates later callback-error regressions.
- `tmp/c1-batch3-model-agent-tests-1.txt`: RED only old manager-arity expectation; corrected to the
  new work argument and added an exact work propagation regression.
- `tmp/c1-batch3-model-agent-tests-2.txt`: app-agent and app-inference full suites PASS; selected
  conversation tests PASS; UI RED only safe-refusal Context fixture missing attribute storage.
  Root/worker are fixing fixture fidelity while retaining the stronger assertion.
- `tmp/c1-batch3-turn-wire-tests-1.txt`: invocation error only; PowerShell split an unquoted dotted
  JVM option. No tests ran. The next command quotes that argument.

Independent review identified and corrected eager cancellation terminal ordering, abnormal pump
exit becoming success, and timeout overriding an earlier cancellation reason. The review also
required post-model cancellation checks. Real controller conversation/agent tests and managed SSE
lifetime probes are in progress. These are dirty-snapshot proofs, not final acceptance. No live
stack is running; required live/integrated batch proof remains as listed above.


## 2026-09-09 stream retirement and setup cleanup

Managed SSE now installs one composed close callback before writing, and owns a pre-created
completion future through Javalin's public Context API. Bytecode inspection of Javalin 6.7.0
confirmed that its keepAlive future can be created after a one-shot close has already fired.
The writer also closes subscriptions installed after synchronous replay triggered a disconnect.
The controller retains work until actual run return and disarms the waiting-client callback before
normal registry retirement. Retirement registration and terminal snapshotting now share one lock,
with callbacks outside it; the old check-then-add sequence could lose a close listener permanently.

`c1-batch3-retirement-green-1.txt` passed; `c1-batch3-retirement-mutant-1.txt` restored the old
production implementation temporarily and failed the concurrent exactly-once assertion. The owned
production file was restored in a finally block. `c1-batch3-turn-wire-tests-4.txt` passed the restored
retirement suite, Engine cleanup/trace/admission tests, and 61 of 62 selected UI tests. Its remaining
failure was the old fixture expectation that Javalin after-filters run before keepAlive completes;
actual after-filter timing is request completion, so the fixture is being corrected without
weakening the occupied-capacity assertion.

Scan setup now encloses cancellation registration, caller-token binding and timer scheduling in
flow ownership. A failing setup releases the asynchronous delivery reference and preserves the
original failure. Subscription setup closes its flow on failed ownership transfer. Unary timer
construction cancels its alarm if cancellation registration fails. New tests assert scan setup
cleanup, entering OTel/MDC propagation across the unary executor, and aggregate refusal precedence
when both admission budgets are full. The subscription producer now closes its flow on Error and
rethrows the fatal cause after reporting it; asynchronous subscription correlation is captured
before dispatch. Focused regressions for these two review findings are being added.

Agent cancellation now has an explicit terminal LifecycleState.CANCELLED; parsing persisted
cancellation no longer falls back to READY_FOR_LLM. Shape fixture and generated frontend error
payload include optional reasonCode. The UI test suite alone adds worker-services so an actual
ForegroundLoadGate can be measured behind the production managed SSE controller; no production
module boundary is widened. Dependency locks were regenerated successfully in
`c1-batch3-test-dependency-locks-1.txt`. Final lock drift verification remains required before merge.


Additional integrated progress: `c1-batch3-durable-sse-tests-2.txt` passes both real HTTP durable
creator disconnect and normal server retirement. Its preceding red run signalled the test latch
before the real gauge listener had run; registering the observation after the gate removes that
measurement race without changing production. `c1-batch3-frontend-full-1.txt` passes 483 files /
6474 tests; typecheck `c1-batch3-frontend-typecheck-3.txt` passes. The suite prints existing
localhost:3000 connection-refused diagnostics from fixtures despite its green verdict. All 27 UI
gates pass in `c1-batch3-ui-gates-2.txt`. Canonical links/index, module graph, runtime configuration
matrix and generated shape handlers pass checks. These remain pre-final-candidate observations.

Full build iterations 1-5 exposed line-ending formatting and unused resource/parameter findings;
owned formatting was normalized, unused helper arguments removed, and lifetime-only resources use
Java unnamed bindings or explicit finally cleanup. No rules were disabled. Build 5 reached all
modules and had one remaining UI test resource-binding warning; that binding is corrected.

Root also found mutation-lease freezing preempted MCP with a REST body before the protocol owner
could preserve its id. The earlier refusal is now carried forward, preserving it across concurrent
unfreeze, and projected by MCP. Real HTTP tests include frozen requests and silent notifications.
Focused rerun and final build are in progress.


`c1-batch3-turn-wire-tests-5.txt` passes the streaming lifetime/early-close/replay/retirement,
frozen MCP request+notification, producer Error cleanup and adverse entering-correlation tests.
`c1-batch3-upgrade-policy-tests-1.txt` passes the assembled LocalApiServer prepare/refresh/cancel/
commit and immutable effective-policy projection, plus startup cap validation. The new diagnostics
current-work count is being verified separately after that run.

`c1-batch3-build-6.txt` is the first complete green build (including PMD and integration tests).
Full suite `c1-batch3-full-suite-1.txt` finished RED in 8m28s across three modules; its
original XML is preserved under `tmp/c1-batch3-full-suite-1-results/`. It exposed two superseded internal overloads
(orderedShutdownSteps and OnlineModeOps.stream), removed with callers migrated, and an old gauge
assertion counting searches but excluding foreground health port calls. The concurrent test still
requires every call to succeed and exact per-call increments; its expected count now includes both
health and search, as C1-10 requires. No quota or exception assertion was relaxed.

The affected notice had no UI harness step. Added engine-admission-wait exercises the real emitter
and real mounted toast twice; same-class supersession leaves one current neutral/polite info notice.
Capture under tmp/c1-batch3-ui-shot/engine-admission-wait.{png,measure.json} passes: 94 accessibility
landmarks, zero axe violations, zero console errors, no overflow. Root inspected the PNG as well.
The first capture found SES rejects import expressions in page.evaluate; the harness now loads the
served module as a module script without changing SES. The second found the test's tone assumption
was wrong: canonical info severity maps to neutral, not the literal info tone. The event still must
be severity info and announce politely. The coverage gate passes 54 mapped source paths.

Tooling identity note: jseval must run from this worktree's scripts/jseval directory (or use its
explicit PYTHONPATH) and both CLAUDE_CODE_SESSION_ID and JUSTSEARCH_AGENT_SESSION_ID must identify
this Codex session. The inherited session-file fallback names the old Claude session. The initial
UI auto-server was tagged to that fallback; the repository sweep identified and stopped only this
newly spawned helper. `agent-spawn-sweep.cjs --help` is not a help command: it defaults to a real
closeout sweep, so inspect that script before invoking unfamiliar options. Subsequent capture used
the correct session identity and the owned helper is stopped after measurement.


The full-suite failures also caught the stale generated SSOT error catalog (regenerated through
`:modules:app-api:updateSchemas`, `c1-batch3-error-catalog-regen-1.txt` PASS) and the old pacing
fixture marking background polling/submissions FOREGROUND. The latter now passes explicit
BACKGROUND contexts while searches remain FOREGROUND; all zero-poll-gauge, nonzero search
pacing, visible indexing progress and eventual resume assertions remain. This is the accepted
C1-10 urgency migration, not an operation-name exception restored to production. Focused
corrections and final full-suite rerun remain pending.


## Integrated candidate and live admission proof (2026-09-09)

`c1-batch3-integrated-2.txt` passes `build test :modules:ui:installDist`: every Java source set,
PMD, integration checks and full unit suites. The reconciled unit XML contains 9551 tests,
zero failures/errors and 25 skips (`tmp/c1-batch3-integrated-green-results/` and
`c1-batch3-integrated-green-counts.json`). Integrated-1 preserved the one remaining old stream
fixture call after overload retirement; it was migrated to the canonical signature and
app-inference reran in integrated-2, while unchanged suite results remained up-to-date.
Integrated-1 XML is retained separately. The first install named app-launcher; the final
successful candidate explicitly installed the UI-owned single Engine distribution.

Aggregate live run `15af132d-79fb-4175-b337-789b3ccc82b4`, API60361, installed stamp
`b51bac7b2258d68a`, cap3/perContext16 and baseline1: both arms offer 2 real compact-model chat
holders plus 2 hybrid searches. Both admit 2 and refuse 2 with ADMISSION_ENGINE_LIMIT and
Retry-After1; all holders emit done once and clean EOF, every refusal header precedes every
holder terminal, and both return to baseline1. Raw captures are
`tmp/c1-batch3-live-aggregate-1/context-{many,one}.json`; root independently inspected them.
This run was stopped before restoring packaged admission limits.

Fairness live run `d3c22cf7-be20-4f55-a900-c34b03077cff`, API52053, same installed stamp,
packaged perContext16/aggregate64: 16 same-client real chat holders, 3 same-client probes
(search/suggest/MCP), health and a different-client hybrid search. The 3 refused probes return
429 ADMISSION_CONTEXT_LIMIT with Retry-After1, MCP preserves id and -32000, health and the
other client's search return200, all holders complete cleanly and baseline1 is restored.
Capture `tmp/c1-batch3-live-fairness-2/fairness.json` passes strict overlap. The first capture
is retained RED: all responses were correct, but its 64-token first chat ended at1058ms before
the other client's cold search headers at1499ms. Fixed fairness workload now requests512
tokens of counting; aggregate remains64. Timing assertions and five-minute deadlines were
not relaxed. Oracle selftests pass45 adverse cases, including malformed/unterminated SSE,
error/missing-done responses, wrong rejection reasons and early holder completion.

The real model is compact Qwen3.5-4B-Q4_K_M; these are admission/model plumbing proofs,
not model quality acceptance. The active default-cap run is now ingesting the committed
synthetic prose fixture under jseval continuous hybrid search. Its corpus quality-certification
warnings are retained; no quality score or baseline is being claimed from this load fixture.


Independent raw-capture audit recomputed counts and timings without executing the analyzer.
Fairness-2's latest probe header is333.9829ms and earliest holder terminal4829.087ms. The
activeAtProbe boolean is a derived convenience, not an independent server observation; actual
SSE timestamps supply the overlap evidence. Baseline counts prove net capacity recovery, while
identity-specific release is established by the controller/port/model lifetime regressions.
Neither the reduced-cap pair nor the fairness run claims aggregate64 saturation. The loaded
policy projection is cross-checked by policy/controller tests and the known startup override.
No additional work-identity endpoint or mutable policy authority is needed for these claims.

## 2026-09-09 completed pacing capture (pre-registry/pre-RMW correction)

Run `d3c22cf7-be20-4f55-a900-c34b03077cff`, installed stamp `b51bac7b2258d68a`,
HEAD `b0078e603` plus batch-3 working changes, compact CUDA model. Executed from scripts/jseval:
`python -m jseval run --dataset golden/synth-multihop-prose-v2 --modes hybrid --max-queries 1 --pipeline --search-load continuous --base-url http://127.0.0.1:52053 --output-dir tmp/eval-results/lane-f-c1-batch3-pacing --timeline tmp/eval-results/lane-f-c1-batch3-pacing/timeline.tsv --json`.
Exit 0. Raw log: `tmp/c1-batch3-live-pacing-1.txt`. Summary:
`scripts/jseval/tmp/eval-results/lane-f-c1-batch3-pacing/20260909T034838_golden_synth-multihop-prose-v2/summary.json`.
Timeline is alongside that run directory at `timeline.tsv`.

7,048 issued / 7,048 successful / zero errors over 2,018.127 seconds; latency p50 322.8 ms,
p95 376.389 ms. Readiness passed with no failure reasons. Initial document count 5, final 474,
reported indexed 469; do not substitute fixture size for the measured delta. Final embed/SPLADE/
chunk were 100%, NER 474 with zero pending. Synthetic 468-document fixture is a load proof,
not a search-quality certification; its warnings and single scored query remain in the report.

The same report records 87 SPLADE churn drops during parent RMW. This is adverse evidence for
the required bounded correction in batch 4, not an ignored advisory. Repeat admission and pacing
on the final registry and enrichment candidate. The owned dev stack was stopped successfully,
ports closed; stop receipt `tmp/dev-runner/runs/d3c22cf7-be20-4f55-a900-c34b03077cff/stop-report.json`.
