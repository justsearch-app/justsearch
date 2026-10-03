/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexerworker.ingest.IngestionSkipPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ExtractionConfigurationTest {

  @Test
  void normalizesTheExactObjectsUsedByProcessExtraction() {
    ResolvedConfig snapshot =
        new ResolvedConfigBuilder()
            .putDefault("justsearch.extraction.sandbox.mode", " process ")
            .putDefault("justsearch.extraction.sandbox.heap", " 768m ")
            .putDefault("justsearch.extraction.sandbox.pool", "3")
            .putDefault("justsearch.extraction.sandbox.max_requests", "41")
            .putDefault("index.ocr.languages", "eng,deu")
            .putDefault("index.ocr.workers", "2")
            .putDefault("worker.limits.max_content_length", "12345")
            .putDefault("worker.limits.max_file_size", "23456")
            .build();

    ExtractionConfiguration configuration =
        ExtractionConfiguration.capture(snapshot, 4, IngestionSkipPolicy.defaultPolicy());

    assertEquals(ExtractionSandboxFactory.Mode.PROCESS, configuration.sandboxMode());
    assertEquals(
        ExtractionConfiguration.SandboxCommandOrigin.BUILT_IN,
        configuration.sandboxCommandOrigin());
    assertEquals("768m", configuration.sandboxHeap());
    assertTrue(commandContains(configuration.sandboxCommand(), "-Xmx768m"));
    assertEquals(new ExtractionSandboxFactory.PoolSettings(3, 41), configuration.sandboxPool());
    assertEquals(2, configuration.ocr().effectiveOcrWorkers());
    assertEquals(java.util.List.of("eng", "deu"), configuration.ocr().languages());
    assertEquals(12345, configuration.tikaPolicy().maxExtractedChars());
    assertEquals(23456, configuration.tikaPolicy().maxInputBytes());
  }

  @Test
  void inProcessModeDoesNotProjectUnusedChildSettings() {
    ResolvedConfig snapshot =
        new ResolvedConfigBuilder()
            .putDefault("justsearch.extraction.sandbox.mode", "in_process")
            .putDefault("justsearch.extraction.sandbox.command", "ignored command")
            .putDefault("justsearch.extraction.sandbox.heap", "2g")
            .putDefault("justsearch.extraction.sandbox.pool", "8")
            .putDefault("justsearch.extraction.sandbox.max_requests", "9")
            .build();

    ExtractionConfiguration configuration =
        ExtractionConfiguration.capture(snapshot, 1, IngestionSkipPolicy.defaultPolicy());

    assertEquals(ExtractionSandboxFactory.Mode.IN_PROCESS, configuration.sandboxMode());
    assertEquals(
        ExtractionConfiguration.SandboxCommandOrigin.NONE,
        configuration.sandboxCommandOrigin());
    assertNull(configuration.sandboxCommand());
    assertNull(configuration.sandboxHeap());
    assertNull(configuration.sandboxPool());
  }

  @Test
  void operatorCommandProjectsNormalizedArgvWithoutBuiltinHeap() {
    ResolvedConfig snapshot =
        new ResolvedConfigBuilder()
            .putDefault("justsearch.extraction.sandbox.mode", "process")
            .putDefault(
                "justsearch.extraction.sandbox.command", "\"C:\\Program Files\\java.exe\" --child")
            .putDefault("justsearch.extraction.sandbox.heap", "2g")
            .build();

    ExtractionConfiguration configuration =
        ExtractionConfiguration.capture(snapshot, 1, IngestionSkipPolicy.defaultPolicy());

    assertEquals(
        ExtractionConfiguration.SandboxCommandOrigin.OPERATOR,
        configuration.sandboxCommandOrigin());
    assertEquals(java.util.List.of("C:\\Program Files\\java.exe", "--child"),
        configuration.sandboxCommand());
    assertNull(configuration.sandboxHeap());
  }

  private static boolean commandContains(java.util.List<String> command, String argument) {
    if (command.contains(argument)) {
      return true;
    }
    return command.stream()
        .filter(token -> token.startsWith("@"))
        .map(token -> Path.of(token.substring(1)))
        .filter(Files::isRegularFile)
        .map(path -> {
          try {
            return Files.readString(path);
          } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
          }
        })
        .anyMatch(contents -> contents.contains(ExtractionSandboxCommand.argFileToken(argument)));
  }
}
