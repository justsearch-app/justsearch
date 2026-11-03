/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.index;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LongPauseStateAdmissionTest {
  @TempDir Path temporary;

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @CsvSource({"ascii,false", "escaped,false", "ascii,true", "escaped,true"})
  void nativeBootAcceptsLongPauseStateWrittenByManager(String shape, boolean previous)
      throws Exception {
    var manager = new IndexGenerationManager(temporary);
    String active = manager.initializeOrLoad().activeGenerationId();
    String reason = shape.equals("ascii") ? "operator-" + "x".repeat(20_000) : "\"".repeat(10_000);
    var written = assertDoesNotThrow(() -> manager.setMigrationPaused(true, reason));
    assertEquals(reason, written.pause_reason());
    Path state = temporary.resolve("state.json");
    assertTrue(Files.size(state) > 16_384, "exercise state larger than the former admission limit");
    if (previous) {
      Files.move(state, temporary.resolve("state.json.prev"), StandardCopyOption.REPLACE_EXISTING);
    }

    var boot = assertDoesNotThrow(() -> new IndexGenerationManager(temporary).initializeForBoot(
        new IndexGenerationManager.BootOwnership.Native(), null));

    assertEquals(active, boot.layout().activeGenerationId());
    assertTrue(boot.layout().state().migration_paused());
    assertEquals(reason, boot.layout().state().pause_reason());
  }
}
