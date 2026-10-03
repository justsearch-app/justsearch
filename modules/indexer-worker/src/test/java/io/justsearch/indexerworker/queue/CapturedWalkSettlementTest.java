/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite coverage for captured H1 membership and immutable post-seal settlement. */
final class CapturedWalkSettlementTest {
  private static final String PLAN = "a".repeat(64);
  private static final String H1 = "1".repeat(64);
  private static final String H2 = "2".repeat(64);
  private static final String H3 = "3".repeat(64);
  private static final String H4 = "4".repeat(64);

  @TempDir Path temp;

  @Test
  void stableAndSupersededCommittedEffectsHaveDifferentSettlementEvidence() throws Exception {
    String key = key(1);
    Path db = temp.resolve("stable-superseded.db");
    Path stable = temp.resolve("stable.txt");
    Path changed = temp.resolve("changed.txt");

    try (var queue = open(db)) {
      var walk = capture(queue, key, List.of(entry(stable, H1), entry(changed, H1)));
      var claims = queue.pollPending(2);
      assertEquals(2, claims.size());
      queue.markDoneTransitions(List.of(transition(claimFor(claims, stable), H1)), success());
      queue.markDoneTransitions(List.of(transition(claimFor(claims, changed), H2)), success());

      var sealed = closeAndSeal(queue, key, walk.enumerationEpoch());
      var settlement = queue.capturedWalkSettlement(key).orElseThrow();

      assertEquals(sealed.revision(), settlement.revision());
      assertEquals(sealed.manifestSha256(), settlement.manifestSha256());
      assertEquals(2, settlement.plannedUnits());
      assertTrue(settlement.gaps().isEmpty(), "a source change is successful processing, not a current gap");
      assertEquals(0, settlement.failedEvents());
      assertEquals(1, settlement.supersededEvents());
      assertEquals(1, settlement.processingHistory().size());
      var superseded = settlement.processingHistory().getFirst();
      assertEquals("INDEXED", superseded.coverage());
      assertEquals(H1, superseded.plannedSourceSha256());
      assertEquals(H2, superseded.contentHash());
      assertEquals(settlement.sha256(), string(db,
          "SELECT json_extract(receipt_json, '$.settlementSha256') FROM ingestion_walk_progress WHERE operation_key = ?", key));
    }
  }

  @Test
  void issuedClaimKeepsCapturedH1AcrossMaintenanceReplacement() throws Exception {
    String key = key(2);
    Path db = temp.resolve("issued-h1.db");
    Path file = temp.resolve("issued.txt");

    try (var queue = open(db)) {
      var walk = capture(queue, key, List.of(entry(file, H1)));
      var issued = queue.pollPending(1).getFirst();
      assertEquals(H1, issued.plannedSourceSha256());

      // The ordinary maintenance writer supplies a newer observation, but the active walk owns H1.
      queue.enqueueEntries(List.of(entry(file, H2)), null, null);
      assertEquals(H1, string(db, "SELECT planned_source_sha256 FROM jobs WHERE path = ?", normalized(file)));
      assertEquals(H1, issued.plannedSourceSha256(), "an issued claim is an immutable capture snapshot");

      queue.markDoneTransitions(List.of(transition(issued, H2)), success());
      var replacement = queue.pollPending(1).getFirst();
      assertEquals(H1, replacement.plannedSourceSha256());
      queue.markDoneTransitions(List.of(transition(replacement, H1)), success());

      closeAndSeal(queue, key, walk.enumerationEpoch());
      var settlement = queue.capturedWalkSettlement(key).orElseThrow();
      assertTrue(settlement.gaps().isEmpty());
      assertEquals(1, settlement.supersededEvents());
      assertTrue(settlement.processingHistory().stream().anyMatch(unit ->
          unit.coverage().equals("INDEXED") && unit.plannedSourceSha256().equals(H1)
              && unit.contentHash().equals(H2)));
      assertEquals(2, count(db,
          "SELECT count(*) FROM ingestion_ledger WHERE operation_key = ? AND planned_source_sha256 = ?", key, H1));
    }
  }

  @Test
  void currentFailedAndSkippedMembersRemainFullGaps() throws Exception {
    String key = key(3);
    Path db = temp.resolve("current-gaps.db");
    Path failed = temp.resolve("failed.txt");
    Path skipped = temp.resolve("skipped.txt");

    try (var queue = open(db)) {
      var walk = capture(queue, key, List.of(entry(failed, H1), entry(skipped, H2)));
      var claims = queue.pollPending(2);
      assertEquals(2, claims.size());
      queue.markClaimFailed(claimFor(claims, failed), terminalFailure(), null);
      queue.markClaimDone(claimFor(claims, skipped), skipped(), null);

      closeAndSeal(queue, key, walk.enumerationEpoch());
      var settlement = queue.capturedWalkSettlement(key).orElseThrow();

      assertEquals(2, settlement.gaps().size());
      assertEquals(Set.of("FAILED", "SKIPPED"), settlement.gaps().stream()
          .map(JobQueue.CapturedUnit::coverage).collect(java.util.stream.Collectors.toSet()));
      assertTrue(settlement.gaps().stream().anyMatch(unit ->
          unit.coverage().equals("FAILED") && unit.reasonCode().equals("PARSER_FAILED")));
      assertTrue(settlement.gaps().stream().anyMatch(unit ->
          unit.coverage().equals("SKIPPED") && unit.reasonCode().equals("SKIPPED_POLICY")));
      assertEquals(1, settlement.failedEvents(), "skips remain gaps but are not failed events");
      assertEquals(1, settlement.processingHistory().size());
      assertEquals("FAILED", settlement.processingHistory().getFirst().coverage());
    }
  }

  @Test
  void failedAndSupersededHistorySurvivesSuccessfulReplacement() throws Exception {
    String key = key(4);
    Path db = temp.resolve("history-replaced.db");
    Path failedThenRepaired = temp.resolve("failed-repaired.txt");
    Path changedThenRestored = temp.resolve("changed-restored.txt");
    Path pendingGuard = temp.resolve("pending-guard.txt");

    try (var queue = open(db)) {
      var walk = capture(queue, key,
          List.of(entry(failedThenRepaired, H1), entry(changedThenRestored, H1), entry(pendingGuard, H1)));
      var firstClaims = queue.pollPending(2);
      assertEquals(2, firstClaims.size(), "the unfinished member keeps the open walk from sealing during replacement");
      queue.markClaimFailed(claimFor(firstClaims, failedThenRepaired), terminalFailure(), null);
      queue.markDoneTransitions(List.of(transition(claimFor(firstClaims, changedThenRestored), H2)), success());

      assertEquals(1, queue.reenqueue(entry(failedThenRepaired, H3)).accepted());
      assertEquals(1, queue.reenqueue(entry(changedThenRestored, H4)).accepted());
      var replacements = queue.pollPending(3);
      assertEquals(3, replacements.size());
      assertEquals(H1, claimFor(replacements, failedThenRepaired).plannedSourceSha256());
      assertEquals(H1, claimFor(replacements, changedThenRestored).plannedSourceSha256());
      queue.markDoneTransitions(List.of(transition(claimFor(replacements, failedThenRepaired), H1)), success());
      queue.markDoneTransitions(List.of(transition(claimFor(replacements, changedThenRestored), H1)), success());
      var guard = claimFor(replacements, pendingGuard);
      assertEquals(pendingGuard.toAbsolutePath().normalize(), guard.path());
      queue.markDoneTransitions(List.of(transition(guard, H1)), success());

      closeAndSeal(queue, key, walk.enumerationEpoch());
      var settlement = queue.capturedWalkSettlement(key).orElseThrow();

      assertTrue(settlement.gaps().isEmpty(), "the final successful replacement clears current gaps");
      assertEquals(1, settlement.failedEvents());
      assertEquals(1, settlement.supersededEvents());
      assertEquals(2, settlement.processingHistory().size());
      assertTrue(settlement.processingHistory().stream().anyMatch(unit -> unit.coverage().equals("FAILED")));
      assertTrue(settlement.processingHistory().stream().anyMatch(unit ->
          unit.coverage().equals("INDEXED") && unit.contentHash().equals(H2)));
    }
  }

  @Test
  void historySampleCapsAtTwoHundredWithoutTruncatingGapsOrCounts() throws Exception {
    String key = key(5);
    Path db = temp.resolve("history-cap.db");
    List<Path> files = new ArrayList<>();
    List<JobQueue.EnqueueEntry> entries = new ArrayList<>();
    for (int i = 0; i < 205; i++) {
      Path file = temp.resolve("gap-" + i + ".txt");
      files.add(file);
      entries.add(entry(file, H1));
    }

    try (var queue = open(db)) {
      var walk = capture(queue, key, entries);
      var claims = queue.pollPending(files.size());
      assertEquals(files.size(), claims.size());
      for (var claim : claims) queue.markClaimFailed(claim, terminalFailure(), null);

      closeAndSeal(queue, key, walk.enumerationEpoch());
      var settlement = queue.capturedWalkSettlement(key).orElseThrow();

      assertEquals(files.size(), settlement.plannedUnits());
      assertEquals(files.size(), settlement.failedEvents());
      assertEquals(files.size(), settlement.gaps().size(), "all current gaps remain available");
      assertEquals(200, settlement.processingHistory().size(), "only the history sample is capped");
      assertEquals(64, settlement.sha256().length());
      assertEquals(settlement, queue.capturedWalkSettlement(key).orElseThrow(),
          "re-reading a sealed projection is deterministic");
    }
  }

  @Test
  void sealedPathReuseAndDatabaseReopenPreserveTheExactSettlement() throws Exception {
    String key = key(6);
    Path db = temp.resolve("sealed-reuse.db");
    Path file = temp.resolve("reused.txt");
    JobQueue.CapturedWalkSettlement beforeReuse;
    JobQueue.SealedWalkReceipt sealedReceipt;

    try (var queue = open(db)) {
      var walk = capture(queue, key, List.of(entry(file, H1)));
      var claim = queue.pollPending(1).getFirst();
      queue.markDoneTransitions(List.of(transition(claim, H1)), success());
      var sealed = closeAndSeal(queue, key, walk.enumerationEpoch());
      beforeReuse = queue.capturedWalkSettlement(key).orElseThrow();
      sealedReceipt = queue.sealedRecordedWalkReceipt(key).orElseThrow();

      assertEquals(1, queue.reenqueue(entry(file, H2)).accepted());
      assertNull(string(db, "SELECT scan_id FROM jobs WHERE path = ?", normalized(file)),
          "post-seal reuse is outside the finite captured walk");
      assertNull(string(db, "SELECT walk_seen_epoch FROM jobs WHERE path = ?", normalized(file)));
      assertEquals(beforeReuse, queue.capturedWalkSettlement(key).orElseThrow());
      assertEquals(sealed.revision(), sealedReceipt.revision());
    }

    try (var reopened = open(db)) {
      assertEquals(beforeReuse, reopened.capturedWalkSettlement(key).orElseThrow());
      assertEquals(sealedReceipt, reopened.sealedRecordedWalkReceipt(key).orElseThrow());
    }
  }

  @Test
  void ledgerRetentionWaitsForExactAcknowledgementThenPrunes() throws Exception {
    String key = key(7);
    Path db = temp.resolve("retention.db");
    Path file = temp.resolve("retention.txt");

    try (var queue = open(db)) {
      var walk = capture(queue, key, List.of(entry(file, H1)));
      var claim = queue.pollPending(1).getFirst();
      queue.markDoneTransitions(List.of(transition(claim, H1)), success());
      var sealed = closeAndSeal(queue, key, walk.enumerationEpoch());

      execute(db, "UPDATE jobs SET last_updated = 0 WHERE scan_id = ?", key);
      execute(db, "UPDATE ingestion_ledger SET observed_at = 0 WHERE operation_key = ?", key);
      execute(db, "UPDATE ingestion_walk_progress SET sealed_at = 0 WHERE operation_key = ?", key);
      assertFalse(queue.acknowledgeRecordedWalk(key, sealed.revision() - 1), "a stale revision cannot release evidence");
      assertEquals(0, queue.cleanupOldJobs(0));
      assertEquals(0, queue.cleanupOldLedgerEvents(0));
      assertEquals(1, count(db, "SELECT count(*) FROM jobs WHERE scan_id = ?", key));
      assertEquals(1, count(db, "SELECT count(*) FROM ingestion_ledger WHERE operation_key = ?", key));
      assertTrue(queue.capturedWalkSettlement(key).isPresent());

      assertTrue(queue.acknowledgeRecordedWalk(key, sealed.revision()));
      assertEquals(1, queue.cleanupOldJobs(0));
      assertEquals(1, queue.cleanupOldLedgerEvents(0));
      assertEquals(0, count(db, "SELECT count(*) FROM jobs WHERE scan_id = ?", key));
      assertEquals(0, count(db, "SELECT count(*) FROM ingestion_ledger WHERE operation_key = ?", key));
      assertEquals(0, count(db, "SELECT count(*) FROM ingestion_walk_progress WHERE operation_key = ?", key));
    }
  }

  @Test
  void unacknowledgedCapturedWalkKeysPageSealedCapturesUntilExactAcknowledgement() throws Exception {
    String firstKey = key(11);
    String secondKey = key(12);
    String thirdKey = key(13);
    String ordinaryKey = key(14);
    String unsealedKey = key(15);
    Path db = temp.resolve("captured-inventory.db");

    try (var queue = open(db)) {
      var firstWalk = capture(queue, firstKey, List.of(entry(temp.resolve("inventory.txt"), H1)));
      var claim = queue.pollPending(1).getFirst();
      queue.markDoneTransitions(List.of(transition(claim, H1)), success());
      var firstSealed = closeAndSeal(queue, firstKey, firstWalk.enumerationEpoch());

      for (String capturedKey : List.of(secondKey, thirdKey)) {
        var emptyWalk = queue.beginCapturedWalk(capturedKey, PLAN, true);
        closeAndSeal(queue, capturedKey, emptyWalk.enumerationEpoch());
      }

      var ordinaryWalk = queue.beginRecordedWalk(ordinaryKey, PLAN, true);
      closeAndSeal(queue, ordinaryKey, ordinaryWalk.enumerationEpoch());

      capture(queue, unsealedKey, List.of(entry(temp.resolve("pending.txt"), H2)));
      assertNull(queue.trySealRecordedWalk(unsealedKey).sealedAt(),
          "a completed enumeration with pending work has not reached settlement");

      var settlement = queue.capturedWalkSettlement(firstKey).orElseThrow();
      assertEquals(firstSealed.manifestSha256(), settlement.manifestSha256());
      assertEquals(1L, settlement.plannedUnits());

      assertEquals(List.of(firstKey, secondKey),
          queue.unacknowledgedCapturedWalkKeys(null, 2),
          "the sealed settlement remains discoverable before queue acknowledgement");
      assertEquals(List.of(thirdKey),
          queue.unacknowledgedCapturedWalkKeys(secondKey, 2),
          "the exclusive key cursor returns the next bounded page");
      assertTrue(queue.unacknowledgedCapturedWalkKeys(thirdKey, 2).isEmpty());
      assertThrows(IllegalArgumentException.class,
          () -> queue.unacknowledgedCapturedWalkKeys(null, 257),
          "inventory pages cannot exceed the configured bound");

      assertFalse(queue.acknowledgeRecordedWalk(firstKey, firstSealed.revision() - 1),
          "a stale acknowledgement leaves the terminal settlement discoverable");
      assertTrue(queue.unacknowledgedCapturedWalkKeys(null, 256).contains(firstKey));
      assertTrue(queue.acknowledgeRecordedWalk(firstKey, firstSealed.revision()));
      assertEquals(List.of(secondKey, thirdKey), queue.unacknowledgedCapturedWalkKeys(null, 256),
          "exact acknowledgement removes only that capture from recovery inventory");
    }
  }

  @Test
  void incompatibleLedgerOutcomeRefusesInitialSeal() throws Exception {
    String key = key(10);
    Path db = temp.resolve("preseal-outcome.db");
    try (var queue = open(db)) {
      capture(queue, key, List.of(entry(temp.resolve("preseal.txt"), H1)));
      var claim = queue.pollPending(1).getFirst();
      queue.markDoneTransitions(List.of(transition(claim, H1)), success());
      execute(db, "UPDATE ingestion_ledger SET outcome_class = 'SKIPPED_POLICY' WHERE operation_key = ?", key);
      assertThrows(JobQueue.RecordedWalkGapException.class, () -> queue.trySealRecordedWalk(key));
      assertNull(queue.recordedWalk(key).orElseThrow().sealedAt());
      assertEquals(0, count(db, "SELECT count(*) FROM ingestion_walk_sealed_units WHERE operation_key = ?", key),
          "failed validation rolls back selection together with receipt sealing");
    }
  }

  @Test
  void missingOrCorruptSelectedEvidenceRefusesSettlement() throws Exception {
    for (String mutation : List.of("missing", "corrupt", "retry-policy", "outcome-class")) {
      String key = mutation.equals("missing") ? key(8) : key(9);
      Path db = temp.resolve("evidence-" + mutation + ".db");
      Path file = temp.resolve("evidence-" + mutation + ".txt");
      try (var queue = open(db)) {
        var walk = capture(queue, key, List.of(entry(file, H1)));
        var claim = queue.pollPending(1).getFirst();
        queue.markDoneTransitions(List.of(transition(claim, H1)), success());
        closeAndSeal(queue, key, walk.enumerationEpoch());

        if (mutation.equals("missing")) {
          execute(db, "DELETE FROM ingestion_ledger WHERE id = (SELECT ledger_id FROM ingestion_walk_sealed_units WHERE operation_key = ?)", key);
        } else if (mutation.equals("retry-policy")) {
          execute(db, "UPDATE ingestion_ledger SET retry_policy = 'DEFER_WITHOUT_ATTEMPT' WHERE operation_key = ?", key);
        } else if (mutation.equals("outcome-class")) {
          execute(db, "UPDATE ingestion_ledger SET outcome_class = 'SKIPPED_POLICY' WHERE operation_key = ?", key);
        } else {
          execute(db, "UPDATE ingestion_ledger SET planned_source_sha256 = 'invalid' WHERE id = "
              + "(SELECT ledger_id FROM ingestion_walk_sealed_units WHERE operation_key = ?)", key);
        }

        assertThrows(JobQueue.RecordedWalkGapException.class,
            () -> queue.capturedWalkSettlement(key), mutation + " selected evidence must not be accepted");
        long revision = queue.recordedWalk(key).orElseThrow().revision();
        assertThrows(JobQueue.RecordedWalkGapException.class, () -> queue.acknowledgeRecordedWalk(key, revision));
      }
    }
  }

  private static SqliteJobQueue open(Path db) throws Exception {
    var queue = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW);
    queue.open();
    return queue;
  }

  private static JobQueue.EnqueueEntry entry(Path path, String plannedHash) {
    return new JobQueue.EnqueueEntry(path, JobQueue.UNKNOWN_SIZE_BYTES, null, plannedHash);
  }

  private static JobQueue.WalkProgress capture(SqliteJobQueue queue, String key,
      List<JobQueue.EnqueueEntry> entries) {
    var walk = queue.beginCapturedWalk(key, PLAN, true);
    queue.enqueueRecordedEntries(key, walk.enumerationEpoch(), entries, null);
    // A captured plan authorizes claims only after COMPLETE enumeration freezes its H1 manifest.
    return queue.closeRecordedWalkEnumeration(key, walk.enumerationEpoch(),
        JobQueue.WalkEnumerationOutcome.COMPLETE);
  }

  private static JobQueue.WalkProgress closeAndSeal(SqliteJobQueue queue, String key, long epoch) {
    queue.closeRecordedWalkEnumeration(key, epoch, JobQueue.WalkEnumerationOutcome.COMPLETE);
    return queue.trySealRecordedWalk(key);
  }

  private static JobQueue.IndexJob claimFor(List<JobQueue.IndexJob> claims, Path path) {
    Path normalized = path.toAbsolutePath().normalize();
    return claims.stream().filter(claim -> claim.path().equals(normalized)).findFirst().orElseThrow();
  }

  private static JobQueue.IngestionLedgerTransition transition(JobQueue.IndexJob claim, String hash) {
    return new JobQueue.IngestionLedgerTransition(claim, null, hash);
  }

  private static IngestionOutcome success() {
    return IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE);
  }

  private static IngestionOutcome skipped() {
    return IngestionOutcome.of(IngestionOutcomeClass.SKIPPED_POLICY, "SKIPPED_POLICY", IngestionRetryPolicy.NONE);
  }

  private static IngestionOutcome terminalFailure() {
    return IngestionOutcome.of(IngestionOutcomeClass.PARSER_FAILED, "PARSER_FAILED", IngestionRetryPolicy.NONE);
  }

  private static String key(int suffix) {
    return "01994180-0000-7000-8000-000000000" + String.format(java.util.Locale.ROOT, "%03d", suffix);
  }

  private static String normalized(Path path) {
    return io.justsearch.indexerworker.util.PathNormalizer.normalizePath(path.toAbsolutePath().toString());
  }

  private static void execute(Path db, String sql, Object... values) throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
      statement.executeUpdate();
    }
  }

  private static String string(Path db, String sql, Object... values) throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
  }

  private static int count(Path db, String sql, Object... values) throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
      try (ResultSet rows = statement.executeQuery()) {
        assertTrue(rows.next());
        return rows.getInt(1);
      }
    }
  }
}
