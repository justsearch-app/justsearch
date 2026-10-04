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
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Real SQLite proof for finite captured plans and their durable source identities. */
final class CapturedWalkPlanTest {
  private static final String PLAN = "a".repeat(64);
  private static final String H1 = "1".repeat(64);
  private static final String H2 = "2".repeat(64);
  private static final String H3 = "3".repeat(64);
  private static final String H4 = "4".repeat(64);
  private static final String KEY = "01994180-0000-7000-8000-000000000121";

  @TempDir Path temp;

  @Test
  void interruptedReopenKeepsCapturedHashJoinsNewMemberAndSealsDisappearedGap() throws Exception {
    Path db = temp.resolve("resume.db");
    Path retained = temp.resolve("retained.txt");
    Path disappeared = temp.resolve("disappeared.txt");
    Path added = temp.resolve("added.txt");
    try (var interrupted = forceAllowedQueue(db)) {
      interrupted.open();
      var first = interrupted.beginCapturedWalk(KEY, PLAN, true);
      assertTrue(first.capturedPlan());
      assertEquals(2, interrupted.enqueueRecordedEntries(KEY, first.enumerationEpoch(), List.of(
          captured(retained, H1), captured(disappeared, H2)), null));
      assertTrue(interrupted.pollPending(10).isEmpty(),
          "ALLOW_FORCE must not claim captured members before COMPLETE enumeration");
    }

    JobQueue.CapturedWalkSettlement firstSettlement;
    String manifest;
    try (var resumed = forceAllowedQueue(db)) {
      resumed.open();
      var reopened = resumed.beginCapturedWalk(KEY, PLAN, false);
      assertEquals(2, reopened.enumerationEpoch(), "an interrupted capture resumes in a new epoch");
      assertTrue(reopened.capturedPlan());
      assertNull(reopened.manifestSha256());
      assertNull(reopened.plannedUnits());

      assertEquals(2, resumed.enqueueRecordedEntries(KEY, reopened.enumerationEpoch(), List.of(
          captured(retained, H3), captured(added, H4)), null));
      assertTrue(resumed.pollPending(10).isEmpty(),
          "the permissive force decision still cannot claim an open capture");

      var complete = resumed.closeRecordedWalkEnumeration(
          KEY, reopened.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.COMPLETE);
      assertNotNull(complete.manifestSha256());
      assertTrue(complete.manifestSha256().matches("[0-9a-f]{64}"));
      assertEquals(3L, complete.plannedUnits());
      manifest = complete.manifestSha256();

      var claims = resumed.pollPending(10);
      assertEquals(2, claims.size(), "the new member joins while the disappeared member is retired");
      var retainedClaim = claims.stream()
          .filter(claim -> normalized(claim.path()).equals(normalized(retained))).findFirst().orElseThrow();
      var addedClaim = claims.stream()
          .filter(claim -> normalized(claim.path()).equals(normalized(added))).findFirst().orElseThrow();
      assertEquals(H1, retainedClaim.plannedSourceSha256(),
          "re-observation cannot replace the original captured H1");
      assertEquals(H4, addedClaim.plannedSourceSha256(), "a newly observed member joins the finite plan");
      assertTrue(retainedClaim.recordedForce());
      assertTrue(addedClaim.recordedForce());

      for (var claim : claims) {
        String contentHash = normalized(claim.path()).equals(normalized(retained)) ? H1 : H4;
        resumed.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(claim, null, contentHash)),
            success());
      }
      assertFalse(resumed.hasIssuedRecordedClaims(KEY));

      var sealed = resumed.trySealRecordedWalk(KEY);
      assertNotNull(sealed.sealedAt());
      assertEquals(manifest, sealed.manifestSha256());
      assertEquals(3L, sealed.plannedUnits());
      assertEquals(2, sealed.completedUnits());
      firstSettlement = resumed.capturedWalkSettlement(KEY).orElseThrow();
      assertEquals(manifest, firstSettlement.manifestSha256());
      assertEquals(3L, firstSettlement.plannedUnits());
      assertEquals(1, firstSettlement.gaps().size());
      JobQueue.CapturedUnit missing = firstSettlement.gaps().getFirst();
      assertEquals(H2, missing.plannedSourceSha256());
      assertEquals("SKIPPED", missing.coverage());
      assertNull(missing.contentHash());
      assertEquals(0L, firstSettlement.failedEvents());
      assertEquals(0L, firstSettlement.supersededEvents());
    }

    try (var reopened = forceAllowedQueue(db)) {
      reopened.open();
      var durable = reopened.recordedWalk(KEY).orElseThrow();
      assertEquals(manifest, durable.manifestSha256());
      assertEquals(3L, durable.plannedUnits());
      assertEquals(firstSettlement, reopened.capturedWalkSettlement(KEY).orElseThrow(),
          "the sealed manifest and settlement survive queue close and reopen");
    }
  }

  @Test
  void captureRejectsMissingSourceHashWithoutPartiallyAdmittingTheBatch() throws Exception {
    Path db = temp.resolve("missing-source-hash.db");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      var opened = queue.beginCapturedWalk(KEY, PLAN, true);
      assertThrows(IllegalArgumentException.class, () -> queue.enqueueRecordedEntries(
          KEY, opened.enumerationEpoch(), List.of(
              captured(temp.resolve("first.txt"), H1), JobQueue.EnqueueEntry.ofUnknownSize(temp.resolve("missing.txt"))),
          null));
      assertEquals(opened.revision(), queue.recordedWalk(KEY).orElseThrow().revision());
      assertEquals(0, count(db, "SELECT count(*) FROM jobs WHERE scan_id = '" + KEY + "'"));
    }
  }

  @ParameterizedTest
  @EnumSource(value = JobQueue.WalkEnumerationOutcome.class, names = {"FAILED", "CANCELLED"})
  void unsuccessfulCaptureNeverClaimsAndHasNoCompletedManifest(JobQueue.WalkEnumerationOutcome outcome)
      throws Exception {
    Path db = temp.resolve(outcome.name().toLowerCase(java.util.Locale.ROOT) + ".db");
    try (var queue = forceAllowedQueue(db)) {
      queue.open();
      var opened = queue.beginCapturedWalk(KEY, PLAN, true);
      queue.enqueueRecordedEntries(KEY, opened.enumerationEpoch(), List.of(captured(temp.resolve("member.txt"), H1)), null);
      assertTrue(queue.pollPending(1).isEmpty(), "captured members stay fenced before enumeration closes");

      var closed = queue.closeRecordedWalkEnumeration(KEY, opened.enumerationEpoch(), outcome);
      assertEquals(outcome, closed.enumerationOutcome());
      assertNull(closed.manifestSha256());
      assertNull(closed.plannedUnits());
      assertTrue(queue.pollPending(1).isEmpty(), "an unsuccessful captured enumeration grants no claim");

      var sealed = queue.trySealRecordedWalk(KEY);
      assertNotNull(sealed.sealedAt());
      assertEquals(outcome, sealed.enumerationOutcome());
      assertNull(sealed.manifestSha256());
      assertNull(sealed.plannedUnits());
      assertEquals(1, queue.sealedRecordedWalkReceipt(KEY).orElseThrow().version(),
          "failed/cancelled enumeration retains only the ordinary v1 refusal receipt");
      assertTrue(queue.capturedWalkSettlement(KEY).isEmpty());
    }
  }

  @Test
  void corruptCapturedModeCannotBeReopenedAsStreaming() throws Exception {
    Path db = temp.resolve("corrupt-mode.db");
    try (var queue = forceAllowedQueue(db)) {
      queue.open();
      var opened = queue.beginCapturedWalk(KEY, PLAN, true);
      queue.enqueueRecordedEntries(KEY, opened.enumerationEpoch(), List.of(captured(temp.resolve("mode.txt"), H1)), null);
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
        statement.execute("PRAGMA ignore_check_constraints = ON");
        statement.execute("UPDATE ingestion_walk_progress SET captured_plan = 2");
      }
      assertThrows(JobQueue.RecordedWalkGapException.class, () -> queue.recordedWalk(KEY));
      assertThrows(JobQueue.RecordedWalkGapException.class, () -> queue.beginRecordedWalk(KEY, PLAN, false));
      assertTrue(queue.pollPending(1).isEmpty());
      assertEquals(0, count(db, "SELECT count(*) FROM jobs WHERE state = 'PROCESSING'"));
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"manifest", "member-hash", "mode"})
  void corruptCompletedCaptureIsRefusedBeforeClaim(String mutation) throws Exception {
    Path db = temp.resolve("corrupt-" + mutation + ".db");
    try (var queue = forceAllowedQueue(db)) {
      queue.open();
      var opened = queue.beginCapturedWalk(KEY, PLAN, true);
      queue.enqueueRecordedEntries(KEY, opened.enumerationEpoch(), List.of(captured(temp.resolve("member.txt"), H1)), null);
      queue.closeRecordedWalkEnumeration(KEY, opened.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.COMPLETE);
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db); var statement = connection.createStatement()) {
        switch (mutation) {
          case "manifest" -> statement.execute("UPDATE ingestion_walk_progress SET manifest_sha256 = 'invalid'");
          case "member-hash" -> statement.execute("UPDATE jobs SET planned_source_sha256 = NULL");
          case "mode" -> {
            statement.execute("PRAGMA ignore_check_constraints = ON");
            statement.execute("UPDATE ingestion_walk_progress SET captured_plan = 2");
          }
          default -> throw new AssertionError(mutation);
        }
      }
      if (!mutation.equals("member-hash")) {
        assertThrows(JobQueue.RecordedWalkGapException.class, () -> queue.recordedWalk(KEY));
      }
      assertTrue(queue.pollPending(1).isEmpty(), "corruption must refuse before an indexing effect can run");
      assertEquals(0, count(db, "SELECT count(*) FROM jobs WHERE state = 'PROCESSING'"));
      assertFalse(queue.hasIssuedRecordedClaims(KEY));
    }
  }

  @Test
  void completeEmptyCaptureSealsValidDurableEmptySettlement() throws Exception {
    Path db = temp.resolve("empty-capture.db");
    JobQueue.CapturedWalkSettlement settlement;
    String manifest;
    try (var queue = forceAllowedQueue(db)) {
      queue.open();
      var opened = queue.beginCapturedWalk(KEY, PLAN, true);
      assertTrue(queue.pollPending(1).isEmpty());
      var complete = queue.closeRecordedWalkEnumeration(
          KEY, opened.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.COMPLETE);
      assertNotNull(complete.manifestSha256());
      assertTrue(complete.manifestSha256().matches("[0-9a-f]{64}"));
      assertEquals(0L, complete.plannedUnits());
      manifest = complete.manifestSha256();

      var sealed = queue.trySealRecordedWalk(KEY);
      assertNotNull(sealed.sealedAt());
      assertEquals(2, queue.sealedRecordedWalkReceipt(KEY).orElseThrow().version());
      settlement = queue.capturedWalkSettlement(KEY).orElseThrow();
      assertEquals(manifest, settlement.manifestSha256());
      assertEquals(0L, settlement.plannedUnits());
      assertTrue(settlement.gaps().isEmpty());
      assertTrue(settlement.processingHistory().isEmpty());
      assertEquals(0L, settlement.failedEvents());
      assertEquals(0L, settlement.supersededEvents());
      assertTrue(settlement.sha256().matches("[0-9a-f]{64}"));
    }

    try (var reopened = forceAllowedQueue(db)) {
      reopened.open();
      assertEquals(manifest, reopened.recordedWalk(KEY).orElseThrow().manifestSha256());
      assertEquals(settlement, reopened.capturedWalkSettlement(KEY).orElseThrow());
    }
  }

  private static SqliteJobQueue forceAllowedQueue(Path db) {
    return new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW_FORCE);
  }

  private static JobQueue.EnqueueEntry captured(Path path, String sourceHash) {
    return new JobQueue.EnqueueEntry(path, JobQueue.UNKNOWN_SIZE_BYTES, null, sourceHash);
  }

  private static String normalized(Path path) {
    return io.justsearch.indexerworker.util.PathNormalizer.normalizePath(path.toAbsolutePath().toString());
  }

  private static IngestionOutcome success() {
    return IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE);
  }

  private static long count(Path db, String sql) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
      assertTrue(rows.next());
      return rows.getLong(1);
    }
  }
}
