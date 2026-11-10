/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.knowledge.IngestCollectionPolicy;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

final class ExtractionAttemptPolicyQueueTest {
  @TempDir Path temporary;

  @ParameterizedTest
  @ValueSource(strings = {"before-admission", "after-admission", "processing", "after-outcome"})
  void restartsRetainTheSinglePolicyAttempt(String point) throws Exception {
    Path file = Files.writeString(temporary.resolve("file.txt"), "source bytes");
    Path db = temporary.resolve("jobs.db");
    try (var queue = open(db, "old")) {
      queue.enqueueEntries(List.of(entry(file)));
      queue.markFailed(file, failure(IngestionOutcomeClass.PARSER_FAILED), ledger("old"));
      queue.useExtractionPolicy("current");
      if (!point.equals("before-admission")) {
        assertEquals(1, admit(queue, file));
        if (point.equals("processing") || point.equals("after-outcome")) {
          var claim = queue.pollPending(1).getFirst();
          if (point.equals("after-outcome"))
            assertTrue(queue.markClaimFailed(claim, failure(IngestionOutcomeClass.PARSER_FAILED), ledger("current")));
        }
      }
    }
    try (var queue = open(db, "current")) {
      queue.recoverStuckJobs();
      assertEquals(point.equals("before-admission") ? 1 : 0, admit(queue, file));
      if (!point.equals("after-outcome")) {
        var claims = queue.pollPending(2);
        assertEquals(1, claims.size());
        assertTrue(queue.markClaimFailed(claims.getFirst(), failure(IngestionOutcomeClass.PARSER_FAILED), ledger("current")));
      }
      assertEquals(0, admit(queue, file));
      assertTrue(queue.pollPending(1).isEmpty());
      assertEquals("current", queue.watcherReconciliationRow(PathNormalizer.normalizeKey(file)).get("extraction_policy_id"));
    }
  }

  @ParameterizedTest
  @EnumSource(value = IngestionOutcomeClass.class,
      names = {"IO_FAILED", "WRITE_FAILED", "SANDBOX_FAILED", "SKIPPED_POLICY", "DEFERRED_POLICY"})
  void policyChangesKeepNonExtractionFailures(IngestionOutcomeClass outcomeClass) throws Exception {
    Path file = Files.writeString(temporary.resolve("file.txt"), "source bytes");
    try (var queue = open(temporary.resolve("jobs.db"), "old")) {
      queue.enqueueEntries(List.of(entry(file)));
      queue.markFailed(file, failure(outcomeClass), ledger("old"));
      var before = queue.watcherReconciliationRow(PathNormalizer.normalizeKey(file));
      queue.useExtractionPolicy("new");
      assertEquals(0, admit(queue, file));
      assertEquals(before, queue.watcherReconciliationRow(PathNormalizer.normalizeKey(file)));
    }
  }

  @Test
  void changedPolicyDoesNotResetTransientParserBackoff() throws Exception {
    Path file = Files.writeString(temporary.resolve("file.txt"), "source bytes");
    try (var queue = open(temporary.resolve("jobs.db"), "old")) {
      queue.enqueueEntries(List.of(entry(file)));
      queue.markFailed(file, IngestionOutcome.of(IngestionOutcomeClass.PARSER_TIMEOUT,
          "PARSER_TIMEOUT", IngestionRetryPolicy.RETRY_WITH_BACKOFF), ledger("old"));
      var before = queue.watcherReconciliationRow(PathNormalizer.normalizeKey(file));
      queue.useExtractionPolicy("new");
      assertEquals(0, admit(queue, file));
      assertEquals(before, queue.watcherReconciliationRow(PathNormalizer.normalizeKey(file)));
    }
  }

  @Test
  void oldPlanDoesNotCertifyCurrentBytesWithRestoredMetadata() throws Exception {
    Path file = Files.writeString(temporary.resolve("file.txt"), "old!");
    var stamp = Files.getLastModifiedTime(file);
    String oldHash = SourceContentHash.sha256(file);
    var planned = new JobQueue.EnqueueEntry(file, 4, null, oldHash).withinRoot(temporary);
    try (var queue = open(temporary.resolve("jobs.db"), "same")) {
      queue.enqueueEntries(List.of(planned));
      queue.markFailed(file, failure(IngestionOutcomeClass.PARSER_FAILED), ledger("same"));
      String revision = queue.watcherReconciliationRow(PathNormalizer.normalizeKey(file)).get("unit_revision");
      Files.writeString(file, "new!");
      Files.setLastModifiedTime(file, stamp);
      assertEquals(1, queue.enqueueMaintenanceScanEntries(List.of(planned), null, null));
      assertNotEquals(revision, queue.watcherReconciliationRow(PathNormalizer.normalizeKey(file)).get("unit_revision"));
    }
  }

  @Test
  void unwatchedAndUnavailableSourcesAreNotRevived() throws Exception {
    Path file = Files.writeString(temporary.resolve("file.txt"), "source bytes");
    try (var queue = open(temporary.resolve("jobs.db"), "old")) {
      queue.enqueueEntries(List.of(entry(file)));
      queue.markFailed(file, failure(IngestionOutcomeClass.PARSER_FAILED), ledger("old"));
      var before = queue.watcherReconciliationRow(PathNormalizer.normalizeKey(file));
      queue.useExtractionPolicy("new");
      queue.useDeclaredCollectionRoots(List::of);
      assertEquals(0, admit(queue, file));
      assertEquals(before, queue.watcherReconciliationRow(PathNormalizer.normalizeKey(file)));
      queue.useDeclaredCollectionRoots(() -> List.of(new IngestCollectionPolicy.RootBinding(temporary, null)));
      Files.delete(file);
      assertEquals(0, admit(queue, file));
      assertEquals(before, queue.watcherReconciliationRow(PathNormalizer.normalizeKey(file)));
    }
  }

  @Test
  void candidateMaintenanceReplacesStaleFailureReceiptAndStaysSettled() throws Exception {
    Path file = Files.writeString(temporary.resolve("file.txt"), "source bytes");
    Path db = temporary.resolve("jobs.db");
    try (var queue = open(db, "old")) {
      assertTrue(queue.enqueueMaintenanceFileForGeneration("green", JobQueue.EnqueueEntry.stat(file,
          new JobQueue.EnqueueProvenance("user", "HTTP")).withinRoot(temporary), null));
      queue.markFailed(file, failure(IngestionOutcomeClass.PARSER_FAILED), ledger("old"));
      var oldReceipt = queue.listSwitchBufferOpsStrictForGeneration("green");
      queue.useExtractionPolicy("new");
      assertTrue(queue.enqueueMaintenanceFileForGeneration("green", JobQueue.EnqueueEntry.stat(file,
          new JobQueue.EnqueueProvenance("system", "SYSTEM_INTERNAL")).withinRoot(temporary), null));
      assertNotEquals(oldReceipt, queue.listSwitchBufferOpsStrictForGeneration("green"));
      var claim = queue.pollPending(1).getFirst();
      assertEquals("user", claim.provenance().originator());
      assertEquals("HTTP", claim.provenance().transport());
      assertTrue(queue.markClaimFailed(claim, failure(IngestionOutcomeClass.PARSER_FAILED), ledger("new")));
      var failed = queue.watcherReconciliationRow(PathNormalizer.normalizeKey(file));
      assertTrue(queue.enqueueMaintenanceFileForGeneration("green", entry(file), null));
      assertEquals(failed, queue.watcherReconciliationRow(PathNormalizer.normalizeKey(file)));
      assertTrue(queue.enqueueMaintenanceFileForGeneration("next", entry(file), null));
      assertEquals(failed, queue.watcherReconciliationRow(PathNormalizer.normalizeKey(file)));
      assertEquals(1, queue.enqueueEnumeratedFilesForGeneration("next", List.of(entry(file)), null));
      assertTrue(queue.pollPending(1).isEmpty());
      assertEquals(0, admit(queue, file));
    }
    try (var queue = open(db, "new")) {
      assertTrue(queue.enqueueMaintenanceFileForGeneration("green", entry(file), null));
      assertTrue(queue.pollPending(1).isEmpty());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sealedWatchedRebuildCanRetryWithoutRevivingARecordedImport(boolean capturedRebuild) throws Exception {
    Path file = Files.writeString(temporary.resolve("file.txt"), "source bytes");
    Path db = temporary.resolve("jobs.db");
    String scan = "01900000-0000-7000-8000-000000000061";
    JobQueue.WalkProgress settled;
    try (var queue = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      queue.useDeclaredCollectionRoots(() -> List.of(new IngestCollectionPolicy.RootBinding(temporary, null)));
      queue.useExtractionPolicy("old");
      var walk = capturedRebuild ? queue.beginCapturedWalk(scan, "a".repeat(64), true)
          : queue.beginRecordedWalk(scan, "a".repeat(64), true);
      var planned = new JobQueue.EnqueueEntry(file, Files.size(file), null, SourceContentHash.sha256(file)).withinRoot(temporary);
      assertEquals(1, queue.enqueueRecordedEntries(scan, walk.enumerationEpoch(), List.of(planned), null));
      queue.closeRecordedWalkEnumeration(scan, walk.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.COMPLETE);
      assertTrue(queue.markClaimFailed(queue.pollPending(1).getFirst(), failure(IngestionOutcomeClass.PARSER_FAILED), ledger("old")));
      settled = queue.trySealRecordedWalk(scan);
      assertNotNull(settled.sealedAt());
      queue.useExtractionPolicy("new");
      assertEquals(capturedRebuild ? 1 : 0, admit(queue, file));
      if (capturedRebuild) {
        var claim = queue.pollPending(1).getFirst();
        assertEquals(scan, claim.scanId());
        assertNull(claim.walkEpoch(), "new work cannot rewrite the sealed rebuild's membership");
        assertTrue(queue.markClaimFailed(claim, failure(IngestionOutcomeClass.PARSER_FAILED), ledger("new")));
      }
      assertEquals(settled, queue.recordedWalk(scan).orElseThrow());
      assertEquals(0, admit(queue, file));
    }
    try (var queue = open(db, "new")) {
      assertEquals(0, admit(queue, file));
      assertEquals(settled, queue.recordedWalk(scan).orElseThrow());
      assertTrue(queue.pollPending(1).isEmpty());
      if (capturedRebuild) {
        assertEquals(1, queue.reenqueue(entry(file)).accepted());
        assertTrue(queue.markClaimDone(queue.pollPending(1).getFirst(),
            IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE), ledger("new")));
        queue.useExtractionPolicy("next");
        assertEquals(1, admit(queue, file), "an explicit successful retry keeps scan attribution usable by later maintenance");
        var next = queue.pollPending(1).getFirst();
        assertEquals(scan, next.scanId());
        assertTrue(queue.markClaimDone(next,
            IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE), ledger("next")));
        assertEquals(1, queue.enqueueEntries(List.of(entry(file)), "docs", null));
        assertTrue(queue.markClaimDone(queue.pollPending(1).getFirst(),
            IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE), ledger("next")));
        queue.useExtractionPolicy("after-batch");
        assertEquals(1, admit(queue, file), "explicit batch admission also keeps the scan usable");
        assertEquals(settled, queue.recordedWalk(scan).orElseThrow());
      }
    }
  }

  @Test
  void watchedFailureRemainsSettledAfterBothRetentionPasses() throws Exception {
    Path file = Files.writeString(temporary.resolve("file.txt"), "source bytes");
    Path db = temporary.resolve("jobs.db");
    try (var queue = open(db, "current")) {
      queue.enqueueEntries(List.of(entry(file)));
      queue.markFailed(file, failure(IngestionOutcomeClass.PARSER_FAILED), ledger("current"));
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
          var statement = connection.createStatement()) {
        statement.execute("UPDATE jobs SET last_updated=1");
        statement.execute("UPDATE ingestion_ledger SET observed_at=1");
      }
      assertEquals(1, queue.cleanupOldLedgerEvents(30));
      assertEquals(0, queue.cleanupOldJobs(30), "the queue row owns once-per-policy memory");
    }
    try (var queue = open(db, "current")) {
      assertEquals(0, admit(queue, file));
      assertTrue(queue.pollPending(1).isEmpty());
      queue.useDeclaredCollectionRoots(List::of);
      assertEquals(1, queue.cleanupOldJobs(30), "unwatching releases ordinary age cleanup");
    }
  }

  @Test
  void missingOutcomePolicyCannotEraseTheAdmittedAttemptPolicy() throws Exception {
    Path file = Files.writeString(temporary.resolve("file.txt"), "source bytes");
    Path db = temporary.resolve("jobs.db");
    try (var queue = open(db, "old")) {
      queue.enqueueEntries(List.of(entry(file)));
      queue.markFailed(file, failure(IngestionOutcomeClass.PARSER_FAILED));
      queue.useExtractionPolicy("new");
      assertEquals(1, admit(queue, file));
      queue.markClaimFailed(queue.pollPending(1).getFirst(), failure(IngestionOutcomeClass.PARSER_FAILED), null);
    }
    try (var queue = open(db, "new")) {
      assertEquals(0, admit(queue, file));
      assertEquals("new", queue.watcherReconciliationRow(PathNormalizer.normalizeKey(file)).get("extraction_policy_id"));
    }
  }

  @Test
  void ledgerPolicyIsBoundToTheFailureTransitionAndSurvivesPruning() throws Exception {
    Path file = Files.writeString(temporary.resolve("file.txt"), "source bytes");
    Path db = temporary.resolve("jobs.db");
    try (var queue = open(db, "old")) {
      queue.enqueueEntries(List.of(entry(file)));
      queue.markFailed(file, failure(IngestionOutcomeClass.PARSER_FAILED), ledger("old"));
      queue.recordIngestionEvent(file, failure(IngestionOutcomeClass.IO_FAILED), ledger("new"));
      queue.useExtractionPolicy("new");
      assertEquals(1, admit(queue, file));
      var claim = queue.pollPending(1).getFirst();
      queue.markClaimFailed(claim, failure(IngestionOutcomeClass.PARSER_FAILED), ledger("new"));
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement()) { statement.execute("DELETE FROM ingestion_ledger"); }
    try (var queue = open(db, "new")) { assertEquals(0, admit(queue, file)); }
  }

  private SqliteJobQueue open(Path db, String policy) throws Exception {
    var queue = new SqliteJobQueue(db);
    queue.useDeclaredCollectionRoots(() -> List.of(new IngestCollectionPolicy.RootBinding(temporary, null)));
    queue.useExtractionPolicy(policy);
    queue.open();
    return queue;
  }

  private JobQueue.EnqueueEntry entry(Path file) {
    return JobQueue.EnqueueEntry.stat(file).withinRoot(temporary);
  }

  private int admit(SqliteJobQueue queue, Path file) {
    return queue.enqueueMaintenanceScanEntries(List.of(entry(file)), null, null);
  }

  private static IngestionOutcome failure(IngestionOutcomeClass outcomeClass) {
    return IngestionOutcome.of(outcomeClass, outcomeClass.name(), IngestionRetryPolicy.NONE);
  }

  private static JobQueue.IngestionLedgerEntry ledger(String policy) {
    return new JobQueue.IngestionLedgerEntry(null, null, null, null, "FILE", "FAILED", policy, "parser");
  }
}
