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
  private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder()
      .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
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

  static java.util.Optional<SourceOrigin> load(Connection connection, String id) throws SQLException {
    try (var query = connection.prepareStatement("SELECT * FROM source_origin WHERE origin_id=?")) {
      query.setString(1, id);
      try (var row = query.executeQuery()) {
        if (!row.next()) return java.util.Optional.empty();
        try {
          var approval = new SourceOrigin.Approval(row.getString("authorization_basis"),
              row.getString("recorded_transport"), row.getString("source_tier"), row.getString("client_kind"),
              row.getString("producing_operation_id"), row.getString("operation_key"),
              SourceOrigin.ApprovalClass.valueOf(row.getString("approval_class")),
              SourceOrigin.EvidenceSource.valueOf(row.getString("evidence_source")));
          io.justsearch.agent.api.registry.TransportTag.valueOf(approval.transport());
          io.justsearch.agent.api.registry.SourceTier.valueOf(approval.sourceTier());
          io.justsearch.core.context.EngineContext.ClientKind.valueOf(approval.clientKind());
          Object storedVersion = row.getObject("restart_binding_version");
          boolean unversioned = storedVersion == null;
          if (!unversioned && !(storedVersion instanceof Integer || storedVersion instanceof Long)) {
            throw new IllegalArgumentException("Invalid origin restart binding version");
          }
          long version = unversioned ? 0 : ((Number) storedVersion).longValue();
          if (version != 0 && version != io.justsearch.app.api.operations.ImportApprovalBinding.VERSION) {
            throw new IllegalArgumentException("Unknown origin restart binding version");
          }
          String bindingId = row.getString("approval_binding_id");
          String planHash = row.getString("approval_plan_hash");
          String predecessor = row.getString("predecessor_origin_id");
          if (unversioned && (bindingId != null || planHash != null || predecessor != null)) {
            throw new IllegalArgumentException("Partial origin restart binding");
          }
          var binding = unversioned ? null : new SourceOrigin.RestartBinding(Math.toIntExact(version), bindingId, planHash, predecessor);
          var root = new io.justsearch.app.api.operations.RecordedRootPlan.Root(
              savedPath(row.getString("normalized_root")), row.getString("collection"),
              booleanColumn(row, "force_reindex"), booleanColumn(row, "single_file"),
              strings(row.getString("request_exclusions")),
              strings(row.getString("partitions")).stream().map(SqliteSourceOrigins::savedPath).toList());
          Object acceptedAt = row.getObject("accepted_at");
          if (!(acceptedAt instanceof Integer || acceptedAt instanceof Long)) {
            throw new IllegalArgumentException("Invalid origin acceptance time");
          }
          return java.util.Optional.of(new SourceOrigin(row.getString("origin_id"),
              SourceOrigin.Kind.valueOf(row.getString("kind")), root, approval, ((Number) acceptedAt).longValue(), binding));
        } catch (RuntimeException malformed) {
          throw new IllegalStateException("Malformed saved source origin", malformed);
        }
      }
    }
  }

  private static Path savedPath(String value) {
    Path path = Path.of(value);
    if (!path.isAbsolute() || !path.normalize().equals(path)) {
      throw new IllegalArgumentException("Invalid saved origin path");
    }
    return path;
  }

  private static boolean booleanColumn(java.sql.ResultSet row, String name) throws SQLException {
    int value = row.getInt(name);
    if (row.wasNull() || (value != 0 && value != 1)) throw new IllegalArgumentException("Invalid origin boolean");
    return value == 1;
  }

  private static List<String> strings(String encoded) {
    Object decoded = JSON.readValue(encoded, Object.class);
    if (!(decoded instanceof List<?> list) || list.stream().anyMatch(value -> !(value instanceof String))) {
      throw new IllegalArgumentException("Invalid origin string array");
    }
    return list.stream().map(String.class::cast).toList();
  }

  static void record(Connection connection, List<SourceOrigin> origins) throws SQLException {
    try (var insert = connection.prepareStatement("""
        INSERT INTO source_origin (origin_id,kind,normalized_root,collection,single_file,force_reindex,
          partitions,request_exclusions,authorization_basis,recorded_transport,source_tier,client_kind,
          producing_operation_id,operation_key,approval_class,evidence_source,accepted_at,
          restart_binding_version,approval_binding_id,approval_plan_hash,predecessor_origin_id)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(origin_id) DO NOTHING
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
        var binding = origin.restartBinding();
        if (binding == null) insert.setNull(18, java.sql.Types.INTEGER);
        else insert.setInt(18, binding.version());
        insert.setString(19, binding == null ? null : binding.bindingId());
        insert.setString(20, binding == null ? null : binding.planHash());
        insert.setString(21, binding == null ? null : binding.predecessorOriginId());
        insert.addBatch();
      }
      insert.executeBatch();
    }
  }
}
