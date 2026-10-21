/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

/** Reproducible SQLite fixtures; rows are generated in SQLite, never collected in Java. */
final class QueueScaleFixtures {
  private QueueScaleFixtures() {}

  static void generate(Path file, String fixture, int version) throws Exception {
    Files.createDirectories(file.getParent());
    if (Files.exists(file)) throw new IllegalArgumentException("Fixture already exists: " + file);
    long start = System.nanoTime();
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + file);
        var stmt = conn.createStatement()) {
      schema(conn, version);
      boolean history = "F2".equals(fixture);
      if (!history && !"F1".equals(fixture)) throw new IllegalArgumentException("Unknown fixture " + fixture);
      int rows = history ? 1_001_000 : 1_000_000;
      String state = history
          ? "CASE WHEN i <= 1000 THEN 'PENDING' WHEN i % 3 = 0 THEN 'FAILED' WHEN i % 3 = 1 THEN 'DONE' ELSE 'RETRY_EXHAUSTED' END"
          : "CASE WHEN i <= 700000 THEN 'PENDING' WHEN i <= 900000 THEN 'DONE' WHEN i <= 901000 THEN 'FAILED' WHEN i <= 901016 THEN 'PROCESSING' ELSE 'RETRY_EXHAUSTED' END";
      conn.setAutoCommit(false);
      stmt.executeUpdate("WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < " + rows + ") "
          + "INSERT INTO jobs(path,state,attempts,last_updated,error_message,retry_after,collection,size_bytes,scan_id,first_failed_at) "
          + "SELECT printf('/queue-scale/%07d.txt',i)," + state + ",i%4,i,'fixture failure',"
          + (history ? "NULL" : "CASE WHEN i BETWEEN 10001 AND 60000 THEN 4000000000000 ELSE NULL END")
          + ",CASE WHEN i%7=0 THEN NULL ELSE 'scale' END,CASE WHEN i%11=0 THEN NULL ELSE i*17 END,"
          + (history ? "NULL" : "CASE WHEN i<=10000 THEN 'denied-walk' ELSE NULL END") + ",NULL FROM n");
      stmt.executeUpdate("UPDATE jobs SET first_failed_at=CASE WHEN attempts>0 THEN max(1,last_updated-1) ELSE NULL END, "
          + "last_outcome_class=CASE WHEN state='DONE' THEN 'SUCCESS_FULL' WHEN attempts>0 THEN 'PARSER_FAILED' ELSE NULL END, "
          + "last_reason_code=CASE WHEN state='DONE' THEN 'SUCCESS' WHEN attempts>0 THEN 'FIXTURE_PARSE' ELSE NULL END, "
          + "last_retry_policy=CASE WHEN state='PENDING' AND attempts>0 THEN 'RETRY_WITH_BACKOFF' ELSE 'NONE' END, "
          + "last_diagnostic_summary='saved fixture detail',last_outcome_at=last_updated");
      if (version >= 22) {
        stmt.executeUpdate("UPDATE jobs SET unit_revision = printf('unit-%s',path), originator='system',transport='internal', "
            + "ingestion_root='/queue-scale',content_hash=CASE WHEN state='DONE' THEN '" + "b".repeat(64) + "' ELSE NULL END");
        if (!history) {
          stmt.executeUpdate("INSERT INTO ingestion_walk_progress(operation_key,plan_hash,enumeration_epoch) VALUES('denied-walk','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',1)");
          stmt.executeUpdate("UPDATE jobs SET walk_seen_epoch=1 WHERE scan_id='denied-walk'");
        }
      }
      conn.commit();
    }
    System.out.printf("Generated %s v%d rows=%d in %.3fs: %s%n", fixture, version,
        "F2".equals(fixture) ? 1_001_000 : 1_000_000, (System.nanoTime() - start) / 1e9, file);
  }

  /** Start from the released v0.3.0 schema, applying the real ladder through the requested source. */
  static void schema(Connection conn, int version) throws Exception {
    if (version < 13 || version > 23) throw new IllegalArgumentException("Fixture version must be 13..23");
    try (var stmt = conn.createStatement();
        var ddl = QueueScaleFixtures.class.getResourceAsStream("/queue-scale-v13.sql")) {
      if (ddl == null) throw new IllegalStateException("Missing v0.3.0 schema");
      for (String sql : new String(ddl.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
        if (!sql.isBlank()) stmt.execute(sql);
      }
      var migration = SqliteQueueMigrationOps.class.getDeclaredMethod("applyMigration", Connection.class, int.class);
      migration.setAccessible(true);
      conn.setAutoCommit(false);
      for (int v = 14; v <= version; v++) migration.invoke(null, conn, v);
      stmt.execute("PRAGMA user_version = " + version);
      conn.commit();
      conn.setAutoCommit(true);
    }
  }
}
