/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.PathPrefixScope;
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

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
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
      assertTrue(queue.enqueueMaintenanceFileForGeneration("green", entry, "docs"));
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

  private static final String CAPTURE = "01994180-0000-7000-8000-000000000164";
  private static final String TARGET = "g-" + CAPTURE;

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @CsvSource({"false,false,false", "true,false,false", "true,true,false", "false,false,true"})
  void firstMaintenanceAttachesCapturedReceiptWithoutSpendingClaim(
      boolean claimed, boolean force, boolean resumed) throws Exception {
    Path file = Files.writeString(temporary.resolve("captured.txt"), "captured bytes");
    Path db = temporary.resolve("captured.db");
    try (var queue = capturedQueue(db)) {
      capture(queue, file, "docs", force, resumed, temporary);
      JobQueue.IndexJob claim = claimed ? queue.pollPending(1).getFirst() : null;
      if (claim != null) assertTrue(claim.recordedForce());
      sql(db, "UPDATE jobs SET attempts=3,retry_after=9999999999999,first_failed_at=123,error_message='retained history'");
      var before = row(db);
      assertEquals(force ? "1" : "0", before.get("force_reindex"));
      var walk = queue.recordedWalk(CAPTURE);
      var maintenance = JobQueue.EnqueueEntry.stat(file,
          new JobQueue.EnqueueProvenance("system", "SYSTEM_INTERNAL"))
          .withinRoot(temporary.resolve("unused/.."));
      assertTrue(queue.enqueueMaintenanceFileForGeneration(TARGET, maintenance, "docs"));
      assertEquals(before, row(db), "first maintenance must retain every captured job fact");
      assertEquals(walk, queue.recordedWalk(CAPTURE), "attachment is outside walk progress");
      var receipts = queue.listSwitchBufferOpsStrictForGeneration(TARGET);
      assertEquals(1, receipts.size());
      var receipt = SwitchBufferUpsert.decode(receipts.getFirst().payload());
      assertEquals("UPSERT", receipts.getFirst().op());
      assertEquals(before.get("unit_revision"), receipt.unitRevision());
      assertEquals(before.get("planned_source_sha256"), receipt.sourceSha256());
      assertEquals("docs", receipt.collection());
      assertEquals(temporary, receipt.ingestionRoot());
      assertEquals(new JobQueue.EnqueueProvenance("agent", "CAPTURE"), receipt.provenance());
      assertTrue(queue.enqueueMaintenanceFileForGeneration(TARGET, maintenance, "docs"));
      assertEquals(before, row(db));
      assertEquals(receipts, queue.listSwitchBufferOpsStrictForGeneration(TARGET));
      if (claim != null) {
        assertTrue(queue.ownsClaimForPublication(claim), "the original extraction still owns publication");
        assertTrue(queue.markClaimDone(claim, skipped(), null));
      }
    }
  }

  @Test
  void pendingCapturedAttachmentAndReceiptIdentitySurviveReopen() throws Exception {
    Path file = Files.writeString(temporary.resolve("reopen.txt"), "durable bytes");
    Path db = temporary.resolve("reopen.db");
    Map<String, String> before;
    List<SwitchBufferCapableQueue.SwitchBufferOp> receipts;
    try (var queue = capturedQueue(db)) {
      capture(queue, file, null, true, false, null);
      before = row(db);
      assertTrue(queue.enqueueMaintenanceFileForGeneration(TARGET,
          JobQueue.EnqueueEntry.stat(file), null));
      assertEquals(before, row(db), "pending capture must not be re-admitted");
      receipts = queue.listSwitchBufferOpsStrictForGeneration(TARGET);
      assertEquals(1, receipts.size());
    }
    try (var queue = capturedQueue(db)) {
      assertEquals(before, row(db));
      assertEquals(receipts, queue.listSwitchBufferOpsStrictForGeneration(TARGET));
      assertTrue(queue.enqueueMaintenanceFileForGeneration(TARGET,
          JobQueue.EnqueueEntry.stat(file), null));
      assertEquals(before, row(db));
      assertEquals(receipts, queue.listSwitchBufferOpsStrictForGeneration(TARGET),
          "a repeated attachment cannot change receipt order or revision");
    }
  }

  @Test
  void rejectedCapturedReceiptRollsBackAndCanBeRetriedAfterReopen() throws Exception {
    Path file = Files.writeString(temporary.resolve("atomic.txt"), "atomic bytes");
    Path db = temporary.resolve("atomic.db");
    Map<String, String> before;
    try (var queue = capturedQueue(db)) {
      capture(queue, file, "docs", false, false, temporary);
      var claim = queue.pollPending(1).getFirst();
      before = row(db);
      var walk = queue.recordedWalk(CAPTURE);
      sql(db, "CREATE TRIGGER reject_receipt BEFORE INSERT ON switch_buffer "
          + "BEGIN SELECT RAISE(ABORT, 'injected receipt failure'); END");
      assertFalse(queue.enqueueMaintenanceFileForGeneration(TARGET,
          JobQueue.EnqueueEntry.stat(file).withinRoot(temporary), "docs"));
      assertEquals(before, row(db));
      assertEquals(walk, queue.recordedWalk(CAPTURE));
      assertTrue(queue.ownsClaimForPublication(claim));
      assertTrue(queue.listSwitchBufferOpsStrictForGeneration(TARGET).isEmpty());
    }
    try (var queue = capturedQueue(db)) {
      assertTrue(queue.listSwitchBufferOpsStrictForGeneration(TARGET).isEmpty());
      sql(db, "DROP TRIGGER reject_receipt");
      // Recovery may re-authorize PROCESSING; attachment must preserve that recovered row.
      var recovered = row(db);
      assertTrue(queue.enqueueMaintenanceFileForGeneration(TARGET,
          JobQueue.EnqueueEntry.stat(file).withinRoot(temporary), "docs"));
      assertEquals(recovered, row(db), "retry must attach, not replace recovered work");
      assertEquals(1, queue.listSwitchBufferOpsStrictForGeneration(TARGET).size());
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"bytes", "collection", "collection-null", "retained-collection-null", "root", "root-null",
      "retained-root-null", "generation",
      "native-target", "force", "planned-hash", "admitted-hash", "DONE", "FAILED", "RETRY_EXHAUSTED",
      "cancelled", "open", "streaming", "sealed", "unseen-resumed", "missing-manifest", "missing-membership",
      "future-epoch", "zero-epoch", "release-revision", "root-revision"})
  void maintenanceWithoutCapturedProofKeepsExistingAdmission(String difference) throws Exception {
    Path root = Files.createDirectory(temporary.resolve("root"));
    Path file = Files.writeString(root.resolve("different.txt"), "original bytes");
    Path db = temporary.resolve("different.db");
    try (var queue = capturedQueue(db)) {
      var walk = difference.equals("streaming")
          ? queue.beginRecordedWalk(CAPTURE, "a".repeat(64), true)
          : queue.beginCapturedWalk(CAPTURE, "a".repeat(64), true);
      var captured = new JobQueue.EnqueueEntry(file, Files.size(file), null, SourceContentHash.sha256(file))
          .withinRoot(difference.equals("retained-root-null") ? null : root);
      queue.enqueueRecordedEntries(CAPTURE, walk.enumerationEpoch(), List.of(captured),
          difference.equals("retained-collection-null") ? null : "docs");
      if (difference.equals("unseen-resumed")) {
        walk = queue.beginCapturedWalk(CAPTURE, "a".repeat(64), false);
      }
      if (!difference.equals("open")) queue.closeRecordedWalkEnumeration(CAPTURE, walk.enumerationEpoch(),
          difference.equals("cancelled") ? JobQueue.WalkEnumerationOutcome.CANCELLED
              : JobQueue.WalkEnumerationOutcome.COMPLETE);
      if (difference.equals("unseen-resumed")) {
        assertEquals("DONE", row(db).get("state"));
        assertEquals("SOURCE_REMOVED", row(db).get("last_reason_code"));
        assertTrue(queue.listSwitchBufferOpsStrictForGeneration(TARGET).isEmpty());
      }
      switch (difference) {
        case "bytes" -> Files.writeString(file, "genuinely newer bytes");
        case "planned-hash" -> sql(db, "UPDATE jobs SET planned_source_sha256='" + "b".repeat(64) + "'");
        case "admitted-hash" -> sql(db, "UPDATE jobs SET admitted_source_sha256='" + "b".repeat(64) + "'");
        case "DONE", "sealed" -> {
          assertTrue(queue.markClaimDone(queue.pollPending(1).getFirst(), skipped(), null));
          if (difference.equals("sealed")) assertNotNull(queue.trySealRecordedWalk(CAPTURE).sealedAt());
        }
        case "FAILED", "RETRY_EXHAUSTED" -> {
          assertTrue(queue.markClaimFailed(queue.pollPending(1).getFirst(),
              IngestionOutcome.of(IngestionOutcomeClass.PARSER_FAILED, "PARSER_FAILED", IngestionRetryPolicy.NONE), null));
          if (difference.equals("RETRY_EXHAUSTED")) sql(db, "UPDATE jobs SET state='RETRY_EXHAUSTED'");
        }
        case "missing-manifest" -> sql(db, "UPDATE ingestion_walk_progress SET manifest_sha256=NULL");
        case "missing-membership" -> sql(db, "UPDATE jobs SET walk_seen_epoch=NULL");
        case "future-epoch" -> sql(db, "UPDATE jobs SET walk_seen_epoch=99");
        case "zero-epoch" -> sql(db, "UPDATE jobs SET walk_seen_epoch=0");
        case "release-revision" -> sql(db, "UPDATE jobs SET unit_revision='release030/job/old'");
        case "root-revision" -> sql(db, "UPDATE jobs SET unit_revision='root2:old'");
        default -> { }
      }
      String before = row(db).get("unit_revision");
      var incoming = JobQueue.EnqueueEntry.stat(file).withinRoot(
          difference.equals("root") ? temporary : difference.equals("root-null") ? null : root)
          .withForceReindex(difference.equals("force"));
      String target = difference.equals("generation") ? "g-other"
          : difference.equals("native-target") ? "native" : TARGET;
      String collection = difference.equals("collection") ? "other"
          : difference.equals("collection-null") ? null : "docs";
      if (List.of("missing-manifest", "missing-membership").contains(difference)) {
        var retained = row(db);
        assertFalse(queue.enqueueMaintenanceFileForGeneration(target, incoming, collection));
        assertEquals(retained, row(db), "existing admission still refuses invalid or stopped membership");
        assertTrue(queue.listSwitchBufferOpsStrictForGeneration(target).isEmpty());
        return;
      }
      assertTrue(queue.enqueueMaintenanceFileForGeneration(target, incoming, collection));
      assertNotEquals(before, row(db).get("unit_revision"), "unqualified work follows normal admission");
      assertEquals(1, queue.listSwitchBufferOpsStrictForGeneration(target).size());
      assertEquals(SourceContentHash.sha256(file), row(db).get("admitted_source_sha256"));
      if (difference.equals("force")) assertEquals("1", row(db).get("force_reindex"));
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @CsvSource({"UPSERT,false", "DELETE,false", "DELETE_PREFIX,false", "DELETE_COLLECTION,false",
      "UPSERT,true", "DELETE,true", "DELETE_PREFIX,true", "DELETE_COLLECTION,true"})
  void foregroundMutationAroundCapturedAttachmentKeepsExistingMaintenancePrecedence(
      String mutation, boolean attachFirst) throws Exception {
    Path file = Files.writeString(temporary.resolve("foreground.txt"), "captured bytes");
    Path db = temporary.resolve("foreground.db");
    try (var queue = capturedQueue(db)) {
      capture(queue, file, "docs", false, false, temporary);
      var original = row(db);
      var entry = JobQueue.EnqueueEntry.stat(file).withinRoot(temporary);
      if (attachFirst) {
        assertTrue(queue.enqueueMaintenanceFileForGeneration(TARGET, entry, "docs"));
        assertEquals(original, row(db), "attach before the foreground mutation without replacing captured work");
      }
      var foreground = JobQueue.EnqueueEntry.stat(file,
          new JobQueue.EnqueueProvenance("user", "HTTP")).withinRoot(temporary);
      switch (mutation) {
        case "UPSERT" -> assertTrue(queue.enqueueAndBufferFileForGeneration(TARGET, foreground, "docs", null));
        case "DELETE" -> {
          assertTrue(queue.putSwitchBufferForGeneration(TARGET, "path:" + file, "DELETE",
              new SwitchBufferDelete(file.toString(), SwitchBufferDelete.Origin.EXPLICIT, null).encode()));
          assertEquals(1, queue.deleteByExactPath(file.toString()));
        }
        case "DELETE_PREFIX" -> {
          assertTrue(queue.putSwitchBufferForGeneration(TARGET, "prefix:" + temporary, "DELETE_PREFIX",
              new PathPrefixScope(temporary.toString(), List.of()).encode()));
          assertEquals(1, queue.deleteByPathPrefix(temporary.toString()));
        }
        case "DELETE_COLLECTION" -> {
          assertTrue(queue.putSwitchBufferForGeneration(TARGET, "collection:docs", "DELETE_COLLECTION", "docs"));
          queue.deleteByCollection("docs");
        }
        default -> throw new AssertionError(mutation);
      }
      var foregroundReceipts = queue.listSwitchBufferOpsStrictForGeneration(TARGET);
      boolean matchingReceiptRetained = mutation.equals("UPSERT")
          || (attachFirst && (mutation.equals("DELETE_PREFIX") || mutation.equals("DELETE_COLLECTION")));
      Map<String, String> replacement = matchingReceiptRetained ? row(db) : null;
      assertTrue(queue.enqueueMaintenanceFileForGeneration(TARGET, entry, "docs"));
      var current = row(db);
      if (replacement != null) {
        assertEquals(replacement, current);
        assertEquals(foregroundReceipts, queue.listSwitchBufferOpsStrictForGeneration(TARGET));
      } else {
        assertNotEquals(original.get("unit_revision"), current.get("unit_revision"),
            "without a matching receipt, recreation follows existing admission");
        if (!mutation.equals("DELETE")) assertTrue(
            queue.listSwitchBufferOpsStrictForGeneration(TARGET).containsAll(foregroundReceipts),
            "containing deletion receipts keep their precedence and identity");
      }
      var upsert = queue.listSwitchBufferOpsStrictForGeneration(TARGET).stream()
          .filter(op -> op.key().equals("path:" + file)).findFirst().orElseThrow();
      assertEquals(current.get("unit_revision"), SwitchBufferUpsert.decode(upsert.payload()).unitRevision());
    }
  }

  private SqliteJobQueue capturedQueue(Path db) throws Exception {
    var queue = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW_FORCE);
    queue.open();
    return queue;
  }

  private void capture(SqliteJobQueue queue, Path file, String collection, boolean force, boolean resumed,
      Path ingestionRoot) throws Exception {
    var walk = queue.beginCapturedWalk(CAPTURE, "a".repeat(64), true);
    var entry = new JobQueue.EnqueueEntry(file, Files.size(file),
        new JobQueue.EnqueueProvenance("agent", "CAPTURE"), SourceContentHash.sha256(file))
        .withinRoot(ingestionRoot).withForceReindex(force);
    if (force) assertEquals(1, queue.enqueueEntries(List.of(entry), collection));
    assertEquals(1, queue.enqueueRecordedEntries(CAPTURE, walk.enumerationEpoch(), List.of(entry), collection));
    if (resumed) {
      walk = queue.beginCapturedWalk(CAPTURE, "a".repeat(64), false);
      assertEquals(2, walk.enumerationEpoch());
      assertEquals(1, queue.enqueueRecordedEntries(CAPTURE, walk.enumerationEpoch(), List.of(entry), collection));
    }
    queue.closeRecordedWalkEnumeration(CAPTURE, walk.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.COMPLETE);
    assertTrue(queue.listSwitchBufferOpsStrictForGeneration(TARGET).isEmpty());
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
