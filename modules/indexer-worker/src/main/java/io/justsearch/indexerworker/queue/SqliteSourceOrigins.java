/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import io.justsearch.app.api.indexing.SourceOrigin;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Immutable origin rows deliberately have no foreign key to expiring operation/job history. */
final class SqliteSourceOrigins {
  private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();
  private SqliteSourceOrigins() {}

  static java.util.Optional<String> find(Connection connection, JobQueue.IndexJob claim, String operation,
      List<io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding> declared,
      boolean currentDeclarations) throws SQLException {
    Path watched = watchedOwner(claim.path(), declared);
    if (watched != null) return watched(connection, watched);
    if (claim.ingestionRoot() == null) return java.util.Optional.empty();
    String root = PathNormalizer.normalizeKey(claim.ingestionRoot());
    if (claim.scanId() != null) {
      try (var query = connection.prepareStatement("""
          SELECT origin_id, normalized_root FROM source_origin
          WHERE kind='IMPORT' AND operation_key=?
          """)) {
        query.setString(1, operation);
        try (var rows = query.executeQuery()) {
          while (rows.next()) {
            if (root.equals(PathNormalizer.normalizeKey(Path.of(rows.getString(2))))) {
              return java.util.Optional.of(rows.getString(1));
            }
          }
        }
      }
    }
    // Recorded import replays without an origin remain unknown; exact-file jobs own themselves.
    if (claim.walkEpoch() != null || root.equals(PathNormalizer.normalizeKey(claim.path()))) {
      return java.util.Optional.empty();
    }
    // With the live declaration owner installed, an old watch claim cannot revive a removed root.
    return currentDeclarations ? java.util.Optional.empty() : watched(connection, claim.ingestionRoot());
  }

  private static Path watchedOwner(Path file,
      List<io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding> declared) {
    Path path = Path.of(PathNormalizer.normalizeKey(file));
    return declared.stream().map(binding -> Path.of(PathNormalizer.normalizeKey(binding.path())))
        .filter(path::startsWith).max(java.util.Comparator.comparingInt(Path::getNameCount)).orElse(null);
  }

  private static java.util.Optional<String> watched(Connection connection, Path root) throws SQLException {
    String normalized = PathNormalizer.normalizeKey(root);
    try (var query = connection.prepareStatement("""
        SELECT origin_id, normalized_root FROM source_origin WHERE kind='WATCHED'
        ORDER BY accepted_at DESC, rowid DESC
        """)) {
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          if (normalized.equals(PathNormalizer.normalizeKey(Path.of(rows.getString(2))))) {
            return java.util.Optional.of(rows.getString(1));
          }
        }
        return java.util.Optional.empty();
      }
    }
  }

  static java.util.Optional<String> moved(Connection connection, Path destination, String former,
      List<io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding> declared) throws SQLException {
    Path watch = watchedOwner(destination, declared);
    if (watch != null) return watched(connection, watch);
    if (former == null) return java.util.Optional.empty();
    try (var query = connection.prepareStatement(
        "SELECT normalized_root,partitions FROM source_origin WHERE origin_id=? AND kind='IMPORT'")) {
      query.setString(1, former);
      try (var row = query.executeQuery()) {
        if (!row.next()) return java.util.Optional.empty();
        Path file = Path.of(PathNormalizer.normalizeKey(destination));
        Path root = Path.of(PathNormalizer.normalizeKey(Path.of(row.getString(1))));
        if (!file.startsWith(root)) return java.util.Optional.empty();
        for (var partition : JSON.readTree(row.getString(2))) {
          if (file.startsWith(Path.of(PathNormalizer.normalizeKey(Path.of(partition.asString()))))) {
            return java.util.Optional.empty();
          }
        }
        return java.util.Optional.of(former);
      }
    }
  }

  static void record(Connection connection, List<SourceOrigin> origins) throws SQLException {
    try (var insert = connection.prepareStatement("""
        INSERT INTO source_origin (origin_id,kind,normalized_root,collection,single_file,force_reindex,
          partitions,request_exclusions,authorization_basis,recorded_transport,source_tier,client_kind,
          producing_operation_id,operation_key,approval_class,evidence_source,accepted_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(origin_id) DO NOTHING
        """)) {
      for (var origin : origins) {
        var root = origin.root();
        var approval = origin.approval();
        insert.setString(1, origin.id());
        insert.setString(2, origin.kind().name());
        insert.setString(3, root.path().toString());
        insert.setString(4, root.collection());
        insert.setBoolean(5, root.singleFile());
        insert.setBoolean(6, root.force());
        insert.setString(7, JSON.writeValueAsString(root.excludedSubtrees().stream().map(Object::toString).toList()));
        insert.setString(8, JSON.writeValueAsString(root.excludePatterns()));
        insert.setString(9, approval.authorizationBasis());
        insert.setString(10, approval.transport());
        insert.setString(11, approval.sourceTier());
        insert.setString(12, approval.clientKind());
        insert.setString(13, approval.producingOperationId());
        insert.setString(14, approval.operationKey());
        insert.setString(15, approval.approvalClass().name());
        insert.setString(16, approval.evidenceSource().name());
        insert.setLong(17, origin.acceptedAt());
        insert.addBatch();
      }
      insert.executeBatch();
    }
  }
}
