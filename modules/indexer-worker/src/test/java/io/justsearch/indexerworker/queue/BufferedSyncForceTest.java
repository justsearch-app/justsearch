/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class BufferedSyncForceTest {
  @TempDir Path temporary;

  @ParameterizedTest
  @CsvSource({"true,false,false", "true,false,true", "false,true,false", "false,true,true"})
  void coalescingRetainsStrongestTraversalAndAttributionAcrossRestart(
      boolean firstForce, boolean secondForce, boolean incomingAttribution) throws Exception {
    Path db = temporary.resolve("queue.db");
    var firstOrigin = new JobQueue.EnqueueProvenance("user", "HTTP");
    var secondOrigin = incomingAttribution ? new JobQueue.EnqueueProvenance("agent", "TOOL") : null;
    SwitchBufferCapableQueue.SwitchBufferOp old;
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      assertTrue(queue.putSyncRootForGeneration("green", "sync:root",
          new SwitchBufferSyncRoot(temporary.toString(), firstForce, firstOrigin)));
      old = queue.listSwitchBufferOpsStrictForGeneration("green").getFirst();
      assertTrue(queue.putSyncRootForGeneration("green", "sync:root",
          new SwitchBufferSyncRoot(temporary.toString(), secondForce, secondOrigin)));
      assertEquals(0, queue.removeReplayedSwitchBufferOpsForGeneration("green", List.of(old)),
          "an older replay cannot acknowledge its replacement");
      assertTrue(queue.putSyncRootForGeneration("other", "sync:root",
          new SwitchBufferSyncRoot(temporary.toString(), false, null)));
    }
    try (var queue = new SqliteJobQueue(db)) {
      queue.open();
      var replacement = queue.listSwitchBufferOpsStrictForGeneration("green").getFirst();
      var request = SwitchBufferSyncRoot.decode(replacement.payload());
      assertTrue(request.force());
      assertEquals(incomingAttribution ? secondOrigin : firstOrigin, request.provenance());
      assertNotEquals(old.revision(), replacement.revision());
      assertTrue(replacement.acceptedOrder() > old.acceptedOrder());
      assertFalse(SwitchBufferSyncRoot.decode(
          queue.listSwitchBufferOpsStrictForGeneration("other").getFirst().payload()).force());
    }
  }

  @Test
  void malformedOrDifferentOperationsAreRetainedEvenForAttributedReplacement() throws Exception {
    try (var queue = new SqliteJobQueue(temporary.resolve("protected.db"))) {
      queue.open();
      var replacement = new SwitchBufferSyncRoot(temporary.toString(), true,
          new JobQueue.EnqueueProvenance("user", "HTTP"));
      for (String operation : List.of("SYNC_ROOT", "DELETE")) {
        assertTrue(queue.putSwitchBufferForGeneration("green", "same", operation, "malformed"));
        var before = queue.listSwitchBufferOpsStrictForGeneration("green");
        assertFalse(queue.putSyncRootForGeneration("green", "same", replacement));
        assertEquals(before, queue.listSwitchBufferOpsStrictForGeneration("green"));
      }
    }
  }
}
