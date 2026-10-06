/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec F §4.3: the quit deadline is named {@code deadlineAtEpochMs} on the lifecycle body, and a
 * request file may carry it under that name. Uses only the reader that exists at the base, so the
 * before-state fails by assertion.
 */
final class ShutdownRequestDeadlineNameTest {

  @Test
  void deadlineIsReadUnderItsSpecFName(@TempDir Path tempDir) throws Exception {
    Path spelled = Files.createDirectories(tempDir.resolve("at"));
    Files.writeString(ShutdownRequest.pathIn(spelled),
        "{\"reason\":\"quit\",\"deadlineAtEpochMs\":1725000008000,\"issuedBy\":\"shell\"}");
    Optional<ShutdownRequest> read = ShutdownRequest.read(spelled);
    assertTrue(read.isPresent(), "a request naming its deadline deadlineAtEpochMs must be read");
    assertEquals(1_725_000_008_000L, read.get().deadlineEpochMs());

    // Both present: the established name wins, so no writer's file changes meaning.
    Path both = Files.createDirectories(tempDir.resolve("both"));
    Files.writeString(ShutdownRequest.pathIn(both),
        "{\"reason\":\"quit\",\"deadlineEpochMs\":5,\"deadlineAtEpochMs\":9}");
    assertEquals(5L, ShutdownRequest.read(both).orElseThrow().deadlineEpochMs());
  }
}
