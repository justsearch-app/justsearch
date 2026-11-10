/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.indexing.SourceOrigin;
import io.justsearch.app.api.operations.OperationAuthorizationBasis;
import io.justsearch.app.api.operations.RecordedRootPlan;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ImportOriginBindingMigrationTest {
  private static final Set<String> COLUMNS = Set.of("restart_binding_version", "approval_binding_id", "approval_plan_hash", "predecessor_origin_id");
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void freshAndV27QueuesNeverUpgradeLegacyApproval(boolean upgrade) throws Exception {
    Path db = directory.resolve("jobs.db");
    if (upgrade) legacy(db);
    try (var queue = new SqliteJobQueue(db)) {
      assertDoesNotThrow(queue::open);
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
        try (var rows = statement.executeQuery("PRAGMA user_version")) { assertTrue(rows.next()); assertEquals(SqliteSchema.TARGET_VERSION, rows.getInt(1), "origin restart bindings require v28"); }
        try (var rows = statement.executeQuery("SELECT count(*) FROM pragma_table_info('source_origin') WHERE name IN "
            + "('restart_binding_version','approval_binding_id','approval_plan_hash','predecessor_origin_id')")) {
          assertTrue(rows.next()); assertEquals(4, rows.getInt(1));
        }
        if (upgrade) {
          assertEquals(1, queue.queueDepth());
          try (var rows = statement.executeQuery("SELECT * FROM source_origin")) {
            assertTrue(rows.next()); assertEquals("jsa1:auto", rows.getString("authorization_basis"));
            for (String column : COLUMNS) assertNull(rows.getObject(column), "legacy approval must remain unversioned");
          }
        }
      }
    }
    try (var queue = new SqliteJobQueue(db)) { assertDoesNotThrow(queue::open); }
  }

  @Test
  void failedV28MigrationRollsBackBindingColumnsAndVersion() throws Exception {
    Path db = directory.resolve("rollback.db"); legacy(db);
    try (var queue = new SqliteJobQueue(db, 3, null, version -> {
      if (version == 28) throw new SQLException("injected v28 binding migration failure");
    })) {
      var failure = assertThrows(SQLException.class, queue::open, "v28 migration must execute transactionally");
      assertTrue(failure.getMessage().contains("injected v28 binding migration failure"));
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
      try (var rows = statement.executeQuery("PRAGMA user_version")) { assertTrue(rows.next()); assertEquals(27, rows.getInt(1)); }
      try (var rows = statement.executeQuery("SELECT name FROM pragma_table_info('source_origin')")) {
        while (rows.next()) assertFalse(COLUMNS.contains(rows.getString(1)), "DDL rolls back with its version");
      }
      try (var rows = statement.executeQuery("SELECT count(*) FROM source_origin")) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); }
    }
    try (var queue = new SqliteJobQueue(db)) { assertDoesNotThrow(queue::open); assertEquals(1, queue.queueDepth()); }
  }

  private SourceOrigin origin() {
    var approval = new SourceOrigin.Approval(new OperationAuthorizationBasis.StructuralAuto().encode(),
        "SYSTEM_INTERNAL", "TRUSTED", "INTERNAL", "core.ingest-files", "legacy-operation",
        SourceOrigin.ApprovalClass.DURABLE, SourceOrigin.EvidenceSource.GATE);
    return SourceOrigin.accepted(SourceOrigin.Kind.IMPORT,
        new RecordedRootPlan.Root(directory.resolve("root"), "docs", false, false, List.of(), List.of()), approval, 1);
  }

  private void legacy(Path db) throws Exception {
    try (var queue = new SqliteJobQueue(db)) { queue.open(); queue.enqueue(List.of(directory.resolve("preserved.txt"))); queue.recordSourceOrigins(List.of(origin())); }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
      var existing = new java.util.HashSet<String>();
      try (var rows = statement.executeQuery("PRAGMA table_info(source_origin)")) { while (rows.next()) existing.add(rows.getString("name")); }
      for (String column : COLUMNS) if (existing.contains(column)) statement.execute("ALTER TABLE source_origin DROP COLUMN " + column);
      statement.execute("PRAGMA user_version=27");
    }
  }
}
