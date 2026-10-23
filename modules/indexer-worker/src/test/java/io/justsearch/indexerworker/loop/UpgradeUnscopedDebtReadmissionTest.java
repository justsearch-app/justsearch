/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.services.NoVolumeIdentityExtension;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A new queue revision without new scope cannot silently retire existing upgrade debt. */
@ExtendWith(NoVolumeIdentityExtension.class)
final class UpgradeUnscopedDebtReadmissionTest {
  @ParameterizedTest
  @ValueSource(strings = {"enqueue", "retry", "maintenance"})
  void unscopedReadmissionKeepsQuarantineUntilActualScopeArrives(String route, @TempDir Path temp)
      throws Exception {
    Path file = Files.writeString(temp.resolve("held.txt"), "unowned content");
    Path database = temp.resolve("jobs.db");
    try (var queue = new SqliteJobQueue(database)) {
      queue.open();
      var entry = JobQueue.EnqueueEntry.stat(file);
      queue.enqueueEntries(List.of(entry));
      // The durable, non-authorizing quarantine written by upgrade preparation.
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
          var update = connection.createStatement()) {
        update.executeUpdate("UPDATE jobs SET unit_revision='release030-unscoped/original'");
      }
      switch (route) {
        case "enqueue" -> assertEquals(1, queue.enqueueEntries(List.of(entry)));
        case "retry" -> assertEquals(1, queue.reenqueue(entry).accepted());
        case "maintenance" -> assertEquals(1, queue.enqueueMaintenanceScanEntries(List.of(entry), null, null));
        default -> throw new AssertionError(route);
      }
      var claim = queue.pollPending(1).getFirst();
      assertNotEquals("release030-unscoped/original", claim.unitRevision());
      assertNull(claim.ingestionRoot());
      assertFalse(queue.isRelease030ExactFile(file));
      var admission = new WorkerIngestionAuthority().admit(claim);
      assertEquals(SourceAdmissionAction.RETRYABLE_FAILURE, admission.action(),
          "a fresh revision without scope must retain upgrade debt rather than retire it");
      assertEquals(IngestionRetryPolicy.RETRY_WITH_BACKOFF, admission.outcome().retryPolicy());
      assertTrue(queue.markClaimFailed(claim, admission.outcome(), null));
      assertEquals(1, queue.jobStateCounts().pendingCount());
      assertEquals(0, queue.jobStateCounts().doneCount());
      queue.enqueueEntries(List.of(entry.withinRoot(file)));
      var scoped = queue.pollPending(1).getFirst();
      assertEquals(file, scoped.ingestionRoot());
      assertEquals(SourceAdmissionAction.ADMIT, new WorkerIngestionAuthority().admit(scoped).action());
    }
  }
}
