package io.justsearch.applauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.app.api.SearchRequest;
import io.justsearch.app.api.SearchResponse;
import io.justsearch.telemetry.LocalTelemetry;
import io.justsearch.telemetry.Telemetry;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SmokeDriverTest {

  @TempDir Path tempDir;

  @AfterEach
  void resetSnapshots() {
    SmokeDriver.resetFactories();
  }

  @Test
  void sanitizeTrimsAndReplaces() throws Exception {
    SmokeDriver driver = newSmokeDriver();
    Method method = SmokeDriver.class.getDeclaredMethod("sanitize", String.class);
    method.setAccessible(true);
    assertEquals("<empty>", method.invoke(driver, (String) null));
    assertEquals("hello world", method.invoke(driver, "hello\nworld"));
  }

  private static SmokeDriver newSmokeDriver() throws Exception {
    SmokeDriver driver =
        org.mockito.Mockito.mock(SmokeDriver.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    setField(driver, "environment", null);
    return driver;
  }

  @Test
  void runSmokeQueryCapturesFailures() throws Exception {
    try (TestContext ctx =
        createContext(
            new StubSearchFn(
                new SearchResponse(
                    java.util.List.of(), java.util.Map.of(), null, java.util.Map.of()),
                null))) {
      SmokeDriver driver = ctx.driver();
      Method runSmokeQuery =
          SmokeDriver.class.getDeclaredMethod(
              "runSmokeQuery", java.util.List.class, java.util.List.class);
      runSmokeQuery.setAccessible(true);
      java.util.List<String> diagnostics = new java.util.ArrayList<>();
      java.util.List<String> failures = new java.util.ArrayList<>();
      Object response = runSmokeQuery.invoke(driver, diagnostics, failures);
      assertTrue(failures.isEmpty());
      assertEquals(0, ((SearchResponse) response).hits().size());

      setField(
          driver,
          "searchFn",
          new StubSearchFn(null, new RuntimeException("Stage corrections failed")));
      diagnostics.clear();
      failures.clear();
      Object errorResponse = runSmokeQuery.invoke(driver, diagnostics, failures);
      assertEquals(null, errorResponse);
      assertTrue(failures.stream().anyMatch(s -> s.startsWith("SMOKE_QUERY_ERROR")));
    }
  }

  @Test
  void runSmokeQueryRecordsFailuresOnUnhandledException() throws Exception {
    try (TestContext ctx =
        createContext(
            new StubSearchFn(
                null, new IllegalStateException("unexpected failure", new RuntimeException("broken"))))) {
      SmokeDriver driver = ctx.driver();
      Method runSmokeQuery =
          SmokeDriver.class.getDeclaredMethod(
              "runSmokeQuery", java.util.List.class, java.util.List.class);
      runSmokeQuery.setAccessible(true);
      java.util.List<String> diagnostics = new java.util.ArrayList<>();
      java.util.List<String> failures = new java.util.ArrayList<>();
      Object result = runSmokeQuery.invoke(driver, diagnostics, failures);
      assertEquals(null, result);
      assertTrue(failures.stream().anyMatch(entry -> entry.startsWith("SMOKE_QUERY_ERROR")));
      assertTrue(failures.stream().anyMatch(entry -> entry.startsWith("SMOKE_QUERY_CAUSE")));
    }
  }

  @Test
  void executeReportsConfigurationViolations() throws Exception {
    StubCommandRunner commands =
        new StubCommandRunner(
            LauncherCommands.CommandResult.success(java.util.List.of("COMMAND/OK")));
    try (TestContext ctx = createContext(successFacade(), commands)) {
      ctx.writeProfile(
          """
          app:
            data_dir: %s
          unknown_section: true
          index:
            collections:
              - name: docs
                roots:
                  - /tmp/absolute/path
          search:
            pipeline: {}
          """
              .formatted(normalizePath(ctx.dataDir())));
      SmokeResult result = ctx.driver().execute();
      assertEquals(2, result.exitCode(), () -> "failures=" + result.failures());
      assertTrue(
          result.failures().contains("CONFIG_EGRESS_GUARD_OFF"),
          () -> "failures=" + result.failures());
      assertTrue(
          result.failures().stream().anyMatch(entry -> entry.startsWith("CONFIG_ABSOLUTE_PATH")),
          () -> "failures=" + result.failures());
      assertTrue(
          result.failures().contains("LAUNCHER/CONFIG_MISSING key=egress.block_all"),
          () -> "failures=" + result.failures());
      assertTrue(
          result.failures().contains("LAUNCHER/CONFIG_MISSING key=search.pipeline.profile"),
          () -> "failures=" + result.failures());
      assertTrue(
          result.failures().contains("CONFIG/UNKNOWN_KEY key=unknown_section"),
          () -> "failures=" + result.failures());
    }
  }

  @Test
  void executeAllowsCanonicalConfiguration() throws Exception {
    StubCommandRunner commands =
        new StubCommandRunner(
            LauncherCommands.CommandResult.success(java.util.List.of("COMMAND/OK")));
    try (TestContext ctx = createContext(successFacade(), commands)) {
      SmokeResult result = ctx.driver().execute();
      assertEquals(0, result.exitCode());
      assertTrue(result.failures().isEmpty());
    }
  }

  @Test
  void executeFailsFastWhenModelRequired() throws Exception {
    String previous = System.getProperty("llm.requireModel");
    System.setProperty("llm.requireModel", "true");
    StubCommandRunner commands =
        new StubCommandRunner(
            LauncherCommands.CommandResult.success(java.util.List.of("COMMAND/OK")));
    try (TestContext ctx = createContext(successFacade(), commands)) {
      SmokeResult result = ctx.driver().execute();
      assertEquals(2, result.exitCode());
      assertTrue(
          result.failures().stream()
              .anyMatch(marker -> marker.equals("LAUNCHER/MODEL_MISSING path=<unset>")));
    } finally {
      if (previous == null) {
        System.clearProperty("llm.requireModel");
      } else {
        System.setProperty("llm.requireModel", previous);
      }
    }
  }

  @Test
  void executeAggregatesCommandResults() throws Exception {
    StubCommandRunner commands =
        new StubCommandRunner(
            LauncherCommands.CommandResult.success(java.util.List.of("COMMAND/OK")));
    try (TestContext ctx = createContext(successFacade(), commands)) {
      SmokeResult result = ctx.driver().execute();
      assertEquals(0, result.exitCode());
      assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("COMMAND/OK")));
      assertTrue(commands.reindexCalls > 0);
    }
  }

  @Test
  void executeCapturesCommandRunnerFailure() throws Exception {
    StubCommandRunner commands =
        new StubCommandRunner(
            LauncherCommands.CommandResult.success(java.util.List.of("COMMAND/OK")));
    commands.reindexFailure = new IllegalStateException("reindex_broken");
    try (TestContext ctx = createContext(successFacade(), commands)) {
      SmokeResult result = ctx.driver().execute();
      assertEquals(2, result.exitCode());
      assertTrue(
          result.failures().stream()
              .anyMatch(f -> f.contains("SMOKE_COMMAND_ERROR command=reindex")));
    }
  }

  @Test
  void executeCapturesCommandFactoryFailure() throws Exception {
    try (TestContext ctx = createContext(successFacade())) {
      SmokeDriver.installCommandRunnerFactory(
          env -> {
            throw new IOException("factory_boom");
          });
      SmokeResult result = ctx.driver().execute();
      assertEquals(2, result.exitCode());
      assertTrue(
          result.failures().stream()
              .anyMatch(f -> f.contains("SMOKE_COMMANDS_ERROR")));
    }
  }

  @Test
  void executeAddsModelMissingDiagnosticWhenLlmEnabled() throws Exception {
    StubCommandRunner commands =
        new StubCommandRunner(
            LauncherCommands.CommandResult.success(java.util.List.of("COMMAND/OK")));
    try (TestContext ctx = createContext(successFacade(), commands)) {
      SmokeResult result = ctx.driver().execute();
      assertTrue(
          result.diagnostics().stream()
              .anyMatch(marker -> marker.startsWith("LAUNCHER/MODEL_MISSING")));
    }
  }

  @Test
  void executeSkipsWorkerMissingDiagnosticsWhenWorkersEnabled() throws Exception {
    StubCommandRunner commands =
        new StubCommandRunner(
            LauncherCommands.CommandResult.success(java.util.List.of("COMMAND/OK")));
    try (TestContext ctx = createContext(successFacade(), commands)) {
      SmokeResult result = ctx.driver().execute();
      assertFalse(
          result.diagnostics().stream()
              .anyMatch(marker -> marker.startsWith("LAUNCHER/WORKER_MISSING")));
    }
  }

  @Test
  void recordCommandResultIgnoresNullResults() throws Exception {
    try (TestContext ctx = createContext(successFacade())) {
      SmokeDriver driver = ctx.driver();
      Method record =
          SmokeDriver.class.getDeclaredMethod(
              "recordCommandResult",
              LauncherCommands.CommandResult.class,
              java.util.List.class,
              java.util.List.class);
      record.setAccessible(true);
      java.util.List<String> diagnostics = new java.util.ArrayList<>();
      java.util.List<String> failures = new java.util.ArrayList<>();
      record.invoke(driver, null, diagnostics, failures);
      assertTrue(diagnostics.isEmpty());
      assertTrue(failures.isEmpty());
    }
  }

  @Test
  void executeReportsNonZeroHitCounts() throws Exception {
    StubCommandRunner commands =
        new StubCommandRunner(
            LauncherCommands.CommandResult.success(java.util.List.of("COMMAND/OK")));
    SearchResponse response =
        new SearchResponse(
            java.util.List.of(new SearchResponse.Hit("doc-1", 1.0, java.util.Map.of())),
            java.util.Map.of(),
            null,
            java.util.Map.of());
    try (TestContext ctx = createContext(new StubSearchFn(response, null), commands)) {
      SmokeResult result = ctx.driver().execute();
      assertTrue(
          result.failures().stream().anyMatch(marker -> marker.startsWith("SMOKE_QUERY_NONZERO")));
    }
  }

  private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
    for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
      try {
        return c.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
        // field may be declared on a superclass; keep walking up until we find it or run out
      }
    }
    throw new NoSuchFieldException(name);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = findField(target.getClass(), name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private TestContext createContext(java.util.function.Function<SearchRequest, SearchResponse> facade) throws Exception {
    return createContext(
        facade,
        new StubCommandRunner(
            LauncherCommands.CommandResult.success(java.util.List.of("COMMAND/OK"))));
  }

  private TestContext createContext(java.util.function.Function<SearchRequest, SearchResponse> facade, StubCommandRunner commands) throws Exception {
    SmokeDriver driver =
        org.mockito.Mockito.mock(SmokeDriver.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    Path homeDir = Files.createTempDirectory(tempDir, "home-");
    Path dataDir = Files.createDirectories(homeDir.resolve(".justsearch-smoke"));
    Path profileFile = homeDir.resolve("profile.yaml");
    Files.writeString(profileFile, defaultProfileYaml(dataDir));
    // Initialize ConfigStore from the profile YAML so SmokeDriver reads correct values
    var rcBuilder = io.justsearch.configuration.resolved.ResolvedConfig.builder();
    rcBuilder.contributeEnvRegistry();
    try {
      rcBuilder.contributeYaml(new ObjectMapper(
          new tools.jackson.dataformat.yaml.YAMLFactory()).readTree(
              Files.readString(profileFile)));
    } catch (Exception ignored) {
      // test helper: invalid/empty yaml falls back to the env-registry-only config
    }
    io.justsearch.configuration.resolved.ConfigStore.setGlobal(
        new io.justsearch.configuration.resolved.ConfigStore(rcBuilder.build()));

    LocalTelemetry telemetry = new LocalTelemetry(new io.justsearch.core.execution.TestEngineExecutors(), dataDir, 5_000, "launcher-test", "test");
    LauncherEnvironment environment =
        org.mockito.Mockito.mock(LauncherEnvironment.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    SmokeDriver.installCommandRunnerFactory(env -> commands);
    setField(environment, "profilePath", profileFile);
    setField(environment, "previousConfigProperty", null);
    setField(environment, "previousEgressProperty", null);
    setField(environment, "configManager", null);
    setField(environment, "telemetry", telemetry);
    setField(environment, "HeadAssembly", null);
    // Tempdoc 519 §5 / Step 4: LauncherEnvironment.appFacade field removed; SmokeDriver
    // holds its own AppFacade reference (which HeadAssembly implements).
    setField(driver, "environment", environment);
    setField(driver, "searchFn", facade);
    return new TestContext(driver, environment, telemetry, homeDir, dataDir, profileFile, commands);
  }

  private java.util.function.Function<SearchRequest, SearchResponse> successFacade() {
    return new StubSearchFn(
        new SearchResponse(
            java.util.List.of(), java.util.Map.of(), null, java.util.Map.of()),
        null);
  }


  private static String defaultProfileYaml(Path dataDir) {
    return """
        app:
          data_dir: %s
        egress:
          block_all: true
        search:
          pipeline:
            profile: default
        workers:
          ai:
            enabled: true
          indexer:
            enabled: true
        """
        .formatted(normalizePath(dataDir));
  }

  private static String normalizePath(Path path) {
    String value = path.toString();
    return java.io.File.separatorChar == '\\' ? value.replace("\\", "\\\\") : value;
  }

  private static void deleteRecursively(Path root) {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (var stream = Files.walk(root)) {
      stream.sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  // best effort
                }
              });
    } catch (IOException ignored) {
      // best effort
    }
  }


  private record TestContext(
      SmokeDriver driver,
      LauncherEnvironment environment,
      Telemetry telemetry,
      Path homeDir,
      Path dataDir,
      Path profileFile,
      StubCommandRunner commands)
      implements AutoCloseable {
    void writeProfile(String yaml) throws IOException {
      Files.writeString(profileFile, yaml);
      // Re-initialize ConfigStore so SmokeDriver reads the new profile values
      var rcBuilder = io.justsearch.configuration.resolved.ResolvedConfig.builder();
      rcBuilder.contributeEnvRegistry();
      try {
        rcBuilder.contributeYaml(new ObjectMapper(
            new tools.jackson.dataformat.yaml.YAMLFactory()).readTree(yaml));
      } catch (Exception ignored) {
        // test helper: invalid/empty yaml falls back to the env-registry-only config
      }
      io.justsearch.configuration.resolved.ConfigStore.setGlobal(
          new io.justsearch.configuration.resolved.ConfigStore(rcBuilder.build()));
    }

    @Override
    public void close() {
      try {
        telemetry.close();
      } catch (Exception ignored) {
        // no-op
      }
      deleteRecursively(dataDir);
      deleteRecursively(homeDir);
      SmokeDriver.installCommandRunnerFactory(null);
    }
  }

  private static final class StubSearchFn
      implements java.util.function.Function<SearchRequest, SearchResponse> {
    private final SearchResponse response;
    private final RuntimeException toThrow;

    StubSearchFn(SearchResponse response, RuntimeException toThrow) {
      this.response = response;
      this.toThrow = toThrow;
    }

    @Override
    public SearchResponse apply(SearchRequest request) {
      if (toThrow != null) {
        throw toThrow;
      }
      return response;
    }
  }

  private static final class StubCommandRunner implements Launcher.CommandRunner {
    private final LauncherCommands.CommandResult successResult;
    RuntimeException reindexFailure;
    RuntimeException verifyFailure;
    RuntimeException snapshotFailure;
    int reindexCalls;

    StubCommandRunner(LauncherCommands.CommandResult successResult) {
      this.successResult = successResult;
    }

    @Override
    public LauncherCommands.CommandResult reindex() throws Exception {
      if (reindexFailure != null) {
        throw reindexFailure;
      }
      reindexCalls++;
      return successResult;
    }

    @Override
    public LauncherCommands.CommandResult verify() throws Exception {
      if (verifyFailure != null) {
        throw verifyFailure;
      }
      return successResult;
    }

    @Override
    public LauncherCommands.CommandResult snapshot() throws Exception {
      if (snapshotFailure != null) {
        throw snapshotFailure;
      }
      return successResult;
    }
  }
}
