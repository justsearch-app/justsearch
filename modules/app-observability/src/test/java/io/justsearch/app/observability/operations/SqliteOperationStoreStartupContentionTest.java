/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.operations.OperationStoreException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class SqliteOperationStoreStartupContentionTest {
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-12T09:00:00Z"), ZoneOffset.UTC);

  @TempDir Path temp;

  private Connection connect(Path path) throws SQLException {
    return DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void windowsFileLockExhaustsBudgetAndCanReopenAfterRelease() throws Exception {
    Path path = temp.resolve("operations.db");
    AtomicInteger beforeJournalMode = new AtomicInteger();
    AtomicInteger contentionClosed = new AtomicInteger();
    AtomicReference<FileChannel> lockChannel = new AtomicReference<>();
    AtomicReference<FileLock> fileLock = new AtomicReference<>();
    try {
      SQLException failure = assertThrows(SQLException.class, () -> {
        try (var store = new SqliteOperationStore(path, CLOCK, step -> {
          if (step.equals("before-journal-mode")) {
            beforeJournalMode.incrementAndGet();
            FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            lockChannel.set(channel);
            FileLock held = channel.tryLock();
            assertNotNull(held, "SQLite must observe the Windows file lock");
            fileLock.set(held);
          } else if (step.equals("startup-contention-closed")) {
            contentionClosed.incrementAndGet();
          }
        })) {
          assertTrue(store.recovery().isEmpty());
        }
      });
      assertEquals(5, failure.getErrorCode() & 0xff);
      assertEquals(1, beforeJournalMode.get(), "the held lock consumes the native five-second budget");
      assertEquals(0, contentionClosed.get(), "expired window cannot admit another attempt");
      assertNoCorruptSiblings(temp, path);
    } finally {
      releaseLock(fileLock, lockChannel);
    }
    try (var reopened = new SqliteOperationStore(path, CLOCK, step -> {});
        Connection db = connect(path); Statement statement = db.createStatement()) {
      assertTrue(reopened.recovery().isEmpty());
      assertEquals(5, scalar(statement, "PRAGMA user_version"));
      assertEquals(1, scalar(statement, "SELECT count(*) FROM operations_meta"));
    }
    assertNoCorruptSiblings(temp, path);
  }

  @Test
  void wrappedBusyAtSchemaCommitRollsBackAndRetriesWithoutQuarantine() throws Exception {
    Path path = temp.resolve("operations.db");
    AtomicInteger beforeJournalMode = new AtomicInteger();
    AtomicInteger schemaCommit = new AtomicInteger();
    AtomicInteger contentionClosed = new AtomicInteger();
    AtomicBoolean sawWalSidecar = new AtomicBoolean();
    SQLException busy = sqliteFailure("startup schema is busy", 5);
    OperationStoreException injected = new OperationStoreException(
        OperationStoreException.Code.STORAGE_FAILED, busy);

    try (var store = new SqliteOperationStore(path, CLOCK, step -> {
      if (step.equals("before-journal-mode")) {
        beforeJournalMode.incrementAndGet();
      } else if (step.equals("before-schema-commit") && schemaCommit.incrementAndGet() == 1) {
        sawWalSidecar.set(Files.exists(path.resolveSibling("operations.db-wal"))
            || Files.exists(path.resolveSibling("operations.db-shm")));
        throw injected;
      } else if (step.equals("startup-contention-closed")) {
        contentionClosed.incrementAndGet();
      }
    })) {
      // The first schema transaction must be rolled back before the retry creates the schema.
      assertTrue(store.recovery().isEmpty());
    }

    assertEquals(2, beforeJournalMode.get());
    assertEquals(2, schemaCommit.get());
    assertEquals(1, contentionClosed.get());
    assertTrue(sawWalSidecar.get(), "the failed WAL transaction should leave a sidecar to preserve");
    assertNoCorruptSiblings(temp, path);
    try (Connection db = connect(path); Statement statement = db.createStatement()) {
      assertEquals(5, scalar(statement, "PRAGMA user_version"));
      assertEquals(1, scalar(statement, "SELECT count(*) FROM operations_meta"));
      assertEquals(1, scalar(statement, "SELECT count(*) FROM sqlite_master WHERE name = 'operations'"));
    }
  }

  @Test
  void wrappedNonBusyAtSchemaCommitFailsWithoutRetryOrQuarantine() throws Exception {
    Path path = temp.resolve("operations.db");
    AtomicInteger beforeJournalMode = new AtomicInteger();
    AtomicInteger schemaCommit = new AtomicInteger();
    AtomicInteger contentionClosed = new AtomicInteger();
    OperationStoreException injected = new OperationStoreException(
        OperationStoreException.Code.STORAGE_FAILED, sqliteFailure("permission denied", 14));

    OperationStoreException failure = assertThrows(OperationStoreException.class,
        () -> new SqliteOperationStore(path, CLOCK, step -> {
          if (step.equals("before-journal-mode")) {
            beforeJournalMode.incrementAndGet();
          } else if (step.equals("before-schema-commit")) {
            schemaCommit.incrementAndGet();
            throw injected;
          } else if (step.equals("startup-contention-closed")) {
            contentionClosed.incrementAndGet();
          }
        }));

    assertSame(injected, failure);
    assertEquals(1, beforeJournalMode.get());
    assertEquals(1, schemaCommit.get());
    assertEquals(0, contentionClosed.get());
    assertNoCorruptSiblings(temp, path);
  }

  @Test
  void interruptedBusyRetryFailsWithIOExceptionAndRestoresInterruptFlag() throws Exception {
    Path path = temp.resolve("operations.db");
    AtomicInteger beforeJournalMode = new AtomicInteger();
    AtomicInteger schemaCommit = new AtomicInteger();
    AtomicInteger contentionClosed = new AtomicInteger();
    SQLException busy = sqliteFailure("startup schema is busy", 5);
    OperationStoreException injected = new OperationStoreException(
        OperationStoreException.Code.STORAGE_FAILED, busy);

    try {
      IOException failure = assertThrows(IOException.class,
          () -> new SqliteOperationStore(path, CLOCK, step -> {
            if (step.equals("before-journal-mode")) {
              beforeJournalMode.incrementAndGet();
            } else if (step.equals("before-schema-commit") && schemaCommit.incrementAndGet() == 1) {
              throw injected;
            } else if (step.equals("startup-contention-closed")) {
              contentionClosed.incrementAndGet();
              Thread.currentThread().interrupt();
            }
          }));

      assertTrue(Thread.currentThread().isInterrupted(), "interruption must be restored");
      assertInstanceOf(InterruptedException.class, failure.getCause());
      assertTrue(java.util.Arrays.stream(failure.getSuppressed()).anyMatch(suppressed -> suppressed == injected),
          "the interrupted failure must retain the BUSY attempt as evidence");
      assertEquals(1, beforeJournalMode.get());
      assertEquals(1, schemaCommit.get());
      assertEquals(1, contentionClosed.get());
      assertNoCorruptSiblings(temp, path);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void expiredRetryWindowCannotAdmitAnotherConnectionAttempt() throws Exception {
    Path path = temp.resolve("operations.db");
    AtomicInteger attempts = new AtomicInteger();
    AtomicInteger closed = new AtomicInteger();
    var busy = new OperationStoreException(OperationStoreException.Code.STORAGE_FAILED,
        sqliteFailure("retry deadline witness", 5));
    var failure = assertThrows(OperationStoreException.class, () -> new SqliteOperationStore(path, CLOCK, step -> {
      if (step.equals("before-journal-mode")) attempts.incrementAndGet();
      if (step.equals("before-schema-commit")) throw busy;
      if (step.equals("startup-contention-closed")) {
        closed.incrementAndGet();
        try {
          Thread.sleep(5_100);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException(interrupted);
        }
      }
    }));
    assertSame(busy, failure);
    assertEquals(1, closed.get());
    assertEquals(1, attempts.get(), "time spent after close also consumes the retry window");
    assertNoCorruptSiblings(temp, path);
  }

  private static SQLException sqliteFailure(String message, int code) {
    return new SQLException(message, "SQLITE_ERROR", code);
  }

  private static void releaseLock(AtomicReference<FileLock> fileLock,
      AtomicReference<FileChannel> lockChannel) throws IOException {
    FileLock held = fileLock.getAndSet(null);
    FileChannel channel = lockChannel.getAndSet(null);
    try {
      if (held != null) held.release();
    } finally {
      if (channel != null) channel.close();
    }
  }

  private static void assertNoCorruptSiblings(Path directory, Path path) throws IOException {
    String prefix = path.getFileName() + ".corrupt-";
    try (var siblings = Files.list(directory)) {
      assertFalse(siblings.anyMatch(candidate -> candidate.getFileName().toString().startsWith(prefix)));
    }
  }

  private long scalar(Statement statement, String sql) throws SQLException {
    try (var result = statement.executeQuery(sql)) {
      assertTrue(result.next());
      return result.getLong(1);
    }
  }
}
