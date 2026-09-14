/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/** SQL projection helpers borrowing the queue's connection, lock and transaction; no independent owner. */
final class SqliteIngestionWalkOps {
  private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

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

  static int enqueueRecorded(Connection connection, String key, long epoch,
      java.util.List<JobQueue.EnqueueEntry> entries, String collection, long now) throws SQLException {
    Objects.requireNonNull(entries, "entries");
    var progress = find(connection, key)
        .orElseThrow(() -> new IllegalStateException("Recorded walk state is unavailable"));
    if (progress.enumerationEpoch() != epoch || progress.enumerationClosedAt() != null
        || progress.sealedAt() != null) {
      throw new IllegalStateException("Recorded enumeration is stale or closed");
    }
    java.util.Set<String> seen = new java.util.HashSet<>();
    boolean changed = false;
    String declaredCollection = collection == null || collection.isBlank() ? null : collection;
    for (var entry : entries) {
      Objects.requireNonNull(entry, "entry");
      String path = io.justsearch.indexerworker.util.PathNormalizer.normalizePath(
          Objects.requireNonNull(entry.path(), "entry.path").toAbsolutePath().toString());
      if (!seen.add(path)) continue;
      String priorKey = null;
      String priorCollection = null;
      Long priorEpoch = null;
      try (var query = connection.prepareStatement("SELECT scan_id, collection, walk_seen_epoch FROM jobs WHERE path = ?")) {
        query.setString(1, path);
        try (var row = query.executeQuery()) {
          if (row.next()) {
            priorKey = row.getString(1); priorCollection = row.getString(2);
            long value = row.getLong(3); priorEpoch = row.wasNull() ? null : value;
          }
        }
      }
      var priorProgress = priorEpoch == null ? Optional.<JobQueue.WalkProgress>empty()
          : find(connection, priorKey);
      if (priorEpoch != null && priorProgress.isEmpty()) {
        throw new SQLException("Recorded unit state is unavailable");
      }
      if (priorEpoch != null && key.equals(priorKey)) {
        // Re-enumeration is observation, not a new admission or permission to reset failure state.
        try (var update = connection.prepareStatement(
            "UPDATE jobs SET walk_seen_epoch = ? WHERE path = ? AND walk_seen_epoch IS NOT ?")) {
          update.setLong(1, epoch); update.setString(2, path); update.setLong(3, epoch);
          changed |= update.executeUpdate() > 0;
        }
        continue;
      }
      if (priorProgress.filter(prior -> prior.sealedAt() == null).isPresent()) {
        throw new IllegalStateException("Path belongs to another active recorded walk");
      }
      try (var insert = connection.prepareStatement("""
          INSERT OR REPLACE INTO jobs
            (path, state, attempts, last_updated, collection, size_bytes, scan_id, originator,
             transport, unit_revision, walk_seen_epoch)
          VALUES (?, 'PENDING', 0, ?, ?, ?, ?, ?, ?, lower(hex(randomblob(16))), ?)
          """)) {
        insert.setString(1, path); insert.setLong(2, now);
        insert.setString(3, declaredCollection != null ? declaredCollection : priorCollection);
        if (entry.sizeBytes() >= 0) insert.setLong(4, entry.sizeBytes());
        else insert.setNull(4, java.sql.Types.BIGINT);
        insert.setString(5, key);
        insert.setString(6, entry.provenance() == null ? null : entry.provenance().originator());
        insert.setString(7, entry.provenance() == null ? null : entry.provenance().transport());
        insert.setLong(8, epoch);
        if (insert.executeUpdate() != 1) throw new SQLException("Recorded admission was not written");
        changed = true;
      }
    }
    if (changed) {
      requireAdvance(progress);
      try (var update = connection.prepareStatement(
          "UPDATE ingestion_walk_progress SET revision = revision + 1 WHERE operation_key = ?")) {
        update.setString(1, key);
        if (update.executeUpdate() != 1) throw new SQLException("Recorded walk disappeared");
      }
    }
    return seen.size();
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

  static JobQueue.WalkProgress seal(Connection connection, String key, boolean issued,
      java.util.function.Function<String, String> pathHash, long now) throws SQLException {
    var progress = find(connection, key)
        .orElseThrow(() -> new JobQueue.RecordedWalkGapException("Recorded walk state is unavailable"));
    if (progress.sealedAt() != null || progress.enumerationClosedAt() == null || issued) return progress;
    long skipped = 0;
    java.util.List<String> failed = new java.util.ArrayList<>();
    try (var query = connection.prepareStatement("SELECT path, state, unit_revision, content_hash, "
        + "last_outcome_class, last_retry_policy FROM jobs WHERE scan_id = ? AND walk_seen_epoch IS NOT NULL")) {
      query.setString(1, key);
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          String state = rows.getString("state");
          if ("PENDING".equals(state) || "PROCESSING".equals(state)) return progress;
          String hash = pathHash.apply(rows.getString("path"));
          String contentHash = rows.getString("content_hash");
          Coverage coverage;
          if ("DONE".equals(state)) coverage = contentHash == null ? Coverage.SKIPPED : Coverage.INDEXED;
          else if ("FAILED".equals(state) || "RETRY_EXHAUSTED".equals(state)) coverage = Coverage.FAILED;
          else throw new JobQueue.RecordedWalkGapException("Recorded unit state is invalid");
          try {
            var outcome = IngestionOutcome.of(
                io.justsearch.indexerworker.ingest.IngestionOutcomeClass.valueOf(rows.getString("last_outcome_class")),
                null, IngestionRetryPolicy.valueOf(rows.getString("last_retry_policy")));
            validateReceipt(new UnitReceipt(key, rows.getString("unit_revision"), coverage, contentHash), outcome);
          } catch (IllegalArgumentException | NullPointerException | SQLException invalid) {
            throw new JobQueue.RecordedWalkGapException("Recorded terminal outcome is incompatible", invalid);
          }
          try (var evidence = connection.prepareStatement("SELECT 1 FROM ingestion_ledger WHERE "
              + "operation_key = ? AND path_hash = ? AND unit_revision = ? AND terminal_coverage = ? "
              + "AND content_hash IS ? LIMIT 1")) {
            evidence.setString(1, key); evidence.setString(2, hash);
            evidence.setString(3, rows.getString("unit_revision")); evidence.setString(4, coverage.name());
            evidence.setString(5, contentHash);
            try (var receipt = evidence.executeQuery()) {
              if (!receipt.next()) throw new JobQueue.RecordedWalkGapException("Recorded terminal coverage is unavailable");
            }
          }
          if (coverage == Coverage.SKIPPED) skipped++;
          if (coverage == Coverage.FAILED) failed.add(hash);
        }
      }
    }
    requireAdvance(progress);
    long revision = progress.revision() + 1;
    failed.sort(String::compareTo);
    var receipt = new java.util.LinkedHashMap<String, Object>();
    receipt.put("version", 1); receipt.put("revision", revision);
    receipt.put("completedUnits", progress.completedUnits()); receipt.put("failedUnits", progress.failedUnits());
    receipt.put("currentFailedUnits", failed.size()); receipt.put("currentSkippedUnits", skipped);
    receipt.put("enumerationOutcome", progress.enumerationOutcome().name());
    receipt.put("failedPathHashes", java.util.List.copyOf(failed.subList(0, Math.min(100, failed.size()))));
    receipt.put("failedPathHashesTruncated", failed.size() > 100);
    try (var update = connection.prepareStatement("UPDATE ingestion_walk_progress SET "
        + "sealed_at = ?, receipt_json = ?, revision = ? WHERE operation_key = ? AND sealed_at IS NULL")) {
      update.setLong(1, now); update.setString(2, JSON.writeValueAsString(receipt));
      update.setLong(3, revision); update.setString(4, key);
      if (update.executeUpdate() != 1) throw new SQLException("Recorded walk disappeared before sealing");
    }
    return find(connection, key).orElseThrow(() -> new JobQueue.RecordedWalkGapException("Recorded walk disappeared"));
  }

  static boolean acknowledge(Connection connection, String key, long revision) throws SQLException {
    var existing = find(connection, key);
    if (existing.isEmpty() || existing.get().sealedAt() == null || existing.get().revision() != revision) return false;
    if (existing.get().acknowledgedRevision() == revision) return true;
    try (var update = connection.prepareStatement("UPDATE ingestion_walk_progress SET acknowledged_revision = ? "
        + "WHERE operation_key = ? AND sealed_at IS NOT NULL AND revision = ?")) {
      update.setLong(1, revision); update.setString(2, key); update.setLong(3, revision);
      return update.executeUpdate() == 1;
    }
  }

  record Membership(String key, Long epoch) {}

  /** Maintenance can replace an existing member, but cannot silently add another walk member. */
  static Membership maintenanceMembership(Connection connection, String path, String requestedKey)
      throws SQLException {
    String priorKey = null;
    Long epoch = null;
    try (var query = connection.prepareStatement("SELECT scan_id, walk_seen_epoch FROM jobs WHERE path = ?")) {
      query.setString(1, path);
      try (var row = query.executeQuery()) {
        if (row.next()) {
          priorKey = row.getString(1);
          long value = row.getLong(2); epoch = row.wasNull() ? null : value;
        }
      }
    }
    if (epoch != null) {
      var progress = find(connection, priorKey)
          .orElseThrow(() -> new SQLException("Recorded unit state is unavailable"));
      if (progress.sealedAt() == null) {
        if (progress.enumerationOutcome() == JobQueue.WalkEnumerationOutcome.FAILED
            || progress.enumerationOutcome() == JobQueue.WalkEnumerationOutcome.CANCELLED) {
          throw new SQLException("Stopped recorded walk awaits issued owners before maintenance admission");
        }
        return new Membership(priorKey, epoch);
      }
      // Post-seal maintenance is outside that immutable finite walk.
      if (Objects.equals(requestedKey, priorKey)) requestedKey = null;
      priorKey = null;
    }
    String selected = requestedKey != null ? requestedKey : priorKey;
    if (selected != null && find(connection, selected).isPresent()) {
      throw new SQLException("Recorded membership requires explicit enumeration admission");
    }
    return new Membership(selected, null);
  }

  static void noteMutation(Connection connection, String key) throws SQLException {
    if (key == null) return;
    var progress = find(connection, key)
        .orElseThrow(() -> new SQLException("Recorded unit state is unavailable"));
    if (progress.sealedAt() != null) throw new SQLException("Recorded walk is sealed");
    requireAdvance(progress);
    try (var update = connection.prepareStatement(
        "UPDATE ingestion_walk_progress SET revision = revision + 1 WHERE operation_key = ?")) {
      update.setString(1, key);
      if (update.executeUpdate() != 1) throw new SQLException("Recorded walk disappeared");
    }
  }

  enum Coverage { INDEXED, FAILED, SKIPPED }

  /** Metadata for the existing ledger row, supplied only within its queue-owned transaction. */
  record UnitReceipt(String operationKey, String unitRevision, Coverage coverage, String contentHash) {}

  static UnitReceipt claimReceipt(Connection connection, JobQueue.IndexJob claim,
      Coverage coverage, String hash) throws SQLException {
    if (claim == null || claim.walkEpoch() == null) return null;
    var progress = find(connection, claim.scanId())
        .orElseThrow(() -> new SQLException("Recorded unit state is unavailable"));
    if (progress.sealedAt() != null) throw new SQLException("Issued unit cannot change a sealed receipt");
    return new UnitReceipt(claim.scanId(), claim.unitRevision(), coverage, hash);
  }

  static UnitReceipt currentReceipt(Connection connection, String path, Coverage coverage)
      throws SQLException {
    try (var query = connection.prepareStatement(
        "SELECT scan_id, unit_revision, walk_seen_epoch FROM jobs WHERE path = ?")) {
      query.setString(1, path);
      try (var row = query.executeQuery()) {
        if (!row.next()) return null;
        row.getLong(3);
        if (row.wasNull()) return null;
        String key = row.getString(1);
        var progress = find(connection, key)
            .orElseThrow(() -> new SQLException("Recorded unit state is unavailable"));
        // Retained terminal jobs may outlive their immutable sealed receipt.
        return progress.sealedAt() == null
            ? new UnitReceipt(key, row.getString(2), coverage, null) : null;
      }
    }
  }

  static void requireFailureOutcome(IngestionOutcome outcome) throws SQLException {
    if (outcome == null) throw new SQLException("Recorded failure outcome is required");
    boolean failure = switch (outcome.outcomeClass()) {
      case SUCCESS_FULL, SUCCESS_PARTIAL, SUCCESS_EMPTY, SKIPPED_POLICY, DEFERRED_POLICY, STALE_SOURCE -> false;
      default -> true;
    };
    if (!failure || outcome.retryPolicy() == IngestionRetryPolicy.DEFER_WITHOUT_ATTEMPT) {
      throw new SQLException("Recorded failure has a non-failure outcome");
    }
  }

  static void validateReceipt(UnitReceipt receipt, IngestionOutcome outcome) throws SQLException {
    if (receipt == null || receipt.coverage() == null) return;
    if (outcome == null) throw new SQLException("Recorded terminal outcome is required");
    if (receipt.coverage() == Coverage.FAILED) {
      requireFailureOutcome(outcome);
      return;
    }
    boolean compatible = switch (receipt.coverage()) {
      case INDEXED -> switch (outcome.outcomeClass()) {
        case SUCCESS_FULL, SUCCESS_PARTIAL, SUCCESS_EMPTY -> true;
        default -> false;
      };
      case SKIPPED -> switch (outcome.outcomeClass()) {
        case SKIPPED_POLICY, STALE_SOURCE, SUCCESS_EMPTY -> true;
        default -> false;
      };
      case FAILED -> false; // Validated above.
    };
    if (!compatible || outcome.retryPolicy() != IngestionRetryPolicy.NONE) {
      throw new SQLException("Recorded terminal coverage disagrees with its outcome");
    }
  }

  /** Returns false for an already recorded terminal receipt; its counters are already durable. */
  static boolean advanceReceipt(Connection connection, UnitReceipt receipt, String pathHash)
      throws SQLException {
    if (receipt == null || receipt.coverage() == null) return true;
    if (connection.getAutoCommit()) throw new SQLException("Terminal coverage requires the outcome transaction");
    if (receipt.unitRevision() == null || receipt.unitRevision().isBlank()
        || (receipt.coverage() == Coverage.INDEXED && receipt.contentHash() == null)) {
      throw new SQLException("Recorded terminal identity is incomplete");
    }
    try (var query = connection.prepareStatement("SELECT 1 FROM ingestion_ledger WHERE "
        + "operation_key = ? AND path_hash = ? AND unit_revision = ? AND terminal_coverage = ?")) {
      query.setString(1, receipt.operationKey()); query.setString(2, pathHash);
      query.setString(3, receipt.unitRevision()); query.setString(4, receipt.coverage().name());
      try (var row = query.executeQuery()) { if (row.next()) return false; }
    }
    long completed = 0;
    long failed = receipt.coverage() == Coverage.FAILED ? 1 : 0;
    if (receipt.coverage() == Coverage.INDEXED) {
      try (var query = connection.prepareStatement("SELECT 1 FROM ingestion_ledger WHERE "
          + "operation_key = ? AND path_hash = ? AND content_hash = ? AND terminal_coverage = 'INDEXED' LIMIT 1")) {
        query.setString(1, receipt.operationKey()); query.setString(2, pathHash);
        query.setString(3, receipt.contentHash());
        try (var row = query.executeQuery()) { completed = row.next() ? 0 : 1; }
      }
    }
    var progress = find(connection, receipt.operationKey())
        .orElseThrow(() -> new SQLException("Recorded unit state is unavailable"));
    requireAdvance(progress);
    if (progress.sealedAt() != null) throw new SQLException("Recorded walk is sealed");
    long nextCompleted = Math.addExact(progress.completedUnits(), completed);
    long nextFailed = Math.addExact(progress.failedUnits(), failed);
    try (var update = connection.prepareStatement("UPDATE ingestion_walk_progress SET "
        + "completed_units = ?, failed_units = ?, revision = revision + 1 WHERE operation_key = ?")) {
      update.setLong(1, nextCompleted); update.setLong(2, nextFailed);
      update.setString(3, receipt.operationKey());
      if (update.executeUpdate() != 1) throw new SQLException("Recorded walk disappeared");
    }
    return true;
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
