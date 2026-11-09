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

  /** Require the current owner-defined shape without allowing CREATE/repair on the source. */
  public void requireCurrentStructure(int version, Connection expected) throws SQLException {
    try (var query = connection.createStatement(); var row = query.executeQuery("PRAGMA user_version")) {
      if (!row.next() || row.getInt(1) != version) {
        throw new SQLException("Frozen eval requires current SQLite schema version " + version);
      }
    }
    try (var query = connection.createStatement(); var rows = query.executeQuery("PRAGMA quick_check")) {
      if (!rows.next() || !"ok".equals(rows.getString(1)) || rows.next()) {
        throw new SQLException("Frozen eval SQLite integrity check failed");
      }
    }
    try (var query = expected.createStatement(); var rows = query.executeQuery(
        "SELECT type,name,tbl_name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")) {
      while (rows.next()) {
        String type = rows.getString(1), name = rows.getString(2);
        try (var actual = connection.prepareStatement(
            "SELECT tbl_name FROM sqlite_master WHERE type=? AND name=?")) {
          actual.setString(1, type); actual.setString(2, name);
          try (var row = actual.executeQuery()) {
            if (!row.next() || !rows.getString(3).equals(row.getString(1))) {
              throw new SQLException("Frozen eval requires SQLite " + type + " " + name);
            }
          }
        }
        if ("table".equals(type)) {
          if (!columns(expected, name).equals(columns(connection, name))) {
            throw new SQLException("Frozen eval requires current SQLite columns for " + name);
          }
        }
      }
    }
  }

  private static java.util.Map<String, String> columns(Connection database, String table)
      throws SQLException {
    var columns = new java.util.TreeMap<String, String>();
    try (var query = database.createStatement(); var rows = query.executeQuery(
        "PRAGMA table_info(\"" + table.replace("\"", "\"\"") + "\")")) {
      while (rows.next()) {
        String value = rows.getString("dflt_value");
        String name = rows.getString("name");
        // The base jobs owner and its v10 ALTER use these two equivalent NULL spellings.
        if ("jobs".equals(table) && "first_failed_at".equals(name)
            && (value == null || "NULL".equalsIgnoreCase(value.trim()))) value = "NULL";
        columns.put(name, rows.getString("type")
            + "/" + rows.getInt("notnull") + "/" + rows.getInt("pk") + "/" + value);
      }
    }
    return columns;
  }

  @Override
  public void close() throws SQLException, IOException {
    Throwable failure = null;
    try {
      connection.close();
    } catch (SQLException | RuntimeException | Error closeFailure) {
      failure = closeFailure;
      throw closeFailure;
    } finally {
      try { removeCopies(directory); } catch (IOException | RuntimeException | Error cleanupFailure) {
        if (failure == null) throw cleanupFailure;
        if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
      }
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
