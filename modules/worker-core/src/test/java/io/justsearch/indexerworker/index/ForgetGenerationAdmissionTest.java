/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.index;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual START/state-file authority and both race orders, without a model or a fake status. */
@Timeout(20)
final class ForgetGenerationAdmissionTest {
  @TempDir Path directory;

  private AutoCloseable admit(IndexGenerationManager manager, Path active) throws IOException {
    try { return (AutoCloseable) IndexGenerationManager.class.getMethod("beginForgetNoCandidate", Path.class).invoke(manager, active); }
    catch (InvocationTargetException failed) {
      if (failed.getCause() instanceof IOException unavailable) throw unavailable;
      if (failed.getCause() instanceof RuntimeException failure) throw failure;
      if (failed.getCause() instanceof Error fatal) throw fatal;
      throw new AssertionError(failed.getCause());
    } catch (ReflectiveOperationException missing) {
      fail("FORGET admission must use the actual generation START owner", missing);
      return null;
    }
  }

  @Test
  void RF55ForgetWinningFirstHoldsActualNativeStartUntilItsBoundedScopeUnwinds() throws Exception {
    var manager = new IndexGenerationManager(directory.resolve("index"));
    var layout = manager.initializeOrLoad();
    var otherOwner = new IndexGenerationManager(layout.basePath());
    CompletableFuture<IndexGenerationManager.State> start;
    try (var ignoredLease = admit(manager, layout.activeGenerationPath())) {
      var attempted = new CountDownLatch(1);
      start = CompletableFuture.supplyAsync(() -> {
        attempted.countDown();
        try { return otherOwner.startMigration("manual"); }
        catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
      });
      assertTrue(attempted.await(2, TimeUnit.SECONDS));
      var waiting = start;
      assertThrows(TimeoutException.class, () -> waiting.get(50, TimeUnit.MILLISECONDS));
      assertNull(manager.readStateBestEffort().building_generation());
    }
    assertNotNull(start);
    var candidate = start.get(3, TimeUnit.SECONDS);
    assertNotNull(candidate.building_generation());
    assertEquals("MIGRATING", candidate.migration_state());
    assertThrows(IOException.class, () -> admit(manager, layout.activeGenerationPath()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"MIGRATING", "PAUSED", "AWAITING_ACCEPTANCE", "SWITCHING", "FAILED"})
  void RF55ActualCandidateWinningFirstRefusesEvenWhenNotSwitching(String phase) throws Exception {
    var manager = new IndexGenerationManager(directory.resolve("index"));
    var layout = manager.initializeOrLoad();
    // Establish the capability first, so the old version's missing guard fails as an assertion.
    try (var lease = admit(manager, layout.activeGenerationPath())) { assertNotNull(lease); }
    manager.startMigration("manual");
    if (phase.equals("PAUSED")) manager.setMigrationPaused(true, "operator pause");
    else manager.updateMigrationState(IndexGenerationManager.MigrationState.valueOf(phase));
    byte[] before = Files.readAllBytes(layout.basePath().resolve("state.json"));
    assertThrows(IOException.class, () -> admit(manager, layout.activeGenerationPath()));
    assertArrayEquals(before, Files.readAllBytes(layout.basePath().resolve("state.json")),
        "refusal must not repair, abandon or alter somebody else's candidate");
  }

  @Test
  void RF55MissingMalformedFutureOrMismatchedAuthorityCannotFallBackToIdle() throws Exception {
    var manager = new IndexGenerationManager(directory.resolve("index"));
    var layout = manager.initializeOrLoad();
    try (var lease = admit(manager, layout.activeGenerationPath())) { assertNotNull(lease); }
    Path state = layout.basePath().resolve("state.json");
    byte[] original = Files.readAllBytes(state);
    Files.delete(state);
    assertThrows(IOException.class, () -> admit(manager, layout.activeGenerationPath()));
    assertTrue(Files.notExists(state));
    for (String invalid : new String[] {"broken", "{}", "{\"format_version\":999}"}) {
      Files.writeString(state, invalid);
      assertThrows(Exception.class, () -> admit(manager, layout.activeGenerationPath()));
      assertEquals(invalid, Files.readString(state));
    }
    Files.write(state, original);
    assertThrows(IOException.class, () -> admit(manager, layout.basePath().resolve("generations/other")));
    assertArrayEquals(original, Files.readAllBytes(state));
  }
}
