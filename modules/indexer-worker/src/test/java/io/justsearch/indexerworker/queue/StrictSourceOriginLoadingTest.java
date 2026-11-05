/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.indexing.SourceOrigin;
import io.justsearch.app.api.operations.OperationAuthorizationBasis;
import io.justsearch.app.api.operations.RecordedRootPlan;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StrictSourceOriginLoadingTest {
  @TempDir Path directory;

  @Test
  void strictOriginLoadingRoundTripsBindingAndRejectsMalformedEvidence() throws Exception {
    Path db = directory.resolve("strict.db");
    SourceOrigin legacy = origin();
    var binding = new SourceOrigin.RestartBinding(1, UUID.randomUUID().toString(), "a".repeat(64), UUID.randomUUID().toString());
    var versioned = new SourceOrigin(UUID.randomUUID().toString(), legacy.kind(), legacy.root(), legacy.approval(), 1, binding);
    try (var queue = new SqliteJobQueue(db)) {
      queue.open(); queue.recordSourceOrigins(List.of(legacy, versioned));
    }
    try (var queue = new SqliteJobQueue(db)) {
      queue.open(); assertEquals(versioned, queue.sourceOrigin(versioned.id()).orElseThrow());
      assertEquals(legacy, queue.sourceOrigin(legacy.id()).orElseThrow());
      assertTrue(queue.sourceOrigin(UUID.randomUUID().toString()).isEmpty());
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
        for (String mutation : List.of("request_exclusions='[1]'", "partitions='{}'", "restart_binding_version=99", "restart_binding_version=1.5", "accepted_at='bad'", "normalized_root='relative'", "authorization_basis='jsa1:unknown'", "approval_binding_id=NULL")) {
          statement.executeUpdate("UPDATE source_origin SET " + mutation + " WHERE origin_id='" + versioned.id() + "'");
          assertThrows(IllegalStateException.class, () -> queue.sourceOrigin(versioned.id()), mutation);
          statement.executeUpdate("DELETE FROM source_origin WHERE origin_id='" + versioned.id() + "'");
          queue.recordSourceOrigins(List.of(versioned));
        }
      }
    }
  }

  private SourceOrigin origin() {
    var approval = new SourceOrigin.Approval(new OperationAuthorizationBasis.StructuralAuto().encode(),
        "SYSTEM_INTERNAL", "TRUSTED", "INTERNAL", "core.ingest-files", "legacy-operation",
        SourceOrigin.ApprovalClass.DURABLE, SourceOrigin.EvidenceSource.GATE);
    return SourceOrigin.accepted(SourceOrigin.Kind.IMPORT,
        new RecordedRootPlan.Root(directory.resolve("root"), "docs", false, false, List.of(), List.of()), approval, 1);
  }

}
