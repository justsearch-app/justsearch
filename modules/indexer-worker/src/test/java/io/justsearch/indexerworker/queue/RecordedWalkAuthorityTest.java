/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** SQLite proof for constructor-bound authorization of recorded walk membership. */
final class RecordedWalkAuthorityTest {
  private static final String PLAN = "a".repeat(64);
  private static final String KEY = "01994180-0000-7000-8000-000000000401";
  private static final String SECOND_KEY = "01994180-0000-7000-8000-000000000402";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void noOwnerDeniesPendingAndBothProcessingRecoveryEntryPoints(@TempDir Path temp)
      throws Exception {
    Path db = temp.resolve("no-owner.db");
    Path file = temp.resolve("pending.txt");
    seedProcessing(db, KEY, file);
    backdate(db, file);

    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertEquals(0, queue.recoverStuckJobs(), "default authority denies unconditional recovery");
      assertEquals(0, queue.recoverStuckJobs(0), "default authority denies aged recovery");
      assertEquals(0, queue.pollPending(1).size(), "denied processing row must stay fenced");
      assertEquals("PROCESSING", state(db, file));
    }

    Path pendingDb = temp.resolve("no-owner-pending.db");
    try (var queue = new SqliteJobQueue(pendingDb)) {
      queue.open();
      admit(queue, KEY, temp.resolve("pending-only.txt"));
      assertTrue(queue.pollPending(1).isEmpty(),
          "default authority denies recorded pending claims");
    }
  }

  @Test
  void falseAndThrowingAuthoritiesDenyPendingAndProcessingRecovery(@TempDir Path temp)
      throws Exception {
    Path falseDb = temp.resolve("false.db");
    Path falseFile = temp.resolve("false.txt");
    try (var queue = new SqliteJobQueue(falseDb, ignored -> JobQueue.RecordedClaimDecision.DENY)) {
      queue.open();
      admit(queue, KEY, falseFile);
      assertTrue(queue.pollPending(1).isEmpty(), "false authority denies pending claim");
    }
    seedProcessing(temp.resolve("false-recovery.db"), KEY, temp.resolve("false-recovery.txt"));
    backdate(temp.resolve("false-recovery.db"), temp.resolve("false-recovery.txt"));
    try (var queue = new SqliteJobQueue(temp.resolve("false-recovery.db"), ignored -> JobQueue.RecordedClaimDecision.DENY)) {
      queue.open();
      assertEquals(0, queue.recoverStuckJobs(), "false authority denies unconditional recovery");
      assertEquals(0, queue.recoverStuckJobs(0), "false authority denies aged recovery");
    }

    Path throwDb = temp.resolve("throwing.db");
    Path throwFile = temp.resolve("throwing.txt");
    try (var queue = new SqliteJobQueue(throwDb, ignored -> {
      throw new IllegalStateException("offline");
    })) {
      queue.open();
      admit(queue, KEY, throwFile);
      assertTrue(queue.pollPending(1).isEmpty(), "throwing authority denies pending claim");
    }
    seedProcessing(
        temp.resolve("throwing-recovery.db"), KEY, temp.resolve("throwing-recovery.txt"));
    backdate(temp.resolve("throwing-recovery.db"), temp.resolve("throwing-recovery.txt"));
    try (var queue = new SqliteJobQueue(temp.resolve("throwing-recovery.db"), ignored -> {
      throw new IllegalStateException("offline");
    })) {
      queue.open();
      assertEquals(0, queue.recoverStuckJobs(), "throwing authority denies unconditional recovery");
      assertEquals(0, queue.recoverStuckJobs(0), "throwing authority denies aged recovery");
    }
  }

  @Test
  void authorityReceivesTheExactRecordedOperationKey(@TempDir Path temp) throws Exception {
    var observed = new java.util.ArrayList<String>();
    Path db = temp.resolve("exact-key.db");
    try (var queue = new SqliteJobQueue(db, ignored -> {
      observed.add(ignored);
      return JobQueue.RecordedClaimDecision.ALLOW;
    })) {
      queue.open();
      admit(queue, KEY, temp.resolve("exact.txt"));
      assertEquals(1, queue.pollPending(1).size());
    }
    assertEquals(List.of(KEY), observed, "authority must receive the row's scan_id exactly");
  }

  @Test
  void deniedOldestRowDoesNotConsumeTheBatchLimit(@TempDir Path temp) throws Exception {
    Path db = temp.resolve("batch-limit.db");
    Path denied = temp.resolve("a-denied.txt");
    Path allowed = temp.resolve("z-allowed.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> SECOND_KEY.equals(ignored) ? JobQueue.RecordedClaimDecision.ALLOW : JobQueue.RecordedClaimDecision.DENY)) {
      queue.open();
      admit(queue, KEY, denied);
      admit(queue, SECOND_KEY, allowed);
      var claims = queue.pollPending(1);
      assertEquals(1, claims.size(), "an ineligible oldest row must not consume the limit");
      assertEquals(allowed.toAbsolutePath().normalize(), claims.getFirst().path());
      assertEquals(SECOND_KEY, claims.getFirst().scanId());
    }
  }

  @Test
  void nonnullLegacyScanIdWithoutEpochRetainsLegacyEligibility(@TempDir Path temp)
      throws Exception {
    Path db = temp.resolve("legacy-scan.db");
    Path file = temp.resolve("legacy.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.DENY)) {
      queue.open();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null, KEY);
      var claim = queue.pollPending(1).getFirst();
      assertEquals(KEY, claim.scanId());
      assertNull(claim.walkEpoch(), "legacy scan rows have no recorded epoch");
    }
  }

  @Test
  void authorityIsCheckedForEachPendingUnitWithinOnePoll(@TempDir Path temp) throws Exception {
    AtomicInteger evaluations = new AtomicInteger();
    Path db = temp.resolve("per-unit-poll.db");
    Path first = temp.resolve("a-first.txt");
    Path second = temp.resolve("b-second.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> evaluations.getAndIncrement() == 0 ? JobQueue.RecordedClaimDecision.ALLOW : JobQueue.RecordedClaimDecision.DENY)) {
      queue.open();
      var walk = queue.beginRecordedWalk(KEY, PLAN, true);
      queue.enqueueRecordedEntries(KEY, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(first),
              JobQueue.EnqueueEntry.ofUnknownSize(second)), null);
      var claims = queue.pollPending(2);
      assertEquals(1, claims.size(), "a denied candidate must not consume the batch limit");
      assertEquals(first.toAbsolutePath().normalize(), claims.getFirst().path());
      assertEquals(2, evaluations.get(), "each candidate must be authorized independently");
    }
  }

  @Test
  void fatalAuthorityErrorPropagatesFromClaimAndRecovery(@TempDir Path temp) throws Exception {
    Path pendingDb = temp.resolve("fatal-pending.db");
    Path pendingFile = temp.resolve("fatal-pending.txt");
    try (var queue = new SqliteJobQueue(pendingDb, ignored -> {
      throw new AssertionError("fatal authority failure");
    })) {
      queue.open();
      admit(queue, KEY, pendingFile);
      assertThrows(AssertionError.class, () -> queue.pollPending(1));
    }

    Path recoveryDb = temp.resolve("fatal-recovery.db");
    Path recoveryFile = temp.resolve("fatal-recovery.txt");
    seedProcessing(recoveryDb, KEY, recoveryFile);
    backdate(recoveryDb, recoveryFile);
    try (var queue = new SqliteJobQueue(recoveryDb, ignored -> {
      throw new AssertionError("fatal authority failure");
    })) {
      queue.open();
      assertThrows(AssertionError.class, queue::recoverStuckJobs);
      assertThrows(AssertionError.class, () -> queue.recoverStuckJobs(60_000));
    }
  }

  @Test
  void revocationBlocksNextClaimButIssuedClaimMayComplete(@TempDir Path temp) throws Exception {
    AtomicBoolean allowed = new AtomicBoolean(true);
    Path db = temp.resolve("revocation.db");
    Path first = temp.resolve("first.txt");
    Path second = temp.resolve("second.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> allowed.get() ? JobQueue.RecordedClaimDecision.ALLOW : JobQueue.RecordedClaimDecision.DENY)) {
      queue.open();
      var walk = queue.beginRecordedWalk(KEY, PLAN, true);
      queue.enqueueRecordedEntries(KEY, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(first)), null);
      var issued = queue.pollPending(1).getFirst();
      allowed.set(false);
      queue.enqueueRecordedEntries(KEY, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(second)), null);
      assertTrue(queue.pollPending(1).isEmpty(), "revocation blocks a new claim");
      queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(issued, null,
          "b".repeat(64))), success());
      assertEquals("DONE", state(db, first));
    }
  }

  @Test
  void reopenDoesNotRememberAPreviouslyAllowedPermit(@TempDir Path temp) throws Exception {
    Path db = temp.resolve("reopen.db");
    Path file = temp.resolve("reopen.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> KEY.equals(ignored) ? JobQueue.RecordedClaimDecision.ALLOW : JobQueue.RecordedClaimDecision.DENY)) {
      queue.open();
      admit(queue, KEY, file);
    }
    try (var reopened = new SqliteJobQueue(db)) {
      reopened.open();
      assertTrue(reopened.pollPending(1).isEmpty(),
          "reopen must default to denied recorded claims");
    }
  }

  @Test
  void laterAllowedAuthorityAndUnconditionalRecoveryReviveHeartbeatedProcessing(
      @TempDir Path temp) throws Exception {
    Path db = temp.resolve("later-authority.db");
    Path file = temp.resolve("heartbeated.txt");
    seedProcessing(db, KEY, file);
    AtomicBoolean permitted = new AtomicBoolean(false);
    try (var queue = new SqliteJobQueue(db, ignored -> permitted.get() ? JobQueue.RecordedClaimDecision.ALLOW : JobQueue.RecordedClaimDecision.DENY)) {
      queue.open();
      assertEquals(0, queue.recoverStuckJobs(), "denied authority keeps processing fenced");
      permitted.set(true);
      queue.heartbeatProcessing();
      assertEquals(0, queue.recoverStuckJobs(60_000),
          "a recent heartbeat must stay outside the aged recovery window");
      assertEquals(1, queue.recoverStuckJobs(),
          "unconditional recovery must revive an authorized heartbeated row");
      assertEquals(1, queue.pollPending(1).size());
    }
  }

  @Test
  void stoppedOrphansBecomeSkippedWithoutCallingDeniedAuthority(@TempDir Path temp)
      throws Exception {
    for (JobQueue.WalkEnumerationOutcome outcome : List.of(
        JobQueue.WalkEnumerationOutcome.FAILED, JobQueue.WalkEnumerationOutcome.CANCELLED)) {
      for (boolean throwing : List.of(false, true)) {
        for (boolean aged : List.of(false, true)) {
          String suffix = outcome.name().toLowerCase() + (throwing ? "-throwing" : "-denied")
              + (aged ? "-aged" : "-unconditional");
          Path db = temp.resolve(suffix + ".db");
          Path file = temp.resolve(suffix + ".txt");
          seedStoppedProcessing(db, KEY, file, outcome);
          backdate(db, file);
          var calls = new AtomicInteger();
          java.util.function.Function<String, JobQueue.RecordedClaimDecision> authority = ignored -> {
            calls.incrementAndGet();
            if (throwing) throw new IllegalStateException("authority unavailable");
            return JobQueue.RecordedClaimDecision.DENY;
          };
          try (var queue = new SqliteJobQueue(db, authority)) {
            queue.open();
            int recovered = aged ? queue.recoverStuckJobs(60_000) : queue.recoverStuckJobs();
            assertEquals(1, recovered,
                "stopped orphan must be skipped in the selected recovery mode");
            assertEquals(0, calls.get(),
                "administrative stop must not request claim permission");
            assertEquals("DONE", state(db, file));
            var sealed = queue.trySealRecordedWalk(KEY);
            assertNotNull(sealed.sealedAt());
            assertEquals(1,
                JSON.readTree(sealed.receiptJson()).path("currentSkippedUnits").asInt());
          }
        }
      }
    }
  }

  private static void seedProcessing(Path db, String key, Path file) throws Exception {
    try (var queue = new SqliteJobQueue(db, ignored -> key.equals(ignored) ? JobQueue.RecordedClaimDecision.ALLOW : JobQueue.RecordedClaimDecision.DENY)) {
      queue.open();
      admit(queue, key, file);
      assertEquals(1, queue.pollPending(1).size());
    }
  }

  private static void seedStoppedProcessing(Path db, String key, Path file,
      JobQueue.WalkEnumerationOutcome outcome) throws Exception {
    try (var queue = new SqliteJobQueue(db, ignored -> key.equals(ignored) ? JobQueue.RecordedClaimDecision.ALLOW : JobQueue.RecordedClaimDecision.DENY)) {
      queue.open();
      var walk = queue.beginRecordedWalk(key, PLAN, true);
      queue.enqueueRecordedEntries(key, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
      assertEquals(1, queue.pollPending(1).size());
      queue.closeRecordedWalkEnumeration(key, walk.enumerationEpoch(), outcome);
    }
  }

  private static void admit(SqliteJobQueue queue, String key, Path file) {
    var walk = queue.recordedWalk(key).orElseGet(() -> queue.beginRecordedWalk(key, PLAN, true));
    queue.enqueueRecordedEntries(key, walk.enumerationEpoch(),
        List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
  }

  private static String state(Path db, Path file) throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.prepareStatement("SELECT state FROM jobs WHERE path = ?")) {
      statement.setString(1, PathNormalizer.normalizePath(file.toAbsolutePath().toString()));
      try (var rows = statement.executeQuery()) {
        assertTrue(rows.next());
        return rows.getString(1);
      }
    }
  }

  private static void backdate(Path db, Path file) throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.prepareStatement(
            "UPDATE jobs SET last_updated = 0 WHERE path = ?")) {
      statement.setString(1, PathNormalizer.normalizePath(file.toAbsolutePath().toString()));
      assertEquals(1, statement.executeUpdate(), "exactly one queue row must be backdated");
    }
  }

  private static IngestionOutcome success() {
    return IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS",
        IngestionRetryPolicy.NONE, "Indexed");
  }

}
