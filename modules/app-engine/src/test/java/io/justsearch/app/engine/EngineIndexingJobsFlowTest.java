/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.ipc.IndexingJobsFrame;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lane F stage A item A7 — the indexing-jobs flow, end to end and in process.
 *
 * <p>{@code BoundedHandoffTest} pins the bound and its policy on the mechanism;
 * {@code RemoteIndexingJobsBridgeTest} pins the frame-to-delta translation on the head side. This
 * test is the join neither covers: that {@code EngineKnowledgeClient.subscribeIndexingJobs} really
 * opens the worker's SQLite change feed, that a snapshot frame arrives, that a job submitted
 * through the ingest port produces a delta on it, and that closing the handle stops it. A test that
 * stubbed either end would prove the two halves work against a fixture and say nothing about
 * whether they were connected — which is the failure mode a substrate change like A6/A7 has.
 */
@Timeout(180)
final class EngineIndexingJobsFlowTest {

  private EngineRoot root;

  @AfterEach
  void tearDown() {
    if (root != null) {
      root.close();
      root = null;
    }
  }

  @Test
  @DisplayName("frames reach a subscriber from the worker's change feed, and close stops them")
  void framesFlowFromTheChangeFeedAndCloseStopsThem(@TempDir Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    Path indexBase = dataDir.resolve("index");
    Files.createDirectories(dataDir);
    Files.createDirectories(indexBase);
    ConfigStore.setGlobal(
        new ConfigStore(
            new ResolvedConfigBuilder()
                .contributeBaseSources()
                .putDefault("justsearch.data.dir", dataDir.toAbsolutePath().toString())
                .putDefault("justsearch.index.base_path", indexBase.toAbsolutePath().toString())
                .build()));

    root =
        new EngineRoot(
            g -> new KnowledgeServer(WorkerConfig.load(), new InProcessWorkerSignalBus(g)),
            60_000L,
            5_000);
    KnowledgeClient client = root.start(new GpuSchedulingGauge(), IpcTelemetry.noop());

    List<IndexingJobsFrame> frames = new CopyOnWriteArrayList<>();
    CountDownLatch snapshot = new CountDownLatch(1);
    CountDownLatch delta = new CountDownLatch(1);
    AtomicReference<Throwable> error = new AtomicReference<>();

    KnowledgeClient.IndexingJobsStream stream =
        client.subscribeIndexingJobs(
            frame -> {
              frames.add(frame);
              if (frame.hasSnapshot()) {
                snapshot.countDown();
              } else if (frame.hasDelta()) {
                delta.countDown();
              }
            },
            error::set,
            () -> {});

    try {
      assertTrue(
          snapshot.await(60, TimeUnit.SECONDS),
          "subscribing must deliver the snapshot frame the head-side bridge hydrates from");

      // A submitted job is a row mutation, which is what the SQLite update hook turns into a delta.
      Path doc = tempDir.resolve("jobs-flow-probe.txt");
      Files.writeString(doc, "indexing jobs flow probe");
      client.submitBatch(List.of(doc));

      assertTrue(
          delta.await(60, TimeUnit.SECONDS),
          "a job submitted through the ingest port must produce a delta on the subscribed flow");
      assertTrue(error.get() == null, "the flow must not have failed: " + error.get());
    } finally {
      stream.close();
    }

    // Closing stops production: the worker's change-feed subscription is closed through the
    // FlowCancelSignal, so nothing further can arrive however much the queue drains afterwards.
    int atClose = frames.size();
    Files.writeString(tempDir.resolve("after-close.txt"), "not observed");
    client.submitBatch(List.of(tempDir.resolve("after-close.txt")));
    Thread.sleep(2_000);
    assertFalse(
        frames.size() > atClose,
        "a closed flow must deliver nothing further; saw " + (frames.size() - atClose) + " frames");
  }
}
