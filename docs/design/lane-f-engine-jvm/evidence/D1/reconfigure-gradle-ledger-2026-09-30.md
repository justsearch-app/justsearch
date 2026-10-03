# Gradle invocation ledger

Every invocation checked the exact grant before launch. No concurrent Gradle builds. All commands begin `./gradlew.bat` and include `-PskipWebBuild=true`; later calls use worktree-local `GRADLE_USER_HOME=tmp/gradle-home`. No dev stack, Engine or backend was started. Tests use local writable temporary directories.

1. `:modules:indexer-worker:compileJava :modules:indexer-worker:compileTestJava -PskipWebBuild=true` ? 0 tests; wrapper download denied. Evidence: `initial tool output`.

2. `:modules:indexer-worker:compileJava :modules:indexer-worker:compileTestJava -PskipWebBuild=true` ? 0; plugin cache unavailable. Evidence: `initial tool output / session 22203`.

3. `:modules:indexer-worker:compileJava :modules:indexer-worker:compileTestJava -PskipWebBuild=true` ? 0; plugin cache unavailable. Evidence: `initial tool output / session 17347`.

4. `:modules:indexer-worker:compileJava :modules:indexer-worker:compileTestJava --offline -PskipWebBuild=true` ? 0; offline plugin cache unavailable. Evidence: `initial tool output`.

5. `:modules:indexer-worker:compileJava :modules:indexer-worker:compileTestJava --offline -PskipWebBuild=true` ? 0; copied plugin cache unavailable. Evidence: `initial tool output`.

6. `:modules:indexer-worker:compileJava :modules:indexer-worker:compileTestJava --info --offline -PskipWebBuild=true` ? 0; plugin cache unavailable. Evidence: `tmp/reconf-compile-diagnostic.log`.

7. `:modules:indexer-worker:compileJava :modules:indexer-worker:compileTestJava --offline -PskipWebBuild=true` ? 0; javac ZipFS access denied in external cache. Evidence: `tmp/reconf-compile-1.log`.

8. `:modules:indexer-worker:compileJava :modules:indexer-worker:compileTestJava --stacktrace --offline -PskipWebBuild=true` ? 0; same cache error with stack trace. Evidence: `tmp/reconf-compile-2.log`.

9. `:modules:indexer-worker:compileJava :modules:indexer-worker:compileTestJava --offline -PskipWebBuild=true` ? 0; JDK 21 discovery failed. Evidence: `tmp/reconf-compile-3.log`.

10. `:modules:indexer-worker:compileJava :modules:indexer-worker:compileTestJava --offline -PskipWebBuild=true` ? 0; PASS. Evidence: `tmp/reconf-compile-4.log`.

11. `:modules:indexer-worker:test --tests io.justsearch.indexerworker.server.KnowledgeServerQuerySettingsOwnerTest --offline -PskipWebBuild=true` ? 9/9 failed; JUnit temporary directory cleanup access denied. Evidence: `tmp/reconf-query-tests-1.log`.

12. `:modules:indexer-worker:test --tests io.justsearch.indexerworker.server.KnowledgeServerQuerySettingsOwnerTest --offline -PskipWebBuild=true` ? 9/9 PASS. Evidence: `tmp/reconf-query-tests-2.log`.

13. `:modules:indexer-worker:test --tests io.justsearch.indexerworker.server.KnowledgeServerQuerySettingsOwnerTest --offline -PskipWebBuild=true` ? 11; 1 failure (recovery guard). Evidence: `tmp/reconf-query-tests-3.log`.

14. `:modules:indexer-worker:test --tests io.justsearch.indexerworker.server.KnowledgeServerQuerySettingsOwnerTest --offline -PskipWebBuild=true` ? 11; 1 failure (fixture publication). Evidence: `tmp/reconf-query-tests-4.log`.

15. `:modules:indexer-worker:test --tests io.justsearch.indexerworker.server.KnowledgeServerQuerySettingsOwnerTest.queryRecoveryRetriesTerminalPublicationWithoutRecomposingA --offline -PskipWebBuild=true` ? 1; 1 failure (fixture publication). Evidence: `tmp/reconf-recovery-test-1.log`.

16. `:modules:indexer-worker:test --tests io.justsearch.indexerworker.server.KnowledgeServerQuerySettingsOwnerTest --offline -PskipWebBuild=true` ? 11; 1 failure (fixture publication). Evidence: `tmp/reconf-query-tests-5.log`.

17. `:modules:app-api:test --tests io.justsearch.app.api.schema.WireRecordSchemaGenTest.settingsV2 -PupdateSchemas=true --offline -PskipWebBuild=true` ? 1/1 PASS; schema writer. Evidence: `tmp/reconf-schema-test.log`.

18. `:modules:ui:test --tests io.justsearch.ui.api.SettingsV2ContractTest --tests io.justsearch.ui.api.SettingsControllerReconfigureDispatchTest --offline -PskipWebBuild=true` ? 0; composition return-type compile error. Evidence: `tmp/reconf-ui-tests.log`.

19. `:modules:indexer-worker:test --tests io.justsearch.indexerworker.server.KnowledgeServerQuerySettingsOwnerTest --tests io.justsearch.indexerworker.server.KnowledgeServerDeviceMemoryLineTest --tests io.justsearch.indexerworker.server.InferenceCompositionRootFootprintTest --tests io.justsearch.indexerworker.server.QueryRoleSetTest --offline -PskipWebBuild=true` ? 0; nonexistent enum compile error. Evidence: `tmp/reconf-indexer-tests.log`.

20. `:modules:indexer-worker:test --tests io.justsearch.indexerworker.server.KnowledgeServerQuerySettingsOwnerTest --tests io.justsearch.indexerworker.server.KnowledgeServerDeviceMemoryLineTest --tests io.justsearch.indexerworker.server.InferenceCompositionRootFootprintTest --tests io.justsearch.indexerworker.server.QueryRoleSetTest --offline -PskipWebBuild=true` ? 34; 1 failure (requested assembly missing from fixture). Evidence: `tmp/reconf-indexer-tests-2.log`.

21. `:modules:ui:test --tests io.justsearch.ui.api.SettingsV2ContractTest --tests io.justsearch.ui.api.SettingsControllerReconfigureDispatchTest :modules:app-services:test --tests io.justsearch.app.services.settings.FixedSettingsComponentComposerTest --tests io.justsearch.app.services.settings.SettingsCommitCoordinatorTest --offline -PskipWebBuild=true` ? 0; missing fixture field compile error. Evidence: `tmp/reconf-settings-ui-tests-2.log`.

22. `:modules:app-api:spotlessApply :modules:app-engine:spotlessApply :modules:app-services:spotlessApply :modules:indexer-worker:spotlessApply :modules:system-tests:spotlessApply :modules:ui:spotlessApply --offline -PskipWebBuild=true` ? 0; PASS. Evidence: `tmp/reconf-spotless.log`.

23. `:modules:ui:test --tests io.justsearch.ui.api.SettingsV2ContractTest --tests io.justsearch.ui.api.SettingsControllerReconfigureDispatchTest :modules:app-services:test --tests io.justsearch.app.services.settings.FixedSettingsComponentComposerTest --tests io.justsearch.app.services.settings.SettingsCommitCoordinatorTest --offline -PskipWebBuild=true` ? UI 11 PASS; services 64, 1 failure (old abort assertion). Evidence: `tmp/reconf-settings-ui-tests-3.log`.

24. `:modules:app-engine:compileJava :modules:app-engine:compileTestJava :modules:system-tests:compileJava :modules:system-tests:compileTestJava --no-configuration-cache -I tmp/installed-classpath.init.gradle --offline -PskipWebBuild=true` ? 0; local init BOM rejected. Evidence: `tmp/reconf-engine-system-compile.log`.

25. `:modules:app-engine:compileJava :modules:app-engine:compileTestJava :modules:system-tests:compileJava :modules:system-tests:compileTestJava --no-configuration-cache -I tmp/installed-classpath.init.gradle --offline -PskipWebBuild=true` ? 0; init ran against included build. Evidence: `tmp/reconf-engine-system-compile-2.log`.

26. `:modules:app-engine:compileJava :modules:app-engine:compileTestJava :modules:system-tests:compileJava :modules:system-tests:compileTestJava --no-configuration-cache -I tmp/installed-classpath.init.gradle --offline -PskipWebBuild=true` ? 0; classpath resolution attempted outside task owner lock. Evidence: `tmp/reconf-engine-system-compile-3.log`.

27. `:modules:app-engine:compileJava :modules:app-engine:compileTestJava :modules:system-tests:compileJava :modules:system-tests:compileTestJava --no-configuration-cache -I tmp/installed-classpath.init.gradle --offline -PskipWebBuild=true` ? 0; PASS; exported integration classpath only. Evidence: `tmp/reconf-engine-system-compile-4.log`.

28. `:modules:indexer-worker:test --tests io.justsearch.indexerworker.server.KnowledgeServerQuerySettingsOwnerTest --tests io.justsearch.indexerworker.server.KnowledgeServerDeviceMemoryLineTest --tests io.justsearch.indexerworker.server.InferenceCompositionRootFootprintTest --tests io.justsearch.indexerworker.server.QueryRoleSetTest --offline -PskipWebBuild=true` ? 35/35 PASS. Evidence: `tmp/reconf-indexer-tests-3.log`.

29. `:modules:indexer-worker:test --tests io.justsearch.indexerworker.server.KnowledgeServerQuerySettingsOwnerTest.inPlaceReleasesOwnCaptureAndKeepsIndexAndProducerDuringCompose --tests io.justsearch.indexerworker.server.KnowledgeServerQuerySettingsOwnerTest.composeRefusalRestoresExactAWithoutComposerRegistration --offline -PskipWebBuild=true` ? 2; 2 expected failures after temporary production reversion. Evidence: `tmp/reconf-red.log`.

30. `:modules:indexer-worker:test --tests io.justsearch.indexerworker.server.KnowledgeServerQuerySettingsOwnerTest.inPlaceReleasesOwnCaptureAndKeepsIndexAndProducerDuringCompose --tests io.justsearch.indexerworker.server.KnowledgeServerQuerySettingsOwnerTest.composeRefusalRestoresExactAWithoutComposerRegistration --offline -PskipWebBuild=true` ? 2/2 PASS after restoring production. Evidence: `tmp/reconf-green.log`.

31. `:modules:app-services:test --tests io.justsearch.app.services.settings.FixedSettingsComponentComposerTest --tests io.justsearch.app.services.settings.SettingsCommitCoordinatorTest :modules:ui:test --tests io.justsearch.ui.api.SettingsV2ContractTest --tests io.justsearch.ui.api.SettingsControllerReconfigureDispatchTest --offline -PskipWebBuild=true` ? services 64 PASS; UI 11 PASS. Evidence: `tmp/reconf-settings-ui-tests-4.log`.

32. `:modules:app-api:spotlessApply :modules:app-engine:spotlessApply :modules:app-services:spotlessApply :modules:indexer-worker:spotlessApply :modules:system-tests:spotlessApply :modules:ui:spotlessApply --offline -PskipWebBuild=true` ? 0; PASS. Evidence: `tmp/reconf-spotless-final.log`.

33. `:modules:indexer-worker:test --tests io.justsearch.indexerworker.server.KnowledgeServerQuerySettingsOwnerTest --tests io.justsearch.indexerworker.server.KnowledgeServerDeviceMemoryLineTest --tests io.justsearch.indexerworker.server.InferenceCompositionRootFootprintTest --tests io.justsearch.indexerworker.server.QueryRoleSetTest :modules:app-services:test --tests io.justsearch.app.services.settings.FixedSettingsComponentComposerTest --tests io.justsearch.app.services.settings.SettingsCommitCoordinatorTest :modules:ui:test --tests io.justsearch.ui.api.SettingsV2ContractTest --tests io.justsearch.ui.api.SettingsControllerReconfigureDispatchTest --offline -PskipWebBuild=true` ? 37; 3 failures (retained A index lease at shutdown); services/UI not reached or reused. Evidence: `tmp/reconf-final-tests.log`.


34. `./gradlew.bat :modules:indexer-worker:spotlessApply -PskipWebBuild=true --offline` - 0 tests; PASS. `tmp/reconf-spotless-shutdown.log`.

35. `./gradlew.bat :modules:indexer-worker:test --tests io.justsearch.indexerworker.server.KnowledgeServerQuerySettingsOwnerTest --tests io.justsearch.indexerworker.server.KnowledgeServerDeviceMemoryLineTest --tests io.justsearch.indexerworker.server.InferenceCompositionRootFootprintTest --tests io.justsearch.indexerworker.server.QueryRoleSetTest :modules:app-services:test --tests io.justsearch.app.services.settings.FixedSettingsComponentComposerTest --tests io.justsearch.app.services.settings.SettingsCommitCoordinatorTest :modules:ui:test --tests io.justsearch.ui.api.SettingsV2ContractTest --tests io.justsearch.ui.api.SettingsControllerReconfigureDispatchTest --offline -PskipWebBuild=true` - indexer 37 PASS, services 64 PASS (up-to-date exact inputs), UI 11 PASS. `tmp/reconf-final-tests-2.log`. Schema writer separately 1 PASS.
