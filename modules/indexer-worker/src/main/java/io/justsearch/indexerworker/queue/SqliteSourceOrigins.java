/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import io.justsearch.app.api.indexing.SourceOrigin;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Immutable origin rows deliberately have no foreign key to expiring operation/job history. */
final class SqliteSourceOrigins {
  private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();
  private SqliteSourceOrigins() {}

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
