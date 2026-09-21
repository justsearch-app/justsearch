/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationPreparedPayload;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.core.context.EngineContext;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Windows WAL locks exercise the operation store's real prepared-accept transaction boundary. */
@EnabledOnOs(OS.WINDOWS)
@Tag("windows")
@Timeout(25)
final class SqliteOperationStoreAcceptanceContentionTest {
  private static final Clock CLOCK = Clock.fixed(
      Instant.parse("2026-09-13T00:00:00Z"), ZoneOffset.UTC);
  private static final OperationDescriptor IDENTITY = OperationDescriptor.invocation(
      OperationKind.NOTE, "core.file-note", "{\"path\":\"C:/prepared-contention\"}", false);
  private static final EngineContext CONTEXT = new EngineContext(
      EngineContext.ClientKind.INTERNAL, "prepared-contention", Optional.empty(), Optional.empty(),
      "SYSTEM", "SYSTEM_INTERNAL", EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);

  @TempDir Path temp;

  @Test
  void sharedShmLockWaitsThenAcceptsAndTransfersPreparationExactlyOnce() throws Exception {
    Path dbPath = temp.resolve("operations.db");
    String key = OperationKeys.generate(CLOCK);
    OperationStore.Preparation prepared = preparation("shared-lock");
    try (var store = open(dbPath); var worker = newWorker()) {
      assertEquals(Optional.of(prepared), store.savePreparation(key, IDENTITY, prepared));
      CountDownLatch started = new CountDownLatch(1);
      Future<OperationStore.Acceptance> acceptance;

      try (var heldShm = lockSidecar(dbPath, "-shm", true)) {
        assertTrue(heldShm.lock().isShared());
        acceptance = submitAccept(worker, store, key, prepared, started, new AtomicLong());
        assertTrue(started.await(2, TimeUnit.SECONDS), "prepared acceptance must start under the held SHM lock");
        assertThrows(TimeoutException.class,
            () -> acceptance.get(250, TimeUnit.MILLISECONDS),
            "acceptance must wait for the live SHM lock rather than fail at deferred write-upgrade");
      }

      var accepted = acceptance.get(8, TimeUnit.SECONDS);
      assertTrue(accepted.created());
      long acceptedId = accepted.record().id();
      assertEquals(Optional.of(prepared), store.acceptedPreparation(acceptedId),
          "the saved prepared payload must transfer with the accepted row");
      assertTrue(store.pendingPreparation(key, IDENTITY).isEmpty());
      assertEquals(1, scalar(dbPath, "SELECT count(*) FROM operations"));
      assertEquals(0, scalar(dbPath, "SELECT count(*) FROM operation_preparations"));

      var retry = store.acceptPrepared(key, IDENTITY, CONTEXT, null, prepared.nonce());
      assertFalse(retry.created(), "same-key retry must retain the first acceptance");
      assertEquals(acceptedId, retry.record().id());
      assertEquals(1, scalar(dbPath, "SELECT count(*) FROM operations"), "same-key retry cannot add a row");
      assertEquals(0, scalar(dbPath, "SELECT count(*) FROM operation_preparations"));
    }
  }

  @Test
  void sharedShmLockExhaustsNativeBusyWaitWithoutPoisoningSameStoreRetry() throws Exception {
    Path dbPath = temp.resolve("operations.db");
    String key = OperationKeys.generate(CLOCK);
    OperationStore.Preparation prepared = preparation("exhausted-shared-lock");
    try (var store = open(dbPath); var worker = newWorker()) {
      assertEquals(Optional.of(prepared), store.savePreparation(key, IDENTITY, prepared));
      CountDownLatch started = new CountDownLatch(1);
      AtomicLong callStartedAt = new AtomicLong();
      Future<OperationStore.Acceptance> acceptance;
      ExecutionException executionFailure;
      long elapsedNanos;
      try (var heldShm = lockSidecar(dbPath, "-shm", true)) {
        assertTrue(heldShm.lock().isShared());
        acceptance = submitAccept(worker, store, key, prepared, started, callStartedAt);
        assertTrue(started.await(2, TimeUnit.SECONDS), "prepared acceptance must start under the held SHM lock");
        executionFailure = assertThrows(ExecutionException.class,
            () -> acceptance.get(8, TimeUnit.SECONDS),
            "a lock held beyond the native busy timeout must refuse acceptance");
        elapsedNanos = System.nanoTime() - callStartedAt.get();
        assertTrue(elapsedNanos >= Duration.ofMillis(4_500).toNanos(),
            "SQLite's native five-second busy wait must be allowed to exhaust instead of immediate upgrade failure");
      }

      OperationStoreException failure = assertInstanceOf(
          OperationStoreException.class, executionFailure.getCause());
      assertEquals(OperationStoreException.Code.STORAGE_FAILED, failure.code());
      assertEquals(0, scalar(dbPath, "SELECT count(*) FROM operations"));
      assertPending(dbPath, key, prepared);
      assertTrue(store.find(key).isEmpty(), "failed begin must not create an accepted operation");
      assertEquals(Optional.of(prepared), store.pendingPreparation(key, IDENTITY));

      var retried = store.acceptPrepared(key, IDENTITY, CONTEXT, null, prepared.nonce());
      assertTrue(retried.created(), "the same live store must remain usable after failed BEGIN");
      assertEquals(Optional.of(prepared), store.acceptedPreparation(retried.record().id()));
      assertTrue(store.pendingPreparation(key, IDENTITY).isEmpty());
      assertEquals(1, scalar(dbPath, "SELECT count(*) FROM operations"));
      assertEquals(0, scalar(dbPath, "SELECT count(*) FROM operation_preparations"));
    }
  }

  @Test
  void exclusiveWalLockRefusesAcceptanceWithoutPublishingOrImplicitCommit() throws Exception {
    Path dbPath = temp.resolve("operations.db");
    Path walPath = sidecar(dbPath, "-wal");
    String key = OperationKeys.generate(CLOCK);
    OperationStore.Preparation prepared = preparation("exclusive-wal-lock");
    try (var store = open(dbPath); var worker = newWorker()) {
      assertEquals(Optional.of(prepared), store.savePreparation(key, IDENTITY, prepared));
      assertTrue(Files.isRegularFile(walPath) && Files.size(walPath) > 0,
          "the accepted preparation must have created the real SQLite WAL sidecar");
      CountDownLatch started = new CountDownLatch(1);
      Future<OperationStore.Acceptance> acceptance;

      ExecutionException executionFailure;
      try (var heldWal = lockSidecar(dbPath, "-wal", false)) {
        assertFalse(heldWal.lock().isShared());
        acceptance = submitAccept(worker, store, key, prepared, started, new AtomicLong());
        assertTrue(started.await(2, TimeUnit.SECONDS), "prepared acceptance must start under the held WAL lock");
        executionFailure = assertThrows(ExecutionException.class,
            () -> acceptance.get(8, TimeUnit.SECONDS),
            "WAL write failure must refuse prepared acceptance rather than report success");
      }

      OperationStoreException failure = assertInstanceOf(
          OperationStoreException.class, executionFailure.getCause());
      assertEquals(OperationStoreException.Code.STORAGE_FAILED, failure.code());
      SQLException sqliteFailure = assertInstanceOf(SQLException.class, failure.getCause());
      assertEquals(10, sqliteFailure.getErrorCode() & 0xff,
          "WAL refusal must retain SQLite's primary IOERR code");
      assertTrue(sqliteFailure.getMessage().contains("SQLITE_IOERR_WRITE"),
          "the native extended write failure must remain diagnosable");
      assertTrue(java.util.Arrays.stream(sqliteFailure.getSuppressed())
          .anyMatch(cleanup -> cleanup.getMessage() != null
              && cleanup.getMessage().contains("no transaction is active")),
          "failed rollback must remain attached to the primary WAL failure");
      assertEquals(0, scalar(dbPath, "SELECT count(*) FROM operations"),
          "a refused WAL commit must not publish an accepted row");
      assertPending(dbPath, key, prepared);
      OperationStoreException unavailable = assertThrows(OperationStoreException.class,
          () -> store.find(key), "a store with an uncertain transaction must not be reused");
      assertEquals(OperationStoreException.Code.STORAGE_FAILED, unavailable.code());
    }
  }

  private static SqliteOperationStore open(Path path) throws IOException, SQLException {
    return new SqliteOperationStore(path, CLOCK, ignored -> {});
  }

  private static OperationStore.Preparation preparation(String target) {
    return new OperationStore.Preparation(UUID.randomUUID(),
        new OperationPreparedPayload(false, "{\"target\":\"" + target + "\"}"));
  }

  private static ExecutorService newWorker() {
    return Executors.newSingleThreadExecutor(task -> {
      Thread thread = new Thread(task, "operation-store-acceptance-contention");
      thread.setDaemon(true);
      return thread;
    });
  }

  private static Future<OperationStore.Acceptance> submitAccept(ExecutorService worker,
      SqliteOperationStore store, String key, OperationStore.Preparation prepared,
      CountDownLatch started, AtomicLong callStartedAt) {
    return worker.submit(() -> {
      callStartedAt.set(System.nanoTime());
      started.countDown();
      return store.acceptPrepared(key, IDENTITY, CONTEXT, null, prepared.nonce());
    });
  }

  private static HeldLock lockSidecar(Path dbPath, String suffix, boolean shared) throws IOException {
    Path path = sidecar(dbPath, suffix);
    assertTrue(Files.isRegularFile(path), "SQLite must create the real sidecar before locking it: " + path);
    FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
    try {
      FileLock lock = channel.tryLock(0, Long.MAX_VALUE, shared);
      assertNotNull(lock, "the test must own the requested sidecar lock");
      assertTrue(lock.isValid());
      return new HeldLock(channel, lock);
    } catch (IOException | RuntimeException | Error failure) {
      channel.close();
      throw failure;
    }
  }

  private static Path sidecar(Path dbPath, String suffix) {
    return dbPath.resolveSibling(dbPath.getFileName() + suffix);
  }

  private static long scalar(Path dbPath, String sql) throws SQLException {
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        var statement = db.createStatement(); var row = statement.executeQuery(sql)) {
      assertTrue(row.next());
      return row.getLong(1);
    }
  }

  private static void assertPending(Path dbPath, String key, OperationStore.Preparation prepared)
      throws SQLException {
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        var query = db.prepareStatement("SELECT nonce, sealed, payload FROM operation_preparations "
            + "WHERE operation_key = ?")) {
      query.setString(1, key);
      try (var row = query.executeQuery()) {
        assertTrue(row.next(), "the saved preparation must survive refused acceptance");
        assertEquals(prepared.nonce().toString(), row.getString("nonce"));
        assertEquals(prepared.payload().sealed() ? 1 : 0, row.getInt("sealed"));
        assertEquals(prepared.payload().value(), row.getString("payload"));
        assertFalse(row.next(), "one key must retain exactly one preparation");
      }
    }
  }

  private record HeldLock(FileChannel channel, FileLock lock) implements AutoCloseable {
    @Override
    public void close() throws IOException {
      try {
        lock.release();
      } finally {
        channel.close();
      }
    }
  }
}
