/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.ops.KnowledgeServerMigrationOps;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Refusal cleanup on the real SQLite queue and a real Lucene A, after the Engine retired the
 * refused walk's pending rows without writing (DONE, SKIPPED_POLICY, RECOVERY_REFUSED). The
 * captured witness of every journal UPSERT names the plan's bytes, which A holds from before the
 * migration; only the file's bytes say whether A is current (task a5fbcb, M1+). An admission A
 * does not hold gets one ordinary A job that cleanup queues (round 4), and hands over only once
 * A's writer commits its effect; a pending or failed A job keeps Green and the journal (round 5).
 */
final class RefusedRecoveryRetiredAdmissionTest extends LuceneExecutorTestBase {
  private static final String OPERATION = "01994180-0000-7000-8000-000000000197";
  private static final String GREEN = "green";
  private static final IngestionOutcome SUCCESS = IngestionOutcome.of(
      IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE);

  @TempDir Path temp;

  // M1+: the accepted watcher update was retired unwritten while A holds the original bytes
  @Test
  void aRetiredWatcherUpdateHandsOverOnceAsJobCommitsIt() throws Exception {
    try (var fixture = new Fixture(temp, "original A source")) {
      fixture.capture();
      fixture.edit("accepted watcher update");
      fixture.admitOnGreen();
      fixture.retire();
      assertFalse(fixture.drain(), "a queued A job is not A's effect");
      assertEquals(1, fixture.journalSize(), "the journal waits for A's writer");
      fixture.assertQueuedOnA();
      fixture.aWriterCommitsTheFile();
      assertTrue(fixture.drain(), "A committed the accepted update");
      assertEquals(0, fixture.journalSize());
    }
  }

  // M1+: a restart re-admitted the changed file to the journal, then retirement skipped it
  @Test
  void aRetiredReadmissionOfAChangedFileHandsOverOnceAsJobCommitsIt() throws Exception {
    try (var fixture = new Fixture(temp, "original A source")) {
      fixture.capture();
      fixture.edit("accepted watcher update");
      fixture.admitOnGreen();
      fixture.readmitByEnumeration();
      fixture.retire();
      int accepted = fixture.journalSize();
      assertFalse(fixture.drain(), "the re-admission's captured witness is not the file's bytes");
      assertEquals(accepted, fixture.journalSize(), "the journal waits for A's writer");
      fixture.assertQueuedOnA();
      fixture.aWriterCommitsTheFile();
      assertTrue(fixture.drain(), "A committed the file's current bytes");
      assertEquals(0, fixture.journalSize());
    }
  }

  // M1+: a re-entered cleanup, before and after A's commit, queues no second job
  @Test
  void aReenteredCleanupQueuesNoDuplicate() throws Exception {
    try (var fixture = new Fixture(temp, "original A source")) {
      fixture.capture();
      fixture.edit("accepted watcher update");
      fixture.admitOnGreen();
      fixture.retire();
      assertFalse(fixture.drain());
      // Read through its own connection: the queued row is durable for a restarted owner.
      String queued = fixture.assertQueuedOnA();
      assertFalse(fixture.drain());
      assertEquals(queued, fixture.assertQueuedOnA(), "the re-entered cleanup queued nothing new");
      fixture.aWriterCommitsTheFile();
      assertThrows(IllegalStateException.class, () -> fixture.drain(() -> {
        throw new IllegalStateException("cut before the journal acknowledgement");
      }));
      assertEquals(1, fixture.journalSize(), "the cut left the journal unacknowledged");
      assertTrue(fixture.drain());
      assertEquals(0, fixture.journalSize());
      assertEquals(0, fixture.queue.jobStateCountsStrict().pendingCount(), "no job was re-queued");
    }
  }

  // M1+: the accepted file is gone from disk: A's ordinary job removes it, then cleanup settles
  @Test
  void aRetiredUpdateOfADeletedFileHandsOverOnceARemovesIt() throws Exception {
    try (var fixture = new Fixture(temp, "original A source")) {
      fixture.capture();
      fixture.edit("accepted watcher update");
      fixture.admitOnGreen();
      fixture.retire();
      Files.delete(fixture.file);
      assertFalse(fixture.drain(), "A still holds the file");
      assertEquals(1, fixture.journalSize());
      fixture.assertQueuedOnA();
      fixture.aWriterRemovesTheFile();
      assertTrue(fixture.drain(), "A shows the removal");
      assertEquals(0, fixture.journalSize());
    }
  }

  // M1+ (verifier, round 5): a watcher update of a file unreadable since the crash keeps Green
  @Test
  void aPendingUpdateOfAnUnreadableFileKeepsTheJournal() throws Exception {
    try (var fixture = new Fixture(temp, "original A source")) {
      fixture.capture();
      fixture.edit("accepted watcher update");
      fixture.admitOnGreen();
      String pending = fixture.assertPending();
      Files.setPosixFilePermissions(fixture.file, java.util.Set.of());
      try {
        assumeFalse(Files.isReadable(fixture.file), "this user can read any file");
        assertFalse(fixture.drain(), "a pending A job is not A's effect");
        assertEquals(1, fixture.journalSize(), "the journal is kept");
        assertEquals(pending, fixture.assertPending(), "the file's own A job stays the only one");
      } finally {
        fixture.makeReadable();
      }
    }
  }

  // M1+ (round 5): A's job fails on the unreadable file; only a later committed attempt settles
  @Test
  void aFailedAJobKeepsTheJournalUntilALaterAttemptCommits() throws Exception {
    try (var fixture = new Fixture(temp, "original A source")) {
      fixture.capture();
      fixture.edit("accepted watcher update");
      fixture.admitOnGreen();
      fixture.retire();
      assertFalse(fixture.drain());
      String queued = fixture.assertQueuedOnA();
      Files.setPosixFilePermissions(fixture.file, java.util.Set.of());
      try {
        assumeFalse(Files.isReadable(fixture.file), "this user can read any file");
        fixture.aWriterFindsTheFileUnreadable();
        assertFalse(fixture.drain(), "a failed A job is no effect");
        assertEquals(1, fixture.journalSize(), "the journal is kept");
      } finally {
        fixture.makeReadable();
      }
      assertFalse(fixture.drain(), "the retry has not run yet");
      assertEquals(queued, fixture.assertPending(), "the retrying job is not replaced");
      fixture.retryNow();
      fixture.aWriterCommitsTheFile();
      assertTrue(fixture.drain(), "the later attempt committed the accepted update");
      assertEquals(0, fixture.journalSize());
    }
  }

  // M1 preserved: retired rows of unchanged files hand over, because A holds their bytes
  @Test
  void aRetiredReadmissionOfAnUnchangedFileIsHandedToA() throws Exception {
    try (var fixture = new Fixture(temp, "original A source")) {
      fixture.capture();
      fixture.readmitByEnumeration();
      fixture.retire();
      assertTrue(fixture.drain(), "A already holds the file's current bytes");
      assertEquals(0, fixture.journalSize());
    }
  }

  // preserved: a file unreadable at queue time keeps the safe state and queues nothing
  @Test
  void anAcceptedUpdateAHoldsSettlesAfterALaterAdmissionFoundItUnchanged() throws Exception {
    // M8: the accepted admission's bytes are on A and on disk. A later admission of the file (a
    // recovery boot's root walk) moved the row's revision on, and A's writer found the file
    // unchanged: it wrote nothing because A already held it. A holds the accepted revision.
    try (var fixture = new Fixture(temp, "original A source")) {
      fixture.capture();
      fixture.admitOnGreen();
      fixture.retire();
      assertEquals(1, fixture.queue.enqueueEntries(
          List.of(JobQueue.EnqueueEntry.stat(fixture.file)), "default"));
      fixture.aWriterFindsTheFileUnchanged();
      assertTrue(fixture.drain(), "A holds the accepted bytes, which are the file's current bytes");
      assertEquals(0, fixture.journalSize());
    }
  }

  @Test
  void aRetiredUpdateOfAnUnreadableFileStaysUnproved() throws Exception {
    try (var fixture = new Fixture(temp, "original A source")) {
      fixture.capture();
      fixture.edit("accepted watcher update");
      fixture.admitOnGreen();
      fixture.retire();
      Files.setPosixFilePermissions(fixture.file, java.util.Set.of());
      try {
        assumeFalse(Files.isReadable(fixture.file), "this user can read any file");
        assertFalse(fixture.drain(), "an unreadable file is not proved");
        assertEquals(1, fixture.journalSize(), "the journal is kept");
        assertEquals(0, fixture.queue.jobStateCountsStrict().pendingCount(), "nothing is queued");
      } finally {
        fixture.makeReadable();
      }
    }
  }

  private final class Fixture implements AutoCloseable {
    final Path file;
    final String original;
    final SqliteJobQueue queue;
    final RunningRuntime source;

    Fixture(Path dir, String content) throws Exception {
      file = Files.writeString(dir.resolve("watched.txt"), content).toAbsolutePath();
      original = SourceContentHash.sha256(file);
      queue = new SqliteJobQueue(dir.resolve("jobs.db"));
      queue.open();
      source = withTestExecutors(
          IndexSchema.fromCatalog(WorkerBootFixture.productionCatalog()).ephemeral()).open();
      source.commitOps().stopCommitTimer();
      source.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, file.toString(), SchemaFields.DOC_UID, "a-original",
          SchemaFields.PATH, file.toString(), SchemaFields.CONTENT, content,
          SchemaFields.SOURCE_SHA256, original)));
      source.commitOps().commitAndTrack(CommitReason.DRAIN);
      source.commitOps().maybeRefreshBlocking();
    }

    void capture() throws Exception {
      var walk = queue.beginCapturedWalk(OPERATION, "a".repeat(64), true);
      queue.enqueueRecordedEntries(OPERATION, walk.enumerationEpoch(), List.of(
          new JobQueue.EnqueueEntry(file, Files.size(file), null, original, null)), null);
      queue.closeRecordedWalkEnumeration(
          OPERATION, walk.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.COMPLETE);
    }

    void edit(String content) throws Exception {
      Files.writeString(file, content);
    }

    void admitOnGreen() {
      assertTrue(queue.enqueueAndBufferFileForGeneration(
          GREEN, JobQueue.EnqueueEntry.stat(file), null, null));
    }

    void readmitByEnumeration() {
      assertEquals(1, queue.enqueueEnumeratedFilesForGeneration(
          GREEN, List.of(JobQueue.EnqueueEntry.stat(file)), null));
    }

    void retire() {
      queue.retireRefusedRecordedWalk(OPERATION, "a".repeat(64));
      assertEquals(0, queue.jobStateCountsStrict().pendingCount());
    }

    boolean drain() {
      return drain(() -> {});
    }

    boolean drain(Runnable beforeAck) {
      return KnowledgeServerMigrationOps.drainRefusedCandidateOnSource(
          new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
              queue, source, null, IndexingPacing.unthrottled(), temp, temp, new ObjectMapper(),
              () -> false, () -> true, LoggerFactory.getLogger(getClass()),
              System.nanoTime() + TimeUnit.SECONDS.toNanos(1), GREEN), beforeAck);
    }

    /** A's writer claims the file's one job and writes its current bytes to A, uncommitted. */
    void aWriterCommitsTheFile() throws Exception {
      var claim = onlyClaim();
      String bytes = SourceContentHash.sha256(file);
      source.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, file.toString(), SchemaFields.DOC_UID, "a-current",
          SchemaFields.PATH, file.toString(), SchemaFields.CONTENT, Files.readString(file),
          SchemaFields.SOURCE_SHA256, bytes)));
      queue.markDoneTransitions(
          List.of(new JobQueue.IngestionLedgerTransition(claim, null, bytes)), SUCCESS);
    }

    /** A's writer claims the job of the deleted file and removes the file from A. */
    void aWriterRemovesTheFile() throws Exception {
      var claim = onlyClaim();
      source.indexingCoordinator().deleteByIdAndChunks(file.toString());
      queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(claim, null)), SUCCESS);
    }

    /** A's writer claims the job and finds A already holds the file: it writes nothing. */
    void aWriterFindsTheFileUnchanged() {
      var claim = onlyClaim();
      queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(claim, null)),
          IngestionOutcome.of(IngestionOutcomeClass.SKIPPED_POLICY, IngestionReasonCodes.UNCHANGED,
              IngestionRetryPolicy.NONE));
    }

    /** A's writer claims the job and cannot read the file; the row retries later. */
    void aWriterFindsTheFileUnreadable() {
      var claim = onlyClaim();
      assertTrue(queue.markClaimFailed(claim, IngestionOutcome.of(IngestionOutcomeClass.IO_FAILED,
          IngestionReasonCodes.SOURCE_UNREADABLE, IngestionRetryPolicy.RETRY_UNTIL_AVAILABLE), null));
    }

    private JobQueue.IndexJob onlyClaim() {
      var claims = queue.pollPending(10);
      assertEquals(1, claims.size(), "A's one job for the file is claimable");
      return claims.getFirst();
    }

    void retryNow() throws Exception {
      try (var connection = java.sql.DriverManager.getConnection(
              "jdbc:sqlite:" + temp.resolve("jobs.db").toAbsolutePath());
          var update = connection.prepareStatement(
              "UPDATE jobs SET retry_after = NULL WHERE path = ?")) {
        update.setString(1, file.toString());
        assertEquals(1, update.executeUpdate());
      }
    }

    void makeReadable() throws Exception {
      Files.setPosixFilePermissions(file,
          java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }

    /** The file's row is pending; returns its revision. */
    String assertPending() throws Exception {
      try (var connection = java.sql.DriverManager.getConnection(
              "jdbc:sqlite:" + temp.resolve("jobs.db").toAbsolutePath());
          var query = connection.prepareStatement(
              "SELECT state, unit_revision FROM jobs WHERE path = ?")) {
        query.setString(1, file.toString());
        try (var row = query.executeQuery()) {
          assertTrue(row.next(), "the file has a queue row");
          assertEquals("PENDING", row.getString("state"));
          return row.getString("unit_revision");
        }
      }
    }

    /** The file's row is an ordinary pending A job; returns its revision. */
    String assertQueuedOnA() throws Exception {
      try (var connection = java.sql.DriverManager.getConnection(
              "jdbc:sqlite:" + temp.resolve("jobs.db").toAbsolutePath());
          var query = connection.prepareStatement("SELECT state, scan_id, walk_seen_epoch, "
              + "planned_source_sha256, unit_revision FROM jobs WHERE path = ?")) {
        query.setString(1, file.toString());
        try (var row = query.executeQuery()) {
          assertTrue(row.next(), "the file has a queue row");
          assertEquals("PENDING", row.getString("state"), "A's writer runs it");
          assertNull(row.getString("scan_id"), "not a member of the refused walk");
          assertNull(row.getObject("walk_seen_epoch"));
          assertNull(row.getString("planned_source_sha256"), "the writer reads the file's bytes");
          return row.getString("unit_revision");
        }
      }
    }

    int journalSize() {
      return queue.listSwitchBufferOpsStrictForGeneration(GREEN).size();
    }

    @Override
    public void close() throws Exception {
      try {
        source.close();
      } finally {
        queue.close();
      }
    }
  }
}
