# Bundled help coverage during Green migration

Scope: one production defect at base `ca12f00e6`, branch `codex/lane-f-d1-help`.
Work remains uncommitted; no backend/dev stack, publication or repository-wide Gradle task is authorized.
This is the governing record for this bounded D1-9 correction, not a claim of full D1 acceptance.
The handoff-history file named in the assignment is absent at this revision.

Design (recorded before implementation):

1. Resolve one immutable bundled help FileSource from Paths.ssotPath()/docs/help after normal collection resolution.
2. Keep operator collection items unchanged and share reserved identities through configuration, which app-api already depends on.
3. Bootstrap consumes the Engine's exact resolved source through WorkerHost, replacing its working-directory path resolver.
4. Green adds physically present help to labeled sources and retains its collection through atomic candidate admission.
5. Missing help skips; startup marker/eval gates remain startup-only; help selection remains top-level Markdown.
6. No old-job ownership inference or persisted/operator-visible watched help root is introduced.

Original evidence: KnowledgeServerMigrationOps.java:1966 loads only watched_roots.json and configured collections;
KnowledgeServer.java:7739-7741 calls this loader and walk. The walk admitted unlabeled entries at
KnowledgeServerMigrationOps.java:2116, while Bootstrap resolved help independently at :1140.
configuration/build.gradle.kts has no app-api edge; app-api already exposes configuration.
worker-core, indexer-worker, app-services and app-engine already have the required lower-layer dependencies.
The non-file registered projection source abstraction is inappropriate for this physical file source.

Acceptance: real filesystem and SQLite candidate admission must retain justsearch-help without old jobs,
despite a current marker; missing physical help must be harmless; existing marker, eval, reservation and
migration enumeration tests must pass. Demonstrate red by reverting production help inclusion, restore,
format touched modules, and rerun focused tests. Capture commands and XML summaries here after verification.

## Verification completed

Tested revision: ca12f00e6 plus the final Java diff in this worktree. No Java edits followed the green suite. All Gradle calls ran sequentially. All six touched modules reported BUILD SUCCESSFUL for Spotless and production/test compilation.

The sandbox used a task-local Gradle home with cached Gradle 9.6.1, a read-only dependency cache and cached JDK 21 for build logic. Tests used JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=<worktree>/build/help-java-temp. Initial network/JDK discovery failures and an initial red run with external-temp cleanup errors are preserved separately; controlled red/green runs supersede them.

Exact formatting/compile commands:

```powershell
./gradlew.bat :modules:configuration:spotlessApply :modules:configuration:compileJava :modules:configuration:compileTestJava -PskipWebBuild=true
./gradlew.bat :modules:app-api:spotlessApply :modules:app-api:compileJava :modules:app-api:compileTestJava -PskipWebBuild=true
./gradlew.bat :modules:worker-core:spotlessApply :modules:worker-core:compileJava :modules:worker-core:compileTestJava -PskipWebBuild=true
./gradlew.bat :modules:indexer-worker:spotlessApply :modules:indexer-worker:compileJava :modules:indexer-worker:compileTestJava -PskipWebBuild=true
./gradlew.bat :modules:app-services:spotlessApply :modules:app-services:compileJava :modules:app-services:compileTestJava -PskipWebBuild=true
./gradlew.bat :modules:app-engine:spotlessApply :modules:app-engine:compileJava :modules:app-engine:compileTestJava -PskipWebBuild=true
```

Exact focused test commands (the first also ran for controlled red):

```powershell
./gradlew.bat :modules:indexer-worker:test --tests io.justsearch.indexerworker.server.ops.HelpMigrationSourceTest -PskipWebBuild=true
./gradlew.bat :modules:indexer-worker:test --tests io.justsearch.indexerworker.server.ops.MigrationEnumerationCompletenessTest -PskipWebBuild=true
./gradlew.bat :modules:indexer-worker:test --tests io.justsearch.indexerworker.server.KnowledgeServerTest -PskipWebBuild=true
./gradlew.bat :modules:app-services:test --tests io.justsearch.app.services.worker.HelpIngestMarkerRecoveryTest -PskipWebBuild=true
./gradlew.bat :modules:app-services:test --tests io.justsearch.app.services.worker.KnowledgeServerBootstrapEvalModeTest -PskipWebBuild=true
./gradlew.bat :modules:app-api:test --tests io.justsearch.app.api.knowledge.IngestCollectionPolicyTest -PskipWebBuild=true
./gradlew.bat :modules:configuration:test --tests io.justsearch.configuration.resolved.ResolvedConfigBuilderTest -PskipWebBuild=true
./gradlew.bat :modules:app-api:test --tests io.justsearch.app.api.ArchitectureRulesTest -PskipWebBuild=true
```

Copied result XML summaries:

| Class | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| HelpMigrationSourceTest | 3 | 0 | 0 | 0 |
| MigrationEnumerationCompletenessTest | 28 | 0 | 0 | 3 |
| KnowledgeServerTest | 40 | 0 | 0 | 0 |
| HelpIngestMarkerRecoveryTest | 3 | 0 | 0 | 0 |
| KnowledgeServerBootstrapEvalModeTest | 5 | 0 | 0 | 0 |
| IngestCollectionPolicyTest | 9 | 0 | 0 | 0 |
| ResolvedConfigBuilderTest | 109 | 0 | 0 | 0 |
| ArchitectureRulesTest | 3 | 0 | 0 | 0 |
| Total | 200 | 0 | 0 | 3 |

Complete command output: build/help-defect-evidence/<Class>.log and *format*.log. Copied XML: build/help-defect-evidence/<Class>/TEST-*.xml. Preserve this local build evidence through review (30 days); commands, summaries and runnable regressions are in the reviewable source diff.

Red proof: removed only the production conditional adding bundled help to migration coverage, retaining the typed source API so the regression could execute. HelpMigrationSourceTest reported tests=3 failures=1 errors=0 skipped=0. Output/XML: build/help-defect-evidence/help-red-controlled.log and help-red-controlled.xml.

```text
HelpMigrationSourceTest.greenIncludesResolvedHelpDespiteCurrentStartupMarkerAndNoOldJobs()
Green must enumerate the bundled help source ==> expected: <1> but was: <0>
```

Restored production source byte-for-byte from the saved green file: the same class then reported tests=3 failures=0 errors=0 skipped=0. The test uses real resolved configuration, filesystem, IndexGenerationManager candidate and SqliteJobQueue. It checks candidate journal and pending-job labels with no old jobs, a current startup marker and an unrelated collection delete. Missing help and top-level Markdown selection are covered. The Bootstrap test resolves SSOT outside an unrelated working directory.

Platform gaps: the three existing MigrationEnumerationCompletenessTest assumptions skip one POSIX-permission fixture and two symbolic-link fixtures (Windows lacks symlink privilege). No new help regression skipped. Skip messages are preserved in its copied XML.

Existing compile/deprecation warnings remain and were not suppressed. A missed Path-to-FileSource conversion in one existing test fixture was caught by compilation, corrected and recompiled successfully.

Documentation/index, skill projection, canonical links, runtime config matrix and module dependency checks passed. Matrix regeneration had no substantive change; timestamp-only churn was removed. No shared skill source changed.

Closeout: the assigned branch/base remains intact. No backend/dev stack, commit or push. Uncommitted changes are intentional under the assignment. The closeout sweep reaped no helpers: two pre-existing ui-shot registrations were retained because process identity could not be verified; the ownerless otlp-sink was reported.

Files changed:

- `docs/design/lane-f-engine-jvm/evidence/D1/help-migration-regression-2026-09-30.md`
- `docs/explanation/11-index-schema-migration.md`
- `modules/app-api/src/main/java/io/justsearch/app/api/knowledge/IngestCollectionPolicy.java`
- `modules/app-engine/src/main/java/io/justsearch/app/engine/EngineRoot.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeServerBootstrap.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/WorkerHost.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/HelpIngestMarkerRecoveryTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeServerBootstrapEvalModeTest.java`
- `modules/configuration/src/main/java/io/justsearch/configuration/InternalCollections.java`
- `modules/configuration/src/main/java/io/justsearch/configuration/resolved/ResolvedConfig.java`
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/queue/SqliteJobQueue.java`
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java`
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/ops/KnowledgeServerMigrationOps.java`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerTest.java`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/HelpMigrationSourceTest.java`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ops/MigrationEnumerationCompletenessTest.java`
- `modules/worker-core/src/main/java/io/justsearch/indexerworker/queue/SwitchBufferCapableQueue.java`

Final tracked git diff --stat: 14 files changed, 151 insertions(+), 81 deletions(-). Three untracked additions: InternalCollections.java, HelpMigrationSourceTest.java and this record.

The exact requested git diff | grep -P '^\+.*[^\x00-\x7F]' produced no output (grep exit 1). Untracked additions are ASCII-only too. git diff --check passed.

The bounded help defect is implemented and locally verified; no help-specific acceptance remains unresolved. No full D1, live-backend or hosted-CI acceptance is claimed.
