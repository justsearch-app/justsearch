/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.app.api.indexing.AcceptedProjection.Kind;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SwitchBufferVersionTest {
  @TempDir Path tempDir;

  @Test
  void staleSnapshotCannotRemoveAnIdenticalReinsertedKey() throws Exception {
    try (var queue = new SqliteJobQueue(tempDir.resolve("reinsert.db"))) {
      queue.open();
      assertTrue(queue.putSwitchBuffer("same", "DELETE", "document"));
      var first = queue.listSwitchBufferOps();
      assertEquals(1, queue.removeReplayedSwitchBufferOps(first));
      assertTrue(queue.putSwitchBuffer("same", "DELETE", "document"));
      var second = queue.listSwitchBufferOps();
      assertNotEquals(first.getFirst().revision(), second.getFirst().revision());
      assertEquals(0, queue.removeReplayedSwitchBufferOps(first));
      assertEquals(second, queue.listSwitchBufferOps());
      assertEquals(1, queue.removeReplayedSwitchBufferOps(second));
    }
  }

  @Test
  void candidateGenerationsRetainSameKeyAndRemoveOnlyMatchingVersion() throws Exception {
    try (var queue = new SqliteJobQueue(tempDir.resolve("generation-scope.db"))) {
      queue.open();
      assertTrue(queue.putSwitchBufferForGeneration("green-a", "same", "DELETE", "a"));
      assertTrue(queue.putSwitchBufferForGeneration("green-b", "same", "DELETE", "b"));

      var a = queue.listSwitchBufferOpsStrictForGeneration("green-a");
      var b = queue.listSwitchBufferOpsStrictForGeneration("green-b");
      assertEquals(List.of("green-a"), a.stream()
          .map(SwitchBufferCapableQueue.SwitchBufferOp::generation).toList());
      assertEquals(List.of("a"), a.stream()
          .map(SwitchBufferCapableQueue.SwitchBufferOp::payload).toList());
      assertEquals(List.of("b"), b.stream()
          .map(SwitchBufferCapableQueue.SwitchBufferOp::payload).toList());
      assertThrows(IllegalArgumentException.class,
          () -> queue.removeReplayedSwitchBufferOpsForGeneration("green-b", a));
      assertEquals(1, queue.removeReplayedSwitchBufferOpsForGeneration("green-a", a));
      assertEquals(List.of("b"), queue.listSwitchBufferOpsStrictForGeneration("green-b")
          .stream().map(SwitchBufferCapableQueue.SwitchBufferOp::payload).toList());
    }
  }

  @Test
  void noFileProjectionKeepsSourceOrderAndConditionalCleanupAcrossRestart() throws Exception {
    Path db = tempDir.resolve("projection-revisions.db");
    var first = new AcceptedProjection("memory", "record-7", 1, Kind.UPSERT,
        "{\"content\":\"first\",\"title\":\"one\"}");
    var equivalent = new AcceptedProjection("memory", "record-7", 1, Kind.UPSERT,
        "{\"title\":\"one\",\"content\":\"first\"}");
    var conflicting = new AcceptedProjection("memory", "record-7", 1, Kind.UPSERT,
        "{\"content\":\"different\"}");
    var deletion = new AcceptedProjection("memory", "record-7", 2, Kind.DELETE, null);
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertEquals(SwitchBufferCapableQueue.ProjectionAdmission.ACCEPTED,
          queue.admitProjectionForGeneration("green", first.encode()));
      var firstSnapshot = queue.listSwitchBufferOpsStrictForGeneration("green");
      assertEquals(SwitchBufferCapableQueue.ProjectionAdmission.DUPLICATE,
          queue.admitProjectionForGeneration("green", equivalent.encode()));
      assertEquals(firstSnapshot, queue.listSwitchBufferOpsStrictForGeneration("green"));
      assertEquals(SwitchBufferCapableQueue.ProjectionAdmission.CONFLICT,
          queue.admitProjectionForGeneration("green", conflicting.encode()));
      assertEquals(SwitchBufferCapableQueue.ProjectionAdmission.ACCEPTED,
          queue.admitProjectionForGeneration("green", deletion.encode()));
      assertEquals(SwitchBufferCapableQueue.ProjectionAdmission.STALE,
          queue.admitProjectionForGeneration("green", first.encode()));
      assertEquals(0, queue.removeReplayedSwitchBufferOps(firstSnapshot));
      assertEquals(deletion, AcceptedProjection.decode(
          queue.listSwitchBufferOpsStrictForGeneration("green").getFirst().payload()));
    }
    try (var reopened = new SqliteJobQueue(db)) {
      reopened.open();
      assertEquals(deletion, AcceptedProjection.decode(
          reopened.listSwitchBufferOpsStrictForGeneration("green").getFirst().payload()));
      assertEquals(SwitchBufferCapableQueue.ProjectionAdmission.ACCEPTED,
          reopened.admitProjectionForGeneration("other", first.encode()));
      assertEquals(SwitchBufferCapableQueue.ProjectionAdmission.ACCEPTED,
          reopened.admitProjectionForGeneration("green", new AcceptedProjection(
              "memory", "record-7", 3, Kind.UPSERT, "{\"content\":\"restored\"}").encode()));
      assertEquals(1, reopened.listSwitchBufferOpsStrictForGeneration("other").size());
    }
  }

  @Test
  void atomicFileAdmissionCommitsJobAndScopedUpsertTogether() throws Exception {
    Path db = tempDir.resolve("atomic-file-admission.db");
    Path file = tempDir.resolve("atomic.txt").toAbsolutePath();
    String normalized = PathNormalizer.normalizeKey(file);
    var provenance = new JobQueue.EnqueueProvenance("agent", "atomic-test");
    var entry = new JobQueue.EnqueueEntry(file, 17L, provenance, "a".repeat(64));
    try (var queue = new SqliteJobQueue(
        db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var captured = queue.beginCapturedWalk("scan-1", "b".repeat(64), true);
      assertEquals(1, queue.enqueueRecordedEntries(
          "scan-1", captured.enumerationEpoch(), List.of(entry), "docs"));
      queue.closeRecordedWalkEnumeration(
          "scan-1", captured.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.COMPLETE);
      // Keep an issued owner live: ordinary maintenance admission seals a captured walk before
      // reading its membership, while an issued member remains open and retains its source hash.
      assertEquals(1, queue.pollPending(1).size());
      assertTrue(queue.enqueueAndBufferFileForGeneration("green", entry, "docs", "scan-1"));
      assertTrue(queue.enqueueAndBufferFileForGeneration("green", entry, "docs", "scan-1"));
      assertTrue(queue.enqueueAndBufferFileForGeneration("other", entry, "docs", "scan-1"));

      assertEquals(1, queue.listSwitchBufferOpsStrictForGeneration("green").size());
      assertEquals(1, queue.listSwitchBufferOpsStrictForGeneration("other").size());
      var upsert = SwitchBufferUpsert.decode(
          queue.listSwitchBufferOpsStrictForGeneration("green").getFirst().payload());
      assertEquals(normalized, upsert.path());
      assertEquals("docs", upsert.collection());
      assertEquals(provenance, upsert.provenance());
      assertEquals("a".repeat(64), upsert.sourceSha256());
      var otherUpsert = SwitchBufferUpsert.decode(
          queue.listSwitchBufferOpsStrictForGeneration("other").getFirst().payload());
      assertNotEquals(upsert.unitRevision(), otherUpsert.unitRevision());

      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
          var statement = connection.prepareStatement(
              "SELECT state, collection, scan_id, size_bytes, planned_source_sha256, "
                  + "originator, transport, unit_revision, content_hash FROM jobs WHERE path = ?")) {
        statement.setString(1, normalized);
        try (var row = statement.executeQuery()) {
          assertTrue(row.next());
          assertEquals("PENDING", row.getString("state"));
          assertEquals("docs", row.getString("collection"));
          assertEquals("scan-1", row.getString("scan_id"));
          assertEquals(17L, row.getLong("size_bytes"));
          assertEquals("a".repeat(64), row.getString("planned_source_sha256"));
          assertEquals("agent", row.getString("originator"));
          assertEquals("atomic-test", row.getString("transport"));
          assertTrue(row.getString("unit_revision").matches("[0-9a-f]{32}"));
          assertEquals(row.getString("unit_revision"), otherUpsert.unitRevision());
          assertNull(row.getString("content_hash"));
        }
      }
    }
  }

  @Test
  void ordinaryAdmissionFreezesSourceHashAndRevisionBeforeLaterFileEdit() throws Exception {
    Path db = tempDir.resolve("ordinary-source-witness.db");
    Path file = Files.writeString(tempDir.resolve("ordinary.txt"), "first").toAbsolutePath();
    String firstHash = io.justsearch.indexerworker.loop.SourceContentHash.sha256(file);
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertTrue(queue.enqueueAndBufferFileForGeneration(
          "green", JobQueue.EnqueueEntry.stat(file), "docs", null));
      var upsert = SwitchBufferUpsert.decode(
          queue.listSwitchBufferOpsStrictForGeneration("green").getFirst().payload());
      assertEquals(firstHash, upsert.sourceSha256());
      assertNotNull(upsert.unitRevision());
      assertFalse(queue.matchesAcceptedFileProjection(
          PathNormalizer.normalizeKey(file), upsert.unitRevision(), firstHash));

      Files.writeString(file, "second");
      assertEquals(firstHash, SwitchBufferUpsert.decode(
          queue.listSwitchBufferOpsStrictForGeneration("green").getFirst().payload())
          .sourceSha256());
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
          var statement = connection.prepareStatement(
              "SELECT unit_revision, planned_source_sha256 FROM jobs WHERE path = ?")) {
        statement.setString(1, PathNormalizer.normalizeKey(file));
        try (var row = statement.executeQuery()) {
          assertTrue(row.next());
          assertEquals(upsert.unitRevision(), row.getString("unit_revision"));
          assertEquals(firstHash, row.getString("planned_source_sha256"));
        }
      }
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
          var statement = connection.prepareStatement(
              "UPDATE jobs SET state = 'DONE', content_hash = ? WHERE path = ?")) {
        statement.setString(1, firstHash);
        statement.setString(2, PathNormalizer.normalizeKey(file));
        assertEquals(1, statement.executeUpdate());
      }
      assertTrue(queue.matchesAcceptedFileProjection(
          PathNormalizer.normalizeKey(file), upsert.unitRevision(), firstHash));
      assertFalse(queue.matchesAcceptedFileProjection(
          PathNormalizer.normalizeKey(file), "older-revision", firstHash));
    }
  }

  @Test
  void atomicFileAdmissionRollsBackJobWhenJournalWriteFails() throws Exception {
    Path db = tempDir.resolve("atomic-file-admission-rollback.db");
    Path file = tempDir.resolve("rollback.txt").toAbsolutePath();
    Files.writeString(file, "rollback content");
    String normalized = PathNormalizer.normalizeKey(file);
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
          var statement = connection.createStatement()) {
        statement.execute("DROP TABLE switch_buffer");
      }
      assertFalse(queue.enqueueAndBufferFileForGeneration(
          "green", JobQueue.EnqueueEntry.ofUnknownSize(file), null, null));
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        var statement = connection.prepareStatement("SELECT COUNT(*) FROM jobs WHERE path = ?")) {
      statement.setString(1, normalized);
      try (var row = statement.executeQuery()) {
        assertTrue(row.next());
        assertEquals(0L, row.getLong(1));
      }
    }
  }

  @Test
  void atomicFileBatchRollsBackAllJobsWhenLaterJournalWriteFails() throws Exception {
    Path db = tempDir.resolve("atomic-file-batch-rollback.db");
    Path first = tempDir.resolve("first.txt").toAbsolutePath();
    Path second = tempDir.resolve("second.txt").toAbsolutePath();
    Files.writeString(first, "first content");
    Files.writeString(second, "second content");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
          var statement = connection.createStatement()) {
        statement.execute("""
            CREATE TRIGGER refuse_later_journal BEFORE INSERT ON switch_buffer
            WHEN NEW.key LIKE '%second.txt'
            BEGIN SELECT RAISE(ABORT, 'injected later journal failure'); END
            """);
      }
      assertEquals(0, queue.enqueueAndBufferFilesForGeneration("green", List.of(
          JobQueue.EnqueueEntry.ofUnknownSize(first),
          JobQueue.EnqueueEntry.ofUnknownSize(second)), "docs", null));
      assertTrue(queue.listSwitchBufferOpsStrictForGeneration("green").isEmpty());
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT COUNT(*) FROM jobs")) {
      assertTrue(rows.next());
      assertEquals(0L, rows.getLong(1));
    }
  }

  @Test
  void atomicRecordedAdmissionCommitsFiniteMemberAndScopedUpsertTogether() throws Exception {
    Path db = tempDir.resolve("atomic-recorded-admission.db");
    Path file = tempDir.resolve("recorded-atomic.txt").toAbsolutePath();
    String normalized = PathNormalizer.normalizeKey(file);
    String operationKey = "recorded-atomic";
    var provenance = new JobQueue.EnqueueProvenance("agent", "recorded-atomic-test");
    var entry = new JobQueue.EnqueueEntry(file, 23L, provenance, "c".repeat(64));
    try (var queue = new SqliteJobQueue(
        db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var captured = queue.beginCapturedWalk(operationKey, "d".repeat(64), true);
      assertEquals(1, queue.enqueueRecordedEntriesAndBufferForGeneration(
          "green", operationKey, captured.enumerationEpoch(), List.of(entry, entry), "docs"));

      var ops = queue.listSwitchBufferOpsStrictForGeneration("green");
      assertEquals(1, ops.size());
      var upsert = SwitchBufferUpsert.decode(ops.getFirst().payload());
      assertEquals(normalized, upsert.path());
      assertEquals("docs", upsert.collection());
      assertEquals(provenance, upsert.provenance());
      assertEquals("c".repeat(64), upsert.sourceSha256());
      assertNotNull(upsert.unitRevision());
      assertEquals(2, queue.recordedWalk(operationKey).orElseThrow().revision());

      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
          var statement = connection.prepareStatement(
              "SELECT state, planned_source_sha256 FROM jobs WHERE path = ?")) {
        statement.setString(1, normalized);
        try (var row = statement.executeQuery()) {
          assertTrue(row.next());
          assertEquals("PENDING", row.getString("state"));
          assertEquals("c".repeat(64), row.getString("planned_source_sha256"));
        }
      }
    }
  }

  @Test
  void streamingRecordedAdmissionFreezesSourceBeforePublishingScopedUpsert() throws Exception {
    Path db = tempDir.resolve("streaming-recorded-admission.db");
    Path file = Files.writeString(tempDir.resolve("streaming.txt"), "streaming source");
    String sourceHash = io.justsearch.indexerworker.loop.SourceContentHash.sha256(file);
    String operationKey = "streaming-recorded";
    try (var queue = new SqliteJobQueue(
        db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var walk = queue.beginRecordedWalk(operationKey, "a".repeat(64), true);
      assertEquals(1, queue.enqueueRecordedEntriesAndBufferForGeneration(
          "green", operationKey, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.stat(file)), "docs"));

      var upsert = SwitchBufferUpsert.decode(
          queue.listSwitchBufferOpsStrictForGeneration("green").getFirst().payload());
      assertEquals(PathNormalizer.normalizeKey(file), upsert.path());
      assertEquals(sourceHash, upsert.sourceSha256());
      assertNotNull(upsert.unitRevision());
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
          var statement = connection.prepareStatement(
              "SELECT scan_id, walk_seen_epoch, unit_revision, planned_source_sha256 "
                  + "FROM jobs WHERE path = ?")) {
        statement.setString(1, PathNormalizer.normalizeKey(file));
        try (var row = statement.executeQuery()) {
          assertTrue(row.next());
          assertEquals(operationKey, row.getString("scan_id"));
          assertEquals(walk.enumerationEpoch(), row.getLong("walk_seen_epoch"));
          assertEquals(upsert.unitRevision(), row.getString("unit_revision"));
          assertEquals(sourceHash, row.getString("planned_source_sha256"));
        }
      }
      queue.closeRecordedWalkEnumeration(
          operationKey, walk.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.COMPLETE);
      var claim = queue.pollPending(1).getFirst();
      assertEquals(operationKey, claim.scanId());
      assertEquals(sourceHash, claim.plannedSourceSha256());
    }
  }

  @Test
  void streamingCandidateReplacesChangedSourceAndJournalBeforeNextClaim() throws Exception {
    Path db = tempDir.resolve("streaming-source-replacement.db");
    Path file = Files.writeString(tempDir.resolve("changing.txt"), "first");
    String firstHash = io.justsearch.indexerworker.loop.SourceContentHash.sha256(file);
    try (var queue = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var walk = queue.beginRecordedWalk("stream-change", "a".repeat(64), true);
      assertEquals(1, queue.enqueueRecordedEntriesAndBufferForGeneration(
          "green", "stream-change", walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.stat(file)), "docs"));
      var first = queue.pollPending(1).getFirst();
      assertEquals(firstHash, first.plannedSourceSha256());
      Files.writeString(file, "second");
      String secondHash = io.justsearch.indexerworker.loop.SourceContentHash.sha256(file);

      assertTrue(queue.supersedeStreamingRecordedSource(first, secondHash, staleSource(), null));
      var successor = queue.pollPending(1).getFirst();
      assertNotEquals(first.unitRevision(), successor.unitRevision());
      assertEquals(secondHash, successor.plannedSourceSha256());
      assertFalse(queue.ownsClaimForPublication(first));
      var upsert = SwitchBufferUpsert.decode(
          queue.listSwitchBufferOpsStrictForGeneration("green").getFirst().payload());
      assertEquals(successor.unitRevision(), upsert.unitRevision());
      assertEquals(secondHash, upsert.sourceSha256());
      assertEquals(3, queue.recordedWalk("stream-change").orElseThrow().revision());
      queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(
          successor, null, secondHash)), IngestionOutcome.of(
          IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE));
      assertTrue(queue.matchesAcceptedFileProjection(
          PathNormalizer.normalizeKey(file), successor.unitRevision(), secondHash));
    }
  }

  @Test
  void streamingSourceReplacementRollsBackWhenCandidateJournalIsUnavailable() throws Exception {
    Path db = tempDir.resolve("streaming-source-rollback.db");
    Path file = Files.writeString(tempDir.resolve("rollback-source.txt"), "first");
    try (var queue = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var walk = queue.beginRecordedWalk("stream-rollback", "a".repeat(64), true);
      assertEquals(1, queue.enqueueRecordedEntriesAndBufferForGeneration(
          "green", "stream-rollback", walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.stat(file)), null));
      var first = queue.pollPending(1).getFirst();
      Files.writeString(file, "second");
      String secondHash = io.justsearch.indexerworker.loop.SourceContentHash.sha256(file);
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
          var statement = connection.createStatement()) {
        statement.execute("CREATE TRIGGER reject_streaming_replacement "
            + "BEFORE INSERT ON switch_buffer BEGIN SELECT RAISE(ABORT, 'journal unavailable'); END");
      }
      assertThrows(RuntimeException.class,
          () -> queue.supersedeStreamingRecordedSource(first, secondHash, staleSource(), null));
      assertTrue(queue.ownsClaimForPublication(first));
      assertEquals(2, queue.recordedWalk("stream-rollback").orElseThrow().revision());
      var upsert = SwitchBufferUpsert.decode(
          queue.listSwitchBufferOpsStrictForGeneration("green").getFirst().payload());
      assertEquals(first.unitRevision(), upsert.unitRevision());
      assertEquals(first.plannedSourceSha256(), upsert.sourceSha256());
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
          var statement = connection.prepareStatement(
              "SELECT state, unit_revision, planned_source_sha256 FROM jobs WHERE path = ?")) {
        statement.setString(1, PathNormalizer.normalizeKey(file));
        try (var row = statement.executeQuery()) {
          assertTrue(row.next());
          assertEquals("PROCESSING", row.getString(1));
          assertEquals(first.unitRevision(), row.getString(2));
          assertEquals(first.plannedSourceSha256(), row.getString(3));
        }
      }
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
          var statement = connection.createStatement();
          var row = statement.executeQuery(
              "SELECT COUNT(*) FROM ingestion_ledger WHERE operation_key = 'stream-rollback'")) {
        assertTrue(row.next());
        assertEquals(0, row.getLong(1));
      }
    }
  }

  @Test
  void capturedCandidateKeepsItsImmutableSourceWitness() throws Exception {
    Path file = Files.writeString(tempDir.resolve("captured-immutable.txt"), "first");
    try (var queue = new SqliteJobQueue(
        tempDir.resolve("captured-immutable.db"),
        ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var walk = queue.beginCapturedWalk("captured-immutable", "a".repeat(64), true);
      assertEquals(1, queue.enqueueRecordedEntriesAndBufferForGeneration(
          "green", "captured-immutable", walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.stat(file)), null));
      queue.closeRecordedWalkEnumeration(
          "captured-immutable", walk.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.COMPLETE);
      var claim = queue.pollPending(1).getFirst();
      var before = queue.listSwitchBufferOpsStrictForGeneration("green").getFirst();
      Files.writeString(file, "second");
      String changedHash = io.justsearch.indexerworker.loop.SourceContentHash.sha256(file);
      assertFalse(queue.supersedeStreamingRecordedSource(claim, changedHash, staleSource(), null));
      assertTrue(queue.ownsClaimForPublication(claim));
      assertEquals(before, queue.listSwitchBufferOpsStrictForGeneration("green").getFirst());
    }
  }

  private static IngestionOutcome staleSource() {
    return IngestionOutcome.of(IngestionOutcomeClass.STALE_SOURCE, "CONTENT_CHANGED",
        IngestionRetryPolicy.DEFER_WITHOUT_ATTEMPT);
  }

  @Test
  void atomicRecordedAdmissionRollsBackMemberWhenJournalWriteFails() throws Exception {
    Path db = tempDir.resolve("atomic-recorded-admission-rollback.db");
    Path file = tempDir.resolve("recorded-rollback.txt").toAbsolutePath();
    String normalized = PathNormalizer.normalizeKey(file);
    String operationKey = "recorded-rollback";
    var entry = new JobQueue.EnqueueEntry(file, 11L, null, "e".repeat(64));
    try (var queue = new SqliteJobQueue(
        db, ignored -> JobQueue.RecordedClaimDecision.ALLOW)) {
      queue.open();
      var captured = queue.beginCapturedWalk(operationKey, "f".repeat(64), true);
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
          var statement = connection.createStatement()) {
        statement.execute("DROP TABLE switch_buffer");
      }

      assertThrows(IllegalStateException.class, () ->
          queue.enqueueRecordedEntriesAndBufferForGeneration(
              "green", operationKey, captured.enumerationEpoch(), List.of(entry), null));
      assertEquals(1, queue.recordedWalk(operationKey).orElseThrow().revision());
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        var statement = connection.prepareStatement("SELECT COUNT(*) FROM jobs WHERE path = ?")) {
      statement.setString(1, normalized);
      try (var row = statement.executeQuery()) {
        assertTrue(row.next());
        assertEquals(0L, row.getLong(1));
      }
    }
  }

  @Test
  void failedRemovalRollsBackEverySnapshotDeletion() throws Exception {
    Path db = tempDir.resolve("rollback-removal.db");
    List<SwitchBufferCapableQueue.SwitchBufferOp> snapshot;
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertTrue(queue.putSwitchBuffer("a", "DELETE", "first"));
      assertTrue(queue.putSwitchBuffer("b", "DELETE", "second"));
      snapshot = queue.listSwitchBufferOps().stream()
          .sorted(java.util.Comparator.comparing(SwitchBufferCapableQueue.SwitchBufferOp::key))
          .toList();
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
          var statement = connection.createStatement()) {
        statement.execute("""
            CREATE TRIGGER reject_second_removal BEFORE DELETE ON switch_buffer
            WHEN OLD.key = 'b' BEGIN SELECT RAISE(ABORT, 'injected removal failure'); END
            """);
      }
      assertThrows(IllegalStateException.class, () -> queue.removeReplayedSwitchBufferOps(snapshot));
    }
    try (var reopened = new SqliteJobQueue(db)) {
      reopened.open();
      assertEquals(snapshot, reopened.listSwitchBufferOps().stream()
          .sorted(java.util.Comparator.comparing(SwitchBufferCapableQueue.SwitchBufferOp::key))
          .toList());
    }
  }

  @Test
  void v15RowsReceiveStableDistinctRevisionsWithoutChangingPayloads() throws Exception {
    Path db = v15Fixture("upgrade.db");
    List<SwitchBufferCapableQueue.SwitchBufferOp> upgraded;
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      upgraded = queue.listSwitchBufferOps();
      assertEquals(2, upgraded.size());
      assertEquals(List.of("first", "second"), upgraded.stream()
          .map(SwitchBufferCapableQueue.SwitchBufferOp::payload).toList());
      assertTrue(upgraded.stream().allMatch(row -> !row.revision().isBlank()));
      assertNotEquals(upgraded.getFirst().revision(), upgraded.getLast().revision());
    }
    try (var reopened = new SqliteJobQueue(db)) {
      reopened.open();
      assertEquals(upgraded, reopened.listSwitchBufferOps());
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        var statement = connection.createStatement();
        var version = statement.executeQuery("PRAGMA user_version")) {
      assertTrue(version.next());
      assertEquals(SqliteSchema.TARGET_VERSION, version.getInt(1));
    }
  }

  @Test
  void v20DatabaseMigratesLegacyRowsIntoScopedPrimaryKey() throws Exception {
    Path db = tempDir.resolve("v20-generation-migration.db");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE switch_buffer");
      statement.execute("""
          CREATE TABLE switch_buffer (
            key TEXT PRIMARY KEY, op TEXT NOT NULL, payload TEXT NOT NULL,
            last_updated INTEGER NOT NULL, revision TEXT NOT NULL,
            accepted_order INTEGER NOT NULL CHECK(accepted_order >= 0)
          )
          """);
      statement.execute("INSERT INTO switch_buffer VALUES ('same', 'DELETE', 'legacy', 1, 'r1', 1)");
      statement.execute("PRAGMA user_version = 20");
    }

    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertEquals(List.of("legacy"), queue.listSwitchBufferOpsStrict().stream()
          .map(SwitchBufferCapableQueue.SwitchBufferOp::payload).toList());
      assertTrue(queue.putSwitchBufferForGeneration("green", "same", "DELETE", "candidate"));
      assertEquals(List.of("legacy", "candidate"), queue.listSwitchBufferOpsStrict().stream()
          .map(SwitchBufferCapableQueue.SwitchBufferOp::payload).toList());
    }
  }

  @Test
  void v16FailureRollsBackRevisionColumnAndVersionTogether() throws Exception {
    Path db = v15Fixture("rollback-migration.db");
    try (var queue = new SqliteJobQueue(db, 3, null, version -> {
      if (version == 16) throw new SQLException("injected v16 failure");
    })) {
      assertThrows(SQLException.class, queue::open);
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        var statement = connection.createStatement()) {
      try (var version = statement.executeQuery("PRAGMA user_version")) {
        assertTrue(version.next());
        assertEquals(15, version.getInt(1));
      }
      try (var columns = statement.executeQuery("PRAGMA table_info(switch_buffer)")) {
        assertFalse(SqliteSchema.hasColumn(columns, "revision"));
      }
      try (var rows = statement.executeQuery("SELECT payload FROM switch_buffer ORDER BY last_updated")) {
        assertTrue(rows.next());
        assertEquals("first", rows.getString(1));
        assertTrue(rows.next());
        assertEquals("second", rows.getString(1));
        assertFalse(rows.next());
      }
    }
  }

  private Path v15Fixture(String name) throws Exception {
    Path db = tempDir.resolve(name);
    try (var queue = new SqliteJobQueue(db)) { queue.open(); }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        var statement = connection.createStatement()) {
      statement.execute("DROP TABLE switch_buffer");
      statement.execute("""
          CREATE TABLE switch_buffer (
            key TEXT PRIMARY KEY, op TEXT NOT NULL, payload TEXT NOT NULL,
            last_updated INTEGER NOT NULL)
          """);
      statement.execute("INSERT INTO switch_buffer VALUES ('a', 'DELETE', 'first', 1)");
      statement.execute("INSERT INTO switch_buffer VALUES ('b', 'DELETE', 'second', 2)");
      statement.execute("PRAGMA user_version = 15");
    }
    return db;
  }
}
