/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class SourceOriginMigrationTest {
  @TempDir Path directory;

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(booleans = {false, true})
  void freshAndV26QueuesHaveDurableOriginStorage(boolean upgrade) throws Exception {
    Path db = directory.resolve("jobs.db");
    if (upgrade) {
      try (var queue = new SqliteJobQueue(db)) {
        queue.open();
        queue.enqueue(List.of(directory.resolve("preserved.txt")));
      }
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
          var statement = connection.createStatement()) {
        statement.execute("DROP TABLE IF EXISTS source_origin");
        statement.execute("PRAGMA user_version=26");
      }
    }
    try (var queue = new SqliteJobQueue(db)) {
      assertDoesNotThrow(queue::open);
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
          var statement = connection.createStatement()) {
        try (var rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE name='source_origin'")) {
          assertTrue(rows.next(), "accepted declarations require durable source_origin storage");
        }
        try (var rows = statement.executeQuery("PRAGMA user_version")) {
          assertTrue(rows.next()); assertEquals(28, rows.getInt(1));
        }
      }
      assertEquals(upgrade ? 1 : 0, queue.queueDepth(), "migration must preserve queue admissions");
    }
    try (var queue = new SqliteJobQueue(db)) { assertDoesNotThrow(queue::open); }
  }

  @Test
  void failedV27MigrationRollsBackTableAndVersion() throws Exception {
    Path db = directory.resolve("rollback.db");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open(); queue.enqueue(List.of(directory.resolve("preserved.txt")));
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS source_origin");
      statement.execute("PRAGMA user_version=26");
    }
    try (var queue = new SqliteJobQueue(db, 3, () -> {}, version -> {
      if (version == 27) throw new SQLException("injected origin migration failure");
    })) {
      var failure = assertThrows(SQLException.class, queue::open, "the v27 migration hook must run");
      assertTrue(failure.getMessage().contains("injected origin migration failure"));
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement()) {
      try (var rows = statement.executeQuery("PRAGMA user_version")) {
        assertTrue(rows.next()); assertEquals(26, rows.getInt(1));
      }
      try (var rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE name='source_origin'")) {
        assertFalse(rows.next(), "transactional DDL must roll back");
      }
      try (var rows = statement.executeQuery("SELECT count(*) FROM jobs")) {
        assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
      }
    }
  }
}
