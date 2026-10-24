/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.ingest.IngestionSkipPolicy;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferUpsert;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyIngestionBoundaryTest {
  @TempDir Path directory;

  @AfterEach
  void resetPolicy() {
    IngestionSkipPolicy.resetToDefaults();
  }

  @Test
  void migratedPendingWatcherAndScanCannotReadExcludedContent() throws Exception {
    Path root = Files.createDirectory(directory.resolve("watched"));
    Path excluded = Files.createDirectory(root.resolve("private"));
    Path watchedFile = Files.writeString(excluded.resolve("watched.txt"), "private watcher content");
    Path scannedFile = Files.writeString(excluded.resolve("scanned.txt"), "private scan content");
    Path db = directory.resolve("upgrade.db");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(watchedFile,
          new JobQueue.EnqueueProvenance("system", "watcher")).withinRoot(root)));
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(scannedFile).withinRoot(root)),
          null, "legacy-scan");
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var statement = connection.createStatement()) {
      statement.execute("ALTER TABLE jobs DROP COLUMN ingestion_root");
      statement.execute("PRAGMA user_version = 21");
    }
    IngestionSkipPolicy.installResolved(new IngestionSkipPolicy(null, null, Set.of("private")));
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      var claims = queue.pollPending(10);
      assertEquals(2, claims.size());
      for (var claim : claims) {
        assertNull(claim.ingestionRoot());
        var admission = new WorkerIngestionAuthority().admit(claim);
        assertEquals(SourceAdmissionAction.SKIP_DONE, admission.action());
        assertEquals(IngestionReasonCodes.MISSING_INGESTION_BOUNDARY,
            admission.outcome().reasonCode());
      }
    }
  }

  @Test
  void historicalSwitchReplayCannotMintExplicitFilePermission() throws Exception {
    Path root = Files.createDirectory(directory.resolve("watched"));
    Path file = Files.writeString(Files.createDirectory(root.resolve("private")).resolve("notes.txt"),
        "private content");
    IngestionSkipPolicy.installResolved(new IngestionSkipPolicy(null, null, Set.of("private")));
    int payloadCase = 0;
    for (String payload : historicalPayloads(file)) {
      try (var queue = new SqliteJobQueue(directory.resolve("replay-" + payloadCase++ + ".db"))) {
        queue.open();
        var entry = SwitchBufferUpsert.decode(payload).entry();
        assertNull(entry.ingestionRoot());
        assertEquals(1, queue.enqueueEntries(List.of(entry)));
        var claim = queue.pollPending(1).getFirst();
        var admission = new WorkerIngestionAuthority().admit(claim);
        assertEquals(SourceAdmissionAction.SKIP_DONE, admission.action());
        assertTrue(queue.markClaimDone(claim, admission.outcome(), null));
        // A new explicit request states that this file, rather than an inferred volume, is scope.
        assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(file))));
        assertEquals(SourceAdmissionAction.ADMIT,
            new WorkerIngestionAuthority().admit(queue.pollPending(1).getFirst()).action());
      }
    }
  }

  @Test
  void historicalReplayPreservesTheExistingBoundaryAcrossRestart() throws Exception {
    Path root = Files.createDirectory(directory.resolve("watched"));
    Path file = Files.writeString(Files.createDirectory(root.resolve("private")).resolve("notes.txt"),
        "private content");
    IngestionSkipPolicy.installResolved(new IngestionSkipPolicy(null, null, Set.of("private")));
    int payloadCase = 0;
    for (Path boundary : List.of(root, file)) {
      for (String payload : historicalPayloads(file)) {
        Path db = directory.resolve("rooted-replay-" + payloadCase++ + ".db");
        try (var queue = new SqliteJobQueue(db)) {
          queue.open();
          assertEquals(1,
              queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(boundary))));
        }
        try (var queue = new SqliteJobQueue(db)) {
          queue.open();
          var historical = SwitchBufferUpsert.decode(payload).entry();
          assertNull(historical.ingestionRoot());
          assertEquals(1, queue.enqueueEntries(List.of(historical)));
          var claim = queue.pollPending(1).getFirst();
          assertEquals(boundary, claim.ingestionRoot(),
              "Replay must retain the prior admission boundary");
          var admission = new WorkerIngestionAuthority().admit(claim);
          assertEquals(
              boundary.equals(file) ? SourceAdmissionAction.ADMIT : SourceAdmissionAction.SKIP_DONE,
              admission.action());
          if (boundary.equals(root)) {
            assertEquals(IngestionReasonCodes.SKIPPED_TEMP_OR_SYSTEM,
                admission.outcome().reasonCode());
          }
        }
      }
    }
  }

  @Test
  void unwatchedScanRetainsBoundaryAcrossRestartAndMaintenanceReplacement() throws Exception {
    Path root = Files.createDirectories(directory.resolve("private").resolve("watched"));
    Path file = Files.writeString(root.resolve("notes.txt"), "public content");
    Path db = directory.resolve("unwatched.db");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(root)));
    }
    IngestionSkipPolicy.installResolved(new IngestionSkipPolicy(null, null, Set.of("private")));
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file)));
      var claim = queue.pollPending(1).getFirst();
      assertEquals(root, claim.ingestionRoot());
      assertEquals(SourceAdmissionAction.ADMIT, new WorkerIngestionAuthority().admit(claim).action());
    }
  }

  @Test
  void replacementCheckpointQuarantinesOnlyUpgradeDebtAndStopsRetryingAfterSevenDays() throws Exception {
    Path file = Files.writeString(directory.resolve("unowned.txt"), "not authorized");
    Path db = directory.resolve("replacement.db");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file)));
    }
    Path checkpoint = directory.resolve("upgrade-030-recovery.properties");
    Files.writeString(checkpoint, "format=1\nprepared=true\ndatabase=another-database\n");
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertTrue(queue.pollPending(1).isEmpty(), "unprepared upgrade work is held");
      String identity = queue.prepareRelease030Recovery(List.of());
      var recovery = io.justsearch.indexerworker.queue.Release030RecoveryState.load(db);
      recovery.prepared(List.of(), identity);
      assertTrue(recovery.finish(), "ordinary checkpoint cleanup cannot lose quarantine");
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
          var query = connection.createStatement(); var row = query.executeQuery("SELECT ingestion_root FROM jobs")) {
        assertTrue(row.next());
        assertNull(row.getString(1), "upgrade quarantine grants no ingestion boundary");
      }
      var claims = queue.pollPending(1);
      assertEquals(1, claims.size(), "prepared quarantine remains claimable after checkpoint cleanup");
      var claim = claims.getFirst();
      assertNull(claim.ingestionRoot(), "quarantine grants no ingestion boundary");
      assertTrue(claim.unitRevision().startsWith("release030-unscoped/"));
      var admission = new WorkerIngestionAuthority().admit(claim);
      assertEquals(SourceAdmissionAction.RETRYABLE_FAILURE, admission.action());
      assertTrue(queue.markClaimFailed(claim, admission.outcome(), null));
      assertEquals(1, queue.jobStateCounts().pendingCount());
      assertEquals(0, queue.jobStateCounts().doneCount());
    }
    // Advance the persisted retry origin, without a wall-clock wait or a migration-state edit.
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
        var update = connection.prepareStatement("UPDATE jobs SET retry_after=0,first_failed_at=?")) {
      update.setLong(1, System.currentTimeMillis()
          - io.justsearch.indexerworker.ingest.IngestionRetryLadder.MAX_RETRY_WINDOW_MS - 1_000);
      update.executeUpdate();
    }
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      var claim = queue.pollPending(1).getFirst();
      var admission = new WorkerIngestionAuthority().admit(claim);
      assertTrue(queue.markClaimFailed(claim, admission.outcome(), null));
      assertEquals(0, queue.jobStateCounts().pendingCount(), "upgrade debt cannot wait forever");
      assertEquals(0, queue.jobStateCounts().doneCount(), "unread debt is never reported done");
      assertEquals(1, queue.failureSummary().failedCount());
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
          var query = connection.createStatement(); var row = query.executeQuery("SELECT state FROM jobs")) {
        assertTrue(row.next());
        assertEquals("RETRY_EXHAUSTED", row.getString(1));
      }
      assertEquals(1, queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(file).withinRoot(file))));
      assertEquals(SourceAdmissionAction.ADMIT,
          new WorkerIngestionAuthority().admit(queue.pollPending(1).getFirst()).action());
    }
  }

  private static List<String> historicalPayloads(Path file) {
    return List.of(file.toString(), new SwitchBufferUpsert(file.toString(), null,
        new JobQueue.EnqueueProvenance("system", "watcher")).encode());
  }
}
