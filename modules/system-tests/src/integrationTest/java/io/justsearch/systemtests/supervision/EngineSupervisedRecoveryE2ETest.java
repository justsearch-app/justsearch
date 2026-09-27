/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.supervision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;

/** Installed Engine proofs for durable recovery, migration and hostile filesystem survival. */
@Timeout(7 * 60)
final class EngineSupervisedRecoveryE2ETest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  static void runSeededBesideSemanticTransition() throws Exception {
    Path repo = repositoryRoot();
    assumeTrue(hasRetainedInstallerModels(repo) && hasRetainedAlternateEmbedding(repo),
        "D1-18 BESIDE scenario requires retained CPU A and FP16 CUDA B model bytes");
    Path work = repo.resolve("tmp/lane-f-takeover/lifecycle-beside-" + UUID.randomUUID());
    withModelCacheCleanup(repo, work, () -> {
      runInstalledModelScenario(repo, work, "installer-before-marker", Map.of(),
          "INSTALLER_ACTIVATION_FAULT_PASS");
      String output = runInstalledModelScenario(repo, work, "model-live-a-b",
          besideModelEnvironment(), "MODEL_LIVE_AB_PASS");
      String line = output.lines().filter(value -> value.startsWith("MODEL_LIVE_AB_PASS "))
          .findFirst().orElseThrow();
      var result = MAPPER.readTree(line.substring("MODEL_LIVE_AB_PASS ".length()));
      var semantic = result.path("semantic");
      assertTrue(output.contains("\"mode\":\"BESIDE\""), output);
      assertTrue(semantic.path("sampledRequests").asInt() > 0, line);
      assertEquals(semantic.path("sampledRequests").asInt(), semantic.path("available").asInt(), line);
      assertEquals(semantic.path("hybridSampled").asInt(), semantic.path("hybridAvailable").asInt(), line);
      assertEquals(0, semantic.path("reloadingRefusals").asInt(), line);
      assertEquals(0, semantic.path("workerStarting").asInt(), line);
      assertEquals(0, semantic.path("transport").asInt(), line);
      assertEquals(0, semantic.path("apiOutageWindowMs").asInt(), line);
      assertEquals(0, result.path("restartCount").asInt(), line);
      assertFalse(result.path("instanceId").asText().isBlank(), line);
      System.out.println("LIFECYCLE_BESIDE_AVAILABILITY_PASS §16 " + line);
    });
  }

  static void runSeededInPlaceSemanticTransition() throws Exception {
    Path repo = repositoryRoot();
    assumeTrue(hasRetainedInstallerModels(repo) && hasRetainedAlternateEmbedding(repo),
        "D1-16 AI scenario requires retained CPU A and FP16 CUDA B model bytes");
    Path work = repo.resolve("tmp/lane-f-takeover/lifecycle-semantic-" + UUID.randomUUID());
    withModelCacheCleanup(repo, work, () -> {
      runInstalledModelScenario(repo, work, "installer-before-marker", Map.of(),
          "INSTALLER_ACTIVATION_FAULT_PASS");
      String output = runInstalledModelScenario(repo, work, "model-live-a-b", inPlaceModelEnvironment(),
          "MODEL_LIVE_AB_PASS");
      String line = output.lines().filter(value -> value.startsWith("MODEL_LIVE_AB_PASS "))
          .findFirst().orElseThrow();
      var semantic = MAPPER.readTree(line.substring("MODEL_LIVE_AB_PASS ".length()))
          .path("semantic");
      assertTrue(semantic.path("transitionMs").asLong() > 0, line);
      assertTrue(semantic.path("refusalWindowMs").asLong() > 0, line);
      assertTrue(semantic.path("refusedFraction").asDouble() > 0, line);
      assertTrue(semantic.path("recoveredAfterRefusal").asBoolean(), line);
      assertTrue(output.contains("\"mode\":\"IN_PLACE\""), output);
      System.out.println("LIFECYCLE_SEMANTIC_AVAILABILITY_PASS §16 " + line);
    });
  }

  static void runSeededInPlaceGapRestoration() throws Exception {
    Path repo = repositoryRoot();
    assumeTrue(hasRetainedInstallerModels(repo) && hasRetainedAlternateEmbedding(repo),
        "D1-16 AI scenario requires retained CPU A and FP16 CUDA B model bytes");
    Path work = repo.resolve("tmp/lane-f-takeover/lifecycle-gap-" + UUID.randomUUID());
    withModelCacheCleanup(repo, work, () -> {
      runInstalledModelScenario(repo, work, "installer-before-marker", Map.of(),
          "INSTALLER_ACTIVATION_FAULT_PASS");
      var environment = new java.util.HashMap<>(inPlaceModelEnvironment());
      environment.put("JUSTSEARCH_RESTORED_A_NATIVE_LEASE_PROBE", "1");
      String output = runInstalledModelScenario(repo, work, "model-live-a-b-gap",
          environment, "MODEL_LIVE_AB_GAP_PASS");
      String line = output.lines().filter(value -> value.startsWith("MODEL_LIVE_AB_GAP_PASS "))
          .findFirst().orElseThrow();
      var result = MAPPER.readTree(line.substring("MODEL_LIVE_AB_GAP_PASS ".length()));
      assertEquals("floor simulated by device-memory cap", result.path("floor").asText(), line);
      assertEquals("PROMOTED_WITH_GAPS", result.path("terminalReason").asText(), line);
      assertTrue(result.path("aVectorHits").asInt() > 0, line);
      assertTrue(result.path("bVectorHits").asInt() > 0, line);
      assertTrue(result.path("semantic").path("recoveredAfterRefusal").asBoolean(), line);
      assertTrue(output.contains("MODEL_LIVE_AB_RESTORED_NATIVE_LEASE "), output);
      System.out.println("LIFECYCLE_IN_PLACE_GAP_RESTORATION_PASS §16 " + line);
    });
  }

  private static Map<String, String> inPlaceModelEnvironment() {
    var environment = new java.util.HashMap<>(besideModelEnvironment());
    environment.put("JUSTSEARCH_WRITER_RECOVERY_FORCE_IN_PLACE", "1");
    return Map.copyOf(environment);
  }

  private static Map<String, String> besideModelEnvironment() {
    return Map.of(
        "JUSTSEARCH_WRITER_RECOVERY_DISTINCT_B", "1",
        "JUSTSEARCH_EMBED_GPU_MEM_MB", "2048",
        "JUSTSEARCH_SPLADE_GPU_MEM_MB", "2048",
        "JUSTSEARCH_NER_GPU_MEM_MB", "1024",
        "JUSTSEARCH_RERANK_GPU_MEM_MB", "1024");
  }

  @FunctionalInterface
  private interface FixtureWork {
    void run() throws Exception;
  }

  private static void withModelCacheCleanup(Path repo, Path work, FixtureWork fixture) throws Exception {
    Throwable primary = null;
    try {
      fixture.run();
    } catch (Exception | AssertionError failure) {
      primary = failure;
      throw failure;
    } finally {
      try {
        pruneStoppedModelCaches(repo, work);
      } catch (Exception cleanupFailure) {
        if (primary == null) throw cleanupFailure;
        primary.addSuppressed(cleanupFailure);
      }
    }
  }

  private static void pruneStoppedModelCaches(Path repo, Path work) throws Exception {
    Path runs = work.resolve("state/runs");
    if (Files.isDirectory(runs)) {
      try (var entries = Files.list(runs)) {
        for (Path run : entries.filter(Files::isDirectory).toList()) {
          Path report = run.resolve("stop-report.json");
          if (!Files.isRegularFile(report)
              || !MAPPER.readTree(Files.readString(report)).path("portsClosed").asBoolean(false)) {
            throw new IllegalStateException("Cannot prune model caches before owned run has stopped: " + run);
          }
        }
      }
    }
    if ("1".equals(System.getenv("JUSTSEARCH_FIXTURE_KEEP_MODEL_CACHES"))) return;
    Path outputFile = work.resolve("model-cache-prune-output.txt");
    ProcessBuilder prune = new ProcessBuilder("node",
        repo.resolve("scripts/supervisor-conformance/prune-model-caches.mjs").toString(),
        work.toString()).directory(repo.toFile()).redirectErrorStream(true)
        .redirectOutput(outputFile.toFile());
    Process process = prune.start();
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
      throw new IllegalStateException("Model cache prune exceeded 30 seconds");
    }
    String output = Files.readString(outputFile, StandardCharsets.UTF_8);
    if (process.exitValue() != 0 || !output.contains("MODEL_CACHE_PRUNED ")) {
      throw new IllegalStateException("Model cache prune failed: " + output);
    }
    System.out.print(output);
  }

  private static String runInstalledModelScenario(Path repo, Path work, String scenario,
      Map<String, String> extraEnvironment, String marker) throws Exception {
    Files.createDirectories(work);
    Path outputFile = work.resolve(scenario + "-fixture-output.txt");
    ProcessBuilder builder = new ProcessBuilder("node",
        repo.resolve("scripts/supervisor-conformance/real-writer-recovery.mjs").toString())
        .directory(repo.toFile()).redirectErrorStream(true)
        .redirectOutput(outputFile.toFile());
    builder.environment().put("JUSTSEARCH_WRITER_RECOVERY_WORK", work.toString());
    builder.environment().put("JUSTSEARCH_REAL_RECOVERY_SCENARIO", scenario);
    builder.environment().putAll(extraEnvironment);
    Process process = builder.start();
    Throwable primary = null;
    try {
      assertTrue(process.waitFor(330, TimeUnit.SECONDS),
          "D1-16 installed " + scenario + " exceeded 330 seconds: " + outputFile);
      String output = Files.readString(outputFile, StandardCharsets.UTF_8);
      assertEquals(0, process.exitValue(), output);
      assertTrue(output.contains(marker), output);
      assertTrue(output.contains("STOP 0") && output.contains("\"portsClosed\":true"), output);
      return output;
    } catch (Exception | AssertionError failure) {
      primary = failure;
      throw failure;
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
      }
      try {
        if (Files.isRegularFile(outputFile)) {
          for (String line : Files.readAllLines(outputFile, StandardCharsets.UTF_8)) {
            if (line.startsWith("{\"ok\":true,\"runId\":")) {
              stopOwnedRun(repo, work, MAPPER.readTree(line).path("runId").asText());
              break;
            }
          }
        }
      } catch (Exception cleanupFailure) {
        if (primary == null) throw cleanupFailure;
        primary.addSuppressed(cleanupFailure);
      }
    }
  }

  private static boolean hasRetainedAlternateEmbedding(Path repo) {
    for (Path ancestor = repo; ancestor != null; ancestor = ancestor.getParent()) {
      if (Files.isRegularFile(ancestor.resolve(
          "models/onnx/gte-multilingual-base/model_fp16.onnx"))) return true;
    }
    return false;
  }

  @ParameterizedTest
  @ValueSource(strings = {"writer", "migration", "lock-ingest", "processing"})
  void supervisedRecoveryUsesTheCorrectExitAndReopensDurableState(String scenario) throws Exception {
    runScenario(scenario);
  }

  @Test
  void initialBootstrapSurvivesHostileLocks() throws Exception {
    runScenario("lock-boot");
  }

  static void runScenario(String scenario) throws Exception {
    boolean processingFamily = "processing".equals(scenario) || "operation".equals(scenario);
    boolean operationFault = scenario.startsWith("ingest-") || scenario.startsWith("settings-")
        || scenario.startsWith("bulk-");
    if ("lock-boot".equals(scenario)) {
      assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"),
          "mandatory file-locking contention at boot is a Windows property");
    }
    if (processingFamily || operationFault) {
      assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"),
          "the repository's process identity collector currently supports Windows only");
    }
    Path repo = repositoryRoot();
    if (scenario.startsWith("installer-")) {
      assumeTrue(hasRetainedInstallerModels(repo),
          "installed standard-model activation requires retained model bytes; run on a model-equipped host");
    }
    Path work =
        repo.resolve("tmp/lane-f-takeover/writer-junit-" + UUID.randomUUID()).normalize();
    Path outputFile = work.resolve("fixture-output.txt");
    Files.createDirectories(work);
    ProcessBuilder builder =
        new ProcessBuilder(
                "node",
                repo.resolve("scripts/supervisor-conformance/real-writer-recovery.mjs")
                    .toString())
            .directory(repo.toFile())
            .redirectErrorStream(true)
            .redirectOutput(outputFile.toFile());
    builder.environment().put("JUSTSEARCH_WRITER_RECOVERY_WORK", work.toString());
    builder.environment().put("JUSTSEARCH_REAL_RECOVERY_SCENARIO", scenario);
    if (processingFamily) {
      Path childArgs = work.resolve("processing-child-args.txt");
      Files.writeString(childArgs, "-Xmx128m\n-Dfile.encoding=UTF-8\n-cp\n\""
          + System.getProperty("java.class.path").replace("\\", "\\\\") + "\"\n"
          + io.justsearch.indexerworker.fixtures.ChaosExtractionSandboxChild.class.getName() + "\n");
      String javaExe = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
      builder.environment().put("JUSTSEARCH_EXTRACTION_SANDBOX_MODE", "process");
      builder.environment().put("JUSTSEARCH_EXTRACTION_SANDBOX_POOL", "1");
      builder.environment().put("JUSTSEARCH_EXTRACTION_SANDBOX_COMMAND",
          "\"" + javaExe + "\" \"@" + childArgs + "\"");
      builder.environment().put("JUSTSEARCH_PROCESSING_TEST_ARGFILE", childArgs.toString());
      builder.environment().put("JUSTSEARCH_PROCESSING_TEST_ENTERED", work.resolve("processing-entered").toString());
    }
    FileIntruder intruder = scenario.startsWith("lock-") ? new FileIntruder(work.resolve("data")) : null;
    boolean intruderStarted = false;
    if ("lock-boot".equals(scenario)) {
      Files.createDirectories(work.resolve("data"));
      intruder.start(5, 10);
      intruderStarted = true;
    }
    Process process = null;
    int exit;
    boolean interrupted = false;
    Throwable primaryFailure = null;
    try {
      process = builder.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(330);
      while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
        if (intruder != null) {
          if (!intruderStarted && Files.exists(work.resolve("intruder-start"))) {
            intruder.start(3, 50);
            intruderStarted = true;
            Files.writeString(work.resolve("intruder-started"), "ready");
          }
          if (Files.exists(work.resolve("intruder-stop"))) {
            intruder.close();
            Files.writeString(work.resolve("intruder-stopped"), "closed");
          }
        }
        if (System.nanoTime() >= deadline) {
          throw new AssertionError("Engine recovery fixture exceeded 330 seconds");
        }
      }
      exit = process.exitValue();
      assertEquals(0, exit, Files.readString(outputFile, StandardCharsets.UTF_8));
    } catch (InterruptedException e) {
      interrupted = true;
      primaryFailure = e;
      throw e;
    } catch (Exception | AssertionError failure) {
      primaryFailure = failure;
      throw failure;
    } finally {
      if (intruder != null) intruder.close();
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
        try {
          process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
      try {
        stopOwnedRun(repo, work);
        if ("writer".equals(scenario) || scenario.startsWith("installer-")) {
          pruneStoppedModelCaches(repo, work);
        }
      } catch (Exception cleanupFailure) {
        if (primaryFailure == null) {
          primaryFailure = cleanupFailure;
          throw cleanupFailure;
        }
        primaryFailure.addSuppressed(cleanupFailure);
      } finally {
        try {
          if (intruder != null) intruder.writeEvidence(work.resolve("intruder-acquisitions.json"));
        } catch (Exception evidenceFailure) {
          if (primaryFailure == null) throw evidenceFailure;
          primaryFailure.addSuppressed(evidenceFailure);
        } finally {
          if (interrupted) {
            Thread.currentThread().interrupt();
          }
        }
      }
    }
    String output = Files.readString(outputFile, StandardCharsets.UTF_8);

    if ("writer".equals(scenario)) {
      assertTrue(output.contains("QUEUE_BEFORE_DEATH"), output);
      assertTrue(output.contains("fatal_or_uncaught"), output);
    } else if ("migration".equals(scenario)) {
      assertTrue(output.contains("MIGRATION_PASS"), output);
    } else if (scenario.startsWith("bulk-")) {
      assertTrue(output.contains("\"scenario\":\"" + scenario + "\""), output);
      assertTrue(output.contains("BULK_FAULT_PASS"), output);
      assertTrue(Files.isRegularFile(work.resolve("bulk-cut.json")), "Missing crash-cut evidence");
      assertTrue(Files.isRegularFile(work.resolve("bulk-final.json")), "Missing successor evidence");
      assertTrue(Files.isRegularFile(work.resolve("bulk-after-retry.json")), "Missing retry evidence");
    } else if (operationFault) {
      assertTrue(output.contains("\"scenario\":\"" + scenario + "\""), output);
      if ("ingest-client-disconnect".equals(scenario)) {
        assertTrue(output.contains("OPERATION_FAULT_DISCONNECT_PASS"), output);
      } else {
        assertTrue(output.contains("OPERATION_FAULT_COOLDOWN_SNAPSHOT"), output);
        String marker = "settings-after-accept-before-effect".equals(scenario)
            ? "OPERATION_FAULT_SETTINGS_PRE_EFFECT_PASS"
            : scenario.startsWith("settings-") ? "OPERATION_FAULT_SETTINGS_PASS" : "OPERATION_FAULT_INGEST_PASS";
        assertTrue(output.contains(marker), output);
      }
    } else if (processingFamily) {
      assertTrue(output.contains("PROCESSING_AFTER_DEATH"), output);
      assertTrue(output.contains("PROCESSING_REPLAY_PASS"), output);
      if ("operation".equals(scenario)) {
        assertTrue(output.contains("OPERATION_RETRY_NO_DUPLICATES_PASS"), output);
        assertTrue(output.contains("OPERATION_ROW_AFTER_DEATH"), output);
        assertTrue(output.contains("OPERATION_ROW_AFTER_RESTART"), output);
      }
    } else {
      assertTrue(output.contains("LOCK_SURVIVAL_PASS"), output);
      assertTrue(intruder.acquiredLockCount() > 0, "the attack must acquire real filesystem locks");
    }
    assertTrue(output.contains("PASS"), output);
    assertTrue(output.contains("\"portsClosed\":true"), output);
  }

  private static boolean hasRetainedInstallerModels(Path repo) {
    for (Path ancestor = repo; ancestor != null; ancestor = ancestor.getParent()) {
      Path models = ancestor.resolve("models");
      if (Files.isRegularFile(models.resolve("onnx/gte-multilingual-base/model.onnx"))
          && Files.isRegularFile(models.resolve("onnx/ner/model.onnx"))
          && Files.isRegularFile(models.resolve("splade/naver-splade-v3/model.onnx"))) {
        return true;
      }
    }
    return false;
  }

  private static void stopOwnedRun(Path repo, Path work) throws Exception {
    Path runs = work.resolve("state/runs");
    if (!Files.isDirectory(runs)) {
      return;
    }
    List<String> runIds;
    try (var entries = Files.list(runs)) {
      runIds =
          entries
              .filter(Files::isDirectory)
              .filter(candidate -> Files.isRegularFile(candidate.resolve("run.json")))
              .map(Path::getFileName)
              .map(Path::toString)
              .toList();
    }
    if (runIds.isEmpty()) {
      return;
    }
    if (runIds.size() != 1) {
      throw new IllegalStateException("ambiguous owned run identities under " + runs + ": " + runIds);
    }
    stopOwnedRun(repo, work, runIds.getFirst());
  }

  private static void stopOwnedRun(Path repo, Path work, String runId) throws Exception {
    Path runs = work.resolve("state/runs");
    Path stopReport = runs.resolve(runId).resolve("stop-report.json");
    if (Files.isRegularFile(stopReport)) {
      try {
        var report = MAPPER.readTree(Files.readString(stopReport));
        if (report != null && report.path("portsClosed").asBoolean(false)) {
          return;
        }
      } catch (java.io.IOException | tools.jackson.core.JacksonException incompleteReport) {
        // A timed-out fixture can die while its stop child is publishing this report.
        // An unreadable report is not proof of cleanup; run the identity-checked stop below.
      }
    }
    ProcessBuilder cleanup =
        new ProcessBuilder(
                "node",
                repo.resolve("scripts/dev/dev-runner.cjs").toString(),
                "stop",
                "--json",
                "--session-id",
                "writer-recovery-live",
                "--run",
                runId)
            .directory(repo.toFile())
            .redirectErrorStream(true)
            .redirectOutput(work.resolve("cleanup-output.txt").toFile());
    cleanup
        .environment()
        .put("JUSTSEARCH_DEV_RUNNER_STATE_ROOT", work.resolve("state").toString());
    Process stop = cleanup.start();
    if (!stop.waitFor(30, TimeUnit.SECONDS) || stop.exitValue() != 0) {
      throw new IllegalStateException("identity-checked cleanup failed for run " + runId);
    }
  }

  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath().normalize();
    while (candidate != null) {
      if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("Could not locate repository root from " + Path.of(""));
  }
}
