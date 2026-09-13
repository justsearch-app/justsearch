/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.supervision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Timeout;

/** Installed Engine proofs for durable recovery, migration and hostile filesystem survival. */
@Timeout(7 * 60)
final class EngineSupervisedRecoveryE2ETest {

  @ParameterizedTest
  @ValueSource(strings = {"writer", "migration", "lock-boot", "lock-ingest", "processing"})
  void supervisedRecoveryUsesTheCorrectExitAndReopensDurableState(String scenario) throws Exception {
    runScenario(scenario);
  }

  static void runScenario(String scenario) throws Exception {
    boolean processingFamily = "processing".equals(scenario) || "operation".equals(scenario);
    if ("lock-boot".equals(scenario)) {
      assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"),
          "mandatory file-locking contention at boot is a Windows property");
    }
    if (processingFamily) {
      assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"),
          "the repository's process identity collector currently supports Windows only");
    }
    Path repo = repositoryRoot();
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
    } catch (InterruptedException e) {
      interrupted = true;
      throw e;
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
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
    String output = Files.readString(outputFile, StandardCharsets.UTF_8);

    assertEquals(0, exit, output);
    if ("writer".equals(scenario)) {
      assertTrue(output.contains("QUEUE_BEFORE_DEATH"), output);
      assertTrue(output.contains("fatal_or_uncaught"), output);
    } else if ("migration".equals(scenario)) {
      assertTrue(output.contains("MIGRATION_PASS"), output);
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
    String runId = runIds.getFirst();
    Path stopReport = runs.resolve(runId).resolve("stop-report.json");
    if (Files.isRegularFile(stopReport)) {
      try {
        var report = new tools.jackson.databind.ObjectMapper().readTree(Files.readString(stopReport));
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
