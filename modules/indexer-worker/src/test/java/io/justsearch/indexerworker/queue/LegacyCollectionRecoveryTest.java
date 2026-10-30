/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.api.operations.RecordedRootPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LegacyCollectionRecoveryTest {
  @TempDir Path temporary;

  @Test
  void frozenNullRootKeepsLegacyNullJobAndReceiptUnlabelled() throws Exception {
    Path file = Files.writeString(temporary.resolve("default.txt"), "default");
    var plan = new RecordedRootPlan("blue", List.of(
        new RecordedRootPlan.Root(temporary, null, false, false, List.of(), List.of())));
    try (var queue = new SqliteJobQueue(temporary.resolve("jobs.db"))) {
      queue.open();
      assertTrue(queue.enqueueAndBufferFileForGeneration("green", JobQueue.EnqueueEntry.stat(file), null));
      var receipt = queue.listSwitchBufferOpsStrict().getFirst();
      queue.recoverLegacyCollections("green", List.of(new RootBinding(temporary, "current-label")), plan);
      assertEquals(receipt, queue.listSwitchBufferOpsStrict().getFirst(),
          "a frozen default is authoritative even when today's root is labelled");
      assertNull(queue.pollPending(1).getFirst().collection());
    }
  }

  @Test
  void uncoveredPlanUsesCurrentOwnerAndKeepsDeletionOrder() throws Exception {
    Path file = Files.writeString(temporary.resolve("child.txt"), "child");
    var plan = new RecordedRootPlan("blue", List.of(new RecordedRootPlan.Root(
        temporary.resolve("elsewhere"), null, false, false, List.of(), List.of())));
    try (var queue = new SqliteJobQueue(temporary.resolve("jobs.db"))) {
      queue.open();
      assertTrue(queue.enqueueAndBufferFileForGeneration("green", JobQueue.EnqueueEntry.stat(file), null));
      assertTrue(queue.putSwitchBufferForGeneration("green", "collection:C", "DELETE_COLLECTION", "C"));
      var original = queue.listSwitchBufferOpsStrict();
      queue.recoverLegacyCollections("green", List.of(new RootBinding(temporary, "C")), plan);
      var repaired = queue.listSwitchBufferOpsStrict();
      assertEquals(original.stream().map(SwitchBufferCapableQueue.SwitchBufferOp::acceptedOrder).toList(),
          repaired.stream().map(SwitchBufferCapableQueue.SwitchBufferOp::acceptedOrder).toList());
      assertEquals("C", SwitchBufferUpsert.decode(repaired.getFirst().payload()).collection());
      assertEquals("C", queue.pollPending(1).getFirst().collection());
      assertEquals(original.getLast(), repaired.getLast(), "collection deletion retains its exact revision");
    }
  }

  @Test
  void explicitAndReservedLabelsKeepTheirReceiptsAndJobs() throws Exception {
    try (var queue = new SqliteJobQueue(temporary.resolve("jobs.db"))) {
      queue.open();
      for (String label : List.of("explicit", "agent-history", "justsearch-help")) {
        Path file = Files.writeString(temporary.resolve(label + ".txt"), label);
        assertTrue(queue.enqueueAndBufferFileForGeneration("green", JobQueue.EnqueueEntry.stat(file), label));
      }
      var original = queue.listSwitchBufferOpsStrict();
      queue.recoverLegacyCollections("green", List.of(new RootBinding(temporary, "C")), null);
      assertEquals(original, queue.listSwitchBufferOpsStrict());
      assertEquals(List.of("agent-history", "explicit", "justsearch-help"), queue.pollPending(3).stream()
          .map(JobQueue.IndexJob::collection).sorted().toList());
    }
  }
}
