/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.ToIntFunction;

/** Disk-backed path ordering for maintenance sync; neither paths nor sort runs accumulate in heap. */
final class ReconciliationSpool implements AutoCloseable {
  private final Path file;
  private final Connection connection;
  private final PreparedStatement insert;
  private int pending;

  ReconciliationSpool() throws IOException {
    file = Files.createTempFile("justsearch-reconciliation-", ".db");
    Connection opened = null;
    try {
      opened = DriverManager.getConnection("jdbc:sqlite:" + file);
      try (var statement = opened.createStatement()) {
        statement.execute("PRAGMA journal_mode=OFF");
        statement.execute("PRAGMA synchronous=OFF");
        statement.execute("PRAGMA cache_size=-2048");
        statement.execute("PRAGMA temp_store=FILE");
        statement.execute("PRAGMA mmap_size=0");
        statement.execute("CREATE TABLE paths (sort_key TEXT, path TEXT, size INTEGER)");
        // Maintain order on disk as paths arrive, avoiding a monolithic ORDER BY sort at drain.
        statement.execute("CREATE INDEX path_order ON paths(sort_key, path)");
      }
      opened.setAutoCommit(false);
      insert = opened.prepareStatement("INSERT INTO paths VALUES (?, ?, ?)");
      connection = opened;
    } catch (SQLException failure) {
      if (opened != null) {
        try { opened.close(); }
        catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
      }
      try { Files.deleteIfExists(file); }
      catch (IOException cleanup) { failure.addSuppressed(cleanup); }
      throw new IOException("Cannot open reconciliation spool", failure);
    }
  }

  void add(Path path, long size) throws IOException {
    try {
      String absolute = path.toAbsolutePath().toString();
      insert.setString(1, PathNormalizer.normalizePath(absolute));
      insert.setString(2, path.toString());
      insert.setLong(3, size);
      insert.executeUpdate();
      if (++pending == 2_000) {
        connection.commit();
        pending = 0;
      }
    } catch (SQLException failure) {
      throw new IOException("Cannot write reconciliation spool", failure);
    }
  }

  int drain(int batchSize, JobQueue.EnqueueProvenance provenance,
      BooleanSupplier cancelled, ToIntFunction<List<JobQueue.EnqueueEntry>> enqueue)
      throws IOException {
    int added = 0;
    try {
      if (cancelled.getAsBoolean()) return added;
      connection.commit();
      try (var statement = connection.createStatement();
          var rows = statement.executeQuery(
              "SELECT path, size FROM paths INDEXED BY path_order ORDER BY sort_key, path")) {
        var batch = new ArrayList<JobQueue.EnqueueEntry>(batchSize);
        while (!cancelled.getAsBoolean() && rows.next()) {
          batch.add(new JobQueue.EnqueueEntry(Path.of(rows.getString(1)), rows.getLong(2), provenance));
          if (batch.size() == batchSize) {
            if (cancelled.getAsBoolean()) return added;
            added += enqueue.applyAsInt(batch);
            batch.clear();
          }
        }
        if (!batch.isEmpty() && !cancelled.getAsBoolean()) added += enqueue.applyAsInt(batch);
      }
      return added;
    } catch (SQLException failure) {
      throw new IOException("Cannot read reconciliation spool", failure);
    }
  }

  @Override
  public void close() throws IOException {
    IOException failure = null;
    try { insert.close(); }
    catch (SQLException cleanup) { failure = new IOException(cleanup); }
    try { connection.close(); }
    catch (SQLException cleanup) {
      if (failure == null) failure = new IOException(cleanup);
      else failure.addSuppressed(cleanup);
    }
    try { Files.deleteIfExists(file); }
    catch (IOException cleanup) {
      if (failure == null) failure = cleanup;
      else failure.addSuppressed(cleanup);
    }
    if (failure != null) throw failure;
  }
}
