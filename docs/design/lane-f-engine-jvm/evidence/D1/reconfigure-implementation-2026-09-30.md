# Ordinary query reconfigure implementation report (2026-09-30)

Worktree: `F:/justsearch-public/.claude/worktrees/lane-f-reconf`; branch `codex/lane-f-reconf-inplace`; base `1e321d50f68ab08a349ce3dfb4f73d2250695bc1`. Working tree changes only: no commit or push. Java edits and manual compilation used explicit UTF-8. No dev stack, installed Engine, or backend was started. The Gradle grant was checked before every command; builds were sequential and focused. Initial grant absence was polled at 60-second intervals while implementation continued.

## Clause reconciliation

| Governing clause | Implementation and proof |
|---|---|
| D1.md:372 | All fallible query preparation, byte validation, component validation and commitment admission precede the existing settings replacement. No commitment point moved. Coordinator tests preserve settings bytes/witness on precommit refusal and publish B after commitment. |
| D1.md:424-431 | Later-owner refusal aborts the prepared query owner, closes B and restores frozen exact A; cause-aware abort releases the apply permit after existing recovery takes ownership. Existing unrelated-key, generation-bound refusal and restart contracts remain covered by SettingsCommitCoordinatorTest. Physical process-death evidence for the new IN_PLACE query path is not established locally. |
| D1.md:2523-2528 | Query footprint uses the exact composition plans/policies and excludes retained index roles. Device-line regressions cover BESIDE/IN_PLACE, unknown memory, insufficient release and realized CPU fallback contributing zero. The installed round launches with ceiling=1 MB; this simulates observation pressure, not a hard VRAM allocation bound. |
| D1.md:3150 | Replaced pending reconfigure row with an AI-tagged installed test containing BESIDE and forced IN_PLACE settings API rounds. Uses realized CUDA state, distinct valid B bytes, exact path/version/selection evidence, model queries, lexical availability, unchanged generation/witness, zero API outage and zero restarts. Written and compiled; execution belongs to root. |
| design.md:1376 | RELOADING reports desired B while applied A remains the committed predecessor. Failed preparation restores A; successful B selection/evidence joins existing atomic settings/publication path. |
| design.md:2312 | BESIDE serves A during B composition. IN_PLACE publishes a borrowed lexical successor, releases preparation's capture, drains all issued same-A views with a bounded wait, detaches only query leases, and retires only QueryRoleSet. Index and producer ownership remain on A until publication. Every precommit abort restores exact A or retains both refusal/restoration reasons in existing encoder recovery. |

No governing design contradiction was found. Independent review corrected implementation defects: one-shot recovery CAS loss, apply-permit release on annotated successful abort, borrowed-service shutdown cleanup, retained A index-lease shutdown cleanup, and separating native query drain from unrelated older service cleanup. No new durable writer, migration, marker, state machine or transition coordinator was added.

## Verification

Final counts and evidence are appended after the final run. Full Gradle invocation ledger: [Gradle invocation ledger](reconfigure-gradle-ledger-2026-09-30.md). Logs and archived XML live in `tmp/reconf-evidence/` and `tmp/reconf-*.log` in this worktree; retain for at least 60 days / until root captures acceptance evidence. No hash-only evidence substitutes for those accessible files.

Red/green: temporarily removed only `prepared.beginInPlace()` to restore unconditional beside preparation, preserving the rest of production. `inPlaceReleasesOwnCaptureAndKeepsIndexAndProducerDuringCompose` failed because composition still saw producer A instead of the borrowed degraded service. `composeRefusalRestoresExactAWithoutComposerRegistration` failed because source A was never retired/restored. Both failed (2/2) in `tmp/reconf-red.log`, then passed (2/2) after production restoration in `tmp/reconf-green.log`; corresponding XML is archived under `tmp/reconf-evidence/red` and `green`.

Installed sources: UTF-8 `javac` compiled all four supervision integration source files against the exported integration compile classpath, without invoking an integration Gradle task or running an Engine. Command: `F:/scoop/apps/temurin25-jdk/current/bin/javac.exe @tmp/installed-javac.args`; evidence `tmp/reconf-installed-javac.log` (exit 0). Focused app-engine/system-tests compilation passed. JavaScript syntax, generated schema projections, frontend typecheck, canonical docs links/index, skills projection, module dependency and runtime matrix checks passed. Existing Error Prone/deprecation warnings remain advisory; none were suppressed.

Unverified: installed CUDA rounds and actual model queries, physical query IN_PLACE precommit kill -> A/FAILED and postcommit kill -> B/COMPLETE, hosted/platform proof. The existing old query crash scenarios do not prove the newly retired-A path. This report does not close the complete D1-4/D1-12/D1-14/D1-16 acceptance bundle. No installed execution was attempted, as instructed.

## Files changed

Tracked file added/deleted line counts from `git diff --numstat`:

| File | Added | Deleted |
|---|---:|---:|
| `.claude/skills/inference-runtime/SKILL.md` | 6 | 0 |
| `SSOT/schemas/settings-v2.v1.json` | 13 | 1 |
| `docs/reference/inference-runtime-register.md` | 7 | 1 |
| `modules/app-api/src/main/java/io/justsearch/app/api/settings/SettingsV2.java` | 20 | 5 |
| `modules/app-engine/src/main/java/io/justsearch/app/engine/EngineRoot.java` | 4 | 0 |
| `modules/app-services/src/main/java/io/justsearch/app/services/settings/FixedSettingsComponentComposer.java` | 50 | 15 |
| `modules/app-services/src/main/java/io/justsearch/app/services/settings/SettingsCommitCoordinator.java` | 19 | 2 |
| `modules/app-services/src/main/java/io/justsearch/app/services/settings/SettingsComponentComposer.java` | 10 | 0 |
| `modules/app-services/src/test/java/io/justsearch/app/services/settings/FixedSettingsComponentComposerTest.java` | 51 | 0 |
| `modules/app-services/src/test/java/io/justsearch/app/services/settings/SettingsCommitCoordinatorTest.java` | 76 | 3 |
| `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/InferenceCompositionRoot.java` | 63 | 0 |
| `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java` | 438 | 104 |
| `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/InferenceCompositionRootFootprintTest.java` | 116 | 0 |
| `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerDeviceMemoryLineTest.java` | 30 | 0 |
| `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerQuerySettingsOwnerTest.java` | 494 | 0 |
| `modules/system-tests/build.gradle.kts` | 1 | 0 |
| `modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/supervision/EngineLifecycleE2ETest.java` | 13 | 4 |
| `modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/supervision/EngineSupervisedRecoveryE2ETest.java` | 22 | 0 |
| `modules/ui-web/src/api/__fixtures__/settings-v2-live.json` | 5 | 1 |
| `modules/ui-web/src/api/generated/schema-types/settings-v2.ts` | 10 | 2 |
| `modules/ui/src/main/java/io/justsearch/ui/api/SettingsController.java` | 5 | 1 |
| `modules/ui/src/main/resources/SSOT/schemas/settings-v2.v1.json` | 13 | 1 |
| `modules/ui/src/test/java/io/justsearch/ui/api/SettingsControllerReconfigureDispatchTest.java` | 60 | 0 |
| `scripts/supervisor-conformance/real-writer-recovery.mjs` | 38 | 0 |

New untracked installed helper: `scripts/supervisor-conformance/query-reconfigure.mjs` (290 lines). This report and the local command ledger are additional evidence artifacts.

Final focused counts: **113 tests, zero failures/errors** (query owner 14; device memory 13; footprint 8; query set 2; fixed composer 10; coordinator 54; HTTP dispatch 10; SettingsV2 fixture 1; schema writer 1). Final XML/patch/source manifest: `tmp/reconf-evidence/final/`. Red demonstration: 2 failures; restored green demonstration: 2 passes. Spotless completed for all six touched Java modules, followed by final indexer formatting after the shutdown fix. Exact ASCII-added-lines check printed nothing (grep exit 1 means no matches).
