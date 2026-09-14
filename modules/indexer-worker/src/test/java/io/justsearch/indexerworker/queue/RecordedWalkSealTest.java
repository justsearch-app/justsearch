/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real SQLite coverage for finite recorded-walk sealing and immutable receipts. */
final class RecordedWalkSealTest {
  private static final String PLAN = "a".repeat(64);
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path temp;

  @Test
  void completeEmptyWalkSealsAndDuplicateReturnsImmutableReceipt() throws Exception {
    String key = key(1);
    Path db = temp.resolve("empty.db");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      var opened = queue.beginRecordedWalk(key, PLAN, true);
      var closed = queue.closeRecordedWalkEnumeration(
          key, opened.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.COMPLETE);

      var sealed = queue.trySealRecordedWalk(key);
      assertNotNull(sealed.sealedAt());
      assertEquals(closed.revision() + 1, sealed.revision());
      assertEquals(sealed.revision(), receipt(sealed).path("revision").asLong());
      assertEquals(1, receipt(sealed).path("version").asInt());
      assertEquals(0, receipt(sealed).path("completedUnits").asLong());
      assertEquals(0, receipt(sealed).path("failedUnits").asLong());
      assertEquals(0, receipt(sealed).path("currentFailedUnits").asLong());
      assertEquals(0, receipt(sealed).path("currentSkippedUnits").asLong());
      assertEquals("COMPLETE", receipt(sealed).path("enumerationOutcome").asText());
      assertTrue(receipt(sealed).path("failedPathHashes").isArray());
      assertTrue(receipt(sealed).path("failedPathHashes").isEmpty());
      assertFalse(receipt(sealed).path("failedPathHashesTruncated").asBoolean());

      var duplicate = queue.trySealRecordedWalk(key);
      assertEquals(sealed, duplicate);
      assertEquals(sealed.receiptJson(), duplicate.receiptJson());
      assertEquals(sealed.revision(), queue.recordedWalk(key).orElseThrow().revision());
    }
  }

  @Test
  void missingProgressAndMissingCurrentCoverageRefuseToSeal() throws Exception {
    Path db = temp.resolve("gaps.db");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertThrows(JobQueue.RecordedWalkGapException.class,
          () -> queue.trySealRecordedWalk(key(2)));

      String key = key(3);
      Path file = temp.resolve("coverage-gap.txt");
      var opened = queue.beginRecordedWalk(key, PLAN, true);
      queue.enqueueRecordedEntries(key, opened.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
      queue.closeRecordedWalkEnumeration(key, opened.enumerationEpoch(),
          JobQueue.WalkEnumerationOutcome.COMPLETE);
      execute(db, "UPDATE jobs SET state = 'DONE', last_outcome_class = 'SKIPPED_POLICY', last_retry_policy = 'NONE' WHERE path = '" + normalized(file) + "'");

      assertThrows(JobQueue.RecordedWalkGapException.class,
          () -> queue.trySealRecordedWalk(key));
      assertNull(queue.recordedWalk(key).orElseThrow().sealedAt());
    }
  }

  @Test
  void failedAndCancelledClosureSkipsUnissuedAndIssuedReturnsBeforeSeal() throws Exception {
    for (JobQueue.WalkEnumerationOutcome outcome : List.of(
        JobQueue.WalkEnumerationOutcome.FAILED, JobQueue.WalkEnumerationOutcome.CANCELLED)) {
      String key = key(outcome == JobQueue.WalkEnumerationOutcome.FAILED ? 4 : 5);
      Path db = temp.resolve(outcome.name().toLowerCase() + ".db");
      Path issuedPath = temp.resolve(outcome.name().toLowerCase() + "-issued.txt");
      Path pendingPath = temp.resolve(outcome.name().toLowerCase() + "-pending.txt");
      try (var queue = new SqliteJobQueue(db, ignored -> true)) {
        queue.open();
        var opened = queue.beginRecordedWalk(key, PLAN, true);
        queue.enqueueRecordedEntries(key, opened.enumerationEpoch(), List.of(
            JobQueue.EnqueueEntry.ofUnknownSize(issuedPath),
            JobQueue.EnqueueEntry.ofUnknownSize(pendingPath)), null);
        var claims = queue.pollPending(2);
        assertEquals(2, claims.size());
        var issued = claims.stream().filter(c -> c.path().equals(issuedPath.toAbsolutePath().normalize()))
            .findFirst().orElseThrow();
        var unissued = claims.stream().filter(c -> c.path().equals(pendingPath.toAbsolutePath().normalize()))
            .findFirst().orElseThrow();
        // Return exactly one claim before closure; the original issued object must remain live.
        queue.returnUnfinishedClaims(List.of(unissued));
        queue.closeRecordedWalkEnumeration(key, opened.enumerationEpoch(), outcome);
        assertTrue(queue.pollPending(2).isEmpty(), "closed failed/cancelled walks cannot be polled");
        assertNull(queue.trySealRecordedWalk(key).sealedAt(),
            "an issued owner keeps failed/cancelled closure NOT_READY");
        assertEquals(0, queue.reenqueue(JobQueue.EnqueueEntry.ofUnknownSize(issuedPath)).accepted(),
            "maintenance cannot replace a closed failed/cancelled member while its owner is issued");
        queue.returnUnfinishedClaims(List.of(issued));
        assertEquals("DONE", string(db, "SELECT state FROM jobs WHERE path = '" + normalized(issued.path()) + "'"));
        assertEquals("SKIPPED", string(db, "SELECT terminal_coverage FROM ingestion_ledger "
            + "WHERE operation_key = '" + key + "' AND unit_revision = '" + issued.unitRevision() + "'"));

        var sealed = queue.trySealRecordedWalk(key);
        assertEquals(2, receipt(sealed).path("currentSkippedUnits").asLong());
        assertEquals(outcome.name(), receipt(sealed).path("enumerationOutcome").asText());
        assertEquals(1, queue.reenqueue(JobQueue.EnqueueEntry.ofUnknownSize(issuedPath)).accepted());
        var replacement = queue.pollPending(1).getFirst();
        assertNull(replacement.scanId());
        assertNull(replacement.walkEpoch());
      }
    }
  }

  @Test
  void failedAndCancelledOrphanRecoveryBecomesTerminalSkip() throws Exception {
    for (JobQueue.WalkEnumerationOutcome outcome : List.of(
        JobQueue.WalkEnumerationOutcome.FAILED, JobQueue.WalkEnumerationOutcome.CANCELLED)) {
      String key = key(outcome == JobQueue.WalkEnumerationOutcome.FAILED ? 10 : 11);
      Path db = temp.resolve(outcome.name().toLowerCase() + "-orphan.db");
      Path file = temp.resolve(outcome.name().toLowerCase() + "-orphan.txt");
      JobQueue.IndexJob orphan;
      try (var queue = new SqliteJobQueue(db, ignored -> true)) {
        queue.open();
        var opened = queue.beginRecordedWalk(key, PLAN, true);
        queue.enqueueRecordedEntries(key, opened.enumerationEpoch(),
            List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
        orphan = queue.pollPending(1).getFirst();
        queue.closeRecordedWalkEnumeration(key, opened.enumerationEpoch(), outcome);
      }
      try (var queue = new SqliteJobQueue(db)) {
        queue.open();
        assertEquals(1, queue.recoverStuckJobs());
        assertEquals("DONE", string(db, "SELECT state FROM jobs WHERE path = '" + normalized(file) + "'"));
        assertEquals("SKIPPED", string(db, "SELECT terminal_coverage FROM ingestion_ledger "
            + "WHERE operation_key = '" + key + "' AND unit_revision = '" + orphan.unitRevision() + "'"));
        assertTrue(queue.pollPending(1).isEmpty(), "orphan recovery must not recreate pending work");
        var sealed = queue.trySealRecordedWalk(key);
        assertEquals(1, receipt(sealed).path("currentSkippedUnits").asLong());
        assertEquals(outcome.name(), receipt(sealed).path("enumerationOutcome").asText());
      }
    }
  }

  @Test
  void closedFailedCallbackCannotLeaveDeferredOrRetryableMemberPending() throws Exception {
    for (JobQueue.WalkEnumerationOutcome outcome : List.of(
        JobQueue.WalkEnumerationOutcome.FAILED, JobQueue.WalkEnumerationOutcome.CANCELLED)) {
      for (boolean deferred : List.of(true, false)) {
        String suffix = outcome.name().toLowerCase() + (deferred ? "-deferred" : "-retryable");
        String key = key(12 + (outcome == JobQueue.WalkEnumerationOutcome.FAILED ? 0 : 4)
            + (deferred ? 0 : 1));
        Path db = temp.resolve(suffix + ".db");
        Path file = temp.resolve(suffix + ".txt");
        try (var queue = new SqliteJobQueue(db, ignored -> true)) {
          queue.open();
          var opened = queue.beginRecordedWalk(key, PLAN, true);
          queue.enqueueRecordedEntries(key, opened.enumerationEpoch(),
              List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
          var claim = queue.pollPending(1).getFirst();
          queue.closeRecordedWalkEnumeration(key, opened.enumerationEpoch(), outcome);

          if (deferred) {
            assertTrue(queue.deferClaim(claim, deferredOutcome(), null));
          } else {
            assertTrue(queue.markClaimFailed(claim, transientFailure(), null));
          }
          assertEquals("DONE", string(db, "SELECT state FROM jobs WHERE path = '" + normalized(file) + "'"));
          assertEquals("SKIPPED", string(db, "SELECT terminal_coverage FROM ingestion_ledger "
              + "WHERE operation_key = '" + key + "' AND terminal_coverage = 'SKIPPED'"));
          assertTrue(queue.pollPending(1).isEmpty(), "closed failure/cancellation cannot leave pending work");
          assertEquals(1, receipt(queue.trySealRecordedWalk(key)).path("currentSkippedUnits").asLong());
        }
      }
    }
  }

  @Test
  void administrativeSkipLateIndexedRollbackRetainsOwnerAndBlocksSeal() throws Exception {
    for (int selector = 0; selector < 3; selector++) {
    String key = key(18 + selector);
    Path db = temp.resolve("admin-late-index-" + selector + ".db");
    Path file = temp.resolve("admin-late-index-" + selector + ".txt");
    try (var queue = new SqliteJobQueue(db, ignored -> true)) {
      queue.open();
      var opened = queue.beginRecordedWalk(key, PLAN, true);
      queue.enqueueRecordedEntries(key, opened.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
      var claim = queue.pollPending(1).getFirst();
      execute(db, "CREATE TRIGGER refuse_admin_ledger BEFORE INSERT ON ingestion_ledger "
          + "BEGIN SELECT RAISE(ABORT, 'fixture rollback'); END");
      assertTrue(removeAdministratively(queue, file, selector) <= 0);
      assertEquals("PROCESSING", string(db, "SELECT state FROM jobs WHERE path = '" + normalized(file) + "'"));
      assertEquals(0, count(db, "SELECT count(*) FROM ingestion_ledger WHERE operation_key = '" + key + "'"));
      execute(db, "DROP TRIGGER refuse_admin_ledger");
      assertEquals(1, removeAdministratively(queue, file, selector));
      queue.closeRecordedWalkEnumeration(key, opened.enumerationEpoch(),
          JobQueue.WalkEnumerationOutcome.COMPLETE);
      assertNull(queue.trySealRecordedWalk(key).sealedAt(), "the issued owner blocks sealing after admin skip");

      execute(db, "CREATE TRIGGER refuse_late_ledger BEFORE INSERT ON ingestion_ledger "
          + "BEGIN SELECT RAISE(ABORT, 'fixture rollback'); END");
      assertThrows(OutcomeWriteException.class,
          () -> queue.markDoneTransitions(List.of(transition(claim, "6".repeat(64))), success()));
      assertNull(queue.trySealRecordedWalk(key).sealedAt(),
          "a rolled-back late callback must retain the issued owner");
      assertTrue(queue.pollPending(1).isEmpty());
      execute(db, "DROP TRIGGER refuse_late_ledger");

      queue.markDoneTransitions(List.of(transition(claim, "6".repeat(64))), success());
      assertEquals(1, count(db, "SELECT count(*) FROM ingestion_ledger WHERE operation_key = '" + key
          + "' AND terminal_coverage = 'INDEXED'"));
      assertEquals(1, count(db, "SELECT count(*) FROM ingestion_ledger WHERE operation_key = '" + key
          + "' AND terminal_coverage = 'SKIPPED'"));
      assertEquals(1, receipt(queue.trySealRecordedWalk(key)).path("currentSkippedUnits").asLong());
    }
  }
    }

  @Test
  void mismatchingLedgerIdentityCoverageOrContentRefusesReceiptSeal() throws Exception {
    for (String column : List.of("path_hash", "unit_revision", "terminal_coverage", "content_hash")) {
      String key = key(22);
      Path db = temp.resolve("mismatch-" + column + ".db");
      Path file = temp.resolve("mismatch-" + column + ".txt");
      try (var queue = new SqliteJobQueue(db, ignored -> true)) {
        queue.open();
        var opened = queue.beginRecordedWalk(key, PLAN, true);
        queue.enqueueRecordedEntries(key, opened.enumerationEpoch(),
            List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
        var claim = queue.pollPending(1).getFirst();
        queue.markDoneTransitions(List.of(transition(claim, "4".repeat(64))), success());
        queue.closeRecordedWalkEnumeration(key, opened.enumerationEpoch(),
            JobQueue.WalkEnumerationOutcome.COMPLETE);
        String wrong = "terminal_coverage".equals(column) ? "SKIPPED" : "e".repeat(64);
        execute(db, "UPDATE ingestion_ledger SET " + column + " = '" + wrong
            + "' WHERE operation_key = '" + key + "'");
        assertThrows(JobQueue.RecordedWalkGapException.class, () -> queue.trySealRecordedWalk(key));
        assertNull(queue.recordedWalk(key).orElseThrow().sealedAt());
      }
    }
  }

  @Test
  void sealedUnacknowledgedWalkSurvivesAdministrativeAndAgeCleanup() throws Exception {
    String key = key(21);
    Path db = temp.resolve("sealed-retention.db");
    Path file = temp.resolve("sealed-retention.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> true)) {
      queue.open();
      var opened = queue.beginRecordedWalk(key, PLAN, true);
      queue.enqueueRecordedEntries(key, opened.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
      var claim = queue.pollPending(1).getFirst();
      queue.markDoneTransitions(List.of(transition(claim, "5".repeat(64))), success());
      queue.closeRecordedWalkEnumeration(key, opened.enumerationEpoch(),
          JobQueue.WalkEnumerationOutcome.COMPLETE);
      var sealed = queue.trySealRecordedWalk(key);
      backdate(db, normalized(file), key);

      assertEquals(0, queue.clearAll(), "administrative clear retains sealed unacknowledged members");
      assertEquals(0, queue.cleanupOldJobs(7));
      assertEquals(0, queue.cleanupOldLedgerEvents(7));
      assertEquals(1, count(db, "SELECT count(*) FROM jobs WHERE path = '" + normalized(file) + "'"));
      assertEquals(1, count(db, "SELECT count(*) FROM ingestion_ledger WHERE operation_key = '" + key + "'"));
      assertEquals(0, count(db, "SELECT count(*) FROM ingestion_walk_progress WHERE operation_key = '" + key
          + "' AND acknowledged_revision = " + sealed.revision()));
      assertFalse(queue.acknowledgeRecordedWalk(key, sealed.revision() - 1));
      assertEquals(0, queue.cleanupOldJobs(7));
      assertEquals(0, queue.cleanupOldLedgerEvents(7));
      assertTrue(queue.acknowledgeRecordedWalk(key, sealed.revision()));
      assertEquals(1, queue.cleanupOldJobs(7));
      assertEquals(1, queue.cleanupOldLedgerEvents(7));
      assertTrue(queue.recordedWalk(key).isPresent(), "recent progress survives old evidence cleanup");
    }
  }

  @Test
  void completeRetiresUnseenMemberWithoutRewritingIssuedObjectOrEffectHistory() throws Exception {
    String key = key(6);
    Path db = temp.resolve("retire.db");
    Path seen = temp.resolve("seen.txt");
    Path unseen = temp.resolve("unseen.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> true)) {
      queue.open();
      var first = queue.beginRecordedWalk(key, PLAN, true);
      queue.enqueueRecordedEntries(key, first.enumerationEpoch(), List.of(
          JobQueue.EnqueueEntry.ofUnknownSize(seen), JobQueue.EnqueueEntry.ofUnknownSize(unseen)), null);
      var claims = queue.pollPending(2);
      var seenClaim = claims.stream().filter(c -> c.path().equals(seen.toAbsolutePath().normalize()))
          .findFirst().orElseThrow();
      var unseenClaim = claims.stream().filter(c -> c.path().equals(unseen.toAbsolutePath().normalize()))
          .findFirst().orElseThrow();
      queue.markDoneTransitions(List.of(transition(seenClaim, "1".repeat(64))), success());

      var second = queue.beginRecordedWalk(key, PLAN, false);
      queue.enqueueRecordedEntries(key, second.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(seen)), null);
      queue.closeRecordedWalkEnumeration(key, second.enumerationEpoch(),
          JobQueue.WalkEnumerationOutcome.COMPLETE);
      assertEquals("DONE", string(db, "SELECT state FROM jobs WHERE path = '" + normalized(unseen) + "'"));
      assertNull(string(db, "SELECT content_hash FROM jobs WHERE path = '" + normalized(unseen) + "'"));

      queue.markDoneTransitions(List.of(transition(unseenClaim, "2".repeat(64))), success());
      assertNull(string(db, "SELECT content_hash FROM jobs WHERE path = '" + normalized(unseen) + "'"));
      assertEquals(2, count(db, "SELECT count(*) FROM ingestion_ledger WHERE operation_key = '" + key
          + "' AND terminal_coverage = 'INDEXED'"));
      assertEquals(1, count(db, "SELECT count(*) FROM ingestion_ledger WHERE operation_key = '" + key
          + "' AND terminal_coverage = 'SKIPPED'"));

      var sealed = queue.trySealRecordedWalk(key);
      assertEquals(2, sealed.completedUnits());
      assertEquals(1, receipt(sealed).path("currentSkippedUnits").asLong());
    }
  }

  @Test
  void maintenancePreflightSealsReadyWalkBeforeReplacementAdmission() throws Exception {
    String key = key(7);
    Path db = temp.resolve("maintenance-preflight.db");
    Path file = temp.resolve("maintenance.txt");
    try (var queue = new SqliteJobQueue(db, ignored -> true)) {
      queue.open();
      var opened = queue.beginRecordedWalk(key, PLAN, true);
      queue.enqueueRecordedEntries(key, opened.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
      var claim = queue.pollPending(1).getFirst();
      queue.markDoneTransitions(List.of(transition(claim, "3".repeat(64))), success());
      queue.closeRecordedWalkEnumeration(key, opened.enumerationEpoch(),
          JobQueue.WalkEnumerationOutcome.COMPLETE);
      assertNull(queue.recordedWalk(key).orElseThrow().sealedAt());

      assertEquals(1, queue.reenqueue(JobQueue.EnqueueEntry.ofUnknownSize(file)).accepted());
      assertNotNull(queue.recordedWalk(key).orElseThrow().sealedAt());
      var replacement = queue.pollPending(1).getFirst();
      assertNull(replacement.scanId());
      assertNull(replacement.walkEpoch());
    }
  }

  @Test
  void administrativeClearKeepsUnsealedMemberAsTerminalSkip() throws Exception {
    String key = key(8);
    Path db = temp.resolve("clear.db");
    Path file = temp.resolve("clear.txt");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      var opened = queue.beginRecordedWalk(key, PLAN, true);
      queue.enqueueRecordedEntries(key, opened.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);

      assertEquals(1, queue.clearAll());
      assertEquals("DONE", string(db, "SELECT state FROM jobs WHERE path = '" + normalized(file) + "'"));
      assertEquals("SKIPPED", string(db, "SELECT terminal_coverage FROM ingestion_ledger WHERE operation_key = '"
          + key + "'"));
      assertNull(queue.recordedWalk(key).orElseThrow().sealedAt());

      queue.closeRecordedWalkEnumeration(key, opened.enumerationEpoch(),
          JobQueue.WalkEnumerationOutcome.COMPLETE);
      assertEquals(1, receipt(queue.trySealRecordedWalk(key)).path("currentSkippedUnits").asLong());
    }
  }

  @Test
  void receiptSortsAndTruncatesMoreThanOneHundredCurrentFailureHashes() throws Exception {
    String key = key(9);
    Path db = temp.resolve("many-failures.db");
    List<Path> files = new ArrayList<>();
    for (int i = 0; i < 101; i++) files.add(temp.resolve("failure-" + i + ".txt"));
    try (var queue = new SqliteJobQueue(db, ignored -> true)) {
      queue.open();
      var opened = queue.beginRecordedWalk(key, PLAN, true);
      queue.enqueueRecordedEntries(key, opened.enumerationEpoch(),
          files.stream().map(JobQueue.EnqueueEntry::ofUnknownSize).toList(), null);
      var claims = queue.pollPending(101);
      assertEquals(101, claims.size());
      for (var claim : claims) queue.markClaimFailed(claim, terminalFailure(), null);
      queue.closeRecordedWalkEnumeration(key, opened.enumerationEpoch(),
          JobQueue.WalkEnumerationOutcome.COMPLETE);

      var sealed = queue.trySealRecordedWalk(key);
      JsonNode receipt = receipt(sealed);
      assertEquals(101, receipt.path("currentFailedUnits").asLong());
      assertEquals(100, receipt.path("failedPathHashes").size());
      assertTrue(receipt.path("failedPathHashesTruncated").asBoolean());
      List<String> actual = new ArrayList<>();
      receipt.path("failedPathHashes").forEach(node -> actual.add(node.asText()));
      assertEquals(actual.stream().sorted().toList(), actual);
      List<String> expected = ledgerHashes(db, key).stream().sorted().limit(100).toList();
      assertEquals(expected, actual);
    }
  }

  private static String key(int suffix) {
    return "01994180-0000-7000-8000-000000000" + String.format("%03d", suffix);
  }

  private static JsonNode receipt(JobQueue.WalkProgress progress) throws Exception {
    return JSON.readTree(progress.receiptJson());
  }

  private static JobQueue.IngestionLedgerTransition transition(JobQueue.IndexJob claim, String hash) {
    return new JobQueue.IngestionLedgerTransition(claim, null, hash);
  }

  private static int removeAdministratively(SqliteJobQueue queue, Path file, int selector) {
    return switch (selector) {
      case 0 -> queue.clearAll();
      case 1 -> queue.deleteByExactPath(normalized(file));
      case 2 -> queue.deleteByPathPrefix(normalized(file.getParent()));
      default -> throw new IllegalArgumentException("Unknown fixture selector");
    };
  }

  private static IngestionOutcome success() {
    return IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE);
  }

  private static IngestionOutcome terminalFailure() {
    return IngestionOutcome.of(IngestionOutcomeClass.PARSER_FAILED, "PARSER_FAILED", IngestionRetryPolicy.NONE);
  }

  private static IngestionOutcome transientFailure() {
    return IngestionOutcome.of(IngestionOutcomeClass.IO_FAILED, "IO_FAILED",
        IngestionRetryPolicy.RETRY_WITH_BACKOFF);
  }

  private static IngestionOutcome deferredOutcome() {
    return IngestionOutcome.of(IngestionOutcomeClass.WRITE_UNAVAILABLE_DRAINING,
        "WRITE_UNAVAILABLE_DRAINING", IngestionRetryPolicy.DEFER_WITHOUT_ATTEMPT);
  }

  private static String normalized(Path path) {
    return io.justsearch.indexerworker.util.PathNormalizer.normalizePath(path.toAbsolutePath().toString());
  }

  private static void execute(Path db, String sql) throws Exception {
    try (Connection connection = connection(db); var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static void backdate(Path db, String path, String key) throws Exception {
    long old = System.currentTimeMillis() - 8L * 24L * 60L * 60L * 1000L;
    try (Connection connection = connection(db);
        var job = connection.prepareStatement("UPDATE jobs SET last_updated = ? WHERE path = ?");
        var ledger = connection.prepareStatement("UPDATE ingestion_ledger SET observed_at = ? WHERE operation_key = ?")) {
      job.setLong(1, old);
      job.setString(2, path);
      assertEquals(1, job.executeUpdate());
      ledger.setLong(1, old);
      ledger.setString(2, key);
      assertEquals(1, ledger.executeUpdate());
    }
  }

  private static long count(Path db, String sql) throws Exception {
    try (Connection connection = connection(db); var statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      return rows.getLong(1);
    }
  }

  private static String string(Path db, String sql) throws Exception {
    try (Connection connection = connection(db); var statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      return rows.getString(1);
    }
  }

  private static List<String> ledgerHashes(Path db, String key) throws Exception {
    List<String> hashes = new ArrayList<>();
    try (Connection connection = connection(db);
        var statement = connection.prepareStatement("SELECT path_hash FROM ingestion_ledger "
            + "WHERE operation_key = ? AND terminal_coverage = 'FAILED' ORDER BY path_hash")) {
      statement.setString(1, key);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) hashes.add(rows.getString(1));
      }
    }
    return hashes;
  }

  private static Connection connection(Path db) throws Exception {
    return DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
  }
}
