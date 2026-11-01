/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Q12/Q13: one generated million-row file, reused in place; never copied or backed up.
 * Set QUEUE_SCALE_MAIN_ROOT to a shared checkout of main V23 and select this class with
 * :modules:indexer-worker:queueScaleEvidence --tests '*QueueScaleV24MigrationEvidenceTest'.
 */
@Tag("evidence")
@Timeout(value = 10, unit = TimeUnit.MINUTES)
final class QueueScaleV24MigrationEvidenceTest {
  @Test
  void v13AndMainV23MigrateToV24WithCrashReopenAndOlderBinaryRefusal() throws Exception {
    assertNotNull(System.getenv("QUEUE_SCALE_MAIN_ROOT"), "Set QUEUE_SCALE_MAIN_ROOT to main's V23 source checkout");
    Path output = Path.of(System.getProperty("queue.scale.output"));
    Files.createDirectories(output);
    Path db = output.resolve("Q12-v13-main-v23-to-v24.db");
    try {
      QueueScaleFixtures.generate(db, "F1", 13);
      for (int source : List.of(13, 23)) {
        if (source == 23) {
          // Keep all rows and main's actual V23 objects, removing only the V24 projection.
          try (var conn = connect(db); var stmt = conn.createStatement()) {
            for (String trigger : List.of("insert", "update", "delete")) {
              stmt.execute("DROP TRIGGER jobs_counts_" + trigger);
            }
            stmt.execute("DROP TABLE job_counts");
            for (String index : List.of("state_retry", "pending_ready", "pending_retry")) {
              stmt.execute("DROP INDEX idx_jobs_" + index);
            }
            stmt.execute("UPDATE switch_buffer_admissions SET count=41");
            stmt.execute("PRAGMA user_version=23");
          }
        }
        List<String> columns;
        String before;
        List<String> expectedClaims = new ArrayList<>();
        try (var conn = connect(db); var stmt = conn.createStatement()) {
          columns = columns(conn);
          before = digest(conn, columns);
          assertEquals(source, version(conn));
          try(var rows=stmt.executeQuery("SELECT COUNT(*) FROM jobs")) { assertTrue(rows.next());assertEquals(1_000_000,rows.getLong(1)); }
          try (var rows = stmt.executeQuery("SELECT path FROM jobs WHERE state='PENDING' "
              + "AND (retry_after IS NULL OR retry_after<=strftime('%s','now')*1000) "
              + "ORDER BY last_updated,path LIMIT 16")) {
            while (rows.next()) expectedClaims.add(rows.getString(1));
          }
        }
        Process crash = new ProcessBuilder(javaTool("java"), "-Xmx1g",
            "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"), "-cp",
            System.getProperty("queue.scale.classpath"), getClass().getName(), db.toString())
            .redirectErrorStream(true).redirectOutput(output.resolve("Q12-v" + source + "-v24-crash.log").toFile()).start();
        try {
          assertTrue(crash.waitFor(60, TimeUnit.SECONDS), "migration child timed out");
          assertEquals(74, crash.exitValue(), "must crash after V24 DDL, inside the transaction");
        } finally {
          if (crash.isAlive()) {
            crash.destroyForcibly();
            assertTrue(crash.waitFor(10, TimeUnit.SECONDS));
          }
        }
        try (var conn = connect(db); var stmt = conn.createStatement()) {
          assertEquals(source, version(conn));
          assertEquals(before, digest(conn, columns), "crash changed saved fields");
          try (var rows = stmt.executeQuery("SELECT name FROM sqlite_master WHERE name='job_counts'")) {
            assertFalse(rows.next(), "crash must roll back V24 DDL");
          }
          long start = System.nanoTime();
          // Exercise the production transaction ladder without its backup callback: the small
          // migration suite checks backups; this measurement has a one-large-file disk budget.
          SqliteQueueMigrationOps.runMigrations(conn, () -> {}, null);
          double seconds = (System.nanoTime() - start) / 1e9;
          System.out.printf("Q12 v%d -> V24 1M-row migration=%.3fs; crash exit=74, rollback version=%d%n", source, seconds, source);
          assertTrue(seconds < 60, "migration exceeds 60s");
          assertEquals(26, version(conn)); // V24 through V26 share the migration transaction
          assertEquals(before, digest(conn, columns), "migration changed saved fields");
        }
        try (var queue = attachMigratedQueue(db)) {
          var conn = JobCountsTest.connection(queue);
          JobCountsTest.assertExact(queue, conn);
          assertEquals(queue.jobStateCountsStrict(), queue.jobStateCounts());
          try (var stmt = conn.createStatement(); var rows = stmt.executeQuery(
              "SELECT COUNT(*) FROM jobs WHERE state='PENDING' AND (retry_after IS NULL OR retry_after<=strftime('%s','now')*1000)")) {
            assertTrue(rows.next()); assertEquals(rows.getLong(1),queue.jobStateCountsStrict().pendingReadyCount());
          }
          try (var stmt = conn.createStatement(); var rows = stmt.executeQuery(
              "SELECT COUNT(*),SUM(size_bytes),SUM(size_bytes IS NULL) FROM jobs WHERE state IN ('PENDING','PROCESSING')")) {
            assertTrue(rows.next());
            assertEquals(rows.getLong(1), queue.queueDepth());
            assertEquals(new JobQueue.PendingBytes(rows.getLong(2), rows.getLong(3)), queue.pendingBytes());
          }
          assertEquals(source == 23 ? 41 : 0, queue.switchBufferAdmissions());
          var actualClaims = new ArrayList<String>();
          for (int poll = 0; poll < 48 && actualClaims.size() < 16; poll++) {
            actualClaims.addAll(queue.pollPending(16 - actualClaims.size()).stream()
                .map(job -> job.path().toString()).toList());
          }
          assertEquals(expectedClaims, actualClaims);
          System.out.printf("Q12 v%d status/counts/bytes/saved fields and ordered 16 claims exact%n", source);
        }
        long reopenStart = System.nanoTime();
        try (var queue = attachMigratedQueue(db)) {
          JobCountsTest.assertExact(queue, JobCountsTest.connection(queue));
          assertEquals(source == 23 ? 41 : 0, queue.switchBufferAdmissions());
          double seconds = (System.nanoTime() - reopenStart) / 1e9;
          System.out.printf("Q12 v%d -> V24 idempotent reopen=%.3fs; counts and admission fence exact%n", source, seconds);
          assertTrue(seconds < 60);
        }
      }
      verifyOlderBinaryRefuses(db, output);
      assertFalse(Files.exists(Path.of(db + ".bak")), "large fixture must never be backed up");
    } finally {
      for (String suffix : List.of("-wal", "-shm", "-journal", "")) Files.deleteIfExists(Path.of(db + suffix));
    }
  }

  private static SqliteJobQueue attachMigratedQueue(Path db) throws Exception {
    // Normal open makes a defensive compatibility copy, forbidden by this evidence run's
    // one-large-file budget. Run the exact migration/reconciliation entry point, then attach
    // its connection to the real queue for status/claim assertions. Small default tests cover
    // the complete public open/backup/refusal lifecycle for every supported source version.
    Connection conn = connect(db);
    try {
      SqliteQueueMigrationOps.runMigrations(conn, () -> { throw new AssertionError("already migrated"); }, null);
      var queue = new SqliteJobQueue(db);
      var field = SqliteJobQueue.class.getDeclaredField("connection");
      field.setAccessible(true); field.set(queue,conn);
      return queue;
    } catch(Exception | Error failure) { conn.close(); throw failure; }
  }

  private static void verifyOlderBinaryRefuses(Path db, Path output) throws Exception {
    Path source = Path.of(System.getenv("QUEUE_SCALE_MAIN_ROOT"));
    Path older = output.resolve("main-v23-classes");
    Files.createDirectories(older);
    Path packagePath = Path.of("modules/indexer-worker/src/main/java/io/justsearch/indexerworker/queue");
    Path compilerLog = output.resolve("Q13-main-v23-compile.log");
    Process compiler = new ProcessBuilder(javaTool("javac"), "-cp", System.getProperty("queue.scale.classpath"),
        "-d", older.toString(), source.resolve(packagePath).resolve("SqliteSchema.java").toString(),
        source.resolve(packagePath).resolve("SqliteQueueMigrationOps.java").toString())
        .redirectErrorStream(true).redirectOutput(compilerLog.toFile()).start();
    awaitSuccess(compiler);
    Path log = output.resolve("Q13-main-v23-refuses-v24.log");
    Process probe = new ProcessBuilder(javaTool("java"), "-Xmx1g",
        "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"), "-cp",
        older + java.io.File.pathSeparator + System.getProperty("queue.scale.classpath"),
        QueueScaleV24MigrationEvidenceTest.class.getName(), db.toString(), "refuse")
        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    awaitSuccess(probe);
    String text = Files.readString(log);
    assertTrue(text.contains("Unsupported jobs database schema version 26 (this binary supports through 23)"), text);
    System.out.println("Q13 " + text.strip());
  }

  private static void awaitSuccess(Process process) throws Exception {
    try {
      assertTrue(process.waitFor(60, TimeUnit.SECONDS), "child timed out");
      assertEquals(0, process.exitValue(), "child failed; see its evidence log");
    } finally {
      if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)); }
    }
  }

  private static String javaTool(String name) {
    return Path.of(System.getProperty("java.home"), "bin", name).toString();
  }

  private static Connection connect(Path db) throws Exception {
    return DriverManager.getConnection("jdbc:sqlite:" + db);
  }

  private static int version(Connection conn) throws Exception {
    try (var stmt = conn.createStatement(); var row = stmt.executeQuery("PRAGMA user_version")) {
      assertTrue(row.next()); return row.getInt(1);
    }
  }

  private static List<String> columns(Connection conn) throws Exception {
    var columns = new ArrayList<String>();
    try (var stmt = conn.createStatement(); var rows = stmt.executeQuery("PRAGMA table_info(jobs)")) {
      while (rows.next()) columns.add(rows.getString("name"));
    }
    return columns;
  }

  private static String digest(Connection conn, List<String> columns) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    try (var stmt = conn.createStatement(); var rows = stmt.executeQuery("SELECT " + String.join(",", columns) + " FROM jobs ORDER BY path")) {
      while (rows.next()) for (int i = 1; i <= columns.size(); i++) {
        String value = rows.getString(i);
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update((value == null ? "N:" : bytes.length + ":").getBytes(StandardCharsets.UTF_8));
        digest.update(bytes);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  /** Isolated child: crash the real migration transaction or run main's real version guard. */
  @SuppressWarnings("PMD.DoNotTerminateVM")
  public static void main(String[] args) throws Exception {
    try (var conn = connect(Path.of(args[0]))) {
      if (args.length == 2) {
        try {
          SqliteQueueMigrationOps.runMigrations(conn, () -> { throw new AssertionError("refusal must precede backup"); }, null);
        } catch (java.sql.SQLException expected) {
          System.out.println(expected.getMessage()); return;
        }
        throw new AssertionError("older binary accepted V24");
      }
      SqliteQueueMigrationOps.runMigrations(conn, () -> {}, version -> {
        if (version == 24) Runtime.getRuntime().halt(74);
      });
      throw new AssertionError("migration did not reach V24");
    }
  }
}
