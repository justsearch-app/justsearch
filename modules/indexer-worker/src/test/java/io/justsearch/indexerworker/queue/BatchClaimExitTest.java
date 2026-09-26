/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression coverage for exact batch-exit claim return and live-owner recovery fences. */
final class BatchClaimExitTest {
  @TempDir Path tempDir;

  private Path dbPath;
  private SqliteJobQueue queue;

  @BeforeEach
  void setUp() throws Exception {
    dbPath = tempDir.resolve("jobs.db");
    queue = new SqliteJobQueue(dbPath);
    queue.open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (queue != null) {
      queue.close();
    }
  }

  @Test
  void profilingClearRetainsIssuedOwnerUntilItsActualReturn() throws Exception {
    Path file = tempDir.resolve("reset.txt");
    queue.enqueue(List.of(file));
    var claim = queue.pollPending(1).getFirst();
    assertEquals(1, queue.clearAll());
    queue.enqueue(List.of(file));
    assertTrue(queue.pollPending(1).isEmpty(), "deletion cannot release an issued owner");
    queue.returnUnfinishedClaims(List.of(claim));
    assertEquals(file.toAbsolutePath().normalize(), queue.pollPending(1).getFirst().path());
  }

  @Test
  void returnsCurrentProcessingClaimToPendingPreservingAttemptsAndRevision() throws Exception {
    Path file = tempDir.resolve("preserve.txt");
    queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null, "scan-preserve");
    JobQueue.IndexJob claim = queue.pollPending(1).getFirst();
    QueueFacts before = facts(file);
    setAttempts(file, 2);

    queue.returnUnfinishedClaims(List.of(claim));

    QueueFacts returned = facts(file);
    assertEquals("PENDING", returned.state());
    assertEquals(2, returned.attempts());
    assertEquals(before.unitRevision(), returned.unitRevision());
    assertEquals("scan-preserve", returned.scanId());
  }

  @Test
  void equalValuedForgedClaimCannotReturnTheIssuedProcessingClaim() throws Exception {
    Path file = tempDir.resolve("forged.txt");
    queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null, "scan-forged");
    JobQueue.IndexJob claim = queue.pollPending(1).getFirst();
    JobQueue.IndexJob forged = new JobQueue.IndexJob(claim.path(), claim.collection(), claim.provenance(),
        claim.scanId(), claim.unitRevision());
    assertEquals(claim, forged, "value equality must not grant return authority");

    queue.returnUnfinishedClaims(List.of(forged));

    assertEquals("PROCESSING", facts(file).state());
    queue.returnUnfinishedClaims(List.of(claim));
    assertEquals("PENDING", facts(file).state());
  }

  @Test
  void replacementStaysPendingUntilTheOldIssuedClaimIsReturned() throws Exception {
    Path file = tempDir.resolve("replacement.txt");
    queue.enqueue(List.of(file));
    JobQueue.IndexJob oldClaim = queue.pollPending(1).getFirst();

    assertEquals("PROCESSING", queue.reenqueue(JobQueue.EnqueueEntry.ofUnknownSize(file)).previousState());
    QueueFacts replacement = facts(file);
    assertEquals("PENDING", replacement.state());
    assertNotEquals(oldClaim.unitRevision(), replacement.unitRevision());
    assertTrue(queue.pollPending(1).isEmpty(), "the replacement cannot overtake its live old claim");

    queue.returnUnfinishedClaims(List.of(oldClaim));

    JobQueue.IndexJob current = queue.pollPending(1).getFirst();
    assertEquals(replacement.unitRevision(), current.unitRevision());
  }

  @Test
  void blockedOldestReplacementDoesNotStarveAnotherEligiblePath() throws Exception {
    Path first = tempDir.resolve("a-first.txt");
    Path second = tempDir.resolve("b-second.txt");
    queue.enqueue(List.of(first, second));
    JobQueue.IndexJob oldFirst = queue.pollPending(1).getFirst();
    assertEquals(first.toAbsolutePath().normalize(), oldFirst.path());

    queue.reenqueue(JobQueue.EnqueueEntry.ofUnknownSize(first));

    JobQueue.IndexJob secondClaim = queue.pollPending(1).getFirst();
    assertEquals(second.toAbsolutePath().normalize(), secondClaim.path());
    queue.returnUnfinishedClaims(List.of(oldFirst, secondClaim));
  }

  @Test
  void liveIssuedClaimsAreSparedByBothRecoveryVariantsAndReopenRecoversOrphans() throws Exception {
    Path first = tempDir.resolve("live-first.txt");
    Path second = tempDir.resolve("live-second.txt");
    queue.enqueue(List.of(first, second));
    queue.pollPending(2);
    backdateProcessingRows(System.currentTimeMillis() - 10 * 60_000L);

    assertEquals(0, queue.recoverStuckJobs());
    assertEquals(0, queue.recoverStuckJobs(60_000L));
    assertEquals("PROCESSING", facts(first).state());
    assertEquals("PROCESSING", facts(second).state());

    queue.close();
    queue = new SqliteJobQueue(dbPath);
    queue.open();
    assertEquals(2, queue.recoverStuckJobs());
    assertEquals("PENDING", facts(first).state());
    assertEquals("PENDING", facts(second).state());
  }

  @Test
  void failedClaimReturnRollsBackAndRetainsOwnershipForRetry() throws Exception {
    Path file = tempDir.resolve("trigger-rollback.txt");
    queue.enqueue(List.of(file));
    JobQueue.IndexJob claim = queue.pollPending(1).getFirst();
    createReturnFailureTrigger();

    assertThrows(OutcomeWriteException.class,
        () -> queue.returnUnfinishedClaims(List.of(claim)));
    assertEquals("PROCESSING", facts(file).state());

    dropReturnFailureTrigger();
    queue.returnUnfinishedClaims(List.of(claim));
    assertEquals("PENDING", facts(file).state());
    assertEquals(1, queue.pollPending(1).size(), "the retained claim must be retryable after rollback");
  }

  private QueueFacts facts(Path file) throws Exception {
    try (Connection connection = connection();
        PreparedStatement statement = connection.prepareStatement(
            "SELECT state, attempts, scan_id, unit_revision FROM jobs WHERE path = ?")) {
      statement.setString(1, normalized(file));
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next(), "expected a jobs row for " + file);
        return new QueueFacts(result.getString(1), result.getInt(2), result.getString(3), result.getString(4));
      }
    }
  }

  private void setAttempts(Path file, int attempts) throws Exception {
    try (Connection connection = connection();
        PreparedStatement statement = connection.prepareStatement(
            "UPDATE jobs SET attempts = ? WHERE path = ?")) {
      statement.setInt(1, attempts);
      statement.setString(2, normalized(file));
      assertEquals(1, statement.executeUpdate());
    }
  }

  private void backdateProcessingRows(long timestamp) throws Exception {
    try (Connection connection = connection();
        PreparedStatement statement = connection.prepareStatement(
            "UPDATE jobs SET last_updated = ? WHERE state = 'PROCESSING'")) {
      statement.setLong(1, timestamp);
      assertEquals(2, statement.executeUpdate());
    }
  }

  private void createReturnFailureTrigger() throws Exception {
    try (Connection connection = connection(); Statement statement = connection.createStatement()) {
      statement.execute("""
          CREATE TRIGGER fail_batch_claim_return
          BEFORE UPDATE OF state ON jobs
          WHEN OLD.state = 'PROCESSING' AND NEW.state = 'PENDING'
          BEGIN SELECT RAISE(ABORT, 'batch claim return failure'); END
          """);
    }
  }

  private void dropReturnFailureTrigger() throws Exception {
    try (Connection connection = connection(); Statement statement = connection.createStatement()) {
      statement.execute("DROP TRIGGER fail_batch_claim_return");
    }
  }

  private Connection connection() throws Exception {
    return DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
  }

  private static String normalized(Path file) {
    return PathNormalizer.normalizePath(file.toAbsolutePath().toString());
  }

  private record QueueFacts(String state, int attempts, String scanId, String unitRevision) {}
}
