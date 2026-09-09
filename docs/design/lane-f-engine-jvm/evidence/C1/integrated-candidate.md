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
