/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

/** Captured-plan projections under the existing queue transaction and lock. */
final class SqliteCapturedWalkOps {
  private static final ObjectMapper JSON = new ObjectMapper();
  static final Set<String> RECEIPT_FIELDS = Set.of(
      "manifestSha256", "plannedUnits", "settlementSha256", "gapCount", "failedEvents", "supersededEvents");
  private static final int HISTORY_LIMIT = 200;

  private SqliteCapturedWalkOps() {}

  /** The finite capture includes earlier-epoch members, including disappeared sources. */
  static void captureManifest(Connection connection, String key) throws SQLException {
    MessageDigest digest = digest("captured-plan-v1");
    var members = new java.util.TreeMap<String, String>();
    // Canonical path-hash order; the full plan remains rows, never a prepared JSON envelope.
    try (var query = connection.prepareStatement("SELECT path, planned_source_sha256 FROM jobs "
        + "WHERE scan_id = ? AND walk_seen_epoch IS NOT NULL ORDER BY path")) {
      query.setString(1, key);
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          String hash = rows.getString(2);
          requireHash(hash);
          if (members.put(hash(rows.getString(1)), hash) != null) throw gap("Duplicate captured path identity");
        }
      }
    }
    members.forEach((pathHash, plannedHash) -> append(digest, List.of(pathHash, plannedHash)));
    try (var update = connection.prepareStatement("UPDATE ingestion_walk_progress SET "
        + "manifest_sha256 = ?, planned_units = ? WHERE operation_key = ?")) {
      update.setString(1, finish(digest)); update.setLong(2, members.size()); update.setString(3, key);
      if (update.executeUpdate() != 1) throw gap("Captured plan disappeared");
    }
  }

  static void seal(Connection connection, JobQueue.WalkProgress progress, long revision,
      java.util.function.Function<String, String> pathHash, Map<String, Object> receipt) throws SQLException {
    try (var query = connection.prepareStatement("SELECT path, unit_revision, planned_source_sha256, "
        + "content_hash, state FROM jobs WHERE scan_id = ? AND walk_seen_epoch IS NOT NULL")) {
      query.setString(1, progress.operationKey());
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          String planned = rows.getString(3);
          requireHash(planned);
          String coverage = "DONE".equals(rows.getString(5))
              ? rows.getString(4) == null ? "SKIPPED" : "INDEXED" : "FAILED";
          try (var select = connection.prepareStatement("INSERT INTO ingestion_walk_sealed_units "
              + "(operation_key, sealed_revision, path_hash, unit_revision, ledger_id) "
              + "SELECT operation_key, ?, path_hash, unit_revision, id FROM ingestion_ledger "
              + "WHERE operation_key = ? AND path_hash = ? AND unit_revision = ? "
              + "AND terminal_coverage = ? AND content_hash IS ? AND planned_source_sha256 = ?")) {
            select.setLong(1, revision); select.setString(2, progress.operationKey());
            select.setString(3, pathHash.apply(rows.getString(1))); select.setString(4, rows.getString(2));
            select.setString(5, coverage); select.setString(6, rows.getString(4)); select.setString(7, planned);
            if (select.executeUpdate() != 1) throw gap("Captured member has no exact terminal evidence");
          }
        }
      }
    }
    var projection = project(connection, progress, revision);
    receipt.put("manifestSha256", projection.manifestSha256());
    receipt.put("plannedUnits", projection.plannedUnits());
    receipt.put("settlementSha256", projection.sha256());
    receipt.put("gapCount", projection.gaps().size());
    receipt.put("failedEvents", projection.failedEvents());
    receipt.put("supersededEvents", projection.supersededEvents());
  }

  static Optional<JobQueue.CapturedWalkSettlement> settlement(Connection connection, String key) throws SQLException {
    var progress = SqliteIngestionWalkOps.find(connection, key)
        .orElseThrow(() -> gap("Captured walk is unavailable"));
    if (!progress.capturedPlan()) throw gap("Recorded walk is not a captured plan");
    if (progress.sealedAt() == null || progress.manifestSha256() == null) return Optional.empty();
    var projection = project(connection, progress, progress.revision());
    var receipt = JSON.readTree(progress.receiptJson());
    if (!projection.sha256().equals(receipt.path("settlementSha256").asText())
        || projection.gaps().size() != receipt.path("gapCount").asLong()
        || projection.failedEvents() != receipt.path("failedEvents").asLong()
        || projection.supersededEvents() != receipt.path("supersededEvents").asLong()) {
      throw gap("Captured settlement disagrees with its sealed receipt");
    }
    return Optional.of(projection);
  }

  static void validateReceipt(JobQueue.WalkProgress progress, Map<?, ?> receipt) {
    if (!progress.manifestSha256().equals(receipt.get("manifestSha256"))
        || nonnegative(receipt, "plannedUnits") != progress.plannedUnits()
        || !(receipt.get("settlementSha256") instanceof String hash)
        || !JobQueue.IngestionLedgerTransition.isSha256(hash)
        || nonnegative(receipt, "gapCount") > progress.plannedUnits()) {
      throw gap("Captured receipt identity is invalid");
    }
    nonnegative(receipt, "failedEvents"); nonnegative(receipt, "supersededEvents");
  }

  private static JobQueue.CapturedWalkSettlement project(Connection connection,
      JobQueue.WalkProgress progress, long revision) throws SQLException {
    MessageDigest digest = digest("captured-settlement-v1");
    append(digest, List.of(progress.manifestSha256(), progress.plannedUnits(), revision));
    List<JobQueue.CapturedUnit> gaps = new ArrayList<>();
    long selected = 0;
    MessageDigest manifest = digest("captured-plan-v1");
    try (var query = connection.prepareStatement("SELECT l.*, s.path_hash AS selected_path, "
        + "s.unit_revision AS selected_unit, s.sealed_revision FROM ingestion_walk_sealed_units s "
        + "LEFT JOIN ingestion_ledger l ON l.id = s.ledger_id WHERE s.operation_key = ? ORDER BY s.path_hash")) {
      query.setString(1, progress.operationKey());
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          if (!progress.operationKey().equals(rows.getString("operation_key"))
              || !rows.getString("selected_path").equals(rows.getString("path_hash"))
              || !rows.getString("selected_unit").equals(rows.getString("unit_revision"))
              || revision != rows.getLong("sealed_revision")) throw gap("Sealed member evidence is missing or changed");
          var unit = unit(rows);
          append(digest, unit);
          append(manifest, List.of(unit.pathHash(), unit.plannedSourceSha256()));
          if (!"INDEXED".equals(unit.coverage())) gaps.add(unit);
          selected++;
        }
      }
    }
    if (selected != progress.plannedUnits() || !finish(manifest).equals(progress.manifestSha256())) {
      throw gap("Sealed members differ from the captured plan");
    }
    append(digest, "history");
    List<JobQueue.CapturedUnit> history = new ArrayList<>();
    long failed = 0;
    long superseded = 0;
    // Hash every terminal event, not just the bounded public history sample.
    try (var query = connection.prepareStatement("SELECT * FROM ingestion_ledger WHERE operation_key = ? "
        + "AND terminal_coverage IS NOT NULL ORDER BY path_hash, id")) {
      query.setString(1, progress.operationKey());
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          var unit = unit(rows);
          append(digest, unit);
          boolean failure = "FAILED".equals(unit.coverage());
          boolean changed = "INDEXED".equals(unit.coverage())
              && !unit.plannedSourceSha256().equals(unit.contentHash());
          if (failure) failed++;
          if (changed) superseded++;
          if ((failure || changed) && history.size() < HISTORY_LIMIT) history.add(unit);
        }
      }
    }
    return new JobQueue.CapturedWalkSettlement(revision, finish(digest), progress.manifestSha256(),
        progress.plannedUnits(), failed, superseded, gaps, history);
  }

  private static JobQueue.CapturedUnit unit(java.sql.ResultSet row) throws SQLException {
    String planned = row.getString("planned_source_sha256");
    requireHash(planned);
    requireHash(row.getString("path_hash"));
    if (row.getString("unit_revision") == null || row.getString("unit_revision").isBlank()) {
      throw gap("Captured unit identity is missing");
    }
    String coverage = row.getString("terminal_coverage");
    if (coverage == null || !Set.of("INDEXED", "FAILED", "SKIPPED").contains(coverage)) throw gap("Invalid captured coverage");
    if ("INDEXED".equals(coverage)) requireHash(row.getString("content_hash"));
    try {
      var outcome = io.justsearch.indexerworker.ingest.IngestionOutcome.of(
          io.justsearch.indexerworker.ingest.IngestionOutcomeClass.valueOf(row.getString("outcome_class")),
          row.getString("reason_code"),
          io.justsearch.indexerworker.ingest.IngestionRetryPolicy.valueOf(row.getString("retry_policy")));
      SqliteIngestionWalkOps.validateReceipt(new SqliteIngestionWalkOps.UnitReceipt(
          row.getString("operation_key"), row.getString("unit_revision"),
          SqliteIngestionWalkOps.Coverage.valueOf(coverage), row.getString("content_hash"), planned), outcome);
    } catch (IllegalArgumentException | NullPointerException | SQLException invalid) {
      throw new JobQueue.RecordedWalkGapException("Captured terminal outcome is incompatible", invalid);
    }
    return new JobQueue.CapturedUnit(row.getString("path_hash"), row.getString("unit_revision"),
        planned, row.getString("content_hash"), coverage, row.getString("outcome_class"),
        row.getString("reason_code"), row.getString("retry_policy"));
  }

  private static long nonnegative(Map<?, ?> values, String key) {
    Object value = values.get(key);
    if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0) {
      throw gap("Invalid captured receipt counter: " + key);
    }
    return ((Number) value).longValue();
  }

  private static void requireHash(String hash) {
    if (hash == null || !JobQueue.IngestionLedgerTransition.isSha256(hash)) throw gap("Captured source identity is missing or invalid");
  }

  private static MessageDigest digest(String domain) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      append(digest, domain);
      return digest;
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static String hash(String text) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static void append(MessageDigest digest, Object value) {
    digest.update(JSON.writeValueAsBytes(value)); digest.update((byte) '\n');
  }

  private static String finish(MessageDigest digest) { return HexFormat.of().formatHex(digest.digest()); }

  private static JobQueue.RecordedWalkGapException gap(String message) {
    return new JobQueue.RecordedWalkGapException(message);
  }
}
