/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.disambiguation;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite-backed entity cluster persistence.
 *
 * <p>Stores the mapping from raw entity forms to cluster IDs and canonical forms.
 * Separate database file ({@code entity-clusters.db}), own connection and lock.
 *
 * <p>Thread-safe: all operations are serialized via {@link ReentrantLock}.
 */
public final class EntityClusterStore implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(EntityClusterStore.class);
  private static final int BUSY_TIMEOUT_MS = 5000;
  public static final int CURRENT_SCHEMA_VERSION = 1;

  private final Path dbPath;
  private final ConnectionFactory connectionFactory;
  private final ReentrantLock lock = new ReentrantLock();
  private Connection connection;
  private boolean initialized;

  @FunctionalInterface
  interface ConnectionFactory {
    Connection open(String jdbcUrl) throws SQLException;
  }

  public EntityClusterStore(Path dbPath) {
    this(dbPath, DriverManager::getConnection);
  }

  EntityClusterStore(Path dbPath, ConnectionFactory connectionFactory) {
    this.dbPath = dbPath;
    this.connectionFactory = connectionFactory;
  }

  /** Opens the database connection and initializes the schema. */
  public void open() throws SQLException, IOException {
    lock.lock();
    try {
      if (connection != null) {
        throw new SQLException("EntityClusterStore already owns a connection");
      }
      Files.createDirectories(dbPath.getParent());
      String jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
      connection = connectionFactory.open(jdbcUrl);
      try {
        try (Statement stmt = connection.createStatement()) {
          stmt.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS);
          stmt.execute("PRAGMA journal_mode = WAL");
          stmt.execute("PRAGMA synchronous = NORMAL");
        }

        initSchema();
        log.info("EntityClusterStore opened: {}", dbPath);
        initialized = true;
      } catch (SQLException | RuntimeException | Error failure) {
        try {
          connection.close();
          connection = null;
        } catch (SQLException | RuntimeException | Error closeFailure) {
          if (closeFailure != failure) failure.addSuppressed(closeFailure);
        }
        throw failure;
      }
    } finally {
      lock.unlock();
    }
  }

  private void initSchema() throws SQLException {
    int version;
    try (Statement stmt = connection.createStatement();
        ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
      version = rs.next() ? rs.getInt(1) : 0;
    }
    if (version > CURRENT_SCHEMA_VERSION) {
      throw new SQLException(
          "entity-clusters.db schema version "
              + version
              + " is newer than supported version "
              + CURRENT_SCHEMA_VERSION);
    }

    boolean previousAutoCommit = connection.getAutoCommit();
    connection.setAutoCommit(false);
    try (Statement stmt = connection.createStatement()) {
      stmt.execute(EntityClusterSchema.CREATE_CLUSTERS_TABLE);
      stmt.execute(EntityClusterSchema.CREATE_CLUSTER_ID_INDEX);
      stmt.execute(EntityClusterSchema.CREATE_CANONICAL_INDEX);
      stmt.execute(EntityClusterSchema.CREATE_OVERRIDES_TABLE);
      stmt.execute("PRAGMA user_version = " + CURRENT_SCHEMA_VERSION);
      connection.commit();
    } catch (SQLException failure) {
      connection.rollback();
      throw failure;
    } finally {
      connection.setAutoCommit(previousAutoCommit);
    }
  }

  /** Loads all cluster entries for snapshot building. */
  public List<ClusterEntry> loadAll() throws SQLException {
    lock.lock();
    try {
      ensureOpen();
      List<ClusterEntry> entries = new ArrayList<>();
      try (Statement stmt = connection.createStatement();
          ResultSet rs =
              stmt.executeQuery(
                  "SELECT raw_form, entity_type, cluster_id, canonical_form,"
                      + " confidence, created_at_ms, updated_at_ms FROM entity_clusters")) {
        while (rs.next()) {
          entries.add(
              new ClusterEntry(
                  rs.getString(1),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getString(4),
                  rs.getDouble(5),
                  rs.getLong(6),
                  rs.getLong(7)));
        }
      }
      return entries;
    } finally {
      lock.unlock();
    }
  }

  /** Upserts a raw_form → cluster mapping. */
  public void upsert(
      String rawForm,
      String entityType,
      String clusterId,
      String canonicalForm,
      double confidence)
      throws SQLException {
    lock.lock();
    try {
      ensureOpen();
      long now = System.currentTimeMillis();
      try (PreparedStatement ps =
          connection.prepareStatement(
              "INSERT INTO entity_clusters"
                  + " (raw_form, entity_type, cluster_id, canonical_form, confidence,"
                  + " created_at_ms, updated_at_ms)"
                  + " VALUES (?, ?, ?, ?, ?, ?, ?)"
                  + " ON CONFLICT(raw_form, entity_type) DO UPDATE SET"
                  + " cluster_id = excluded.cluster_id,"
                  + " canonical_form = excluded.canonical_form,"
                  + " confidence = excluded.confidence,"
                  + " updated_at_ms = excluded.updated_at_ms")) {
        ps.setString(1, rawForm);
        ps.setString(2, entityType);
        ps.setString(3, clusterId);
        ps.setString(4, canonicalForm);
        ps.setDouble(5, confidence);
        ps.setLong(6, now);
        ps.setLong(7, now);
        ps.executeUpdate();
      }
    } finally {
      lock.unlock();
    }
  }

  /** Deletes all cluster entries (for batch re-cluster). */
  public void deleteAll() throws SQLException {
    lock.lock();
    try {
      ensureOpen();
      try (Statement stmt = connection.createStatement()) {
        stmt.executeUpdate("DELETE FROM entity_clusters");
      }
    } finally {
      lock.unlock();
    }
  }

  /** Deletes entries by cluster_id. */
  public void deleteByCluster(String clusterId) throws SQLException {
    lock.lock();
    try {
      ensureOpen();
      try (PreparedStatement ps =
          connection.prepareStatement("DELETE FROM entity_clusters WHERE cluster_id = ?")) {
        ps.setString(1, clusterId);
        ps.executeUpdate();
      }
    } finally {
      lock.unlock();
    }
  }

  /** Loads entries for a specific cluster. */
  public List<ClusterEntry> loadByCluster(String clusterId) throws SQLException {
    lock.lock();
    try {
      ensureOpen();
      List<ClusterEntry> entries = new ArrayList<>();
      try (PreparedStatement ps =
          connection.prepareStatement(
              "SELECT raw_form, entity_type, cluster_id, canonical_form,"
                  + " confidence, created_at_ms, updated_at_ms"
                  + " FROM entity_clusters WHERE cluster_id = ?")) {
        ps.setString(1, clusterId);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            entries.add(
                new ClusterEntry(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getDouble(5),
                    rs.getLong(6),
                    rs.getLong(7)));
          }
        }
      }
      return entries;
    } finally {
      lock.unlock();
    }
  }

  /** Counts distinct clusters per entity type. */
  public Map<String, Long> countClusters() throws SQLException {
    lock.lock();
    try {
      ensureOpen();
      Map<String, Long> counts = new HashMap<>();
      try (Statement stmt = connection.createStatement();
          ResultSet rs =
              stmt.executeQuery(
                  "SELECT entity_type, COUNT(DISTINCT cluster_id)"
                      + " FROM entity_clusters GROUP BY entity_type")) {
        while (rs.next()) {
          counts.put(rs.getString(1), rs.getLong(2));
        }
      }
      return counts;
    } finally {
      lock.unlock();
    }
  }

  private void ensureOpen() throws SQLException {
    if (!initialized || connection == null || connection.isClosed()) {
      throw new SQLException("EntityClusterStore is not open");
    }
  }

  @Override
  public void close() throws IOException {
    lock.lock();
    try {
      initialized = false;
      if (connection != null) {
        connection.close();
        connection = null;
        log.info("EntityClusterStore closed: {}", dbPath);
      }
    } catch (SQLException e) {
      throw new IOException("Failed to close EntityClusterStore", e);
    } finally {
      lock.unlock();
    }
  }
}
