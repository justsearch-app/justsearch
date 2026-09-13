/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
      assertEquals(16, version.getInt(1));
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
