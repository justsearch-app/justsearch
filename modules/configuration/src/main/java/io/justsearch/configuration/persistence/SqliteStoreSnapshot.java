/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Private startup inspection of a quiescent SQLite store, including committed WAL frames.
 * SQLite mode=ro may still change the original SHM, so compatibility probes use copied artifacts.
 * The caller must already exclude writers; this is not an online database backup API.
 */
public final class SqliteStoreSnapshot implements AutoCloseable {
  private final Path directory;
  private final Connection connection;

  private SqliteStoreSnapshot(Path directory, Connection connection) {
    this.directory = directory;
    this.connection = connection;
  }

  public static SqliteStoreSnapshot open(Path source) throws IOException, SQLException {
    Path directory = Files.createTempDirectory("justsearch-sqlite-inspection-");
    try {
      Path copy = directory.resolve("store.db");
      Files.copy(source, copy);
      Path wal = source.resolveSibling(source.getFileName() + "-wal");
      if (Files.exists(wal)) Files.copy(wal, directory.resolve("store.db-wal"));
      // SQLite reconstructs SHM from this private WAL; copying a live SHM is unnecessary.
      return new SqliteStoreSnapshot(directory,
          DriverManager.getConnection("jdbc:sqlite:" + copy.toUri() + "?mode=ro"));
    } catch (IOException | SQLException | RuntimeException | Error failure) {
      try { removeCopies(directory); } catch (IOException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  public Connection connection() { return connection; }

  @Override
  public void close() throws SQLException, IOException {
    try {
      connection.close();
    } finally {
      removeCopies(directory);
    }
  }

  private static void removeCopies(Path directory) throws IOException {
    // Only our fixed private artifacts, never a recursive deletion of a caller's path.
    for (String name : java.util.List.of("store.db-shm", "store.db-wal", "store.db")) {
      Files.deleteIfExists(directory.resolve(name));
    }
    Files.delete(directory);
  }
}
