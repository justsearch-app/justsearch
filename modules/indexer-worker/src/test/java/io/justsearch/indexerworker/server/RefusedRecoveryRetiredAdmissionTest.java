/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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
        // Round 8: the back-off is the failure of the state it ran on; the unreadable state gets
        // its own one admission at once, not after the back-off.
        assertNotEquals(queued, fixture.assertQueuedOnA(), "the unreadable state's admission");
        fixture.aWriterFindsTheFileUnreadable();
        assertFalse(fixture.drain(), "its failure is no effect, and it is not admitted again");
        assertEquals(0, fixture.queue.jobStateCountsStrict().pendingReadyCount(),
            "only the back-off remains");
      } finally {
        fixture.makeReadable();
      }
      assertFalse(fixture.drain(), "the readable state is admitted at once");
      fixture.assertQueuedOnA();
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
        assertEquals(1, fixture.queue.jobStateCountsStrict().pendingCount(),
            "the file's one admission for this state");
        assertFalse(fixture.drain(), "still unproved");
        assertEquals(1, fixture.queue.jobStateCountsStrict().pendingCount(), "and only one");
      } finally {
        fixture.makeReadable();
      }
    }
  }

  // M8 (round 6, P1): A durably holds a revision later than the accepted one, and a rescan
  // after the restart found it unchanged, so A's row records no write: A's index decides.
  @Test
  void aLaterRevisionAHoldsSettlesThoughARescanFoundItUnchanged() throws Exception {
    try (var fixture = new Fixture(temp, "original A source")) {
      fixture.capture();
      fixture.edit("accepted watcher update");
      fixture.admitOnGreen();
      fixture.retire();
      fixture.edit("later revision");
      fixture.admitOnA();
      fixture.aWriterCommitsTheFile();
      fixture.admitOnA();
      fixture.aWriterFindsTheFileUnchanged();
      assertTrue(fixture.drain(), "A holds the file's current bytes");
      assertEquals(0, fixture.journalSize());
      assertEquals(0, fixture.queue.jobStateCountsStrict().pendingCount(), "nothing was queued");
    }
  }

  // M8 (round 6, P1): the saved FAILED/RUNNING store. Its journal witnesses the captured bytes;
  // A got the file's offline change before the upgrade, and the upgraded boot's rescan found
  // it unchanged. One restart settles it.
  @Test
  void aSavedFailedRunningStoreSettlesWhereAHoldsTheCurrentBytes() throws Exception {
    try (var fixture = new Fixture(temp, "original A source")) {
      fixture.capture();
      fixture.readmitByEnumeration();
      fixture.retire();
      fixture.edit("offline changed source");
      fixture.admitOnA();
      fixture.aWriterCommitsTheFile();
      fixture.restart();
      fixture.admitOnA();
      fixture.aWriterFindsTheFileUnchanged();
      assertTrue(fixture.drain(), "A holds the file's current bytes");
      assertEquals(0, fixture.journalSize());
      assertEquals(0, fixture.queue.jobStateCountsStrict().pendingCount(), "nothing was queued");
    }
  }

  // round 6, P2: the accepted file became a link. A's job ends it by A's policy (not a regular
  // file); that is A's committed effect, so cleanup admits it once and settles.
  @Test
  void aSymlinkReplacementAdmitsOnceAndSettlesByAsPolicy() throws Exception {
    try (var fixture = new Fixture(temp, "original A source")) {
      fixture.capture();
      fixture.edit("accepted watcher update");
      fixture.admitOnGreen();
      fixture.retire();
      Path target = Files.writeString(temp.resolve("target.txt"), "link target");
      Files.delete(fixture.file);
      Files.createSymbolicLink(fixture.file, target);
      assertFalse(fixture.drain(), "A has not answered the link yet");
      fixture.assertQueuedOnA();
      fixture.aWriterEndsTheFile(IngestionOutcomeClass.SKIPPED_POLICY,
          IngestionReasonCodes.NON_REGULAR_SOURCE, true);
      assertTrue(fixture.drain(), "A's policy retired the link");
      assertEquals(0, fixture.journalSize());
      assertEquals(0, fixture.queue.jobStateCountsStrict().pendingCount(), "admitted once");
    }
  }

  // round 6: too large for A's policy is A's answer to the file as it is
  @Test
  void aTooLargeFileSettlesByAsPolicy() throws Exception {
    try (var fixture = new Fixture(temp, "original A source")) {
      fixture.capture();
      fixture.edit("accepted watcher update");
      fixture.admitOnGreen();
      fixture.retire();
      assertFalse(fixture.drain());
      fixture.assertQueuedOnA();
      fixture.aWriterFailsTheFile(IngestionOutcomeClass.BUDGET_EXCEEDED,
          IngestionReasonCodes.INPUT_TOO_LARGE);
      assertTrue(fixture.drain(), "A's size policy ended the file");
      assertEquals(0, fixture.journalSize());
    }
  }

  // round 6, rule 3: an outcome that settles nothing is not re-admitted for the same file
  // state; a changed file admits it once more. Round 9: a restart does not, since the queue row
  // records the admission.
  @Test
  void anUnsettledOutcomeIsAdmittedOncePerFileState() throws Exception {
    try (var fixture = new Fixture(temp, "original A source")) {
      fixture.capture();
      fixture.edit("accepted watcher update");
      fixture.admitOnGreen();
      fixture.retire();
      assertFalse(fixture.drain());
      String first = fixture.assertQueuedOnA();
      fixture.aWriterEndsTheFile(IngestionOutcomeClass.SKIPPED_POLICY,
          IngestionReasonCodes.MISSING_INGESTION_BOUNDARY, false);
      for (int attempt = 0; attempt < 3; attempt++) {
        assertFalse(fixture.drain(), "a retired row is no effect");
      }
      assertEquals(0, fixture.queue.jobStateCountsStrict().pendingCount(),
          "the same file state is not admitted again");
      assertEquals(1, fixture.journalSize());
      fixture.edit("changed again");
      assertFalse(fixture.drain());
      assertNotEquals(first, fixture.assertQueuedOnA(), "the changed file is admitted once more");
      fixture.aWriterEndsTheFile(IngestionOutcomeClass.SKIPPED_POLICY,
          IngestionReasonCodes.MISSING_INGESTION_BOUNDARY, false);
      assertFalse(fixture.drain());
      assertEquals(0, fixture.queue.jobStateCountsStrict().pendingCount());
      fixture.restart();
      assertFalse(fixture.drain());
      assertEquals(0, fixture.queue.jobStateCountsStrict().pendingCount(),
          "a restart keeps the admission of the same file state");
    }
  }

  // round 6, preserved: an I/O failure ends nothing; the journal waits and nothing is re-queued
  @Test
  void anIoFailedFileStillWaits() throws Exception {
    try (var fixture = new Fixture(temp, "original A source")) {
      fixture.capture();
      fixture.edit("accepted watcher update");
      fixture.admitOnGreen();
      fixture.retire();
      assertFalse(fixture.drain());
      fixture.assertQueuedOnA();
      fixture.aWriterFailsTheFile(IngestionOutcomeClass.IO_FAILED, IngestionReasonCodes.IO_ERROR);
      for (int attempt = 0; attempt < 3; attempt++) {
        assertFalse(fixture.drain(), "an I/O failure is no effect");
      }
      assertEquals(1, fixture.journalSize(), "the journal is kept");
      assertEquals(0, fixture.queue.jobStateCountsStrict().pendingCount(), "nothing is re-queued");
    }
  }

  // round 7, F1: A committed H2 from a successful row; while the Engine was down the file
  // changed to H3 and became unreadable. Only A's index against the current bytes settles a
  // file, so nothing does: the file gets its one admission and cleanup keeps Green.
  @Test
  void anEarlierCommitNeverSettlesChangedBytesThatCannotBeRead() throws Exception {
    try (var fixture = new Fixture(temp, "original A source")) {
      fixture.capture();
      fixture.edit("accepted watcher update H2");
      fixture.admitOnGreen();
      fixture.retire();
      assertFalse(fixture.drain());
      fixture.assertQueuedOnA();
      fixture.aWriterCommitsTheFile();
      fixture.edit("H3, written while the Engine was down");
      Files.setPosixFilePermissions(fixture.file, java.util.Set.of());
      try {
        assumeFalse(Files.isReadable(fixture.file), "this user can read any file");
        fixture.restart();
        for (int attempt = 0; attempt < 3; attempt++) {
          assertFalse(fixture.drain(), "A holds H2; the current bytes cannot be compared");
        }
        assertEquals(1, fixture.journalSize(), "the journal is kept");
        assertEquals(1, fixture.queue.jobStateCountsStrict().pendingCount(),
            "one admission for the unreadable file state");
      } finally {
        fixture.makeReadable();
      }
    }
  }

  // round 7, F2: a folder replaced the accepted file. A's policy retires a source that is not a
  // regular file; once A no longer holds the path, cleanup settles without any file bytes.
  @Test
  void aFolderReplacementSettlesOnceAsPolicyRetiresTheOldText() throws Exception {
    try (var fixture = new Fixture(temp, "original A source")) {
      fixture.capture();
      fixture.edit("accepted watcher update");
      fixture.admitOnGreen();
      fixture.retire();
      Files.delete(fixture.file);
      Files.createDirectory(fixture.file);
      assertFalse(fixture.drain(), "A still serves the replaced file's text");
      fixture.assertQueuedOnA();
      fixture.aWriterEndsTheFile(IngestionOutcomeClass.SKIPPED_POLICY,
          IngestionReasonCodes.NON_REGULAR_SOURCE, true);
      assertTrue(fixture.drain(), "A's policy retired the folder's old text");
      assertEquals(0, fixture.journalSize());
      assertEquals(0, fixture.queue.jobStateCountsStrict().pendingCount(), "admitted once");
    }
  }

  // round 7, F2: outside a migration A retires the text of a source that became a link, so a
  // policy outcome that left A serving it is no settlement
  @Test
  void aPolicyOutcomeThatLeavesAServingTheReplacedTextDoesNotSettle() throws Exception {
    try (var fixture = new Fixture(temp, "original A source")) {
      fixture.capture();
      fixture.edit("accepted watcher update");
      fixture.admitOnGreen();
      fixture.retire();
      Path target = Files.writeString(temp.resolve("target.txt"), "link target");
      Files.delete(fixture.file);
      Files.createSymbolicLink(fixture.file, target);
      assertFalse(fixture.drain());
      fixture.assertQueuedOnA();
      fixture.aWriterEndsTheFile(IngestionOutcomeClass.SKIPPED_POLICY,
          IngestionReasonCodes.NON_REGULAR_SOURCE, false);
      for (int attempt = 0; attempt < 3; attempt++) {
        assertFalse(fixture.drain(), "A still serves the replaced file's text");
      }
      assertEquals(1, fixture.journalSize(), "the journal is kept");
    }
  }

  // P1 (round 8): "not found" under an unavailable folder is uncertainty, never a delete. The
  // accepted new file stays owed to A, with nothing queued, until its folder returns.
  @Test
  void anAcceptedNewFileUnderAnUnavailableFolderIsKeptUntilItReturns() throws Exception {
    try (var fixture = new Fixture(temp, "accepted new file", "volume")) {
      fixture.forgetOnA();
      fixture.capture();
      fixture.admitOnGreen();
      fixture.retire();
      Path folder = fixture.file.getParent();
      Path away = temp.resolve("volume-away");
      Files.move(folder, away);
      try {
        assertFalse(fixture.drain(), "not found under an unavailable folder proves nothing");
        assertFalse(fixture.drain(), "it stays unproved");
        assertEquals(1, fixture.journalSize(), "the accepted file stays owed to A");
        assertEquals(0, fixture.queue.jobStateCountsStrict().pendingCount(),
            "nothing is admitted on uncertainty");
      } finally {
        Files.move(away, folder);
      }
      assertFalse(fixture.drain(), "the returned file gets its admission on A");
      fixture.assertQueuedOnA();
      fixture.aWriterCommitsTheFile();
      assertTrue(fixture.drain(), "A holds the accepted bytes");
      assertEquals(0, fixture.journalSize());
    }
  }

  // P2 (round 8): a failure belongs to the file state it was for. A's job failed terminally on
  // the accepted bytes; the file is then deleted, and that new state gets its own admission.
  @Test
  void aTerminalFailureOnEarlierBytesDoesNotHoldBackTheDeletedState() throws Exception {
    try (var fixture = new Fixture(temp, "original A source")) {
      fixture.capture();
      fixture.edit("accepted watcher update");
      fixture.admitOnGreen();
      fixture.retire();
      assertFalse(fixture.drain());
      fixture.assertQueuedOnA();
      fixture.aWriterFailsTheFile(IngestionOutcomeClass.PARSER_FAILED,
          IngestionReasonCodes.PARSER_FAILED);
      assertFalse(fixture.drain(), "a failure is no effect");
      assertEquals(0, fixture.queue.jobStateCountsStrict().pendingCount(),
          "the failed state is not admitted again");
      Files.delete(fixture.file);
      assertFalse(fixture.drain(), "A still serves the deleted file's text");
      fixture.assertQueuedOnA();
      fixture.aWriterRemovesTheFile();
      assertTrue(fixture.drain(), "A shows the removal");
      assertEquals(0, fixture.journalSize());
    }
  }

  // P1 (round 9): only a confirmed path state settles. The accepted new file's folder is
  // replaced by an unreadable one, with a folder at the file's path: uncertainty, never a delete.
  @Test
  void aFolderSeenThroughAnUnreadableReplacedFolderSettlesNothing() throws Exception {
    try (var fixture = new Fixture(temp, "accepted new file", "volume")) {
      fixture.forgetOnA();
      fixture.capture();
      fixture.admitOnGreen();
      fixture.retire();
      Path folder = fixture.file.getParent();
      Path away = temp.resolve("volume-away");
      Files.move(folder, away);
      Files.createDirectories(fixture.file);
      Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("--x------"));
      try {
        assumeFalse(Files.isReadable(folder), "this user can read any folder");
        assertTrue(Files.isDirectory(fixture.file), "a folder stands at the file's path");
        assertFalse(fixture.drain(), "a path seen through an unreadable folder proves nothing");
        assertFalse(fixture.drain(), "it stays unproved");
        assertEquals(1, fixture.journalSize(), "the accepted file stays owed to A");
        assertEquals(0, fixture.queue.jobStateCountsStrict().pendingCount(),
            "nothing is admitted on uncertainty");
      } finally {
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
        Files.delete(fixture.file);
        Files.delete(folder);
        Files.move(away, folder);
      }
      assertFalse(fixture.drain(), "the returned file gets its admission on A");
      fixture.assertQueuedOnA();
      fixture.aWriterCommitsTheFile();
      assertTrue(fixture.drain(), "A holds the accepted bytes");
      assertEquals(0, fixture.journalSize());
    }
  }

  // P1 (round 9): under a watched root, every folder from the root down to the path must be
  // readable for its state to count. An unreadable subfolder hides what is at the path.
  @Test
  void aFolderSeenThroughAnUnreadableSubfolderOfTheRootSettlesNothing() throws Exception {
    try (var fixture = new Fixture(temp, "accepted new file", "root/sub")) {
      Path root = temp.resolve("root");
      Path sub = fixture.file.getParent();
      fixture.forgetOnA();
      fixture.capture();
      fixture.admitOnGreenWithin(root);
      fixture.retire();
      Path away = temp.resolve("sub-away");
      Files.move(sub, away);
      Files.createDirectories(fixture.file);
      Files.setPosixFilePermissions(sub, PosixFilePermissions.fromString("--x------"));
      try {
        assumeFalse(Files.isReadable(sub), "this user can read any folder");
        assertFalse(fixture.drain(), "the root vouches, but the subfolder cannot be read");
        assertEquals(1, fixture.journalSize(), "the accepted file stays owed to A");
        assertEquals(0, fixture.queue.jobStateCountsStrict().pendingCount(),
            "nothing is admitted on uncertainty");
      } finally {
        Files.setPosixFilePermissions(sub, PosixFilePermissions.fromString("rwx------"));
        Files.delete(fixture.file);
        Files.delete(sub);
        Files.move(away, sub);
      }
      assertFalse(fixture.drain(), "the returned file gets its admission on A");
      fixture.assertQueuedOnA();
      fixture.aWriterCommitsTheFile();
      assertTrue(fixture.drain(), "A holds the accepted bytes");
      assertEquals(0, fixture.journalSize());
    }
  }

  // P2 (round 9): a restart keeps an existing admission for the same file state: the same job
  // and the same retry deadline. The queue row is the record, so a new process rebuilds it.
  @Test
  void aRestartKeepsTheBackedOffAdmissionOfAnUnchangedState() throws Exception {
    try (var fixture = new Fixture(temp, "original A source")) {
      fixture.capture();
      fixture.edit("accepted watcher update");
      fixture.admitOnGreen();
      fixture.retire();
      assertFalse(fixture.drain());
      fixture.assertQueuedOnA();
      Files.setPosixFilePermissions(fixture.file, java.util.Set.of());
      try {
        assumeFalse(Files.isReadable(fixture.file), "this user can read any file");
        fixture.aWriterFindsTheFileUnreadable();
        assertFalse(fixture.drain(), "a failed A job is no effect");
        fixture.assertQueuedOnA(); // The unreadable state's one admission.
        fixture.aWriterFindsTheFileUnreadable();
        List<Object> backedOff = fixture.job();
        assertNotNull(backedOff.get(3), "the job waits out a back-off");
        fixture.restart();
        assertFalse(fixture.drain(), "still no effect");
        assertFalse(fixture.drain());
        assertEquals(backedOff, fixture.job(), "the same job keeps its retry deadline");
        assertEquals(1, fixture.journalSize(), "the journal is kept");
      } finally {
        fixture.makeReadable();
      }
      assertFalse(fixture.drain(), "the readable state is admitted at once");
      fixture.assertQueuedOnA();
      fixture.aWriterCommitsTheFile();
      assertTrue(fixture.drain(), "A holds the accepted bytes");
      assertEquals(0, fixture.journalSize());
    }
  }

  private final class Fixture implements AutoCloseable {
    final Path file;
    final String original;
    SqliteJobQueue queue;
    final RunningRuntime source;

    /** The live Worker's root authority, as the Engine's cleanup is given. */
    final WorkerIngestService publisher = VouchingRootAuthority.publisher();

    Fixture(Path dir, String content) throws Exception {
      this(dir, content, "");
    }

    /** The watched file in {@code folder} beneath {@code dir}, so its folder can go away. */
    Fixture(Path dir, String content, String folder) throws Exception {
      Files.createDirectories(dir.resolve(folder));
      file = Files.writeString(dir.resolve(folder).resolve("watched.txt"), content).toAbsolutePath();
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

    /** A never held the file: it was accepted new during the migration. */
    void forgetOnA() throws Exception {
      source.indexingCoordinator().deleteByIdAndChunks(file.toString());
      source.commitOps().commitAndTrack(CommitReason.DRAIN);
      source.commitOps().maybeRefreshBlocking();
    }

    /** A new Engine process: the queue reopens and cleanup remembers nothing it admitted. */
    void restart() throws Exception {
      queue.close();
      queue = new SqliteJobQueue(temp.resolve("jobs.db"));
      queue.open();
    }

    /** An ordinary A admission of the file, as a watcher event or a rescan makes. */
    void admitOnA() {
      assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file)), "default"));
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

    /** A watcher admission of the file under the watched root {@code root}. */
    void admitOnGreenWithin(Path root) {
      assertTrue(queue.enqueueAndBufferFileForGeneration(
          GREEN, JobQueue.EnqueueEntry.stat(file).withinRoot(root), null, null));
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
      // A new context per attempt, as the Engine's cleanup builds.
      return KnowledgeServerMigrationOps.drainRefusedCandidateOnSource(
          new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
              queue, source, null, IndexingPacing.unthrottled(), temp, temp, new ObjectMapper(),
              () -> false, () -> true, LoggerFactory.getLogger(getClass()),
              System.nanoTime() + TimeUnit.SECONDS.toNanos(1), GREEN).withPublisher(publisher),
          beforeAck);
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

    /** A's writer claims the job and ends it without a write; {@code retire} drops A's copy. */
    void aWriterEndsTheFile(IngestionOutcomeClass outcome, String reason, boolean retire)
        throws Exception {
      var claim = onlyClaim();
      if (retire) source.indexingCoordinator().deleteByIdAndChunks(file.toString());
      queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(claim, null)),
          IngestionOutcome.of(outcome, reason, IngestionRetryPolicy.NONE));
    }

    /** A's writer claims the job and fails it terminally. */
    void aWriterFailsTheFile(IngestionOutcomeClass outcome, String reason) {
      var claim = onlyClaim();
      assertTrue(queue.markClaimFailed(claim,
          IngestionOutcome.of(outcome, reason, IngestionRetryPolicy.NONE), null));
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

    void makeReadable() throws Exception {
      Files.setPosixFilePermissions(file,
          PosixFilePermissions.fromString("rw-------"));
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

    /** The file's job as the queue holds it: its identity, attempts and retry deadline. */
    List<Object> job() throws Exception {
      try (var connection = java.sql.DriverManager.getConnection(
              "jdbc:sqlite:" + temp.resolve("jobs.db").toAbsolutePath());
          var query = connection.prepareStatement("SELECT unit_revision, state, attempts,"
              + " retry_after, first_failed_at FROM jobs WHERE path = ?")) {
        query.setString(1, file.toString());
        try (var row = query.executeQuery()) {
          assertTrue(row.next(), "the file has a queue row");
          return java.util.Arrays.asList(row.getString(1), row.getString(2), row.getLong(3),
              row.getObject(4), row.getObject(5));
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
