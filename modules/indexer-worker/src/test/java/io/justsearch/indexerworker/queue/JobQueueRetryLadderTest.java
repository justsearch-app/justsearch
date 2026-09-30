/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.ingest.IngestionRetryLadder;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 885 item 21a/21b/21c — the completed failure ladder.
 *
 * <p>Each case names the pre-item behaviour it inverts, because that is what makes the assertion
 * precise rather than merely green: before this item, three I/O failures produced a permanent
 * {@code FAILED}, the backoff capped at ~17 minutes, and {@code error_message} held a fixed
 * literal.
 */
@DisplayName("Job queue retry ladder (885 item 21)")
final class JobQueueRetryLadderTest {

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
  @DisplayName("three IO_FAILED outcomes stay PENDING with retry_after set, past the attempts cap")
  void transientFailuresDoNotCountAgainstTheAttemptsCap() throws Exception {
    Path file = tempDir.resolve("flaky.txt");
    Files.writeString(file, "content");
    String normalized = PathNormalizer.normalizePath(file.toAbsolutePath().toString());

    // Enqueued ONCE. The enqueue statement is INSERT OR REPLACE, so re-enqueueing inside the loop
    // would reset attempts and first_failed_at on every iteration — the run would never accumulate,
    // and the test would stay green even if the attempts cap were restored on the transient arm.
    jobQueue.enqueue(List.of(file));

    // Three failures is exactly DEFAULT_MAX_ATTEMPTS — the number that used to mean "permanent".
    for (int i = 0; i < SqliteJobQueue.DEFAULT_MAX_ATTEMPTS; i++) {
      // Return the row to PENDING the way the reaper does, without touching the failure run.
      setStateViaJdbc(normalized, "PENDING");
      jobQueue.pollPending(1);
      jobQueue.markFailed(file, ioFailure("stat failed on attempt " + (i + 1)));
    }

    JobRow row = readRow(normalized);
    assertEquals(
        "PENDING",
        row.state(),
        "a transient outcome must keep retrying past the attempts cap, not become FAILED");
    assertNotNull(row.retryAfter(), "a PENDING retry must carry a scheduled time");
    assertTrue(row.retryAfter() > System.currentTimeMillis(), "the retry is in the future");
    // The exact count is what makes this test falsifiable: three accumulated failures is the cap,
    // so a restored cap on the transient arm turns the state assertion above red.
    assertEquals(
        SqliteJobQueue.DEFAULT_MAX_ATTEMPTS,
        row.attempts(),
        "attempts is still counted for display, and the run really did accumulate three");
    assertNotNull(row.firstFailedAt(), "the failure run must have an origin for the 7-day bound");
  }

  @Test
  @DisplayName("the backoff ladder runs 1 min, 10 min, 1 h, 6 h, 24 h — not a 17-minute cap")
  void transientBackoffFollowsTheLadder() throws Exception {
    Path file = tempDir.resolve("ladder.txt");
    Files.writeString(file, "content");
    String normalized = PathNormalizer.normalizePath(file.toAbsolutePath().toString());
    jobQueue.enqueue(List.of(file));

    long[] expected = {60_000L, 600_000L, 3_600_000L, 21_600_000L, 86_400_000L, 86_400_000L};
    for (int i = 0; i < expected.length; i++) {
      setStateViaJdbc(normalized, "PENDING");
      jobQueue.pollPending(1);
      long before = System.currentTimeMillis();
      jobQueue.markFailed(file, ioFailure("boom " + i));
      JobRow row = readRow(normalized);
      assertEquals("PENDING", row.state(), "failure " + (i + 1) + " must stay retryable");
      long delay = row.retryAfter() - before;
      assertTrue(
          delay >= expected[i] && delay <= expected[i] + 2_000L,
          "failure " + (i + 1) + " should back off ~" + expected[i] + "ms, was " + delay);
    }
  }

  @Test
  @DisplayName("a parser failure is terminal on the first attempt")
  void parserFailureIsTerminalImmediately() throws Exception {
    Path file = tempDir.resolve("corrupt.pdf");
    Files.writeString(file, "not really a pdf");
    String normalized = PathNormalizer.normalizePath(file.toAbsolutePath().toString());

    jobQueue.enqueue(List.of(file));
    jobQueue.pollPending(1);
    jobQueue.markFailed(
        file,
        IngestionOutcome.of(
            IngestionOutcomeClass.PARSER_FAILED,
            IngestionReasonCodes.PARSER_FAILED,
            IngestionRetryPolicy.NONE,
            "TikaException: Unexpected RuntimeException from PDFParser"));

    JobRow row = readRow(normalized);
    assertEquals("FAILED", row.state(), "a NONE-policy outcome fails on the first attempt");
    assertEquals(1, row.attempts());
    assertNull(row.retryAfter(), "a terminal failure schedules no retry");
  }

  @Test
  @DisplayName("past the 7-day bound the job becomes RETRY_EXHAUSTED, and a rescan resets it")
  void sevenDayBoundExhaustsAndRescanResets() throws Exception {
    Path file = tempDir.resolve("unreachable.txt");
    Files.writeString(file, "on a network share");
    String normalized = PathNormalizer.normalizePath(file.toAbsolutePath().toString());

    jobQueue.enqueue(List.of(file));
    jobQueue.pollPending(1);
    jobQueue.markFailed(file, ioFailure("share offline"));
    assertEquals("PENDING", readRow(normalized).state(), "the first failure always retries");

    // Age the failure run past the window. Rewriting first_failed_at rather than sleeping is the
    // only way to reach a seven-day boundary in a unit test; everything downstream of it is the
    // real code path.
    long eightDaysAgo =
        System.currentTimeMillis() - IngestionRetryLadder.MAX_RETRY_WINDOW_MS - 86_400_000L;
    setFirstFailedAtViaJdbc(normalized, eightDaysAgo);
    setStateViaJdbc(normalized, "PENDING");
    jobQueue.pollPending(1);
    jobQueue.markFailed(file, ioFailure("share still offline"));

    JobRow exhausted = readRow(normalized);
    assertEquals(
        "RETRY_EXHAUSTED",
        exhausted.state(),
        "a failure run that outlives the 7-day window must reach a VISIBLE terminal state");
    assertNull(exhausted.retryAfter(), "an exhausted job schedules no further retry");

    // An exhausted job is not claimable — it must not silently rejoin the queue.
    assertTrue(jobQueue.pollPending(10).isEmpty(), "RETRY_EXHAUSTED is not PENDING");

    // ... and it counts as failed everywhere a failure is counted.
    assertEquals(1L, jobQueue.failureSummary().failedCount(), "exhausted counts as failed");
    assertEquals(1, jobQueue.listFailedJobs(10).size(), "exhausted is listed as a failed job");

    // The rescan. WorkerScanOps enqueues every admitted file; the enqueue statement is
    // INSERT OR REPLACE, so this is the reset.
    jobQueue.enqueue(List.of(file));

    JobRow reset = readRow(normalized);
    assertEquals("PENDING", reset.state(), "a rescan must revive an exhausted job");
    assertEquals(0, reset.attempts(), "the attempt count restarts");
    assertNull(reset.retryAfter(), "no stale backoff survives the reset");
    assertNull(reset.firstFailedAt(), "the 7-day window restarts from the next failure");
    assertFalse(jobQueue.pollPending(10).isEmpty(), "the revived job is claimable again");
  }

  @Test
  @DisplayName("a retry reports the state it actually replaced, not a hardcoded FAILED")
  void reenqueueReportsTheStateItReplaced() throws Exception {
    // 885 §UD open item 1: WorkerIngestService.retryIndexingJob stated setPreviousState("FAILED")
    // without ever reading the row — wrong for a PENDING-in-backoff job before this item, and wrong
    // for RETRY_EXHAUSTED after it. Every case below is a state that literal misreported.

    // (1) RETRY_EXHAUSTED — the state item 21b introduced, and the reason this is worth fixing.
    Path exhausted = tempDir.resolve("exhausted.txt");
    Files.writeString(exhausted, "on a network share");
    String exhaustedNorm = PathNormalizer.normalizePath(exhausted.toAbsolutePath().toString());
    jobQueue.enqueue(List.of(exhausted));
    jobQueue.pollPending(1);
    jobQueue.markFailed(exhausted, ioFailure("share offline"));
    setFirstFailedAtViaJdbc(
        exhaustedNorm,
        System.currentTimeMillis() - IngestionRetryLadder.MAX_RETRY_WINDOW_MS - 86_400_000L);
    setStateViaJdbc(exhaustedNorm, "PENDING");
    jobQueue.pollPending(1);
    jobQueue.markFailed(exhausted, ioFailure("share still offline"));
    assertEquals("RETRY_EXHAUSTED", readRow(exhaustedNorm).state(), "precondition");

    JobQueue.ReenqueueResult exhaustedResult =
        jobQueue.reenqueue(JobQueue.EnqueueEntry.stat(exhausted));
    assertEquals(1, exhaustedResult.accepted());
    assertEquals("RETRY_EXHAUSTED", exhaustedResult.previousState());
    JobRow revived = readRow(exhaustedNorm);
    assertEquals("PENDING", revived.state(), "the retry still revives the row");
    assertEquals(0, revived.attempts(), "and still resets the failure run");
    assertNull(revived.firstFailedAt());

    // (2) FAILED — the one case the old literal happened to get right.
    Path failed = tempDir.resolve("failed.txt");
    Files.writeString(failed, "content");
    String failedNorm = PathNormalizer.normalizePath(failed.toAbsolutePath().toString());
    jobQueue.enqueue(List.of(failed));
    jobQueue.pollPending(1);
    jobQueue.markFailed(
        failed,
        IngestionOutcome.of(
            IngestionOutcomeClass.PARSER_FAILED,
            IngestionReasonCodes.PARSER_FAILED,
            IngestionRetryPolicy.NONE,
            "unparseable"));
    assertEquals("FAILED", readRow(failedNorm).state(), "precondition");
    assertEquals("FAILED", jobQueue.reenqueue(JobQueue.EnqueueEntry.stat(failed)).previousState());

    // (3) PENDING in backoff — a job the user retries early because it is waiting, not broken.
    Path backoff = tempDir.resolve("backoff.txt");
    Files.writeString(backoff, "content");
    String backoffNorm = PathNormalizer.normalizePath(backoff.toAbsolutePath().toString());
    jobQueue.enqueue(List.of(backoff));
    jobQueue.pollPending(1);
    jobQueue.markFailed(backoff, ioFailure("transient"));
    JobRow waiting = readRow(backoffNorm);
    assertEquals("PENDING", waiting.state(), "precondition");
    assertNotNull(waiting.retryAfter(), "precondition: it is waiting on a backoff");
    JobQueue.ReenqueueResult backoffResult =
        jobQueue.reenqueue(JobQueue.EnqueueEntry.stat(backoff));
    assertEquals("PENDING", backoffResult.previousState());
    assertNull(readRow(backoffNorm).retryAfter(), "the retry clears the backoff");

    // (4) No row at all — "cannot say", not a guessed state name.
    Path unknown = tempDir.resolve("never-queued.txt");
    Files.writeString(unknown, "content");
    JobQueue.ReenqueueResult unknownResult =
        jobQueue.reenqueue(JobQueue.EnqueueEntry.stat(unknown));
    assertEquals(1, unknownResult.accepted(), "a path with no row is still enqueued");
    assertNull(unknownResult.previousState(), "there was no previous state to report");
  }

  @Test
  @DisplayName("an exhausted row counts as failed in BOTH count projections")
  void exhaustedRowCountsAsFailedInEveryProjection() throws Exception {
    Path file = tempDir.resolve("both-projections.txt");
    Files.writeString(file, "content");
    String normalized = PathNormalizer.normalizePath(file.toAbsolutePath().toString());

    jobQueue.enqueue(List.of(file));
    jobQueue.pollPending(1);
    jobQueue.markFailed(file, ioFailure("share offline"));
    setFirstFailedAtViaJdbc(
        normalized,
        System.currentTimeMillis() - IngestionRetryLadder.MAX_RETRY_WINDOW_MS - 86_400_000L);
    setStateViaJdbc(normalized, "PENDING");
    jobQueue.pollPending(1);
    jobQueue.markFailed(file, ioFailure("share still offline"));
    assertEquals("RETRY_EXHAUSTED", readRow(normalized).state());

    // The two projections are computed by DIFFERENT queries in different classes
    // (SqliteJobQueue.failureSummary vs SqliteQueueSwitchBufferOps.stateCounts). Widening one and
    // not the other let them disagree about the same row, which is the defect this pins.
    assertEquals(1L, jobQueue.failureSummary().failedCount(), "failureSummary counts it failed");
    assertEquals(
        1L,
        jobQueue.jobStateCounts().failedCount(),
        "jobStateCounts must agree with failureSummary about the same row");
    assertEquals(
        0L, jobQueue.jobStateCounts().pendingCount(), "an exhausted row is not pending");
    assertEquals(0L, jobQueue.queueDepth(), "an exhausted row is not in the queue depth");
  }

  @Test
  @DisplayName("listFailedJobs reports each row's real terminal state, not a blanket FAILED")
  void failedJobListingDistinguishesTheTwoTerminalStates() throws Exception {
    Path parsed = tempDir.resolve("unparseable.pdf");
    Path unread = tempDir.resolve("unreadable.txt");
    Files.writeString(parsed, "not a pdf");
    Files.writeString(unread, "on a dead share");
    String unreadNorm = PathNormalizer.normalizePath(unread.toAbsolutePath().toString());

    // One genuinely unparseable file -> FAILED on the first attempt.
    jobQueue.enqueue(List.of(parsed));
    jobQueue.pollPending(1);
    jobQueue.markFailed(
        parsed,
        IngestionOutcome.of(
            IngestionOutcomeClass.PARSER_FAILED,
            IngestionReasonCodes.PARSER_FAILED,
            IngestionRetryPolicy.NONE,
            "TikaException"));

    // One file we never managed to read -> RETRY_EXHAUSTED after the window.
    jobQueue.enqueue(List.of(unread));
    jobQueue.pollPending(1);
    jobQueue.markFailed(unread, ioFailure("share offline"));
    setFirstFailedAtViaJdbc(
        unreadNorm,
        System.currentTimeMillis() - IngestionRetryLadder.MAX_RETRY_WINDOW_MS - 86_400_000L);
    setStateViaJdbc(unreadNorm, "PENDING");
    jobQueue.pollPending(1);
    jobQueue.markFailed(unread, ioFailure("share still offline"));

    var byState = new java.util.HashMap<String, String>();
    for (JobQueue.FailedJobInfo j : jobQueue.listFailedJobs(10)) {
      byState.put(j.state(), j.path());
    }
    assertEquals(
        java.util.Set.of("FAILED", "RETRY_EXHAUSTED"),
        byState.keySet(),
        "both terminal states must be listed, and distinguishable — the failure API used to"
            + " hardcode FAILED for every row, relabelling a week of retries as a parse failure");
    assertTrue(byState.get("RETRY_EXHAUSTED").endsWith("unreadable.txt"));
    assertTrue(byState.get("FAILED").endsWith("unparseable.pdf"));
  }

  @Test
  @DisplayName("error_message carries the exception text, not a fixed literal")
  void errorMessageCarriesTheExceptionText() throws Exception {
    Path file = tempDir.resolve("detail.txt");
    Files.writeString(file, "content");
    String normalized = PathNormalizer.normalizePath(file.toAbsolutePath().toString());

    jobQueue.enqueue(List.of(file));
    jobQueue.pollPending(1);
    jobQueue.markFailed(
        file,
        IngestionOutcome.of(
            IngestionOutcomeClass.SANDBOX_FAILED,
            IngestionReasonCodes.SANDBOX_FAILED,
            IngestionRetryPolicy.RETRY_WITH_BACKOFF,
            "SandboxExtractionException: Sandbox child exited with code 137: OOM killed"));

    String message = readErrorMessage(normalized);
    assertNotNull(message);
    assertTrue(
        message.contains("exited with code 137"),
        "the child exit code must survive to the database, was: " + message);
    assertFalse(message.equals("Sandbox failed"), "the fixed literal must be gone");
  }

  @Test
  @DisplayName("the untyped markFailed path still honours the attempts cap")
  void untypedFailurePathKeepsTheAttemptsCap() throws Exception {
    Path file = tempDir.resolve("legacy.txt");
    Files.writeString(file, "content");
    String normalized = PathNormalizer.normalizePath(file.toAbsolutePath().toString());
    jobQueue.enqueue(List.of(file));

    for (int i = 0; i < SqliteJobQueue.DEFAULT_MAX_ATTEMPTS; i++) {
      setStateViaJdbc(normalized, "PENDING");
      jobQueue.pollPending(1);
      jobQueue.markFailed(file, "untyped failure " + i);
    }

    assertEquals(
        "FAILED",
        readRow(normalized).state(),
        "the untyped path has no outcome class to classify on, so the cap is all it has");
  }

  @Test
  @DisplayName("the queue meters count admissions and claims")
  void throughputMetersRecordEnqueueAndDequeue() throws Exception {
    Path a = tempDir.resolve("a.txt");
    Path b = tempDir.resolve("b.txt");
    Files.writeString(a, "a");
    Files.writeString(b, "b");

    assertEquals(0L, jobQueue.throughputMeters().enqueuedTotal());
    jobQueue.enqueue(List.of(a, b));
    assertEquals(2L, jobQueue.throughputMeters().enqueuedTotal());
    assertEquals(2L, jobQueue.throughputMeters().enqueueRatePerMinute());

    jobQueue.pollPending(10);
    assertEquals(2L, jobQueue.throughputMeters().dequeuedTotal());
    assertEquals(2L, jobQueue.throughputMeters().dequeueRatePerMinute());
    assertTrue(jobQueue.throughputMeters().lockWaitMaxMs() >= 0L);
  }

  @Test
  @DisplayName("the per-outcome observer is notified with the outcome class name")
  void outcomeObserverSeesTheOutcomeClass() throws Exception {
    Path file = tempDir.resolve("observed.txt");
    Files.writeString(file, "content");
    List<String> seen = new java.util.ArrayList<>();
    jobQueue.setOutcomeObserver(seen::add);

    jobQueue.enqueue(List.of(file));
    jobQueue.pollPending(1);
    jobQueue.markFailed(file, ioFailure("boom"));

    assertEquals(List.of("IO_FAILED"), seen);
  }

  // ---------------------------------------------------------------- helpers

  private static IngestionOutcome ioFailure(String detail) {
    return IngestionOutcome.of(
        IngestionOutcomeClass.IO_FAILED,
        IngestionReasonCodes.IO_ERROR,
        IngestionRetryPolicy.RETRY_WITH_BACKOFF,
        detail);
  }

  private record JobRow(String state, int attempts, Long retryAfter, Long firstFailedAt) {}

  private JobRow readRow(String path) throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        PreparedStatement stmt =
            conn.prepareStatement(
                "SELECT state, attempts, retry_after, first_failed_at FROM jobs WHERE path = ?")) {
      stmt.setString(1, path);
      try (ResultSet rs = stmt.executeQuery()) {
        assertTrue(rs.next(), "no row for " + path);
        String state = rs.getString(1);
        int attempts = rs.getInt(2);
        long retryAfter = rs.getLong(3);
        Long retry = rs.wasNull() ? null : retryAfter;
        long firstFailed = rs.getLong(4);
        Long first = rs.wasNull() ? null : firstFailed;
        return new JobRow(state, attempts, retry, first);
      }
    }
  }

  private String readErrorMessage(String path) throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        PreparedStatement stmt =
            conn.prepareStatement("SELECT error_message FROM jobs WHERE path = ?")) {
      stmt.setString(1, path);
      try (ResultSet rs = stmt.executeQuery()) {
        assertTrue(rs.next());
        return rs.getString(1);
      }
    }
  }

  private void setStateViaJdbc(String path, String state) throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        PreparedStatement stmt =
            conn.prepareStatement(
                "UPDATE jobs SET state = ?, retry_after = NULL WHERE path = ?")) {
      stmt.setString(1, state);
      stmt.setString(2, path);
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
