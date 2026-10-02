/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationPreparedPayload;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.core.context.EngineContext;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.AdditionalAnswers;

final class SqliteOperationStoreTransactionFailureTest {
  private static final Clock CLOCK = Clock.fixed(
      Instant.parse("2026-09-13T00:00:00Z"), ZoneOffset.UTC);
  private static final OperationDescriptor IDENTITY = OperationDescriptor.invocation(
      OperationKind.NOTE, "core.file-note", "{\"path\":\"C:/commit-failure\"}", false);
  private static final EngineContext CONTEXT = new EngineContext(
      EngineContext.ClientKind.INTERNAL, "commit-failure", Optional.empty(), Optional.empty(),
      "SYSTEM", "SYSTEM_INTERNAL", EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);

  @TempDir Path temp;

  @ParameterizedTest
  @ValueSource(strings = {"sql", "runtime", "error"})
  void ordinaryFailedCloseRefusesOperationsUntilNativeCloseSucceeds(String cleanupKind)
      throws Exception {
    try (var store = new SqliteOperationStore(temp.resolve("ordinary-close.db"), CLOCK, ignored -> {})) {
      Connection realConnection = connection(store);
      Connection faulting = mock(Connection.class, AdditionalAnswers.delegatesTo(realConnection));
      Throwable closeFailure = cleanupFailure(cleanupKind, "injected ordinary close failure");
      doThrow(closeFailure).when(faulting).close();
      setConnection(store, faulting);
      try {
        Throwable observed = assertThrows(
            cleanupKind.equals("sql") ? IOException.class : closeFailure.getClass(), store::close);
        assertSame(closeFailure, cleanupKind.equals("sql") ? observed.getCause() : observed);
        assertFalse(realConnection.isClosed());
        OperationStoreException refused = assertThrows(OperationStoreException.class,
            () -> store.find(OperationKeys.generate(CLOCK)));
        assertEquals(OperationStoreException.Code.STORAGE_FAILED, refused.code());
        verify(faulting).close();
        doAnswer(invocation -> {
          realConnection.close();
          return null;
        }).when(faulting).close();
        store.close();
        assertTrue(realConnection.isClosed());
        verify(faulting, times(2)).close();
      } finally {
        doAnswer(invocation -> {
          realConnection.close();
          return null;
        }).when(faulting).close();
        store.close();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"sql", "runtime", "error"})
  void commitFailureKeepsPrimaryAndCleanupFailuresAndRetiresUncertainConnection(String cleanupKind)
      throws Exception {
    Path dbPath = temp.resolve("operations.db");
    String key = OperationKeys.generate(CLOCK);
    OperationStore.Preparation prepared = new OperationStore.Preparation(UUID.randomUUID(),
        new OperationPreparedPayload(false, "{\"target\":\"commit-failure\"}"));
    try (var store = new SqliteOperationStore(dbPath, CLOCK, ignored -> {})) {
      assertEquals(Optional.of(prepared), store.savePreparation(key, IDENTITY, prepared));

      Connection realConnection = connection(store);
      Statement realControl = realConnection.createStatement();
      SQLException commitFailure = new SQLException("injected primary commit failure", "SQLITE_IOERR", 10);
      Throwable rollbackFailure = cleanupFailure(cleanupKind, "injected rollback failure");
      Throwable closeFailure = cleanupFailure(cleanupKind, "injected connection close failure");
      Statement faultingControl = mock(Statement.class, AdditionalAnswers.delegatesTo(realControl));
      doAnswer(invocation -> {
        String sql = invocation.getArgument(0, String.class);
        if ("COMMIT".equals(sql)) throw commitFailure;
        if ("ROLLBACK".equals(sql)) throw rollbackFailure;
        return realControl.execute(sql);
      }).when(faultingControl).execute(anyString());

      Connection faultingConnection = mock(Connection.class, AdditionalAnswers.delegatesTo(realConnection));
      AtomicBoolean controlRequested = new AtomicBoolean();
      doAnswer(invocation -> controlRequested.compareAndSet(false, true)
          ? faultingControl : realConnection.createStatement()).when(faultingConnection).createStatement();
      doThrow(closeFailure).when(faultingConnection).close();
      setConnection(store, faultingConnection);

      try {
        OperationStoreException observed = assertThrows(OperationStoreException.class,
            () -> store.acceptPrepared(key, IDENTITY, CONTEXT, null, prepared.nonce()));
        assertEquals(OperationStoreException.Code.STORAGE_FAILED, observed.code());
        assertSame(commitFailure, observed.getCause(), "the commit error must remain primary");
        Throwable[] suppressed = commitFailure.getSuppressed();
        assertEquals(2, suppressed.length, "rollback and close failures must both be retained");
        assertSame(rollbackFailure, suppressed[0]);
        assertSame(closeFailure, suppressed[1]);
        verify(faultingConnection).close();

        OperationStoreException unavailable = assertThrows(OperationStoreException.class,
            () -> store.find(key), "later store calls must not reuse the uncertain connection");
        assertEquals(OperationStoreException.Code.STORAGE_FAILED, unavailable.code());
        assertTrue(unavailable.getCause().getMessage().contains("closed"));
        Throwable shutdownFailure = assertThrows(
            cleanupKind.equals("sql") ? IOException.class : closeFailure.getClass(), store::close);
        assertSame(closeFailure,
            cleanupKind.equals("sql") ? shutdownFailure.getCause() : shutdownFailure);
        verify(faultingConnection, times(2)).close();
        assertFalse(realConnection.isClosed(), "failed cleanup still owns the native handle");

        try (Connection replacement = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            Statement writer = replacement.createStatement()) {
          writer.execute("PRAGMA busy_timeout = 0");
          SQLException busy = assertThrows(SQLException.class, () -> writer.execute("BEGIN IMMEDIATE"));
          assertEquals(5, busy.getErrorCode() & 0xff, "the failed owner still holds the writer");

          doAnswer(invocation -> {
            realConnection.close();
            return null;
          }).when(faultingConnection).close();
          store.close();
          assertTrue(realConnection.isClosed(), "successful retry must close the native handle");
          verify(faultingConnection, times(3)).close();
          writer.execute("BEGIN IMMEDIATE");
          writer.execute("ROLLBACK");
          store.close();
          verify(faultingConnection, times(3)).close();
        }
      } finally {
        doAnswer(invocation -> {
          realConnection.close();
          return null;
        }).when(faultingConnection).close();
        try {
          realControl.close();
        } finally {
          store.close();
        }
      }
    }
  }

  private static Connection connection(SqliteOperationStore store) throws ReflectiveOperationException {
    return (Connection) connectionField().get(store);
  }

  private static Throwable cleanupFailure(String kind, String message) {
    return switch (kind) {
      case "sql" -> new SQLException(message);
      case "runtime" -> new IllegalStateException(message);
      case "error" -> new AssertionError(message);
      default -> throw new IllegalArgumentException(kind);
    };
  }

  private static void setConnection(SqliteOperationStore store, Connection connection)
      throws ReflectiveOperationException {
    connectionField().set(store, connection);
  }

  private static Field connectionField() throws NoSuchFieldException {
    Field field = SqliteOperationStore.class.getDeclaredField("connection");
    field.setAccessible(true);
    return field;
  }
}
