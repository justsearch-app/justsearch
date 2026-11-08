/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FrozenQueueSchemaAdmissionTest {
  @TempDir Path directory;

  @Test void acceptsTheOwnersExplicitAndImplicitNullDefaultsWithoutRewritingSchema() throws Exception {
    Path database = directory.resolve("jobs.db");
    try (var queue = new SqliteJobQueue(database)) { queue.open(); }
    try (var mode = frozen(); var queue = new SqliteJobQueue(database)) {
      queue.open(); assertEquals(0, queue.queueDepth());
    }
    // The v10 migration owns DEFAULT NULL; fresh base CREATE uses implicit NULL instead.
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement()) {
      statement.execute("ALTER TABLE jobs DROP COLUMN first_failed_at");
      statement.execute(SqliteSchema.MIGRATE_V9_TO_V10_ADD_FIRST_FAILED_AT);
    }
    String before = schema(database);
    try (var mode = frozen(); var queue = new SqliteJobQueue(database)) {
      queue.open(); assertEquals(0, queue.queueDepth());
    }
    assertEquals(before, schema(database));
  }

  @Test void oldFutureAndCorruptQueuesRefuseWithoutOpeningOrRepair() throws Exception {
    Path database = directory.resolve("jobs.db");
    try (var queue = new SqliteJobQueue(database)) { queue.open(); }
    for (int version : new int[] {SqliteSchema.TARGET_VERSION - 1, SqliteSchema.TARGET_VERSION + 1}) {
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
          var statement = connection.createStatement()) { statement.execute("PRAGMA user_version=" + version); }
      byte[] before = Files.readAllBytes(database);
      try (var mode = frozen(); var queue = new SqliteJobQueue(database)) { assertThrows(Exception.class, queue::open); }
      assertArrayEquals(before, Files.readAllBytes(database));
    }
    Files.writeString(database, "not a SQLite database");
    byte[] before = Files.readAllBytes(database);
    try (var mode = frozen(); var queue = new SqliteJobQueue(database)) { assertThrows(Exception.class, queue::open); }
    assertArrayEquals(before, Files.readAllBytes(database));
  }

  static String schema(Path database) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement();
        var row = statement.executeQuery("SELECT sql FROM sqlite_master WHERE name='jobs'")) {
      assertTrue(row.next()); return row.getString(1);
    }
  }

  static AutoCloseable frozen() {
    String eval = System.getProperty("justsearch.eval.mode"), frozen = System.getProperty("justsearch.eval.frozen");
    System.setProperty("justsearch.eval.mode", "true"); System.setProperty("justsearch.eval.frozen", "true");
    return () -> {
      if (eval == null) System.clearProperty("justsearch.eval.mode"); else System.setProperty("justsearch.eval.mode", eval);
      if (frozen == null) System.clearProperty("justsearch.eval.frozen"); else System.setProperty("justsearch.eval.frozen", frozen);
    };
  }
}
