/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Rediscovery covers an accepted revision without spending its claim, retry budget or receipt. */
final class RediscoveryAdmissionTest {
  @TempDir Path temporary;

  @ParameterizedTest
  @ValueSource(strings = {"unchanged", "size", "mtime", "planned-hash", "collection", "root"})
  void legacyFailuresKeepWaitsOnlyWithoutEvidenceOfChange(String change) throws Exception {
    Path root = Files.createDirectory(temporary.resolve("sources"));
    Path file = Files.writeString(root.resolve("legacy.txt"), "old!");
    Path db = temporary.resolve("legacy.db");
    var stamp = Files.getLastModifiedTime(file);
    try (var queue = open(db)) {
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(temporary)), "docs");
    }
    sql(db, "UPDATE jobs SET state='FAILED',attempts=5,retry_after=9999999999999");
    if (change.equals("planned-hash")) {
      sql(db, "UPDATE jobs SET planned_source_sha256='" + SourceContentHash.sha256(file) + "'");
    }
    sql(db, "ALTER TABLE jobs DROP COLUMN force_reindex");
    sql(db, "ALTER TABLE jobs DROP COLUMN admitted_source_sha256");
    sql(db, "PRAGMA user_version=25");
    switch (change) {
      case "size" -> Files.writeString(file, "repaired larger source");
      case "mtime" -> Files.setLastModifiedTime(file,
          java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10_000));
      case "planned-hash" -> {
        Files.writeString(file, "new!");
        Files.setLastModifiedTime(file, stamp);
      }
      default -> { }
    }
    try (var queue = open(db)) {
      var before = row(db);
      var entry = JobQueue.EnqueueEntry.stat(file).withinRoot(change.equals("root") ? root : temporary);
      boolean changed = !change.equals("unchanged");
      assertEquals(changed ? 1 : 0, queue.enqueueMaintenanceScanEntries(
          List.of(entry), change.equals("collection") ? "other" : "docs", null));
      if (changed) {
        assertEquals("PENDING", row(db).get("state"));
        assertNotEquals(before.get("unit_revision"), row(db).get("unit_revision"));
      } else assertEquals(before, row(db), "missing new fields alone must preserve legacy retry history");
    }
  }

  @ParameterizedTest
  @CsvSource({"PENDING,SANDBOX_FAILED,600000", "PENDING,SANDBOX_FAILED,-1000", "PENDING,PARSER_FAILED,-1000",
      "PENDING,IO_FAILED,-1000", "PENDING,PARSER_TIMEOUT,600000",
      "PENDING,WRITE_FAILED,600000", "FAILED,SANDBOX_FAILED,0",
      "RETRY_EXHAUSTED,SANDBOX_FAILED,0"})
  void unchangedFailureRetainsTheEntireRetryRun(String state, String outcome, long wait) throws Exception {
    Path file = Files.writeString(temporary.resolve("source.txt"), "unchanged");
    Path db = temporary.resolve("queue.db");
    try (var queue = open(db)) {
      var entry = JobQueue.EnqueueEntry.stat(file,
          new JobQueue.EnqueueProvenance("user", "HTTP")).withinRoot(temporary);
      assertEquals(1, queue.enqueueEntries(List.of(entry), "docs"));
      sql(db, "UPDATE jobs SET state='" + state + "', attempts=4, retry_after="
          + (System.currentTimeMillis() + wait) + ", first_failed_at=1234, error_message='parser failed', "
          + "last_outcome_class='" + outcome + "',last_reason_code='failure',last_retry_policy='"
          + (outcome.equals("WRITE_FAILED") ? "DEFER_WITHOUT_ATTEMPT" : "RETRY_WITH_BACKOFF") + "'");
      Map<String, String> before = row(db);
      var rediscovered = JobQueue.EnqueueEntry.stat(file,
          new JobQueue.EnqueueProvenance("system", "SYSTEM_INTERNAL")).withinRoot(temporary);
      assertEquals(0, queue.enqueueMaintenanceScanEntries(List.of(rediscovered), "docs", "maintenance"));
      assertEquals(before, row(db), "rediscovery must preserve every saved retry and attribution field");
    }
    try (var queue = open(db)) {
      Map<String, String> before = row(db);
      assertEquals(0, queue.enqueueMaintenanceScanEntries(
          List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(temporary)), "docs", null));
      assertEquals(before, row(db), "the same rule holds after opening the persisted queue");
    }
  }

  @Test
  void pendingAndClaimedUnchangedWorkRemainCovered() throws Exception {
    Path file = Files.writeString(temporary.resolve("in-flight.txt"), "same");
    Path second = Files.writeString(temporary.resolve("new.txt"), "new");
    Path db = temporary.resolve("claims.db");
    try (var queue = open(db)) {
      var entry = JobQueue.EnqueueEntry.stat(file).withinRoot(temporary);
      assertEquals(1, queue.enqueueEntries(List.of(entry)));
      Map<String, String> pending = row(db);
      assertEquals(0, queue.enqueueMaintenanceScanEntries(List.of(entry), null, null));
      assertEquals(pending, row(db));
      var claim = queue.pollPending(1).getFirst();
      Map<String, String> claimed = row(db);
      assertEquals(0, queue.enqueueMaintenanceScanEntries(List.of(entry), null, null));
      assertEquals(claimed, row(db));
      assertTrue(queue.ownsClaimForPublication(claim));
      assertEquals(1, queue.enqueueMaintenanceScanEntries(
          List.of(entry, JobQueue.EnqueueEntry.stat(second).withinRoot(temporary)), null, null));
      assertEquals(2, queue.queueDepth(), "the covered claim and one new file count once each");
      assertEquals(new JobQueue.PendingBytes(Files.size(file) + Files.size(second), 0), queue.pendingBytes());
      assertTrue(queue.markClaimDone(claim, skipped(), null));
    }
  }

  @Test
  void activeRecordedMemberKeepsRetryHistoryAndItsWalkReceipt() throws Exception {
    Path file = Files.writeString(temporary.resolve("recorded.txt"), "recorded source");
    Path db = temporary.resolve("recorded.db");
    String key = "01900000-0000-7000-8000-000000000061";
    try (var queue = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var progress = queue.beginRecordedWalk(key, "a".repeat(64), true);
      var entry = JobQueue.EnqueueEntry.stat(file).withinRoot(temporary);
      assertEquals(1, queue.enqueueRecordedEntries(key, progress.enumerationEpoch(), List.of(entry), "docs"));
      sql(db, "UPDATE jobs SET attempts=3,retry_after=1234,first_failed_at=123,last_outcome_class='SANDBOX_FAILED'");
      Map<String, String> before = row(db);
      var walk = queue.recordedWalk(key);
      assertEquals(0, queue.enqueueMaintenanceScanEntries(List.of(entry), "docs", null));
      assertEquals(before, row(db));
      assertEquals(walk, queue.recordedWalk(key));
    }
  }

  @Test
  void changedBytesWithEqualMetadataReplaceFailureAndExplicitRetryStillResetsIt() throws Exception {
    Path file = Files.writeString(temporary.resolve("changed.txt"), "old!");
    var stamp = Files.getLastModifiedTime(file);
    Path db = temporary.resolve("changed.db");
    try (var queue = open(db)) {
      var entry = JobQueue.EnqueueEntry.stat(file).withinRoot(temporary);
      queue.enqueueEntries(List.of(entry));
      sql(db, "UPDATE jobs SET state='FAILED',attempts=6,first_failed_at=1234");
      String oldRevision = row(db).get("unit_revision");
      Files.writeString(file, "new!");
      Files.setLastModifiedTime(file, stamp);
      assertEquals(1, queue.enqueueMaintenanceScanEntries(List.of(entry), null, null));
      assertNotEquals(oldRevision, row(db).get("unit_revision"));
      assertEquals("0", row(db).get("attempts"));
      sql(db, "UPDATE jobs SET state='FAILED',attempts=6,first_failed_at=1234");
      String failedRevision = row(db).get("unit_revision");
      assertEquals(1, queue.enqueueEntries(List.of(entry)));
      assertNotEquals(failedRevision, row(db).get("unit_revision"));
      assertEquals("0", row(db).get("attempts"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"PENDING", "PROCESSING", "DONE", "FAILED", "RETRY_EXHAUSTED"})
  void identicalCandidateEnumerationRetainsRevisionAndReplayWitness(String state) throws Exception {
    Path file = Files.writeString(temporary.resolve("candidate.txt"), "candidate bytes");
    Path db = temporary.resolve("migration.db");
    var entry = JobQueue.EnqueueEntry.stat(file).withinRoot(temporary);
    Map<String, String> saved;
    List<SwitchBufferCapableQueue.SwitchBufferOp> receipts;
    try (var queue = open(db)) {
      assertEquals(1, queue.enqueueEnumeratedFilesForGeneration("green", List.of(entry), "docs"));
      sql(db, "UPDATE jobs SET state='" + state + "',attempts=3,retry_after=1234,first_failed_at=123");
      saved = row(db);
      receipts = queue.listSwitchBufferOpsStrictForGeneration("green");
      assertEquals(1, queue.enqueueEnumeratedFilesForGeneration("green", List.of(entry), "docs"));
      assertEquals(saved, row(db));
      assertEquals(receipts, queue.listSwitchBufferOpsStrictForGeneration("green"));
    }
    try (var queue = open(db)) {
      assertEquals(1, queue.enqueueEnumeratedFilesForGeneration("green", List.of(entry), "docs"));
      assertEquals(saved, row(db));
      assertEquals(receipts, queue.listSwitchBufferOpsStrictForGeneration("green"));
      assertEquals(1, queue.enqueueEnumeratedFilesForGeneration("next", List.of(entry), "docs"));
      assertNotEquals(saved.get("unit_revision"), row(db).get("unit_revision"));
    }
  }

  @Test
  void changedCandidateSourceAndCollectionAreStillAdmitted() throws Exception {
    Path file = Files.writeString(temporary.resolve("migration-change.txt"), "first");
    Path db = temporary.resolve("migration-change.db");
    try (var queue = open(db)) {
      var entry = JobQueue.EnqueueEntry.stat(file).withinRoot(temporary);
      queue.enqueueEnumeratedFilesForGeneration("green", List.of(entry), "one");
      String first = row(db).get("unit_revision");
      assertEquals(1, queue.enqueueEnumeratedFilesForGeneration("green", List.of(entry), "two"));
      assertNotEquals(first, row(db).get("unit_revision"));
      String second = row(db).get("unit_revision");
      Files.writeString(file, "other");
      assertEquals(1, queue.enqueueEnumeratedFilesForGeneration("green", List.of(entry), "two"));
      assertNotEquals(second, row(db).get("unit_revision"));
      assertEquals(SourceContentHash.sha256(file), row(db).get("planned_source_sha256"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"collection", "root", "generation", "force"})
  void candidateRediscoveryDoesNotPreserveADifferentObligation(String change) throws Exception {
    Path root = Files.createDirectory(temporary.resolve("sources"));
    Path file = Files.writeString(root.resolve("source.txt"), "same bytes");
    try (var queue = open(temporary.resolve("scope.db"))) {
      var entry = JobQueue.EnqueueEntry.stat(file).withinRoot(root);
      assertEquals(1, queue.enqueueEnumeratedFilesForGeneration("green", List.of(entry), "docs"));
      var claim = queue.pollPending(1).getFirst();
      var receipt = queue.listSwitchBufferOpsStrictForGeneration("green");
      assertTrue(queue.enqueueMaintenanceFileForGeneration("green", entry, "docs"));
      assertTrue(queue.ownsClaimForPublication(claim));
      assertEquals(receipt, queue.listSwitchBufferOpsStrictForGeneration("green"));
      var replacement = change.equals("root") ? entry.withinRoot(temporary)
          : change.equals("force") ? entry.withForceReindex(true) : entry;
      assertTrue(queue.enqueueMaintenanceFileForGeneration(change.equals("generation") ? "next" : "green",
          replacement, change.equals("collection") ? "other" : "docs"));
      assertFalse(queue.ownsClaimForPublication(claim));
      assertFalse(queue.markClaimDone(claim, skipped(), null));
      var next = queue.pollPending(1).getFirst();
      assertNotEquals(claim.unitRevision(), next.unitRevision());
      if (change.equals("force")) assertTrue(next.forceReindex());
    }
  }

  private static SqliteJobQueue open(Path db) throws Exception {
    var queue = new SqliteJobQueue(db);
    queue.open();
    return queue;
  }

  private static IngestionOutcome skipped() {
    return IngestionOutcome.of(IngestionOutcomeClass.SKIPPED_POLICY, IngestionReasonCodes.UNCHANGED,
        IngestionRetryPolicy.NONE, "unchanged");
  }

  private static void sql(Path db, String sql) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement()) { statement.executeUpdate(sql); }
  }

  private static Map<String, String> row(Path db) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM jobs ORDER BY path LIMIT 1")) {
      assertTrue(rows.next());
      var result = new LinkedHashMap<String, String>();
      for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
        result.put(rows.getMetaData().getColumnName(i), rows.getString(i));
      }
      return result;
    }
  }
}
