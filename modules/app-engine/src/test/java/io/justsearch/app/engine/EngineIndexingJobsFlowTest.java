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
        new EngineRoot(org.mockito.Mockito.mock(io.justsearch.app.api.operations.OperationStore.class),
            g -> new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(), WorkerConfig.load(), new InProcessWorkerSignalBus(g)),
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
            () -> {}, TestEngineContexts.FOREGROUND);

    try {
      assertTrue(
          snapshot.await(60, TimeUnit.SECONDS),
          "subscribing must deliver the snapshot frame the head-side bridge hydrates from");

      // A submitted job is a row mutation, which is what the SQLite update hook turns into a delta.
      Path doc = tempDir.resolve("jobs-flow-probe.txt");
      Files.writeString(doc, "indexing jobs flow probe");
      client.submitBatch(List.of(doc), TestEngineContexts.FOREGROUND);

      assertTrue(
          delta.await(60, TimeUnit.SECONDS),
          "a job submitted through the ingest port must produce a delta on the subscribed flow");
      assertTrue(error.get() == null, "the flow must not have failed: " + error.get());
    } finally {
      stream.close();
    }

    // Closing stops production: the worker's change-feed subscription is closed through the
    // FlowCancelSignal, so nothing further can arrive however much the queue drains afterwards.
    //
    // Review S9: this assertion is negative, and a negative assertion about a stream is satisfied
    // by everything being broken. If the submit below silently enqueued nothing — a path outside
    // the roots, a queue that rejected it, a change feed that had already fallen over — the closed
    // flow would see no frames for a reason that has nothing to do with closing it, and the test
    // would pass while proving nothing. That is the unreachable-seed-green shape.
    //
    // So a SECOND subscription is opened over the same submit. It is the positive control: it must
    // see the delta, which is what makes the first flow's silence attributable to its close.
    int atClose = frames.size();

    CountDownLatch controlDelta = new CountDownLatch(1);
    AtomicReference<Throwable> controlError = new AtomicReference<>();
    KnowledgeClient.IndexingJobsStream control =
        client.subscribeIndexingJobs(
            frame -> {
              if (frame.hasDelta()) {
                controlDelta.countDown();
              }
            },
            controlError::set,
            () -> {}, TestEngineContexts.FOREGROUND);
    try {
      Path afterClose = tempDir.resolve("after-close.txt");
      Files.writeString(afterClose, "observed by the control, not by the closed flow");
      client.submitBatch(List.of(afterClose), TestEngineContexts.FOREGROUND);

      assertTrue(
          controlDelta.await(60, TimeUnit.SECONDS),
          "the positive control must observe the submit — without it, the assertion below is"
              + " satisfied by a change feed that stopped working for any reason at all");
      assertTrue(controlError.get() == null, "the control flow must not have failed: " + controlError.get());
    } finally {
      control.close();
    }

    assertFalse(
        frames.size() > atClose,
        "a closed flow must deliver nothing further; saw " + (frames.size() - atClose) + " frames");
  }
}
