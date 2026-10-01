# Reconfigure independent-review corrections (2026-09-30)

Active scope: fix all findings on reviewed commit `5ccfa74a8`, in `codex/lane-f-reconf-inplace`, without committing, pushing, or starting any backend/Engine/dev stack. Root decisions supersede the flat response fields introduced in that commit.

Acceptance:

- Replace SettingsV2 flat evidence with nullable response-only `composition` (mode, reason, freeBytes, footprintBytes), present on success and typed composition refusal; identical SSOT copies; generated TS/fixture; documented values/refusal placement.
- Realize required CUDA B through bounded warm-up inference before returning preparation; restored GPU A must realize before READY publication. Any failure precedes settings replacement and restores A.
- Restoration Error retains existing recovery ownership and both causes.
- Installed query-before-runtime checks, outage sampling from POST issuance through completion, per-apply counters, and held issued requests across transition.
- Composition exceptions carry measured evidence in typed refusal and replay.
- Four physical IN_PLACE transaction regressions inject actual later-owner, validation, cancellation and replacement failures. Each must be demonstrated red once, then green; no manual abort labels.
- Focused compilation/tests/formatting only after exact `gradle-reconf2` grant exists. Poll at 60 seconds up to 170 minutes while independent work continues.

Design investigation: CrossEncoderReranker.rerank has a best-effort native deadline and cannot force-stop native CUDA construction/run. The proposed bounded caller wait therefore reuses EngineFutures actual-exit ownership, the existing deferred-model executor and a QueryRoleSet lease retained until actual task exit; cancellation must never imply native retirement. A timed-out candidate cannot release memory or restore A until existing cleanup proves retirement. Existing encoder recovery owns any refused cleanup, including an untouched BESIDE predecessor. This is a transient preparation lifetime, with no durable state, new executor, writer, marker or transition coordinator. Independent refutation requested before implementation.

Durable replay correction: the existing operation receipt JSON carries one optional typed
`CompositionV2`, extracted only from the success result's `structuredData.composition` or a failure's
`errorDetails.composition`. This uses the existing `result_json` writer and schema; no table migration
or second writer is introduced. Receipt validation admits only the six physical device-memory reason
tokens. All other structured handler content remains excluded. The exception is required because a
same-key settings replay is served from the durable receipt after the original rich result is gone.

Test ownership: a narrow indexer-worker testImplementation(app-services) edge permits physical owner tests to traverse the real fixed composer/coordinator without duplicating the existing QueryFixture; production dependencies remain unchanged. Regenerate the canonical graph for this test-only edge.

Verification/results and remaining environment proof will be added here. Installed execution remains prohibited and assigned to root.

Governing clause reconciliation (production changes; execution still pending):

- D1:372: CUDA realization and validation remain before SettingsCommitCoordinator's existing replacement boundary. Cleanup/restoration happens before a precommit failure is returned; no settings-file commitment moved.
- D1:424-431: the physical query owner traverses the real fixed composer/coordinator for second-owner, registry validation, cancellation admission, and replacement I/O refusals; each asserts unchanged settings bytes/witness/configuration and exact A query identities.
- D1:2523-2528: existing BESIDE/IN_PLACE owner tests preserve serving A versus lexical RELOADING; native warm-up/fallback/Error regressions add device verification and retained recovery, with both reasons. The installed ceiling is set before launch and samples include preparation.
- D1:3150: the existing ai-tagged installed BESIDE/forced-IN_PLACE scenario remains the executable installed proof; this task edits it but does not launch it, per root authorization.
- design.md:1376: only fully realized, validated B reaches the existing commitment; source restoration uses frozen exact A configuration/selection and verifies its prior realized GPU device.
- design.md:2312: availability sampling starts before each POST, captures A requests across the transition, checks zero outages/restarts and unchanged generation, and queries before CUDA diagnostics. Installed execution is unverified.

Independent refutation accepted the actual-exit warm-up lifetime and the bounded composition addition to existing operation receipt JSON. The receipt carries only six DeviceMemoryLine reason tokens, an enum mode and nullable nonnegative byte counts; arbitrary handler maps/prose remain excluded. This is a projection on the existing writer and table, requiring no migration. Recovery retry Error follows the existing typed FAILED path while retaining the original B/restoration causes in its reservation.

Verification correction: the operations outcome test used an older local schema generator that omitted nullability for the newly nested CompositionV2. Failure XML showed a mismatch on all four reference fields versus canonical WireSchemaConfig and actual explicit-null serialization. Independent review approved a scoped nullable-reference mirror for that type plus a wire/schema conformance test; golden equality remains intact. The old issued-query timeout assertion was updated for the root-mandated typed refusal and now also checks its IOException cause and measured IN_PLACE decision.

Verification complete on HEAD `5ccfa74a8cad1461ea4da282fc8bda578f375aa9` plus the uncommitted source snapshot at `tmp/reconf2-evidence/final-source.patch`. No commit/push, Engine/backend/dev stack or installed scenario execution occurred. The grant was polled every 60 seconds and was present before every Gradle invocation. Builds ran serially.

Final green: 202 focused Java tests, zero failures/skips; 12 TypeScript contract tests; TypeScript typecheck. Four installed supervision Java source files compile with javac using the current integration compile classpath (19-installed-javac.log), without running them. SpotlessApply passed. Both schema copies are byte-identical; repository wire generation/check and documentation/module/config checks passed. The exact requested `LC_ALL=C.UTF-8 git diff | grep -P '^\+.*[^\x00-\x7F]'` printed nothing (exit 1 means no matches); all new Java/Markdown files are ASCII and valid UTF-8.

Red/green proof:

- Later-owner, registry validation, precommit cancellation, and replacement I/O: all 4 failed with restoration disabled, each reporting expected 2 physical compositions but actual 1. Correct restoration restored; all 4 pass in final 16.
- Warm-up removal: realized-before-return and CUDA-to-CPU fallback refusal both fail for the intended reason; both pass in final 16.
- Restoration Error catch removal: failed to publish UNAVAILABLE/recovery ownership; the regression passes after restoring the Error catch in final 16.
- Red XML retained in `tmp/reconf2-evidence/12-red-results`, `13-red-results`, `14-red-results`; final green XML retained in `final-results`. All temporary production mutations were restored before final verification.

Unverified: the installed CUDA scenario, real CUDA A/B/restoration, installed zero-outage/restart/generation and refusal replay observations. Root owns installed execution. Warm-up bounds the caller's preparation wait, not forced termination of native work; a timed-out native lifetime stays owned until actual exit. Existing compiler/deprecation warnings remain. No design contradiction was found; the preexisting local schema generator needed a faithful nullable projection correction. The corresponding Codex and Claude inference skills were manually reviewed; Claude's canonical embedding was regenerated.

Every Gradle command below ran with GRADLE_USER_HOME=tmp/gradle-home and JAVA_HOME=Temurin25. From 02 onward JAVA_TOOL_OPTIONS set java.io.tmpdir to this worktree's tmp/java-temp. Full output is in the matching NN log under tmp/reconf2-evidence. Counts are executed/selected test cases, not compiler task counts.

| Log | Tests | Result | Exact Gradle command |
|---|---:|---|---|
| 01 | 0 | FAIL: PowerShell split JVM flag; corrected in 02 | `./gradlew.bat :modules:app-api:test --tests io.justsearch.app.api.schema.WireRecordSchemaGenTest.settingsV2 --tests io.justsearch.app.api.schema.WireRecordSchemaGenTest.operationOutcomeView -PupdateSchemas=true -PskipWebBuild=true --offline -Djava.io.tmpdir=F:/justsearch-public/.claude/worktrees/lane-f-reconf/tmp/java-temp` |
| 02 | 2 | PASS: schema writer | `./gradlew.bat :modules:app-api:test --tests io.justsearch.app.api.schema.WireRecordSchemaGenTest.settingsV2 --tests io.justsearch.app.api.schema.WireRecordSchemaGenTest.operationOutcomeView -PupdateSchemas=true -PskipWebBuild=true --offline` |
| 03 | 0 | FAIL: fixture tokenizer matcher used List instead of String[]; corrected | `./gradlew.bat :modules:indexer-worker:compileJava :modules:indexer-worker:compileTestJava --write-locks -PskipWebBuild=true --offline` |
| 04 | 0 | FAIL: fixture constructor missing nullable citation path argument; corrected | `./gradlew.bat :modules:indexer-worker:compileJava :modules:indexer-worker:compileTestJava :modules:ui:compileJava :modules:ui:compileTestJava :modules:app-observability:compileJava :modules:app-observability:compileTestJava :modules:app-services:compileJava :modules:app-services:compileTestJava :modules:system-tests:compileJava :modules:system-tests:compileTestJava --write-locks -PskipWebBuild=true --offline` |
| 05 | 11 | PASS: two UI classes; third filter belonged to app-observability and matched no class, fixed in 16 | `./gradlew.bat :modules:ui:test --tests io.justsearch.ui.api.SettingsV2ContractTest --tests io.justsearch.ui.api.SettingsControllerReconfigureDispatchTest --tests io.justsearch.ui.api.OperationHistorySchemaTest -PskipWebBuild=true --offline` |
| 07 | 0 | FAIL: broad lock update needed uncached range metadata offline; narrowed to new test dependencies in 08 | `./gradlew.bat :modules:indexer-worker:test --tests io.justsearch.indexerworker.server.KnowledgeServerQuerySettingsOwnerTest --tests io.justsearch.indexerworker.server.KnowledgeServerDeviceMemoryLineTest --tests io.justsearch.indexerworker.server.QueryRoleSetTest --tests io.justsearch.indexerworker.server.InferenceCompositionRootFootprintTest --tests io.justsearch.indexerworker.server.KnowledgeServerQueryPreparationTransactionTest --tests io.justsearch.indexerworker.server.KnowledgeServerQueryPreparationDeviceTest --write-locks -PskipWebBuild=true --offline` |
| 08 | 49 | 48 PASS, 1 FAIL: old timeout expected old exception type; strengthened for typed refusal | `./gradlew.bat :modules:indexer-worker:test --tests io.justsearch.indexerworker.server.KnowledgeServerQuerySettingsOwnerTest --tests io.justsearch.indexerworker.server.KnowledgeServerDeviceMemoryLineTest --tests io.justsearch.indexerworker.server.QueryRoleSetTest --tests io.justsearch.indexerworker.server.InferenceCompositionRootFootprintTest --tests io.justsearch.indexerworker.server.KnowledgeServerQueryPreparationTransactionTest --tests io.justsearch.indexerworker.server.KnowledgeServerQueryPreparationDeviceTest --update-locks 'com.google.auto.value:auto-value-annotations,com.google.re2j:re2j,dev.cel:common,dev.cel:runtime,io.github.metarank:lightgbm4j,org.threeten:threeten-extra' -PskipWebBuild=true --offline` |
| 11 | 139 | 138 PASS, 1 FAIL: old outcome generator nullability mismatch; corrected with conformance test | `./gradlew.bat :modules:app-services:test --tests io.justsearch.app.services.settings.SettingsCommitCoordinatorTest --tests io.justsearch.app.services.settings.FixedSettingsComponentComposerTest --tests io.justsearch.app.services.settings.SettingsV2ProjectionTest :modules:app-observability:test --tests io.justsearch.app.observability.operations.OperationAttemptRunnerTest --tests io.justsearch.app.observability.operations.OperationSettingsRunnerTest --tests io.justsearch.app.observability.operations.OperationHistorySchemaTest  -PskipWebBuild=true --offline` |
| 12 | 4 | EXPECTED RED: 4 failures with restoration disabled | `./gradlew.bat :modules:indexer-worker:test --tests io.justsearch.indexerworker.server.KnowledgeServerQueryPreparationTransactionTest -PskipWebBuild=true --offline` |
| 13 | 2 | EXPECTED RED: 2 failures with warm-up invocation removed | `./gradlew.bat :modules:indexer-worker:test --tests io.justsearch.indexerworker.server.KnowledgeServerQueryPreparationDeviceTest.cudaCandidateIsRealizedBeforeBesideAndInPlacePreparationReturns --tests io.justsearch.indexerworker.server.KnowledgeServerQueryPreparationDeviceTest.cudaCandidateCpuFallbackRefusesWithCompositionAndRestoresExactA -PskipWebBuild=true --offline` |
| 14 | 1 | EXPECTED RED: 1 failure with restoration Error catch removed | `./gradlew.bat :modules:indexer-worker:test --tests io.justsearch.indexerworker.server.KnowledgeServerQueryPreparationDeviceTest.candidateRuntimeAndRestorationLinkageErrorRetainContextAndBothCauses -PskipWebBuild=true --offline` |
| 15 | 0 | PASS: all touched Java modules | `./gradlew.bat :modules:app-api:spotlessApply :modules:app-observability:spotlessApply :modules:app-services:spotlessApply :modules:indexer-worker:spotlessApply :modules:ui:spotlessApply -PskipWebBuild=true --offline` |
| 16 | 202 | PASS: 49 indexer, 72 services, 68 observability, 11 UI, 2 schemas; zero skipped | `./gradlew.bat :modules:indexer-worker:test --tests io.justsearch.indexerworker.server.KnowledgeServerQuerySettingsOwnerTest --tests io.justsearch.indexerworker.server.KnowledgeServerDeviceMemoryLineTest --tests io.justsearch.indexerworker.server.QueryRoleSetTest --tests io.justsearch.indexerworker.server.InferenceCompositionRootFootprintTest --tests io.justsearch.indexerworker.server.KnowledgeServerQueryPreparationTransactionTest --tests io.justsearch.indexerworker.server.KnowledgeServerQueryPreparationDeviceTest :modules:app-services:test --tests io.justsearch.app.services.settings.SettingsCommitCoordinatorTest --tests io.justsearch.app.services.settings.FixedSettingsComponentComposerTest --tests io.justsearch.app.services.settings.SettingsV2ProjectionTest :modules:app-observability:test --tests io.justsearch.app.observability.operations.OperationAttemptRunnerTest --tests io.justsearch.app.observability.operations.OperationSettingsRunnerTest --tests io.justsearch.app.observability.operations.OperationHistorySchemaTest :modules:ui:test --tests io.justsearch.ui.api.SettingsV2ContractTest --tests io.justsearch.ui.api.SettingsControllerReconfigureDispatchTest :modules:app-api:test --tests io.justsearch.app.api.schema.WireRecordSchemaGenTest.settingsV2 --tests io.justsearch.app.api.schema.WireRecordSchemaGenTest.operationOutcomeView  -PskipWebBuild=true --offline` |
| 17 | 0 | FAIL: temporary classpath export helper not compatible with configuration cache | `./gradlew.bat :modules:system-tests:compileJava :modules:system-tests:compileTestJava -I tmp/installed-classpath.init.gradle -PskipWebBuild=true --offline` |
| 18 | 0 | PASS: classpath export/compile | `./gradlew.bat :modules:system-tests:compileJava :modules:system-tests:compileTestJava -I tmp/installed-classpath.init.gradle --no-configuration-cache -PskipWebBuild=true --offline` |

Non-Gradle checks: `node scripts/ci/regen-all.mjs --only wire-schema-types` and `--check --only wire-schema-types`; docs regeneration/checks; both installed scripts `node --check`; `npm.cmd run typecheck`; focused Vitest contract run (12 passed) via existing Vite config with temporary worktree cache adapter and native config loader, because the shared node_modules junction rejects writes to .vite-temp; UTF-8 javac argfile compilation of 4 installed sources; `git diff --check`; requested ASCII diff scan. No shared dependency directory was written.

File line counts (added/deleted; new files included separately from git diff --stat):

| File | Added | Deleted |
|---|---:|---:|
| `.claude/skills/inference-runtime/SKILL.md` | 3 | 3 |
| `SSOT/schemas/operation-outcome-view.v1.json` | 17 | 0 |
| `SSOT/schemas/settings-v2.v1.json` | 16 | 11 |
| `docs/design/lane-f-engine-jvm/evidence/C2/operations-store-design.md` | 1 | 1 |
| `docs/design/lane-f-engine-jvm/evidence/D1/reconfigure-review-fixes-2026-09-30.md` | 116 | 0 |
| `docs/reference/api-contract-map.md` | 11 | 3 |
| `docs/reference/architecture/module-deps.md` | 1 | 0 |
| `docs/reference/inference-runtime-register.md` | 3 | 3 |
| `modules/app-api/src/main/java/io/justsearch/app/api/operations/OperationOutcomeView.java` | 9 | 2 |
| `modules/app-api/src/main/java/io/justsearch/app/api/operations/OperationReceipt.java` | 25 | 3 |
| `modules/app-api/src/main/java/io/justsearch/app/api/settings/CompositionV2.java` | 23 | 0 |
| `modules/app-api/src/main/java/io/justsearch/app/api/settings/SettingsCommitOwner.java` | 1 | 1 |
| `modules/app-api/src/main/java/io/justsearch/app/api/settings/SettingsV2.java` | 6 | 10 |
| `modules/app-observability/src/main/java/io/justsearch/app/observability/operations/OperationAttemptRunnerImpl.java` | 15 | 3 |
| `modules/app-observability/src/main/java/io/justsearch/app/observability/operations/SqliteOperationStore.java` | 2 | 1 |
| `modules/app-observability/src/test/java/io/justsearch/app/observability/operations/OperationAttemptRunnerTest.java` | 100 | 0 |
| `modules/app-observability/src/test/java/io/justsearch/app/observability/operations/OperationHistorySchemaTest.java` | 23 | 0 |
| `modules/app-observability/src/test/java/io/justsearch/app/observability/operations/OperationSettingsRunnerTest.java` | 23 | 4 |
| `modules/app-services/src/main/java/io/justsearch/app/services/settings/SettingsCommitCoordinator.java` | 2 | 4 |
| `modules/app-services/src/test/java/io/justsearch/app/services/settings/SettingsCommitCoordinatorTest.java` | 9 | 5 |
| `modules/app-services/src/test/java/io/justsearch/app/services/settings/SettingsV2ProjectionTest.java` | 14 | 0 |
| `modules/indexer-worker/build.gradle.kts` | 1 | 0 |
| `modules/indexer-worker/gradle.lockfile` | 10 | 0 |
| `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java` | 110 | 30 |
| `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerQueryPreparationDeviceTest.java` | 417 | 0 |
| `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerQueryPreparationTransactionTest.java` | 301 | 0 |
| `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerQuerySettingsOwnerTest.java` | 51 | 47 |
| `modules/ui-web/src/api/__fixtures__/settings-v2-live.json` | 6 | 4 |
| `modules/ui-web/src/api/generated/schema-types/operation-outcome-view.ts` | 12 | 0 |
| `modules/ui-web/src/api/generated/schema-types/settings-v2.ts` | 12 | 8 |
| `modules/ui/src/main/java/io/justsearch/ui/api/SettingsController.java` | 19 | 3 |
| `modules/ui/src/main/resources/SSOT/schemas/operation-outcome-view.v1.json` | 17 | 0 |
| `modules/ui/src/main/resources/SSOT/schemas/settings-v2.v1.json` | 16 | 11 |
| `modules/ui/src/test/java/io/justsearch/ui/api/SettingsControllerReconfigureDispatchTest.java` | 68 | 28 |
| `modules/ui/src/test/java/io/justsearch/ui/api/SettingsV2ContractTest.java` | 6 | 1 |
| `scripts/supervisor-conformance/query-reconfigure.mjs` | 141 | 42 |
| `scripts/supervisor-conformance/real-writer-recovery.mjs` | 3 | 0 |


### 2026-10-01: BESIDE cleanup Error and captured A shutdown drain

Review correction against lane head `5b4560d8c045bd4346916c067fddc85311ee3cad`:
`PreparedQueryRoleSettings.closeCandidate()` previously skipped its captured A lease release
when native candidate cleanup threw an Error. Ordered close drains serving holders before
retrying the retained recovery candidate, so that capture prevented the retry from being reached.

The existing owner now releases `source` in a finally, collects RuntimeException/Error cleanup
failures without losing earlier failures, and leaves candidate ownership intact for the existing
recovery reservation. The settings commitment point and producer transfer are unchanged; no
new durable writer or lifecycle authority is introduced. Errors are retained in recovery rather
than discarded, including when an earlier service-close IOException is the primary failure.

Two regressions in `KnowledgeServerQuerySettingsOwnerTest` use the existing physical BESIDE
fixture, a one-shot SessionHandle.close LinkageError, and actual KnowledgeServer ordered close:
`besideCleanupErrorReleasesCapturedAAndOrderedShutdownRetriesCandidate` checks capture drain,
candidate retry and the terminal shutdown latch; `besideCleanupErrorPreservesEarlierServiceFailureAndCandidate`
also injects a one-shot service-close IOException and checks both causes. Candidate identity and
recovery ownership are asserted before shutdown. The test-only finally releases the capture solely
to permit fixture shutdown retry on a red revision; it runs after the asserted ordered close.

Status: implemented, not compiled/formatted/tested. The 2026-10-01 owner instruction supersedes
all earlier grant-file polling instructions: no Gradle command is authorized until the orchestrator
explicitly grants it. No Gradle, installed Engine or dev stack was started for this correction.

Required granted sequence (one command at a time):

```text
./gradlew.bat :modules:indexer-worker:spotlessApply -PskipWebBuild=true
./gradlew.bat :modules:indexer-worker:compileJava :modules:indexer-worker:compileTestJava -PskipWebBuild=true
./gradlew.bat :modules:indexer-worker:test --tests io.justsearch.indexerworker.server.KnowledgeServerQuerySettingsOwnerTest --tests io.justsearch.indexerworker.server.KnowledgeServerQueryPreparationDeviceTest --tests io.justsearch.indexerworker.server.KnowledgeServerQueryPreparationTransactionTest -PskipWebBuild=true
```

Red/green: temporarily replace only `closeCandidate()` with its lane-head implementation, run
`./gradlew.bat :modules:indexer-worker:test --tests 'io.justsearch.indexerworker.server.KnowledgeServerQuerySettingsOwnerTest.besideCleanupError*' -PskipWebBuild=true`,
restore the fixed method and run the same focused command. The first case must fail holder drain
and shutdown completion; the second must also reveal the lost earlier cleanup cause. Neither
red nor green has run yet. The original method is saved in the worktree's ignored
`tmp/query-capture-cleanup-red/closeCandidate-before.java.txt` for that reversible check.
