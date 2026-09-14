/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/** SQL projection helpers borrowing the queue's connection, lock and transaction; no independent owner. */
final class SqliteIngestionWalkOps {
  private SqliteIngestionWalkOps() {}

  static Optional<JobQueue.WalkProgress> find(Connection connection, String key) throws SQLException {
    try (var query = connection.prepareStatement("SELECT * FROM ingestion_walk_progress WHERE operation_key = ?")) {
      query.setString(1, key);
      try (var row = query.executeQuery()) {
        return row.next() ? Optional.of(read(row)) : Optional.empty();
      }
    }
  }

  static JobQueue.WalkProgress begin(Connection connection, String key, String planHash,
      boolean createIfMissing) throws SQLException {
    // Validate the value before writing; acceptance/key authority remains the outer operations owner.
    var initial = new JobQueue.WalkProgress(key, planHash, 1, null, null, 0, 0, 1, null, null, 0);
    var existing = find(connection, key);
    if (existing.isEmpty()) {
      if (!createIfMissing) throw new IllegalStateException("Recorded walk state is unavailable");
      try (var insert = connection.prepareStatement("INSERT INTO ingestion_walk_progress "
          + "(operation_key, plan_hash, enumeration_epoch) VALUES (?, ?, 1)")) {
        insert.setString(1, key); insert.setString(2, planHash);
        if (insert.executeUpdate() != 1) throw new SQLException("Walk projection was not created");
      }
      return initial;
    }
    var progress = existing.get();
    if (!progress.planHash().equals(planHash)) throw new IllegalArgumentException("Recorded walk plan changed");
    if (progress.enumerationClosedAt() != null) return progress;
    requireAdvance(progress);
    if (progress.enumerationEpoch() == Long.MAX_VALUE) throw new IllegalStateException("Walk epoch exhausted");
    try (var update = connection.prepareStatement("UPDATE ingestion_walk_progress SET "
        + "enumeration_epoch = enumeration_epoch + 1, revision = revision + 1 WHERE operation_key = ?")) {
      update.setString(1, key);
      if (update.executeUpdate() != 1) throw new SQLException("Walk projection disappeared");
    }
    return find(connection, key).orElseThrow(() -> new SQLException("Walk projection disappeared"));
  }

  static JobQueue.WalkProgress closeEnumeration(Connection connection, String key, long epoch,
      JobQueue.WalkEnumerationOutcome outcome, long now) throws SQLException {
    Objects.requireNonNull(outcome, "outcome");
    var progress = find(connection, key).orElseThrow(() -> new IllegalStateException("Recorded walk state is unavailable"));
    if (progress.enumerationEpoch() != epoch) throw new IllegalStateException("Stale recorded walk enumeration");
    if (progress.enumerationClosedAt() != null) {
      if (progress.enumerationOutcome() != outcome) throw new IllegalStateException("Recorded enumeration is already closed");
      return progress;
    }
    requireAdvance(progress);
    try (var update = connection.prepareStatement("UPDATE ingestion_walk_progress SET "
        + "enumeration_closed_at = ?, enumeration_outcome = ?, revision = revision + 1 WHERE operation_key = ?")) {
      update.setLong(1, now); update.setString(2, outcome.name()); update.setString(3, key);
      if (update.executeUpdate() != 1) throw new SQLException("Walk projection disappeared");
    }
    return find(connection, key).orElseThrow(() -> new SQLException("Walk projection disappeared"));
  }

  static boolean acknowledge(Connection connection, String key, long revision) throws SQLException {
    var existing = find(connection, key);
    if (existing.isEmpty() || existing.get().sealedAt() == null || existing.get().revision() != revision) return false;
    try (var update = connection.prepareStatement("UPDATE ingestion_walk_progress SET acknowledged_revision = ? "
        + "WHERE operation_key = ? AND sealed_at IS NOT NULL AND revision = ?")) {
      update.setLong(1, revision); update.setString(2, key); update.setLong(3, revision);
      return update.executeUpdate() == 1;
    }
  }

  private static void requireAdvance(JobQueue.WalkProgress progress) {
    if (progress.revision() == Long.MAX_VALUE) throw new IllegalStateException("Walk revision exhausted");
  }

  private static JobQueue.WalkProgress read(ResultSet row) throws SQLException {
    String outcome = row.getString("enumeration_outcome");
    return new JobQueue.WalkProgress(row.getString("operation_key"), row.getString("plan_hash"),
        row.getLong("enumeration_epoch"), nullableLong(row, "enumeration_closed_at"),
        outcome == null ? null : JobQueue.WalkEnumerationOutcome.valueOf(outcome),
        row.getLong("completed_units"), row.getLong("failed_units"), row.getLong("revision"),
        nullableLong(row, "sealed_at"), row.getString("receipt_json"), row.getLong("acknowledged_revision"));
  }

  private static Long nullableLong(ResultSet row, String column) throws SQLException {
    long value = row.getLong(column);
    return row.wasNull() ? null : value;
  }
}
