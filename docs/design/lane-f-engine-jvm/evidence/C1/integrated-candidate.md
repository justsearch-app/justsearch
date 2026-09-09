# C1 batch4 integrated verification ledger

2026-09-09 Windows / Temurin25.0.2. Current installed-candidate work begins at4a57950ae.
C1 is not complete. Raw paths are relative to lane-F-A; retain through lane completion plus30 days.

| Run | Check | Result |
| --- | --- | --- |
| 136 | check-logic-seams --mode gate | PASS16 seams |
| 137 | test-dev-runner-head-java-opts | PASS exact dev-runner and lib.rs sets |
| 138 | Focused NRT/generation/fanout with --rerun-tasks | PASS5tests,93/93tasks executed; see lucene-generation-lifetime.md |
| 140 | build -x test | FAIL1 redundant qualifier in OCR test fixture |
| 143 | build -x test after fixture correction | PASS332tasks;10executed322up-to-date |
| 141 | UI typecheck | PASS |
| 142 | UI full unit | PASS483files6474tests; localhost/teardown console diagnostics under audit |
| 148 | UI lint | FAIL1 unused initial assignment in admissionFetch; corrected; restored lint154 and typecheck155 pass, seven focused tests156 pass |
| 144 | engine-port gate | PASS1 informational finding |
| 145 | register-guard-resolution gate | PASS |
| 146 | check-store-recoverability | PASS6catalog stores44durable authorities27policies |
| 147 | test -PincludeStress=true --console=plain | FAIL: only SystemAccessFunnelTest; Engine stress/read-write tests pass |
| 149 | run-ui-web-gates | PASS27/27 |
| 150 | regen-all --check | PASS8generated sets |
| 151 | run-publish-preflight --check/--list | PASS11classified required checks; classification is not hosted execution |
| 152 | docs-validate | PASS |
| 153 | verify-canonical-doc-links | PASS156files |

Logs: tmp/c1-batch4-{logic-seams-136,java-opts-137,lifetime-executed-138,build-140,build-143,
ui-typecheck-141,ui-unit-142,ui-lint-148,engine-port-144,register-guards-145,
store-recoverability-146,full-stress-147,ui-gates-149,regen-150,publish-preflight-151,
docs-validate-152,doc-links-153}.txt. Proof scope is the combined candidate; cached outputs are
revalidation and are not labelled fresh execution. Final live, platform and hosted proof is pending.


SchemaMismatchStatusContractTest is now executed proof: the integration XML timestamp is
2026-09-09T09:59:58.763Z, one test, zero failures/errors/skips,7.209seconds. The actual
/api/status response exposed reindexRequired and index_fingerprint in build140 despite that
build's unrelated PMD failure; build143 revalidated its unchanged output. Preserved XML:
tmp/c1-batch4-schema-mismatch-green-140/TEST-io.justsearch.ui.api.SchemaMismatchStatusContractTest.xml.

The engine-port informational finding in144/158 is positive coverage evidence: scan found3
implementations at floor3. Combined run158 avoids overwriting evidence between separate kernel
runs; raw SARIF is tmp/c1-batch4-combined-governance-158.sarif. Both gates pass.


Full stress147 completed in8m28s. Its sole failing suite was SystemAccessFunnelTest: the new
LauncherEnvironment.restoreProperties helper bypassed the configuration funnel. Root now uses
existing SystemAccess.setSysProp for the three boot properties (null clears them), and removes
only the obsolete LauncherEnvironment#close allowlist entry. No exception is added to the ratchet.
Full147 XML is preserved under tmp/c1-batch4-full-stress-results-147 with a timestamped manifest;
other modules' compatible preexisting results are included, so the manifest is not a claim that
all1109 suites executed in147. Run162 is the integrated build/test/installDist revalidation.

UI console audit atc57525718 traced the refusals to preexisting unmocked HealthLitView recovery
fetches and UnifiedChatView agent-tools polling. The pre-C1 tmp/b-ci-ui-unit.txt already has the
same profile as142:887 AggregateError clusters,24 AbortErrors,1316 localhost3000 refusal text matches
and458 localhost5173 refusal text matches. Root independently compared both raw logs. These are existing
test-fixture diagnostics, not evidence of a C1 regression; no console suppression was introduced.
Responsible callsites: HealthLitView.ts connectedCallback/updated/fetchRecoveryIndex and
HealthLitView.test.ts mounts without fetch mocks; UnifiedChatView.test.ts controller creation via
agentSessionStore.ts starts AgentSessionController tool polling. The audit did not rerun individual
tests with network instrumentation and does not claim all UI fixtures are network-isolated.


Integrated162 finished in4m45s:9718tests represented in1595suite XMLs, one failure only in
LocalApiServerThinComposerTest (32fields vs the existing30ceiling). Root removes the constructor-only
slowRequestExecutor field while retaining its registered owner, and moves the borrowed per-source
search owner into the existing CoreApiAssembly.Result next to its controller. Both eager and late
wiring read the same owner. No new wrapper or ceiling increase is needed. All other suite outputs
pass, including SystemAccessFunnelTest after fix239ac1a6c. Snapshot:
tmp/c1-batch4-integrated-results-162/manifest.json. Restored build/test/installDist164 passed in1m27s (368tasks;22executed346up-to-date).

Residue163 finds ForegroundLoadInterceptor only in dated design/tempdoc history and ADR0048's
explicit historical account. No production/test symbol remains. Raw grep:
tmp/c1-batch4-foreground-residue-163.txt. Admission oracle160 passes45adverse cases; this proves
the capture validator, not live Engine admission.


Integrated164 is green: build/test/installDist with stress enabled; unit manifest9718tests,
zero failures/errors,25existing skips. Configured integration outputs36tests,zero failures/errors,
10skips. The snapshot also retains other previously generated source-set reports; do not credit
its old loadSensitive/system/updateSchemas reports as fresh164 execution. Full timestamped manifest:
tmp/c1-batch4-integrated-green-164/manifest.json. Source fix65f65d36c is pushed. Exact previous
red results are preserved rather than overwritten. Installed standard-model live proof and final
hosted/platform proof remain outstanding.


## Installed standard-model campaign (2026-09-09, in progress)

Installed revision `db368e353`, stamp `ddfbc9256c22bbd4`, Windows/Temurin 25.0.2.
The current worktree dev runner rebuilt/installed before launch. The attached older MCP
preflight still requires the deleted Worker distribution; its other checks passed. The
worktree runner uses the same shared lease and single Engine distribution (the already
recorded stale-helper correction), not a second stack or restored Worker artifact.

Aggregate run165/166: owned run `ad26d7c9-3cd2-449f-8611-5f807aa9e965`, API54446,
fresh `tmp/c1-final-standard-aggregate-data`, eval mode and aggregate limit3. Initial
`worker.core.indexedDocuments=0`; standard activation completed and inference status
reported `Qwen_Qwen3.5-9B-Q4_K_M.gguf`, online/available, CUDA12, context32768, two slots.
`node scripts/jseval/lane-f/admission-loop.mjs --capture tmp/c1-final-standard-aggregate --base-url http://127.0.0.1:54446`
and `--analyze tmp/c1-final-standard-aggregate` both PASS. Each arm offered4, admitted2,
returned2 HTTP429 `ADMISSION_ENGINE_LIMIT`, with no per-context rejection. Baseline and
final active count1; configured per-context16/aggregate3. Independent raw JSON reread:
many-context latest overflow headers37.0783ms precede first holder terminal1235.7305ms;
one-context10.73ms precedes1050.2529ms. Each holder reports one done, zero errors, EOF.
This is reduced-cap aggregate proof, not saturation of the default64. Stop succeeded,
ports closed. Logs165/166 and captures are under tmp/c1-final-standard-*.

Default run167/169: owned run `a7a93cdb-cd78-4069-9b74-b95fabe35ae3`, API61400,
fresh `tmp/c1-final-standard-default-data`, eval mode, default aggregate64/per-context16.
Initial indexedDocuments0; standard activation completed. The fairness capture command
`node scripts/jseval/lane-f/admission-loop.mjs --capture-fairness tmp/c1-final-standard-fairness --base-url http://127.0.0.1:61400`
passes:16 holders complete with one done/zero errors/EOF each; same-client search,
suggest and MCP each return429 `ADMISSION_CONTEXT_LIMIT` with Retry-After1; health and
another client's search return200. Independent raw reread: latest probe headers1672.918ms,
earliest holder terminal7971.5026ms, final terminal120576.8286ms. Active baseline/final1.
Raw `tmp/c1-final-standard-fairness/fairness.json`; log169.

Run170 FAILED (exit1): continuous hybrid search while ingesting the identity-verified469-file
synthetic corpus through jseval. All469 reached the index, but at approximately186s enrichment
stalled (embedding2.3%, SPLADE50.3%, NER0/469) with GPU100% and11.5–11.6GiB VRAM use. Status
returned429 repeatedly; readiness declared the backend unreachable and the query retried five
times with429 before failing. Health remained READY. The relaxed jseval document floor must not
be used as proof: the next run must reconcile all469 inputs independently. This is no pacing or
SPLADE-success claim. Raw log: tmp/c1-final-standard-pacing-170.txt.

Thread dumps tmp/c1-pacing-stall-176.json (10:43:01UTC) and177.json (10:43:53UTC) independently show
indexing-loop in OnnxEmbeddingEncoder native OrtSession.run, one foreground reranker in native
OrtSession.run, and15 foreground callers blocked in NativeSessionHandle.acquire's uninterruptible
GPU semaphore. The Engine deadline already interrupts work and retains admission until actual
exit. The targeted correction makes that existing interrupt effective for waiters, while an issued
native lease remains held until actual exit. It introduces no new cancellation state or owner.

The owned default stack was stopped normally; its receipt is under main's tmp/dev-runner/runs/
a7a93cdb-cd78-4069-9b74-b95fabe35ae3/stop-report.json. Two unregistered compact llama processes
(PIDs25200/32540, created September7, absent parents43212/14972) had remained on GPU throughout
this campaign. Exact executable/model/port/start-time, absence of established clients, and the
ownership register were checked before retiring only these abandoned processes. World-state178
and the session's cleanup output retain the evidence. Admission166/169's raw count/order evidence
still holds, but carries this co-resident-hardware caveat; it is not a performance benchmark.
Fresh pacing is required without these abandoned consumers.

Hosted34339800148's three failures and local corrections are reconciled in hosted-ci.md.
Strict focused181 passes; new waiter proof182 initially failed compilation because ort-common did
not declare Mockito. Root added the existing catalog dependency and regenerated locks in183
(PASS). Focused waiter plus real Engine broken-child workflow proof184 passes; restored186 passes,
Tika mutation185 fails correctly, and native waiter mutation187 fails correctly. See their owning
evidence pages. Filtered stress188 stops on modules with no matching test names. Full integrated
stress190 is running and reported a format-matrix initialization timeout. Hosted1c7fcfff9 still
has ADR-coverage (corrected3ccf3749b) and indexing-to-action-ledger integration failures. No final
C1 acceptance is claimed.


## Integrated190 and observer correction

Run190 completed FAILED in14m38s (368tasks:94executed,17fromcache,257up-to-date).
Snapshot tmp/c1-integrated-native-stress-results-190/manifest.json contains1597unit suites,
9712represented tests,1failure,0errors,25skips; configured integration36tests,0failures,10skips.
The lower count includes the matrix's failed setup rather than its11 normal cases. Existing
NativeSessionHandleConcurrentStressTest executed at11:07:40UTC and passed; the three GPU waiter
cases passed, and all six ForegroundLoadGate cases executed at11:20:59UTC and passed. Their
meaning and native shutdown limits are unchanged. All XML was copied before focused reruns.

The sole failure is EngineFormatCapabilityMatrixTest.indexTheMatrix: exact XML says30seconds,
not the class's600seconds. JUnit class-level Timeout does not apply to BeforeAll. Its120second
index wait was interrupted while processing the third fixture, after fixture generation and
Engine boot consumed about17seconds. The same log shows the test's own status polls activating
foreground pacing. EngineTestHarness.status had been mechanically stamped FOREGROUND in C1,
although it is an observer; the existing EngineForegroundPacingTest correctly uses BACKGROUND
for these reads. Root corrects the helper and matrix ledger polling to BACKGROUND, and applies
the already-declared600second suite budget explicitly to setup. Content, ledger, identity and
bounded120second convergence assertions remain unchanged. Focused restored202 passes all13cases: matrix11 (16.003s including setup), Engine foreground
pacing1 (27.433s), broken-child workflow1 (3.436s). XML is preserved in tmp/c1-matrix-restored-202/.
This Engine pacing test uses the deterministic fixture path; final standard-model pacing is
still required. The read-only audit independently confirms the setup timeout and that the new
broken-child workflow ran later than the failed matrix, so it could not contaminate that setup.

Public claims local-subset197 passed lock completeness/install and54/55analytics files; only
world-state.test's performance assertion failed at10108ms against its unchanged10000ms bound
while integrated190 was running. With Gradle stopped, standalone200 passes all16world-state
checks. No threshold/suppression changed. The remaining manifest commands201 PASS, including governance tests, docs, skills, and canonical
Markdown. Together197 plus targeted200 and continuation201 reconcile the Public claims local
subset. This does not replace the separate hosted ADR gate or hosted integration tier.
Local npm reported the existing ini7 engine-range warning on Node24.12.0; it was not hidden.
