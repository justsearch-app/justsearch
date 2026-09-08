/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.ipc.StatusResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lane F stage A item A12 — the in-process replacement for the chaos tier's three-part rig
 * ({@code WorkerProcessManager} + {@code MmfTestHarness} + {@code GrpcTestClient}).
 *
 * <p>Every one of those three existed to reach a <em>second process</em>: spawn it, discover the
 * port it published through the memory-mapped signal file, open a channel to that port. Since item
 * A9 the index half binds no port, so all three were unreachable by construction. This class is
 * what the surviving half of their job collapses to — publish a config, build an {@link EngineRoot},
 * call {@link EngineRoot#start} and drive the returned {@link KnowledgeClient}.
 *
 * <p><b>{@link #restart()} is a TEST ACTION, not a translation of anything production does.</b>
 * This javadoc previously called it "the honest translation of 'the worker restarted'", on the
 * grounds that the migration paths ended in a {@code restartWorkerCallback} wired to
 * {@code KnowledgeServer#initiateShutdown}. The stage-A checkpoint found that framing was the
 * problem: that callback set a flag and counted down a latch no production code read, so it
 * restarted nothing and reopened nothing. Calling {@code restart()} in a test right after a cutover
 * therefore did not simulate what the product does — it supplied, by hand, the one step the product
 * had silently stopped doing, and every assertion downstream passed on evidence the test itself had
 * manufactured.
 *
 * <p>That callback is deleted. What remains true is narrower and must be stated at each call site:
 * closing and re-opening this harness on the same data directory re-reads {@code state.json} the
 * way a genuinely restarted process would. So {@code restart()} is the right tool for asserting
 * <em>"after a restart, X"</em> — and the wrong tool for asserting that an operation took effect
 * without one. A test that wants the second thing must assert against the LIVE engine, before any
 * restart. See {@code EngineMigrationLifecycleTest}, which now does both explicitly.
 */
final class EngineTestHarness implements AutoCloseable {

  /** Base call deadline, matching {@code KnowledgeServerConfig.deadlineMs()}'s dev default. */
  private static final long DEADLINE_MS = 30_000L;

  private static final int BATCH_SIZE = 5_000;

  private final Path dataDir;
  private final Path indexBase;
  private final Map<String, String> extraConfig;

  private EngineRoot root;
  private KnowledgeClient client;
  private GpuSchedulingGauge gauge;

  private EngineTestHarness(Path dataDir, Path indexBase, Map<String, String> extraConfig) {
    this.dataDir = dataDir;
    this.indexBase = indexBase;
    this.extraConfig = new LinkedHashMap<>(extraConfig);
  }

  /** Publishes a config rooted at {@code dataDir} and starts the Engine's index half. */
  static EngineTestHarness start(Path dataDir) throws Exception {
    return start(dataDir, dataDir.resolve("index"), Map.of());
  }

  /** As {@link #start(Path)}, with extra resolved-config defaults (recovery policy, pacing, …). */
  static EngineTestHarness start(Path dataDir, Map<String, String> extraConfig) throws Exception {
    return start(dataDir, dataDir.resolve("index"), extraConfig);
  }

  /** As {@link #start(Path)}, with the index base path pointed somewhere other than the default. */
  static EngineTestHarness start(Path dataDir, Path indexBase, Map<String, String> extraConfig)
      throws Exception {
    EngineTestHarness harness = new EngineTestHarness(dataDir, indexBase, extraConfig);
    harness.open();
    return harness;
  }

  /**
   * Publishes the resolved config globally. Package-private and static so a test that needs a
   * SECOND index owner in this JVM (the index-base-path lock) can share the same config.
   */
  static void publishConfig(Path dataDir, Path indexBase, Map<String, String> extraConfig)
      throws Exception {
    Files.createDirectories(dataDir);
    Files.createDirectories(indexBase);
    ResolvedConfigBuilder builder =
        new ResolvedConfigBuilder()
            .contributeBaseSources()
            .putDefault("justsearch.data.dir", dataDir.toAbsolutePath().toString())
            .putDefault("justsearch.index.base_path", indexBase.toAbsolutePath().toString());
    for (Map.Entry<String, String> entry : extraConfig.entrySet()) {
      builder = builder.putDefault(entry.getKey(), entry.getValue());
    }
    ConfigStore.setGlobal(new ConfigStore(builder.build()));
  }

  private void open() throws Exception {
    publishConfig(dataDir, indexBase, extraConfig);
    gauge = new GpuSchedulingGauge();
    root = new EngineRoot(DEADLINE_MS, BATCH_SIZE);
    client = root.start(gauge, IpcTelemetry.noop());
  }

  /**
   * Closes and re-opens the Engine on the same data directory — what "the worker restarted" means
   * when there is only one process. Every generation pointer, switch buffer and job queue is on
   * disk, so the reopened Engine sees exactly what a respawned process saw.
   */
  void restart() throws Exception {
    close();
    open();
  }

  KnowledgeClient client() {
    return client;
  }

  Path dataDir() {
    return dataDir;
  }

  Path indexBase() {
    return indexBase;
  }

  StatusResponse status() {
    return client.getStatus();
  }

  /** The worker's coarse lifecycle state ({@code IDLE}, {@code INDEXING}, {@code PAUSED}, …). */
  String workerState() {
    return status().getCore().getState();
  }

  /**
   * The chaos tier's {@code GrpcTestClient.awaitIndexing} (GrpcTestClient.java:410-434), verbatim
   * in its condition: the queue has drained AND the index holds at least the expected count.
   */
  boolean awaitIndexed(long expectedDocCount, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      try {
        StatusResponse status = status();
        if (status.getCore().getQueueDepth() == 0
            && status.getCore().getDocCount() >= expectedDocCount) {
          return true;
        }
      } catch (RuntimeException stillSettling) {
        // A status call can fail while the index half swaps a writer; the loop re-reads.
      }
      Thread.sleep(200);
    }
    return false;
  }

  /** True once {@code marker} is findable. Polls, because the searcher refreshes asynchronously. */
  boolean awaitSearchable(String marker, long timeoutMs) throws InterruptedException {
    return awaitSearchCount(marker, timeoutMs, count -> count > 0);
  }

  /** True once {@code marker} is NOT findable — the delete/prune direction. */
  boolean awaitNotSearchable(String marker, long timeoutMs) throws InterruptedException {
    return awaitSearchCount(marker, timeoutMs, count -> count == 0);
  }

  private boolean awaitSearchCount(
      String marker, long timeoutMs, java.util.function.IntPredicate satisfied)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      try {
        if (satisfied.test(client.search(marker, 10).getResultsCount())) {
          return true;
        }
      } catch (RuntimeException stillSettling) {
        // Same: a search during a generation swap can fail; the loop re-reads.
      }
      Thread.sleep(250);
    }
    return false;
  }

  @Override
  public void close() {
    if (root != null) {
      root.close();
    }
    root = null;
    client = null;
    gauge = null;
  }
}
