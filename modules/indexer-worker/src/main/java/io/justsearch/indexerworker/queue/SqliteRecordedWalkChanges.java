/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteUpdateListener;

/** Queue-owned transient notification projection; the durable walk row remains the source. */
final class SqliteRecordedWalkChanges implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(SqliteRecordedWalkChanges.class);
  private final Connection connection;
  private final SQLiteConnection sqlite;
  private final Set<Long> pending = new LinkedHashSet<>();
  private final Set<String> committed = new LinkedHashSet<>();
  private final List<Listener> listeners = new CopyOnWriteArrayList<>();
  private final SQLiteUpdateListener capture;
  private volatile boolean closed;

  private static final class Listener implements JobQueue.WalkSubscription {
    private final Consumer<String> consumer;
    private volatile boolean active = true;
    private Listener(Consumer<String> consumer) { this.consumer = consumer; }
    @Override public void close() { active = false; }
  }

  SqliteRecordedWalkChanges(Connection connection) throws SQLException {
    this.connection = connection;
    sqlite = connection.unwrap(SQLiteConnection.class);
    capture = (type, database, table, rowId) -> {
      if (!closed && "ingestion_walk_progress".equals(table) && type != SQLiteUpdateListener.Type.DELETE) pending.add(rowId);
    };
    sqlite.addUpdateListener(capture);
  }

  JobQueue.WalkSubscription subscribe(Consumer<String> consumer) {
    if (closed) throw new IllegalStateException("Recorded walk notifications are closed");
    var listener = new Listener(java.util.Objects.requireNonNull(consumer, "consumer"));
    listeners.add(listener);
    return () -> { listener.close(); listeners.remove(listener); };
  }

  /** Only the queue calls this, after JDBC confirms commit, while its lock is held. */
  void commitSucceeded() throws SQLException {
    if (pending.isEmpty()) return;
    try (var query = connection.prepareStatement("SELECT operation_key FROM ingestion_walk_progress WHERE rowid = ?")) {
      for (long rowId : pending) {
        query.setLong(1, rowId);
        try (var row = query.executeQuery()) { if (row.next()) committed.add(row.getString(1)); }
      }
    } finally { pending.clear(); }
  }

  void discardPending() { pending.clear(); }

  /** Actual owner release can make an already committed terminal row sealable without a SQL write. */
  void claimReleased(String key) { if (key != null && !closed) committed.add(key); }

  /** Detach under the owner lock; the returned delivery must run after the outermost unlock. */
  Runnable takeDelivery() {
    if (committed.isEmpty()) return null;
    var keys = List.copyOf(committed);
    committed.clear();
    var targets = List.copyOf(listeners);
    return () -> {
      for (String key : keys) {
        for (Listener listener : targets) {
          if (closed || !listener.active) continue;
          try { listener.consumer.accept(key); }
          catch (RuntimeException failure) {
            log.warn("Recorded walk projection notification failed; durable receipt remains available", failure);
          }
        }
      }
    };
  }

  @Override public void close() {
    closed = true;
    sqlite.removeUpdateListener(capture);
    pending.clear();
    committed.clear();
    for (Listener listener : listeners) listener.close();
    listeners.clear();
  }
}
