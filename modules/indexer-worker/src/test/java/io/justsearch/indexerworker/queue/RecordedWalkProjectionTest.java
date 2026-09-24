/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real derived-store migration and finite enumeration persistence, independent of operation acceptance. */
final class RecordedWalkProjectionTest {
  private static final String KEY = "01994180-0000-7000-8000-000000000001";
  private static final String PLAN = "a".repeat(64);
  @TempDir Path temp;

  @Test
  void interruptedEnumerationAdvancesEpochWithoutRecreatingItsProgress() throws Exception {
    Path db = temp.resolve("jobs.db");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertThrows(IllegalStateException.class, () -> queue.beginRecordedWalk(KEY, PLAN, false));
      var first = queue.beginRecordedWalk(KEY, PLAN, true);
      assertEquals(1, first.enumerationEpoch());
      assertEquals(1, first.revision());
      assertNull(first.receiptJson());
      assertFalse(queue.acknowledgeRecordedWalk(KEY, first.revision()));
      assertThrows(IllegalArgumentException.class, () -> queue.beginRecordedWalk(KEY, "b".repeat(64), false));
    }
    try (var reopened = new SqliteJobQueue(db)) {
      reopened.open();
      var resumed = reopened.beginRecordedWalk(KEY, PLAN, false);
      assertEquals(2, resumed.enumerationEpoch());
      assertEquals(2, resumed.revision());
      assertThrows(IllegalStateException.class, () -> reopened.closeRecordedWalkEnumeration(
          KEY, 1, JobQueue.WalkEnumerationOutcome.COMPLETE));
      var closed = reopened.closeRecordedWalkEnumeration(KEY, 2, JobQueue.WalkEnumerationOutcome.FAILED);
      assertEquals(3, closed.revision());
      assertNotNull(closed.enumerationClosedAt());
      assertEquals(closed, reopened.closeRecordedWalkEnumeration(KEY, 2, JobQueue.WalkEnumerationOutcome.FAILED));
      assertEquals(closed, reopened.beginRecordedWalk(KEY, PLAN, false));
      assertThrows(IllegalStateException.class, () -> reopened.closeRecordedWalkEnumeration(
          KEY, 2, JobQueue.WalkEnumerationOutcome.COMPLETE));
      assertNull(closed.receiptJson(), "enumeration closure alone cannot certify terminal unit coverage");
    }
    try (var reopened = new SqliteJobQueue(db)) {
      reopened.open();
      assertEquals(JobQueue.WalkEnumerationOutcome.FAILED, reopened.recordedWalk(KEY).orElseThrow().enumerationOutcome());
      assertEquals(3, reopened.recordedWalk(KEY).orElseThrow().revision());
    }
  }

  @Test
  void actualV17MigrationAndInjectedV18RollbackPreserveAdmissions() throws Exception {
    Path db = temp.resolve("v17.db");
    createV17(db);
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var insert = connection.createStatement()) {
      insert.execute("INSERT INTO jobs(path,state,attempts,last_updated,scan_id,unit_revision) "
          + "VALUES('/fixture/a','PROCESSING',2,123,'legacy-scan','11111111111111111111111111111111')");
    }
    try (var failing = new SqliteJobQueue(db, 3, null, version -> {
      if (version == 18) throw new SQLException("fixture v18 rollback");
    })) {
      assertThrows(SQLException.class, failing::open);
    }
    assertEquals(17, scalar(db, "PRAGMA user_version"));
    assertEquals(0, scalar(db, "SELECT count(*) FROM sqlite_master WHERE name='ingestion_walk_progress'"));
    assertEquals(0, scalar(db, "SELECT count(*) FROM pragma_table_info('jobs') WHERE name='walk_seen_epoch'"));
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertEquals(SqliteSchema.TARGET_VERSION, scalar(db, "PRAGMA user_version"));
      assertTrue(queue.recordedWalk(KEY).isEmpty(), "migration must not invent operation acceptance");
      assertEquals(1, scalar(db, "SELECT count(*) FROM jobs WHERE state='PROCESSING' AND attempts=2 "
          + "AND last_updated=123 AND scan_id='legacy-scan' AND unit_revision='11111111111111111111111111111111'"));
      assertEquals(1, scalar(db, "SELECT count(*) FROM pragma_table_info('jobs') WHERE name='walk_seen_epoch'"));
      assertEquals(4, scalar(db, "SELECT count(*) FROM pragma_table_info('ingestion_ledger') "
          + "WHERE name IN ('operation_key','unit_revision','content_hash','terminal_coverage')"));
    }
  }

  @Test
  void privacyRepairKeepsExistingAttributionAndRecordedTerminalIdentity() throws Exception {
    Path db = temp.resolve("privacy.db");
    try (var queue = new SqliteJobQueue(db)) { queue.open(); }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement()) {
      statement.execute("ALTER TABLE ingestion_ledger ADD COLUMN job_path TEXT");
      statement.execute("INSERT INTO ingestion_ledger(path_hash,outcome_class,reason_code,retry_policy,observed_at, "
          + "originator,transport,operation_key,unit_revision,content_hash,terminal_coverage,job_path) "
          + "VALUES('path-hash','INDEXED','INDEXED','NONE',1,'original-owner','MCP','operation', "
          + "'revision','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','INDEXED','private-path')");
    }
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertEquals(0, scalar(db, "SELECT count(*) FROM pragma_table_info('ingestion_ledger') WHERE name='job_path'"));
      assertEquals(1, scalar(db, "SELECT count(*) FROM ingestion_ledger WHERE originator='original-owner' "
          + "AND transport='MCP' AND operation_key='operation' AND unit_revision='revision' "
          + "AND content_hash='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' AND terminal_coverage='INDEXED'"));
      assertEquals(2, scalar(db, "SELECT count(*) FROM sqlite_master WHERE name IN "
          + "('idx_ledger_walk_unit','idx_ledger_walk_hash')"));
    }
  }

  private static void createV17(Path db) throws Exception {
    try (var queue = new SqliteJobQueue(db)) { queue.open(); }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE ingestion_walk_progress");
      statement.execute("DROP INDEX idx_jobs_walk_epoch");
      statement.execute("DROP INDEX idx_ledger_walk_unit");
      statement.execute("DROP INDEX idx_ledger_walk_hash");
      statement.execute("ALTER TABLE jobs DROP COLUMN walk_seen_epoch");
      for (String column : new String[] {"operation_key", "unit_revision", "content_hash", "terminal_coverage"}) {
        statement.execute("ALTER TABLE ingestion_ledger DROP COLUMN " + column);
      }
      statement.execute("PRAGMA user_version=17");
    }
  }

  private static long scalar(Path db, String sql) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement(); var row = statement.executeQuery(sql)) {
      assertTrue(row.next());
      return row.getLong(1);
    }
  }
}
