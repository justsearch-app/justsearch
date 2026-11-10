/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ExtractionAttemptPolicyMigrationTest {
  @TempDir Path temporary;

  @Test
  void v28UpgradeKeepsEveryJobAndLeavesAmbiguousHistoryUnknown() throws Exception {
    Path db = temporary.resolve("jobs.db");
    try (var queue = new SqliteJobQueue(db)) { queue.open(); }
    List<List<String>> before;
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement()) {
      assertDoesNotThrow(() -> statement.execute("ALTER TABLE jobs DROP COLUMN extraction_policy_id"),
          "the queue stores each attempt's extraction policy on its job (C9)");
      for (var state : List.of("PENDING", "PROCESSING", "DONE", "FAILED", "RETRY_EXHAUSTED")) {
        try (var insert = connection.prepareStatement("""
            INSERT INTO jobs(path,state,attempts,last_updated,collection,size_bytes,scan_id,unit_revision,
              originator,transport,last_outcome_class,last_reason_code,last_retry_policy,last_outcome_at)
            VALUES(?,?,3,17,'docs',19,'scan','revision','user','HTTP','PARSER_FAILED','parser','NONE',23)
            """)) {
          insert.setString(1, temporary.resolve(state).toString());
          insert.setString(2, state);
          insert.executeUpdate();
        }
      }
      statement.execute("PRAGMA user_version=28");
      before = snapshot(db);
    }
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertEquals(before, snapshot(db));
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT extraction_policy_id FROM jobs")) {
      int count = 0;
      while (rows.next()) { assertNull(rows.getString(1)); count++; }
      assertEquals(5, count);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"exact", "other-revision", "duplicate", "pruned"})
  void backfillsOnlyUnambiguousExactTerminalEvidence(String history) throws Exception {
    Path file = Files.writeString(temporary.resolve("source.txt"), "same bytes");
    Path db = temporary.resolve("jobs.db");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(temporary)), "docs", "scan");
      var failure = IngestionOutcome.of(IngestionOutcomeClass.PARSER_FAILED, "parser", IngestionRetryPolicy.NONE);
      queue.markFailed(file, failure, new JobQueue.IngestionLedgerEntry(null, "docs", Files.size(file),
          Files.getLastModifiedTime(file).toMillis(), "FILE", "FAILED", "old", "parser"));
      queue.recordIngestionEvent(file, IngestionOutcome.of(IngestionOutcomeClass.IO_FAILED,
          "unrelated", IngestionRetryPolicy.NONE), new JobQueue.IngestionLedgerEntry(
              null, "docs", null, null, "FILE", "FAILED", "new", "parser"));
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement()) {
      statement.execute("""
          UPDATE ingestion_ledger SET operation_key='scan', unit_revision=(SELECT unit_revision FROM jobs)
          WHERE outcome_class='PARSER_FAILED'
          """);
      if (history.equals("other-revision")) statement.execute(
          "UPDATE ingestion_ledger SET unit_revision='another-revision' WHERE outcome_class='PARSER_FAILED'");
      if (history.equals("pruned")) statement.execute("DELETE FROM ingestion_ledger WHERE outcome_class='PARSER_FAILED'");
      if (history.equals("duplicate")) statement.execute("""
          INSERT INTO ingestion_ledger(path_hash,collection,outcome_class,reason_code,retry_policy,
            observed_at,source_kind,artifact_status,policy_id,parser_id,operation_key,unit_revision)
          SELECT path_hash,collection,outcome_class,reason_code,retry_policy,
            observed_at,source_kind,artifact_status,policy_id,parser_id,operation_key,unit_revision
          FROM ingestion_ledger WHERE outcome_class='PARSER_FAILED'
          """);
      assertDoesNotThrow(() -> statement.execute("ALTER TABLE jobs DROP COLUMN extraction_policy_id"),
          "the queue stores each attempt's extraction policy on its job (C9)");
      statement.execute("PRAGMA user_version=28");
    }
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      var row = queue.watcherReconciliationRow(PathNormalizer.normalizeKey(file));
      assertEquals("FAILED", row.get("state"));
      assertEquals(history.equals("exact") ? "old" : null, row.get("extraction_policy_id"));
    }
  }

  private static List<List<String>> snapshot(Path db) throws Exception {
    var result = new ArrayList<List<String>>();
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement();
        var rows = statement.executeQuery("""
            SELECT path,state,attempts,last_updated,collection,size_bytes,scan_id,unit_revision,
              originator,transport,last_outcome_class,last_reason_code,last_retry_policy,last_outcome_at
            FROM jobs ORDER BY path
            """)) {
      while (rows.next()) {
        var values = new ArrayList<String>();
        for (int column = 1; column <= 14; column++) values.add(rows.getString(column));
        result.add(values);
      }
    }
    return result;
  }
}
