/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.indexerworker.loop.SourceContentHash;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class AdmissionHashLivenessTest {
  @TempDir Path temporary;

  @ParameterizedTest
  @ValueSource(strings = {"enqueue", "maintenance", "retry", "reappeared"})
  void blockedSourceReadDoesNotReserveClaimsOrCompletions(String route) throws Exception {
    Path completed = Files.writeString(temporary.resolve("completed.txt"), "complete");
    Path pending = Files.writeString(temporary.resolve("pending.txt"), "claim");
    Path slow = Files.writeString(temporary.resolve("slow.txt"), "slow source");
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var queue = new SqliteJobQueue(temporary.resolve("queue.db"));
        var executor = Executors.newFixedThreadPool(2)) {
      queue.open();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(completed).withinRoot(temporary)));
      var claim = queue.pollPending(1).getFirst();
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(pending).withinRoot(temporary)));
      var admission = executor.submit(() -> {
        try (var hashes = mockStatic(SourceContentHash.class, CALLS_REAL_METHODS)) {
          hashes.when(() -> SourceContentHash.sha256(eq(slow), any())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS), "test must release its source read");
            return call.callRealMethod();
          });
          var entry = JobQueue.EnqueueEntry.stat(slow).withinRoot(temporary);
          return switch (route) {
            case "enqueue" -> queue.enqueueEntries(List.of(entry));
            case "maintenance" -> queue.enqueueMaintenanceScanEntries(List.of(entry), null, null);
            case "retry" -> queue.reenqueue(entry).accepted();
            case "reappeared" -> queue.enqueueReappearedFile(entry, null) ? 1 : 0;
            default -> throw new AssertionError(route);
          };
        }
      });
      try {
        assertTrue(entered.await(10, TimeUnit.SECONDS), "admission must reach the real source read");
        var progress = executor.submit(() -> {
          assertTrue(queue.markClaimDone(claim, null, null));
          assertEquals(pending, queue.pollPending(1).getFirst().path());
        });
        assertDoesNotThrow(() -> progress.get(2, TimeUnit.SECONDS),
            "a blocked admission hash must not block completion or claiming on the same queue");
      } finally {
        release.countDown();
      }
      assertEquals(1, admission.get(10, TimeUnit.SECONDS));
    }
  }
}
