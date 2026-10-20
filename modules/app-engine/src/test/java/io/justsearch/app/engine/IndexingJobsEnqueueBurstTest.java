/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.server.KnowledgeServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** A real worker subscription must not buffer invisible enqueues behind a held delivery. */
@Timeout(180)
final class IndexingJobsEnqueueBurstTest {

  private EngineRoot root;

  @AfterEach
  void tearDown() {
    if (root != null) {
      root.admission().beginClosing();
      root.quiesceProducers();
      assertTrue(root.admission().awaitDrained(java.time.Duration.ofSeconds(30)));
      root.close();
      root = null;
    }
  }

  @Test
  void enqueueBurstDoesNotFillTheHandoffWithRowsOutsideTheWindow(@TempDir Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    EngineTestHarness.publishConfig(dataDir, dataDir.resolve("index"), java.util.Map.of());
    root = new EngineRoot(org.mockito.Mockito.mock(io.justsearch.app.api.operations.OperationStore.class),
        org.mockito.Mockito.mock(io.justsearch.app.api.operations.OperationAttemptRunner.class),
        (g, executors, ingestion, indexComponent, encoderComponent) -> new KnowledgeServer(executors,
            WorkerConfig.load(), new InProcessWorkerSignalBus(g),
            io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), ingestion, indexComponent, encoderComponent),
        60_000L, 5_000);
    KnowledgeClient client = root.start(new GpuSchedulingGauge(), IpcTelemetry.noop());
    var snapshotEntered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var error = new AtomicReference<Throwable>();
    var stream = client.subscribeIndexingJobs(frame -> {
      if (frame.hasSnapshot()) {
        snapshotEntered.countDown();
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> assertTrue(release.await(20, TimeUnit.SECONDS)));
      }
    }, error::set, () -> {}, TestEngineContexts.FOREGROUND);
    try {
      assertTrue(snapshotEntered.await(60, TimeUnit.SECONDS));
      var paths = new java.util.ArrayList<Path>();
      for (int i = 0; i < 2005; i++) {
        var file = tempDir.resolve("file-" + i + ".txt");
        Files.writeString(file, "Real queue burst fixture " + i);
        paths.add(file);
      }
      client.submitBatch(paths, TestEngineContexts.FOREGROUND);
      org.junit.jupiter.api.Assertions.assertNull(error.get(),
          "a committed enqueue burst must not occupy 2005 handoff slots for invisible PENDING inserts");
    } finally { release.countDown(); stream.close(); }
  }
}
