/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SwitchBufferVersionTest {
  @TempDir Path tempDir;

  @Test
  void staleSnapshotCannotRemoveAnIdenticalReinsertedKey() throws Exception {
    try (var queue = new SqliteJobQueue(tempDir.resolve("reinsert.db"))) {
      queue.open();
      assertTrue(queue.putSwitchBuffer("same", "DELETE", "document"));
      var first = queue.listSwitchBufferOps();
      assertEquals(1, queue.removeReplayedSwitchBufferOps(first));
      assertTrue(queue.putSwitchBuffer("same", "DELETE", "document"));
      var second = queue.listSwitchBufferOps();
      assertNotEquals(first.getFirst().revision(), second.getFirst().revision());
      assertEquals(0, queue.removeReplayedSwitchBufferOps(first));
      assertEquals(second, queue.listSwitchBufferOps());
      assertEquals(1, queue.removeReplayedSwitchBufferOps(second));
    }
  }

  @Test
  void candidateGenerationsRetainSameKeyAndRemoveOnlyMatchingVersion() throws Exception {
    try (var queue = new SqliteJobQueue(tempDir.resolve("generation-scope.db"))) {
      queue.open();
      assertTrue(queue.putSwitchBufferForGeneration("green-a", "same", "DELETE", "a"));
      assertTrue(queue.putSwitchBufferForGeneration("green-b", "same", "DELETE", "b"));

      var a = queue.listSwitchBufferOpsStrictForGeneration("green-a");
      var b = queue.listSwitchBufferOpsStrictForGeneration("green-b");
      assertEquals(List.of("green-a"), a.stream()
          .map(SwitchBufferCapableQueue.SwitchBufferOp::generation).toList());
      assertEquals(List.of("a"), a.stream()
          .map(SwitchBufferCapableQueue.SwitchBufferOp::payload).toList());
      assertEquals(List.of("b"), b.stream()
          .map(SwitchBufferCapableQueue.SwitchBufferOp::payload).toList());
      assertThrows(IllegalArgumentException.class,
          () -> queue.removeReplayedSwitchBufferOpsForGeneration("green-b", a));
      assertEquals(1, queue.removeReplayedSwitchBufferOpsForGeneration("green-a", a));
      assertEquals(List.of("b"), queue.listSwitchBufferOpsStrictForGeneration("green-b")
          .stream().map(SwitchBufferCapableQueue.SwitchBufferOp::payload).toList());
    }
  }

  @Test
  void atomicFileAdmissionCommitsJobAndScopedUpsertTogether() throws Exception {
    Path db = tempDir.resolve("atomic-file-admission.db");
    Path file = tempDir.resolve("atomic.txt").toAbsolutePath();
    String normalized = PathNormalizer.normalizeKey(file);
    var provenance = new JobQueue.EnqueueProvenance("agent", "atomic-test");
    var entry = new JobQueue.EnqueueEntry(file, 17L, provenance, "a".repeat(64));
    try (var queue = new SqliteJobQueue(
        db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var captured = queue.beginCapturedWalk("scan-1", "b".repeat(64), true);
      assertEquals(1, queue.enqueueRecordedEntries(
          "scan-1", captured.enumerationEpoch(), List.of(entry), "docs"));
      queue.closeRecordedWalkEnumeration(
          "scan-1", captured.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.COMPLETE);
      // Keep an issued owner live: ordinary maintenance admission seals a captured walk before
      // reading its membership, while an issued member remains open and retains its source hash.
      assertEquals(1, queue.pollPending(1).size());
      assertTrue(queue.enqueueAndBufferFileForGeneration("green", entry, "docs", "scan-1"));
      assertTrue(queue.enqueueAndBufferFileForGeneration("green", entry, "docs", "scan-1"));
      assertTrue(queue.enqueueAndBufferFileForGeneration("other", entry, "docs", "scan-1"));

      assertEquals(1, queue.listSwitchBufferOpsStrictForGeneration("green").size());
      assertEquals(1, queue.listSwitchBufferOpsStrictForGeneration("other").size());
      var upsert = SwitchBufferUpsert.decode(
          queue.listSwitchBufferOpsStrictForGeneration("green").getFirst().payload());
      assertEquals(normalized, upsert.path());
      assertEquals("docs", upsert.collection());
      assertEquals(provenance, upsert.provenance());

      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
          var statement = connection.prepareStatement(
              "SELECT state, collection, scan_id, size_bytes, planned_source_sha256, "
                  + "originator, transport, unit_revision, content_hash FROM jobs WHERE path = ?")) {
        statement.setString(1, normalized);
        try (var row = statement.executeQuery()) {
          assertTrue(row.next());
          assertEquals("PENDING", row.getString("state"));
          assertEquals("docs", row.getString("collection"));
          assertEquals("scan-1", row.getString("scan_id"));
          assertEquals(17L, row.getLong("size_bytes"));
          assertEquals("a".repeat(64), row.getString("planned_source_sha256"));
          assertEquals("agent", row.getString("originator"));
          assertEquals("atomic-test", row.getString("transport"));
          assertTrue(row.getString("unit_revision").matches("[0-9a-f]{32}"));
          assertNull(row.getString("content_hash"));
        }
      }
    }
  }

  @Test
  void atomicFileAdmissionRollsBackJobWhenJournalWriteFails() throws Exception {
    Path db = tempDir.resolve("atomic-file-admission-rollback.db");
    Path file = tempDir.resolve("rollback.txt").toAbsolutePath();
    String normalized = PathNormalizer.normalizeKey(file);
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
          var statement = connection.createStatement()) {
        statement.execute("DROP TABLE switch_buffer");
      }
      assertFalse(queue.enqueueAndBufferFileForGeneration(
          "green", JobQueue.EnqueueEntry.ofUnknownSize(file), null, null));
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        var statement = connection.prepareStatement("SELECT COUNT(*) FROM jobs WHERE path = ?")) {
      statement.setString(1, normalized);
      try (var row = statement.executeQuery()) {
        assertTrue(row.next());
        assertEquals(0L, row.getLong(1));
      }
    }
  }

  @Test
  void atomicFileBatchRollsBackAllJobsWhenLaterJournalWriteFails() throws Exception {
    Path db = tempDir.resolve("atomic-file-batch-rollback.db");
    Path first = tempDir.resolve("first.txt").toAbsolutePath();
    Path second = tempDir.resolve("second.txt").toAbsolutePath();
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
          var statement = connection.createStatement()) {
        statement.execute("""
            CREATE TRIGGER refuse_later_journal BEFORE INSERT ON switch_buffer
            WHEN NEW.key LIKE '%second.txt'
            BEGIN SELECT RAISE(ABORT, 'injected later journal failure'); END
            """);
      }
      assertEquals(0, queue.enqueueAndBufferFilesForGeneration("green", List.of(
          JobQueue.EnqueueEntry.ofUnknownSize(first),
          JobQueue.EnqueueEntry.ofUnknownSize(second)), "docs", null));
      assertTrue(queue.listSwitchBufferOpsStrictForGeneration("green").isEmpty());
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT COUNT(*) FROM jobs")) {
      assertTrue(rows.next());
      assertEquals(0L, rows.getLong(1));
    }
  }

  @Test
  void atomicRecordedAdmissionCommitsFiniteMemberAndScopedUpsertTogether() throws Exception {
    Path db = tempDir.resolve("atomic-recorded-admission.db");
    Path file = tempDir.resolve("recorded-atomic.txt").toAbsolutePath();
    String normalized = PathNormalizer.normalizeKey(file);
    String operationKey = "recorded-atomic";
    var provenance = new JobQueue.EnqueueProvenance("agent", "recorded-atomic-test");
    var entry = new JobQueue.EnqueueEntry(file, 23L, provenance, "c".repeat(64));
    try (var queue = new SqliteJobQueue(
        db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var captured = queue.beginCapturedWalk(operationKey, "d".repeat(64), true);
      assertEquals(1, queue.enqueueRecordedEntriesAndBufferForGeneration(
          "green", operationKey, captured.enumerationEpoch(), List.of(entry, entry), "docs"));

      var ops = queue.listSwitchBufferOpsStrictForGeneration("green");
      assertEquals(1, ops.size());
      var upsert = SwitchBufferUpsert.decode(ops.getFirst().payload());
      assertEquals(normalized, upsert.path());
      assertEquals("docs", upsert.collection());
      assertEquals(provenance, upsert.provenance());
      assertEquals(2, queue.recordedWalk(operationKey).orElseThrow().revision());

      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
          var statement = connection.prepareStatement(
              "SELECT state, planned_source_sha256 FROM jobs WHERE path = ?")) {
        statement.setString(1, normalized);
        try (var row = statement.executeQuery()) {
          assertTrue(row.next());
          assertEquals("PENDING", row.getString("state"));
          assertEquals("c".repeat(64), row.getString("planned_source_sha256"));
        }
      }
    }
  }

  @Test
  void atomicRecordedAdmissionRollsBackMemberWhenJournalWriteFails() throws Exception {
    Path db = tempDir.resolve("atomic-recorded-admission-rollback.db");
    Path file = tempDir.resolve("recorded-rollback.txt").toAbsolutePath();
    String normalized = PathNormalizer.normalizeKey(file);
    String operationKey = "recorded-rollback";
    var entry = new JobQueue.EnqueueEntry(file, 11L, null, "e".repeat(64));
    try (var queue = new SqliteJobQueue(
        db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var captured = queue.beginCapturedWalk(operationKey, "f".repeat(64), true);
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
          var statement = connection.createStatement()) {
        statement.execute("DROP TABLE switch_buffer");
      }

      assertThrows(IllegalStateException.class, () ->
          queue.enqueueRecordedEntriesAndBufferForGeneration(
              "green", operationKey, captured.enumerationEpoch(), List.of(entry), null));
      assertEquals(1, queue.recordedWalk(operationKey).orElseThrow().revision());
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        var statement = connection.prepareStatement("SELECT COUNT(*) FROM jobs WHERE path = ?")) {
      statement.setString(1, normalized);
      try (var row = statement.executeQuery()) {
        assertTrue(row.next());
        assertEquals(0L, row.getLong(1));
      }
    }
  }

  @Test
  void failedRemovalRollsBackEverySnapshotDeletion() throws Exception {
    Path db = tempDir.resolve("rollback-removal.db");
    List<SwitchBufferCapableQueue.SwitchBufferOp> snapshot;
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertTrue(queue.putSwitchBuffer("a", "DELETE", "first"));
      assertTrue(queue.putSwitchBuffer("b", "DELETE", "second"));
      snapshot = queue.listSwitchBufferOps().stream()
          .sorted(java.util.Comparator.comparing(SwitchBufferCapableQueue.SwitchBufferOp::key))
          .toList();
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
          var statement = connection.createStatement()) {
        statement.execute("""
            CREATE TRIGGER reject_second_removal BEFORE DELETE ON switch_buffer
            WHEN OLD.key = 'b' BEGIN SELECT RAISE(ABORT, 'injected removal failure'); END
            """);
      }
      assertThrows(IllegalStateException.class, () -> queue.removeReplayedSwitchBufferOps(snapshot));
    }
    try (var reopened = new SqliteJobQueue(db)) {
      reopened.open();
      assertEquals(snapshot, reopened.listSwitchBufferOps().stream()
          .sorted(java.util.Comparator.comparing(SwitchBufferCapableQueue.SwitchBufferOp::key))
          .toList());
    }
  }

  @Test
  void v15RowsReceiveStableDistinctRevisionsWithoutChangingPayloads() throws Exception {
    Path db = v15Fixture("upgrade.db");
    List<SwitchBufferCapableQueue.SwitchBufferOp> upgraded;
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      upgraded = queue.listSwitchBufferOps();
      assertEquals(2, upgraded.size());
      assertEquals(List.of("first", "second"), upgraded.stream()
          .map(SwitchBufferCapableQueue.SwitchBufferOp::payload).toList());
      assertTrue(upgraded.stream().allMatch(row -> !row.revision().isBlank()));
      assertNotEquals(upgraded.getFirst().revision(), upgraded.getLast().revision());
    }
    try (var reopened = new SqliteJobQueue(db)) {
      reopened.open();
      assertEquals(upgraded, reopened.listSwitchBufferOps());
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        var statement = connection.createStatement();
        var version = statement.executeQuery("PRAGMA user_version")) {
      assertTrue(version.next());
      assertEquals(SqliteSchema.TARGET_VERSION, version.getInt(1));
    }
  }

  @Test
  void v20DatabaseMigratesLegacyRowsIntoScopedPrimaryKey() throws Exception {
    Path db = tempDir.resolve("v20-generation-migration.db");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE switch_buffer");
      statement.execute("""
          CREATE TABLE switch_buffer (
            key TEXT PRIMARY KEY, op TEXT NOT NULL, payload TEXT NOT NULL,
            last_updated INTEGER NOT NULL, revision TEXT NOT NULL,
            accepted_order INTEGER NOT NULL CHECK(accepted_order >= 0)
          )
          """);
      statement.execute("INSERT INTO switch_buffer VALUES ('same', 'DELETE', 'legacy', 1, 'r1', 1)");
      statement.execute("PRAGMA user_version = 20");
    }

    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertEquals(List.of("legacy"), queue.listSwitchBufferOpsStrict().stream()
          .map(SwitchBufferCapableQueue.SwitchBufferOp::payload).toList());
      assertTrue(queue.putSwitchBufferForGeneration("green", "same", "DELETE", "candidate"));
      assertEquals(List.of("legacy", "candidate"), queue.listSwitchBufferOpsStrict().stream()
          .map(SwitchBufferCapableQueue.SwitchBufferOp::payload).toList());
    }
  }

  @Test
  void v16FailureRollsBackRevisionColumnAndVersionTogether() throws Exception {
    Path db = v15Fixture("rollback-migration.db");
    try (var queue = new SqliteJobQueue(db, 3, null, version -> {
      if (version == 16) throw new SQLException("injected v16 failure");
    })) {
      assertThrows(SQLException.class, queue::open);
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        var statement = connection.createStatement()) {
      try (var version = statement.executeQuery("PRAGMA user_version")) {
        assertTrue(version.next());
        assertEquals(15, version.getInt(1));
      }
      try (var columns = statement.executeQuery("PRAGMA table_info(switch_buffer)")) {
        assertFalse(SqliteSchema.hasColumn(columns, "revision"));
      }
      try (var rows = statement.executeQuery("SELECT payload FROM switch_buffer ORDER BY last_updated")) {
        assertTrue(rows.next());
        assertEquals("first", rows.getString(1));
        assertTrue(rows.next());
        assertEquals("second", rows.getString(1));
        assertFalse(rows.next());
      }
    }
  }

  private Path v15Fixture(String name) throws Exception {
    Path db = tempDir.resolve(name);
    try (var queue = new SqliteJobQueue(db)) { queue.open(); }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE switch_buffer");
      statement.execute("""
          CREATE TABLE switch_buffer (
            key TEXT PRIMARY KEY, op TEXT NOT NULL, payload TEXT NOT NULL,
            last_updated INTEGER NOT NULL)
          """);
      statement.execute("INSERT INTO switch_buffer VALUES ('a', 'DELETE', 'first', 1)");
      statement.execute("INSERT INTO switch_buffer VALUES ('b', 'DELETE', 'second', 2)");
      statement.execute("PRAGMA user_version = 15");
    }
    return db;
  }
}
