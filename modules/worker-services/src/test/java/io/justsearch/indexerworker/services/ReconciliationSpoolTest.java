/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReconciliationSpoolTest {
  @TempDir Path root;

  @Test
  void globallyOrdersSpilledPathsAndDrainsOnlyBoundedBatches() throws Exception {
    var provenance = new JobQueue.EnqueueProvenance("agent", "AGENT_LOOP");
    var previous = new AtomicReference<String>("");
    var calls = new AtomicInteger();
    var delivered = new AtomicInteger();
    try (var spool = new ReconciliationSpool()) {
      for (int i = 5_000; i >= 0; i--) spool.add(root.resolve(i + ".md"), i);

      int added = spool.drain(2_000, provenance, () -> false, entries -> {
        calls.incrementAndGet();
        assertTrue(entries.size() <= 2_000);
        for (var entry : entries) {
          String normalized = PathNormalizer.normalizeKey(entry.path());
          assertTrue(previous.get().compareTo(normalized) < 0, "Order spans spool transactions and batches");
          previous.set(normalized);
          assertEquals(provenance, entry.provenance());
          assertEquals(Long.parseLong(entry.path().getFileName().toString().replace(".md", "")),
              entry.sizeBytes());
          delivered.incrementAndGet();
        }
        return entries.size();
      });

      assertEquals(5_001, added);
      assertEquals(5_001, delivered.get());
      assertEquals(3, calls.get());
    }
  }
}
