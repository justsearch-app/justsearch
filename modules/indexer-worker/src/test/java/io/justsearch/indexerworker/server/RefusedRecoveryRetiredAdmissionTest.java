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
 * does not hold is handed over through one ordinary pending A job that cleanup queues (round 4).
 */
final class RefusedRecoveryRetiredAdmissionTest extends LuceneExecutorTestBase {
  private static final String OPERATION = "01994180-0000-7000-8000-000000000197";
  private static final String GREEN = "green";

  @TempDir Path temp;

  // M1+: the accepted watcher update was retired unwritten while A holds the original bytes
  @Test
  void aRetiredWatcherUpdateIsQueuedOnA() throws Exception {
    try (var fixture = new Fixture(temp, "original A source")) {
      fixture.capture();
      fixture.edit("accepted watcher update");
      fixture.admitOnGreen();
      fixture.retire();
      assertTrue(fixture.drain(), "an ordinary A job now owns the accepted update");
      assertEquals(0, fixture.journalSize());
      fixture.assertQueuedOnA();
    }
  }

  // M1+: a restart re-admitted the changed file to the journal, then retirement skipped it
  @Test
  void aRetiredReadmissionOfAChangedFileIsQueuedOnA() throws Exception {
    try (var fixture = new Fixture(temp, "original A source")) {
      fixture.capture();
      fixture.edit("accepted watcher update");
      fixture.admitOnGreen();
      fixture.readmitByEnumeration();
      fixture.retire();
      assertTrue(fixture.drain(), "the re-admission's captured witness is not the file's bytes");
      assertEquals(0, fixture.journalSize());
      fixture.assertQueuedOnA();
    }
  }

  // M1+: a cleanup cut after queuing and re-entered later queues no second job
  @Test
  void aReenteredCleanupQueuesNoDuplicate() throws Exception {
    try (var fixture = new Fixture(temp, "original A source")) {
      fixture.capture();
      fixture.edit("accepted watcher update");
      fixture.admitOnGreen();
      fixture.retire();
      assertThrows(IllegalStateException.class, () -> fixture.drain(() -> {
        throw new IllegalStateException("cut before the journal acknowledgement");
      }));
      assertEquals(1, fixture.journalSize(), "the cut left the journal unacknowledged");
      // Read through its own connection: the queued row is durable for a restarted owner.
      String queued = fixture.assertQueuedOnA();
      assertTrue(fixture.drain());
      assertEquals(0, fixture.journalSize());
      assertEquals(queued, fixture.assertQueuedOnA(), "the re-entered cleanup queued nothing new");
    }
  }

  // M1+: the accepted file is gone from disk: A's ordinary removal job owns it
  @Test
  void aRetiredUpdateOfADeletedFileQueuesItsRemovalOnA() throws Exception {
    try (var fixture = new Fixture(temp, "original A source")) {
      fixture.capture();
      fixture.edit("accepted watcher update");
      fixture.admitOnGreen();
      fixture.retire();
      Files.delete(fixture.file);
      assertTrue(fixture.drain(), "A's removal job owns the accepted update");
      assertEquals(0, fixture.journalSize());
      fixture.assertQueuedOnA();
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
        Files.setPosixFilePermissions(fixture.file,
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
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

    /** The file's row is an ordinary pending A job; returns its revision. */
    String assertQueuedOnA() throws Exception {
      try (var connection = java.sql.DriverManager.getConnection(
              "jdbc:sqlite:" + temp.resolve("jobs.db").toAbsolutePath());
          var query = connection.prepareStatement("SELECT state, scan_id, walk_seen_epoch, "
              + "planned_source_sha256, unit_revision FROM jobs WHERE path = ?")) {
        query.setString(1, file.toString());
        try (var row = query.executeQuery()) {
          assertTrue(row.next(), "the file has a queue row");
          assertEquals("PENDING", row.getString("state"), "A's writer runs it after the restart");
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
