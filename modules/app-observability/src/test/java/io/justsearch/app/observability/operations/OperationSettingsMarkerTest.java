/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationPreparedPayload;
import io.justsearch.app.api.operations.OperationStore;
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
import java.util.UUID;
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
  void installerGenerationHasAnExactSeparateMarker() throws Exception {
    Path path = directory.resolve("operations.db");
    var activation = new OperationDescriptor(OperationKind.REINDEX,
        "core.activate-installed-models", "{}");
    var ordinary = new OperationDescriptor(OperationKind.REINDEX, "core.bulk-reindex", "{}");
    try (var store = new SqliteOperationStore(path)) {
      var unprepared = store.accept(OperationKeys.generate(CLOCK), activation, CONTEXT, null).record();
      store.start(unprepared.id());
      assertFalse(store.armInstallerGenerationSettingsRevision(unprepared.id(), 4));

      var other = store.accept(OperationKeys.generate(CLOCK), ordinary, CONTEXT, null).record();
      store.start(other.id());
      assertFalse(store.armInstallerGenerationSettingsRevision(other.id(), 4));

      String wrongSchemaKey = OperationKeys.generate(CLOCK);
      var wrongSchema = new OperationStore.Preparation(UUID.randomUUID(), new OperationPreparedPayload(false,
          "{\"preparation\":{\"replaySchema\":\"recorded-bulk-v1\"}}"));
      store.savePreparation(wrongSchemaKey, activation, wrongSchema);
      var wrongRow = store.acceptPrepared(wrongSchemaKey, activation, CONTEXT, null,
          wrongSchema.nonce()).record();
      store.start(wrongRow.id());
      assertFalse(store.armInstallerGenerationSettingsRevision(wrongRow.id(), 4));

      String key = OperationKeys.generate(CLOCK);
      var prepared = new OperationStore.Preparation(UUID.randomUUID(), new OperationPreparedPayload(false,
          "{\"preparation\":{\"replaySchema\":\"recorded-installer-generation-v2\"}}"));
      store.savePreparation(key, activation, prepared);
      var row = store.acceptPrepared(key, activation, CONTEXT, null, prepared.nonce()).record();
      assertFalse(store.armInstallerGenerationSettingsRevision(row.id(), 4));
      store.start(row.id());
      assertFalse(store.armSettingsRevision(row.id(), 4));
      assertTrue(store.armInstallerGenerationSettingsRevision(row.id(), 4));
      assertTrue(store.armInstallerGenerationSettingsRevision(row.id(), 4),
          "the same durable marker must survive a resumed activation attempt");
      assertFalse(store.armInstallerGenerationSettingsRevision(row.id(), 5));
    }
    try (var reopened = new SqliteOperationStore(path)) {
      var row = reopened.openRecords().stream()
          .filter(candidate -> "core.activate-installed-models".equals(candidate.descriptor().operationRef())
              && candidate.expectedSettingsRevision() != null)
          .findFirst().orElseThrow();
      assertEquals(4L, row.expectedSettingsRevision());
      assertTrue(reopened.armInstallerGenerationSettingsRevision(row.id(), 4),
          "the same revision must re-arm after process restart");
      reopened.finish(row.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null));
      assertFalse(reopened.armInstallerGenerationSettingsRevision(row.id(), 5));
    }
  }

  @Test
  void installerV4SettingsMarkerSurvivesReopen() throws Exception {
    Path path = directory.resolve("installer-v4-operations.db");
    String key = OperationKeys.generate(CLOCK);
    var activation = new OperationDescriptor(OperationKind.REINDEX,
        "core.activate-installed-models", "{}");
    var prepared = new OperationStore.Preparation(UUID.randomUUID(), new OperationPreparedPayload(false,
        "{\"preparation\":{\"replaySchema\":\"recorded-installer-generation-v4\"}}"));
    try (var store = new SqliteOperationStore(path)) {
      store.savePreparation(key, activation, prepared);
      var row = store.acceptPrepared(key, activation, CONTEXT, null, prepared.nonce()).record();
      store.start(row.id());
      assertTrue(store.armInstallerGenerationSettingsRevision(row.id(), 7));
    }
    try (var reopened = new SqliteOperationStore(path)) {
      var row = reopened.find(key).orElseThrow();
      assertEquals(7L, row.expectedSettingsRevision());
      assertTrue(reopened.armInstallerGenerationSettingsRevision(row.id(), 7));
      assertFalse(reopened.armInstallerGenerationSettingsRevision(row.id(), 8));
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
