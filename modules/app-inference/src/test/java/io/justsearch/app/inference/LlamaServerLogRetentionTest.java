/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.Mode;
import io.justsearch.app.inference.telemetry.InferenceTelemetryEvents;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 909 item 6 — {@code logs/llama-server.log} had NO retention policy: both stdout and
 * stderr of every launch were {@code Redirect.appendTo} the same file for the life of the install,
 * so the file grew without bound and carried every launch's diagnostics — whose verbosity is
 * llama-server's choice, not ours — forever.
 *
 * <p>These drive the REAL launch-path method ({@code configureServerLogRedirection}), not a private
 * rotation helper, so a future edit that stops calling the rotation fails here.
 */
final class LlamaServerLogRetentionTest {

  @TempDir Path home;

  @Test
  @DisplayName("each launch rotates the previous log and keeps exactly three generations")
  void launchRotatesAndBoundsGenerations() throws IOException {
    Path logFile = home.resolve("logs").resolve("llama-server.log");
    LlamaServerOps ops = newOps();

    launch(ops, logFile, "first launch\n");
    launch(ops, logFile, "second launch\n");
    launch(ops, logFile, "third launch\n");
    launch(ops, logFile, "fourth launch\n");

    assertEquals("fourth launch\n", Files.readString(logFile));
    assertEquals("third launch\n", Files.readString(generation(logFile, 1)));
    assertEquals("second launch\n", Files.readString(generation(logFile, 2)));
    assertFalse(
        Files.exists(generation(logFile, 3)),
        "retention is bounded: the fourth-oldest generation must be gone, not accumulating");
    assertEquals(
        3,
        countLogFiles(logFile.getParent()),
        "exactly the live log plus two archived generations survive a launch");
  }

  @Test
  @DisplayName("the launch redirects both streams to the live log and starts it empty")
  void launchRedirectsToAFreshLiveLog() throws IOException {
    Path logFile = home.resolve("logs").resolve("llama-server.log");
    Files.createDirectories(logFile.getParent());
    Files.writeString(logFile, "a previous launch's output\n");
    LlamaServerOps ops = newOps();

    ProcessBuilder pb = new ProcessBuilder(List.of("does-not-run"));
    Path returned = ops.configureServerLogRedirection(pb, logFile);

    assertEquals(logFile, returned);
    assertFalse(
        Files.exists(logFile), "the live log is rotated away, so this launch starts a fresh file");
    assertEquals(
        "a previous launch's output\n",
        Files.readString(generation(logFile, 1)),
        "the previous launch's output survives exactly one restart");
    assertEquals(logFile.toFile(), pb.redirectOutput().file());
    assertEquals(logFile.toFile(), pb.redirectError().file());
    assertTrue(pb.redirectOutput().type() == ProcessBuilder.Redirect.Type.APPEND);
  }

  /**
   * The failure branch, and the reason the live file is renamed BEFORE anything is pruned. On
   * Windows a rename fails while a handle is still open — a llama-server that outlived the 5s kill
   * timeout, an orphan, or a handle the OS has not released yet — which is precisely the
   * crash-restart loop where retention matters. Prune-then-move would have already deleted the
   * oldest generation and shifted the rest before discovering it cannot move the live file: fewer
   * retained logs AND an unbounded live file, from a code path whose whole purpose is retention.
   *
   * <p>Windows-tagged because the guarantee is Windows-specific: on Linux an open descriptor does
   * not block a rename, so the injection cannot be reproduced there (tempdoc 668 lane split). The
   * open {@code FileOutputStream} is the real mechanism, not a mock — Java opens without
   * FILE_SHARE_DELETE on Windows, so the rename fails exactly as it does in production.
   */
  @Test
  @Tag("windows")
  @DisplayName("a live log that cannot be renamed leaves every retained generation intact")
  void rotationFailureLeavesOlderGenerationsIntact() throws IOException {
    Path logFile = home.resolve("logs").resolve("llama-server.log");
    LlamaServerOps ops = newOps();
    launch(ops, logFile, "oldest\n");
    launch(ops, logFile, "middle\n");
    launch(ops, logFile, "live\n");
    // Precondition: a full retained set, which the failing rotation must not shrink.
    assertEquals("middle\n", Files.readString(generation(logFile, 1)));
    assertEquals("oldest\n", Files.readString(generation(logFile, 2)));

    try (var held = new java.io.FileOutputStream(logFile.toFile(), true)) {
      held.write("still being written\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      held.flush();
      ops.configureServerLogRedirection(new ProcessBuilder(List.of("does-not-run")), logFile);

      assertEquals(
          "middle\n",
          Files.readString(generation(logFile, 1)),
          "generation 1 must not have been shifted by a rotation that could not complete");
      assertEquals(
          "oldest\n",
          Files.readString(generation(logFile, 2)),
          "the oldest generation must not have been pruned before the live file moved");
      assertTrue(
          Files.exists(logFile),
          "the live log is still the live log — the launch appends to it rather than losing it");
      assertTrue(
          Files.readString(logFile).contains("still being written"),
          "and it still holds what the previous process wrote");
    }
  }

  /** One launch: configure redirection (which rotates), then write what that launch "produced". */
  private static void launch(LlamaServerOps ops, Path logFile, String output) throws IOException {
    ops.configureServerLogRedirection(new ProcessBuilder(List.of("does-not-run")), logFile);
    Files.writeString(logFile, output);
  }

  private static Path generation(Path logFile, int n) {
    return logFile.resolveSibling(logFile.getFileName() + "." + n);
  }

  private static long countLogFiles(Path dir) throws IOException {
    try (var entries = Files.list(dir)) {
      return entries.filter(p -> p.getFileName().toString().startsWith("llama-server.log")).count();
    }
  }

  private static LlamaServerOps newOps() {
    return new LlamaServerOps(new InferenceExecutorRegistrations(new io.justsearch.core.execution.TestEngineExecutors()),
        HttpClient.newHttpClient(),
        new ObjectMapper(),
        () -> null,
        null,
        () -> Mode.OFFLINE,
        new PropsObserver() {
          @Override
          public void onModelIdObserved(String modelId) {}

          @Override
          public void onContextTokensObserved(int contextTokens) {}

          @Override
          public String observedModelId() {
            return null;
          }

          @Override
          public Integer observedContextTokens() {
            return null;
          }
        },
        () -> {},
        reason -> {},
        InferenceTelemetryEvents.noop());
  }
}
