/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task t-20261013-ef3c41, scenario S7 — a source that exists but cannot be read is retried with a
 * counted, growing back-off and no time limit, survives a restart queued, and is listed on the
 * failed-files surface as "indexed, source unreadable", distinct from a missing file. Reason codes
 * and the retry policy are named as strings, the way they are persisted, so the check compiles at
 * the revision before the change.
 */
final class UnreadableSourceRetryTest {
  private static final long DAY_MS = 86_400_000L;

  @TempDir Path tempDir;
  private SqliteJobQueue jobQueue;
  private Path dbPath;

  @BeforeEach
  void setUp() throws Exception {
    dbPath = tempDir.resolve("jobs.db");
    jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (jobQueue != null) {
      jobQueue.close();
    }
  }

  @Test
  void unreadableRediscoveryPreservesExistingFutureIoRetryWait() throws Exception {
    Path source = Files.writeString(tempDir.resolve("source.txt"), "unchanged bytes");
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Files.getFileStore(source).supportsFileAttributeView("posix"));
    var permissions = Files.getPosixFilePermissions(source);
    String hash = io.justsearch.indexerworker.loop.SourceContentHash.sha256(source);
    var stamp = Files.getLastModifiedTime(source);
    var entry = JobQueue.EnqueueEntry.stat(source).withinRoot(tempDir);
    assertEquals(1, jobQueue.enqueueEntries(List.of(entry), "docs", "accepted"));
    var claim = jobQueue.pollPending(1).getFirst();
    assertTrue(jobQueue.markClaimFailed(claim, IngestionOutcome.of(
        IngestionOutcomeClass.IO_FAILED, "source_io", IngestionRetryPolicy.RETRY_WITH_BACKOFF), null));
    var before = readFullRow();
    assertEquals("PENDING", before.get("state"));
    assertEquals("1", before.get("attempts"));
    assertTrue(Long.parseLong(before.get("retry_after")) > System.currentTimeMillis());
    try {
      Files.setPosixFilePermissions(source, java.util.Set.of());
      org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(source),
          "the test requires actual permission denial");
      org.junit.jupiter.api.Assertions.assertThrows(java.nio.file.AccessDeniedException.class,
          () -> io.justsearch.indexerworker.loop.SourceContentHash.sha256(source));
      for (int rediscovery = 0; rediscovery < 2; rediscovery++) {
        assertEquals(0, jobQueue.enqueueMaintenanceScanEntries(List.of(entry), "docs", "rediscovery"),
            "unreadable rediscovery must retain the existing retry obligation");
        assertEquals(before, readFullRow(), "every saved retry and attribution field must survive");
        assertTrue(jobQueue.pollPending(1).isEmpty(), "the future retry must remain unavailable");
      }
    } finally {
      Files.setPosixFilePermissions(source, permissions);
      assertEquals(stamp, Files.getLastModifiedTime(source));
      assertEquals(hash, io.justsearch.indexerworker.loop.SourceContentHash.sha256(source));
    }
  }

  private java.util.Map<String, String> readFullRow() throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT * FROM jobs")) {
      assertTrue(rows.next());
      var result = new java.util.TreeMap<String, String>();
      for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
        result.put(rows.getMetaData().getColumnName(column), rows.getString(column));
      }
      return result;
    }
  }

  @Test
  void backOffIsCountedAndGrowsRatherThanRetryingEverySecond() throws Exception {
    Path file = Files.writeString(tempDir.resolve("locked.docx"), "content");
    String normalized = normalize(file);
    jobQueue.enqueue(List.of(file));

    long[] expected = {60_000L, 600_000L, 3_600_000L, 21_600_000L, DAY_MS, DAY_MS};
    for (int i = 0; i < expected.length; i++) {
      setPendingViaJdbc(normalized);
      jobQueue.pollPending(1);
      long before = System.currentTimeMillis();
      jobQueue.markFailed(file, unreadable("INDEXED_SOURCE_UNREADABLE"));
      Row row = readRow(normalized);
      assertEquals("PENDING", row.state());
      assertEquals(i + 1, row.attempts(), "every retry is counted");
      long delay = row.retryAfter() - before;
      assertTrue(delay >= expected[i] && delay <= expected[i] + 2_000L,
          "retry " + (i + 1) + " should back off ~" + expected[i] + " ms, was " + delay);
    }
  }

  @Test
  void anUnreadableSourceIsNeverExhaustedAndKeepsGrowingPastTheWindow() throws Exception {
    Path file = Files.writeString(tempDir.resolve("denied.txt"), "content");
    String normalized = normalize(file);
    jobQueue.enqueue(List.of(file));
    jobQueue.pollPending(1);
    jobQueue.markFailed(file, unreadable("SOURCE_UNREADABLE"));
    // Age the failure run far past the seven-day window that exhausts ordinary I/O failures.
    setFirstFailedAtViaJdbc(normalized, System.currentTimeMillis() - 30 * DAY_MS);

    setPendingViaJdbc(normalized);
    jobQueue.pollPending(1);
    long before = System.currentTimeMillis();
    jobQueue.markFailed(file, unreadable("SOURCE_UNREADABLE"));

    Row row = readRow(normalized);
    assertEquals("PENDING", row.state(), "no time limit: never RETRY_EXHAUSTED");
    assertTrue(row.retryAfter() - before >= 600_000L,
        "past the window the retry is still backed off, not scheduled at once");
  }

  @Test
  void theFailedFilesSurfaceListsItAsIndexedSourceUnreadableAcrossARestart() throws Exception {
    Path folder = Files.createDirectory(tempDir.resolve("folder"));
    Path locked = Files.writeString(folder.resolve("locked.docx"), "content");
    Path rootless = Files.writeString(folder.resolve("on-unplugged-drive.txt"), "content");
    jobQueue.enqueue(List.of(locked, rootless));
    jobQueue.pollPending(2);
    jobQueue.markFailed(locked, unreadable("INDEXED_SOURCE_UNREADABLE"));
    jobQueue.markFailed(rootless, IngestionOutcome.of(
        IngestionOutcomeClass.IO_FAILED,
        "ROOT_UNAVAILABLE",
        untilAvailable(),
        "Folder unavailable"));

    jobQueue.close();
    jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();

    List<JobQueue.FailedJobInfo> listed =
        jobQueue.listFailedJobsByPathPrefix(folder.toString(), 100);
    assertEquals(1, listed.size(),
        "the unreadable file is listed; a root-level outage is one folder state, not file rows");
    assertEquals(normalize(locked), listed.get(0).path());
    assertEquals("INDEXED_SOURCE_UNREADABLE", listed.get(0).state());
    assertEquals(1, jobQueue.listFailedJobs(100).size());
    assertEquals(1L, jobQueue.countByPathPrefix(folder.toString()).failedCount(),
        "the folder counts it as a problem, not as work in flight");
    assertEquals("PENDING", readRow(normalize(locked)).state(), "still queued after a restart");
  }

  private static IngestionOutcome unreadable(String reasonCode) {
    return IngestionOutcome.of(
        IngestionOutcomeClass.IO_FAILED,
        reasonCode,
        untilAvailable(),
        "File not readable");
  }

  /**
   * The retry policy of a source that exists but cannot be read. Looked up by name so this check
   * also runs against a revision that has no such policy, where it fails by assertion.
   */
  private static IngestionRetryPolicy untilAvailable() {
    return assertDoesNotThrow(() -> IngestionRetryPolicy.valueOf("RETRY_UNTIL_AVAILABLE"),
        "an unreadable source needs a retry policy that is counted and has no time limit");
  }

  private static String normalize(Path file) {
    return PathNormalizer.normalizePath(file.toAbsolutePath().toString());
  }

  private record Row(String state, int attempts, Long retryAfter) {}

  private Row readRow(String path) throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        PreparedStatement stmt =
            conn.prepareStatement("SELECT state, attempts, retry_after FROM jobs WHERE path = ?")) {
      stmt.setString(1, path);
      try (ResultSet rs = stmt.executeQuery()) {
        assertTrue(rs.next(), "no row for " + path);
        String state = rs.getString(1);
        int attempts = rs.getInt(2);
        long retryAfter = rs.getLong(3);
        return new Row(state, attempts, rs.wasNull() ? null : retryAfter);
      }
    }
  }

  private void setPendingViaJdbc(String path) throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        PreparedStatement stmt =
            conn.prepareStatement(
                "UPDATE jobs SET state = 'PENDING', retry_after = NULL WHERE path = ?")) {
      stmt.setString(1, path);
      stmt.executeUpdate();
    }
  }

  private void setFirstFailedAtViaJdbc(String path, long epochMs) throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        PreparedStatement stmt =
            conn.prepareStatement("UPDATE jobs SET first_failed_at = ? WHERE path = ?")) {
      stmt.setLong(1, epochMs);
      stmt.setString(2, path);
      stmt.executeUpdate();
    }
  }
}
