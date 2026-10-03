package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for switch buffer durability and failure handling.
 *
 * <p>These tests verify:
 * <ul>
 *   <li>putSwitchBuffer returns true on successful writes</li>
 *   <li>putSwitchBuffer returns false on SQL failures (not silently swallowed)</li>
 *   <li>Invalid input is rejected with false return</li>
 * </ul>
 *
 * <p>This is critical for ACK-without-durability protection: callers must check
 * the return value and NOT acknowledge operations when putSwitchBuffer fails.
 */
final class SwitchBufferDurabilityTest {

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

  /**
   * Verify putSwitchBuffer returns true on successful write.
   */
  @Test
  void putSwitchBufferReturnsTrueOnSuccess() {
    boolean result = jobQueue.putSwitchBuffer("path:/test/file.txt", "UPSERT", "{\"data\":\"test\"}");
    assertTrue(result, "putSwitchBuffer should return true on successful write");

    // Verify data was actually written
    List<SqliteJobQueue.SwitchBufferOp> ops = jobQueue.listSwitchBufferOps();
    assertEquals(1, ops.size(), "Should have one buffered operation");
    assertEquals("path:/test/file.txt", ops.get(0).key());
    assertEquals("UPSERT", ops.get(0).op());
    assertEquals("{\"data\":\"test\"}", ops.get(0).payload());
  }

  /**
   * Verify putSwitchBuffer returns false for null key.
   */
  @Test
  void putSwitchBufferReturnsFalseForNullKey() {
    boolean result = jobQueue.putSwitchBuffer(null, "UPSERT", "{\"data\":\"test\"}");
    assertFalse(result, "putSwitchBuffer should return false for null key");
  }

  /**
   * Verify putSwitchBuffer returns false for blank key.
   */
  @Test
  void putSwitchBufferReturnsFalseForBlankKey() {
    boolean result = jobQueue.putSwitchBuffer("  ", "UPSERT", "{\"data\":\"test\"}");
    assertFalse(result, "putSwitchBuffer should return false for blank key");
  }

  /**
   * Verify putSwitchBuffer returns false for null operation.
   */
  @Test
  void putSwitchBufferReturnsFalseForNullOp() {
    boolean result = jobQueue.putSwitchBuffer("path:/test/file.txt", null, "{\"data\":\"test\"}");
    assertFalse(result, "putSwitchBuffer should return false for null op");
  }

  /**
   * Verify putSwitchBuffer returns false for null payload.
   */
  @Test
  void putSwitchBufferReturnsFalseForNullPayload() {
    boolean result = jobQueue.putSwitchBuffer("path:/test/file.txt", "UPSERT", null);
    assertFalse(result, "putSwitchBuffer should return false for null payload");
  }

  /**
   * Verify putSwitchBuffer returns false when database is in read-only mode.
   *
   * <p>This simulates a SQL failure scenario where writes cannot be performed.
   */
  @Test
  void putSwitchBufferReturnsFalseOnReadOnlyDatabase() throws Exception {
    // Close the existing queue
    jobQueue.close();

    // Open a fresh database
    Path readOnlyDbPath = tempDir.resolve("readonly.db");
    jobQueue = new SqliteJobQueue(readOnlyDbPath);
    jobQueue.open();

    // Drop the switch_buffer table to cause writes to fail
    String jdbcUrl = "jdbc:sqlite:" + readOnlyDbPath.toAbsolutePath();
    try (Connection conn = DriverManager.getConnection(jdbcUrl);
         Statement stmt = conn.createStatement()) {
      stmt.execute("DROP TABLE switch_buffer");
    }

    // Now putSwitchBuffer should fail and return false
    boolean result = jobQueue.putSwitchBuffer("path:/test/file.txt", "UPSERT", "{\"data\":\"test\"}");
    assertFalse(result, "putSwitchBuffer should return false when table is missing");
    assertThrows(IllegalStateException.class, jobQueue::listSwitchBufferOpsStrict,
        "a missing table cannot certify an empty final replay");
  }

  @Test
  void unreadableJobCountsCannotCertifyFinalDrain() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
         Statement stmt = conn.createStatement()) {
      stmt.execute("DROP TABLE jobs");
    }
    assertThrows(IllegalStateException.class, jobQueue::jobStateCountsStrict);
  }

  /**
   * Verify putSwitchBuffer can handle large payloads.
   */
  @Test
  void putSwitchBufferHandlesLargePayload() {
    // Create a large payload (1MB)
    StringBuilder sb = new StringBuilder("{\"data\":\"");
    for (int i = 0; i < 100_000; i++) {
      sb.append("abcdefghij");
    }
    sb.append("\"}");
    String largePayload = sb.toString();

    boolean result = jobQueue.putSwitchBuffer("path:/test/large.txt", "UPSERT", largePayload);
    assertTrue(result, "putSwitchBuffer should handle large payloads");

    // Verify data was actually written
    List<SqliteJobQueue.SwitchBufferOp> ops = jobQueue.listSwitchBufferOps();
    assertEquals(1, ops.size());
    assertEquals(largePayload, ops.get(0).payload(), "Large payload should be preserved");
  }

  /**
   * Verify putSwitchBuffer handles duplicate keys (INSERT OR REPLACE).
   */
  @Test
  void putSwitchBufferHandlesDuplicateKeys() {
    // First write
    boolean result1 = jobQueue.putSwitchBuffer("path:/test/file.txt", "UPSERT", "{\"version\":1}");
    assertTrue(result1);

    // Second write with same key
    boolean result2 = jobQueue.putSwitchBuffer("path:/test/file.txt", "DELETE", "{\"version\":2}");
    assertTrue(result2);

    // Should only have one entry (the latest)
    List<SqliteJobQueue.SwitchBufferOp> ops = jobQueue.listSwitchBufferOps();
    assertEquals(1, ops.size(), "Duplicate key should result in single entry");
    assertEquals("DELETE", ops.get(0).op(), "Latest operation should win");
    assertEquals("{\"version\":2}", ops.get(0).payload(), "Latest payload should win");
  }

  /**
   * Verify that draining the buffer returns entries in order.
   */
  @Test
  void drainSwitchBufferReturnsInOrder() throws Exception {
    // Replay order must survive a restart even when the timestamp cannot distinguish admissions.
    jobQueue.putSwitchBuffer("prefix:/root", "DELETE_PREFIX", "{\"order\":1}");
    jobQueue.putSwitchBuffer("path:/root/file.txt", "UPSERT", "{\"order\":2}");
    jobQueue.putSwitchBuffer("path:/other.txt", "UPSERT", "{\"order\":3}");
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
         Statement stmt = conn.createStatement()) {
      stmt.executeUpdate("UPDATE switch_buffer SET last_updated = 42");
    }
    jobQueue.close();
    jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();

    List<SqliteJobQueue.SwitchBufferOp> ops = jobQueue.listSwitchBufferOpsStrict();
    assertEquals(3, ops.size(), "Should have 3 buffered operations");

    assertEquals("{\"order\":1}", ops.get(0).payload());
    assertEquals("{\"order\":2}", ops.get(1).payload());
    assertEquals("{\"order\":3}", ops.get(2).payload());

    // Coalescing the first key is a new accepted operation, so it moves after the other keys.
    jobQueue.putSwitchBuffer("prefix:/root", "DELETE_PREFIX", "{\"order\":4}");
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
         Statement stmt = conn.createStatement()) {
      stmt.executeUpdate("UPDATE switch_buffer SET last_updated = 42");
    }
    ops = jobQueue.listSwitchBufferOpsStrict();
    assertEquals("{\"order\":2}", ops.get(0).payload());
    assertEquals("{\"order\":3}", ops.get(1).payload());
    assertEquals("{\"order\":4}", ops.get(2).payload());

    Path backup = tempDir.resolve("restored-jobs.db");
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
         Statement stmt = conn.createStatement()) {
      stmt.execute("VACUUM INTO '" + backup.toAbsolutePath().toString().replace("'", "''") + "'");
    }
    SqliteJobQueue restored = new SqliteJobQueue(backup);
    try {
      restored.open();
      var recovered = restored.listSwitchBufferOpsStrict();
      assertEquals("{\"order\":2}", recovered.get(0).payload());
      assertEquals("{\"order\":3}", recovered.get(1).payload());
      assertEquals("{\"order\":4}", recovered.get(2).payload());
    } finally {
      restored.close();
    }
  }
}
