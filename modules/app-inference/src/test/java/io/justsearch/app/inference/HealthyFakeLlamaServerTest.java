/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fixture's teardown contract: once {@link HealthyFakeLlamaServer#endAll} returns, no server
 * launched from the fixture is running and nothing it wrote is still held, so the test's
 * {@code @TempDir} can be deleted. The launch is the one {@link LlamaServerOps} makes (output
 * appended to {@code logs/llama-server.log}) and the stop is the one it makes (end the launched
 * process and wait for it), which on Windows ends only the launcher shell and leaves the server
 * itself running until the fixture ends it.
 */
final class HealthyFakeLlamaServerTest {

  @TempDir Path tmp;

  @Test
  @Timeout(value = 25, unit = TimeUnit.SECONDS)
  void afterEndAllNoLaunchedServerRunsAndItsLogCanBeDeleted() throws Exception {
    Path dir = tmp.resolve("fake-llama");
    Path executable = HealthyFakeLlamaServer.install(dir);
    for (int round = 1; round <= 3; round++) {
      Path log = tmp.resolve("round-" + round).resolve("logs").resolve("llama-server.log");
      Files.createDirectories(log.getParent());
      int port;
      try (ServerSocket free = new ServerSocket(0)) {
        port = free.getLocalPort();
      }
      ProcessBuilder launch = new ProcessBuilder(
          List.of(executable.toString(), "--port", String.valueOf(port), "-c", "4096"));
      launch.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
      launch.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
      Process launched = launch.start();
      long server = awaitRecordedServer(dir, round);

      // LlamaServerOps.stopLlamaServer: end the launched process and wait for it.
      launched.destroy();
      assertTrue(launched.waitFor(10, TimeUnit.SECONDS), "the launched process ends");

      HealthyFakeLlamaServer.endAll(dir);

      assertFalse(ProcessHandle.of(server).map(ProcessHandle::isAlive).orElse(false),
          "round " + round + ": the fake server " + server + " still runs after endAll");
      Path moved = log.resolveSibling("llama-server.log.deleted");
      int finalRound = round;
      assertDoesNotThrow(() -> Files.move(log, moved),
          () -> "round " + finalRound + ": the server's log is still held after endAll");
      Files.delete(moved);
    }
  }

  /** The pid the {@code round}-th server recorded once it was serving. */
  private static long awaitRecordedServer(Path dir, int round) throws Exception {
    Path pids = dir.resolve("pids.txt");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
    while (System.nanoTime() < deadline) {
      if (Files.exists(pids)) {
        List<String> lines = Files.readAllLines(pids, StandardCharsets.US_ASCII).stream()
            .filter(line -> !line.isBlank()).toList();
        if (lines.size() >= round) return Long.parseLong(lines.get(round - 1).trim());
      }
      Thread.sleep(20);
    }
    throw new AssertionError("fake llama-server " + round + " never recorded its pid");
  }
}
