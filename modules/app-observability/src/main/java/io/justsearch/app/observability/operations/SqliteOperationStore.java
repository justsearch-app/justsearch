/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One connection and one lock for the shared operations.db, including its final close. */
public final class SqliteOperationStore implements OperationStore {
  private static final Logger LOG = LoggerFactory.getLogger(SqliteOperationStore.class);
  private static final long FUTURE_SKEW_MS = Duration.ofMinutes(5).toMillis();
  private final ReentrantLock lock = new ReentrantLock();
  private final Path path;
  private final Clock clock;
  private final OpenStepHook hook;
  private Connection connection;
  private Recovery recovery;

  @Override
  public java.util.Optional<Recovery> recovery() { return java.util.Optional.ofNullable(recovery); }

  public SqliteOperationStore(Path path) throws IOException, SQLException {
    this(path, Clock.systemUTC(), step -> {});
  }

  @FunctionalInterface
  interface OpenStepHook {
    void afterStep(String step) throws IOException;
  }

  SqliteOperationStore(Path path, Clock clock, OpenStepHook hook) throws IOException, SQLException {
    this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    this.clock = Objects.requireNonNull(clock, "clock");
    this.hook = Objects.requireNonNull(hook, "hook");
    Files.createDirectories(this.path.getParent());
    try {
      resumePendingPreservation();
      if (Files.exists(this.path) && Files.size(this.path) > 0) {
        try {
          inspectExisting();
        } catch (SQLException failure) {
          if (!isCorruption(failure)) throw failure;
          preserveCorruptStore();
        }
      } else if (Files.exists(sidecar("-wal")) || Files.exists(sidecar("-shm"))) {
        // A kill may have moved the main file but left its WAL. It must never reach a new file.
        preserveCorruptStore();
      }
      Recovery latestRecovery = latestRecovery();
      long recoveryFloor = latestRecovery == null ? 0 : latestRecovery.historySinceMillis();
      connection = DriverManager.getConnection("jdbc:sqlite:" + this.path);
      try (Statement statement = connection.createStatement()) {
        statement.execute("PRAGMA busy_timeout = 5000");
        statement.execute("PRAGMA journal_mode = WAL");
        statement.execute("PRAGMA synchronous = NORMAL");
      }
      boolean recovered = initializeSchema(recoveryFloor);
      if (recovered) recovery = latestRecovery;
    } catch (IOException | SQLException | RuntimeException | Error failure) {
      if (connection != null) {
        try { connection.close(); } catch (SQLException closeFailure) {
          failure.addSuppressed(closeFailure);
        }
        connection = null;
      }
      throw failure;
    }
  }

  private void inspectExisting() throws SQLException, IOException {
    // Read-only SQLite sees user_version in WAL as well as the main header. Compatibility is
    // checked before journal-mode setup or writable DDL can modify a future database.
    try (var snapshot = io.justsearch.configuration.persistence.SqliteStoreSnapshot.open(path);
        Statement statement = snapshot.connection().createStatement()) {
      refuseFutureVersion(schemaVersion(snapshot.connection()));
      try (ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
        boolean sawResult = false;
        while (result.next()) {
          sawResult = true;
          if (!"ok".equalsIgnoreCase(result.getString(1))) {
            throw new SQLException("operations.db integrity check failed", "SQLITE_CORRUPT", 11);
          }
        }
        if (!sawResult) throw new SQLException("operations.db integrity check returned no result");
      }
    }
  }

  private static boolean isCorruption(SQLException failure) {
    // SQLite's primary CORRUPT and NOTADB codes; never quarantine access, locking or disk-full errors.
    int primaryCode = failure.getErrorCode() & 0xff;
    return primaryCode == 11 || primaryCode == 26;
  }

  private static int schemaVersion(Connection database) throws SQLException {
    try (Statement statement = database.createStatement();
        ResultSet result = statement.executeQuery("PRAGMA user_version")) {
      if (!result.next()) throw new SQLException("operations.db has no schema version result");
      return result.getInt(1);
    }
  }

  private static void refuseFutureVersion(int version) {
    if (version > OperationSchema.VERSION) {
      throw new UnsupportedStoreVersionException("operations-db", version, OperationSchema.VERSION);
    }
  }

  private boolean initializeSchema(long recoveryFloor) throws SQLException, IOException {
    int version = schemaVersion(connection);
    refuseFutureVersion(version);
    long previousFloor = 0;
    if (version != 0) {
      try (Statement statement = connection.createStatement();
          ResultSet result = statement.executeQuery("SELECT history_since_ms FROM operations_meta WHERE singleton = 1")) {
        if (!result.next()) throw new SQLException("operations.db metadata is missing");
        previousFloor = result.getLong(1);
      }
    }
    connection.setAutoCommit(false);
    boolean transactionEnded = false;
    try (Statement statement = connection.createStatement()) {
      if (version == 0) {
        OperationSchema.createTables(statement);
        try (var insert = connection.prepareStatement(
            "INSERT INTO operations_meta(singleton, history_since_ms, created_at_ms) VALUES (1, ?, ?)")) {
          insert.setLong(1, recoveryFloor);
          insert.setLong(2, clock.millis());
          insert.executeUpdate();
        }
        statement.execute("PRAGMA user_version = " + OperationSchema.VERSION);
      } else {
        try (var update = connection.prepareStatement(
            "UPDATE operations_meta SET history_since_ms = MAX(history_since_ms, ?) WHERE singleton = 1")) {
          update.setLong(1, recoveryFloor);
          if (update.executeUpdate() != 1) throw new SQLException("operations.db metadata is missing");
        }
      }
      hook.afterStep("before-schema-commit");
      connection.commit();
      transactionEnded = true;
      return recoveryFloor > previousFloor;
    } catch (SQLException | IOException | RuntimeException | Error failure) {
      try { connection.rollback(); transactionEnded = true; } catch (SQLException rollbackFailure) {
        failure.addSuppressed(rollbackFailure);
      }
      throw failure;
    } finally {
      if (transactionEnded) connection.setAutoCommit(true);
      else connection.close();
    }
  }

  private Path sidecar(String suffix) {
    return path.resolveSibling(path.getFileName() + suffix);
  }

  private void preserveCorruptStore() throws IOException {
    Path pending = path.resolveSibling(path.getFileName() + ".corrupt-"
        + clock.millis() + "-" + UUID.randomUUID() + ".pending");
    Files.createDirectory(pending);
    hook.afterStep("quarantine-directory");
    finishPreservation(pending);
  }

  private void resumePendingPreservation() throws IOException {
    Path pending = null;
    Pattern name = Pattern.compile(Pattern.quote(path.getFileName().toString())
        + "\\.corrupt-\\d+-[0-9a-f-]{36}\\.pending");
    try (var siblings = Files.newDirectoryStream(path.getParent(), path.getFileName() + ".corrupt-*.pending")) {
      for (Path candidate : siblings) {
        if (!Files.isDirectory(candidate) || !name.matcher(candidate.getFileName().toString()).matches()) {
          throw new IOException("Invalid pending operations preservation: " + candidate);
        }
        if (pending != null) throw new IOException("Multiple pending operations preservations");
        pending = candidate;
      }
    }
    if (pending != null) finishPreservation(pending);
  }

  private void finishPreservation(Path pending) throws IOException {
    for (Path artifact : List.of(path, sidecar("-wal"), sidecar("-shm"))) {
      if (Files.exists(artifact)) {
        Path destination = pending.resolve(artifact.getFileName());
        if (Files.exists(destination)) throw new IOException("Conflicting operations preservation: " + destination);
        movePreserved(artifact, destination);
      }
      hook.afterStep("quarantine-" + artifact.getFileName());
    }
    String name = pending.getFileName().toString();
    Path preserved = pending.resolveSibling(name.substring(0, name.length() - ".pending".length()));
    movePreserved(pending, preserved);
    hook.afterStep("quarantine-finalized");
    LOG.warn("Preserved corrupt operations database at {}; outcome history has a recovery fence", preserved);
  }

  private static void movePreserved(Path source, Path destination) throws IOException {
    try {
      Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(source, destination);
    }
  }

  private Recovery latestRecovery() throws IOException {
    Pattern pattern = Pattern.compile(Pattern.quote(path.getFileName().toString())
        + "\\.corrupt-(\\d+)-[0-9a-f-]{36}");
    Recovery latest = null;
    try (var siblings = Files.newDirectoryStream(path.getParent(), path.getFileName() + ".corrupt-*")) {
      for (Path sibling : siblings) {
        var match = pattern.matcher(sibling.getFileName().toString());
        if (Files.isDirectory(sibling) && match.matches()) {
          try {
            long floor = Math.addExact(Long.parseLong(match.group(1)), FUTURE_SKEW_MS + 1);
            if (latest == null || floor > latest.historySinceMillis()) latest = new Recovery(sibling, floor);
          } catch (NumberFormatException | ArithmeticException invalid) {
            throw new IOException("Invalid operations recovery timestamp: " + sibling, invalid);
          }
        }
      }
    }
    return latest;
  }

  @Override
  public void close() throws IOException {
    lock.lock();
    try {
      if (connection == null) return;
      try {
        try (Statement statement = connection.createStatement();
            ResultSet result = statement.executeQuery("PRAGMA wal_checkpoint(FULL)")) {
          if (!result.next() || result.getInt(1) != 0) {
            LOG.warn("Operations WAL remains replayable after close; a reader held its checkpoint");
          }
        }
      } catch (SQLException checkpointFailure) {
        LOG.warn("Operations WAL checkpoint failed; retaining WAL for replay", checkpointFailure);
      }
      try {
        connection.close();
        connection = null;
      } catch (SQLException failure) {
        throw new IOException("Could not close operations database", failure);
      }
    } finally {
      lock.unlock();
    }
  }
}
