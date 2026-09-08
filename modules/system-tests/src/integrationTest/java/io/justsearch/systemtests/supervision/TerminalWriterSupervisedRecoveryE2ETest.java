/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.supervision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real installed-Engine proof for terminal Lucene writer recovery through the dev supervisor. */
@Timeout(7 * 60)
final class TerminalWriterSupervisedRecoveryE2ETest {

  @Test
  void terminalWriterExitsRestartsAndReplaysAcceptedWork() throws Exception {
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
    Process process = builder.start();
    int exit;
    boolean interrupted = false;
    try {
      if (!process.waitFor(330, TimeUnit.SECONDS)) {
        throw new AssertionError("writer recovery fixture exceeded 330 seconds");
      }
      exit = process.exitValue();
    } catch (InterruptedException e) {
      interrupted = true;
      throw e;
    } finally {
      if (process.isAlive()) {
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
    assertTrue(output.contains("QUEUE_BEFORE_DEATH"), output);
    assertTrue(output.contains("fatal_or_uncaught"), output);
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
