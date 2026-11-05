/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ImportApprovalMigrationTest {
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC);
  private static final Set<String> COLUMNS = Set.of("active_approval_binding", "interruption_revision", "interruption_reason");
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void freshAndV6StoresKeepApprovalUnversionedUntilActivation(boolean upgrade) throws Exception {
    Path db = directory.resolve("operations.db");
    if (upgrade) legacy(db);
    try (var _ = new SqliteOperationStore(db, CLOCK, ignored -> {});
        var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement()) {
      assertEquals(7, scalar(statement, "PRAGMA user_version"), "versioned authority requires operations v7");
      assertEquals(3, scalar(statement, "SELECT count(*) FROM pragma_table_info('operations') WHERE name IN "
          + "('active_approval_binding','interruption_revision','interruption_reason')"));
      if (upgrade) {
        assertEquals(1, scalar(statement, "SELECT count(*) FROM operations WHERE operation_key='legacy' "
            + "AND active_approval_binding IS NULL AND interruption_revision=0 AND interruption_reason IS NULL AND units_completed=3"));
        assertEquals(73, scalar(statement, "SELECT seq FROM sqlite_sequence WHERE name='operations'"));
      }
    }
    try (var store = new SqliteOperationStore(db, CLOCK, ignored -> {})) { assertTrue(store.recovery().isEmpty()); }
  }

  @Test
  void failedV7MigrationRollsBackFieldsVersionAndRows() throws Exception {
    Path db = directory.resolve("rollback.db"); legacy(db);
    var failure = assertThrows(java.io.IOException.class, () -> new SqliteOperationStore(db, CLOCK, step -> {
      if (step.equals("before-schema-commit")) {
        // Observe the real migration's uncommitted DDL through the opening connection hook's phase.
        throw new java.io.IOException("injected v7 migration failure");
      }
    }));
    assertEquals("injected v7 migration failure", failure.getMessage());
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
      assertEquals(6, scalar(statement, "PRAGMA user_version"));
      assertEquals(0, scalar(statement, "SELECT count(*) FROM pragma_table_info('operations') WHERE name IN "
          + "('active_approval_binding','interruption_revision','interruption_reason')"));
      assertEquals(3, scalar(statement, "SELECT units_completed FROM operations"));
    }
    try (var _ = new SqliteOperationStore(db, CLOCK, ignored -> {});
        var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
      assertEquals(7, scalar(statement, "PRAGMA user_version"), "retry must execute the v7 migration");
    }
  }

  private static void legacy(Path db) throws Exception {
    try (var _ = new SqliteOperationStore(db, CLOCK, ignored -> {});
        var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
      var existing = new java.util.HashSet<String>();
      try (var rows = statement.executeQuery("PRAGMA table_info(operations)")) {
        while (rows.next()) existing.add(rows.getString("name"));
      }
      for (String column : COLUMNS) if (existing.contains(column)) statement.execute("ALTER TABLE operations DROP COLUMN " + column);
      statement.execute("INSERT INTO operations(operation_key,kind,survival,urgency,state,identity_json,accepted_at,updated_at,units_completed) "
          + "VALUES ('legacy','ingest','DURABLE','BACKGROUND','RUNNING','{}',1,1,3)");
      statement.execute("UPDATE sqlite_sequence SET seq=73 WHERE name='operations'");
      statement.execute("PRAGMA user_version=6");
    }
  }

  private static long scalar(Statement statement, String sql) throws Exception {
    try (var rows = statement.executeQuery(sql)) { assertTrue(rows.next()); return rows.getLong(1); }
  }
}
