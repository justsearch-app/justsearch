/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

final class MigrationTransitionBarrierTest {
  @TempDir Path data;

  @Test
  void selectionIsHarnessOnlyAndAbsentSelectionIsTheExactNoop() {
    assertSame(MigrationTransitionBarrier.NO_HOOK,
        MigrationTransitionBarrier.fromEnvironment(data, Map.<String, String>of()::get));
    assertThrows(IllegalArgumentException.class, () -> MigrationTransitionBarrier.fromEnvironment(
        data, Map.of("JUSTSEARCH_MIGRATION_BARRIER_POINT", "migration-before-pointer-commit")::get));
    assertThrows(IllegalArgumentException.class, () -> MigrationTransitionBarrier.fromEnvironment(
        data, Map.of("JUSTSEARCH_SUPERVISOR_HARNESS", "1",
            "JUSTSEARCH_MIGRATION_BARRIER_POINT", "unknown")::get));
  }

  @Test
  void installedHandshakeReportsExactGenerationAndDoesNotRetrigger() throws Exception {
    var hook = MigrationTransitionBarrier.fromEnvironment(data,
        Map.of("JUSTSEARCH_SUPERVISOR_HARNESS", "1",
            "JUSTSEARCH_MIGRATION_BARRIER_POINT", "migration-before-pointer-commit")::get);
    Path reached = data.resolve("runtime/migration-barrier-reached.json");
    hook.await(new MigrationTransitionBarrier.Transition("migration-green-drained", "a", "b"));
    assertFalse(Files.exists(reached));
    Files.createDirectories(reached.getParent());
    Files.writeString(data.resolve("runtime/migration-barrier-release"), "release");
    hook.await(new MigrationTransitionBarrier.Transition("migration-before-pointer-commit", "a", "b"));
    var marker = JsonMapper.builder().build().readTree(Files.readString(reached));
    assertEquals("migration-before-pointer-commit", marker.path("point").asText());
    assertEquals("a", marker.path("sourceGeneration").asText());
    assertEquals("b", marker.path("buildingGeneration").asText());
    assertEquals(ProcessHandle.current().pid(), marker.path("pid").asLong());
    Files.delete(data.resolve("runtime/migration-barrier-release"));
    hook.await(new MigrationTransitionBarrier.Transition("migration-before-pointer-commit", "a", "b"));
  }

  @Test
  void inProcessBarrierReleasesOrCancelsAtTheExactPoint() throws Exception {
    var release = new MigrationTransitionBarrier.Controlled("migration-before-pointer-commit");
    var first = new AtomicReference<Throwable>();
    Thread released = new Thread(() -> {
      try {
        release.await(new MigrationTransitionBarrier.Transition("migration-before-pointer-commit", "a", "b"));
      } catch (Throwable failure) { first.set(failure); }
    });
    released.start();
    assertTrue(release.awaitReached(5, TimeUnit.SECONDS));
    assertTrue(released.isAlive());
    release.release();
    released.join(5_000);
    assertFalse(released.isAlive());
    assertEquals(null, first.get());

    var cancel = new MigrationTransitionBarrier.Controlled("migration-before-pointer-commit");
    var second = new AtomicReference<Throwable>();
    Thread cancelled = new Thread(() -> {
      try {
        cancel.await(new MigrationTransitionBarrier.Transition("migration-before-pointer-commit", "a", "b"));
      } catch (Throwable failure) { second.set(failure); }
    });
    cancelled.start();
    assertTrue(cancel.awaitReached(5, TimeUnit.SECONDS));
    cancel.cancel();
    cancelled.join(5_000);
    assertFalse(cancelled.isAlive());
    assertTrue(second.get() instanceof InterruptedException);
  }
}
