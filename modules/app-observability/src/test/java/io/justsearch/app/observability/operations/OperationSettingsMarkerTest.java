/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OperationSettingsMarkerTest {
  private static final Clock CLOCK = Clock.systemUTC();
  private static final EngineContext CONTEXT = new EngineContext(EngineContext.ClientKind.INTERNAL,
      "settings-test", Optional.empty(), Optional.empty(), "system", "SYSTEM_INTERNAL",
      EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.BACKGROUND);
  @TempDir Path directory;

  @ParameterizedTest
  @EnumSource(value = OperationKind.class, names = {"SETTINGS_APPLY", "RECONFIGURE"})
  void armsRunningSettingsExactlyOnceAndRetainsMarkerOnReopen(OperationKind kind) throws Exception {
    Path path = directory.resolve("operations.db");
    String key = OperationKeys.generate(CLOCK);
    try (var store = new SqliteOperationStore(path)) {
      var row = store.accept(key, new OperationDescriptor(kind, null, "{}"), CONTEXT, null).record();
      assertNull(row.expectedSettingsRevision());
      assertFalse(store.armSettingsRevision(row.id(), 4));
      assertTrue(store.start(row.id()));
      AtomicInteger notifications = new AtomicInteger();
      try (var ignored = store.subscribeCompletions(completed -> notifications.incrementAndGet())) {
        assertTrue(store.armSettingsRevision(row.id(), 4));
        assertFalse(store.armSettingsRevision(row.id(), 4));
        assertFalse(store.armSettingsRevision(row.id(), 5));
        assertEquals(0, notifications.get());
        assertEquals(OperationState.RUNNING, store.find(key).orElseThrow().state());
      }
    }
    try (var reopened = new SqliteOperationStore(path)) {
      var row = reopened.find(key).orElseThrow();
      assertEquals(4L, row.expectedSettingsRevision());
      assertFalse(reopened.armSettingsRevision(row.id(), 5));
      reopened.finish(row.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null));
      assertFalse(reopened.armSettingsRevision(row.id(), 5));
      assertEquals(4L, reopened.find(key).orElseThrow().expectedSettingsRevision());
    }
  }

  @ParameterizedTest
  @EnumSource(value = OperationKind.class, names = {"SETTINGS_APPLY", "RECONFIGURE"})
  void zeroRevisionIsEligibleButUnarmedTerminalRowsAreImmutable(OperationKind kind) throws Exception {
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var descriptor = new OperationDescriptor(kind, null, "{}");
      var first = store.accept(OperationKeys.generate(CLOCK), descriptor, CONTEXT, null).record();
      store.start(first.id());
      assertTrue(store.armSettingsRevision(first.id(), 0));
      assertEquals(0L, store.find(first.key()).orElseThrow().expectedSettingsRevision());
      for (var state : java.util.List.of(OperationState.COMPLETE, OperationState.FAILED, OperationState.CANCELLED)) {
        var terminal = store.accept(OperationKeys.generate(CLOCK), descriptor, CONTEXT, null).record();
        store.start(terminal.id());
        store.finish(terminal.id(), state, new OperationReceipt("TEST_OUTCOME", null));
        assertFalse(store.armSettingsRevision(terminal.id(), 0));
        assertNull(store.find(terminal.key()).orElseThrow().expectedSettingsRevision());
      }
    }
  }

  @Test
  void otherKindsAndUnadvanceableRevisionsRefuse() throws Exception {
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var row = store.accept(OperationKeys.generate(CLOCK),
          new OperationDescriptor(OperationKind.OPERATION, null, "{}"), CONTEXT, null).record();
      store.start(row.id());
      assertFalse(store.armSettingsRevision(row.id(), 0));
      assertThrows(IllegalArgumentException.class, () -> store.armSettingsRevision(row.id(), -1));
      assertThrows(IllegalArgumentException.class, () -> store.armSettingsRevision(row.id(), Long.MAX_VALUE));
      assertNull(store.find(row.key()).orElseThrow().expectedSettingsRevision());
    }
  }

  @Test
  void concurrentMarkersHaveOneWinnerAndFailedWriteLeavesNull() throws Exception {
    Path path = directory.resolve("operations.db");
    try (var store = new SqliteOperationStore(path);
        var pool = Executors.newFixedThreadPool(2)) {
      var row = store.accept(OperationKeys.generate(CLOCK),
          new OperationDescriptor(OperationKind.SETTINGS_APPLY, null, "{}"), CONTEXT, null).record();
      store.start(row.id());
      try (var db = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
          var statement = db.createStatement()) {
        statement.execute("CREATE TRIGGER refuse_marker BEFORE UPDATE OF accepted_settings_revision ON operations "
            + "BEGIN SELECT RAISE(ABORT, 'fixture'); END");
        assertThrows(OperationStoreException.class, () -> store.armSettingsRevision(row.id(), 2));
        assertNull(store.find(row.key()).orElseThrow().expectedSettingsRevision());
        statement.execute("DROP TRIGGER refuse_marker");
      }
      CountDownLatch start = new CountDownLatch(1);
      var first = pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return store.armSettingsRevision(row.id(), 2); });
      var second = pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return store.armSettingsRevision(row.id(), 3); });
      start.countDown();
      assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
      long winner = store.find(row.key()).orElseThrow().expectedSettingsRevision();
      assertTrue(winner == 2 || winner == 3);
    }
  }
}
